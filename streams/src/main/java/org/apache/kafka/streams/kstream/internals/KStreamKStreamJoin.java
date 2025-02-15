/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.ValueJoinerWithKey;
import org.apache.kafka.streams.kstream.internals.KStreamImplJoin.TimeTracker;
import org.apache.kafka.streams.kstream.internals.KStreamImplJoin.TimeTrackerSupplier;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.ProcessorSupplier;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.processor.internals.metrics.StreamsMetricsImpl;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.WindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.apache.kafka.streams.state.internals.LeftOrRightValue;
import org.apache.kafka.streams.state.internals.TimestampedKeyAndJoinSide;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.apache.kafka.streams.StreamsConfig.InternalConfig.EMIT_INTERVAL_MS_KSTREAMS_OUTER_JOIN_SPURIOUS_RESULTS_FIX;
import static org.apache.kafka.streams.processor.internals.metrics.TaskMetrics.droppedRecordsSensor;

class KStreamKStreamJoin<K, V1, V2, VOut> implements ProcessorSupplier<K, V1, K, VOut> {
    private static final Logger LOG = LoggerFactory.getLogger(KStreamKStreamJoin.class);

    private final String otherWindowName;
    private final long joinBeforeMs;
    private final long joinAfterMs;
    private final long joinGraceMs;
    private final boolean enableSpuriousResultFix;
    private final long windowsBeforeMs;
    private final long windowsAfterMs;

    private final boolean outer;
    private final boolean isLeftSide;
    private final Optional<String> outerJoinWindowName;
    private final ValueJoinerWithKey<? super K, ? super V1, ? super V2, ? extends VOut> joiner;

    private final TimeTrackerSupplier sharedTimeTrackerSupplier;

    KStreamKStreamJoin(final boolean isLeftSide,
                       final String otherWindowName,
                       final JoinWindowsInternal windows,
                       final ValueJoinerWithKey<? super K, ? super V1, ? super V2, ? extends VOut> joiner,
                       final boolean outer,
                       final Optional<String> outerJoinWindowName,
                       final TimeTrackerSupplier sharedTimeTrackerSupplier) {
        this.isLeftSide = isLeftSide;
        this.otherWindowName = otherWindowName;
        if (isLeftSide) {
            this.joinBeforeMs = windows.beforeMs;
            this.joinAfterMs = windows.afterMs;
        } else {
            this.joinBeforeMs = windows.afterMs;
            this.joinAfterMs = windows.beforeMs;
        }
        this.windowsAfterMs = windows.afterMs;
        this.windowsBeforeMs = windows.beforeMs;
        this.joinGraceMs = windows.gracePeriodMs();
        this.enableSpuriousResultFix = windows.spuriousResultFixEnabled();
        this.joiner = joiner;
        this.outer = outer;
        this.outerJoinWindowName = outerJoinWindowName;
        this.sharedTimeTrackerSupplier = sharedTimeTrackerSupplier;
    }

    @Override
    public Processor<K, V1, K, VOut> get() {
        return new KStreamKStreamJoinProcessor();
    }

    private class KStreamKStreamJoinProcessor extends ContextualProcessor<K, V1, K, VOut> {
        private WindowStore<K, V2> otherWindowStore;
        private Sensor droppedRecordsSensor;
        private Optional<KeyValueStore<TimestampedKeyAndJoinSide<K>, LeftOrRightValue<V1, V2>>> outerJoinStore = Optional.empty();
        private InternalProcessorContext<K, VOut> internalProcessorContext;
        private TimeTracker sharedTimeTracker;

        @Override
        public void init(final ProcessorContext<K, VOut> context) {
            super.init(context);
            internalProcessorContext = (InternalProcessorContext<K, VOut>) context;

            final StreamsMetricsImpl metrics = (StreamsMetricsImpl) context.metrics();
            droppedRecordsSensor = droppedRecordsSensor(Thread.currentThread().getName(), context.taskId().toString(), metrics);
            otherWindowStore = context.getStateStore(otherWindowName);
            sharedTimeTracker = sharedTimeTrackerSupplier.get(context.taskId());

            if (enableSpuriousResultFix) {
                outerJoinStore = outerJoinWindowName.map(context::getStateStore);

                sharedTimeTracker.setEmitInterval(
                    StreamsConfig.InternalConfig.getLong(
                        context.appConfigs(),
                        EMIT_INTERVAL_MS_KSTREAMS_OUTER_JOIN_SPURIOUS_RESULTS_FIX,
                        1000L
                    )
                );
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void process(final Record<K, V1> record) {

            final long inputRecordTimestamp = record.timestamp();
            final long timeFrom = Math.max(0L, inputRecordTimestamp - joinBeforeMs);
            final long timeTo = Math.max(0L, inputRecordTimestamp + joinAfterMs);

            sharedTimeTracker.advanceStreamTime(inputRecordTimestamp);
            if (sharedTimeTracker.streamTime > inputRecordTimestamp + joinBeforeMs + joinAfterMs + joinGraceMs) {
                StreamStreamJoinUtil.logSkip(
                    "Record is too old. It's own window and any matching window on the other input stream are closed.",
                    LOG, droppedRecordsSensor, context()
                );
                return;
            }
            if (outer && record.key() == null && record.value() != null) {
                onNullKeyRecord(record, inputRecordTimestamp);
                return;
            } else if (StreamStreamJoinUtil.skipRecord(record, LOG, droppedRecordsSensor, context())) {
                return;
            }

            // Emit all non-joined records which window has closed
            if (inputRecordTimestamp == sharedTimeTracker.streamTime) {
                outerJoinStore.ifPresent(store -> emitNonJoinedOuterRecords(store, record));
            }

            boolean needOuterJoin = outer;
            try (final WindowStoreIterator<V2> iter = otherWindowStore.fetch(record.key(), timeFrom, timeTo)) {
                while (iter.hasNext()) {
                    needOuterJoin = false;
                    final KeyValue<Long, V2> otherRecord = iter.next();
                    final long otherRecordTimestamp = otherRecord.key;

                    outerJoinStore.ifPresent(store -> {
                        // use putIfAbsent to first read and see if there's any values for the key,
                        // if yes delete the key, otherwise do not issue a put;
                        // we may delete some values with the same key early but since we are going
                        // range over all values of the same key even after failure, since the other window-store
                        // is only cleaned up by stream time, so this is okay for at-least-once.
                        store.putIfAbsent(TimestampedKeyAndJoinSide.make(!isLeftSide, record.key(), otherRecordTimestamp), null);
                    });

                    //skip join if windows of both records closed
                    if (Math.max(inputRecordTimestamp + joinAfterMs, otherRecordTimestamp + joinBeforeMs) + joinGraceMs < sharedTimeTracker.streamTime) {
                        continue;
                    }

                    context().forward(
                        record.withValue(joiner.apply(record.key(), record.value(), otherRecord.value))
                               .withTimestamp(Math.max(inputRecordTimestamp, otherRecordTimestamp)));
                }

                if (needOuterJoin) {
                    // The maxStreamTime contains the max time observed in both sides of the join.
                    // Having access to the time observed in the other join side fixes the following
                    // problem:
                    //
                    // Say we have a window size of 5 seconds
                    //  1. A non-joined record with time T10 is seen in the left-topic (maxLeftStreamTime: 10)
                    //     The record is not processed yet, and is added to the outer-join store
                    //  2. A non-joined record with time T2 is seen in the right-topic (maxRightStreamTime: 2)
                    //     The record is not processed yet, and is added to the outer-join store
                    //  3. A joined record with time T11 is seen in the left-topic (maxLeftStreamTime: 11)
                    //     It is time to look at the expired records. T10 and T2 should be emitted, but
                    //     because T2 was late, then it is not fetched by the window store, so it is not processed
                    //
                    // See KStreamKStreamLeftJoinTest.testLowerWindowBound() tests
                    //
                    // This condition below allows us to process the out-of-order records without the need
                    // to hold it in the temporary outer store
                    if (!outerJoinStore.isPresent() || timeTo < sharedTimeTracker.streamTime) {
                        context().forward(record.withValue(joiner.apply(record.key(), record.value(), null)));
                    } else {
                        sharedTimeTracker.updatedMinTime(inputRecordTimestamp);
                        outerJoinStore.ifPresent(store -> store.put(
                            TimestampedKeyAndJoinSide.make(isLeftSide, record.key(), inputRecordTimestamp),
                            LeftOrRightValue.make(isLeftSide, record.value())));
                    }
                }
            }
        }

        private void onNullKeyRecord(final Record<K, V1> record, final long inputRecordTimestamp) {
            if (sharedTimeTracker.streamTime > inputRecordTimestamp + joinAfterMs + joinGraceMs) {
                StreamStreamJoinUtil.logSkip("Null-key record arrived after window close", LOG, droppedRecordsSensor, context());
                return;
            }
            context().forward(record.withValue(joiner.apply(record.key(), record.value(), null)));
        }

        private void emitNonJoinedOuterRecords(
            final KeyValueStore<TimestampedKeyAndJoinSide<K>, LeftOrRightValue<V1, V2>> store,
            final Record<K, V1> record) {

            // calling `store.all()` creates an iterator what is an expensive operation on RocksDB;
            // to reduce runtime cost, we try to avoid paying those cost

            // only try to emit left/outer join results if there _might_ be any result records
            if (sharedTimeTracker.minTime + joinAfterMs + joinGraceMs >= sharedTimeTracker.streamTime) {
                return;
            }
            // throttle the emit frequency to a (configurable) interval;
            // we use processing time to decouple from data properties,
            // as throttling is a non-functional performance optimization
            if (internalProcessorContext.currentSystemTimeMs() < sharedTimeTracker.nextTimeToEmit) {
                return;
            }

            // Ensure `nextTimeToEmit` is synced with `currentSystemTimeMs`, if we dont set it everytime,
            // they can get out of sync during a clock drift
            sharedTimeTracker.nextTimeToEmit = internalProcessorContext.currentSystemTimeMs();
            sharedTimeTracker.advanceNextTimeToEmit();

            // reset to MAX_VALUE in case the store is empty
            sharedTimeTracker.minTime = Long.MAX_VALUE;

            try (final KeyValueIterator<TimestampedKeyAndJoinSide<K>, LeftOrRightValue<V1, V2>> it = store.all()) {
                TimestampedKeyAndJoinSide<K> prevKey = null;

                boolean outerJoinLeftWindowOpen = false;
                boolean outerJoinRightWindowOpen = false;
                while (it.hasNext()) {
                    if (outerJoinLeftWindowOpen && outerJoinRightWindowOpen) {
                        // if windows are open for both joinSides we can break since there are no more candidates to emit
                        break;
                    }
                    final KeyValue<TimestampedKeyAndJoinSide<K>, LeftOrRightValue<V1, V2>> next = it.next();
                    final TimestampedKeyAndJoinSide<K> timestampedKeyAndJoinSide = next.key;
                    final long timestamp = timestampedKeyAndJoinSide.getTimestamp();
                    sharedTimeTracker.minTime = timestamp;

                    // Continue with the next outer record if window for this joinSide has not closed yet
                    // There might be an outer record for the other joinSide which window has not closed yet
                    // We rely on the <timestamp><left/right-boolean><key> ordering of KeyValueIterator
                    final long outerJoinLookBackTimeMs = getOuterJoinLookBackTimeMs(timestampedKeyAndJoinSide);
                    if (sharedTimeTracker.minTime + outerJoinLookBackTimeMs + joinGraceMs >= sharedTimeTracker.streamTime) {
                        if (timestampedKeyAndJoinSide.isLeftSide()) {
                            outerJoinLeftWindowOpen = true; // there are no more candidates to emit on left-outerJoin-side
                        } else {
                            outerJoinRightWindowOpen = true; // there are no more candidates to emit on right-outerJoin-side
                        }
                        // We continue with the next outer record
                        continue;
                    }

                    final K key = timestampedKeyAndJoinSide.getKey();
                    final LeftOrRightValue<V1, V2> leftOrRightValue = next.value;
                    final VOut nullJoinedValue = getNullJoinedValue(key, leftOrRightValue);
                    context().forward(
                        record.withKey(key).withValue(nullJoinedValue).withTimestamp(timestamp)
                    );

                    if (prevKey != null && !prevKey.equals(timestampedKeyAndJoinSide)) {
                        // blind-delete the previous key from the outer window store now it is emitted;
                        // we do this because this delete would remove the whole list of values of the same key,
                        // and hence if we delete eagerly and then fail, we would miss emitting join results of the later
                        // values in the list.
                        // we do not use delete() calls since it would incur extra get()
                        store.put(prevKey, null);
                    }
                    prevKey = timestampedKeyAndJoinSide;
                }

                // at the end of the iteration, we need to delete the last key
                if (prevKey != null) {
                    store.put(prevKey, null);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private VOut getNullJoinedValue(
            final K key,
            final LeftOrRightValue<V1, V2> leftOrRightValue) {
            // depending on the JoinSide fill in the joiner key and joiner values
            if (isLeftSide) {
                return joiner.apply(key,
                    leftOrRightValue.getLeftValue(),
                    leftOrRightValue.getRightValue());
            } else {
                return joiner.apply(key,
                    (V1) leftOrRightValue.getRightValue(),
                    (V2) leftOrRightValue.getLeftValue());
            }
        }

        private long getOuterJoinLookBackTimeMs(
            final TimestampedKeyAndJoinSide<K> timestampedKeyAndJoinSide) {
            // depending on the JoinSide we fill in the outerJoinLookBackTimeMs
            if (timestampedKeyAndJoinSide.isLeftSide()) {
                return windowsAfterMs; // On the left-JoinSide we look back in time
            } else {
                return windowsBeforeMs; // On the right-JoinSide we look forward in time
            }
        }

        @Override
        public void close() {
            sharedTimeTrackerSupplier.remove(context().taskId());
        }
    }
}
