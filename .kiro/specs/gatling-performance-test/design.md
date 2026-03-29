# 设计文档：Gatling 性能测试套件（gateway-perf）

## 概述

为 netty-gateway 项目新增独立 Maven 模块 `gateway-perf`，基于 Gatling 3.10+ 构建完整的性能测试套件。
测试对象为 gateway-app（端口 8080），上游为 gateway-example（端口 8082），Auth 服务为 auth-jwt-example（端口 8091）。

核心目标：
- 量化网关在纯代理、JWT 认证、限流、大文件传输等场景下的 RPS 吞吐量和 P99 延迟
- 建立可回归的性能基线（baseline.json），在 CI 中自动检测 RPS 和 P99 退化
- 提供 profile.sh 脚本，在压测期间同步采集 CPU 火焰图、分配火焰图、JFR 录制和资源利用率时序数据
- 支持本地 loopback 和 Docker bridge 两种部署拓扑，所有吞吐量测试使用开环负载模型（constantUsersPerSec）


## 架构

### 整体拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│  宿主机（Gatling 进程）                                           │
│                                                                  │
│  gateway-perf                                                    │
│  ├── Simulation 类（constantUsersPerSec 开环注入）                │
│  ├── WarmupHelper（预热阶段，不计入指标）                          │
│  ├── BaselineManager（读写 baseline.json，退化检测）              │
│  ├── EnvSnapshot（环境快照采集）                                  │
│  └── RepeatAggregator（重复测试取平均）                           │
│                                                                  │
│  scripts/profile.sh                                              │
│  ├── async-profiler（CPU + 分配火焰图）                           │
│  ├── jcmd JFR（GC / 线程阻塞 / CPU 负载）                        │
│  └── resource-usage.csv（每 5 秒采样 CPU + 堆内存）              │
└──────────────────┬──────────────────────────────────────────────┘
                   │ HTTP/1.1（loopback 或 Docker bridge）
                   ▼
┌──────────────────────────────┐
│  gateway-app :8080           │
│  Netty pipeline              │
│  Spring Boot profile=perf    │
└──────────┬───────────────────┘
           │ HTTP/2（H2 连接池）
           ▼
┌──────────────────────────────┐
│  gateway-example :8082       │
│  Spring Boot MVC             │
│  + DelayController（新增）   │
└──────────────────────────────┘
           ▲
           │ JWT Token 获取
┌──────────────────────────────┐
│  auth-jwt-example :8091      │
└──────────────────────────────┘
```

### 两种部署拓扑

| 拓扑 | 说明 | gatling.baseUrl | 适用场景 |
|------|------|-----------------|----------|
| 场景 A（本地 loopback） | 所有服务同机运行 | `http://localhost:8080` | 开发阶段快速验证 |
| 场景 B（Docker bridge） | gateway-app 和 gateway-example 在独立容器中，Gatling 在宿主机 | `http://localhost:8080`（端口映射） | 标准 benchmark，网络延迟可控 |

### 负载模型

所有吞吐量测试（需求 2、17、18）、Soak Test（需求 12）、Spike Test（需求 11）均使用 Gatling 开环注入：

```java
// 开环：固定注入速率，不受响应时间影响
setUp(scenario.injectOpen(
    constantUsersPerSec(rps).during(Duration.ofSeconds(duration))
));
```

Spike Test 使用 `rampUsersPerSec` 模拟流量突增：

```java
injectOpen(
    constantUsersPerSec(10).during(Duration.ofSeconds(5)),
    rampUsersPerSec(10).to(spikeUsers).during(Duration.ofSeconds(5)),
    constantUsersPerSec(spikeUsers).during(Duration.ofSeconds(spikeDuration)),
    rampUsersPerSec(spikeUsers).to(10).during(Duration.ofSeconds(5))
)
```


## 组件与接口

### 模块目录布局

```
gateway-perf/
├── pom.xml
├── baseline.json                          # 性能基线（纳入版本控制）
├── docker-compose.yml                     # 场景 B 标准 benchmark 拓扑
├── README.md
├── scripts/
│   └── profile.sh                         # async-profiler + JFR + 资源监控
└── src/
    └── test/
        ├── java/
        │   └── com/lei/gateway/perf/
        │       ├── simulation/
        │       │   ├── BaseProxySimulation.java        # 需求 2：基础代理路径
        │       │   ├── JwtAuthSimulation.java          # 需求 5：JWT 认证路径
        │       │   ├── RateLimitSimulation.java        # 需求 3：限流场景
        │       │   ├── FileTransferSimulation.java     # 需求 4：大文件传输
        │       │   ├── DelaySimulation.java            # 需求 9：上游延迟场景
        │       │   ├── ConnectionPoolSimulation.java   # 需求 10：连接池压力
        │       │   ├── SpikeSimulation.java            # 需求 11：流量突增
        │       │   ├── SoakSimulation.java             # 需求 12：长时间稳定性
        │       │   ├── FaultToleranceSimulation.java   # 需求 13：容错测试
        │       │   ├── RouteCountSimulation.java       # 需求 14：多路由对比
        │       │   ├── MockBaselineSimulation.java     # 需求 17：Mock 基线
        │       │   ├── ConnectionModeSimulation.java   # 需求 18：连接模式对比
        │       │   └── KneeDetectionSimulation.java    # 需求 22：膝点探测
        │       └── util/
        │           ├── WarmupHelper.java               # 预热阶段构建器
        │           ├── BaselineManager.java            # 基线读写与退化检测
        │           ├── EnvSnapshot.java                # 环境快照采集
        │           └── RepeatAggregator.java           # 重复测试取平均
        └── resources/
            └── gatling.conf                            # Gatling 全局配置
```

### Simulation 类设计

所有 Simulation 遵循统一生命周期：

```
1. EnvSnapshot.capture()          → 采集环境快照到 env-snapshot.json
2. WarmupHelper.build()           → 构建预热 Scenario（不计入指标）
3. 正式 Scenario（开环注入）
4. Assertions 校验
5. BaselineManager.compare()      → 退化检测（或写入初始基线）
6. RepeatAggregator.aggregate()   → 多次重复取平均（repeatCount > 1 时）
```

#### WarmupHelper

```java
/**
 * 构建预热阶段 ScenarioBuilder，预热期间请求不计入 RPS/P99 统计。
 */
public class WarmupHelper {
    /**
     * 返回预热注入配置，使用 atOnceUsers 注入低并发用户，持续 warmupDuration 秒。
     */
    public static PopulationBuilder build(ScenarioBuilder scenario, int warmupUsers,
            int warmupDuration);
}
```

预热实现方式：Gatling 不支持原生"不计入指标"的预热，通过以下方式近似实现：
- 预热 Scenario 使用独立的 `ScenarioBuilder`，在 `setUp` 中与正式 Scenario 并列注入
- 预热 Scenario 的 Assertion 不设置（不影响构建结果）
- 正式 Scenario 在预热结束后通过 `nothingFor(warmupDuration)` 延迟启动

```java
setUp(
    warmupScenario.injectOpen(constantUsersPerSec(warmupUsers).during(warmupDuration)),
    mainScenario.injectOpen(
        nothingFor(warmupDuration),
        constantUsersPerSec(rps).during(duration)
    )
).assertions(/* 只对 mainScenario 设置 Assertion */);
```

#### BaselineManager

```java
/**
 * 负责读写 baseline.json 和执行退化检测。
 */
public class BaselineManager {
    /**
     * 将本次测试指标写入或更新 baseline.json。
     */
    public void save(PerfMetrics metrics);

    /**
     * 读取 baseline.json，返回 Optional.empty() 表示文件不存在。
     */
    public Optional<PerfMetrics> load();

    /**
     * 执行退化检测，返回 RegressionResult（包含是否退化、退化幅度、报告文本）。
     * WHEN RPS 退化超过 rpsThreshold 或 P99 退化超过 p99Threshold，返回退化结果。
     */
    public RegressionResult compare(PerfMetrics baseline, PerfMetrics current,
            double rpsThreshold, double p99Threshold);

    /**
     * 将退化报告写入 target/gatling/regression-report.txt。
     */
    public void writeRegressionReport(RegressionResult result);
}
```

#### EnvSnapshot

```java
/**
 * 采集宿主机环境快照，写入 target/gatling/env-snapshot.json。
 */
public class EnvSnapshot {
    /**
     * 采集并写入环境快照，返回快照对象供 BaselineManager 存储摘要。
     */
    public static EnvSnapshotData capture();

    /**
     * 检查 load average 是否超过 CPU 核心数的 80%，超过则输出警告。
     */
    public static void checkLoadWarning(EnvSnapshotData snapshot);

    /**
     * 测试结束时再次采集 load average，与 start 对比；
     * 超过阈值时输出警告"宿主机负载过高，测试结果可能受环境干扰，建议重新测试"；
     * 将结束时快照追加写入 env-snapshot.json。
     */
    public static void captureEnd(EnvSnapshotData start);
}
```

#### RepeatAggregator

```java
/**
 * 对多次重复测试结果取算术平均值，偏差超过 10% 时输出警告。
 */
public class RepeatAggregator {
    /**
     * 聚合多次 PerfMetrics，返回平均值；偏差超过 deviationThreshold 时输出警告。
     */
    public static PerfMetrics aggregate(List<PerfMetrics> results, double deviationThreshold);
}
```

### gateway-example 新增接口

新增两个 Controller，包路径 `com.lei.gateway.example`（与现有 `ExampleController` 相同包），与现有 `ExampleController` 平级。

#### DelayController

```java
/**
 * 延迟模拟控制器，为 Gatling 性能测试提供可参数化的延迟接口。
 */
@RestController
@RequestMapping("/api/example/delay")
public class DelayController {

    /** GET /api/example/delay/fixed?ms=50 — 固定延迟，默认 50ms。 */
    @GetMapping("/fixed")
    public ResponseEntity<Map<String, Object>> fixed(
            @RequestParam(defaultValue = "50") int ms);

    /** GET /api/example/delay/random?min=0&max=100 — 均匀随机延迟。 */
    @GetMapping("/random")
    public ResponseEntity<Map<String, Object>> random(
            @RequestParam(defaultValue = "0") int min,
            @RequestParam(defaultValue = "100") int max);

    /** GET /api/example/delay/slow — 固定 500ms 慢接口。 */
    @GetMapping("/slow")
    public ResponseEntity<Map<String, Object>> slow();
}
```

#### MockController

```java
/**
 * 零延迟 Mock 控制器，为 Gatling 性能测试提供网关自身吞吐量基线接口。
 */
@RestController
@RequestMapping("/api/example")
public class MockController {

    /** GET /api/example/mock — 零延迟，直接返回 {"status":"ok"}。 */
    @GetMapping("/mock")
    public ResponseEntity<Map<String, Object>> mock();
}
```

注意：
- `mock` 接口路径为 `/api/example/mock`（需求 17 明确要求），与 delay 接口路径前缀不同，独立为 `MockController`，避免路径混淆。
- `MockController` 的 `@RequestMapping("/api/example")` 与 `ExampleController` 相同，Spring MVC 允许多个 Controller 共享同一前缀，只要方法路径不冲突即可。现有 `ExampleController` 没有 `/mock` 路径，无冲突。
- `DelayController` 的 `/api/example/delay/**` 路径与现有路由 `example-service`（`/api/example/**`）存在前缀包含关系，网关路由匹配时 `RouteResolver` 按 pathPrefix 长度降序优先，因此 `/api/example/delay/**` 会优先匹配 `example-service` 路由（`/api/example/**`），无需额外路由配置。

参数校验规则：
- `ms < 0`：返回 HTTP 400，响应体 `{"error": "ms must be non-negative"}`
- `min > max`：返回 HTTP 400，响应体 `{"error": "min must not exceed max"}`
- `min < 0` 或 `max < 0`：返回 HTTP 400，响应体 `{"error": "min and max must be non-negative"}`
- 使用 `Thread.sleep(ms)` 实现延迟（Spring MVC 同步线程模型，不影响 Netty 网关测试）


## 数据模型

### PerfMetrics（性能指标）

```java
/**
 * 单次测试的核心性能指标，用于基线存储和退化检测。
 */
public record PerfMetrics(
    double rps,              // 每秒请求数
    long p95Ms,              // P95 响应时间（ms）
    long p99Ms,              // P99 响应时间（ms）
    double errorRate,        // 错误率（0.0 ~ 1.0）
    double mockRps,          // Mock 基线场景 RPS（需求 17）
    long kneeRps,            // 膝点 RPS（需求 22）
    EnvSnapshotSummary env,  // 环境快照摘要（CPU 核心数、JVM 版本）
    Instant timestamp        // 测试时间戳
) {}
```

### baseline.json 格式

```json
{
  "rps": 8500.0,
  "p95Ms": 12,
  "p99Ms": 18,
  "errorRate": 0.002,
  "mockRps": 12000.0,
  "kneeRps": 9000,
  "env": {
    "cpuCores": 2,
    "jvmVersion": "21.0.3",
    "availableMemoryMb": 3800
  },
  "timestamp": "2025-01-15T10:30:00Z"
}
```

### RegressionResult（退化检测结果）

```java
public record RegressionResult(
    boolean regressed,           // 是否发生退化
    String rpsReport,            // RPS 退化报告文本（null 表示未退化）
    String p99Report,            // P99 退化报告文本（null 表示未退化）
    double rpsBaselineValue,     // 基线 RPS
    double rpsCurrentValue,      // 本次 RPS
    double rpsDeviationPct,      // RPS 退化幅度（%）
    long p99BaselineMs,          // 基线 P99（ms）
    long p99CurrentMs,           // 本次 P99（ms）
    double p99DeviationPct       // P99 退化幅度（%）
) {}
```

### EnvSnapshotData（环境快照）

```java
public record EnvSnapshotData(
    int cpuCores,
    long availableMemoryMb,
    double loadAvg1min,
    double loadAvg5min,
    double loadAvg15min,
    String osName,
    String osVersion,
    String jvmVersion,
    String jvmArgs,
    String gatlingVersion,
    Instant capturedAt
) {}
```

### resource-usage.csv 格式

```
timestamp,cpu_percent,heap_used_mb,heap_max_mb
2025-01-15T10:30:05Z,45.2,512,3072
2025-01-15T10:30:10Z,87.6,634,3072
...
```

### application-perf.yml 配置设计

`gateway-app/src/main/resources/application-perf.yml`，通过 `--spring.profiles.active=perf` 激活：

```yaml
gateway:
  # 需求 10：覆盖连接池配置，提供足够连接数用于压力测试
  # 生产配置 max-connections-per-host=1 过低，无法触发 borrow/requite 竞争
  connection-pool:
    max-connections-per-host: 20

  routes:
    # 需求 3：限流测试专用路由（独立路径，不覆盖业务路由）
    - id: perf-rate-limit-test
      path-prefix: /api/perf/rate-limit
      upstream: http://localhost:8082
      security:
        enabled: true
        rate-limit:
          ip:
            enabled: true
            permits-per-second: 100   # 由 gatling.rateLimitRps 参数控制，此处为默认值

    # 需求 14：1 条路由配置（基准，路由匹配性能对比用）
    - id: perf-route-1
      path-prefix: /api/perf/route1
      upstream: http://localhost:8082

    # 需求 14：10 条路由配置（perf-route-2 ~ perf-route-10）
    # 需求 14：50 条路由配置（perf-route-11 ~ perf-route-50）
    # 实际文件中展开全部路由条目

    # 需求 13：不可达上游路由（用于 502 测试）
    - id: perf-unreachable
      path-prefix: /api/perf/unreachable
      upstream: http://localhost:19999
```

设计决策：
- `application-perf.yml` 只声明测试专用路由，不覆盖 `application.yml` 中的业务路由。Spring Boot profile 合并语义确保 `perf` profile 激活时，业务路由和测试路由同时生效。
- 限流配置通过 `security.rate-limit.ip.permits-per-second` 设置，与 `RouteSecurityProperties.RouteRateLimitProperties.RouteIpRateLimitProperties` 字段对应。
- 连接池 `max-connections-per-host` 在 perf profile 中覆盖为 20，避免生产配置的 1 个连接导致连接池压力测试无意义。
- 测试路由路径前缀统一使用 `/api/perf/` 命名空间，与业务路由 `/api/example/` 完全隔离，不存在 Ant 路径匹配冲突。
- Docker bridge 场景（需求 7）下，`upstream` 地址需改为容器 hostname（如 `http://gateway-example:8082`），通过单独的 `application-perf-docker.yml` 或启动参数 `--gateway.routes[0].upstream=...` 覆盖。

### docker-compose.yml 设计

```yaml
services:
  gateway-example:
    image: eclipse-temurin:21-jre
    working_dir: /app
    volumes:
      - ../gateway-example/target:/app
    command: >
      java -jar gateway-example-1.0.0-SNAPSHOT.jar
    ports:
      - "8082:8082"
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/api/example/hello"]
      interval: 5s
      timeout: 3s
      retries: 10

  gateway-app:
    image: eclipse-temurin:21-jre
    working_dir: /app
    volumes:
      - ../gateway-app/target:/app
      - ../gateway-app/src/main/resources:/app/config
    command: >
      java
      -Xmx3g -XX:+UseG1GC -XX:+FlightRecorder
      -jar gateway-app-1.0.0-SNAPSHOT.jar
      --spring.profiles.active=perf
      --spring.config.additional-location=file:/app/config/
      --gateway.routes[0].upstream=http://gateway-example:8082
      --gateway.routes[1].upstream=http://gateway-example:8082
    ports:
      - "8080:8080"
    depends_on:
      gateway-example:
        condition: service_healthy
```

注意：Docker bridge 场景下，`application-perf.yml` 中的 `upstream: http://localhost:8082` 需要覆盖为容器 hostname `http://gateway-example:8082`。通过启动参数 `--gateway.routes[N].upstream=...` 逐条覆盖，或提供独立的 `application-perf-docker.yml`。`depends_on.condition: service_healthy` 确保 gateway-example 就绪后 gateway-app 再启动，避免路由初始化失败。

### Maven pom.xml 设计

```xml
<project>
  <parent>
    <groupId>com.lei</groupId>
    <artifactId>netty-gateway</artifactId>
    <version>1.0.0-SNAPSHOT</version>
  </parent>

  <artifactId>gateway-perf</artifactId>
  <name>gateway-perf</name>

  <properties>
    <gatling.version>3.10.5</gatling.version>
    <gatling-maven-plugin.version>4.9.6</gatling-maven-plugin.version>

    <!-- 可通过 -D 覆盖的默认参数 -->
    <gatling.baseUrl>http://localhost:8080</gatling.baseUrl>
    <gatling.users>50</gatling.users>
    <gatling.duration>60</gatling.duration>
    <gatling.targetRps>5000</gatling.targetRps>
    <gatling.rateLimitRps>100</gatling.rateLimitRps>
    <gatling.fileUsers>10</gatling.fileUsers>
    <gatling.slowUsers>10</gatling.slowUsers>
    <gatling.spikeUsers>200</gatling.spikeUsers>
    <gatling.spikeDuration>30</gatling.spikeDuration>
    <gatling.soakDuration>1800</gatling.soakDuration>
    <gatling.soakUsers>50</gatling.soakUsers>
    <gatling.warmupDuration>30</gatling.warmupDuration>
    <gatling.warmupUsers>10</gatling.warmupUsers>
    <gatling.skipWarmup>false</gatling.skipWarmup>
    <gatling.repeatCount>3</gatling.repeatCount>
    <gatling.updateBaseline>false</gatling.updateBaseline>
    <gatling.rpsRegressionThreshold>0.10</gatling.rpsRegressionThreshold>
    <gatling.p99RegressionThreshold>0.20</gatling.p99RegressionThreshold>
    <gatling.maxProxyOverheadMs>20</gatling.maxProxyOverheadMs>
    <gatling.maxConnectionLeak>0</gatling.maxConnectionLeak>
    <gatling.upstreamTimeoutMs>2000</gatling.upstreamTimeoutMs>
    <gatling.routeCountVariants>1,10,50</gatling.routeCountVariants>
  </properties>

  <dependencies>
    <dependency>
      <groupId>io.gatling.highcharts</groupId>
      <artifactId>gatling-charts-highcharts</artifactId>
      <version>${gatling.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>io.gatling</groupId>
        <artifactId>gatling-maven-plugin</artifactId>
        <version>${gatling-maven-plugin.version}</version>
        <configuration>
          <!-- 报告输出到独立目录，不与 surefire 报告混淆 -->
          <resultsFolder>${project.build.directory}/gatling</resultsFolder>
          <!-- 通过 -DsimulationClass 指定单个 Simulation（CI 用） -->
          <simulationClass>${gatling.simulationClass}</simulationClass>
          <!-- 将 Maven 属性传递给 Simulation -->
          <jvmArgs>
            <jvmArg>-DbaseUrl=${gatling.baseUrl}</jvmArg>
            <jvmArg>-Dusers=${gatling.users}</jvmArg>
            <!-- 其余参数同理 -->
          </jvmArgs>
        </configuration>
      </plugin>
      <!-- 禁用 Surefire，gateway-perf 不运行 JUnit 测试 -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <configuration>
          <skipTests>true</skipTests>
        </configuration>
      </plugin>
      <!-- 跳过 Checkstyle：Gatling Simulation 类不符合项目 Java 代码规范 -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-checkstyle-plugin</artifactId>
        <configuration>
          <skip>true</skip>
        </configuration>
      </plugin>
      <!-- 跳过 forbiddenapis：Gatling 内部使用部分受限 API -->
      <plugin>
        <groupId>de.thetaphi</groupId>
        <artifactId>forbiddenapis</artifactId>
        <configuration>
          <skip>true</skip>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

父 `pom.xml` 在 `<modules>` 中声明 `gateway-perf`，但通过以下方式隔离默认构建：
- `gatling:test` 不绑定到 `verify` 生命周期，需显式执行 `mvn gatling:test -pl gateway-perf`
- Surefire 在 gateway-perf 中被禁用，`mvn clean verify` 不触发 Gatling 测试


### profile.sh 脚本设计

```bash
#!/usr/bin/env bash
# 用法：./scripts/profile.sh <gateway-pid> [duration-seconds]
# 依赖：async-profiler（ASYNC_PROFILER_HOME 环境变量），jcmd（JDK 内置）

GATEWAY_PID=$1
DURATION=${2:-120}
OUTPUT_DIR="target/profiling"

# 1. 启动 async-profiler CPU 采集
$ASYNC_PROFILER_HOME/bin/asprof -e cpu -d $DURATION \
    -f $OUTPUT_DIR/cpu-flamegraph.svg $GATEWAY_PID &

# 2. 启动 async-profiler 分配采集
$ASYNC_PROFILER_HOME/bin/asprof -e alloc -d $DURATION \
    -f $OUTPUT_DIR/alloc-flamegraph.svg $GATEWAY_PID &

# 3. 启动 JFR 录制
jcmd $GATEWAY_PID JFR.start \
    settings=profile \
    name=gateway-perf \
    filename=$OUTPUT_DIR/gateway.jfr \
    duration=${DURATION}s \
    +jdk.ObjectAllocationSample#enabled=true \
    +jdk.GarbageCollection#enabled=true \
    +jdk.GCPhasePause#enabled=true \
    +jdk.ThreadPark#enabled=true \
    +jdk.MonitorWait#enabled=true \
    +jdk.CPULoad#enabled=true \
    +jdk.ObjectAllocationInNewTLAB#enabled=true \
    +jdk.ObjectAllocationOutsideTLAB#enabled=true \
    +jdk.ClassLoad#enabled=true

# 4. 每 5 秒采样 CPU + 堆内存，写入 resource-usage.csv
echo "timestamp,cpu_percent,heap_used_mb,heap_max_mb" > $OUTPUT_DIR/resource-usage.csv
for i in $(seq 1 $((DURATION / 5))); do
    TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    CPU=$(ps -p $GATEWAY_PID -o %cpu= | tr -d ' ')
    HEAP=$(jcmd $GATEWAY_PID GC.heap_info | grep -oP 'used \K[0-9]+' | head -1)
    HEAP_MB=$((HEAP / 1024 / 1024))
    MAX_HEAP=$(jcmd $GATEWAY_PID GC.heap_info | grep -oP 'capacity \K[0-9]+' | head -1)
    MAX_HEAP_MB=$((MAX_HEAP / 1024 / 1024))
    echo "$TIMESTAMP,$CPU,$HEAP_MB,$MAX_HEAP_MB" >> $OUTPUT_DIR/resource-usage.csv
    sleep 5
done

# 5. 输出摘要
PEAK_CPU=$(awk -F',' 'NR>1 {print $2}' $OUTPUT_DIR/resource-usage.csv | sort -n | tail -1)
AVG_CPU=$(awk -F',' 'NR>1 {sum+=$2; count++} END {printf "%.1f", sum/count}' \
    $OUTPUT_DIR/resource-usage.csv)
PEAK_HEAP=$(awk -F',' 'NR>1 {print $3}' $OUTPUT_DIR/resource-usage.csv | sort -n | tail -1)
echo "=== 资源利用率摘要 ==="
echo "峰值 CPU: ${PEAK_CPU}%  平均 CPU: ${AVG_CPU}%  峰值堆内存: ${PEAK_HEAP}MB"

# 6. CPU 利用率过低警告
if (( $(echo "$AVG_CPU < 50" | bc -l) )); then
    echo "警告：网关 CPU 利用率过低（${AVG_CPU}%），测试可能未达到性能瓶颈，建议增加并发用户数"
fi
```

错误处理：
- PID 不存在：`kill -0 $GATEWAY_PID 2>/dev/null || { echo "错误：PID $GATEWAY_PID 不存在"; exit 1; }`
- async-profiler 未安装：检查 `$ASYNC_PROFILER_HOME/bin/asprof` 是否可执行，不可执行则输出错误并退出


## 正确性属性

*属性（Property）是在系统所有合法执行路径上都应成立的特征或行为——本质上是对系统应做什么的形式化陈述。属性是人类可读规格说明与机器可验证正确性保证之间的桥梁。*

---

### Property 1：错误率不变量

*对于任意*一批向网关发送的请求（基础代理、JWT 认证、文件传输、连接模式对比等场景），
在正常上游可用的条件下，HTTP 5xx 错误响应的比例应低于 1%。

**Validates: Requirements 2.3, 4.3, 5.5, 18.4**

---

### Property 2：基线序列化 round-trip

*对于任意*一组 `PerfMetrics`（包含 RPS、P95、P99、错误率、mockRps、kneeRps、环境摘要），
将其序列化为 JSON 写入 `baseline.json`，再反序列化读取，应得到与原始对象字段值完全相同的结果。

**Validates: Requirements 8.1, 17.3, 21.4, 22.3, 23.4**

---

### Property 3：退化检测不变量（RPS + P99）

*对于任意*两组指标（基线指标和当前指标），`BaselineManager.compare()` 的行为应满足：
- 当 `(baseline.rps - current.rps) / baseline.rps > rpsThreshold` 时，返回 `regressed=true` 且 `rpsDeviationPct` 等于实际退化幅度
- 当 `(current.p99Ms - baseline.p99Ms) / baseline.p99Ms > p99Threshold` 时，返回 `regressed=true` 且 `p99DeviationPct` 等于实际退化幅度
- 当两者均未超过阈值时，返回 `regressed=false`

**Validates: Requirements 8.3, 15.1**

---

### Property 4：退化报告格式不变量

*对于任意*发生退化的 `RegressionResult`，`BaselineManager.writeRegressionReport()` 写入的报告文本应同时包含：基线值、本次值、退化幅度百分比三个字段，且写入 `target/gatling/regression-report.txt` 后可被读取。

**Validates: Requirements 8.6, 15.2, 15.3**

---

### Property 5：资源监控 CSV 格式不变量

*对于任意*一次 `profile.sh` 采样执行，写入 `resource-usage.csv` 的每一行（除表头外）应包含四列：`timestamp`（ISO-8601 格式）、`cpu_percent`（非负浮点数）、`heap_used_mb`（非负整数）、`heap_max_mb`（正整数，且 `heap_used_mb ≤ heap_max_mb`）。

**Validates: Requirements 12.2, 19.1**

---

### Property 6：延迟接口响应不变量

*对于任意*合法的 `ms` 参数（`ms ≥ 0`），`GET /api/example/delay/fixed?ms={ms}` 的响应体中 `delay` 字段应等于 `ms`，`type` 字段应为 `"fixed"`，HTTP 状态码应为 200。

*对于任意*合法的 `min` 和 `max` 参数（`0 ≤ min ≤ max`），`GET /api/example/delay/random?min={min}&max={max}` 的响应体中 `delay` 字段应满足 `min ≤ delay ≤ max`，`type` 字段应为 `"random"`，HTTP 状态码应为 200。

**Validates: Requirements 9.1, 9.2**

---

### Property 7：上游故障错误码映射不变量

*对于任意*配置了不可达上游地址的路由，当上游连接被拒绝时，网关应返回 HTTP 502。

*对于任意*上游响应时间超过网关超时阈值的请求，网关应返回 HTTP 504，而非请求 hang 住超过 `upstreamTimeoutMs`。

**Validates: Requirements 13.1, 13.2**

---

### Property 8：重复测试统计不变量

*对于任意*一组 `n` 次重复测试的 `PerfMetrics` 列表（`n ≥ 2`），`RepeatAggregator.aggregate()` 返回的平均 RPS 应等于各次 RPS 的算术平均值（误差 < 0.01%）。

当各次 RPS 中最大值与最小值之差超过平均值的 10% 时，`aggregate()` 应输出偏差警告。

**Validates: Requirements 21.2, 21.3**

---

### Property 9：环境快照格式不变量

*对于任意*测试环境，`EnvSnapshot.capture()` 返回的 `EnvSnapshotData` 应包含所有必填字段（cpuCores > 0、availableMemoryMb > 0、loadAvg1min ≥ 0、osName 非空、jvmVersion 非空、gatlingVersion 非空），且序列化为 JSON 后可被反序列化为等价对象。

**Validates: Requirements 23.1**

---

### Property 10：膝点探测停止条件不变量

*对于任意*负载递增序列，`KneeDetectionSimulation` 的膝点探测逻辑应满足：当某阶段 P99 延迟超过前一阶段 P99 的 150%，或错误率超过 1% 时，停止递增并记录当前 RPS 为膝点 RPS；在停止条件触发之前，应持续递增并发用户数。

**Validates: Requirements 22.1**

---

### Property 11：预热阶段隔离不变量

*对于任意* Simulation，当 `skipWarmup=false` 时，预热阶段（`warmupDuration` 秒内）的请求不应计入正式 Scenario 的 RPS、P95、P99 统计；正式 Scenario 应在预热结束后才开始注入流量（通过 `nothingFor` 延迟实现）。

**Validates: Requirements 20.1, 20.2**

---

### Property 12：Mock 接口响应不变量

*对于任意*发往 `GET /api/example/mock` 的请求，响应状态码应为 200，响应体应为 `{"status":"ok"}`，且响应时间应趋近于 0（无业务逻辑延迟）。

**Validates: Requirements 17.1**

---

### Property 13：Mock 场景 RPS 优于基础代理（Metamorphic）

*对于任意*相同硬件环境和相同并发配置，`MockBaselineSimulation` 的 RPS 应高于 `BaseProxySimulation` 的 RPS，因为 Mock 接口无业务逻辑，网关代理开销是唯一瓶颈。若 Mock RPS ≤ 基础代理 RPS，则说明测试环境存在异常。

**Validates: Requirements 17.4**

---

### Property 14：慢上游隔离不变量

*对于任意*混合流量场景（50% 正常请求 + 50% 慢上游请求），正常请求的 P99 延迟应不超过纯正常流量基线 P99 的 120%，即慢上游不应通过共享资源（连接池、线程池）影响正常请求的延迟。

**Validates: Requirements 13.3**

---

### Property 15：路由规则数量性能退化不变量

*对于任意*相同负载配置，50 条路由规则下的 RPS 应不低于 1 条路由规则下 RPS 的 90%（即退化不超过 10%），验证 `RoutingHandler` 路径匹配的线性扫描开销在 50 条规则内可接受。

**Validates: Requirements 14.3**

---

### Property 16：Soak 测试内存增长不变量

*对于任意* Soak 测试（持续 `soakDuration` 秒），测试结束时的堆内存占用应不超过测试开始时堆内存占用的 150%，排除 JVM 预热阶段的初始增长。

**Validates: Requirements 12.4**

---

### Property 17：固定延迟 Proxy Overhead 不变量

*对于任意*向 `GET /api/example/delay/fixed?ms=50` 发送的请求，网关端到端 P99 延迟应不超过 `50ms + maxProxyOverheadMs`（默认 70ms），即网关自身引入的代理开销 P99 应低于 `maxProxyOverheadMs`。

**Validates: Requirements 9.7**

---

### Property 18：Spike 后延迟恢复不变量

*对于任意* Spike 测试，在 spike 峰值结束后 60 秒内，P99 延迟应恢复至基线 P99 的 120% 以内，验证网关在流量突增后能释放排队积压并恢复稳态性能。

**Validates: Requirements 11.3**

---

### Property 19：故障注入响应率准确性不变量

*对于任意*故障注入场景（上游宕机或超时），实际收到的 502/504 响应率与注入故障比例的误差应低于 5%，验证网关错误传播的准确性。

**Validates: Requirements 13.4**

---

### Property 20：连接池无泄漏不变量

*对于任意*连接池压力测试，压测结束后活跃连接数与压测开始前活跃连接数的差值应不超过 `maxConnectionLeak`（默认 0），验证 borrow/requite 在高并发竞争下无连接泄漏。

**Validates: Requirements 10.4**


## 错误处理

### Simulation 级别

| 错误场景 | 处理方式 |
|----------|----------|
| Auth_Service 不可用（JWT Token 获取失败） | Simulation 启动阶段抛出异常，终止并输出"JWT Token 获取失败，无法执行认证场景" |
| baseline.json 不存在 | 写入初始基线，输出"初始基线已创建"，不执行退化检测 |
| 退化检测触发 | 输出退化报告到 stdout 和 regression-report.txt，以非零退出码结束 |
| 开环模式积压导致 Gatling OOM | 捕获 OutOfMemoryError，降级为闭环模式，在报告中标注"已降级为闭环模式" |
| skipWarmup=true | 跳过预热，输出警告"已跳过预热，测试数据可能包含 JIT 冷启动噪声" |

### DelayController 级别

| 错误场景 | HTTP 状态码 | 响应体 |
|----------|-------------|--------|
| `ms < 0` | 400 | `{"error": "ms must be non-negative"}` |
| `ms` 非整数（类型转换失败） | 400 | `{"error": "ms must be a valid integer"}` |
| `min > max` | 400 | `{"error": "min must not exceed max"}` |
| `min` 或 `max` 为负数 | 400 | `{"error": "min and max must be non-negative"}` |

### profile.sh 级别

| 错误场景 | 处理方式 |
|----------|----------|
| PID 不存在 | 输出"错误：PID $GATEWAY_PID 不存在"，以非零退出码退出 |
| async-profiler 未安装（ASYNC_PROFILER_HOME 未设置或 asprof 不可执行） | 输出"错误：async-profiler 未安装，请设置 ASYNC_PROFILER_HOME"，以非零退出码退出，不影响压测流程 |
| jcmd 不可用 | 输出"警告：jcmd 不可用，跳过 JFR 录制和堆内存采样" |

## 测试策略

### 双测试方法

本模块采用单元测试和属性测试互补的双测试方法：

- **单元测试**：验证具体示例、边界条件和错误处理
- **属性测试**：验证跨所有输入的通用属性（使用 jqwik，与项目现有测试框架一致）

### 单元测试（JUnit 5 + jqwik）

测试目标为工具类（`BaselineManager`、`RepeatAggregator`、`EnvSnapshot`）和 `DelayController`，
不测试 Gatling Simulation 类本身（Simulation 通过实际运行验证）。

| 测试类 | 测试内容 |
|--------|----------|
| `BaselineManagerTest` | 初始基线创建、基线更新、退化检测边界值（恰好等于阈值、略超阈值） |
| `RepeatAggregatorTest` | 平均值计算正确性、偏差警告触发条件 |
| `EnvSnapshotTest` | 快照字段非空校验、JSON 序列化/反序列化 |
| `DelayControllerTest` | 固定延迟响应体校验、随机延迟范围校验、参数校验（负数、min>max） |
| `RegressionResultTest` | 退化报告文本包含必填字段 |

### 属性测试（jqwik，最少 100 次迭代）

每个属性测试对应设计文档中的一个 Correctness Property，标注格式：

```java
// Feature: gatling-performance-test, Property 2: 基线序列化 round-trip
@Property(tries = 100)
void baselineRoundTrip(@ForAll PerfMetrics metrics) {
    baselineManager.save(metrics);
    PerfMetrics loaded = baselineManager.load().orElseThrow();
    assertThat(loaded).isEqualTo(metrics);
}
```

| 属性测试类 | 对应 Property |
|------------|---------------|
| `BaselineManagerPropertyTest` | Property 2（序列化 round-trip）、Property 3（退化检测不变量）、Property 4（报告格式不变量） |
| `RepeatAggregatorPropertyTest` | Property 8（重复测试统计不变量） |
| `EnvSnapshotPropertyTest` | Property 9（环境快照格式不变量） |
| `DelayControllerPropertyTest` | Property 6（延迟接口响应不变量） |
| `KneeDetectionPropertyTest` | Property 10（膝点探测停止条件不变量） |

### Gatling Simulation 测试（集成测试）

Simulation 类通过实际运行验证，不编写 JUnit 测试。
CI 中通过 `mvn gatling:test -pl gateway-perf -DsimulationClass=...` 指定单个 Simulation 运行。

Assertion 配置示例（Property 1 对应的 Gatling Assertion）：

```java
// Feature: gatling-performance-test, Property 1: 错误率不变量
setUp(scenario.injectOpen(...))
    .assertions(
        global().failedRequests().percent().lt(1.0),
        global().requestsPerSec().gte(targetRps)
    );
```

### 测试数据生成策略

jqwik 生成器设计：

```java
@Provide
Arbitrary<PerfMetrics> perfMetrics() {
    return Combinators.combine(
        Arbitraries.doubles().between(100, 50000),   // rps
        Arbitraries.longs().between(1, 10000),        // p95Ms
        Arbitraries.longs().between(1, 10000),        // p99Ms
        Arbitraries.doubles().between(0, 0.1)         // errorRate
    ).as(PerfMetrics::new);
}
```

`DelayController` 的参数生成器需覆盖边界值：
- `ms`：0、1、Integer.MAX_VALUE、-1（期望 400）
- `min/max`：`min == max`（合法）、`min > max`（期望 400）、`min == 0 && max == 0`（合法）

