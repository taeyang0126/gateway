# 需求文档

## 简介

基于 Netty 4.2.x 最新版本与 Spring Boot 构建一个 API 网关项目。该网关作为统一入口，接收客户端 HTTP 请求，根据路由规则将请求转发至后端服务，并将响应返回给客户端。

## 术语表

- **Gateway**：网关系统，基于 Netty 构建的 HTTP 反向代理服务
- **Route**：路由规则，定义请求路径与后端服务地址的映射关系
- **Route_Config**：路由配置，存储在 Spring Boot 配置文件中的路由规则集合
- **Upstream**：上游服务，网关转发请求的目标后端服务
- **Client**：发起 HTTP 请求的调用方
- **ProxyHandler**：统一代理处理器，负责所有请求的流式转发（包括普通请求和文件上传）
- **Request_Limit_Config**：请求限制配置，存储在 Spring Boot 配置文件中的请求大小限制和超时参数
- **Connection_Pool_Config**：连接池配置，存储在 Spring Boot 配置文件中的 Upstream 连接池参数
- **Observability_Config**：可观测性配置，存储在 Spring Boot 配置文件中的指标、访问日志和分布式追踪开关参数
- **Access_Log**：结构化访问日志，记录每个代理请求的关键信息（JSON 格式，包含 trace-id 用于追踪关联）
- **Trace_Context**：分布式追踪上下文，遵循 W3C Trace Context 规范的 traceparent/tracestate 头
- **ConcurrentPool**：泛型资源连接池（gateway-pool 模块），负责资源的池化管理

## 需求

### 需求 1：HTTP 请求接收

**用户故事：** 作为调用方，我希望网关能接收 HTTP 请求，以便通过统一入口访问后端服务。

#### 验收标准

1. WHEN Client 发送 HTTP 请求至 Gateway 监听端口, THE Gateway SHALL 接收该请求并解析请求方法、路径、请求头和请求体
2. THE Gateway SHALL 支持 HTTP/1.1 协议
3. WHEN Client 发送的请求格式不合法, THE Gateway SHALL 返回 HTTP 400 状态码及错误描述
4. THE Gateway SHALL 支持 HTTP/1.1 Keep-Alive，在同一连接上处理多个请求
5. WHEN Client 发送 Connection: close 头, THE Gateway SHALL 在响应完成后关闭连接

### 需求 2：路由匹配

**用户故事：** 作为调用方，我希望网关能根据请求路径匹配到正确的后端服务，以便请求被转发到对应的服务。

#### 验收标准

1. WHEN Gateway 接收到请求, THE Gateway SHALL 根据请求路径前缀匹配 Route_Config 中定义的路由规则
2. WHEN 请求路径匹配到 Route, THE Gateway SHALL 确定对应的 Upstream 地址
3. WHEN 请求路径未匹配到任何 Route, THE Gateway SHALL 返回 HTTP 404 状态码及错误描述

### 需求 3：请求转发

**用户故事：** 作为调用方，我希望网关将我的请求转发至后端服务，以便后端服务处理我的业务逻辑。

#### 验收标准

1. WHEN Route 匹配成功, THE Gateway SHALL 以流式方式将请求转发至对应的 Upstream 地址，不将请求体聚合到内存中
2. THE Gateway SHALL 在转发时保留原始请求的方法、请求头和请求体
3. THE Gateway SHALL 以流式方式将 Upstream 的响应状态码、响应头和响应体原样返回给 Client
4. WHEN Upstream 在配置的超时时间内未响应, THE Gateway SHALL 返回 HTTP 504 状态码及错误描述
5. WHEN Gateway 转发请求至 Upstream, THE Gateway SHALL 添加代理请求头：X-Forwarded-For（追加客户端真实 IP，已有则逗号分隔追加）、X-Forwarded-Host（原始 Host 头值）、X-Forwarded-Proto（原始协议 http/https）、X-Real-IP（客户端真实 IP），并将 Host 头修改为 Upstream 的 host

### 需求 4：路由配置

**用户故事：** 作为开发者，我希望通过 Spring Boot 配置文件定义路由规则，以便灵活管理路由映射。

#### 验收标准

1. THE Route_Config SHALL 支持在 Spring Boot application.yml 中定义路由规则
2. THE Route_Config SHALL 包含路由 ID、匹配路径前缀和 Upstream 目标地址三个字段
3. WHEN 应用启动时 Route_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动
4. THE Route_Config SHALL 支持可选的超时时间字段（timeoutSeconds），用于覆盖全局超时配置
5. WHEN Route 配置了 timeoutSeconds, THE Gateway SHALL 使用该值作为该路由的请求超时时间；否则使用全局默认值
6. THE Route_Config SHALL 支持可选的请求大小限制字段（maxRequestSize），用于覆盖全局 Request_Limit_Config 的 maxRequestSize
7. WHEN Route 配置了 maxRequestSize, THE Gateway SHALL 使用该值作为该路由的请求大小限制；否则使用全局默认值

### 需求 5：Netty 服务生命周期管理

**用户故事：** 作为开发者，我希望 Netty 服务的启动和关闭与 Spring Boot 生命周期集成，以便统一管理应用生命周期。

#### 验收标准

1. WHEN Spring Boot 应用启动完成, THE Gateway SHALL 启动 Netty 服务并监听配置的端口
2. WHEN Spring Boot 应用关闭, THE Gateway SHALL 优雅关闭 Netty 服务，等待已有连接处理完毕
3. WHEN Netty 服务启动失败, THE Gateway SHALL 记录错误日志并终止 Spring Boot 应用

### 需求 6：健康检查

**用户故事：** 作为运维人员，我希望能检查网关的运行状态，以便监控服务是否正常。

#### 验收标准

1. WHEN Client 发送 GET 请求至 Gateway 的 /health 路径, THE Gateway SHALL 返回 HTTP 200 状态码及网关运行状态信息
2. THE Gateway SHALL 在 /health 响应中包含网关启动时间和当前活跃连接数

### 需求 7：文件上传代理

**用户故事：** 作为调用方，我希望通过网关上传文件至后端服务，以便在不绕过网关的情况下完成文件上传操作。

#### 验收标准

1. WHEN Client 发送包含请求体的请求（包括文件上传请求）, THE Gateway SHALL 以流式方式逐块转发请求体至 Upstream，不区分请求的 Content-Type
2. THE Gateway SHALL 在流式转发时保留原始请求体的完整内容（包括 multipart 边界符、表单字段和文件内容）
3. WHILE 请求体正在转发, THE Gateway SHALL 以分块读取方式处理，避免将完整请求体加载到内存中
4. THE Gateway SHALL 将 Upstream 对请求的响应状态码、响应头和响应体原样返回给 Client
5. WHEN Client 在请求转发过程中断开连接, THE Gateway SHALL 释放已分配的资源并关闭与 Upstream 的对应连接

### 需求 8：请求大小限制与超时

**用户故事：** 作为开发者，我希望能配置请求体的大小限制和超时时间，以便保护网关和后端服务免受异常大请求或慢速传输的影响。

#### 验收标准

1. THE Request_Limit_Config SHALL 支持在 Spring Boot application.yml 中配置单次请求最大允许大小（默认值 50MB）
2. THE Request_Limit_Config SHALL 支持在 Spring Boot application.yml 中配置请求传输超时时间（默认值 60 秒）
3. WHEN 请求携带 Content-Length 头且其值超过 Request_Limit_Config 中配置的最大允许大小, THE Gateway SHALL 在转发前直接返回 HTTP 413 状态码及错误描述
4. WHEN 请求体传输过程中已传输字节数累计超过 Request_Limit_Config 中配置的最大允许大小, THE Gateway SHALL 终止传输并返回 HTTP 413 状态码及错误描述
5. WHEN 请求传输在 Request_Limit_Config 配置的超时时间内未完成, THE Gateway SHALL 终止传输并返回 HTTP 504 状态码及错误描述
6. WHEN 应用启动时 Request_Limit_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动


### 需求 9：连接池管理

**用户故事：** 作为开发者，我希望网关复用到上游服务的连接，以便减少连接建立开销提升性能。

#### 验收标准

1. THE Gateway SHALL 维护按 Upstream 地址分组的连接池，复用已建立的连接
2. THE Connection_Pool_Config SHALL 支持在 application.yml 中配置每个 Upstream 的最大连接数（默认 50）、空闲连接最大存活时间（默认 60 秒）、慢连接监控阈值 slowConnectThresholdMillis（默认 50ms）和连接建立硬超时 connectTimeoutMillis（默认 500ms）
3. WHEN 连接池中无可用连接且未达到最大连接数, THE Gateway SHALL 建立新连接
4. WHEN 连接池中无可用连接且已达到最大连接数, THE Gateway SHALL 等待可用连接或超时后返回 HTTP 503
5. WHEN 空闲连接超过最大存活时间, THE Gateway SHALL 关闭该连接并从池中移除
6. WHEN Upstream 连接建立耗时超过 slowConnectThresholdMillis, THE Gateway SHALL 在指标和日志中记录该慢连接事件
7. WHEN Upstream 连接建立耗时超过 connectTimeoutMillis, THE Gateway SHALL 终止连接建立并返回 HTTP 502


### 需求 10：可观测性

**用户故事：** 作为运维人员，我希望网关提供指标、结构化访问日志和分布式追踪能力，以便快速定位问题和监控网关运行状况。

#### 验收标准

##### 指标（参考 RED method：Rate、Errors、Duration）

1. THE Gateway SHALL 记录每个代理请求的请求速率（QPS）、错误计数（按 HTTP 状态码分类：4xx、5xx）和响应耗时（P50、P95、P99）
2. THE Gateway SHALL 记录当前活跃连接数
3. THE ConcurrentPool SHALL 暴露每个池实例的可观测性指标：活跃条目数、空闲条目数和总条目数
4. THE Observability_Config SHALL 支持在 application.yml 中配置是否启用指标采集（默认启用）
5. WHEN 指标采集已启用, THE Gateway SHALL 通过 Micrometer 暴露 Prometheus 格式的指标端点
6. THE Gateway SHALL 记录 Upstream 连接建立的耗时分布（Timer）、慢连接计数（超过 slowConnectThresholdMillis 的连接数）和连接失败计数
7. THE Gateway SHALL 记录连接池借用失败计数（连接池已满且等待超时的次数）
8. THE Gateway SHALL 暴露 JVM 指标：堆内存使用量、GC 次数与耗时、线程数，通过 Micrometer MeterBinder 自动配置（JvmMemoryMetrics、JvmGcMetrics、JvmThreadMetrics）
9. THE Gateway SHALL 暴露 Netty 运行时指标：EventLoop pending tasks、ByteBuf allocator 直接内存使用量和堆内存使用量

##### 结构化访问日志（参考 Envoy access log 模式）

10. WHEN 代理请求完成, THE Gateway SHALL 以 JSON 格式输出一条访问日志，包含以下字段：请求方法、请求路径、HTTP 状态码、响应耗时（毫秒）、客户端 IP、Upstream 地址、请求体大小、响应体大小和 trace-id
11. THE Observability_Config SHALL 支持在 application.yml 中配置访问日志级别（默认 WARN），访问日志默认启用
12. THE Observability_Config SHALL 支持在 application.yml 中配置是否启用访问日志（默认启用）

##### 分布式追踪（参考 W3C Trace Context 规范 / OpenTelemetry）

13. WHEN Client 请求携带 W3C traceparent 头, THE Gateway SHALL 解析该头并在转发至 Upstream 时传递 traceparent 和 tracestate 头
14. WHEN Client 请求未携带 traceparent 头, THE Gateway SHALL 生成新的 trace-id 和 span-id，并在转发至 Upstream 时添加 traceparent 头
15. THE Gateway SHALL 在访问日志中包含 trace-id 字段，用于关联日志与追踪数据
16. THE Observability_Config SHALL 支持在 application.yml 中配置是否启用分布式追踪（默认启用）

### 需求 11：代码质量保障

**用户故事：** 作为开发者，我希望项目构建过程中自动执行代码风格检查、静态 bug 检测和测试覆盖率报告，以便持续保障代码质量。

#### 验收标准

1. THE Build SHALL 在 validate 阶段执行 Checkstyle 检查，使用 Google Java Style 规则集，违规时构建失败
2. THE Build SHALL 在 compile 阶段执行 forbidden-apis 检查，禁止使用 JDK 内部不安全 API、平台相关默认编码 API 和已废弃 JDK API，发现违规时构建失败
3. THE Build SHALL 在 test 阶段通过 JaCoCo 收集测试覆盖率数据并生成报告
4. THE Build SHALL 对所有模块（gateway-pool、gateway-core、gateway-example）统一执行上述质量检查
