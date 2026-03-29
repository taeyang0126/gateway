package com.lei.gateway.perf.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: gatling-performance-test
 * Property 9: 环境快照格式不变量
 * Validates: 需求 23.1
 */
class EnvSnapshotPropertyTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    // Property 9：环境快照格式不变量
    // 对于任意测试环境，capture() 返回的 EnvSnapshotData 应包含所有必填字段，
    // 且序列化为 JSON 后可被反序列化为等价对象。
    @Property(tries = 100)
    void envSnapshotJsonRoundTrip(@ForAll("envSnapshotData") EnvSnapshotData original) throws Exception {
        String json = mapper.writeValueAsString(original);
        EnvSnapshotData deserialized = mapper.readValue(json, EnvSnapshotData.class);

        assertThat(deserialized.cpuCores()).isEqualTo(original.cpuCores());
        assertThat(deserialized.availableMemoryMb()).isEqualTo(original.availableMemoryMb());
        assertThat(deserialized.loadAvg1min()).isEqualTo(original.loadAvg1min());
        assertThat(deserialized.loadAvg5min()).isEqualTo(original.loadAvg5min());
        assertThat(deserialized.loadAvg15min()).isEqualTo(original.loadAvg15min());
        assertThat(deserialized.osName()).isEqualTo(original.osName());
        assertThat(deserialized.osVersion()).isEqualTo(original.osVersion());
        assertThat(deserialized.jvmVersion()).isEqualTo(original.jvmVersion());
        assertThat(deserialized.jvmArgs()).isEqualTo(original.jvmArgs());
        assertThat(deserialized.gatlingVersion()).isEqualTo(original.gatlingVersion());
        assertThat(deserialized.capturedAt()).isEqualTo(original.capturedAt());
    }

    @Property(tries = 100)
    void envSnapshotRequiredFieldsNonNull(@ForAll("envSnapshotData") EnvSnapshotData snapshot) {
        assertThat(snapshot.cpuCores()).isGreaterThan(0);
        assertThat(snapshot.availableMemoryMb()).isGreaterThan(0);
        assertThat(snapshot.loadAvg1min()).isGreaterThanOrEqualTo(0);
        assertThat(snapshot.osName()).isNotBlank();
        assertThat(snapshot.jvmVersion()).isNotBlank();
        assertThat(snapshot.gatlingVersion()).isNotBlank();
        assertThat(snapshot.capturedAt()).isNotNull();
    }

    @Provide
    Arbitrary<EnvSnapshotData> envSnapshotData() {
        // jqwik Combinators.combine 最多支持 8 个参数，分两步组合
        Arbitrary<int[]> cpuAndLoad = Combinators.combine(
                Arbitraries.integers().between(1, 128),
                Arbitraries.doubles().between(0.0, 100.0),
                Arbitraries.doubles().between(0.0, 100.0),
                Arbitraries.doubles().between(0.0, 100.0)
        ).as((cpuCores, load1, load5, load15) -> new int[]{cpuCores});

        return Combinators.combine(
                Arbitraries.integers().between(1, 128),
                Arbitraries.longs().between(256, 65536),
                Arbitraries.doubles().between(0.0, 100.0),
                Arbitraries.of("Linux", "Mac OS X", "Windows 10"),
                Arbitraries.of("21.0.3", "17.0.9", "11.0.22"),
                Arbitraries.strings().alpha().ofMaxLength(50),
                Arbitraries.of("3.10.5", "3.10.4")
        ).as((cpuCores, memMb, load, osName, jvmVer, jvmArgs, gatlingVer) ->
                new EnvSnapshotData(cpuCores, memMb, load, load, load,
                        osName, "5.15.0", jvmVer, jvmArgs, gatlingVer,
                        Instant.parse("2025-01-15T10:30:00Z"))
        );
    }
}
