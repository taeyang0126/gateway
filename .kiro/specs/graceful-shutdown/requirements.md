# Requirements Document

## Introduction

为 Netty 反向代理网关实现优雅停机（Graceful Shutdown）和 Kubernetes 友好的健康检查机制。

当前 `NettyServerBootstrap.stop()` 在关闭 serverChannel 后立即销毁连接池和 EventLoopGroup，导致正在处理的请求被强制中断。本特性引入分阶段停机流程：停止接受新连接 → 排空（drain）阶段拒绝新请求 → 等待在途请求完成 → 超时后强制关闭。

同时，当前 `/health` 端点仅返回固定 UP 状态，无法区分 liveness 和 readiness，也无法与停机状态联动。本特性引入 K8s 探针模型（`/health/live`、`/health/ready`）加综合状态端点（`/health`），readiness 与 drain 状态自动联动，并支持启动延迟配置用于 JVM 预热。

## Glossary

- **Gateway**: 基于 Netty 的 HTTP 反向代理网关系统，即 `NettyServerBootstrap` 及其管理的 Netty pipeline
- **Shutdown_Coordinator**: 优雅停机协调器，负责编排停机各阶段的执行顺序和超时控制
- **In_Flight_Request_Tracker**: 在途请求追踪器，追踪当前正在处理中（已接收但尚未完成响应）的请求数量
- **Drain_Handler**: 排空阶段处理器，在停机排空阶段拦截新到达的 HTTP 请求并返回 503 + `Connection: close`
- **Shutdown_Properties**: 优雅停机配置属性，包含超时时间、轮询间隔等可配置参数
- **In_Flight_Request**: 已被 RoutingHandler 接收并分发给 ProxyHandler，但尚未完成响应回传给客户端的请求
- **Health_Endpoint**: 健康检查端点，提供 K8s 探针模型（liveness / readiness）及综合状态端点
- **Health_Properties**: 健康检查配置属性，包含启动延迟等可配置参数

## Requirements

### Requirement 1: 停机配置

**User Story:** As a 运维人员, I want to 通过配置文件控制优雅停机的超时时间和行为参数, so that 可以根据不同部署环境调整停机策略。

#### Acceptance Criteria

1. THE Shutdown_Properties SHALL 提供 `shutdown-timeout-seconds` 配置项，默认值为 30 秒，表示优雅停机的最大等待时间
2. THE Shutdown_Properties SHALL 提供 `shutdown-poll-interval-millis` 配置项，默认值为 500 毫秒，表示等待在途请求完成时的轮询间隔
3. THE Shutdown_Properties SHALL 通过 Spring Boot 配置绑定机制绑定到 `gateway` 前缀下
4. IF `shutdown-timeout-seconds` 配置值小于 1，THEN THE Shutdown_Properties SHALL 在 Bean 校验阶段拒绝该值

### Requirement 2: 在途请求追踪

**User Story:** As a 网关系统, I want to 精确追踪当前正在处理中的请求数量, so that 停机时能判断何时所有请求已完成。

#### Acceptance Criteria

1. WHEN RoutingHandler 将请求分发给 ProxyHandler 时，THE In_Flight_Request_Tracker SHALL 将在途请求计数加 1
2. WHEN ProxyHandler 完成请求处理（响应已回传客户端或请求异常终止）时，THE In_Flight_Request_Tracker SHALL 将在途请求计数减 1
3. THE In_Flight_Request_Tracker SHALL 以线程安全方式维护在途请求计数，支持 Netty EventLoop 多线程并发访问
4. THE In_Flight_Request_Tracker SHALL 提供查询当前在途请求数量的方法

### Requirement 3: 停机阶段编排

**User Story:** As a 网关系统, I want to 按照确定的阶段顺序执行停机流程, so that 在途请求能够完成处理而不被中断。

#### Acceptance Criteria

1. WHEN Spring 容器触发 `SmartLifecycle.stop(Runnable callback)` 时，THE Shutdown_Coordinator SHALL 按以下顺序执行停机阶段：关闭 serverChannel + bossGroup → 激活排空模式 → 等待在途请求完成 → 关闭上游连接池 → 关闭 workerGroup
2. WHEN 停机阶段 1（关闭 serverChannel）执行时，THE Gateway SHALL 关闭 serverChannel 以停止接受新的 TCP 连接
3. WHEN 停机阶段 2（激活排空模式）执行时，THE Shutdown_Coordinator SHALL 将 Gateway 标记为排空状态，使后续新请求被 Drain_Handler 拦截
4. WHEN 停机阶段 3（等待在途请求）执行时，THE Shutdown_Coordinator SHALL 以 `shutdown-poll-interval-millis` 为间隔轮询 In_Flight_Request_Tracker，直到在途请求数降为 0 或达到 `shutdown-timeout-seconds` 超时
5. WHEN 在途请求数降为 0 时，THE Shutdown_Coordinator SHALL 立即进入下一阶段，不再继续等待
6. WHEN `shutdown-timeout-seconds` 超时到达且仍有在途请求时，THE Shutdown_Coordinator SHALL 记录警告日志（包含剩余在途请求数量）并强制进入下一阶段
7. THE Shutdown_Coordinator SHALL 在所有阶段完成后调用 `SmartLifecycle.stop(Runnable callback)` 的 callback，通知 Spring 容器停机完成

### Requirement 4: 排空阶段请求拒绝

**User Story:** As a 网关系统, I want to 在排空阶段拒绝新到达的请求, so that 不会有新的请求进入处理流程延长停机时间。

#### Acceptance Criteria

1. WHILE Gateway 处于排空状态，WHEN 新的非健康检查 HTTP 请求到达已有连接时，THE Drain_Handler SHALL 返回 HTTP 503 Service Unavailable 响应
2. WHILE Gateway 处于排空状态，WHEN 新的非健康检查 HTTP 请求到达已有连接时，THE Drain_Handler SHALL 在 503 响应中设置 `Connection: close` 头，指示客户端关闭连接
3. WHILE Gateway 未处于排空状态，THE Drain_Handler SHALL 将请求透传给下游 Handler，不做任何拦截
4. WHILE Gateway 处于排空状态，WHEN 健康检查请求（`/health`、`/health/live`、`/health/ready`）到达时，THE Drain_Handler SHALL 将请求透传给下游 Handler，确保 K8s 探针在排空期间仍可获取状态

### Requirement 5: workerGroup 生命周期统一管理

**User Story:** As a 开发者, I want to workerGroup 的关闭由 Shutdown_Coordinator 统一管理, so that 避免 Spring `destroyMethod` 与 `SmartLifecycle.stop()` 之间的关闭顺序竞争。

#### Acceptance Criteria

1. THE Gateway SHALL 移除 workerGroup Bean 上的 `destroyMethod = "shutdownGracefully"` 声明，由 Shutdown_Coordinator 显式管理 workerGroup 的关闭
2. WHEN 停机阶段 4（关闭上游连接池）完成后，THE Shutdown_Coordinator SHALL 调用 `workerGroup.shutdownGracefully()` 关闭 workerGroup
3. WHEN Shutdown_Coordinator 关闭 workerGroup 时，THE Shutdown_Coordinator SHALL 等待 workerGroup 终止完成后再调用 stop callback

### Requirement 6: 停机过程可观测

**User Story:** As a 运维人员, I want to 通过日志了解停机各阶段的执行情况, so that 可以排查停机异常问题。

#### Acceptance Criteria

1. WHEN 停机流程开始时，THE Shutdown_Coordinator SHALL 记录 INFO 级别日志，包含配置的超时时间
2. WHEN 排空阶段激活时，THE Shutdown_Coordinator SHALL 记录 INFO 级别日志
3. WHEN 在途请求等待完成时，THE Shutdown_Coordinator SHALL 记录 INFO 级别日志，包含当前在途请求数量
4. WHEN 停机超时强制关闭时，THE Shutdown_Coordinator SHALL 记录 WARN 级别日志，包含剩余在途请求数量
5. WHEN 停机流程全部完成时，THE Shutdown_Coordinator SHALL 记录 INFO 级别日志，包含停机总耗时

### Requirement 7: 健康检查端点

**User Story:** As a 运维人员, I want to 通过标准化的健康检查端点了解网关的运行状态, so that K8s 探针和负载均衡器可以正确判断网关是否可用。

#### Acceptance Criteria

1. THE Health_Endpoint SHALL 在 `/health/live` 路径提供 liveness 探针，只要 Netty 服务在运行即返回 HTTP 200 和 `{"status":"UP"}`
2. THE Health_Endpoint SHALL 在 `/health/ready` 路径提供 readiness 探针，当网关已完成启动延迟且未处于排空状态时返回 HTTP 200 和 `{"status":"UP"}`
3. WHEN Gateway 处于排空状态时，THE Health_Endpoint SHALL 在 `/health/ready` 返回 HTTP 503 和 `{"status":"DOWN","reason":"draining"}`
4. WHEN Gateway 尚未完成启动延迟时，THE Health_Endpoint SHALL 在 `/health/ready` 返回 HTTP 503 和 `{"status":"DOWN","reason":"warming_up"}`
5. THE Health_Endpoint SHALL 在 `/health` 路径保留现有综合状态端点，返回 `status`、`startTime`、`activeConnections` 信息

### Requirement 8: 健康检查配置

**User Story:** As a 运维人员, I want to 配置启动延迟时间用于 JVM 预热, so that 网关在 JIT 编译完成前不接收生产流量。

#### Acceptance Criteria

1. THE Health_Properties SHALL 提供 `startup-delay-seconds` 配置项，默认值为 0（不延迟），表示启动后延迟多少秒 readiness 才返回 UP
2. THE Health_Properties SHALL 通过 Spring Boot 配置绑定机制绑定到 `gateway.health` 前缀下
3. IF `startup-delay-seconds` 配置值小于 0，THEN THE Health_Properties SHALL 在 Bean 校验阶段拒绝该值
4. WHEN `startup-delay-seconds` 大于 0 时，THE Gateway SHALL 在 Netty 服务启动后等待指定秒数，期间 readiness 返回 DOWN
