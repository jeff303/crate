/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import com.carrotsearch.hppc.ObjectHashSet;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.DelegatingAnalyzerWrapper;
import org.elasticsearch.Assertions;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.analysis.IndexAnalyzers;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.indices.InvalidTypeNameException;
import org.elasticsearch.indices.mapper.MapperRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Collections.emptyMap;

public class MapperService extends AbstractIndexComponent implements Closeable {

    /**
     * The reason why a mapping is being merged.
     */
    public enum MergeReason {
        /**
         * Create or update a mapping.
         */
        MAPPING_UPDATE,
        /**
         * Recovery of an existing mapping, for instance because of a restart,
         * if a shard was moved to a different node or for administrative
         * purposes.
         */
        MAPPING_RECOVERY;
    }

    public static final String SINGLE_MAPPING_NAME = "_doc";
    public static final Setting<Long> INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING =
        Setting.longSetting("index.mapping.total_fields.limit", 1000L, 0, Property.Dynamic, Property.IndexScope);
    public static final Setting<Long> INDEX_MAPPING_DEPTH_LIMIT_SETTING =
            Setting.longSetting("index.mapping.depth.limit", 20L, 1, Property.Dynamic, Property.IndexScope);

    //TODO this needs to be cleaned up: _timestamp and _ttl are not supported anymore, _field_names, _seq_no, _version and _source are
    //also missing, not sure if on purpose. See IndicesModule#getMetadataMappers
    private static final ObjectHashSet<String> META_FIELDS = ObjectHashSet.from(
            "_id", "_type", "_routing", "_index",
            "_size", "_timestamp", "_ttl", IgnoredFieldMapper.NAME
    );

    private final IndexAnalyzers indexAnalyzers;

    private volatile DocumentMapper mapper;
    private volatile FieldTypeLookup fieldTypes;
    private volatile Map<String, ObjectMapper> fullPathObjectMappers = emptyMap();

    private final DocumentMapperParser documentParser;

    private final MapperAnalyzerWrapper indexAnalyzer;
    private final MapperAnalyzerWrapper searchAnalyzer;
    private final MapperAnalyzerWrapper searchQuoteAnalyzer;

    final MapperRegistry mapperRegistry;

    public MapperService(IndexSettings indexSettings,
                         IndexAnalyzers indexAnalyzers,
                         NamedXContentRegistry xContentRegistry,
                         MapperRegistry mapperRegistry,
                         Supplier<QueryShardContext> queryShardContextSupplier) {
        super(indexSettings);
        this.indexAnalyzers = indexAnalyzers;
        this.fieldTypes = new FieldTypeLookup();
        this.documentParser = new DocumentMapperParser(
            indexSettings,
            this,
            xContentRegistry,
            mapperRegistry,
            queryShardContextSupplier
        );
        this.indexAnalyzer = new MapperAnalyzerWrapper(indexAnalyzers.getDefaultIndexAnalyzer(), MappedFieldType::indexAnalyzer);
        this.searchAnalyzer = new MapperAnalyzerWrapper(indexAnalyzers.getDefaultSearchAnalyzer(), MappedFieldType::searchAnalyzer);
        this.searchQuoteAnalyzer = new MapperAnalyzerWrapper(indexAnalyzers.getDefaultSearchQuoteAnalyzer(), MappedFieldType::searchQuoteAnalyzer);
        this.mapperRegistry = mapperRegistry;
    }

    public IndexAnalyzers getIndexAnalyzers() {
        return this.indexAnalyzers;
    }

    public DocumentMapperParser documentMapperParser() {
        return this.documentParser;
    }

    /**
     * Parses the mappings (formatted as JSON) into a map
     */
    public static Map<String, Object> parseMapping(NamedXContentRegistry xContentRegistry, String mappingSource) throws Exception {
        try (XContentParser parser = XContentType.JSON.xContent()
                .createParser(xContentRegistry, LoggingDeprecationHandler.INSTANCE, mappingSource)) {
            return parser.map();
        }
    }

    /**
     * Update mapping by only merging the metadata that is different between received and stored entries
     */
    public boolean updateMapping(final IndexMetadata currentIndexMetadata, final IndexMetadata newIndexMetadata) throws IOException {
        assert newIndexMetadata.getIndex().equals(index()) : "index mismatch: expected " + index() + " but was " + newIndexMetadata.getIndex();
        // go over and add the relevant mappings (or update them)
        final Set<String> existingMappers = new HashSet<>();
        if (mapper != null) {
            existingMappers.add(mapper.type());
        }
        final Map<String, DocumentMapper> updatedEntries;
        try {
            // only update entries if needed
            updatedEntries = internalMerge(newIndexMetadata, MergeReason.MAPPING_RECOVERY, true);
        } catch (Exception e) {
            logger.warn(() -> new ParameterizedMessage("[{}] failed to apply mappings", index()), e);
            throw e;
        }

        boolean requireRefresh = false;

        assertMappingVersion(currentIndexMetadata, newIndexMetadata, updatedEntries);

        for (DocumentMapper documentMapper : updatedEntries.values()) {
            String mappingType = documentMapper.type();
            CompressedXContent incomingMappingSource = newIndexMetadata.mapping().source();

            String op = existingMappers.contains(mappingType) ? "updated" : "added";
            if (logger.isDebugEnabled() && incomingMappingSource.compressed().length < 512) {
                logger.debug("[{}] {} mapping [{}], source [{}]", index(), op, mappingType, incomingMappingSource.string());
            } else if (logger.isTraceEnabled()) {
                logger.trace("[{}] {} mapping [{}], source [{}]", index(), op, mappingType, incomingMappingSource.string());
            } else {
                logger.debug("[{}] {} mapping [{}] (source suppressed due to length, use TRACE level if needed)", index(), op, mappingType);
            }

            // refresh mapping can happen when the parsing/merging of the mapping from the metadata doesn't result in the same
            // mapping, in this case, we send to the master to refresh its own version of the mappings (to conform with the
            // merge version of it, which it does when refreshing the mappings), and warn log it.
            if (documentMapper().mappingSource().equals(incomingMappingSource) == false) {
                logger.debug("[{}] parsed mapping [{}], and got different sources\noriginal:\n{}\nparsed:\n{}", index(), mappingType,
                    incomingMappingSource, documentMapper().mappingSource());

                requireRefresh = true;
            }
        }

        return requireRefresh;
    }

    private void assertMappingVersion(
            final IndexMetadata currentIndexMetadata,
            final IndexMetadata newIndexMetadata,
            final Map<String, DocumentMapper> updatedEntries) {
        if (Assertions.ENABLED
                && currentIndexMetadata != null
                && currentIndexMetadata.getCreationVersion().onOrAfter(Version.ES_V_6_5_1)) {
            if (currentIndexMetadata.getMappingVersion() == newIndexMetadata.getMappingVersion()) {
                // if the mapping version is unchanged, then there should not be any updates and all mappings should be the same
                assert updatedEntries.isEmpty() : updatedEntries;
                MappingMetadata mapping = newIndexMetadata.mapping();
                if (mapping != null) {
                    final CompressedXContent currentSource = currentIndexMetadata.mapping().source();
                    final CompressedXContent newSource = mapping.source();
                    assert currentSource.equals(newSource) :
                            "expected current mapping [" + currentSource + "] for type [" + mapping.type() + "] "
                                    + "to be the same as new mapping [" + newSource + "]";
                }
            } else {
                // if the mapping version is changed, it should increase, there should be updates, and the mapping should be different
                final long currentMappingVersion = currentIndexMetadata.getMappingVersion();
                final long newMappingVersion = newIndexMetadata.getMappingVersion();
                assert currentMappingVersion < newMappingVersion :
                        "expected current mapping version [" + currentMappingVersion + "] "
                                + "to be less than new mapping version [" + newMappingVersion + "]";
                assert updatedEntries.isEmpty() == false;
                for (final DocumentMapper documentMapper : updatedEntries.values()) {
                    final MappingMetadata currentMapping = currentIndexMetadata.mapping();
                    if (currentMapping != null) {
                        final CompressedXContent currentSource = currentMapping.source();
                        final CompressedXContent newSource = documentMapper.mappingSource();
                        assert currentSource.equals(newSource) == false :
                                "expected current mapping [" + currentSource + "] for type [" + documentMapper.type() + "] " +
                                        "to be different than new mapping";
                    }
                }
            }
        }
    }

    public void merge(Map<String, Map<String, Object>> mappings, MergeReason reason) {
        Map<String, CompressedXContent> mappingSourcesCompressed = new LinkedHashMap<>(mappings.size());
        for (Map.Entry<String, Map<String, Object>> entry : mappings.entrySet()) {
            try {
                mappingSourcesCompressed.put(entry.getKey(), new CompressedXContent(Strings.toString(XContentFactory.jsonBuilder().map(entry.getValue()))));
            } catch (Exception e) {
                throw new MapperParsingException("Failed to parse mapping [{}]: {}", e, entry.getKey(), e.getMessage());
            }
        }

        internalMerge(mappingSourcesCompressed, reason);
    }

    public void merge(IndexMetadata indexMetadata, MergeReason reason) {
        internalMerge(indexMetadata, reason, false);
    }

    public DocumentMapper merge(String type, CompressedXContent mappingSource, MergeReason reason) {
        // TODO change internalMerge() to return a single DocumentMapper rather than a Map
        return internalMerge(Collections.singletonMap(type, mappingSource), reason).values().iterator().next();
    }

    private synchronized Map<String, DocumentMapper> internalMerge(IndexMetadata indexMetadata, MergeReason reason, boolean onlyUpdateIfNeeded) {
        Map<String, CompressedXContent> map = new LinkedHashMap<>();
        MappingMetadata mappingMetadata = indexMetadata.mapping();
        if (mappingMetadata != null) {
            if (onlyUpdateIfNeeded) {
                DocumentMapper existingMapper = documentMapper();
                if (existingMapper == null || mappingMetadata.source().equals(existingMapper.mappingSource()) == false) {
                    map.put(mappingMetadata.type(), mappingMetadata.source());
                }
            } else {
                map.put(mappingMetadata.type(), mappingMetadata.source());
            }
        }
        return internalMerge(map, reason);
    }

    private synchronized Map<String, DocumentMapper> internalMerge(Map<String, CompressedXContent> mappings, MergeReason reason) {
        DocumentMapper documentMapper = null;
        for (Map.Entry<String, CompressedXContent> entry : mappings.entrySet()) {
            String type = entry.getKey();
            if (documentMapper != null) {
                throw new IllegalArgumentException("Cannot put multiple mappings: " + mappings.keySet());
            }
            try {
                documentMapper = documentParser.parse(type, entry.getValue());
            } catch (Exception e) {
                throw new MapperParsingException("Failed to parse mapping [{}]: {}", e, entry.getKey(), e.getMessage());
            }
        }
        return internalMerge(documentMapper, reason);
    }

    static void validateTypeName(String type) {
        if (type.length() == 0) {
            throw new InvalidTypeNameException("mapping type name is empty");
        }
        if (type.length() > 255) {
            throw new InvalidTypeNameException("mapping type name [" + type + "] is too long; limit is length 255 but was [" + type.length() + "]");
        }
        if (type.charAt(0) == '_' && SINGLE_MAPPING_NAME.equals(type) == false) {
            throw new InvalidTypeNameException("mapping type name [" + type + "] can't start with '_' unless it is called [" + SINGLE_MAPPING_NAME + "]");
        }
        if (type.contains("#")) {
            throw new InvalidTypeNameException("mapping type name [" + type + "] should not include '#' in it");
        }
        if (type.contains(",")) {
            throw new InvalidTypeNameException("mapping type name [" + type + "] should not include ',' in it");
        }
        if (type.charAt(0) == '.') {
            throw new IllegalArgumentException("mapping type name [" + type + "] must not start with a '.'");
        }
    }

    private synchronized Map<String, DocumentMapper> internalMerge(DocumentMapper mapper,
                                                                   MergeReason reason) {
        Map<String, ObjectMapper> fullPathObjectMappers = this.fullPathObjectMappers;
        FieldTypeLookup fieldTypes = this.fieldTypes;
        Map<String, DocumentMapper> results = new LinkedHashMap<>(2);

        DocumentMapper newMapper = null;
        if (mapper != null) {
            // check naming
            validateTypeName(mapper.type());

            // compute the merged DocumentMapper
            DocumentMapper oldMapper = this.mapper;
            if (oldMapper != null) {
                newMapper = oldMapper.merge(mapper.mapping());
            } else {
                newMapper = mapper;
            }

            // check basic sanity of the new mapping
            List<ObjectMapper> objectMappers = new ArrayList<>();
            List<FieldMapper> fieldMappers = new ArrayList<>();
            List<FieldAliasMapper> fieldAliasMappers = new ArrayList<>();
            MetadataFieldMapper[] metadataMappers = newMapper.mapping().metadataMappers;
            Collections.addAll(fieldMappers, metadataMappers);
            MapperUtils.collect(newMapper.mapping().root(), objectMappers, fieldMappers, fieldAliasMappers);

            MapperMergeValidator.validateNewMappers(objectMappers, fieldMappers, fieldAliasMappers, fieldTypes);
            checkPartitionedIndexConstraints(newMapper);

            // update lookup data-structures
            fieldTypes = fieldTypes.copyAndAddAll(newMapper.type(), fieldMappers, fieldAliasMappers);

            for (ObjectMapper objectMapper : objectMappers) {
                if (fullPathObjectMappers == this.fullPathObjectMappers) {
                    // first time through the loops
                    fullPathObjectMappers = new HashMap<>(this.fullPathObjectMappers);
                }
                fullPathObjectMappers.put(objectMapper.fullPath(), objectMapper);
            }

            if (reason == MergeReason.MAPPING_UPDATE) {
                // this check will only be performed on the master node when there is
                // a call to the update mapping API. For all other cases like
                // the master node restoring mappings from disk or data nodes
                // deserializing cluster state that was sent by the master node,
                // this check will be skipped.
                // Also, don't take metadata mappers into account for the field limit check
                checkTotalFieldsLimit(
                    objectMappers.size() + fieldMappers.size() - metadataMappers.length + fieldAliasMappers.size());
            }

            results.put(newMapper.type(), newMapper);
        }

        if (reason == MergeReason.MAPPING_UPDATE) {
            // this check will only be performed on the master node when there is
            // a call to the update mapping API. For all other cases like
            // the master node restoring mappings from disk or data nodes
            // deserializing cluster state that was sent by the master node,
            // this check will be skipped.
            checkDepthLimit(fullPathObjectMappers.keySet());
        }

        if (newMapper != null) {
            DocumentMapper updatedDocumentMapper = newMapper.updateFieldType(fieldTypes.fullNameToFieldType);
            if (updatedDocumentMapper != newMapper) {
                // update both mappers and result
                newMapper = updatedDocumentMapper;
                results.put(updatedDocumentMapper.type(), updatedDocumentMapper);
            }
        }

        // make structures immutable
        results = Collections.unmodifiableMap(results);

        // only need to immutably rewrap these if the previous reference was changed.
        // if not then they are already implicitly immutable.
        if (fullPathObjectMappers != this.fullPathObjectMappers) {
            fullPathObjectMappers = Collections.unmodifiableMap(fullPathObjectMappers);
        }

        if (newMapper != null) {
            this.mapper = newMapper;
        }
        this.fieldTypes = fieldTypes;
        this.fullPathObjectMappers = fullPathObjectMappers;

        assert assertMappersShareSameFieldType();
        assert results.values().stream().allMatch(this::assertSerialization);

        return results;
    }

    private boolean assertMappersShareSameFieldType() {
        if (mapper != null) {
            List<FieldMapper> fieldMappers = new ArrayList<>();
            Collections.addAll(fieldMappers, mapper.mapping().metadataMappers);
            MapperUtils.collect(mapper.root(), new ArrayList<>(), fieldMappers, new ArrayList<>());
            for (FieldMapper fieldMapper : fieldMappers) {
                assert fieldMapper.fieldType() == fieldTypes.get(fieldMapper.name()) : fieldMapper.name();
            }
        }
        return true;
    }

    private boolean assertSerialization(DocumentMapper mapper) {
        // capture the source now, it may change due to concurrent parsing
        final CompressedXContent mappingSource = mapper.mappingSource();
        DocumentMapper newMapper = parse(mapper.type(), mappingSource);

        if (newMapper.mappingSource().equals(mappingSource) == false) {
            throw new IllegalStateException("DocumentMapper serialization result is different from source. \n--> Source ["
                + mappingSource + "]\n--> Result ["
                + newMapper.mappingSource() + "]");
        }
        return true;
    }

    private void checkTotalFieldsLimit(long totalMappers) {
        long allowedTotalFields = indexSettings.getValue(INDEX_MAPPING_TOTAL_FIELDS_LIMIT_SETTING);
        if (allowedTotalFields < totalMappers) {
            throw new IllegalArgumentException("Limit of total fields [" + allowedTotalFields + "] in index [" + index().getName() + "] has been exceeded");
        }
    }

    private void checkDepthLimit(Collection<String> objectPaths) {
        final long maxDepth = indexSettings.getValue(INDEX_MAPPING_DEPTH_LIMIT_SETTING);
        for (String objectPath : objectPaths) {
            checkDepthLimit(objectPath, maxDepth);
        }
    }

    private void checkDepthLimit(String objectPath, long maxDepth) {
        int numDots = 0;
        for (int i = 0; i < objectPath.length(); ++i) {
            if (objectPath.charAt(i) == '.') {
                numDots += 1;
            }
        }
        final int depth = numDots + 2;
        if (depth > maxDepth) {
            throw new IllegalArgumentException("Limit of mapping depth [" + maxDepth + "] in index [" + index().getName()
                    + "] has been exceeded due to object field [" + objectPath + "]");
        }
    }

    private void checkPartitionedIndexConstraints(DocumentMapper newMapper) {
        if (indexSettings.getIndexMetadata().isRoutingPartitionedIndex()) {
            if (!newMapper.routingFieldMapper().required()) {
                throw new IllegalArgumentException("mapping type [" + newMapper.type() + "] must have routing "
                        + "required for partitioned index [" + indexSettings.getIndex().getName() + "]");
            }
        }
    }

    public DocumentMapper parse(String mappingType, CompressedXContent mappingSource) throws MapperParsingException {
        return documentParser.parse(mappingType, mappingSource);
    }

    /**
     * Return the {@link DocumentMapper} for the given type. By using the special
     * {@value #DEFAULT_MAPPING} type, you can get a {@link DocumentMapper} for
     * the default mapping.
     */
    public DocumentMapper documentMapper() {
        return mapper;
    }

    /**
     * Returns the {@link MappedFieldType} for the give fullName.
     *
     * If multiple types have fields with the same full name, the first is returned.
     */
    public MappedFieldType fullName(String fullName) {
        return fieldTypes.get(fullName);
    }

    /**
     * Returns all the fields that match the given pattern. If the pattern is prefixed with a type
     * then the fields will be returned with a type prefix.
     */
    public Collection<String> simpleMatchToFullName(String pattern) {
        if (Regex.isSimpleMatchPattern(pattern) == false) {
            // no wildcards
            return Collections.singletonList(pattern);
        }
        return fieldTypes.simpleMatchToFullName(pattern);
    }

    /**
     * Returns all mapped field types.
     */
    public Iterable<MappedFieldType> fieldTypes() {
        return fieldTypes;
    }

    public ObjectMapper getObjectMapper(String name) {
        return fullPathObjectMappers.get(name);
    }

    public Analyzer indexAnalyzer() {
        return this.indexAnalyzer;
    }

    public Analyzer searchAnalyzer() {
        return this.searchAnalyzer;
    }

    public Analyzer searchQuoteAnalyzer() {
        return this.searchQuoteAnalyzer;
    }

    @Override
    public void close() throws IOException {
        indexAnalyzers.close();
    }

    /**
     * @return Whether a field is a metadata field.
     */
    public static boolean isMetadataField(String fieldName) {
        return META_FIELDS.contains(fieldName);
    }

    /** An analyzer wrapper that can lookup fields within the index mappings */
    final class MapperAnalyzerWrapper extends DelegatingAnalyzerWrapper {

        private final Analyzer defaultAnalyzer;
        private final Function<MappedFieldType, Analyzer> extractAnalyzer;

        MapperAnalyzerWrapper(Analyzer defaultAnalyzer, Function<MappedFieldType, Analyzer> extractAnalyzer) {
            super(Analyzer.PER_FIELD_REUSE_STRATEGY);
            this.defaultAnalyzer = defaultAnalyzer;
            this.extractAnalyzer = extractAnalyzer;
        }

        @Override
        protected Analyzer getWrappedAnalyzer(String fieldName) {
            MappedFieldType fieldType = fullName(fieldName);
            if (fieldType != null) {
                Analyzer analyzer = extractAnalyzer.apply(fieldType);
                if (analyzer != null) {
                    return analyzer;
                }
            }
            return defaultAnalyzer;
        }
    }

}
