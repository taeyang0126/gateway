# gateway-perf — Gatling 性能测试

## 前置准备

```bash
# macOS 默认文件描述符限制 256，高并发压测会 Too many open files
# 三个终端都要执行
ulimit -n 65536

mvn install -DskipTests

# 终端1：启动上游服务
mvn spring-boot:run -pl example-upstream \
   -Dspring-boot.run.jvmArguments="-Xms512m -Xmx512m" \
   -Dspring-boot.run.arguments="--spring.profiles.active=perf"

# 终端2：启动网关（perf profile：连接池 20、异步日志、关闭控制台输出）
mvn spring-boot:run -pl gateway \
  -Dspring-boot.run.jvmArguments="-Xms1g -Xmx1g" \
  -Dspring-boot.run.arguments="--spring.profiles.active=perf"
```

## 运行测试

### 核心流程（必须按顺序）

```bash
# 第1步：探测膝点（必须先跑；默认注入总时长约 85s）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.KneeDetectionSimulation \
  -DrequestTimeoutMillis=1200 \
  -DclientMaxConnectionsPerHost=300

# 第2步：基础代理性能（自动读取 kneeRps，以 70% 负载运行）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.BaseProxySimulation

# 第3步（可选）：零延迟上游，测网关理论最大 RPS
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.MockBaselineSimulation

# 第4步（可选）：固定 1s 延迟，测并发承载极限（连接池/stream）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.Delay1sKneeSimulation \
  -DdelayMs=1000 \
  -DrequestTimeoutMillis=3500 \
  -DclientMaxConnectionsPerHost=500 \
  -DmaxFailedPercent=99 \
  -DstartUsers=200 \
  -DstepUsers=200 \
  -DmaxSteps=5
```

不跑膝点直接跑 BaseProxySimulation 会回退到 50 users/sec，RPS 远低于 5000 断言，必定失败。

### 按需跑其他场景

膝点探测完成后，以下场景可独立运行，顺序无关：

```bash
# JWT 认证路径 vs 非认证路径对比
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.JwtAuthSimulation

# 限流（429 视为预期响应）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.RateLimitSimulation

# 连接池 borrow/requite 竞争
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.ConnectionPoolSimulation

# 流量突增后延迟恢复
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.SpikeSimulation

# 慢上游超时（504）+ 上游宕机（502）+ 混合流量
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.FaultToleranceSimulation

# 1MB 上传 + 下载
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.FileTransferSimulation

# 固定/随机/慢上游延迟
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.DelaySimulation

# Keep-Alive vs 短连接对比
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.ConnectionModeSimulation

# 1/10/50 条路由规则性能对比
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.RouteCountSimulation

# 长时间稳定性（默认 30 分钟）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.SoakSimulation
```

全部跑：`mvn gatling:test -pl gateway-perf`（不指定 simulationClass，可能因个别场景断言失败而中断，不建议用于探索阶段）。

## Docker 环境测试

本地回环测试受系统调度影响，数据波动大。Docker 环境隔离更好，适合正式 benchmark。

```bash
# 1. 构建 JAR
mvn package -pl example-upstream,gateway,example-auth -DskipTests

# 2. 启动容器
cd gateway-perf
docker compose up -d

# 3. 等待就绪（约 30 秒）
docker compose ps  # 确认状态为 healthy

# 4. 探测膝点
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.KneeDetectionSimulation \
  -DbaseUrl=http://localhost:8080

# 5. 正式测试（Docker bridge 建议 targetRps 设 3000）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.BaseProxySimulation \
  -DbaseUrl=http://localhost:8080 -DtargetRps=3000

# 6. 测完关容器
cd gateway-perf
docker compose down
```

Docker bridge 网络有额外开销，`targetRps` 建议设 3000（本地回环可设 5000）。

## 看结果

报告在 `gateway-perf/target/gatling/<simulation>-<timestamp>/index.html`，打开后看三个指标：

1. `Global > failed` — 失败率，应 < 1%
2. `Global > mean req/s` — 吞吐量
3. `Response Time > p99` — 尾部延迟

断言失败时看 `js/assertions.xml` 确认具体哪项不达标。

膝点探测场景末尾增加了“峰值短平台 + 平滑回落”，用于降低测试收尾阶段
瞬时连接关闭导致的噪声；Simulation `after()` 会尝试打印
“稳态窗口统计（去掉末尾 N 秒）”。默认 `N=5`，可通过
`-DsteadyWindowTrimSeconds` 调整。

已知现象：从仓库根目录执行 `mvn gatling:test -pl gateway-perf ...` 时，
有概率出现“稳态窗口统计：未找到 simulation.log，跳过”。这不影响
HTML 报告本身，稳态窗口建议以下面脚本手工复核为准：

```bash
python3 - <<'PY'
from pathlib import Path
from collections import Counter

report = Path("gateway-perf/target/gatling/<simulation>-<timestamp>/simulation.log")
run_start = None
status_per_sec = Counter()
ko_per_sec = Counter()

with report.open("r", encoding="utf-8", errors="ignore") as f:
    for line in f:
        if line.startswith("RUN\t"):
            run_start = int(line.split("\t")[3])
            continue
        if not line.startswith("REQUEST\t") or run_start is None:
            continue
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 6:
            continue
        sec = (int(parts[4]) - run_start) // 1000
        status_per_sec[sec] += 1
        if parts[5] == "KO":
            ko_per_sec[sec] += 1

last_sec = max(status_per_sec) if status_per_sec else 0
steady_secs = [s for s in status_per_sec if s <= last_sec - 5]
steady_total = sum(status_per_sec[s] for s in steady_secs)
steady_ko = sum(ko_per_sec[s] for s in steady_secs)
rate = (steady_ko / steady_total * 100) if steady_total else 0.0
print(f"steady_window: 0~{last_sec-5}s, total={steady_total}, ko={steady_ko}, ko_rate={rate:.4f}%")
PY
```

### 膝点探测固定参数（推荐）

针对“网关 + 本机/同机房低时延上游”的压测场景，默认固定使用：

1. `-DrequestTimeoutMillis=1200`
2. `-DclientMaxConnectionsPerHost=300`
3. `-DsteadyWindowTrimSeconds=5`

这组参数的目标是：避免压测端先成为瓶颈，同时不把 timeout 放得过宽。

### 1s 延迟并发压测固定参数（推荐）

当目标是逼近连接池/stream 并发位点，而不是测纯吞吐时，建议使用：

1. `-Dgatling.simulationClass=com.lei.gateway.perf.simulation.Delay1sKneeSimulation`
2. `-DdelayMs=1000`
3. `-DrequestTimeoutMillis=3500`
4. `-DclientMaxConnectionsPerHost=500`

说明：`delayMs=1000` 会显著抬高在途请求数，更容易观察 `acquire_timeout`、
`pool_exhausted`、`max_streams_exhausted` 这类并发瓶颈指标。

注入峰值计算公式：

`peakUsers = startUsers + maxSteps * stepUsers`

推荐先跑三档定位膝点区间（`delayMs=1000`）：

```bash
# 低档：峰值约 800（peak=200+5*120）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.Delay1sKneeSimulation \
  -DdelayMs=1000 -DrequestTimeoutMillis=3500 -DclientMaxConnectionsPerHost=500 \
  -DstartUsers=200 -DstepUsers=120 -DmaxSteps=5

# 中档：峰值约 1200（默认档，peak=200+5*200）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.Delay1sKneeSimulation \
  -DdelayMs=1000 -DrequestTimeoutMillis=3500 -DclientMaxConnectionsPerHost=500 \
  -DstartUsers=200 -DstepUsers=200 -DmaxSteps=5

# 高档：峰值约 1600（peak=200+5*280）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.Delay1sKneeSimulation \
  -DdelayMs=1000 -DrequestTimeoutMillis=3500 -DclientMaxConnectionsPerHost=500 \
  -DstartUsers=200 -DstepUsers=280 -DmaxSteps=5
```

探索膝点阶段建议带 `-DmaxFailedPercent=99`，避免因断言失败导致
Maven 直接中断，便于连续收集多档位报告；最终定档后再把阈值收回。

## 看日志

压测期间网关和上游的 WARN/ERROR 日志自动写入项目内：

```bash
cat gateway/gateway/logs/error.log
cat gateway/gateway/logs/warn.log
cat example-upstream/example-upstream/logs/warn.log
```

常见错误速查：


| 日志关键词                          | 含义                           | 排查方向                                                         |
| ----------------------------------- | ------------------------------ | ---------------------------------------------------------------- |
| `MAX_CONCURRENT_STREAMS 已满`       | 所有 H2 连接的 stream 槽位用完 | 增大`max-connections-per-host` 或检查 activeStreamCount 是否泄漏 |
| `H2 stream reset, errorCode=11`     | 上游 ENHANCE_YOUR_CALM         | Tomcat overhead 计数超限，检查是否有大量异常帧                   |
| `H2 connection closed unexpectedly` | 上游关闭了 H2 连接             | 检查上游日志，可能是 GOAWAY 或 overhead 超限                     |
| `写 DATA 帧失败, streamId=0`        | DATA 帧在 streamId 分配前发出  | 网关 bug，检查 connectingToUpstream 时序                         |
| 大量`503`                           | 网关过载                       | 连接池、并发流、限流配置                                         |
| 大量`502`                           | 上游不可达或连接失败           | 确认上游服务是否正常运行                                         |

## 看指标（Proxy 失败定位）

压测时建议同时观察网关 `metrics` 里的代理失败指标：

```bash
curl -s http://localhost:8080/metrics | grep -E "gateway_proxy_failures|gateway_proxy_error_responses"
```

重点看两类指标：

1. `gateway_proxy_failures{stage,reason}`
   - `stage=acquire`：连接池获取/stream 槽位阶段失败
     - `reason=acquire_timeout`：等待可用连接超时（池有抖动/排队）
     - `reason=pool_exhausted`：连接池容量/并发流位点打满
   - `stage=h2_error`：上游 H2 链路错误（如 reset/goaway/channel close）
   - `stage=request`：请求生命周期错误（如 idle timeout）
2. `gateway_proxy_error_responses{status,connection_policy}`
   - `connection_policy=keep_alive`：错误响应后连接保持
   - `connection_policy=close`：错误响应后连接关闭

当 `Premature close` 或 `Connection reset by peer` 抬升时，优先联动查看：

1. `gateway_proxy_failures` 中 `h2_error` 的增长速度
2. `gateway_proxy_error_responses` 中 `connection_policy=close` 占比
3. `warn.log` 是否出现 `MAX_CONCURRENT_STREAMS 已满`

## 常用参数

通过 `-D参数名=值` 传入：


| 参数             | 默认值                  | 说明                        |
| ---------------- | ----------------------- | --------------------------- |
| `baseUrl`        | `http://localhost:8080` | 网关地址                    |
| `users`          | `50`                    | 开环注入速率（users/sec）   |
| `duration`       | `60`                    | 测试持续秒数                |
| `targetRps`      | `5000`                  | RPS 断言阈值                |
| `warmupDuration` | `30`                    | 预热秒数                    |
| `skipWarmup`     | `false`                 | 跳过预热（数据含 JIT 噪声） |
| `updateBaseline` | `false`                 | 强制覆盖 baseline.json      |
| `clientMaxConnectionsPerHost` | `300`       | Gatling 客户端每主机最大连接数（膝点探测） |
| `requestTimeoutMillis` | `1200`            | 单请求超时毫秒（膝点探测）  |
| `steadyWindowTrimSeconds` | `5`            | 稳态窗口自动统计时剔除末尾秒数（膝点探测） |
| `delayMs`         | `1000`                  | 固定延迟毫秒（`Delay1sKneeSimulation`） |
| `maxFailedPercent` | `30.0`                | 允许失败率上限（`Delay1sKneeSimulation`） |
| `startUsers`      | `200`                   | 并发探测起始注入速率（`Delay1sKneeSimulation`） |
| `stepUsers`       | `200`                   | 每阶段注入增量（`Delay1sKneeSimulation`） |
| `maxSteps`        | `5`                     | 注入阶段数（`Delay1sKneeSimulation`） |
| `stepDurationSeconds` | `15`                | 每阶段持续秒数（`Delay1sKneeSimulation`） |
| `peakHoldSeconds` | `5`                     | 峰值平台持续秒数（`Delay1sKneeSimulation`） |
| `coolDownSeconds` | `5`                     | 回落阶段持续秒数（`Delay1sKneeSimulation`） |
| `coolDownTargetUsers` | `200`              | 回落终点注入速率（`Delay1sKneeSimulation`） |

## 业界参考

1. Gatling 官方 HTTP Protocol 配置（含 `maxConnectionsPerHost`）  
   https://docs.gatling.io/reference/script/http/protocol/
2. Gatling 官方 HTTP Request 配置（含 `requestTimeout`）  
   https://docs.gatling.io/reference/script/http/request/
3. Grafana k6 官方大规模压测指南（强调避免负载发生器先成瓶颈）  
   https://grafana.com/docs/k6/latest/testing-guides/running-large-tests/
