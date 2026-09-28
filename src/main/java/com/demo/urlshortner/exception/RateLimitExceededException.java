package com.demo.urlshortner.exception;

import lombok.Getter;

@Getter
public class RateLimitExceededException extends RuntimeException {

    /** Value for the {@code Retry-After} response header. */
    private final long retryAfterSeconds;

    public RateLimitExceededException(String group, long retryAfterSeconds) {
        super("Rate limit exceeded for " + group + " requests. Try again later.");
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
