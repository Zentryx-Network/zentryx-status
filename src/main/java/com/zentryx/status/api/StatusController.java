package com.zentryx.status.api;

import com.zentryx.status.config.StatusProperties;
import com.zentryx.status.domain.HealthCheck;
import com.zentryx.status.domain.HealthCheckRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public read-only API. Three endpoints:
 *
 * <ul>
 *   <li>GET /api/v1/status         — current snapshot per target</li>
 *   <li>GET /api/v1/uptime?days=N  — uptime % per target over N days</li>
 *   <li>GET /api/v1/history?target=X&hours=H — raw checks for charting</li>
 * </ul>
 *
 * Anyone can hit these — they're meant to be embeddable, scriptable
 * and crawlable (status pages should be transparent).
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class StatusController {

    private final HealthCheckRepository repo;
    private final StatusProperties props;

    public StatusController(HealthCheckRepository repo, StatusProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @GetMapping("/status")
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        Map<String, Object> targets = new LinkedHashMap<>();

        for (StatusProperties.Target t : props.targets()) {
            HealthCheck last = repo.findFirstByTargetOrderByCheckedAtDesc(t.name()).orElse(null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", t.url());
            data.put("expect", t.expect());
            if (last == null) {
                data.put("state", "unknown");
                data.put("checkedAt", null);
            } else {
                data.put("state", last.isOk() ? "ok" : "down");
                data.put("checkedAt", last.getCheckedAt().toString());
                data.put("httpStatus", last.getHttpStatus());
                data.put("latencyMs", last.getLatencyMs());
                if (last.getError() != null) data.put("error", last.getError());
            }
            targets.put(t.name(), data);
        }
        out.put("targets", targets);
        return out;
    }

    @GetMapping("/uptime")
    public Map<String, Object> uptime(@RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 90))));
        List<HealthCheck> rows = repo.findByCheckedAtAfterOrderByCheckedAtAsc(since);

        Map<String, long[]> agg = new HashMap<>();   // target -> [ok, total]
        for (HealthCheck c : rows) {
            long[] v = agg.computeIfAbsent(c.getTarget(), k -> new long[2]);
            if (c.isOk()) v[0]++;
            v[1]++;
        }

        Map<String, Object> targets = new LinkedHashMap<>();
        for (StatusProperties.Target t : props.targets()) {
            long[] v = agg.getOrDefault(t.name(), new long[]{0, 0});
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("checks", v[1]);
            data.put("ok", v[0]);
            data.put("uptimePct", v[1] == 0 ? null : Math.round((v[0] * 10000.0) / v[1]) / 100.0);
            targets.put(t.name(), data);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", days);
        out.put("since", since.toString());
        out.put("targets", targets);
        return out;
    }

    @GetMapping("/history")
    public List<Map<String, Object>> history(
            @RequestParam String target,
            @RequestParam(defaultValue = "24") int hours) {
        Instant since = Instant.now().minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 30))));
        return repo.findByTargetAndCheckedAtAfterOrderByCheckedAtAsc(target, since)
                .stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", c.getCheckedAt().toString());
                    m.put("ok", c.isOk());
                    m.put("httpStatus", c.getHttpStatus());
                    m.put("latencyMs", c.getLatencyMs());
                    return m;
                })
                .toList();
    }
}
