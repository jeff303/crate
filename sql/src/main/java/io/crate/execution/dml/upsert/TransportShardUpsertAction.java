/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.dml.upsert;

import com.google.common.annotations.VisibleForTesting;
import io.crate.Constants;
import io.crate.action.FutureActionListener;
import io.crate.execution.ddl.SchemaUpdateClient;
import io.crate.execution.dml.ShardResponse;
import io.crate.execution.dml.TransportShardAction;
import io.crate.execution.dml.upsert.ShardUpsertRequest.DuplicateKeyAction;
import io.crate.execution.engine.collect.PKLookupOperation;
import io.crate.execution.jobs.TasksService;
import io.crate.expression.reference.Doc;
import io.crate.expression.symbol.Symbol;
import io.crate.metadata.ColumnIdent;
import io.crate.metadata.Functions;
import io.crate.metadata.GeneratedReference;
import io.crate.metadata.Reference;
import io.crate.metadata.RelationName;
import io.crate.metadata.Schemas;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.doc.DocTableInfo;
import io.crate.metadata.table.Operation;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.replication.TransportReplicationAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.index.engine.DocumentSourceMissingException;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.crate.exceptions.SQLExceptions.userFriendlyCrateExceptionTopOnly;

/**
 * Realizes Upserts of tables which either results in an Insert or an Update.
 */
@Singleton
public class TransportShardUpsertAction extends TransportShardAction<ShardUpsertRequest, ShardUpsertRequest.Item> {

    private static final String ACTION_NAME = "internal:crate:sql/data/write";
    private static final int MAX_RETRY_LIMIT = 100_000; // upper bound to prevent unlimited retries on unexpected states

    private final Schemas schemas;
    private final Functions functions;

    @Inject
    public TransportShardUpsertAction(ThreadPool threadPool,
                                      ClusterService clusterService,
                                      TransportService transportService,
                                      SchemaUpdateClient schemaUpdateClient,
                                      TasksService tasksService,
                                      IndicesService indicesService,
                                      ShardStateAction shardStateAction,
                                      Functions functions,
                                      Schemas schemas,
                                      IndexNameExpressionResolver indexNameExpressionResolver) {
        super(
            ACTION_NAME,
            transportService,
            indexNameExpressionResolver,
            clusterService,
            indicesService,
            threadPool,
            shardStateAction,
            ShardUpsertRequest::new,
            schemaUpdateClient
        );
        this.schemas = schemas;
        this.functions = functions;
        tasksService.addListener(this);
    }

    @Override
    protected void processRequestItems(IndexShard indexShard,
                                       ShardUpsertRequest request,
                                       AtomicBoolean killed,
                                       ActionListener<PrimaryResult<ShardUpsertRequest, ShardResponse>> listener) {
        ShardResponse shardResponse = new ShardResponse();
        String indexName = request.index();
        DocTableInfo tableInfo = schemas.getTableInfo(RelationName.fromIndexName(indexName), Operation.INSERT);
        Reference[] insertColumns = request.insertColumns();
        GeneratedColumns.Validation valueValidation = request.validateConstraints()
            ? GeneratedColumns.Validation.VALUE_MATCH
            : GeneratedColumns.Validation.NONE;

        TransactionContext txnCtx = TransactionContext.of(request.sessionSettings());
        InsertSourceGen insertSourceGen = insertColumns == null
            ? null
            : InsertSourceGen.of(txnCtx,
                                 functions,
                                 tableInfo,
                                 indexName,
                                 valueValidation,
                                 Arrays.asList(insertColumns));

        UpdateSourceGen updateSourceGen = request.updateColumns() == null
            ? null
            : new UpdateSourceGen(functions, txnCtx, tableInfo, request.updateColumns());

        Map<CompletableFuture<Translog.Location>, ShardUpsertRequest.Item> indexOpResults = new LinkedHashMap<>();
        for (ShardUpsertRequest.Item item : request.items()) {
            if (killed.get()) {
                // set failure on response and skip all next items.
                // with a valid source (= was processed on primary) will be processed on
                // the replica this way replica operation will be executed, but only items
                shardResponse.failure(new InterruptedException());
                break;
            }
            indexOpResults.put(indexItemRetrying(request,
                                                 item,
                                                 indexShard,
                                                 item.insertValues() != null,
                                                 // try insert first
                                                 updateSourceGen,
                                                 insertSourceGen),
                               item);
        }

        Translog.Location translogLocation = null;
        if (false == killed.get()) {
            Iterator<Map.Entry<CompletableFuture<Translog.Location>, ShardUpsertRequest.Item>> it = indexOpResults.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<CompletableFuture<Translog.Location>, ShardUpsertRequest.Item> entry = it.next();
                CompletableFuture<Translog.Location> indexResult = entry.getKey();
                ShardUpsertRequest.Item item = entry.getValue();
                if (indexResult.isDone() || indexResult.isCompletedExceptionally()) {
                    try {
                        translogLocation = indexResult.get();
                        if (null != translogLocation) {
                            shardResponse.add(item.location());
                        }
                    } catch (ExecutionException e) {
                        Throwable realCause = e.getCause();
                        if (retryPrimaryException(realCause)) {
                            RuntimeException rte;
                            if (realCause instanceof RuntimeException) {
                                rte = (RuntimeException) realCause;
                            } else {
                                rte = new RuntimeException(realCause);
                            }
                            listener.onFailure(rte);
                            break;
                        } else {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Failed to execute upsert shardId={} id={} error={}",
                                             request.shardId(), item.id(), realCause);
                            }

                            // *mark* the item as failed by setting the source to null to prevent
                            // the replica operation from processing this concrete item
                            item.source(null);

                            if (false == request.continueOnError()) {
                                shardResponse.failure((Exception) realCause);
                            } else {
                                shardResponse.add(item.location(),
                                                  new ShardResponse.Failure(
                                                      item.id(),
                                                      userFriendlyCrateExceptionTopOnly(realCause),
                                                      (realCause instanceof VersionConflictEngineException)));
                            }
                        }
                    } catch (Exception e) {
                        listener.onFailure(e);
                        break;
                    } finally {
                        it.remove();
                    }
                }
            }
        }
        listener.onResponse(new WritePrimaryResult<>(request, shardResponse, translogLocation, null, indexShard));
    }

    @Override
    protected WriteReplicaResult<ShardUpsertRequest> processRequestItemsOnReplica(IndexShard indexShard,
                                                                                  ShardUpsertRequest request) throws IOException {
        Translog.Location location = null;
        for (ShardUpsertRequest.Item item : request.items()) {
            if (item.source() == null) {
                if (logger.isTraceEnabled()) {
                    logger.trace("[{} (R)] Document with id {}, has no source, primary operation must have failed",
                                 indexShard.shardId(), item.id());
                }
                continue;
            }
            SourceToParse sourceToParse = new SourceToParse(
                indexShard.shardId().getIndexName(),
                item.id(),
                item.source(),
                XContentType.JSON
            );

            Engine.IndexResult indexResult = indexShard.applyIndexOperationOnReplica(
                item.seqNo(),
                item.version(),
                Translog.UNSET_AUTO_GENERATED_TIMESTAMP,
                false,
                sourceToParse
            );
            if (indexResult.getResultType() == Engine.Result.Type.MAPPING_UPDATE_REQUIRED) {
                // Even though the primary waits on all nodes to ack the mapping changes to the master
                // (see MappingUpdatedAction.updateMappingOnMaster) we still need to protect against missing mappings
                // and wait for them. The reason is concurrent requests. Request r1 which has new field f triggers a
                // mapping update. Assume that that update is first applied on the primary, and only later on the replica
                // (it’s happening concurrently). Request r2, which now arrives on the primary and which also has the new
                // field f might see the updated mapping (on the primary), and will therefore proceed to be replicated
                // to the replica. When it arrives on the replica, there’s no guarantee that the replica has already
                // applied the new mapping, so there is no other option than to wait.
                throw new TransportReplicationAction.RetryOnReplicaException(indexShard.shardId(),
                                                                             "Mappings are not available on the replica yet, triggered update: " +
                                                                             indexResult.getRequiredMappingUpdate());
            }
            location = indexResult.getTranslogLocation();
        }
        return new WriteReplicaResult<>(request, location, null, indexShard, logger);
    }

    private CompletableFuture<Translog.Location> indexItemRetrying(ShardUpsertRequest request,
                                                                   ShardUpsertRequest.Item item,
                                                                   IndexShard indexShard,
                                                                   boolean tryInsertFirst,
                                                                   @Nullable UpdateSourceGen updateSourceGen,
                                                                   @Nullable InsertSourceGen insertSourceGen) {
        VersionConflictEngineException lastException = null;
        boolean insertFirst = tryInsertFirst;
        for (int attemptNo = 0; attemptNo < MAX_RETRY_LIMIT; attemptNo++) {
            try {
                return CompletableFuture.completedFuture(indexItem(request,
                                                                   item,
                                                                   indexShard,
                                                                   insertFirst,
                                                                   updateSourceGen,
                                                                   insertSourceGen,
                                                                   attemptNo > 0).get());
            } catch (ExecutionException e) {
                if (e.getCause() instanceof VersionConflictEngineException) {
                    lastException = (VersionConflictEngineException) e.getCause();
                    if (request.duplicateKeyAction() == DuplicateKeyAction.IGNORE) {
                        // on conflict do nothing
                        item.source(null);
                        return CompletableFuture.completedFuture(null);
                    }
                    Symbol[] updateAssignments = item.updateAssignments();
                    if (updateAssignments != null && updateAssignments.length > 0) {
                        if (insertFirst) {
                            insertFirst = false;
                            continue;
                        } else if (item.retryOnConflict()) {
                            if (logger.isTraceEnabled()) {
                                logger.trace(
                                    "[{}] VersionConflict, retrying operation for document id={}, version={} retryCount={}",
                                    indexShard.shardId(),
                                    item.id(),
                                    item.version(),
                                    attemptNo);
                            }
                            continue;
                        }
                    }
                    return CompletableFuture.failedFuture(lastException);
                } else {
                    return CompletableFuture.failedFuture(e.getCause());
                }
            } catch (Exception other) {
                return CompletableFuture.failedFuture(other);
            }
        }
        logger.warn("[{}] VersionConflict for document id={}, version={} exceeded retry limit of {}, will stop retrying",
                    indexShard.shardId(), item.id(), item.version(), MAX_RETRY_LIMIT);
        return CompletableFuture.failedFuture(lastException);
    }


    @VisibleForTesting
    protected CompletableFuture<Translog.Location> indexItem(ShardUpsertRequest request,
                                                             ShardUpsertRequest.Item item,
                                                             IndexShard indexShard,
                                                             boolean tryInsertFirst,
                                                             UpdateSourceGen updateSourceGen,
                                                             InsertSourceGen insertSourceGen,
                                                             boolean isRetry) {
        FutureActionListener<Translog.Location, Translog.Location> indexItemResult = FutureActionListener.newInstance();
        final long seqNo;
        final long primaryTerm;
        final long version;
        if (tryInsertFirst) {
            version = request.duplicateKeyAction() == DuplicateKeyAction.OVERWRITE
                ? Versions.MATCH_ANY
                : Versions.MATCH_DELETED;
            seqNo = SequenceNumbers.UNASSIGNED_SEQ_NO;
            primaryTerm = SequenceNumbers.UNASSIGNED_PRIMARY_TERM;
            try {
                insertSourceGen.checkConstraints(item.insertValues());
                item.source(insertSourceGen.generateSource(item.insertValues()));
            } catch (IOException e) {
                indexItemResult.onFailure(ExceptionsHelper.convertToElastic(e));
                return indexItemResult;
            }
        } else {
            BytesReference updatedSource;
            try {
                Doc currentDoc = getDocument(indexShard, item.id(), item.version(), item.seqNo(), item.primaryTerm());
                updatedSource = updateSourceGen.generateSource(
                    currentDoc,
                    item.updateAssignments(),
                    item.insertValues()
                );
            } catch (Exception e) {
                indexItemResult.onFailure(e);
                return indexItemResult;
            }
            item.source(updatedSource);
            seqNo = item.seqNo();
            primaryTerm = item.primaryTerm();
            version = Versions.MATCH_ANY;
        }
        SourceToParse sourceToParse = new SourceToParse(
            indexShard.shardId().getIndexName(),
            item.id(),
            item.source(),
            XContentType.JSON
        );
        executeOnPrimaryHandlingMappingUpdate(
            indexShard.shardId(),
            () -> indexShard.applyIndexOperationOnPrimary(
                version,
                VersionType.INTERNAL,
                sourceToParse,
                seqNo,
                primaryTerm,
                Translog.UNSET_AUTO_GENERATED_TIMESTAMP,
                isRetry
            ),
            e -> indexShard.getFailedIndexResult(e, Versions.MATCH_ANY),
            ActionListener.wrap(
                indexResult -> {
                    switch (indexResult.getResultType()) {
                        case SUCCESS:
                            // update the seqNo and version on request for the replicas
                            item.seqNo(indexResult.getSeqNo());
                            item.version(indexResult.getVersion());
                            indexItemResult.onResponse(indexResult.getTranslogLocation());
                            break;

                        case FAILURE:
                            Exception failure = indexResult.getFailure();
                            assert failure != null : "Failure must not be null if resultType is FAILURE";
                            indexItemResult.onFailure(failure);
                            break;

                        case MAPPING_UPDATE_REQUIRED:
                        default:
                            throw new AssertionError(
                                "IndexResult must either succeed or fail. Required mapping updates must have been handled.");
                    }
                },
                indexItemResult::onFailure)
        );
        return indexItemResult;
    }

    private static Doc getDocument(IndexShard indexShard, String id, long version, long seqNo, long primaryTerm) {
        // when sequence versioning is used, this lookup will throw VersionConflictEngineException
        Doc doc = PKLookupOperation.lookupDoc(indexShard,
                                              id,
                                              Versions.MATCH_ANY,
                                              VersionType.INTERNAL,
                                              seqNo,
                                              primaryTerm);
        if (doc == null) {
            throw new DocumentMissingException(indexShard.shardId(), Constants.DEFAULT_MAPPING_TYPE, id);
        }
        if (doc.getSource() == null) {
            throw new DocumentSourceMissingException(indexShard.shardId(), Constants.DEFAULT_MAPPING_TYPE, id);
        }
        if (version != Versions.MATCH_ANY && version != doc.getVersion()) {
            throw new VersionConflictEngineException(
                indexShard.shardId(),
                id,
                "Requested version: " + version + " but got version: " + doc.getVersion());
        }
        return doc;
    }

    public static Collection<ColumnIdent> getNotUsedNonGeneratedColumns(Reference[] targetColumns,
                                                                        DocTableInfo tableInfo) {
        Set<String> targetColumnsSet = new HashSet<>();
        Collection<ColumnIdent> columnsNotUsed = new ArrayList<>();

        if (targetColumns != null) {
            for (Reference targetColumn : targetColumns) {
                targetColumnsSet.add(targetColumn.column().fqn());
            }
        }

        for (Reference reference : tableInfo.columns()) {
            if (!reference.isNullable() &&
                !(reference instanceof GeneratedReference || reference.defaultExpression() != null)) {
                if (!targetColumnsSet.contains(reference.column().fqn())) {
                    columnsNotUsed.add(reference.column());
                }
            }
        }
        return columnsNotUsed;
    }
}
