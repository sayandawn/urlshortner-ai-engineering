package com.demo.urlshortner.repository;

import com.demo.urlshortner.entity.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Convenience overload used as a {@code ClickRecorder} method reference (single id parameter);
     * stamps lastClickedAt with the current time.
     */
    default int incrementClickCount(Long id) {
        return incrementClickCount(id, Instant.now());
    }

    /**
     * Atomic in-database increment of clickCount and lastClickedAt, so concurrent redirects do not
     * lose updates. Transactional on its own, so each (retried) call from the async click recorder
     * is a fresh transaction. clickedAt is passed in (rather than CURRENT_TIMESTAMP) because
     * Hibernate rejects assigning a java.sql.Timestamp expression to an Instant-typed column.
     */
    @Transactional
    @Modifying
    @Query("update UrlMapping m set m.clickCount = m.clickCount + 1, m.lastClickedAt = :clickedAt where m.id = :id")
    int incrementClickCount(@Param("id") Long id, @Param("clickedAt") Instant clickedAt);
}
