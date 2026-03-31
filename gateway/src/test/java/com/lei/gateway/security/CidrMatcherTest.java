package com.lei.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CidrMatcherTest {

    private final CidrMatcher matcher = new CidrMatcher();

    @Test
    void shouldMatchSingleIp() {
        assertThat(matcher.matches("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(matcher.matches("127.0.0.2", "127.0.0.1")).isFalse();
    }

    @Test
    void shouldMatchIpv4Cidr() {
        assertThat(matcher.matches("10.1.2.3", "10.0.0.0/8")).isTrue();
        assertThat(matcher.matches("11.1.2.3", "10.0.0.0/8")).isFalse();
    }

    @Test
    void invalidRuleShouldReturnFalse() {
        assertThat(matcher.matches("10.1.1.1", "bad-cidr")).isFalse();
    }
}
