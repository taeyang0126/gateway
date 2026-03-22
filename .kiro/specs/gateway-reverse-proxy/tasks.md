# 实现计划：Netty Gateway

## 概述

基于 JDK 21、Netty 4.2.x 和 Spring Boot 构建 HTTP 反向代理网关。采用 Maven 多模块结构（gateway-pool、gateway-core、gateway-app、gateway-example），按模块自底向上实现：先完成泛型连接池，再构建网关核心，最后集成可观测性和示例服务。

## 任务

- [x] 1. 搭建 Maven 多模块项目结构与基础配置
  - 创建父 POM（netty-gateway），定义 JDK 21、Netty 4.2.x、Spring Boot、Micrometer、jqwik 等依赖版本管理
  - 创建 gateway-pool 子模块 POM（纯 Java，不依赖 Netty/Spring）
  - 创建 gateway-core 子模块 POM（依赖 gateway-pool、Netty、Spring Boot、Micrometer）
  - 创建 gateway-app 子模块 POM（依赖 gateway-core，包含 Spring Boot Maven Plugin 打可执行 jar）
  - 创建 gateway-example 子模块 POM（Spring Boot Web）
  - 在父 POM 中配置代码质量插件：Checkstyle（Google Java Style，validate 阶段）、SpotBugs（verify 阶段）、JaCoCo（测试覆盖率报告），所有模块统一继承
  - 在 gateway-core 中创建 `application.yml`，包含网关端口、路由规则、请求限制、连接池和可观测性配置
  - 在 gateway-example 中创建 `application.yml`，配置端口 8081 和 multipart 限制
  - _需求: 4.1, 4.2, 8.1, 8.2, 9.2, 10.4, 10.11, 10.12, 10.16, 11.1, 11.2, 11.3, 11.4_

- [x] 2. 实现 gateway-pool 泛型资源连接池模块
  - [x] 2.1 实现 PoolEntry 接口、PoolConfig 和 PoolEntryFactory 接口
    - 定义 `PoolEntry` 接口（STATE_NOT_IN_USE / STATE_IN_USE / STATE_REMOVED，CAS 状态切换，isAlive，close）
    - 定义 `PoolConfig`（maxPoolSize、maxIdleTimeSeconds、connectionTimeoutMillis）
    - 定义 `PoolEntryFactory<T extends PoolEntry>` 工厂接口
    - _需求: 9.1, 9.2_

  - [x] 2.2 实现 ConcurrentPool 核心池化容器
    - 实现三级获取策略：ThreadLocal 快速路径 → CopyOnWriteArrayList CAS 扫描 → SynchronousQueue handoff
    - 实现 `borrow(timeout, unit)`、`requite(entry)`、`remove(entry)`、`close()`
    - requite 时优先通知异步等待者（`LinkedBlockingDeque<PendingBorrow>`，CAS 失败时 `offerFirst` 插回队头保证 FIFO）；无等待者时尝试 handoff，handoff 失败则加回当前线程 ThreadLocal 列表
    - 实现 `getActiveCount()`、`getIdleCount()`、`getTotalCount()` 查询方法（供指标暴露）
    - _需求: 9.1, 9.3, 9.4, 10.3_

  - [x] 2.3 实现 IdleEvictor 空闲条目清理器
    - 定时扫描池中 STATE_NOT_IN_USE 条目，空闲时间超过 maxIdleTimeSeconds 的 CAS 标记为 STATE_REMOVED 并关闭
    - _需求: 9.5_

  - [x] 2.4 编写 ConcurrentPool 单元测试
    - 测试 borrow/requite/remove 基本流程
    - 测试池满时 borrow 超时
    - 测试 borrow 后条目状态为 STATE_IN_USE，requite 后为 STATE_NOT_IN_USE
    - 测试 IdleEvictor：空闲条目被清理、未超时条目不被清理
    - 测试 borrowAsync：池有空闲时立即返回、池空时创建新条目、池满等待归还后完成、超时、池已关闭、等待中池关闭
    - _需求: 9.1, 9.3, 9.4, 9.5_

  - [x] 2.5 编写 gateway-pool 属性测试
    - **Property 8: 连接池新建条目** — 生成随机 PoolConfig 和 mock PoolEntryFactory，验证池未满时 borrow 通过工厂创建新条目并返回（验证: 需求 9.3）
    - **Property 9: 空闲条目清理** — 生成随机池状态和 maxIdleTimeSeconds 配置，验证超时空闲条目被标记为 STATE_REMOVED 并关闭（验证: 需求 9.5）
    - **Property 10: CAS 状态切换并发安全** — 多线程并发执行 borrow/requite，验证同一条目不会被两个线程同时持有（验证: 需求 9.1, 9.3, 9.4）
    - **Property 13: 连接池指标不变量** — 生成随机 borrow/requite/remove 操作序列，验证 activeCount + idleCount == totalCount（验证: 需求 10.3）

- [x] 3. 检查点 - 确保 gateway-pool 模块所有测试通过
  - 确保所有测试通过，有问题请询问用户。

- [x] 4. 实现 gateway-core 配置模型与路由匹配
  - [x] 4.1 实现配置类：GatewayProperties、Route、RequestLimitProperties、ConnectionPoolProperties、ObservabilityProperties
    - `GatewayProperties`（port、routes 列表）绑定 `gateway` 前缀
    - `Route`（id、pathPrefix、upstream、可选 timeoutSeconds、可选 maxRequestSize）
    - `RequestLimitProperties`（maxRequestSize 默认 50MB、timeoutSeconds 默认 60s）绑定 `gateway.request-limit` 前缀
    - `ConnectionPoolProperties`（maxConnectionsPerHost 默认 50、maxIdleTimeSeconds 默认 60、slowConnectThresholdMillis 默认 50、connectTimeoutMillis 默认 500）绑定 `gateway.connection-pool` 前缀
    - `ObservabilityProperties`（metricsEnabled 默认 true、accessLogEnabled 默认 true、accessLogLevel 默认 "WARN"、tracingEnabled 默认 true）绑定 `gateway.observability` 前缀
    - _需求: 4.1, 4.2, 4.4, 4.6, 8.1, 8.2, 9.2, 10.4, 10.11, 10.12, 10.16_

  - [x] 4.2 实现 RouteResolver 路由匹配
    - 根据请求路径前缀匹配路由规则，返回 `Optional<Route>`
    - _需求: 2.1, 2.2, 2.3_

  - [x] 4.3 编写配置与路由单元测试
    - 验证 application.yml 正确绑定到各配置类
    - 验证 ObservabilityProperties 默认值
    - 验证 ConnectionPoolProperties 默认值（connectTimeoutMillis=500、slowConnectThresholdMillis=50）
    - 验证 Route timeoutSeconds 和 maxRequestSize 字段正确加载（含有值和无值两种情况）
    - 验证 RouteResolver 具体路径匹配示例、无匹配路径返回空、多路由最长前缀匹配
    - 验证不合法配置导致启动失败
    - _需求: 2.1, 2.2, 2.3, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 8.6_

- [x] 5. 实现代理请求头与工具类
  - [x] 5.1 实现 ProxyHeaderUtil
    - 添加 X-Forwarded-For（追加客户端 IP，已有则逗号分隔追加）、X-Forwarded-Host、X-Forwarded-Proto、X-Real-IP
    - 修改 Host 头为 Upstream 的 host
    - _需求: 3.5_

  - [x] 5.2 编写 ProxyHeaderUtil 单元测试
    - 测试代理请求头添加的具体示例（含已有 X-Forwarded-For 追加场景）
    - _需求: 3.5_

- [x] 6. 实现可观测性组件
  - [x] 6.1 实现 AccessLogEntry 和 AccessLogWriter
    - `AccessLogEntry` 数据结构（method、path、statusCode、durationMs、clientIp、upstream、requestBodySize、responseBodySize、traceId）
    - `AccessLogWriter` 使用 SLF4J 输出 JSON 格式访问日志，日志级别由配置控制，accessLogEnabled=false 时为空操作
    - _需求: 10.10, 10.11, 10.12_

  - [x] 6.2 实现 TraceContextHandler
    - 解析请求中的 traceparent 头，提取 trace-id 和 span-id，生成新 span-id，存入 Channel Attribute
    - 未携带 traceparent 时生成新的 trace-id（16 字节 hex）和 span-id（8 字节 hex），构造 `00-{trace-id}-{span-id}-01`
    - tracingEnabled=false 时直接传递消息
    - _需求: 10.13, 10.14, 10.15, 10.16_

  - [x] 6.3 实现 MetricsCollector
    - 封装 Micrometer MeterRegistry，注册 RED 指标（gateway.requests.total Counter、gateway.requests.duration Timer、gateway.requests.errors Counter）
    - 注册活跃连接数 Gauge（gateway.connections.active）
    - 注册连接池指标 Gauge（gateway.pool.active/idle/total，按 upstream tag 分组）
    - 注册 upstream 连接指标（gateway.upstream.connect.duration Timer、gateway.upstream.connect.slow Counter、gateway.upstream.connect.failures Counter、gateway.pool.borrow.failures Counter）
    - 注册 JVM 指标（JvmMemoryMetrics、JvmGcMetrics、JvmThreadMetrics）
    - 注册 Netty 运行时指标（netty.eventloop.pending.tasks、netty.allocator.used.direct/heap.memory）
    - 实现 `scrape()` 方法输出 Prometheus 格式文本
    - metricsEnabled=false 时所有 record 方法为空操作
    - _需求: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_

  - [x] 6.4 编写可观测性组件单元测试
    - MetricsCollector：指标采集禁用时 recordRequest 为空操作、启用时 Counter/Timer 正确注册
    - MetricsCollector upstream 连接指标：recordUpstreamConnect 正确记录 Timer 和慢连接 Counter、recordPoolBorrowFailure 正确递增 Counter
    - TraceContextHandler：traceparent 解析具体示例（合法格式、非法格式）、未携带时生成新 traceparent
    - AccessLogWriter：JSON 序列化包含所有字段的具体示例、accessLogEnabled=false 时不输出
    - AccessLogEntry JSON 序列化：验证各字段正确映射到 JSON key
    - _需求: 10.1, 10.6, 10.7, 10.10, 10.13, 10.14, 10.15_

- [x] 7. 检查点 - 确保配置、路由、工具类和可观测性组件测试通过
  - 确保所有测试通过，有问题请询问用户。

- [x] 8. 实现 Upstream 连接池适配与 Netty 服务启动
  - [x] 8.1 实现 ChannelPoolEntry 和 ChannelPoolEntryFactory
    - `ChannelPoolEntry` 将 Netty Channel 适配为 PoolEntry（AtomicIntegerFieldUpdater 做 CAS，isAlive 委托 channel.isActive()，close 委托 channel.close()）
    - `ChannelPoolEntryFactory` 使用 Netty Bootstrap 创建到 upstream 的 TCP 连接
    - _需求: 9.1, 9.3_

  - [x] 8.2 实现 UpstreamConnectionPool
    - 按 host:port 分组，每组一个 `ConcurrentPool<ChannelPoolEntry>`
    - 实现 `acquire(host, port)`（内部调用 borrow，超时返回失败）、`release(channel)`（channel 不 active 则 remove）、`closeAll()`
    - 将 ConnectionPoolProperties 映射到 PoolConfig
    - _需求: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 8.3 实现 GatewayChannelInitializer
    - 配置 Pipeline：HttpServerCodec → IdleStateHandler → TraceContextHandler → RoutingHandler
    - _需求: 1.1, 1.2_

  - [x] 8.4 实现 NettyServerBootstrap
    - 实现 `SmartLifecycle` 接口，Spring 容器就绪后启动 Netty
    - 优雅关闭：先关闭 bossGroup 停止接受新连接，再等待 workerGroup 处理完已有请求
    - 启动失败时终止 Spring Boot 应用
    - 注册活跃连接数 Gauge 到 MetricsCollector
    - 注册 Netty 运行时指标到 MetricsCollector
    - _需求: 5.1, 5.2, 5.3, 10.2, 10.9_

- [x] 9. 实现 RoutingHandler 路由分发
  - 拦截 HttpRequest，提取请求路径
  - `/health` 路径直接返回 JSON 健康信息（status、startTime、activeConnections）
  - `/metrics` 路径（指标采集启用时）委托 MetricsCollector.scrape() 返回 Prometheus 格式文本；未启用时返回 404
  - 路由匹配成功后动态向 Pipeline 添加 ProxyHandler，传递 HttpRequest
  - 未匹配返回 404（JSON 错误响应）
  - Keep-Alive 支持：ProxyHandler 完成后重置状态，准备处理同一连接上的下一个请求
  - Client 发送 `Connection: close` 头时，响应完成后关闭连接
  - _需求: 1.4, 1.5, 2.1, 2.2, 2.3, 6.1, 6.2, 10.5_

  - [x] 9.1 编写健康检查和错误响应单元测试
    - GET /health 返回正确的 JSON 结构
    - 各种错误场景返回正确的状态码和 JSON 格式
    - _需求: 6.1, 6.2_

- [x] 10. 实现 ProxyHandler 全流式转发
  - [x] 10.1 实现 ProxyHandler 请求头处理
    - 收到 HttpRequest 时：记录开始时间、读取 trace-id、Content-Length 预检（路由级别 maxRequestSize 优先于全局）、获取 upstream 连接、添加代理请求头和 traceparent/tracestate、设置 Connection: keep-alive、确定超时时间（路由级别优先）、转发请求头
    - _需求: 3.1, 3.2, 3.5, 4.5, 4.7, 8.3, 10.13, 10.14_

  - [x] 10.2 实现 ProxyHandler 请求体流式转发
    - 收到 HttpContent 时：累计字节数检查，超过 maxRequestSize 返回 413，否则逐块转发
    - 收到 LastHttpContent 时：转发到 Upstream 标记请求体结束
    - _需求: 3.1, 7.1, 7.2, 7.3, 8.4_

  - [x] 10.3 实现 ProxyHandler 响应流式回传
    - 收到 Upstream HttpResponse 时：转发响应头给 Client
    - 收到 Upstream HttpContent 时：逐块转发给 Client
    - 收到 Upstream LastHttpContent 时：转发给 Client、计算耗时、记录指标（MetricsCollector.recordRequest）、输出访问日志（AccessLogWriter.log）、归还连接、从 Pipeline 移除自身
    - _需求: 3.3, 7.4, 10.1, 10.10, 10.15_

  - [x] 10.4 实现 ProxyHandler 异常与资源释放
    - Client 断开连接时释放 ByteBuf 资源并关闭 Upstream 连接（不归还连接池）
    - Upstream 连接失败返回 502、连接池满超时返回 503、Upstream 响应超时返回 504
    - 所有异常通过 exceptionCaught 统一处理，异常日志传递异常对象 `log.error("msg", e)`
    - 统一 JSON 错误响应格式（status、error、message）
    - _需求: 3.4, 7.5, 8.5, 9.4, 9.7_

  - [x] 10.5 编写大小限制预检单元测试
    - Content-Length 超限时直接返回 413
    - _需求: 8.3_

- [x] 11. 检查点 - 确保网关核心转发逻辑测试通过
  - 确保所有测试通过，有问题请询问用户。

- [x] 12. 实现 gateway-example 模块
  - 实现 ExampleController：GET /api/example/hello、POST /api/example/echo、POST /api/example/upload（单文件）、POST /api/example/upload/multi（多文件）、POST /api/example/upload/with-fields（文件+表单字段）、GET /api/example/download（大文件下载）
  - 配置 Spring Boot multipart 限制
  - _需求: 7.1, 7.2_

- [x] 13. 编写集成测试
  - [x] 13.1 编写端到端请求转发集成测试
    - GET/POST 请求转发流程（含代理请求头验证）
    - 路由未匹配返回 404
    - _需求: 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 3.5_

  - [x] 13.2 编写文件上传与大文件下载集成测试
    - 单文件上传、多文件上传、文件+表单字段混合上传流式转发
    - 大文件下载响应流式转发
    - _需求: 7.1, 7.2, 7.3, 7.4_

  - [x] 13.3 编写请求限制与超时集成测试
    - Content-Length 预检超限返回 413
    - 字节累计超限返回 413
    - 路由级别 maxRequestSize 覆盖全局值
    - 超时处理返回 504
    - 路由级别超时覆盖全局超时
    - _需求: 4.5, 4.7, 8.3, 8.4, 8.5_

  - [x] 13.4 编写连接管理集成测试
    - Keep-Alive：同一连接上发送多个请求
    - Connection: close 头触发连接关闭
    - Upstream 连接池复用验证
    - 连接池满时返回 503
    - Client 断开连接时资源释放
    - _需求: 1.4, 1.5, 7.5, 9.1, 9.4_

  - [x] 13.5 编写可观测性集成测试
    - W3C traceparent 传递：Client 携带时 Upstream 收到相同 trace-id，未携带时 Upstream 收到网关生成的 traceparent
    - /metrics 端点返回 Prometheus 格式数据（含 gateway.requests.total、gateway.connections.active、gateway.upstream.connect.duration 等）
    - /metrics 端点包含 JVM 指标和 Netty 指标
    - 访问日志输出验证：请求完成后日志包含所有必要字段
    - 可观测性开关：metricsEnabled=false 时 /metrics 返回 404，tracingEnabled=false 时不添加 traceparent
    - 慢连接监控：upstream 连接耗时超过 slowConnectThresholdMillis 时指标正确记录
    - _需求: 10.1, 10.2, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10, 10.13, 10.14, 10.15_

  - [x] 13.6 编写 Spring Boot 生命周期集成测试
    - 验证 Netty 服务随 Spring Boot 启动和关闭
    - _需求: 5.1, 5.2, 5.3_

- [x] 14. 检查点 - 确保所有单元测试和集成测试通过
  - 确保所有测试通过，有问题请询问用户。

- [ ]* 15. 编写属性测试（Property-Based Testing）
  - [ ]* 15.1 编写路由与配置属性测试
    - **Property 1: 路由匹配正确性** — 生成随机路由配置和请求路径，验证匹配结果的正确性（验证: 需求 2.1, 2.2, 2.3）
    - **Property 7: 路由级别超时优先级** — 生成随机路由配置（有或没有 timeoutSeconds）和随机全局超时值，验证最终使用的超时时间正确（验证: 需求 4.4, 4.5）
    - **Property 16: 路由级别 maxRequestSize 优先级** — 生成随机路由配置（有或没有 maxRequestSize）和随机全局 maxRequestSize 值，验证最终使用的请求大小限制正确（验证: 需求 4.6, 4.7）

  - [ ]* 15.2 编写代理请求头属性测试
    - **Property 4: 代理请求头正确性** — 生成随机客户端 IP、Host 头、协议和可选的已有 X-Forwarded-For 值，验证 ProxyHeaderUtil 输出符合预期（验证: 需求 3.5）

  - [ ]* 15.3 编写可观测性属性测试
    - **Property 11: 请求指标记录正确性** — 生成随机请求结果（方法、路径、状态码、耗时），调用 recordRequest() 后验证 Counter/Timer 值正确（验证: 需求 10.1）
    - **Property 12: 活跃连接数指标正确性** — 生成随机连接建立/断开序列，验证 Gauge 值始终等于当前活跃连接数（验证: 需求 10.2）
    - **Property 14: 访问日志字段完整性** — 生成随机 AccessLogEntry，序列化为 JSON 后验证所有字段存在且值正确（验证: 需求 10.10, 10.15）
    - **Property 15: W3C Trace Context 传播** — 生成随机请求（有或没有 traceparent 头），验证 TraceContextHandler 输出符合传递/生成规则（验证: 需求 10.13, 10.14）

  - [ ]* 15.4 编写网关转发属性测试
    - **Property 2: 请求转发内容保留** — 生成随机 HTTP 请求（含 multipart），通过 mock Upstream 验证转发内容一致（验证: 需求 3.2, 7.1, 7.2）
    - **Property 3: 响应原样返回** — 生成随机 HTTP 响应，验证网关返回内容与 Upstream 响应一致（验证: 需求 3.3, 7.4）
    - **Property 5: 请求大小超限返回 413** — 生成随机大小的请求和随机限制配置，验证超限时返回 413（验证: 需求 8.3, 8.4）
    - **Property 6: Keep-Alive 连接行为** — 生成随机请求序列（含有和不含 Connection: close 头），验证连接保持或关闭行为符合预期（验证: 需求 1.4, 1.5）

- [ ]* 16. 最终检查点 - 确保所有测试（含属性测试）通过
  - 确保所有测试通过，有问题请询问用户。

## 备注

- 标记 `*` 的任务为可选任务（属性测试），MVP 交付后再补充
- 每个任务引用了具体的需求编号，确保需求可追溯
- 检查点任务用于阶段性验证，确保增量开发的正确性
- 属性测试使用 jqwik 库，每个属性至少运行 100 次迭代
- 单元测试使用 JUnit 5 + Mockito
