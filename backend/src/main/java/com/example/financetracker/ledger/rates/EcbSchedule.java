package com.example.financetracker.ledger.rates;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Loads the ECB's rates at startup and then on the ECB's working days after it publishes (app.rates.ecb.cron). A load
 * that fails is logged, and the next one catches up. Off where app.rates.ecb.enabled is false.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EcbProperties.class)
class EcbSchedule {

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "app.rates.ecb.enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {

        private static final Logger log = LoggerFactory.getLogger(EcbSchedule.class);

        private final EcbRateLoader loader;
        private final TaskScheduler scheduler;

        Enabled(EcbRateLoader loader, TaskScheduler scheduler) {
            this.loader = loader;
            this.scheduler = scheduler;
        }

        /** In the background, so that a slow ECB doesn't hold up anything else. */
        @EventListener(ApplicationReadyEvent.class)
        void loadAtStartup() {
            scheduler.schedule(this::load, Instant.now());
        }

        @Scheduled(cron = "${app.rates.ecb.cron}", zone = "Europe/Berlin")
        void load() {
            try {
                loader.load();
            } catch (RuntimeException e) {
                log.warn("Could not load the ECB's exchange rates; the next scheduled load tries again", e);
            }
        }
    }
}
