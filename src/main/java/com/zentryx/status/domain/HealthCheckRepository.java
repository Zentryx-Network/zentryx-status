package com.zentryx.status.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface HealthCheckRepository extends JpaRepository<HealthCheck, Long> {

    Optional<HealthCheck> findFirstByTargetOrderByCheckedAtDesc(String target);

    List<HealthCheck> findByCheckedAtAfterOrderByCheckedAtAsc(Instant since);

    List<HealthCheck> findByTargetAndCheckedAtAfterOrderByCheckedAtAsc(String target, Instant since);

    /**
     * Append-only table — we run a daily prune to keep H2 from growing
     * forever. 30 days of 4 targets × every 30s ≈ 350k rows, fine for H2,
     * but no reason to keep checks that nobody will ever look at.
     */
    @Modifying
    @Transactional
    @Query("delete from HealthCheck h where h.checkedAt < :cutoff")
    int pruneOlderThan(@Param("cutoff") Instant cutoff);
}
