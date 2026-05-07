package com.zentryx.status.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Typed configuration mirror of the {@code zentryx.status.*} block
 * in application.yml. Spring Boot validates the file at startup —
 * a missing target list or a negative interval fails fast with a
 * readable error instead of silently misbehaving.
 */
@ConfigurationProperties(prefix = "zentryx.status")
public record StatusProperties(
        @Min(5_000) long pollIntervalMs,
        @Min(1_000) long timeoutMs,
        @NotEmpty List<Target> targets,
        @NotNull Alerts alerts,
        @NotNull Digest digest
) {
    public record Target(
            @NotEmpty String name,
            @NotEmpty String url,
            int expect
    ) {}

    public record Alerts(
            String discordWebhook,
            @Min(1) int cooldownMinutes
    ) {}

    public record Digest(
            boolean enabled,
            String from,
            String to,
            String resendApiKey,
            String cron
    ) {}
}
