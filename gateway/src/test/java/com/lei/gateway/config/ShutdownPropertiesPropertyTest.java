package com.lei.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/**
 * Feature: graceful-shutdown, Property 1: 配置校验边界。
 */
class ShutdownPropertiesPropertyTest {

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    /**
     * shutdownTimeoutSeconds < 1 应被拒绝。
     */
    @Property(tries = 100)
    void shutdownTimeoutBelowMinIsRejected(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = 0) int timeout) {
        ShutdownProperties props = new ShutdownProperties();
        props.setShutdownTimeoutSeconds(timeout);

        assertThat(VALIDATOR.validateProperty(props, "shutdownTimeoutSeconds"))
                .isNotEmpty();
    }

    /**
     * shutdownTimeoutSeconds >= 1 应通过校验。
     */
    @Property(tries = 100)
    void shutdownTimeoutAtOrAboveMinIsAccepted(
            @ForAll @IntRange(min = 1, max = 10_000) int timeout) {
        ShutdownProperties props = new ShutdownProperties();
        props.setShutdownTimeoutSeconds(timeout);

        assertThat(VALIDATOR.validateProperty(props, "shutdownTimeoutSeconds"))
                .isEmpty();
    }

    /**
     * startupDelaySeconds < 0 应被拒绝。
     */
    @Property(tries = 100)
    void startupDelayBelowMinIsRejected(
            @ForAll @IntRange(min = Integer.MIN_VALUE, max = -1) int delay) {
        HealthProperties props = new HealthProperties();
        props.setStartupDelaySeconds(delay);

        assertThat(VALIDATOR.validateProperty(props, "startupDelaySeconds"))
                .isNotEmpty();
    }

    /**
     * startupDelaySeconds >= 0 应通过校验。
     */
    @Property(tries = 100)
    void startupDelayAtOrAboveMinIsAccepted(
            @ForAll @IntRange(min = 0, max = 10_000) int delay) {
        HealthProperties props = new HealthProperties();
        props.setStartupDelaySeconds(delay);

        assertThat(VALIDATOR.validateProperty(props, "startupDelaySeconds"))
                .isEmpty();
    }
}
