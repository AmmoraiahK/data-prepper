/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.pipeline;

import org.opensearch.dataprepper.model.CheckpointState;
import org.opensearch.dataprepper.model.buffer.Buffer;
import org.opensearch.dataprepper.model.processor.Processor;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.pipeline.common.FutureHelper;
import org.opensearch.dataprepper.pipeline.common.FutureHelperResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

@SuppressWarnings({"rawtypes", "unchecked"})
public class ProcessWorker implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessWorker.class);

    private final Buffer readBuffer;
    private final List<Processor> processors;
    private final Pipeline pipeline;
    private boolean isEmptyRecordsLogged = false;

    public ProcessWorker(
            final Buffer readBuffer,
            final List<Processor> processors,
            final Pipeline pipeline) {
        this.readBuffer = readBuffer;
        this.processors = processors;
        this.pipeline = pipeline;
    }

    @Override
    public void run() {
        try {
            // Phase 1 - execute until stop requested
            while (!pipeline.isStopRequested()) {
                doRun();
            }
            LOG.info("Processor shutdown phase 1 complete.");

            // Phase 2 - execute until buffers are empty
            LOG.info("Beginning processor shutdown phase 2, iterating until buffers empty.");
            while (!readBuffer.isEmpty()) {
                doRun();
            }
            LOG.info("Processor shutdown phase 2 complete.");

            // Phase 3 - execute until peer forwarder drain period expires (best effort to process all peer forwarder data)
            final long drainTimeoutExpiration = System.currentTimeMillis() + pipeline.getPeerForwarderDrainTimeout().toMillis();
            LOG.info("Beginning processor shutdown phase 3, iterating until {}.", drainTimeoutExpiration);
            while (System.currentTimeMillis() < drainTimeoutExpiration) {
                doRun();
            }
            LOG.info("Processor shutdown phase 3 complete.");

            // Phase 4 - prepare processors for shutdown
            LOG.info("Beginning processor shutdown phase 4, preparing processors for shutdown.");
            processors.forEach(Processor::prepareForShutdown);
            LOG.info("Processor shutdown phase 4 complete.");

            // Phase 5 - execute until processors are ready to shutdown
            LOG.info("Beginning processor shutdown phase 5, iterating until processors are ready to shutdown.");
            while (!areComponentsReadyForShutdown()) {
                doRun();
            }
            LOG.info("Processor shutdown phase 5 complete.");
        } catch (final Exception e) {
            LOG.error("Encountered exception during pipeline {} processing", pipeline.getName(), e);
        }
    }

    private void doRun() {
        final Map.Entry<Collection, CheckpointState> readResult = readBuffer.read(pipeline.getReadBatchTimeoutInMillis());
        Collection records = readResult.getKey();
        final CheckpointState checkpointState = readResult.getValue();
        //TODO Hacky way to avoid logging continuously - Will be removed as part of metrics implementation
        if (records.isEmpty()) {
            if(!isEmptyRecordsLogged) {
                LOG.debug(" {} Worker: No records received from buffer", pipeline.getName());
                isEmptyRecordsLogged = true;
            }
        } else {
            LOG.debug(" {} Worker: Processing {} records from buffer", pipeline.getName(), records.size());
        }
        //Should Empty list from buffer should be sent to the processors? For now sending as the Stateful processors expects it.
        for (final Processor processor : processors) {
            records = processor.execute(records);
        }
        if (!records.isEmpty()) {
            postToSink(records);
        }
        // Checkpoint the current batch read from the buffer after being processed by processors and sinks.
        readBuffer.checkpoint(checkpointState);
    }

    private boolean areComponentsReadyForShutdown() {
        return readBuffer.isEmpty() && processors.stream()
                .map(Processor::isReadyForShutdown)
                .allMatch(result -> result == true);
    }

    /**
     * TODO Add isolator pattern - Fail if one of the Sink fails [isolator Pattern]
     * Uses the pipeline method to publish to sinks, waits for each of the sink result to be true before attempting to
     * process more records from buffer.
     */
    private boolean postToSink(final Collection<Record> records) {
        LOG.debug("Pipeline Worker: Submitting {} processed records to sinks", records.size());
        final List<Future<Void>> sinkFutures = pipeline.publishToSinks(records);
        final FutureHelperResult<Void> futureResults = FutureHelper.awaitFuturesIndefinitely(sinkFutures);
        return futureResults.getFailedReasons().size() == 0;
    }
}
