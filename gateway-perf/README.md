# gateway-perf — Gatling 性能测试

## 前置准备

```bash
# macOS 默认文件描述符限制 256，高并发压测会 Too many open files
# 三个终端都要执行
ulimit -n 65536

mvn install -DskipTests

# 终端1：启动上游服务
mvn spring-boot:run -pl example-upstream -Dspring-boot.run.jvmArguments="-Xms512m -Xmx512m"

# 终端2：启动网关（perf profile：连接池 20、异步日志、关闭控制台输出）
mvn spring-boot:run -pl gateway \
  -Dspring-boot.run.jvmArguments="-Xms1g -Xmx1g" \
  -Dspring-boot.run.arguments="--spring.profiles.active=perf"
```

## 运行测试

### 核心流程（必须按顺序）

```bash
# 第1步：探测膝点（必须先跑，约 5~10 分钟）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.KneeDetectionSimulation

# 第2步：基础代理性能（自动读取 kneeRps，以 70% 负载运行）
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.BaseProxySimulation

# 第3步（可选）：零延迟上游，测网关理论最大 RPS
mvn gatling:test -pl gateway-perf \
  -Dgatling.simulationClass=com.lei.gateway.perf.simulation.MockBaselineSimulation
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

全部跑：`mvn gatling:test -pl gateway-perf`（不指定 simulationClass）。

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
