package com.zentryx.status.poll;

import com.zentryx.status.alert.AlertDispatcher;
import com.zentryx.status.config.StatusProperties;
import com.zentryx.status.domain.HealthCheck;
import com.zentryx.status.domain.HealthCheckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hits every configured target every {@code zentryx.status.poll-interval-ms}
 * milliseconds. Stores each observation in H2 and forwards state changes
 * (OK→DOWN, DOWN→OK) to the alert dispatcher.
 *
 * <p>Polling runs in parallel — a slow target never blocks the others.
 * Each request uses the strict timeouts from {@link com.zentryx.status.config.WebClientConfig}.
 */
@Component
public class HealthPoller {

    private static final Logger log = LoggerFactory.getLogger(HealthPoller.class);

    private final WebClient client;
    private final StatusProperties props;
    private final HealthCheckRepository repo;
    private final AlertDispatcher alerts;

    /**
     * Last-known status per target. Used purely to detect TRANSITIONS
     * (rising/falling edges) so we only alert on state changes, not on
     * every consecutive failure.
     */
    private final java.util.Map<String, AtomicReference<Boolean>> lastSeen = new java.util.concurrent.ConcurrentHashMap<>();

    public HealthPoller(WebClient client, StatusProperties props,
                        HealthCheckRepository repo, AlertDispatcher alerts) {
        this.client = client;
        this.props = props;
        this.repo = repo;
        this.alerts = alerts;
    }

    @Scheduled(fixedDelayString = "${zentryx.status.poll-interval-ms}", initialDelay = 5_000)
    public void pollAll() {
        Instant tick = Instant.now();
        Flux.fromIterable(props.targets())
                .flatMap(t -> probe(t, tick).subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.error("poll cycle error", e))
                .subscribe();
    }

    /**
     * Single target probe — returns a Mono of the persisted check.
     * Errors are NOT propagated; failures are recorded as a check
     * with ok=false and the error message stored.
     */
    private reactor.core.publisher.Mono<HealthCheck> probe(StatusProperties.Target t, Instant tick) {
        long started = System.nanoTime();

        return client.get()
                .uri(t.url())
                .exchangeToMono(resp -> reactor.core.publisher.Mono.just(resp.statusCode().value()))
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .map(status -> new HealthCheck(
                        t.name(),
                        tick,
                        status == t.expect(),
                        status,
                        elapsedMs(started),
                        status == t.expect() ? null : "expected " + t.expect() + " got " + status
                ))
                .onErrorResume(err -> reactor.core.publisher.Mono.just(new HealthCheck(
                        t.name(),
                        tick,
                        false,
                        0,
                        elapsedMs(started),
                        err.getClass().getSimpleName() + ": " + truncate(err.getMessage(), 200)
                )))
                .map(check -> {
                    repo.save(check);
                    detectStateChange(t, check);
                    return check;
                });
    }

    private void detectStateChange(StatusProperties.Target t, HealthCheck check) {
        AtomicReference<Boolean> ref = lastSeen.computeIfAbsent(t.name(), k -> new AtomicReference<>());
        Boolean prev = ref.getAndSet(check.isOk());
        if (prev == null) {
            log.info("first observation: target={} ok={} status={} latency={}ms",
                    t.name(), check.isOk(), check.getHttpStatus(), check.getLatencyMs());
            return;
        }
        if (prev != check.isOk()) {
            log.warn("state change: target={} {} -> {} (status={}, error={})",
                    t.name(), prev ? "OK" : "DOWN", check.isOk() ? "OK" : "DOWN",
                    check.getHttpStatus(), check.getError());
            alerts.onStateChange(t, check, prev);
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
