package com.zentryx.status.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Single observation of a single target. Inserted by the poller every
 * 30s; we never UPDATE rows so this table is append-only — easy to
 * back up, easy to reason about for time-series queries.
 */
@Entity
@Table(
        name = "health_check",
        indexes = {
                @Index(name = "idx_check_target_time", columnList = "target,checkedAt DESC"),
                @Index(name = "idx_check_time", columnList = "checkedAt DESC"),
        }
)
public class HealthCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String target;

    @Column(nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private boolean ok;

    @Column(nullable = false)
    private int httpStatus;        // 0 if request failed before getting a response

    @Column(nullable = false)
    private long latencyMs;        // 0 if request failed before getting a response

    @Column(length = 256)
    private String error;          // null on success

    protected HealthCheck() { /* JPA */ }

    public HealthCheck(String target, Instant checkedAt, boolean ok,
                       int httpStatus, long latencyMs, String error) {
        this.target = target;
        this.checkedAt = checkedAt;
        this.ok = ok;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.error = error;
    }

    public Long getId()           { return id; }
    public String getTarget()     { return target; }
    public Instant getCheckedAt() { return checkedAt; }
    public boolean isOk()         { return ok; }
    public int getHttpStatus()    { return httpStatus; }
    public long getLatencyMs()    { return latencyMs; }
    public String getError()      { return error; }
}
