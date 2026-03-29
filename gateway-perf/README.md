# gateway-perf — Gatling 性能测试套件

基于 Gatling 3.10.5 Java API 构建的 netty-gateway 性能测试套件，覆盖基础代理、JWT 认证、限流、大文件传输、连接池压力、流量突增、长时间稳定性等场景。

---

## 三种使用场景

### 场景一：快速验证（5 分钟）

**适用场景**：开发调试，想快速确认网关能正常响应，不需要精确数据。

**前置条件**：无。

**步骤**：

```bash
# 先编译安装所有模块（确保跑的是最新代码）
mvn install -DskipTests

# 终端1：启动上游服务（8082）
mvn spring-boot:run -pl gateway-example

# 终端2：启动网关（8080，perf profile）
mvn spring-boot:run -pl gateway-app -Dspring-boot.run.arguments="--spring.profiles.active=perf"

# 终端3：运行所有测试
mvn gatling:test -pl gateway-perf
```

**结果**：所有 Simulation 跑一遍，HTML 报告在 `gateway-perf/target/gatling/`。

**注意**：这个场景下延迟数据不准确（P99 包含大量排队时间），仅用于验证功能是否正常。

---

### 场景二：正式 Benchmark（30~60 分钟）

**适用场景**：需要发布准确的性能数据（发报告、发版对比），追求数据可信度。

**前置条件**：无。

**核心概念**：**膝点**。在最大并发下测延迟，P99 会包含大量排队时间，数据失真。正确做法是先探测"膝点"（再增加负载延迟就会暴涨的临界点），然后在膝点的 70% 负载下测延迟，这样 P99 才代表网关真实处理能力。

**步骤**：

```bash
# 先编译安装所有模块（确保跑的是最新代码）
mvn install -DskipTests

# 终端1：启动上游服务
mvn spring-boot:run -pl gateway-example

# 终端2：启动网关
mvn spring-boot:run -pl gateway-app -Dspring-boot.run.arguments="--spring.profiles.active=perf"

# 终端3：按顺序跑核心测试
# 第1步：探测膝点 RPS（大约 5~10 分钟）
mvn gatling:test -pl gateway-perf -Dgatling.simulationClass=com.lei.gateway.perf.simulation.KneeDetectionSimulation

# 第2步：测基础代理性能（自动读取 kneeRps，以 70% 负载执行）
mvn gatling:test -pl gateway-perf -Dgatling.simulationClass=com.lei.gateway.perf.simulation.BaseProxySimulation

# 第3步：测网关理论最大 RPS（零延迟上游）
mvn gatling:test -pl gateway-perf -Dgatling.simulationClass=com.lei.gateway.perf.simulation.MockBaselineSimulation

# 第4步：按需跑其他测试
mvn gatling:test -pl gateway-perf -Dgatling.simulationClass=com.lei.gateway.perf.simulation.JwtAuthSimulation
mvn gatling:test -pl gateway-perf -Dgatling.simulationClass=com.lei.gateway.perf.simulation.RateLimitSimulation
# ... 其他 Simulation 同理
```

**结果**：
- `baseline.json` 会记录 `kneeRps` 和 `mockRps`
- `BaseProxySimulation` 的 P99 延迟是可信的（代表网关真实处理能力）
- `MockBaselineSimulation` 的 RPS 是网关理论最大吞吐量

**耗时**：
- KneeDetectionSimulation：5~10 分钟
- BaseProxySimulation：3~5 分钟（默认 60 秒 × 3 次重复）
- MockBaselineSimulation：3~5 分钟
- 其他单个 Simulation：1~5 分钟

---

### 场景三：CI 自动化

**适用场景**：每次代码提交自动跑性能测试，防止性能退化。

**前置条件**：已有 `baseline.json`（通过场景二生成）。

**方式**：通过 GitHub Actions 手动触发。

1. 进入 GitHub 仓库 → Actions → Performance Test → Run workflow
2. 选择要跑的 Simulation（默认 BaseProxySimulation）
3. 可选：勾选 "Update baseline" 强制更新基线

**基线退化检测**：
- RPS 下降超过 10% → 构建失败
- P99 延迟上升超过 20% → 构建失败
- 退化报告输出到 `target/gatling/regression-report.txt`

**报告归档**：每次运行后 HTML 报告会上传为 artifact，可在 Actions 日志中下载。

---

## 结果查看与分析（重点）

每次 `mvn gatling:test -pl gateway-perf` 运行后，**都会生成 HTML 报告**。

### 1. 先看 HTML 总览

报告目录在 `gateway-perf/target/gatling/`，每次运行会生成一个独立文件夹，例如：

`gateway-perf/target/gatling/baseproxysimulation-20260329143428569/index.html`

打开 `index.html` 后，先看 3 个指标：
- `Global > failed`：失败率是否超阈值
- `Global > mean requests/sec`：吞吐是否达标
- `Response Time`：P95/P99 是否异常抬升

### 2. 再看断言失败原因

断言结果文件：

`gateway-perf/target/gatling/<run-id>/js/assertions.xml`

这里会明确写出失败项，例如：
- `percentage of failed events`（失败率）
- `mean requests per second`（吞吐）

### 3. 最后看逐请求明细（定位根因）

明细日志文件：

`gateway-perf/target/gatling/<run-id>/simulation.log`

建议重点 grep 这些关键词：
- `status.find.is(200), but actually found xxx`（看实际 HTTP 状态码）
- `KO`（失败请求）
- `Exception`（网络/协议异常）

示例命令：

```bash
# 找到最新一次报告目录
LATEST=$(ls -td gateway-perf/target/gatling/* | head -n 1)
echo "$LATEST"

# 统计失败原因
rg "KO|found 4|found 5|Exception" "$LATEST/simulation.log"
```

### 4. 常见结论速查

- 大量 `404`：通常是路由未命中或 rewrite 配置不生效
- 大量 `503`：通常是网关/上游过载（连接池、并发流、限流）
- 出现 `H2 stream reset, errorCode=11`：上游在 H2 层主动降载（ENHANCE_YOUR_CALM）
- 少量 `400` 且集中在 `POST`：优先检查请求体/Content-Type/上游解析逻辑

---

## 参数说明

所有参数通过 `-Dgatling.<参数名>=<值>` 传入。

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `baseUrl` | `http://localhost:8080` | 网关地址 |
| `users` | `50` | 并发用户数（开环注入速率） |
| `duration` | `60` | 测试持续时间（秒） |
| `targetRps` | `5000` | 目标 RPS，低于此值则 Assertion 失败 |
| `rateLimitRps` | `100` | 限流测试阈值（RPS） |
| `fileUsers` | `10` | 文件传输场景并发数 |
| `slowUsers` | `10` | 慢上游场景并发数 |
| `spikeUsers` | `200` | Spike 峰值并发数 |
| `spikeDuration` | `30` | Spike 峰值持续时间（秒） |
| `soakDuration` | `1800` | Soak 测试持续时间（秒，默认 30 分钟） |
| `soakUsers` | `50` | Soak 测试并发数 |
| `warmupDuration` | `30` | 预热阶段持续时间（秒） |
| `warmupUsers` | `10` | 预热阶段并发数 |
| `skipWarmup` | `false` | 跳过预热（会输出警告，测试数据含 JIT 冷启动噪声） |
| `repeatCount` | `3` | 每个场景重复执行次数，取平均值 |
| `updateBaseline` | `false` | 强制覆盖 baseline.json（不执行退化检测） |
| `rpsRegressionThreshold` | `0.10` | RPS 退化阈值（10%），超过则构建失败 |
| `p99RegressionThreshold` | `0.20` | P99 退化阈值（20%），超过则构建失败 |
| `maxProxyOverheadMs` | `20` | 固定延迟场景允许的最大代理开销（ms） |
| `maxConnectionLeak` | `0` | 允许的最大连接泄漏数 |
| `upstreamTimeoutMs` | `2000` | 上游超时阈值（ms） |
| `routeCountVariants` | `1,10,50` | 路由规则数量对比变体 |
| `simulationClass` | （空，运行全部） | 指定单个 Simulation 类名（CI 用） |

---

## Simulation 列表

| Simulation | 说明 | 耗时 | 场景 |
|------------|------|------|------|
| `BaseProxySimulation` | 基础代理路径（GET hello + POST echo） | 3~5 分钟 | 1,2,3 |
| `JwtAuthSimulation` | JWT 认证路径 vs 非认证路径对比 | 3~5 分钟 | 1,2 |
| `RateLimitSimulation` | 限流场景（429 视为预期响应） | 1~2 分钟 | 1,2 |
| `FileTransferSimulation` | 1MB 上传 + 下载 | 2~3 分钟 | 1,2 |
| `DelaySimulation` | 固定/随机/慢上游延迟场景 | 2~3 分钟 | 1,2 |
| `ConnectionPoolSimulation` | 连接池 borrow/requite 竞争 | 2~3 分钟 | 1,2 |
| `SpikeSimulation` | 流量突增后延迟恢复 | 3~5 分钟 | 1,2 |
| `SoakSimulation` | 长时间稳定性（默认 30 分钟） | 30~35 分钟 | 2 |
| `FaultToleranceSimulation` | 慢上游超时（504）+ 上游宕机（502）+ 混合流量 | 2~3 分钟 | 1,2 |
| `RouteCountSimulation` | 1/10/50 条路由规则性能对比 | 5~10 分钟 | 2 |
| `MockBaselineSimulation` | 零延迟上游理论最大 RPS | 3~5 分钟 | 2 |
| `ConnectionModeSimulation` | Keep-Alive vs 短连接对比 | 3~5 分钟 | 2 |
| `KneeDetectionSimulation` | 膝点 RPS 探测 | 5~10 分钟 | 2 |

---

## Docker 部署（场景 B）

适用于：标准 benchmark，环境隔离，网络延迟可控。

**步骤**：

```bash
# 1. 构建 JAR
mvn package -pl gateway-example,gateway-app,auth-jwt-example -DskipTests

# 2. 启动容器
cd gateway-perf
docker compose up -d

# 3. 等待就绪（约 30 秒）
docker compose ps  # 确认状态为 healthy

# 4. 运行测试
mvn gatling:test -pl gateway-perf -Dgatling.baseUrl=http://localhost:8080 -Dgatling.targetRps=3000
```

> Docker bridge 场景建议 `targetRps` 设为 3000，本地 loopback 可设为 5000。

---

## 同步 Profiling

压测期间可同步采集 CPU 火焰图、分配火焰图和 JFR 录制：

```bash
# 获取网关 PID
GATEWAY_PID=$(jps | grep GatewayApplication | awk '{print $1}')

# 启动 profiling（需要 async-profiler，设置 ASYNC_PROFILER_HOME 环境变量）
export ASYNC_PROFILER_HOME=/path/to/async-profiler
./gateway-perf/scripts/profile.sh $GATEWAY_PID 120

# 同时在另一个终端运行压测
mvn gatling:test -pl gateway-perf
```

输出文件：
- `target/profiling/cpu-flamegraph.svg` — CPU 热点火焰图
- `target/profiling/alloc-flamegraph.svg` — 对象分配火焰图
- `target/profiling/gateway.jfr` — JFR 录制（可用 JDK Mission Control 分析）
- `target/profiling/resource-usage.csv` — CPU% 和堆内存时序数据（每 5 秒采样）

---

## 常见问题

**Q：为什么场景一的数据不能用于正式发布？**

A：场景一在最大并发下测试，P99 延迟包含大量排队时间，不能反映网关的真实处理能力。场景二通过膝点探测找到临界点，然后在 70% 负载下测试，这样 P99 才代表网关实际能处理的速度。

**Q：第一次跑需要做什么？**

A：先跑场景二，按顺序执行 KneeDetection → BaseProxy → MockBaseline，生成初始基线。后续可以用场景三做 CI 自动化。

**Q：所有 Simulation 一起跑和单个跑结果一样吗？**

A：一样。`-DsimulationClass=xxx` 只是指定跑哪个，不影响测试逻辑。一起跑的好处是省事，单个跑的好处是快（CI 用）。

**Q：测试失败怎么办？**

A：
- Assertion 失败（错误率、RPS 不达标）：检查网关日志，看是否有异常
- 基线退化：确认是代码变更导致的还是环境问题，可以用 `-Dgatling.updateBaseline=true` 强制更新基线
- 服务起不来：检查端口占用 `lsof -i :8080`

**Q：可以跳过预热吗？**

A：可以，但数据会包含 JIT 冷启动噪声。用 `-Dgatling.skipWarmup=true` 跳过。
