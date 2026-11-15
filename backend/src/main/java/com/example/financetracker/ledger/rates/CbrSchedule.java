package com.example.financetracker.ledger.rates;

import com.example.financetracker.WallClock;
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
 * Loads the CBR's EUR rate at startup and then at 13:00 and 17:00 Europe/Moscow every day (app.rates.cbr.cron, D-856).
 * A load that fails is logged, and the next one catches up. Off where app.rates.cbr.enabled is false.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CbrProperties.class)
class CbrSchedule {

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "app.rates.cbr.enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {

        private static final Logger log = LoggerFactory.getLogger(CbrSchedule.class);

        private final CbrRateLoader loader;
        private final TaskScheduler scheduler;

        Enabled(CbrRateLoader loader, TaskScheduler scheduler) {
            this.loader = loader;
            this.scheduler = scheduler;
        }

        /** In the background, so that a slow CBR doesn't hold up anything else. */
        @EventListener(ApplicationReadyEvent.class)
        void loadAtStartup() {
            scheduler.schedule(this::load, WallClock.now());
        }

        @Scheduled(cron = "${app.rates.cbr.cron}", zone = "Europe/Moscow")
        void load() {
            try {
                loader.load();
            } catch (RuntimeException e) {
                log.warn("Could not load the CBR's exchange rates; the next scheduled load tries again", e);
            }
        }
    }
}
