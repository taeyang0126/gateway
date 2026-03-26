# Implementation Plan: 优雅停机（Graceful Shutdown）

## Overview

基于设计文档，按自底向上的顺序实现：先创建基础组件（配置类、计数器、聚合对象），再实现核心停机/排空/预热逻辑，最后修改现有类完成集成。每个任务增量构建，确保无孤立代码。

## Tasks

- [x] 1. 新增配置类与校验
  - [x] 1.1 创建 `ShutdownProperties` 配置类
    - 在 `gateway-core/.../config/` 下新建 `ShutdownProperties.java`
    - 使用 `@ConfigurationProperties(prefix = "gateway.shutdown")`
    - 字段：`shutdownTimeoutSeconds`（默认 30，`@Min(1)`）、`shutdownPollIntervalMillis`（默认 500）
    - 添加 `@Validated` 注解
    - _Requirements: 1.1, 1.2, 1.3, 1.4_

  - [x] 1.2 创建 `HealthProperties` 配置类
    - 在 `gateway-core/.../config/` 下新建 `HealthProperties.java`
    - 使用 `@ConfigurationProperties(prefix = "gateway.health")`
    - 字段：`startupDelaySeconds`（默认 0，`@Min(0)`）、`warmupTimeoutSeconds`（默认 10，`@Min(1)`）
    - 添加 `@Validated` 注解
    - _Requirements: 8.1, 8.2, 8.3_

  - [x] 1.3 编写 `ShutdownProperties` 和 `HealthProperties` 默认值单元测试
    - 新建 `ShutdownPropertiesTest.java`，验证默认值 30 / 500
    - 新建 `HealthPropertiesTest.java`，验证默认值 0 / 10
    - _Requirements: 1.1, 1.2, 8.1_

  - [x] 1.4 编写配置校验边界属性测试
    - **Property 1: 配置校验边界**
    - 新建 `ShutdownPropertiesPropertyTest.java`（jqwik）
    - 生成随机整数验证 `shutdownTimeoutSeconds < 1` 被拒绝、`>= 1` 通过
    - 生成随机整数验证 `startupDelaySeconds < 0` 被拒绝、`>= 0` 通过
    - **Validates: Requirements 1.4, 8.3**

- [x] 2. 实现 `InFlightRequestTracker`
  - [x] 2.1 创建 `InFlightRequestTracker` 类
    - 在 `gateway-core/.../proxy/` 下新建 `InFlightRequestTracker.java`
    - 基于 `AtomicInteger` 实现 `increment()`、`decrement()`、`getInFlightCount()`
    - `decrement()` 防御性检查：当前值 <= 0 时记录 WARN 日志，不减为负数
    - _Requirements: 2.1, 2.2, 2.3, 2.4_

  - [x] 2.2 编写 `InFlightRequestTracker` 单元测试
    - 新建 `InFlightRequestTrackerTest.java`
    - 测试基本 increment/decrement、边界（decrement 到 0 以下不变为负数）
    - _Requirements: 2.1, 2.2, 2.4_

  - [x] 2.3 编写在途请求计数 round-trip 属性测试
    - **Property 2: 在途请求计数 round-trip**
    - 在 `InFlightRequestTrackerPropertyTest.java` 中实现
    - 生成随机正整数 N，执行 N 次 increment 后 N 次 decrement，验证最终为 0
    - **Validates: Requirements 2.1, 2.2**

  - [x] 2.4 编写在途请求计数并发安全属性测试
    - **Property 3: 在途请求计数并发安全**
    - 在 `InFlightRequestTrackerPropertyTest.java` 中实现
    - 生成随机线程数 M 和每线程操作数 N，并发执行后验证最终为 0
    - **Validates: Requirements 2.3**

- [x] 3. 实现 `DrainHandler`
  - [x] 3.1 创建 `DrainHandler` 类
    - 在 `gateway-core/.../proxy/` 下新建 `DrainHandler.java`
    - `@Sharable` 的 `ChannelInboundHandlerAdapter`
    - `volatile boolean draining` 控制排空状态
    - `activateDrain()` 激活排空
    - `channelRead()`：排空时对 `HttpRequest` 返回 503 + `Connection: close`，非 `HttpRequest` 释放 msg；非排空时透传
    - `exceptionCaught()` 中关闭连接
    - _Requirements: 4.1, 4.2, 4.4_

  - [x] 3.2 编写 `DrainHandler` 单元测试
    - 新建 `DrainHandlerTest.java`
    - 测试排空前后状态切换、非 HttpRequest 消息处理
    - _Requirements: 4.1, 4.4_

  - [x] 3.3 编写排空状态拒绝请求属性测试
    - **Property 4: 排空状态下拒绝所有请求**
    - 新建 `DrainHandlerPropertyTest.java`（jqwik）
    - 生成随机 HTTP method + URI，验证排空时返回 503 + `Connection: close`
    - **Validates: Requirements 4.1, 4.2**

  - [x] 3.4 编写非排空状态透传请求属性测试
    - **Property 5: 非排空状态下透传所有请求**
    - 在 `DrainHandlerPropertyTest.java` 中实现
    - 生成随机 HTTP method + URI，验证非排空时请求被透传
    - **Validates: Requirements 4.4**

- [x] 4. Checkpoint - 基础组件验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. 创建聚合对象并重构构造函数
  - [x] 5.1 创建 `RoutingContext` 聚合对象
    - 在 `gateway-core/.../proxy/` 下新建 `RoutingContext.java`
    - 聚合 `RouteResolver`、`RequestLimitProperties`、`UpstreamConnectionPool`、`MetricsCollector`、`AccessLogWriter`、`ObservabilityProperties`、`GatewaySecurityProcessor`、`InFlightRequestTracker`、`DrainHandler`、`HealthProperties`
    - 全参构造函数 + getter
    - _Requirements: 2.1, 7.1, 7.2_

  - [x] 5.2 创建 `ProxyContext` 聚合对象
    - 在 `gateway-core/.../proxy/` 下新建 `ProxyContext.java`
    - 聚合 `RequestLimitProperties`、`UpstreamConnectionPool`、`MetricsCollector`、`AccessLogWriter`、`ObservabilityProperties`、`InFlightRequestTracker`
    - 全参构造函数 + getter
    - _Requirements: 2.2_

  - [x] 5.3 重构 `RoutingHandler` 构造函数
    - 构造函数改为 `(RoutingContext ctx, AtomicInteger activeConnections, Instant startTime)`
    - 内部从 `RoutingContext` 获取各依赖
    - `channelRead()` 中在 `ctx.fireChannelRead(msg)` 之前调用 `inFlightTracker.increment()`
    - 创建 `ProxyHandler` 时传入 `ProxyContext`
    - _Requirements: 2.1_

  - [x] 5.4 重构 `ProxyHandler` 构造函数
    - 构造函数改为 `(Route route, ProxyContext ctx)`
    - 内部从 `ProxyContext` 获取各依赖
    - `completeRequest()`：`requestCompleted.compareAndSet(false, true)` 成功后调用 `inFlightTracker.decrement()`
    - `channelInactive()`：`requestCompleted.get() == false` 时调用 `decrement()`
    - `sendErrorAndCleanup()`：检查旧值，之前为 false 则调用 `decrement()`
    - _Requirements: 2.2_

- [x] 6. 实现健康检查端点
  - [x] 6.1 在 `RoutingHandler` 中添加健康检查端点
    - 新增 `/health/live` 处理：返回 200 + `{"status":"UP"}`
    - 新增 `/health/ready` 处理：检查 `drainHandler.isDraining()` 和启动延迟（`startTime` + `startupDelaySeconds`）
    - 保留现有 `/health` 端点行为
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 8.4_

  - [x] 6.2 编写健康检查端点单元测试
    - 更新 `RoutingHandlerTest.java`，新增 `/health/live`、`/health/ready` 端点测试
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 6.3 编写 Readiness 端点状态矩阵属性测试
    - **Property 6: Readiness 端点状态矩阵**
    - 新建 `HealthEndpointPropertyTest.java`（jqwik）
    - 枚举 `(isDraining, isWarmupComplete)` 组合，验证响应状态码和 body
    - **Validates: Requirements 7.2, 7.3, 7.4, 8.4**

- [x] 7. Checkpoint - 构造函数重构与健康检查验证
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. 实现 `WarmupRunner` 和 `ShutdownCoordinator`
  - [x] 8.1 创建 `WarmupRunner` 预热执行器
    - 在 `gateway-core/.../proxy/` 下新建 `WarmupRunner.java`
    - 阶段1：内部热路径预热（遍历路由执行 `routeResolver.resolve()`、模拟 JSON 序列化/反序列化）
    - 阶段2：upstream 预热请求（向每个 upstream 发 HTTP HEAD `/`，使用连接池 acquire/release）
    - 整体受 `warmupTimeoutSeconds` 总超时控制，超时放弃剩余预热
    - 预热失败不阻塞启动，记录 WARN 日志
    - _Requirements: 8.1, 8.4_

  - [x] 8.2 编写 `WarmupRunner` 单元测试
    - 新建 `WarmupRunnerTest.java`
    - 测试预热执行、upstream 不可达时不阻塞
    - _Requirements: 8.1_

  - [x] 8.3 创建 `ShutdownCoordinator` 生命周期管理器
    - 在 `gateway-core/.../proxy/` 下新建 `ShutdownCoordinator.java`
    - 实现 `SmartLifecycle`，`getPhase()` 返回 `Integer.MAX_VALUE - 1`
    - `start()`：执行 `warmupRunner.runWarmup()` → `serverBootstrap.start()` → `running = true`
    - `stop(Runnable callback)`：四阶段停机编排（关闭 serverChannel + bossGroup → 激活排空 → 轮询等待在途请求 → 关闭连接池 + workerGroup）
    - 每个阶段 catch-and-continue，`callback.run()` 在 finally 块中
    - 各阶段记录 INFO/WARN 日志
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 5.2, 5.3, 6.1, 6.2, 6.3, 6.4, 6.5_

  - [x] 8.4 编写 `ShutdownCoordinator` 单元测试
    - 新建 `ShutdownCoordinatorTest.java`（mock 依赖）
    - 测试停机阶段顺序、超时行为、在途请求为 0 立即完成
    - _Requirements: 3.4, 3.5, 3.6, 3.7_

- [x] 9. 修改现有类完成集成
  - [x] 9.1 修改 `NettyServerBootstrap`
    - 移除 `implements SmartLifecycle` 及 `isRunning()`/`stop()` 方法
    - 移除 `@Component` 注解
    - `start()` 改为普通 public 方法
    - 新增 `closeServerChannelAndBossGroup()` 方法
    - 构造函数改为接收 `RoutingContext`、`ProxyContext`、`DrainHandler` 及其他必要参数
    - 创建 `RoutingHandler` 时传入 `RoutingContext`
    - _Requirements: 3.2, 5.1_

  - [x] 9.2 修改 `GatewayChannelInitializer`
    - 构造函数新增 `DrainHandler` 参数
    - `initChannel()` 中在 `traceContext` 和 `routing` 之间插入 `drain` handler
    - _Requirements: 4.1_

  - [x] 9.3 修改 `GatewayAutoConfiguration`
    - `workerGroup()` Bean 去掉 `destroyMethod = "shutdownGracefully"`
    - `@EnableConfigurationProperties` 新增 `ShutdownProperties.class` 和 `HealthProperties.class`
    - 注册 `InFlightRequestTracker`、`DrainHandler`、`RoutingContext`、`ProxyContext`、`WarmupRunner`、`NettyServerBootstrap`、`ShutdownCoordinator` 等 Bean
    - _Requirements: 1.3, 5.1, 8.2_

- [x] 10. 编写集成测试
  - [x] 10.1 编写优雅停机集成测试
    - 新建 `GracefulShutdownIntegrationTest.java`
    - 端到端验证：发送请求 → 触发停机 → 验证在途请求完成 → 验证新请求被拒绝（503）
    - _Requirements: 3.1, 3.4, 3.5, 4.1_

- [x] 11. Final checkpoint - 全量验证
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- 所有子任务均为必须执行
- 每个任务引用具体需求编号，确保可追溯
- 属性测试使用 jqwik 1.9.x，测试类命名 `*PropertyTest.java`
- Checkpoint 确保增量验证，避免问题累积
