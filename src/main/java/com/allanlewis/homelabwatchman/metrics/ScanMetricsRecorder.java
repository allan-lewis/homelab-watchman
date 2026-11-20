package com.allanlewis.homelabwatchman.metrics;

import com.allanlewis.homelabwatchman.plan.UpdateKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Records this run's results into the PrometheusMeterRegistry, then scrapes
 * it to a textfile-collector file - matches allans-home-lab's existing
 * node_exporter --collector.textfile.directory pattern, no live endpoint or
 * Pushgateway needed for a one-shot CLI.
 */
@Component
public class ScanMetricsRecorder {

    private final PrometheusMeterRegistry registry;
    private final AtomicLong lastRunTimestampSeconds = new AtomicLong();

    public ScanMetricsRecorder(PrometheusMeterRegistry registry) {
        this.registry = registry;
        registry.gauge("homelab_watchman_last_run_timestamp_seconds", lastRunTimestampSeconds);
    }

    public void recordCandidatesByKind(Map<UpdateKind, Long> countsByKind) {
        for (UpdateKind kind : UpdateKind.values()) {
            Counter.builder("homelab_watchman_candidates_total")
                    .tag("kind", kind.name())
                    .register(registry)
                    .increment(countsByKind.getOrDefault(kind, 0L));
        }
    }

    public void recordPrsOpened(int count) {
        Counter.builder("homelab_watchman_prs_opened_total").register(registry).increment(count);
    }

    public void writeTo(Path outputPath) {
        lastRunTimestampSeconds.set(System.currentTimeMillis() / 1000);

        try {
            Files.createDirectories(outputPath.getParent());
            Files.writeString(outputPath, registry.scrape());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
