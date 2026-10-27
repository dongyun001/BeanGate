package io.beangate.usage;

import io.beangate.config.BeanGateProperties;
import io.beangate.ratelimit.RateLimitTokenSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Usage metering off the forwarding path.
 *
 * <p>Requests enqueue a record and return immediately; one writer thread drains batches and persists
 * them. The queue is bounded and drops on overflow: metering availability must never take down a
 * paid forwarding path. Drops are counted, and a full queue is a signal, not an error.
 */
@Component
public class AsyncUsageRecorder implements UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(AsyncUsageRecorder.class);

    private final BlockingQueue<UsageRecord> queue;
    private final ExecutorService writer;
    private final UsageLogRepository repository;
    private final RateLimitTokenSink tokenSink;
    private final Counter droppedCounter;
    private final int batchSize;
    private final long flushIntervalMs;
    private final AtomicLong dropped = new AtomicLong();
    /** Records accepted but not yet written (or explicitly dropped after a failure). */
    private final java.util.concurrent.atomic.AtomicLong pending =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;

    public AsyncUsageRecorder(BeanGateProperties properties, UsageLogRepository repository,
                              RateLimitTokenSink tokenSink, MeterRegistry meterRegistry) {
        BeanGateProperties.Usage config = properties.usage();
        this.queue = new ArrayBlockingQueue<>(Math.max(1, config.queueCapacity()));
        this.batchSize = Math.max(1, config.batchSize());
        this.flushIntervalMs = Math.max(10, config.flushIntervalMs());
        this.repository = repository;
        this.tokenSink = tokenSink;
        this.droppedCounter = Counter.builder("beangate.usage.dropped")
                .description("Usage records dropped because the queue was full or the DB write failed")
                .register(meterRegistry);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "beangate-usage-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostConstruct
    void start() {
        writer.submit(this::runLoop);
    }

    @Override
    public void record(UsageRecord record) {
        if (queue.offer(record)) {
            pending.incrementAndGet();
        } else {
            dropped.incrementAndGet();
            droppedCounter.increment();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    /** Visible for tests: blocks until every accepted record has been written or dropped. */
    public void flush() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pending.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
    }

    private void runLoop() {
        List<UsageRecord> batch = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                UsageRecord first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                try {
                    queue.drainTo(batch, batchSize - 1);
                    persist(batch);
                } finally {
                    pending.addAndGet(-batch.size());
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("usage writer loop failed", e);
                batch.clear();
            }
        }
    }

    private void persist(List<UsageRecord> batch) {
        feedTokenCounters(batch);
        try {
            repository.insertBatch(batch);
        } catch (DuplicateKeyException duplicate) {
            // A retried batch after a partial success: keep every row, ignore the already-written ones.
            log.warn("duplicate request_id in usage batch of {}, falling back to row-by-row", batch.size());
            repository.insertOneByOneIgnoringDuplicates(batch,
                    record -> log.debug("usage record already persisted: {}", record.requestId()));
        } catch (DataAccessException firstFailure) {
            log.warn("usage batch insert failed ({} records), retrying once: {}",
                    batch.size(), firstFailure.getMessage());
            sleepQuietly(200);
            try {
                repository.insertBatch(batch);
            } catch (RuntimeException secondFailure) {
                dropped.addAndGet(batch.size());
                for (int i = 0; i < batch.size(); i++) {
                    droppedCounter.increment();
                }
                log.error("dropping {} usage records after repeated DB failure: {}",
                        batch.size(), secondFailure.getMessage());
            }
        }
    }

    /**
     * TPM is enforced on already-consumed tokens (see the rate limiter), so the writer is the right
     * place to add them: it keeps the forwarding thread off Redis.
     */
    private void feedTokenCounters(List<UsageRecord> batch) {
        for (UsageRecord record : batch) {
            if (record.apiKeyId() != null && record.totalTokens() != null && record.totalTokens() > 0) {
                try {
                    tokenSink.addTokens(record.apiKeyId(), record.totalTokens());
                } catch (RuntimeException e) {
                    log.debug("could not feed TPM counter: {}", e.getMessage());
                }
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void shutdown() {
        running = false;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}
