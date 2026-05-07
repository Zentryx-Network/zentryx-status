package com.zentryx.status.poll;

import com.zentryx.status.domain.HealthCheckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Daily prune of checks older than 30 days — keeps H2 small. */
@Component
public class Pruner {

    private static final Logger log = LoggerFactory.getLogger(Pruner.class);
    private static final Duration RETENTION = Duration.ofDays(30);

    private final HealthCheckRepository repo;

    public Pruner(HealthCheckRepository repo) {
        this.repo = repo;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Bogota")  // 03:30 every day
    public void prune() {
        Instant cutoff = Instant.now().minus(RETENTION);
        int deleted = repo.pruneOlderThan(cutoff);
        log.info("pruned {} checks older than {}", deleted, cutoff);
    }
}
