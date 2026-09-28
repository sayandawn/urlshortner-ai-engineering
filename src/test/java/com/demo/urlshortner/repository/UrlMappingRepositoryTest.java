package com.demo.urlshortner.repository;

import com.demo.urlshortner.entity.UrlMapping;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class UrlMappingRepositoryTest {

    @Autowired
    private UrlMappingRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAndFindsMappingByShortCode() {
        repository.saveAndFlush(new UrlMapping("abc1234", "https://example.com/page"));

        assertThat(repository.findByShortCode("abc1234"))
                .hasValueSatisfying(mapping -> {
                    assertThat(mapping.getId()).isNotNull();
                    assertThat(mapping.getOriginalUrl()).isEqualTo("https://example.com/page");
                    assertThat(mapping.getCreatedAt()).isNotNull();
                });
        assertThat(repository.existsByShortCode("abc1234")).isTrue();
    }

    @Test
    void returnsEmptyForUnknownShortCode() {
        assertThat(repository.findByShortCode("unknown")).isEmpty();
        assertThat(repository.existsByShortCode("unknown")).isFalse();
    }

    @Test
    void newMappingStartsWithZeroClicksAndIncrementsAtomically() {
        UrlMapping saved = repository.saveAndFlush(new UrlMapping("clk1234", "https://example.com"));
        assertThat(saved.getClickCount()).isZero();

        assertThat(repository.incrementClickCount(saved.getId())).isEqualTo(1);
        repository.incrementClickCount(saved.getId());
        entityManager.clear();

        assertThat(repository.findByShortCode("clk1234"))
                .hasValueSatisfying(mapping -> assertThat(mapping.getClickCount()).isEqualTo(2));
    }

    @Test
    void enforcesUniqueShortCode() {
        repository.saveAndFlush(new UrlMapping("dup1234", "https://example.com/a"));

        assertThatThrownBy(() -> repository.saveAndFlush(new UrlMapping("dup1234", "https://example.com/b")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
