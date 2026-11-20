package com.allanlewis.homelabwatchman.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Manual PrometheusMeterRegistry bean: we deliberately don't pull in
 * spring-boot-starter-actuator (which would auto-configure this, but also
 * implies a live /actuator/prometheus endpoint) - metrics here are written
 * to a textfile-collector file instead. See ScanMetricsRecorder.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }
}
