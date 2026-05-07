package com.zentryx.status.notify;

import com.zentryx.status.config.StatusProperties;
import com.zentryx.status.domain.HealthCheck;
import com.zentryx.status.domain.HealthCheckRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sends a weekly summary email via Resend every Monday at 09:00.
 * The cron expression is configurable. The email lists each target's
 * uptime % over the past 7 days, total checks, and worst-case latency.
 *
 * <p>Same Resend API as news-scout uses — the digest is a small piece
 * of the management plane that lives in the same Mailcow / Resend
 * pipeline already running.
 */
@Component
public class WeeklyDigest {

    private static final Logger log = LoggerFactory.getLogger(WeeklyDigest.class);

    private final HealthCheckRepository repo;
    private final StatusProperties props;
    private final WebClient client;

    public WeeklyDigest(HealthCheckRepository repo, StatusProperties props, WebClient client) {
        this.repo = repo;
        this.props = props;
        this.client = client;
    }

    @Scheduled(cron = "${zentryx.status.digest.cron}", zone = "America/Bogota")
    public void send() {
        if (!props.digest().enabled()) {
            log.info("digest disabled — skipping");
            return;
        }
        String apiKey = props.digest().resendApiKey();
        String to = props.digest().to();
        if (apiKey == null || apiKey.isBlank() || to == null || to.isBlank()) {
            log.warn("digest disabled at runtime: api-key or recipient missing");
            return;
        }

        Instant since = Instant.now().minus(Duration.ofDays(7));
        List<HealthCheck> rows = repo.findByCheckedAtAfterOrderByCheckedAtAsc(since);

        Map<String, long[]> agg = new HashMap<>();
        Map<String, Long> worstLatency = new HashMap<>();
        for (HealthCheck c : rows) {
            long[] v = agg.computeIfAbsent(c.getTarget(), k -> new long[2]);
            if (c.isOk()) v[0]++;
            v[1]++;
            if (c.isOk()) {
                worstLatency.merge(c.getTarget(), c.getLatencyMs(), Math::max);
            }
        }

        StringBuilder body = new StringBuilder();
        body.append("<h2>Zentryx weekly status</h2>");
        body.append("<p>Window: last 7 days</p><table style=\"border-collapse:collapse\">");
        body.append("<tr><th align=left>Target</th><th>Checks</th><th>Uptime</th><th>Max latency</th></tr>");
        for (StatusProperties.Target t : props.targets()) {
            long[] v = agg.getOrDefault(t.name(), new long[]{0, 0});
            String pct = v[1] == 0 ? "—" : "%.2f%%".formatted((v[0] * 100.0) / v[1]);
            String maxL = worstLatency.getOrDefault(t.name(), 0L) + " ms";
            body.append("<tr><td>").append(t.name())
                    .append("</td><td>").append(v[1])
                    .append("</td><td>").append(pct)
                    .append("</td><td>").append(maxL).append("</td></tr>");
        }
        body.append("</table>");

        String json = """
                {
                  "from": "%s",
                  "to": ["%s"],
                  "subject": "Zentryx weekly status · %s",
                  "html": %s
                }
                """.formatted(
                escape(props.digest().from()),
                escape(to),
                Instant.now().toString().substring(0, 10),
                quote(body.toString())
        );

        client.post()
                .uri("https://api.resend.com/emails")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .doOnSuccess(r -> log.info("weekly digest sent to {}", to))
                .doOnError(e -> log.error("weekly digest failed: {}", e.getMessage()))
                .subscribe();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                       .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
