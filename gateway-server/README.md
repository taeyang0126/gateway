# gateway-server 模块说明

## 1. 模块定位
- 提供阶段 1 的 HTTP 反向代理最小闭环实现。
- 技术栈：Spring Boot（依赖注入 + 外置配置）、Netty（服务端 + 上游客户端）、SLF4J 日志门面。

## 2. 关键设计
- 启动与生命周期：
  - `GatewayBootstrap` 负责 Netty Server 的启动、停止与端口绑定管理。
- 请求处理主链路：
  - `ServerPipelineFactory` 组装 `HttpServerCodec`、`HttpObjectAggregator`、`HttpServerKeepAliveHandler`、`DefaultHttpServerHandler`。
- 路由与转发：
  - `StaticRouteService` 负责静态路由匹配（优先级 + 匹配类型 + 最长前缀）。
  - `DefaultHttpServerHandler` 负责请求转发、健康检查、错误映射与访问日志（直接使用 SLF4J）。
  - 上游连接采用“按路由复用连接 + 单连接串行在途请求”模型，避免每请求建连导致端口耗尽。
- Header 策略：
  - `DefaultHeaderPolicyService` 处理 Hop-by-Hop 移除、`X-Forwarded-*`、`Host rewrite`、`X-Trace-Id`。
- 超时与错误：
  - `DefaultTimeoutPolicy` 提供 connect/read/write 超时策略。
  - `DefaultErrorResponseMapper` 统一错误分类与错误响应格式。

## 3. 代码结构
```text
gateway-server/src/main/java/com/lei/java/gateway/server
├── app
├── bootstrap
├── config
├── http
├── metrics
├── proxy
└── routing
```

## 4. 运行与验证
- 单测：
```bash
./mvnw -pl gateway-server -am test
```
- 打包（可运行 fat-jar）：
```bash
./mvnw -pl gateway-server -am -DskipTests package
java -jar gateway-server/target/gateway-server-2.0.0-SNAPSHOT.jar
```
- 外置配置启动（推荐）：
```bash
java -jar gateway-server/target/gateway-server-2.0.0-SNAPSHOT.jar \
  --spring.config.additional-location=file:./gateway-config.yml
```
- 配置入口：
  - 默认配置：`gateway-server/src/main/resources/application.yml`
  - 支持外置文件覆盖 `gateway.*`（含 routes/upstream/timeout）。
  - 阶段 1 约束：`upstream.scheme` 仅支持 `http`，配置 `https` 会在启动阶段直接失败。
- 管理端点安全：
  - 默认白名单仅允许本机访问（`127.0.0.1` / `::1`）。
  - 可通过 `gateway.management-allowed-client-ips` 配置放行来源 IP（支持 `*` 放开全部，不建议生产使用）。
  - 可通过 `gateway.health-endpoint-enabled` / `gateway.metrics-endpoint-enabled` 开关管理端点。
- 转发保护：
  - `gateway.max-pending-per-route` 控制单路由待转发请求上限，溢出返回 `503 UPSTREAM_BACKLOG_OVERFLOW`。
- 可观测性入口：
  - 健康检查：`GET /health`
  - Prometheus 指标：`GET /metrics/prometheus`
  - 关键指标：
    - `gateway_http_requests_total`
    - `gateway_http_request_duration_seconds`
    - `gateway_http_inflight_requests`
    - `gateway_http_request_bytes` / `gateway_http_response_bytes`
    - `gateway_upstream_requests_total`
    - `gateway_upstream_request_duration_seconds`
    - `gateway_upstream_connect_duration_seconds`
    - `gateway_upstream_inflight_requests`
    - `gateway_upstream_request_bytes` / `gateway_upstream_response_bytes`
    - `gateway_upstream_timeouts_total`
    - `gateway_upstream_connection_errors_total`
- 本地压测（默认产物输出到仓库内 `reports/`）：
```bash
GATLING_HOME=/tmp/gatling-dist/gatling-charts-highcharts-bundle-3.15.0 \
./scripts/perf/stage1/run-stage1-ac4-local.sh \
  --duration-minutes 10 \
  --rps 2000 \
  --body-bytes 1024 \
  --output-dir reports/stage1/ac4-final
```
- 网关压测启动 JVM 默认参数：`-Xms2g -Xmx2g`（可用 `GATEWAY_JVM_XMS`/`GATEWAY_JVM_XMX` 覆盖）。

## 6. 试运行上线检查清单
- 网络边界：禁止公网直接暴露管理端点，至少在入口层限制来源 IP。
- 配置审计：确认 `upstream.scheme=http`，避免误配置导致启动失败。
- 容量保护：根据上游能力设置 `max-pending-per-route`，避免堆积放大。
- 可观测性：确保 `/metrics/prometheus` 可被监控系统采集且非公开暴露。
- 冒烟验证：发布前验证 fat-jar 启动后 `/health` 与 `/metrics/prometheus` 均可访问。

## 5. 压测证据位置
- `reports/stage1/ac4-final/runtime-metrics.csv`
- `reports/stage1/ac4-final/stability-summary.txt`
- `reports/stage1/ac4-final/gatling-report.tgz`
- `reports/stage1/ac4-final/gatling-simulation.log.gz`
- `docs/requirements/evidence/stage1/T1-206-压测与稳定性报告.md`
