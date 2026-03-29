# 需求文档

## 简介

为 netty-gateway 项目添加 Gatling 性能测试套件，在受控的固定资源环境（2 CPU 核心、4GB 内存）下，
对网关的核心代理路径进行量化性能基准测试，覆盖吞吐量、延迟分布、错误率等关键指标，
并在 CI 流程中提供可回归的性能基线。

测试对象为运行中的 gateway-app（端口 8080），上游为 gateway-example（端口 8082）。
Gatling 测试作为独立 Maven 模块 `gateway-perf` 存在，不影响现有模块的构建和测试。

## 词汇表

- **Gateway**：被测系统，即 gateway-app，监听 8080 端口的 HTTP 反向代理网关。
- **Upstream**：上游 mock 服务，即 gateway-example，监听 8082 端口。
- **Auth_Service**：JWT 签发服务，即 auth-jwt-example，监听 8091 端口，提供 `POST /api/auth-jwt/token` 接口。
- **Simulation**：Gatling 测试场景类，继承 `io.gatling.javaapi.core.Simulation`。
- **Scenario**：Simulation 内的一个用户行为序列。
- **RPS**：每秒请求数（Requests Per Second）。
- **Proxy_Overhead**：网关自身引入的代理开销，计算方式为端到端延迟减去上游处理时间（ms）。
- **P99**：第 99 百分位响应时间。
- **P95**：第 95 百分位响应时间。
- **基线**：首次通过的性能测试结果，作为后续回归对比的参考值。
- **Assertion**：Gatling 内置断言，测试结束后自动校验指标是否达标，不达标则构建失败。
- **Feeder**：Gatling 数据馈送器，用于参数化请求数据。
- **Perf_Profile**：测试专用 Spring Boot Profile（`perf`），通过 `application-perf.yml` 提供与业务完全隔离的限流和路由配置。

---

## 需求

### 需求 1：独立 Maven 模块

**用户故事：** 作为开发者，我希望性能测试代码与业务代码隔离，以便在不影响现有构建的情况下单独运行性能测试。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 作为独立 Maven 模块 `gateway-perf` 存在于项目根目录，
   与 `gateway-app`、`gateway-core` 平级。
2. THE Gateway_Perf_Module SHALL 在父 `pom.xml` 的 `<modules>` 中声明，但默认不参与 `mvn clean verify` 的测试执行（通过 profile 或 skip 配置隔离）。
3. THE Gateway_Perf_Module SHALL 使用 `gatling-maven-plugin` 驱动测试，Gatling 版本不低于 3.10。
4. WHEN 执行 `mvn gatling:test -pl gateway-perf` 时，THE Gateway_Perf_Module SHALL 运行所有 Simulation 并在 `gateway-perf/target/gatling/` 下生成 HTML 报告。
5. THE Gateway_Perf_Module SHALL 通过 `gatling.baseUrl`、`gatling.rps`、`gatling.duration` 等 Maven 属性支持外部参数化，允许在 CI 中覆盖默认值。

---

### 需求 2：基础代理路径性能测试

**用户故事：** 作为性能工程师，我希望测量网关在纯代理转发场景下的 RPS 吞吐量和 Proxy Overhead，以便建立性能基线并与业界参考值（Kong P99 约 6ms、API7 P95 约 2ms）对比。

#### 验收标准

1. THE Simulation SHALL 包含针对 `GET /api/example/hello` 的 Scenario，模拟稳定并发用户持续发送请求。
2. THE Simulation SHALL 包含针对 `POST /api/example/echo` 的 Scenario，请求体为固定 JSON 字符串（不超过 1KB）。
3. WHEN Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 全局错误率低于 1%
   - 实际 RPS 不低于 `gatling.targetRps` 参数指定的目标值
4. WHEN Assertion 不满足时，THE Gateway_Perf_Module SHALL 以非零退出码结束，使 Maven 构建失败。
5. THE Simulation SHALL 支持通过 `gatling.users`（并发用户数，默认 50）、`gatling.duration`（持续秒数，默认 60）和 `gatling.targetRps`（目标 RPS，默认 5000）参数化负载配置。
6. THE Simulation SHALL 在报告中输出 P95 / P99 端到端延迟，供人工计算 Proxy_Overhead（端到端延迟 − 上游处理时间）；由于 gateway-example 无业务延迟，端到端延迟即近似等于 Proxy_Overhead。

---

### 需求 3：限流场景测试

**用户故事：** 作为性能工程师，我希望验证网关限流器在高并发下的行为，以便确认限流配置生效且不会导致服务崩溃。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 提供测试专用配置文件 `application-perf.yml`，该文件须声明：
   - 独立的限流路由（如 `id: perf-rate-limit-test`，路径 `/api/perf/hello`），限流阈值通过 `gatling.rateLimitRps` 参数指定（默认 100 RPS）
   - 独立的上游指向 gateway-example（`http://localhost:8082`）
   - 不引用、不覆盖 gateway-app 的任何业务路由配置
2. WHEN 使用 `--spring.profiles.active=perf` 启动 gateway-app 时，THE Gateway SHALL 加载 `application-perf.yml` 中的测试专用路由和限流规则，与业务配置完全隔离。
3. THE Simulation SHALL 包含限流压测 Scenario，以超过限流阈值的 RPS 向 `GET /api/perf/hello` 发送请求。
4. WHEN 限流触发时，THE Simulation SHALL 记录 HTTP 429 响应，并将其计入预期响应而非错误。
5. WHEN 限流场景执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - HTTP 2xx + 429 的合计比例高于 99%（即非预期错误率低于 1%）
   - 网关进程在测试期间保持存活（通过 `/health/live` 探针验证）
6. THE Simulation SHALL 在限流场景结束后，通过独立 Scenario 向 `/health/live` 发送请求，
   WHEN 响应状态码为 200 时，THE Simulation SHALL 记录网关存活验证通过。

---

### 需求 4：大文件上传/下载性能测试

**用户故事：** 作为性能工程师，我希望测量网关在大文件传输场景下的吞吐量，以便验证 50MB 限制内的传输性能。

#### 验收标准

1. THE Simulation SHALL 包含文件上传 Scenario，向 `POST /api/example/upload` 上传大小为 1MB 的二进制文件。
2. THE Simulation SHALL 包含文件下载 Scenario，向 `GET /api/example/download` 下载约 10MB 的文件。
3. WHEN 文件上传/下载 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 全局错误率低于 1%
4. THE Simulation SHALL 对文件传输场景使用较低并发（默认 `gatling.fileUsers`，默认值 10），
   避免在 2 CPU / 4GB 内存约束下耗尽资源。

---

### 需求 5：JWT 认证路径性能测试

**用户故事：** 作为性能工程师，我希望测量带 JWT 认证的请求路径的额外延迟开销，以便评估安全层对性能的影响。

#### 验收标准

1. WHEN Simulation 启动时，THE Simulation SHALL 自动向 Auth_Service 的 `POST /api/auth-jwt/token` 接口发送请求以获取 JWT Token，请求体为 `{"userId": "perf-test-user"}`。
2. WHEN Auth_Service 返回 HTTP 200 时，THE Simulation SHALL 从响应体的 `accessToken` 字段提取 Token，并在后续所有 JWT 认证 Scenario 中以 `Authorization: Bearer <token>` 头携带该 Token。
3. IF Auth_Service 在 Simulation 启动阶段返回非 200 响应，THEN THE Simulation SHALL 终止并报告"JWT Token 获取失败，无法执行认证场景"。
4. THE Simulation SHALL 包含 JWT 认证 Scenario，携带步骤 1 获取的 Token 向 `GET /api/example/private/profile` 发送请求。
5. WHEN JWT 认证 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 全局错误率低于 1%
   - 实际 RPS 不低于 `gatling.targetRps` 参数指定的目标值
6. THE Simulation SHALL 在报告中输出 JWT 认证路径与非认证路径（`/api/example/hello`）的 P95 / P99 延迟对比数据，供人工评估认证层引入的 Proxy_Overhead 增量。

---

### 需求 6：性能报告与 CI 集成

**用户故事：** 作为 CI 工程师，我希望性能测试结果可以被 CI 系统收集和展示，以便追踪性能趋势。

#### 验收标准

1. WHEN 所有 Simulation 执行完毕，THE Gateway_Perf_Module SHALL 在 `gateway-perf/target/gatling/` 下生成包含以下内容的 HTML 报告：
   - 请求数、错误率、RPS 时序图
   - P50 / P75 / P95 / P99 响应时间分布
   - 每个 Scenario 的独立统计
2. THE Gateway_Perf_Module SHALL 提供 `README.md`，说明本地运行步骤、参数说明和 CI 集成方式。
3. WHERE CI 环境变量 `CI=true` 存在，THE Gateway_Perf_Module SHALL 通过 `gatling.simulationClass` 属性支持指定单个 Simulation 运行，以缩短 CI 执行时间。
4. THE Gateway_Perf_Module SHALL 将 Gatling 报告目录配置为 Maven Surefire/Failsafe 之外的独立目录，避免与单元测试报告混淆。

---

### 需求 7：双部署拓扑支持

**用户故事：** 作为性能工程师，我希望能在本地快速验证和标准 benchmark 两种环境下运行性能测试，以便在开发阶段和 CI 阶段分别使用合适的拓扑。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 支持两种部署拓扑：
   - 场景 A（本地快速验证）：gateway-app 与 gateway-example 同机运行，`gatling.baseUrl` 默认为 `http://localhost:8080`，走 loopback 网络。
   - 场景 B（标准 benchmark）：gateway-app 与 gateway-example 分别运行在独立 Docker 容器中，走 Docker bridge 网络，网络延迟可控在 1ms 以内。
2. THE Gateway_Perf_Module SHALL 通过 `gatling.baseUrl` Maven 属性切换目标地址，场景 A 使用默认值 `http://localhost:8080`，场景 B 传入容器 IP 或 hostname（如 `http://gateway-app:8080`）。
3. THE Gateway_Perf_Module SHALL 在模块根目录提供 `docker-compose.yml`，声明 `gateway-app` 和 `gateway-example` 两个服务，使场景 B 开箱即用，执行 `docker compose up -d` 后即可运行 Gatling 测试；Docker bridge 场景建议 `gatling.targetRps` 设为 3000。
4. WHEN 使用 `docker-compose.yml` 启动时，THE docker-compose.yml SHALL 将 gateway-app 的 8080 端口和 gateway-example 的 8082 端口映射到宿主机，以便 Gatling 进程（运行在宿主机）直接访问。
5. THE docker-compose.yml SHALL 通过 `healthcheck` 配置确保 gateway-example 就绪后 gateway-app 再启动，避免启动竞态导致路由初始化失败。
6. THE Gateway_Perf_Module 的 `README.md` SHALL 分别说明场景 A 和场景 B 的启动步骤及 `gatling.baseUrl` 传参方式。

---

### 需求 8：性能基线回归检测

**用户故事：** 作为性能工程师，我希望每次测试完成后自动与历史基线对比，以便在性能退化时立即感知并阻断构建。

#### 验收标准

1. WHEN 所有 Simulation 执行完毕，THE Gateway_Perf_Module SHALL 将本次测试的核心指标（RPS、P95、P99、错误率）写入基线文件 `gateway-perf/baseline.json`，格式为 JSON，字段名与指标名一一对应。
2. THE baseline.json SHALL 纳入版本控制，随代码一起提交，体现网关每次迭代的性能演进历史。
3. WHEN 基线文件已存在且 `gatling.updateBaseline` 属性不为 `true` 时，THE Gateway_Perf_Module SHALL 在测试完成后执行基线对比：
   - WHEN 本次 RPS 低于基线 RPS 超过 10%（或 `gatling.rpsRegressionThreshold` 指定的百分比），THEN THE Gateway_Perf_Module SHALL 以非零退出码结束并输出退化报告，报告内容包含基线值、本次值和退化幅度。
   - WHEN 本次错误率高于基线错误率，THEN THE Gateway_Perf_Module SHALL 以非零退出码结束并输出退化报告。
4. WHEN `gatling.updateBaseline=true` 时，THE Gateway_Perf_Module SHALL 以本次测试结果强制覆盖 `baseline.json`，并输出"基线已更新"提示，不执行退化检测。
5. IF `baseline.json` 不存在，THEN THE Gateway_Perf_Module SHALL 将本次结果写入 `baseline.json` 作为初始基线，并输出"初始基线已创建"提示，不执行退化检测。
6. THE 退化报告 SHALL 以人类可读格式输出到标准输出，同时写入 `gateway-perf/target/gatling/regression-report.txt`，供 CI 系统归档。

---

### 需求 9：上游延迟模拟接口与对应 Scenario

**用户故事：** 作为性能工程师，我希望 gateway-example 提供可参数化的延迟接口，以便在 Gatling 测试中模拟真实业务延迟，使 Proxy Overhead 的相对占比更有意义。

#### 验收标准

1. THE Upstream SHALL 新增固定延迟接口 `GET /api/example/delay/fixed`，接受查询参数 `ms`（整数，默认 50），WHEN 请求到达时，THE Upstream SHALL 等待 `ms` 毫秒后返回 HTTP 200 及响应体 `{"delay": <ms>, "type": "fixed"}`。
2. THE Upstream SHALL 新增随机延迟接口 `GET /api/example/delay/random`，接受查询参数 `min`（默认 0）和 `max`（默认 100），WHEN 请求到达时，THE Upstream SHALL 在 `[min, max]` ms 范围内均匀随机等待后返回 HTTP 200 及响应体 `{"delay": <actual_ms>, "type": "random"}`。
3. THE Upstream SHALL 新增慢接口 `GET /api/example/delay/slow`，WHEN 请求到达时，THE Upstream SHALL 固定等待 500ms 后返回 HTTP 200 及响应体 `{"delay": 500, "type": "slow"}`。
4. IF `ms` 参数为负数或非整数，THEN THE Upstream SHALL 返回 HTTP 400 及描述性错误信息。
5. IF `min` 大于 `max`，THEN THE Upstream SHALL 返回 HTTP 400 及描述性错误信息。
6. THE Simulation SHALL 新增"上游延迟场景"Scenario 组，包含以下三个 Scenario：
   - 固定延迟 Scenario：向 `GET /api/example/delay/fixed?ms=50` 发送请求，测量网关端到端延迟，计算 Proxy_Overhead（端到端延迟 − 50ms）。
   - 随机延迟 Scenario：向 `GET /api/example/delay/random?min=0&max=100` 发送请求，测量 P95 / P99 延迟分布。
   - 慢上游 Scenario：向 `GET /api/example/delay/slow` 发送请求，以较低并发（默认 `gatling.slowUsers`，默认值 10）验证网关在慢上游下的稳定性。
7. WHEN 上游延迟场景执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 全局错误率低于 1%
   - 固定延迟 Scenario 的 P99 端到端延迟不超过 `50ms + gatling.maxProxyOverheadMs`（默认 20ms），即 P99 ≤ 70ms。

---

### 需求 10：连接池压力测试

**用户故事：** 作为性能工程师，我希望在高并发下验证网关 H2 连接池的 borrow/requite 竞争行为及连接泄漏情况，以便确认连接池在极限压力下的正确性。

#### 验收标准

1. THE Simulation SHALL 包含连接池压力 Scenario，以超过连接池最大连接数的并发用户数持续向 `GET /api/example/hello` 发送请求，触发 borrow/requite 竞争。
2. WHEN 连接池已满时，THE Simulation SHALL 验证后续请求进入等待队列而非立即返回错误，通过观察响应时间分布（无异常 5xx 峰值）来确认排队行为。
3. WHEN 连接池压力 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 全局错误率低于 1%
   - 连接池耗尽导致的超时响应（HTTP 503 或连接超时）比例低于 5%
4. THE Simulation SHALL 在压测开始前通过 `/health/live` 记录当前活跃连接数，压测结束后再次查询，WHEN 两次连接数差值超过 `gatling.maxConnectionLeak`（默认 0）时，THE Simulation SHALL 输出连接泄漏告警并使构建失败。

---

### 需求 11：流量突增（Spike）测试

**用户故事：** 作为性能工程师，我希望模拟流量突增场景，以便验证网关在 spike 期间的稳定性及 spike 结束后的延迟恢复能力。

#### 验收标准

1. THE Simulation SHALL 包含 Spike Scenario，注入模式为：从 10 并发用户在 5 秒内线性增加到 `gatling.spikeUsers`（默认 200）并发用户，持续 30 秒后在 5 秒内回落至 10 并发用户。
2. WHEN Spike Scenario 执行期间，THE Simulation SHALL 通过 Assertion 校验：
   - spike 期间（高峰 30 秒内）错误率低于 5%
   - 不出现 HTTP 5xx 响应率超过 5% 的情况
3. WHEN Spike Scenario 结束后 60 秒内，THE Simulation SHALL 通过 Assertion 校验 P99 延迟恢复至基线 P99 的 120% 以内（即退化不超过 20%）。
4. THE Simulation SHALL 支持通过 `gatling.spikeUsers`（默认 200）和 `gatling.spikeDuration`（默认 30 秒）参数化 spike 峰值配置。

---

### 需求 12：长时间稳定性测试（Soak Test）

**用户故事：** 作为性能工程师，我希望通过长时间持续压测发现内存泄漏和性能退化问题，以便确认网关在生产环境长期运行的稳定性。

#### 验收标准

1. THE Simulation SHALL 包含 Soak Scenario，持续时间不低于 `gatling.soakDuration`（默认 1800 秒），以稳定并发用户数持续向 `GET /api/example/hello` 发送请求。
2. WHEN Soak Scenario 执行期间，THE profile.sh SHALL 通过 `jcmd <pid> GC.heap_info` 每隔 60 秒采样一次网关 JVM 堆内存使用量，将堆内存时序数据追加写入 `resource-usage.csv`（与 CPU 数据合并，格式新增 `heap_used_mb` 列）；THE Simulation 只负责触发压测流量，不负责采集网关 JVM 内部指标。
3. WHEN Soak Scenario 执行完毕，THE profile.sh SHALL 输出内存趋势摘要，包含：起始堆占用（MB）、结束堆占用（MB）、测试期间最大堆占用（MB）。
4. WHEN Soak Scenario 执行完毕，THE Gateway_Perf_Module SHALL 通过 Assertion 校验：
   - 测试结束时堆占用不超过起始堆占用的 150%（排除 JVM 预热影响）
5. THE Simulation SHALL 支持通过 `gatling.soakDuration`（默认 1800）和 `gatling.soakUsers`（默认 50）参数化 Soak 测试配置。

---

### 需求 13：上游不可用 / 超时容错测试

**用户故事：** 作为性能工程师，我希望验证网关在上游不可用或超时场景下的容错行为，以便确认网关能正确返回标准错误码且正常请求不受影响。

#### 验收标准

1. THE Simulation SHALL 包含慢上游 Scenario（场景 A）：向 `GET /api/example/delay/slow`（固定 500ms 延迟）发送请求，WHEN 网关超时阈值小于 500ms 时，THE Simulation SHALL 通过 Assertion 校验响应状态码为 504，而非请求 hang 住超过 `gatling.upstreamTimeoutMs`（默认 2000ms）。
2. THE Simulation SHALL 包含上游宕机 Scenario（场景 B）：向配置了不可达上游地址的路由发送请求，WHEN 上游连接被拒绝时，THE Simulation SHALL 通过 Assertion 校验响应状态码为 502。
3. THE Simulation SHALL 包含混合流量 Scenario（场景 C）：50% 请求发往正常上游（`GET /api/example/hello`），50% 请求发往慢上游（`GET /api/example/delay/slow`），WHEN 混合流量 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验正常请求的 P99 延迟不超过纯正常流量基线 P99 的 120%（即慢上游不影响正常请求的隔离性）。
4. WHEN 场景 A 和场景 B 执行完毕，THE Simulation SHALL 通过 Assertion 校验：502/504 响应率与注入故障比例的误差低于 5%。

---

### 需求 14：多路由规则性能对比测试

**用户故事：** 作为性能工程师，我希望测量不同路由规则数量对网关吞吐量的影响，以便评估 RoutingHandler 路径匹配的性能开销。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 在 `application-perf.yml` 中分别提供 1 条、10 条、50 条路由规则的测试配置，所有路由均指向 gateway-example，不影响业务配置。
2. THE Simulation SHALL 包含路由规则对比 Scenario 组，分别在 1 条、10 条、50 条路由配置下向目标路由发送相同负载的请求，记录各配置下的 RPS 和 P99 延迟。
3. WHEN 路由规则对比 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：50 条路由配置下的 RPS 退化不超过 1 条路由配置下 RPS 的 10%。
4. THE Simulation SHALL 支持通过 `gatling.routeCountVariants`（默认 `1,10,50`）参数化路由数量变体配置。

---

### 需求 15：基线回归补充 P99 退化检测

**用户故事：** 作为性能工程师，我希望在需求 8 的基线回归检测基础上补充 P99 退化检测，以便在响应时间退化时立即感知并阻断构建。

#### 验收标准

1. WHEN 基线文件已存在且 `gatling.updateBaseline` 属性不为 `true` 时，THE Gateway_Perf_Module SHALL 在需求 8 的退化检测基础上额外执行 P99 对比：WHEN 本次 P99 高于基线 P99 超过 `gatling.p99RegressionThreshold`（默认 20%）时，THEN THE Gateway_Perf_Module SHALL 以非零退出码结束并输出 P99 退化报告。
2. THE P99 退化报告 SHALL 包含以下字段：基线 P99（ms）、本次 P99（ms）、退化幅度百分比。
3. THE P99 退化报告 SHALL 以人类可读格式输出到标准输出，同时追加写入 `gateway-perf/target/gatling/regression-report.txt`，与需求 8 的退化报告合并存储。

---

### 需求 16：压测期间同步 Profiling 监控

**用户故事：** 作为性能工程师，我希望在压测执行期间同步采集 CPU 火焰图、分配火焰图和 JFR 录制，以便在发现性能问题时快速定位热点代码和内存分配来源。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 在 `scripts/profile.sh` 提供 profiling 启动脚本，接受网关进程 PID 作为参数，压测结束后自动停止所有 profiling 并输出摘要。
2. WHEN `profile.sh` 启动时，THE profile.sh SHALL 使用 async-profiler 采集 CPU 热点，输出火焰图 SVG 到 `gateway-perf/target/profiling/cpu-flamegraph.svg`。
3. WHEN `profile.sh` 启动时，THE profile.sh SHALL 使用 async-profiler 采集对象分配热点，输出分配火焰图 SVG 到 `gateway-perf/target/profiling/alloc-flamegraph.svg`。
4. WHEN `profile.sh` 启动时，THE profile.sh SHALL 开启 JFR 录制，录制文件输出到 `gateway-perf/target/profiling/gateway.jfr`，须同时启用以下事件类别：
   - `jdk.ObjectAllocationSample`（分配热点采样）
   - `jdk.GarbageCollection` 和 `jdk.GCPhasePause`（GC 停顿）
   - `jdk.ThreadPark` 和 `jdk.MonitorWait`（线程阻塞）
   - `jdk.CPULoad`（用户态/内核态 CPU 占用）
   - `jdk.ObjectAllocationInNewTLAB` 和 `jdk.ObjectAllocationOutsideTLAB`（TLAB 外分配）
   - `jdk.ClassLoad`（类加载）
5. THE JFR 录制 SHALL 使用 `settings=profile` 配置，确保录制开销低于 2%，不影响压测数据有效性。
6. THE docker-compose.yml 中的 gateway-app 容器 SHALL 预置 JVM 参数 `-Xmx3g -XX:+UseG1GC -XX:+FlightRecorder`，确保不同机器测试结果可比。
7. IF 指定的 PID 不存在或 async-profiler 未安装，THEN THE profile.sh SHALL 输出描述性错误信息并以非零退出码退出，不影响压测流程继续执行。

---

### 需求 17：网关自身基线测试（Mock 场景）

**用户故事：** 作为性能工程师，我希望测量网关在上游零延迟下的理论最大 RPS，以便建立网关自身吞吐能力的基准值，量化各功能引入的开销百分比。

#### 验收标准

1. THE Upstream SHALL 新增 Mock 接口 `GET /api/example/mock`，WHEN 请求到达时，THE Upstream SHALL 直接返回 HTTP 200 及固定响应体 `{"status":"ok"}`，无任何业务逻辑，响应时间趋近于 0。
2. THE Simulation SHALL 包含 Mock 基线 Scenario，以最大并发向 `GET /api/example/mock` 发送请求，测量网关在上游零延迟下的理论最大 RPS 上限。
3. THE Mock 基线 Scenario 的测试结果 SHALL 作为网关自身吞吐能力的基准值，记录到 `baseline.json` 的 `mockRps` 字段，供后续所有场景对比，量化认证、限流、路由匹配各功能引入的开销百分比。
4. WHEN Mock 基线 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 错误率低于 1%
   - 本场景 RPS 高于需求 2 基础代理场景的 RPS（IF 本场景 RPS 不高于需求 2 RPS，THEN THE Simulation SHALL 输出警告"Mock 场景 RPS 未高于基础代理场景，测试环境可能存在问题"并使构建失败）

---

### 需求 18：客户端连接模式性能对比（Keep-Alive vs 短连接）

**用户故事：** 作为性能工程师，我希望对比 Gatling 与网关之间 Keep-Alive 和短连接两种模式的性能差异，以便量化网关处理连接建立/销毁的开销。

#### 验收标准

1. THE Simulation SHALL 包含连接模式对比 Scenario 组，测试对象为 Gatling（客户端）与网关之间的连接模式，与上游 H2 连接池无关：
   - 场景 A（Keep-Alive）：使用 HTTP/1.1 长连接（Gatling 默认模式），向 `GET /api/example/hello` 发送请求。
   - 场景 B（短连接）：每次请求携带 `Connection: close` 头，强制新建连接，向同一接口发送请求。
2. THE 两个 Scenario SHALL 使用相同的并发用户数和持续时间，确保对比结果可信。
3. WHEN 连接模式对比 Scenario 执行完毕，THE Simulation SHALL 在报告中输出两种模式的 RPS 对比和 P99 延迟对比，量化网关处理连接建立/销毁的开销。
4. WHEN 连接模式对比 Scenario 执行完毕，THE Simulation SHALL 通过 Assertion 校验：
   - 场景 A 错误率低于 1%
   - 场景 B 错误率低于 1%

---

### 需求 19：压测期间资源利用率监控

**用户故事：** 作为性能工程师，我希望在压测期间同步采集网关进程的 CPU 和内存利用率时序数据，以便证明测试期间网关已达到资源瓶颈而非测试工具成为瓶颈。

#### 验收标准

1. WHEN `scripts/profile.sh` 启动时，THE profile.sh SHALL 同时采集网关进程的 CPU 和内存利用率时序数据，每 5 秒采样一次，输出到 `gateway-perf/target/profiling/resource-usage.csv`，格式为：`timestamp,cpu_percent,heap_used_mb,heap_max_mb`。
2. WHEN 压测结束时，THE profile.sh SHALL 输出资源利用率摘要，包含：峰值 CPU%、平均 CPU%、峰值堆内存（MB）。
3. IF 压测结束时 CPU 利用率低于 50%，THEN THE profile.sh SHALL 输出警告"网关 CPU 利用率过低，测试可能未达到性能瓶颈，建议增加并发用户数"。
4. THE resource-usage.csv 数据 SHALL 用于证明测试期间网关已达到资源瓶颈（CPU 利用率应接近 200%，即 2 核满载），而非测试工具成为瓶颈。

---

## 待办

- TODO：路由热重载性能测试 — 待确认网关是否支持运行时动态更新路由配置（无需重启）。若支持，需补充测试：动态新增/删除路由后，新路由生效时间（路由传播延迟），以及热重载期间对正在处理请求的影响（是否有请求失败）。

---

### 需求 20：JVM 预热（Warmup）阶段

**用户故事：** 作为性能工程师，我希望每个 Simulation 在正式计数前有充分的 JVM 预热期，以便 JIT 编译器完成热路径优化，确保测试数据反映网关的峰值性能而非冷启动性能。

#### 验收标准

1. THE Simulation SHALL 在每个 Scenario 正式计数阶段开始前，先执行 `gatling.warmupDuration`（默认 30 秒）的预热阶段，预热期间以较低并发（`gatling.warmupUsers`，默认值 10）持续发送请求。
2. WHEN 预热阶段执行期间，THE Simulation SHALL 不将预热期间的请求计入 RPS、P95、P99 等性能指标统计。
3. WHEN 预热阶段结束时，THE Simulation SHALL 输出"预热完成，开始正式计数"日志，标记正式测试开始时间。
4. THE 预热阶段 SHALL 覆盖所有 Simulation（包括基础代理、JWT 认证、限流、连接池压力等），不得跳过。
5. IF `gatling.skipWarmup=true`，THEN THE Simulation SHALL 跳过预热阶段并输出警告"已跳过预热，测试数据可能包含 JIT 冷启动噪声"。

---

### 需求 21：重复测试与结果平均

**用户故事：** 作为性能工程师，我希望每个核心测试场景重复执行多次并取平均值，以便消除单次测试的环境噪声，提高结果可信度（对标 Kong/APISIX 的 5 次重复标准）。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 支持通过 `gatling.repeatCount`（默认 3，CI 环境可设为 1）参数指定每个 Scenario 的重复执行次数。
2. WHEN `gatling.repeatCount > 1` 时，THE Gateway_Perf_Module SHALL 对每次重复的 RPS、P95、P99 取算术平均值，作为该 Scenario 的最终结果。
3. THE 最终报告 SHALL 同时输出每次重复的原始数据和平均值，供人工判断结果稳定性；WHEN 各次 RPS 偏差超过 10% 时，THE Gateway_Perf_Module SHALL 输出警告"结果波动较大，建议检查测试环境稳定性"。
4. THE baseline.json SHALL 存储重复测试的平均值，而非单次结果。

---

### 需求 22：膝点负载下的延迟测量

**用户故事：** 作为性能工程师，我希望在 QPS-延迟曲线的膝点以下测量延迟，以便获得有意义的延迟数据（对标 Envoy 官方 benchmark 最佳实践：永远不要在最大负载下测延迟）。

#### 验收标准

1. THE Gateway_Perf_Module SHALL 提供膝点探测 Scenario：从低并发（10 用户）开始，每 15 秒递增 10 用户，WHEN P99 延迟超过前一阶段 50% 或错误率超过 1% 时，THE Simulation SHALL 停止递增并记录此时的 RPS 为"膝点 RPS"。
2. THE 基础延迟测量 Scenario（需求 2）SHALL 在膝点 RPS 的 70% 负载下执行，而非在最大并发下执行，以获得有意义的稳态延迟数据。
3. WHEN 膝点探测完成时，THE Simulation SHALL 输出膝点 RPS 值，并将其记录到 `baseline.json` 的 `kneeRps` 字段。
4. THE Gateway_Perf_Module 的 `README.md` SHALL 说明膝点测量的意义：在膝点以下测量的 P99 才能反映网关在正常生产负载下的真实延迟，而非排队延迟。

---

### 需求 23：测试环境噪声记录

**用户故事：** 作为性能工程师，我希望每次测试自动记录宿主机系统负载，以便区分性能差异是真实退化还是环境抖动。

#### 验收标准

1. WHEN 每次 Simulation 开始时，THE Gateway_Perf_Module SHALL 自动采集并记录以下环境快照到 `gateway-perf/target/gatling/env-snapshot.json`：
   - 宿主机 CPU 核心数、可用内存（MB）
   - 测试开始时的系统 load average（1min、5min、15min）
   - 操作系统类型和版本
   - JVM 版本和启动参数
   - Gatling 版本
2. WHEN 测试结束时，THE Gateway_Perf_Module SHALL 再次采集系统 load average 与开始时对比；IF 测试期间 load average 超过 CPU 核心数的 80%，THEN THE Gateway_Perf_Module SHALL 输出警告"宿主机负载过高，测试结果可能受环境干扰，建议重新测试"。
3. THE env-snapshot.json SHALL 与 Gatling HTML 报告一起归档，作为测试结果有效性的证明。
4. THE baseline.json SHALL 包含生成该基线时的环境快照摘要（CPU 核心数、JVM 版本），用于判断不同环境下基线对比的有效性。

---

### 需求 24：开环负载模型

**用户故事：** 作为性能工程师，我希望使用开环（open-loop）负载模型生成压测流量，以便避免闭环模型在高延迟时自动降低 RPS 导致数据失真（对标 Envoy 推荐的开环负载生成器最佳实践）。

#### 验收标准

1. THE 所有吞吐量测试 Scenario（需求 2、17、18）SHALL 使用 Gatling 的 `constantUsersPerSec` 注入模式（开环），而非 `rampUsers`（闭环），确保在网关延迟升高时 RPS 注入速率不变，真实反映网关的排队和降级行为。
2. THE Soak Test（需求 12）和 Spike Test（需求 11）SHALL 同样使用开环注入模式。
3. THE Gateway_Perf_Module 的 `README.md` SHALL 说明开环 vs 闭环的区别：闭环模型在高延迟时会自动减少并发，导致 RPS 下降，掩盖真实的性能瓶颈；开环模型保持固定注入速率，能真实暴露网关的排队和超时行为。
4. IF 使用开环模型时请求队列积压导致 Gatling 客户端内存溢出，THEN THE Simulation SHALL 输出警告并自动降级为闭环模式，同时在报告中标注"已降级为闭环模式"。
