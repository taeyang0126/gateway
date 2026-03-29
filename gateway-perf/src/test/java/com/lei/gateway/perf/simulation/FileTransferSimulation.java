package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import com.lei.gateway.perf.util.WarmupHelper;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static io.gatling.javaapi.core.CoreDsl.ByteArrayBody;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 大文件上传/下载性能测试。
 * 覆盖需求 4（文件传输）、需求 20（预热）。
 */
public class FileTransferSimulation extends Simulation {

    private static final int ONE_MB = 1024 * 1024;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int fileUsers = Integer.parseInt(System.getProperty("fileUsers", "10"));
    private final int duration = Integer.parseInt(System.getProperty("duration", "60"));
    private final int warmupDuration = Integer.parseInt(System.getProperty("warmupDuration", "30"));
    private final int warmupUsers = Integer.parseInt(System.getProperty("warmupUsers", "10"));
    private final boolean skipWarmup = Boolean.parseBoolean(System.getProperty("skipWarmup", "false"));

    // 预生成 1MB 随机字节，避免每次请求重新分配
    private final byte[] uploadPayload = generatePayload(ONE_MB);

    private byte[] generatePayload(int size) {
        byte[] data = new byte[size];
        new Random(42).nextBytes(data);
        return data;
    }

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl);

    // 上传 Scenario：1MB 二进制文件
    private final ScenarioBuilder uploadScenario = scenario("文件上传-POST-upload")
            .exec(http("POST /api/example/upload")
                    .post("/api/example/upload")
                    .header("Content-Type", "application/octet-stream")
                    .body(ByteArrayBody(uploadPayload))
                    .check(status().is(200)));

    // 下载 Scenario
    private final ScenarioBuilder downloadScenario = scenario("文件下载-GET-download")
            .exec(http("GET /api/example/download")
                    .get("/api/example/download")
                    .check(status().is(200)));

    private final ScenarioBuilder warmupScenario = scenario("文件传输-预热-POST-upload")
            .exec(http("POST /api/example/upload (warmup)")
                    .post("/api/example/upload")
                    .header("Content-Type", "application/octet-stream")
                    .body(ByteArrayBody(uploadPayload))
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        List<PopulationBuilder> populations = new ArrayList<>();

        PopulationBuilder warmup = WarmupHelper.build(warmupScenario, warmupUsers, warmupDuration, skipWarmup);
        if (warmup != null) {
            populations.add(warmup);
        }

        int delaySeconds = skipWarmup ? 0 : warmupDuration;

        // 上传（低并发，开环）
        populations.add(uploadScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(fileUsers).during(Duration.ofSeconds(duration))
        ));

        // 下载（低并发，开环）
        populations.add(downloadScenario.injectOpen(
                nothingFor(Duration.ofSeconds(delaySeconds)),
                constantUsersPerSec(fileUsers).during(Duration.ofSeconds(duration))
        ));

        setUp(populations.toArray(new PopulationBuilder[0]))
                .protocols(httpProtocol)
                .assertions(
                        global().failedRequests().percent().lt(1.0)
                );

    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
