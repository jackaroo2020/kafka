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
package org.apache.kafka.streams.processor.internals;

import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownProducerIdException;
import org.apache.kafka.common.utils.LogContext;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.errors.TaskMigratedException;
import org.apache.kafka.streams.processor.TaskId;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Future;

import static org.apache.kafka.streams.processor.internals.ClientUtils.getTaskProducerClientId;
import static org.apache.kafka.streams.processor.internals.ClientUtils.getThreadProducerClientId;
import static org.apache.kafka.streams.processor.internals.StreamThread.ProcessingMode.EXACTLY_ONCE_BETA;

/**
 * {@code StreamsProducer} manages the producers within a Kafka Streams application.
 * <p>
 * If EOS is enabled, it is responsible to init and begin transactions if necessary.
 * It also tracks the transaction status, ie, if a transaction is in-fight.
 * <p>
 * For non-EOS, the user should not call transaction related methods.
 */
public class StreamsProducer {
    private final Logger log;
    private final String logPrefix;

    private final Map<String, Object> eosBetaProducerConfigs;
    private final KafkaClientSupplier clientSupplier;
    private final StreamThread.ProcessingMode processingMode;

    private Producer<byte[], byte[]> producer;
    private boolean transactionInFlight = false;
    private boolean transactionInitialized = false;

    public StreamsProducer(final StreamsConfig config,
                           final String threadId,
                           final KafkaClientSupplier clientSupplier,
                           final TaskId taskId,
                           final UUID processId,
                           final LogContext logContext) {
        Objects.requireNonNull(config, "config cannot be null");
        Objects.requireNonNull(threadId, "threadId cannot be null");
        this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier cannot be null");
        log = Objects.requireNonNull(logContext, "logContext cannot be null").logger(getClass());
        logPrefix = logContext.logPrefix().trim();

        processingMode = StreamThread.processingMode(config);

        final Map<String, Object> producerConfigs;
        switch (processingMode) {
            case AT_LEAST_ONCE: {
                producerConfigs = config.getProducerConfigs(getThreadProducerClientId(threadId));
                eosBetaProducerConfigs = null;

                break;
            }
            case EXACTLY_ONCE_ALPHA: {
                producerConfigs = config.getProducerConfigs(
                    getTaskProducerClientId(
                        threadId,
                        Objects.requireNonNull(taskId, "taskId cannot be null for exactly-once alpha")
                    )
                );

                final String applicationId = config.getString(StreamsConfig.APPLICATION_ID_CONFIG);
                producerConfigs.put(ProducerConfig.TRANSACTIONAL_ID_CONFIG, applicationId + "-" + taskId);

                eosBetaProducerConfigs = null;

                break;
            }
            case EXACTLY_ONCE_BETA: {
                producerConfigs = config.getProducerConfigs(getThreadProducerClientId(threadId));

                final String applicationId = config.getString(StreamsConfig.APPLICATION_ID_CONFIG);
                producerConfigs.put(
                    ProducerConfig.TRANSACTIONAL_ID_CONFIG,
                    applicationId + "-" +
                        Objects.requireNonNull(processId, "processId cannot be null for exactly-once beta") +
                        "-" + threadId.split("-StreamThread-")[1]);

                eosBetaProducerConfigs = producerConfigs;

                break;
            }
            default:
                throw new IllegalArgumentException("Unknown processing mode: " + processingMode);
        }

        producer = clientSupplier.getProducer(producerConfigs);
    }

    private String formatException(final String message) {
        return message + " [" + logPrefix + "]";
    }

    boolean eosEnabled() {
        return processingMode == StreamThread.ProcessingMode.EXACTLY_ONCE_ALPHA ||
            processingMode == StreamThread.ProcessingMode.EXACTLY_ONCE_BETA;
    }

    /**
     * @throws IllegalStateException if EOS is disabled
     */
    void initTransaction() {
        if (!eosEnabled()) {
            throw new IllegalStateException(formatException("Exactly-once is not enabled"));
        }
        if (!transactionInitialized) {
            // initialize transactions if eos is turned on, which will block if the previous transaction has not
            // completed yet; do not start the first transaction until the topology has been initialized later
            try {
                producer.initTransactions();
                transactionInitialized = true;
            } catch (final TimeoutException exception) {
                log.warn(
                    "Timeout exception caught trying to initialize transactions. " +
                        "The broker is either slow or in bad state (like not having enough replicas) in " +
                        "responding to the request, or the connection to broker was interrupted sending " +
                        "the request or receiving the response. " +
                        "Will retry initializing the task in the next loop. " +
                        "Consider overwriting {} to a larger value to avoid timeout errors",
                    ProducerConfig.MAX_BLOCK_MS_CONFIG
                );

                throw exception;
            } catch (final KafkaException exception) {
                throw new StreamsException(
                    formatException("Error encountered trying to initialize transactions"),
                    exception
                );
            }
        }
    }

    public void resetProducer() {
        if (processingMode != EXACTLY_ONCE_BETA) {
            throw new IllegalStateException(formatException("Exactly-once beta is not enabled"));
        }

        producer.close();

        producer = clientSupplier.getProducer(eosBetaProducerConfigs);
        transactionInitialized = false;
    }

    private void maybeBeginTransaction() throws ProducerFencedException {
        if (eosEnabled() && !transactionInFlight) {
            try {
                producer.beginTransaction();
                transactionInFlight = true;
            } catch (final ProducerFencedException error) {
                throw new TaskMigratedException(
                    formatException("Producer got fenced trying to begin a new transaction"),
                    error
                );
            } catch (final KafkaException error) {
                throw new StreamsException(
                    formatException("Error encountered trying to begin a new transaction"),
                    error
                );
            }
        }
    }

    Future<RecordMetadata> send(final ProducerRecord<byte[], byte[]> record,
                                final Callback callback) {
        maybeBeginTransaction();
        try {
            return producer.send(record, callback);
        } catch (final KafkaException uncaughtException) {
            if (isRecoverable(uncaughtException)) {
                // producer.send() call may throw a KafkaException which wraps a FencedException,
                // in this case we should throw its wrapped inner cause so that it can be
                // captured and re-wrapped as TaskMigrationException
                throw new TaskMigratedException(
                    formatException("Producer got fenced trying to send a record"),
                    uncaughtException.getCause()
                );
            } else {
                throw new StreamsException(
                    formatException(String.format("Error encountered trying to send record to topic %s", record.topic())),
                    uncaughtException
                );
            }
        }
    }

    private static boolean isRecoverable(final KafkaException uncaughtException) {
        return uncaughtException.getCause() instanceof ProducerFencedException ||
            uncaughtException.getCause() instanceof UnknownProducerIdException;
    }

    /**
     * @throws IllegalStateException if EOS is disabled
     * @throws TaskMigratedException
     */
    void commitTransaction(final Map<TopicPartition, OffsetAndMetadata> offsets,
                           final ConsumerGroupMetadata consumerGroupMetadata) throws ProducerFencedException {
        if (!eosEnabled()) {
            throw new IllegalStateException(formatException("Exactly-once is not enabled"));
        }
        maybeBeginTransaction();
        try {
            producer.sendOffsetsToTransaction(offsets, consumerGroupMetadata);
            producer.commitTransaction();
            transactionInFlight = false;
        } catch (final ProducerFencedException | CommitFailedException error) {
            throw new TaskMigratedException(
                formatException("Producer got fenced trying to commit a transaction"),
                error
            );
        } catch (final TimeoutException error) {
            // TODO KIP-447: we can consider treating it as non-fatal and retry on the thread level
            throw new StreamsException(formatException("Timed out trying to commit a transaction"), error);
        } catch (final KafkaException error) {
            throw new StreamsException(
                formatException("Error encountered trying to commit a transaction"),
                error
            );
        }
    }

    /**
     * @throws IllegalStateException if EOS is disabled
     */
    void abortTransaction() throws ProducerFencedException {
        if (!eosEnabled()) {
            throw new IllegalStateException(formatException("Exactly-once is not enabled"));
        }
        if (transactionInFlight) {
            try {
                producer.abortTransaction();
            } catch (final ProducerFencedException error) {
                // The producer is aborting the txn when there's still an ongoing one,
                // which means that we did not commit the task while closing it, which
                // means that it is a dirty close. Therefore it is possible that the dirty
                // close is due to an fenced exception already thrown previously, and hence
                // when calling abortTxn here the same exception would be thrown again.
                // Even if the dirty close was not due to an observed fencing exception but
                // something else (e.g. task corrupted) we can still ignore the exception here
                // since transaction already got aborted by brokers/transactional-coordinator if this happens
                log.debug("Encountered {} while aborting the transaction; this is expected and hence swallowed", error.getMessage());
            } catch (final KafkaException error) {
                throw new StreamsException(
                    formatException("Error encounter trying to abort a transaction"),
                    error
                );
            }
            transactionInFlight = false;
        }
    }

    List<PartitionInfo> partitionsFor(final String topic) throws TimeoutException {
        return producer.partitionsFor(topic);
    }

    Map<MetricName, ? extends Metric> metrics() {
        return producer.metrics();
    }

    void flush() {
        producer.flush();
    }

    void close() {
        producer.close();
    }

    // for testing only
    Producer<byte[], byte[]> kafkaProducer() {
        return producer;
    }
}
