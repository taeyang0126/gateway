package com.lei.gateway.perf.simulation;

import com.lei.gateway.perf.util.EnvSnapshot;
import com.lei.gateway.perf.util.EnvSnapshotData;
import io.gatling.javaapi.core.OpenInjectionStep;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * 固定 1s 延迟的并发膝点探测。
 *
 * <p>用途：把请求时延抬高到约 1s，更容易触发连接池/stream 并发位点，
 * 用于验证“并发承载极限”，而不是纯吞吐极限。
 */
public class Delay1sKneeSimulation extends Simulation {

    private static final int DEFAULT_START_USERS = 200;
    private static final int DEFAULT_STEP_USERS = 200;
    private static final int DEFAULT_STEP_DURATION_SECONDS = 15;
    private static final int DEFAULT_MAX_STEPS = 5;
    private static final int DEFAULT_PEAK_HOLD_SECONDS = 5;
    private static final int DEFAULT_COOL_DOWN_SECONDS = 5;
    private static final int DEFAULT_COOL_DOWN_TARGET_USERS = 200;

    private final String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    private final int delayMs = Integer.parseInt(System.getProperty("delayMs", "1000"));
    private final int requestTimeoutMillis =
            Integer.parseInt(System.getProperty("requestTimeoutMillis", "3500"));
    private final int clientMaxConnectionsPerHost =
            Integer.parseInt(System.getProperty("clientMaxConnectionsPerHost", "500"));
    private final double maxFailedPercent =
            Double.parseDouble(System.getProperty("maxFailedPercent", "30.0"));
    private final int startUsers =
            Integer.parseInt(System.getProperty("startUsers", String.valueOf(DEFAULT_START_USERS)));
    private final int stepUsers =
            Integer.parseInt(System.getProperty("stepUsers", String.valueOf(DEFAULT_STEP_USERS)));
    private final int stepDurationSeconds = Integer.parseInt(
            System.getProperty("stepDurationSeconds", String.valueOf(DEFAULT_STEP_DURATION_SECONDS)));
    private final int maxSteps =
            Integer.parseInt(System.getProperty("maxSteps", String.valueOf(DEFAULT_MAX_STEPS)));
    private final int peakHoldSeconds = Integer.parseInt(
            System.getProperty("peakHoldSeconds", String.valueOf(DEFAULT_PEAK_HOLD_SECONDS)));
    private final int coolDownSeconds = Integer.parseInt(
            System.getProperty("coolDownSeconds", String.valueOf(DEFAULT_COOL_DOWN_SECONDS)));
    private final int coolDownTargetUsers = Integer.parseInt(System.getProperty(
            "coolDownTargetUsers", String.valueOf(DEFAULT_COOL_DOWN_TARGET_USERS)));

    private final String delayPath = "/api/example/delay/fixed?ms=" + delayMs;

    private final HttpProtocolBuilder httpProtocol = http.baseUrl(baseUrl)
            .acceptHeader("application/json")
            .disableWarmUp()
            .shareConnections()
            .maxConnectionsPerHost(clientMaxConnectionsPerHost);

    private final ScenarioBuilder delayScenario = scenario("膝点探测-GET-delay-1s")
            .exec(http("GET " + delayPath)
                    .get(delayPath)
                    .requestTimeout(Duration.ofMillis(requestTimeoutMillis))
                    .check(status().is(200)));

    private final EnvSnapshotData envStart = EnvSnapshot.capture();

    {
        int effectiveStartUsers = Math.max(1, startUsers);
        int effectiveStepUsers = Math.max(1, stepUsers);
        int effectiveMaxSteps = Math.max(1, maxSteps);
        int effectiveStepDurationSeconds = Math.max(1, stepDurationSeconds);
        int effectivePeakHoldSeconds = Math.max(1, peakHoldSeconds);
        int effectiveCoolDownSeconds = Math.max(1, coolDownSeconds);
        int effectiveCoolDownTargetUsers = Math.max(1, coolDownTargetUsers);

        List<OpenInjectionStep> steps = new ArrayList<>();
        for (int step = 0; step < effectiveMaxSteps; step++) {
            int fromUsers = effectiveStartUsers + step * effectiveStepUsers;
            int toUsers = effectiveStartUsers + (step + 1) * effectiveStepUsers;
            steps.add(rampUsersPerSec(fromUsers).to(toUsers)
                    .during(Duration.ofSeconds(effectiveStepDurationSeconds)));
        }
        int peakUsers = effectiveStartUsers + effectiveMaxSteps * effectiveStepUsers;
        steps.add(constantUsersPerSec(peakUsers)
                .during(Duration.ofSeconds(effectivePeakHoldSeconds)));
        steps.add(rampUsersPerSec(peakUsers).to(effectiveCoolDownTargetUsers)
                .during(Duration.ofSeconds(effectiveCoolDownSeconds)));

        setUp(delayScenario.injectOpen(steps.toArray(new OpenInjectionStep[0])))
                .protocols(httpProtocol)
                .assertions(global().failedRequests().percent().lt(maxFailedPercent));

        System.out.printf(
                "Delay1sKnee 注入参数：start=%d, step=%d, maxSteps=%d, peak=%d, "
                        + "stepDuration=%ds, peakHold=%ds, coolDown=%ds->%d%n",
                effectiveStartUsers, effectiveStepUsers, effectiveMaxSteps, peakUsers,
                effectiveStepDurationSeconds, effectivePeakHoldSeconds,
                effectiveCoolDownSeconds, effectiveCoolDownTargetUsers);
    }

    @Override
    public void after() {
        EnvSnapshot.captureEnd(envStart);
    }
}
