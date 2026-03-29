package com.lei.gateway.perf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EnvSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void captureReturnsCpuCoresGreaterThanZero() {
        EnvSnapshotData snapshot = EnvSnapshot.capture();
        assertThat(snapshot.cpuCores()).isGreaterThan(0);
    }

    @Test
    void captureReturnsAvailableMemoryGreaterThanZero() {
        EnvSnapshotData snapshot = EnvSnapshot.capture();
        assertThat(snapshot.availableMemoryMb()).isGreaterThan(0);
    }

    @Test
    void captureReturnsNonEmptyOsName() {
        EnvSnapshotData snapshot = EnvSnapshot.capture();
        assertThat(snapshot.osName()).isNotBlank();
    }

    @Test
    void captureReturnsNonEmptyJvmVersion() {
        EnvSnapshotData snapshot = EnvSnapshot.capture();
        assertThat(snapshot.jvmVersion()).isNotBlank();
    }

    @Test
    void captureReturnsNonEmptyGatlingVersion() {
        EnvSnapshotData snapshot = EnvSnapshot.capture();
        assertThat(snapshot.gatlingVersion()).isNotBlank();
    }

    @Test
    void jsonRoundTrip() throws Exception {
        EnvSnapshotData original = new EnvSnapshotData(
                4, 3800L, 1.5, 1.2, 0.9,
                "Linux", "5.15.0", "21.0.3", "-Xmx3g",
                "3.10.5", Instant.parse("2025-01-15T10:30:00Z")
        );

        String json = mapper.writeValueAsString(original);
        EnvSnapshotData deserialized = mapper.readValue(json, EnvSnapshotData.class);

        assertThat(deserialized.cpuCores()).isEqualTo(original.cpuCores());
        assertThat(deserialized.availableMemoryMb()).isEqualTo(original.availableMemoryMb());
        assertThat(deserialized.loadAvg1min()).isEqualTo(original.loadAvg1min());
        assertThat(deserialized.osName()).isEqualTo(original.osName());
        assertThat(deserialized.jvmVersion()).isEqualTo(original.jvmVersion());
        assertThat(deserialized.gatlingVersion()).isEqualTo(original.gatlingVersion());
        assertThat(deserialized.capturedAt()).isEqualTo(original.capturedAt());
    }
}
