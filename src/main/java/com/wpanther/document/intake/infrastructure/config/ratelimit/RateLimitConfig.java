package com.wpanther.document.intake.infrastructure.config.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Rate limiting configuration for the document intake REST endpoint.
 * <p>
 * Registers {@link RateLimitProperties} as a configuration-properties bean so its values
 * ({@code app.rate-limit.requests-per-second}, {@code app.rate-limit.time-period-seconds})
 * are available for Spring property interpolation in {@code application.yml}, where they
 * feed the Resilience4j rate-limiter instance configuration:
 * <pre>
 * resilience4j.ratelimiter.instances.documentIntake.limit-for-period:
 *     ${app.rate-limit.requests-per-second:10}
 * resilience4j.ratelimiter.instances.documentIntake.limit-refresh-period:
 *     "${app.rate-limit.time-period-seconds:60}s"
 * </pre>
 * Rate limiting can be disabled by setting {@code app.rate-limit.enabled=false} in
 * application properties (the Resilience4j bean still loads; no requests will be
 * throttled if the rate limiter is not applied via annotation).
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private final RateLimitProperties properties;

    public RateLimitConfig(RateLimitProperties properties) {
        this.properties = properties;
        log.info("Configuring rate limiting: {} requests/{} seconds per client",
            properties.getRequestsPerSecond(), properties.getTimePeriodSeconds());
    }

    /**
     * Calculate the maximum requests per period.
     */
    public long getMaximumRequestsPerPeriod() {
        return properties.getRequestsPerSecond() * properties.getTimePeriodSeconds();
    }
}
