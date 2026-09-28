package com.demo.urlshortner.service;

/**
 * Records a successful redirect for analytics. Implementations must not delay or fail the redirect;
 * counts are eventually consistent. A message-broker (e.g. Kafka) publisher can replace the
 * current in-process implementation without changing {@link RedirectService}.
 */
@FunctionalInterface
public interface ClickRecorder {

    void recordClick(Long mappingId);
}
