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

import io.crate.Streamer;
import io.crate.common.collections.EnumSets;
import io.crate.expression.symbol.Symbol;
import io.crate.expression.symbol.Symbols;
import io.crate.metadata.Reference;
import io.crate.metadata.settings.SessionSettings;
import org.elasticsearch.Version;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.ShardId;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class ShardUpsertRequest extends ShardWriteRequest<ShardUpsertRequest, ShardUpsertRequest.Item> {

    private SessionSettings sessionSettings;

    private DuplicateKeyAction duplicateKeyAction;
    private boolean continueOnError;
    private boolean validateConstraints;

    /**
     * List of column names used on update
     */
    @Nullable
    private String[] updateColumns;

    /**
     * List of references used on insert
     */
    @Nullable
    private Reference[] insertColumns;

    /**
     * List of references or expressions to compute values for returning for update.
     */
    @Nullable
    private Symbol[] returnValues;

    public ShardUpsertRequest(ShardId shardId,
                              UUID jobId,
                              boolean continueOnError,
                              boolean validateConstraints,
                              DuplicateKeyAction duplicateKeyAction,
                              SessionSettings sessionSettings,
                              @Nullable String[] updateColumns,
                              @Nullable Reference[] insertColumns,
                              @Nullable Symbol[] returnValues) {
        super(shardId, jobId);
        assert updateColumns != null || insertColumns != null : "Missing updateAssignments, whether for update nor for insert";
        this.continueOnError = continueOnError;
        this.validateConstraints = validateConstraints;
        this.duplicateKeyAction = duplicateKeyAction;
        this.sessionSettings = sessionSettings;
        this.updateColumns = updateColumns;
        this.insertColumns = insertColumns;
        this.returnValues = returnValues;
    }

    public ShardUpsertRequest(StreamInput in) throws IOException {
        super(in);
        int assignmentsColumnsSize = in.readVInt();
        if (assignmentsColumnsSize > 0) {
            updateColumns = new String[assignmentsColumnsSize];
            for (int i = 0; i < assignmentsColumnsSize; i++) {
                updateColumns[i] = in.readString();
            }
        }
        int missingAssignmentsColumnsSize = in.readVInt();
        Streamer[] insertValuesStreamer = null;
        if (missingAssignmentsColumnsSize > 0) {
            insertColumns = new Reference[missingAssignmentsColumnsSize];
            for (int i = 0; i < missingAssignmentsColumnsSize; i++) {
                insertColumns[i] = Reference.fromStream(in);
            }
            insertValuesStreamer = Symbols.streamerArray(List.of(insertColumns));
        }

        if (in.getVersion().onOrAfter(Version.V_4_2_0)) {
            EnumSet<Mode> modes = EnumSets.unpackFromInt(in.readVInt(), Mode.class);
            continueOnError = modes.contains(Mode.CONTINUE_ON_ERROR);
            validateConstraints = modes.contains(Mode.VALIDATE_CONSTRAINTS);
            if (modes.contains(Mode.DUPLICATE_KEY_IGNORE)) {
                duplicateKeyAction = DuplicateKeyAction.IGNORE;
            } else if (modes.contains(Mode.DUPLICATE_KEY_OVERWRITE)) {
                duplicateKeyAction = DuplicateKeyAction.OVERWRITE;
            } else if (modes.contains(Mode.DUPLICATE_KEY_UPDATE_OR_FAIL)) {
                duplicateKeyAction = DuplicateKeyAction.UPDATE_OR_FAIL;
            }
        } else {
            continueOnError = in.readBoolean();
            duplicateKeyAction = DuplicateKeyAction.values()[in.readVInt()];
            validateConstraints = in.readBoolean();
        }

        sessionSettings = new SessionSettings(in);
        int numItems = in.readVInt();
        items = new ArrayList<>(numItems);
        for (int i = 0; i < numItems; i++) {
            items.add(new Item(in, insertValuesStreamer));
        }
        if (in.getVersion().onOrAfter(Version.V_4_2_0)) {
            int returnValuesSize = in.readVInt();
            if (returnValuesSize > 0) {
                returnValues = new Symbol[returnValuesSize];
                for (int i = 0; i < returnValuesSize; i++) {
                    returnValues[i] = Symbols.fromStream(in);
                }
            }
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        // Stream References
        if (updateColumns != null) {
            out.writeVInt(updateColumns.length);
            for (String column : updateColumns) {
                out.writeString(column);
            }
        } else {
            out.writeVInt(0);
        }
        Streamer[] insertValuesStreamer = null;
        if (insertColumns != null) {
            out.writeVInt(insertColumns.length);
            for (Reference reference : insertColumns) {
                Reference.toStream(reference, out);
            }
            insertValuesStreamer = Symbols.streamerArray(List.of(insertColumns));
        } else {
            out.writeVInt(0);
        }

        boolean allOn4_2 = out.getVersion().onOrAfter(Version.V_4_2_0);

        if (allOn4_2) {
            out.writeVInt(EnumSets.packToInt(Mode.toEnumSet(continueOnError, validateConstraints, duplicateKeyAction)));
        } else {
            out.writeBoolean(continueOnError);
            out.writeVInt(duplicateKeyAction.ordinal());
            out.writeBoolean(validateConstraints);
        }

        sessionSettings.writeTo(out);

        out.writeVInt(items.size());
        for (Item item : items) {
            item.writeTo(out, insertValuesStreamer, allOn4_2);
        }
        if (allOn4_2) {
            if (returnValues != null) {
                out.writeVInt(returnValues.length);
                for (Symbol returnValue : returnValues) {
                    Symbols.toStream(returnValue, out);
                }
            } else {
                out.writeVInt(0);
            }
        }
    }

    @Nullable
    @Override
    public SessionSettings sessionSettings() {
        return sessionSettings;
    }

    @Nullable
    @Override
    public Symbol[] returnValues() {
        return returnValues;
    }

    @Nullable
    @Override
    public String[] updateColumns() {
        return updateColumns;
    }

    @Nullable
    @Override
    public Reference[] insertColumns() {
        return insertColumns;
    }

    @Override
    public boolean continueOnError() {
        return continueOnError;
    }

    @Override
    public boolean validateConstraints() {
        return validateConstraints;
    }

    @Override
    public DuplicateKeyAction duplicateKeyAction() {
        return duplicateKeyAction;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        ShardUpsertRequest items = (ShardUpsertRequest) o;
        return continueOnError == items.continueOnError &&
               validateConstraints == items.validateConstraints &&
               Objects.equals(sessionSettings, items.sessionSettings) &&
               duplicateKeyAction == items.duplicateKeyAction &&
               Arrays.equals(updateColumns, items.updateColumns) &&
               Arrays.equals(insertColumns, items.insertColumns) &&
               Arrays.equals(returnValues, items.returnValues);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(super.hashCode(),
                                  sessionSettings,
                                  duplicateKeyAction,
                                  continueOnError,
                                  validateConstraints);
        result = 31 * result + Arrays.hashCode(updateColumns);
        result = 31 * result + Arrays.hashCode(insertColumns);
        result = 31 * result + Arrays.hashCode(returnValues);
        return result;
    }

    /**
     * A single update item.
     */
    public static class Item extends ShardWriteRequest.Item {

        @Nullable
        protected BytesReference source;

        /**
         * List of symbols used on update if document exist
         */
        @Nullable
        private Symbol[] updateAssignments;

        /**
         * List of objects used on insert
         */
        @Nullable
        private Object[] insertValues;

        protected Set<Mode> modes;

        public Item(String id,
                    @Nullable Symbol[] updateAssignments,
                    @Nullable Object[] insertValues,
                    @Nullable Long version,
                    @Nullable Long seqNo,
                    @Nullable Long primaryTerm
        ) {
            super(id, version, seqNo, primaryTerm);
            this.updateAssignments = updateAssignments;
            if (version != null) {
                this.version = version;
            }
            if (seqNo != null) {
                this.seqNo = seqNo;
            }
            if (primaryTerm != null) {
                this.primaryTerm = primaryTerm;
            }
            this.insertValues = insertValues;
        }

        @Nullable
        @Override
        public BytesReference source() {
            return source;
        }

        @Override
        public void source(BytesReference source) {
            this.source = source;
        }

        boolean retryOnConflict() {
            return seqNo == SequenceNumbers.UNASSIGNED_SEQ_NO && version == Versions.MATCH_ANY;
        }

        @Nullable
        Symbol[] updateAssignments() {
            return updateAssignments;
        }

        @Nullable
        public Object[] insertValues() {
            return insertValues;
        }

        public Item(StreamInput in, @Nullable Streamer[] insertValueStreamers) throws IOException {
            super(in);
            if (in.readBoolean()) {
                int assignmentsSize = in.readVInt();
                updateAssignments = new Symbol[assignmentsSize];
                for (int i = 0; i < assignmentsSize; i++) {
                    updateAssignments[i] = Symbols.fromStream(in);
                }
            }

            int missingAssignmentsSize = in.readVInt();
            if (missingAssignmentsSize > 0) {
                assert insertValueStreamers != null : "streamers are required if reading insert values";
                this.insertValues = new Object[missingAssignmentsSize];
                for (int i = 0; i < missingAssignmentsSize; i++) {
                    insertValues[i] = insertValueStreamers[i].readValueFrom(in);
                }
            }
            if (in.readBoolean()) {
                source = in.readBytesReference();
            }
        }

        public void writeTo(StreamOutput out, @Nullable Streamer[] insertValueStreamers, boolean allOn4_2) throws IOException {
            super.writeTo(out);
            if (updateAssignments != null) {
                out.writeBoolean(true);
                out.writeVInt(updateAssignments.length);
                for (Symbol updateAssignment : updateAssignments) {
                    Symbols.toStream(updateAssignment, out);
                }
            } else {
                out.writeBoolean(false);
            }
            // Stream References
            if (insertValues != null) {
                assert insertValueStreamers != null : "streamers are required to stream insert values";
                out.writeVInt(insertValues.length);
                for (int i = 0; i < insertValues.length; i++) {
                    insertValueStreamers[i].writeValueTo(out, insertValues[i]);
                }
            } else {
                out.writeVInt(0);
            }
            boolean sourceAvailable = source != null;
            out.writeBoolean(sourceAvailable);
            if (sourceAvailable) {
                out.writeBytesReference(source);
            }
        }
    }


    public static class Builder {

        private final SessionSettings sessionSettings;
        private final TimeValue timeout;
        @Nullable
        private final String[] assignmentsColumns;
        @Nullable
        private final Reference[] missingAssignmentsColumns;
        private final UUID jobId;
        @Nullable
        private final Symbol[] returnValues;
        private final boolean validateGeneratedColumns;
        private final boolean continueOnError;
        private final DuplicateKeyAction duplicateKeyAction;

        public Builder(SessionSettings sessionSettings,
                       TimeValue timeout,
                       boolean continueOnError,
                       @Nullable String[] assignmentsColumns,
                       @Nullable Reference[] missingAssignmentsColumns,
                       @Nullable Symbol[] returnValue,
                       UUID jobId,
                       boolean validateGeneratedColumns,
                       DuplicateKeyAction duplicateKeyAction) {
            this.sessionSettings = sessionSettings;
            this.timeout = timeout;
            this.assignmentsColumns = assignmentsColumns;
            this.missingAssignmentsColumns = missingAssignmentsColumns;
            this.jobId = jobId;
            this.returnValues = returnValue;
            this.validateGeneratedColumns = validateGeneratedColumns;
            this.continueOnError = continueOnError;
            this.duplicateKeyAction = duplicateKeyAction;
        }

        public ShardUpsertRequest newRequest(ShardId shardId) {
            return new ShardUpsertRequest(
                shardId,
                jobId,
                continueOnError,
                validateGeneratedColumns,
                duplicateKeyAction,
                sessionSettings,
                assignmentsColumns,
                missingAssignmentsColumns,
                returnValues
            ).timeout(timeout);
        }
    }
}
