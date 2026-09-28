package com.demo.urlshortner.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Fails application startup immediately when the "prod" profile is active and DB_USERNAME or
 * DB_PASSWORD is missing, instead of silently falling back to a default or letting an
 * unresolved placeholder leak into the datasource URL (see application-prod.properties, which
 * has no default for either).
 *
 * <p>Runs as an {@link EnvironmentPostProcessor}, before any bean (including the DataSource) is
 * created, so the check can't lose a bean-creation-order race the way a {@code @Configuration}
 * bean did in practice: DataSource/Flyway initialization ran first and failed with a cryptic
 * Flyway placeholder-parsing error instead of this class's clear message.
 */
public class ProdCredentialsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) {
            return;
        }
        requireProperty(environment, "DB_USERNAME");
        requireProperty(environment, "DB_PASSWORD");
    }

    private static void requireProperty(ConfigurableEnvironment environment, String name) {
        if (environment.getProperty(name) == null) {
            throw new IllegalStateException(
                    "Missing required environment variable '" + name + "' for the prod profile. "
                            + "Set it explicitly; there is no default credential.");
        }
    }
}
