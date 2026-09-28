package com.demo.urlshortner.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as rate limited by the given group's limiter.
 * Endpoints without this annotation (static resources, Swagger/API docs) are never limited.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    RateLimitGroup value();
}
