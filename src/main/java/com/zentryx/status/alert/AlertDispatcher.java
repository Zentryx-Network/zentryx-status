package com.zentryx.status.alert;

import com.zentryx.status.config.StatusProperties;
import com.zentryx.status.domain.HealthCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends Discord webhook alerts on target state changes (OK→DOWN, DOWN→OK).
 * Includes a per-target cooldown so a flapping target doesn't generate
 * one notification every 30 seconds.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final WebClient client;
    private final StatusProperties props;
    private final Map<String, Instant> lastAlertAt = new ConcurrentHashMap<>();

    public AlertDispatcher(WebClient client, StatusProperties props) {
        this.client = client;
        this.props = props;
    }

    public void onStateChange(StatusProperties.Target target, HealthCheck check, boolean prevOk) {
        String webhook = props.alerts().discordWebhook();
        if (webhook == null || webhook.isBlank()) {
            log.debug("alert skipped: no Discord webhook configured");
            return;
        }
        if (inCooldown(target.name())) {
            log.info("alert suppressed: target={} still in cooldown window", target.name());
            return;
        }

        boolean recovered = check.isOk();
        String emoji = recovered ? "🟢" : "🔴";
        String title = recovered ? "RECOVERED" : "DOWN";
        String body = """
                {
                  "username": "Zentryx Status",
                  "embeds": [{
                    "title": "%s %s · %s",
                    "color": %d,
                    "fields": [
                      { "name": "URL",      "value": "%s", "inline": false },
                      { "name": "HTTP",     "value": "%s", "inline": true  },
                      { "name": "Latency",  "value": "%d ms", "inline": true },
                      { "name": "Error",    "value": "%s", "inline": false }
                    ],
                    "timestamp": "%s"
                  }]
                }
                """.formatted(
                emoji, title, escape(target.name()),
                recovered ? 0x4ade80 : 0xef4444,
                escape(target.url()),
                check.getHttpStatus() == 0 ? "no response" : Integer.toString(check.getHttpStatus()),
                check.getLatencyMs(),
                check.getError() == null ? "—" : escape(check.getError()),
                check.getCheckedAt().toString()
        );

        client.post().uri(webhook)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(5))
                .doOnSuccess(r -> log.info("alert sent: target={} status={}", target.name(), title))
                .doOnError(e -> log.warn("alert failed: target={} err={}", target.name(), e.getMessage()))
                .subscribe();

        lastAlertAt.put(target.name(), check.getCheckedAt());
    }

    private boolean inCooldown(String target) {
        Instant prev = lastAlertAt.get(target);
        if (prev == null) return false;
        return prev.plus(Duration.ofMinutes(props.alerts().cooldownMinutes())).isAfter(Instant.now());
    }

    /** Minimal JSON-string escape — Discord embeds reject unescaped quotes/backslashes/newlines. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
