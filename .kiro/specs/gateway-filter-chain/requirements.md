# 需求文档

## 简介

在现有 Netty 网关（netty-gateway）的基础上，新增过滤器链（Filter Chain）机制，支持在请求转发前后插入可扩展的处理逻辑。本次迭代实现六个内置过滤器：IP 访问控制（IP Access Control）、鉴权（Authentication）、限流（Rate Limiting）、请求/响应头改写（Header Transformation）、重试（Retry）和熔断（Circuit Breaker）。过滤器链在 RoutingHandler 完成路由匹配后、ProxyHandler 转发请求前执行，各过滤器按配置顺序串行执行。

## 术语表

- **Filter_Chain**：过滤器链，由一组有序 Filter 组成，在请求转发前后依次执行
- **Filter**：过滤器接口，定义请求前置处理（pre）和响应后置处理（post）两个扩展点
- **Auth_Filter**：鉴权过滤器，根据配置的鉴权协议类型将请求 Token 转发至外部鉴权服务进行身份验证
- **Auth_Service**：外部鉴权服务，接收 Token 并返回鉴权结果（含 userId）
- **Auth_Type**：鉴权协议类型，当前支持 JWT（Bearer Token 验证），后续可扩展 OAuth2、OIDC 等
- **Auth_JWT_Example**：用于测试的示例鉴权服务模块（auth-jwt-example），提供 JWT 签发和验证端点
- **PreAuthRateLimit_Filter**：认证前限流过滤器，在鉴权前执行，支持按 IP 和 Route 维度限流，不依赖鉴权结果
- **PostAuthRateLimit_Filter**：认证后限流过滤器，在鉴权后执行，支持按 userId 维度限流，依赖 Auth_Filter 注入的 userId
- **RateLimit_Filter**：限流过滤器的统称，包含 PreAuthRateLimit_Filter 和 PostAuthRateLimit_Filter 两个实例，共享同一套限流算法实现和 RateLimit_Config 配置结构
- **Retry_Filter**：重试过滤器，在 Upstream 返回特定错误或连接失败时自动重试
- **CircuitBreaker_Filter**：熔断过滤器，在 Upstream 持续故障时快速失败，保护后端服务
- **Circuit_State**：熔断器状态，包含 CLOSED（正常）、OPEN（熔断中）、HALF_OPEN（半开探测）三种状态
- **HeaderTransform_Filter**：请求/响应头改写过滤器，支持对转发请求和返回响应的 Header 进行增删操作
- **IpAccessControl_Filter**：IP 访问控制过滤器，基于黑白名单对客户端 IP 进行访问控制
- **IpAccessControl_Config**：IP 访问控制配置，存储在 Spring Boot 配置文件中的黑白名单规则
- **HeaderTransform_Config**：请求/响应头改写配置，存储在 Spring Boot 配置文件中的 Header 增删规则
- **Auth_Config**：鉴权配置，存储在 Spring Boot 配置文件中的鉴权规则（含 Auth_Service 地址）
- **RateLimit_Config**：限流配置，存储在 Spring Boot 配置文件中的限流规则
- **Retry_Config**：重试配置，存储在 Spring Boot 配置文件中的重试规则
- **CircuitBreaker_Config**：熔断配置，存储在 Spring Boot 配置文件中的熔断规则
- **Filter_Config**：过滤器链配置，存储在 Spring Boot 配置文件中的过滤器启用与顺序配置
- **FilterContext**：过滤器请求上下文，每次请求独立的 Map<String, Object>，用于过滤器间传递数据（如 userId、过滤器执行结果等），生命周期与单次请求一致
- **Token**：鉴权令牌，客户端在请求头 Authorization: Bearer <token> 中携带的身份凭证
- **Rate_Window**：限流时间窗口，用于统计请求数量的时间区间
- **Gateway**：网关系统（继承自 netty-gateway 术语表）
- **Route**：路由规则（继承自 netty-gateway 术语表）
- **Upstream**：上游服务（继承自 netty-gateway 术语表）
- **Client**：发起 HTTP 请求的调用方（继承自 netty-gateway 术语表）

## 需求

### 需求 1：过滤器链框架

**用户故事：** 作为开发者，我希望网关提供可扩展的过滤器链机制，以便在不修改核心转发逻辑的情况下插入鉴权、限流等横切关注点。

#### 验收标准

1. THE Filter_Chain SHALL 在路由匹配成功后、请求转发至 Upstream 前，按配置顺序依次执行各 Filter 的前置处理逻辑
2. THE Filter_Chain SHALL 在 Upstream 响应返回后、响应写回 Client 前，按配置逆序依次执行各 Filter 的后置处理逻辑
3. WHEN 任意 Filter 的前置处理拒绝请求, THE Filter_Chain SHALL 终止后续 Filter 执行并直接返回对应错误响应，不转发至 Upstream
4. THE Filter_Config SHALL 支持在 application.yml 中按路由（Route）维度配置启用的过滤器列表及执行顺序
5. THE Filter_Config SHALL 支持在 application.yml 中配置全局默认过滤器列表，作用于所有未单独配置过滤器的路由；WHEN Route 配置了过滤器列表，该列表完全覆盖全局默认过滤器列表，不做合并
6. WHEN Filter 执行过程中抛出未捕获异常, THE Filter_Chain SHALL 返回 HTTP 500 状态码及错误描述，并记录错误日志（含异常对象）
7. THE Filter_Chain SHALL 为每个请求维护一个 FilterContext（Map<String, Object>），各 Filter 可向其中写入和读取数据；FilterContext 的生命周期与单次请求一致，由 Filter_Chain 在请求开始时创建、请求结束时销毁
8. THE Filter 接口 SHALL 提供 `getOrder()` 方法（默认返回 0）；THE FilterChainFactory SHALL 在路由未显式配置过滤器顺序时，按 `getOrder()` 升序排列过滤器；显式配置的顺序优先于 `getOrder()`；各内置过滤器须定义默认 order 常量（IpAccessControl=100、PreAuthRateLimit=200、Auth=300、PostAuthRateLimit=400、HeaderTransform=500、CircuitBreaker=600）；RetryFilter 实现 WrappingFilter 接口，不参与 pre/post 链排序，不定义 order 常量
9. THE Filter_Chain SHALL 支持配置过滤器链总超时（`filterChainTimeoutMs`，默认 0 即不限制）；WHEN filterChainTimeoutMs > 0 且过滤器链执行时间超过该值，THE Filter_Chain SHALL 先写出 HTTP 504 响应再调用 `channel.close()` 释放 Client Channel，同时取消所有正在等待的异步任务（包括 RetryFilter 的延迟 schedule 任务）；WHEN filterChainTimeoutMs > 0 且启动时检测到 `retryDelayMs * (maxAttempts - 1) >= filterChainTimeoutMs`，THE Gateway SHALL 记录 warn 日志提示重试延迟可能导致超时提前触发，不终止启动

### 需求 2：鉴权过滤器

**用户故事：** 作为安全管理员，我希望网关将请求 Token 转发至外部鉴权服务进行身份验证，并将鉴权结果中的用户身份传递给上游服务，以便拒绝未授权的访问且无需在网关内实现鉴权逻辑。

#### 验收标准

1. THE Auth_Config SHALL 支持在 Route 上配置是否启用鉴权（auth.enabled，默认 false）
2. THE Auth_Config SHALL 支持配置鉴权协议类型（auth.type），当前支持 JWT；类型决定 Token 的提取方式和转发给 Auth_Service 的请求格式；设计上须支持后续扩展 OAuth2、OIDC 等类型，不得将协议逻辑硬编码在 Auth_Filter 主流程中
3. THE Auth_Config SHALL 支持在 application.yml 中配置 Auth_Service 的地址（authServiceUrl）；Auth_Service 须在 GatewayProperties 的 routes 中注册为一个独立路由，Auth_Filter 通过 UpstreamConnectionPool 复用连接池调用该路由，不另起独立 HTTP 客户端
4. WHEN Route 启用鉴权且 Client 请求未携带 Authorization 头, THE Auth_Filter SHALL 返回 HTTP 401 状态码及错误描述，不调用 Auth_Service
5. WHEN Route 启用鉴权且 Client 请求携带 Authorization: Bearer <token> 头（JWT 类型）, THE Auth_Filter SHALL 将该 Token 转发至 Auth_Service 进行验证；调用须在 Netty EventLoop 线程上以异步非阻塞方式发起，不阻塞 EventLoop
6. WHEN Auth_Service 返回成功响应（HTTP 200）且响应体包含 userId, THE Auth_Filter SHALL 将 userId 以 X-User-Id 请求头注入转发至 Upstream 的请求中，并将请求传递至 Filter_Chain 的下一个 Filter；Auth_Service 成功响应体格式为 JSON：`{"userId": "<string>"}` — userId 不得为 null 或空字符串，否则视为鉴权失败，返回 HTTP 502，`authResult = "upstream_error"`；WHEN Auth_Service 返回 HTTP 200 但响应体格式不合法（非 JSON、缺少 userId 字段、字段值类型不为字符串），THE Auth_Filter SHALL 同样返回 HTTP 502，`authResult = "upstream_error"`，并记录错误日志（含响应体摘要，不超过 200 字符）
7. WHEN Auth_Service 返回 HTTP 401, THE Auth_Filter SHALL 返回 HTTP 401 状态码及错误描述
8. WHEN Auth_Service 返回 HTTP 403, THE Auth_Filter SHALL 返回 HTTP 403 状态码及错误描述
9. WHEN Auth_Service 连接失败或响应超时, THE Auth_Filter SHALL 返回 HTTP 502 状态码及错误描述，并记录错误日志（含异常对象）
10. THE Auth_Config SHALL 支持在 application.yml 中配置调用 Auth_Service 的超时时间（authServiceTimeoutMs，默认 3000ms）
11. THE Auth_Filter SHALL 在鉴权失败时记录访问日志，包含客户端 IP、请求路径和失败原因
12. THE Auth_Config SHALL 支持配置鉴权结果缓存时间（authCacheTtlSeconds，默认 0 即不缓存）；WHEN authCacheTtlSeconds > 0，相同 Token 在 TTL 内复用上次鉴权结果，不重复调用 Auth_Service；缓存 key 优先使用 JWT 的 `jti` claim，不存在 `jti` 时退回使用 Token 的 SHA-256 哈希（hex 字符串）；缓存使用本地内存，支持并发安全访问
13. THE Gateway SHALL 提供 auth-jwt-example 示例模块，实现 JWT 签发端点（POST /token）和验证端点（POST /verify），用于集成测试；该模块为独立 Spring Boot 应用，不依赖 gateway-core 内部实现

### 需求 3：限流过滤器

**用户故事：** 作为运维人员，我希望网关对请求进行速率控制，以便保护后端服务免受流量突增的冲击，同时支持在鉴权前后分别限流，避免未鉴权请求消耗已鉴权用户的配额。

#### 验收标准

1. THE RateLimit_Filter SHALL 拆分为两个独立过滤器实例：PreAuthRateLimit_Filter（认证前）和 PostAuthRateLimit_Filter（认证后）；两者共享同一套限流算法实现和 RateLimit_Config 配置结构，仅支持的限流维度不同
2. THE PreAuthRateLimit_Filter SHALL 支持按客户端 IP 维度进行限流；THE PreAuthRateLimit_Filter SHALL 支持按路由（Route）维度进行限流；THE PreAuthRateLimit_Filter 不支持 userId 维度（此时 Auth_Filter 尚未执行）
3. THE PostAuthRateLimit_Filter SHALL 支持按 userId 维度进行限流（userId 由 Auth_Filter 注入，仅在鉴权过滤器已启用且鉴权成功时生效）；THE PostAuthRateLimit_Filter 亦可配置 IP 和 Route 维度；WHEN PostAuthRateLimit_Filter 配置了 USER_ID 维度但路由未启用 Auth_Filter 时，THE Gateway SHALL 在启动时记录 warn 日志提示配置不合理（USER_ID 维度将在运行时被跳过）；WHEN 运行时 FilterContext 中不存在 userId（包含以下两种情况：① Auth_Filter 未在该路由启用；② Auth_Filter 已启用但鉴权失败导致 ABORT，此时 PostAuthRateLimit_Filter 的 pre() 不会执行，不存在跳过问题），THE PostAuthRateLimit_Filter SHALL 静默跳过 USER_ID 维度检查，不返回错误
4. THE RateLimit_Config SHALL 支持在 application.yml 中配置每个限流维度的请求数上限（limit）和时间窗口（windowSeconds，单位秒）
5. THE RateLimit_Config SHALL 支持配置突发流量上限（burstCapacity），允许短时间内超过 limit 但不超过 burstCapacity 的请求通过；burstCapacity 默认等于 limit（即不允许突发）
6. WHEN 在 Rate_Window 内某维度的请求数未超过 limit, THE RateLimit_Filter SHALL 将请求传递至 Filter_Chain 的下一个 Filter
7. WHEN 在 Rate_Window 内某维度的请求数超过 limit, THE RateLimit_Filter SHALL 返回 HTTP 429 状态码及错误描述
8. WHEN RateLimit_Filter 返回 HTTP 429, THE RateLimit_Filter SHALL 在响应头中添加 Retry-After 字段，值为令牌桶补充下一个令牌所需的秒数（ceil((1 - tokens) / refillRate) 毫秒转秒）；该响应头须在 pre() 中直接写入 429 响应，不依赖 post()
9. THE RateLimit_Filter SHALL 在响应头中添加 X-RateLimit-Limit（当前维度的 limit 配置值）、X-RateLimit-Remaining（max(0, floor(当前桶中令牌数))）和 X-RateLimit-Reset（令牌数 < 1 时为下一个令牌可用的 Unix 秒时间戳，令牌数 ≥ 1 时为当前时间戳）三个字段；请求被放行时在 post() 中写入，请求被拒绝（429）时在 pre() 中写入
10. THE RateLimit_Filter SHALL 使用令牌桶算法（Token Bucket）实现限流：令牌以固定速率（limit / windowSeconds 个/秒）持续补充，桶容量上限为 burstCapacity；每个请求消耗一个令牌，桶中无令牌时拒绝请求；令牌桶天然支持突发流量平滑，消除固定窗口的边界突刺问题；内存占用为 O(维度数)，与请求量无关
11. THE RateLimit_Filter SHALL 使用本地内存存储令牌桶状态，支持并发安全访问
12. WHEN 应用启动时 RateLimit_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动；合法性规则：`limit` 须 ≥ 1，`windowSeconds` 须 ≥ 1，`burstCapacity`（若显式配置）须 ≥ `limit`

### 需求 4：重试过滤器

**用户故事：** 作为调用方，我希望网关在 Upstream 出现临时故障时自动重试，以便提升请求成功率。

#### 验收标准

1. THE Retry_Config SHALL 支持在 application.yml 中配置最大重试次数（maxAttempts，默认 3，含首次请求）
2. THE Retry_Config SHALL 支持在 application.yml 中配置触发重试的 Upstream 响应状态码列表（retryOnStatus，默认 502、503、504）
3. THE Retry_Config SHALL 支持在 application.yml 中配置触发重试的条件：Upstream 连接失败（retryOnConnectFailure，默认 true）
4. THE Retry_Config SHALL 支持在 application.yml 中配置重试间隔（retryDelayMs，默认 0ms，即立即重试）；不支持退避策略，所有重试使用相同的固定间隔，以避免在网关场景下因累计延迟导致客户端超时
5. WHEN Upstream 返回的响应状态码在 retryOnStatus 列表中, THE Retry_Filter SHALL 在未超过 maxAttempts 时重新发起请求至同一 Upstream
6. WHEN Upstream 连接失败且 retryOnConnectFailure 为 true, THE Retry_Filter SHALL 在未超过 maxAttempts 时重新发起请求至同一 Upstream
7. WHEN 重试次数已达到 maxAttempts, THE Retry_Filter SHALL 将最后一次 Upstream 响应或错误返回给 Client
8. THE Retry_Filter SHALL 按以下优先级判断是否执行重试（优先级从高到低）：① HTTP 方法为 POST 或 PATCH → 始终不重试；② 请求包含请求体（Content-Length > 0 或存在 Transfer-Encoding: chunked）→ 始终不重试，因流式转发不缓存请求体；③ HTTP 方法为 PUT 或 DELETE 且未在 retryOnMethods 中显式配置 → 不重试；④ HTTP 方法为 GET、HEAD、OPTIONS，或 PUT/DELETE 已在 retryOnMethods 中配置 → 允许重试（受 maxAttempts 限制）
9. WHEN Retry_Filter 执行重试, THE Retry_Filter SHALL 在请求头中添加 X-Retry-Attempt 字段，值为当前重试次数（首次请求为 0）
10. THE Retry_Filter SHALL 记录每次重试事件的日志，包含请求路径、重试次数、触发原因（状态码或连接失败）和 Upstream 地址
11. WHEN 应用启动时 Retry_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动；合法性规则：`maxAttempts` 须 ≥ 1，`retryDelayMs` 须 ≥ 0

### 需求 5：熔断过滤器

**用户故事：** 作为运维人员，我希望网关在 Upstream 持续故障时自动熔断，以便快速失败保护后端服务，并在服务恢复后自动恢复流量。

#### 验收标准

1. THE CircuitBreaker_Config SHALL 支持在 application.yml 中按 Route 维度配置熔断规则
2. THE CircuitBreaker_Config SHALL 支持配置触发熔断的错误率阈值（failureRateThreshold，默认 50%，即统计窗口内失败请求占比超过该值时触发熔断）
3. THE CircuitBreaker_Config SHALL 支持配置触发熔断的慢调用率阈值（slowCallRateThreshold，默认 100% 即不启用）和慢调用时间阈值（slowCallDurationThresholdMs，默认 0 即不启用）；WHEN slowCallDurationThresholdMs > 0，响应时间超过该阈值的请求计为慢调用；WHEN 统计窗口内慢调用占比超过 slowCallRateThreshold，THE CircuitBreaker_Filter SHALL 将 Circuit_State 切换为 OPEN
4. THE CircuitBreaker_Config SHALL 支持配置统计窗口内的最小请求数（minimumNumberOfCalls，默认 10，请求数不足时不触发熔断）
5. THE CircuitBreaker_Config SHALL 支持配置统计窗口类型（slidingWindowType，默认 COUNT_BASED）和窗口大小（slidingWindowSize，默认 10，COUNT_BASED 时单位为请求数，TIME_BASED 时单位为秒）
6. THE CircuitBreaker_Config SHALL 支持配置熔断持续时间（waitDurationInOpenState，默认 30 秒，OPEN 状态持续该时间后转为 HALF_OPEN）
7. THE CircuitBreaker_Config SHALL 支持配置 HALF_OPEN 状态下允许通过的探测请求数（permittedCallsInHalfOpenState，默认 3）
8. WHEN Circuit_State 为 CLOSED 且统计窗口内错误率超过 failureRateThreshold, THE CircuitBreaker_Filter SHALL 将 Circuit_State 切换为 OPEN
9. WHEN Circuit_State 为 OPEN, THE CircuitBreaker_Filter SHALL 直接返回 HTTP 503 状态码及错误描述，不转发请求至 Upstream
10. WHEN Circuit_State 为 OPEN 且已持续 waitDurationInOpenState, THE CircuitBreaker_Filter SHALL 将 Circuit_State 切换为 HALF_OPEN
11. WHEN Circuit_State 为 HALF_OPEN, THE CircuitBreaker_Filter SHALL 允许最多 permittedCallsInHalfOpenState 个请求通过至 Upstream；并发到达的超额请求直接返回 HTTP 503，不等待探测结果
12. WHEN Circuit_State 为 HALF_OPEN 且探测请求全部成功, THE CircuitBreaker_Filter SHALL 将 Circuit_State 切换为 CLOSED
13. WHEN Circuit_State 为 HALF_OPEN 且任意探测请求失败, THE CircuitBreaker_Filter SHALL 将 Circuit_State 切换回 OPEN
14. THE CircuitBreaker_Filter SHALL 将 Upstream 返回的 5xx 响应和连接失败计为失败请求；4xx 响应不计为失败
15. THE CircuitBreaker_Filter SHALL 在 Circuit_State 发生变更时记录日志，包含路由 ID、变更前后状态和触发原因
16. WHEN 应用启动时 CircuitBreaker_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动；合法性规则：`failureRateThreshold` 须在 (0, 100]，`slowCallRateThreshold` 须在 (0, 100]，`minimumNumberOfCalls` 须 ≥ 1，`slidingWindowSize` 须 ≥ 1，`waitDurationInOpenState` 须 ≥ 1，`permittedCallsInHalfOpenState` 须 ≥ 1，`slowCallDurationThresholdMs` 须 ≥ 0

### 需求 6：请求/响应头改写过滤器

**用户故事：** 作为开发者，我希望网关能在转发请求和返回响应时对 Header 进行增删改，以便统一注入内部标识、修改特定头的值、删除敏感信息，而无需修改 Upstream 服务。

#### 验收标准

1. THE HeaderTransform_Config SHALL 支持在 application.yml 中按 Route 维度配置请求头改写规则
2. THE HeaderTransform_Config SHALL 支持在 application.yml 中按 Route 维度配置响应头改写规则
3. THE HeaderTransform_Config SHALL 支持配置需要添加的请求头列表（request.add），每项包含 Header 名称和值；若同名 Header 已存在则追加，不覆盖
4. THE HeaderTransform_Config SHALL 支持配置需要覆盖的请求头列表（request.set），每项包含 Header 名称和值；若同名 Header 已存在则覆盖，不存在则新增
5. THE HeaderTransform_Config SHALL 支持配置需要删除的请求头列表（request.remove），每项为 Header 名称；Header 不存在时静默忽略
6. THE HeaderTransform_Config SHALL 支持配置需要添加的响应头列表（response.add），每项包含 Header 名称和值；若同名 Header 已存在则追加，不覆盖
7. THE HeaderTransform_Config SHALL 支持配置需要覆盖的响应头列表（response.set），每项包含 Header 名称和值；若同名 Header 已存在则覆盖，不存在则新增
8. THE HeaderTransform_Config SHALL 支持配置需要删除的响应头列表（response.remove），每项为 Header 名称；Header 不存在时静默忽略
9. THE HeaderTransform_Filter SHALL 在请求头改写完成后将请求传递至 Filter_Chain 的下一个 Filter，在响应头改写完成后将响应返回给 Client
10. WHEN 应用启动时 HeaderTransform_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动；合法性规则：`add`/`set` 列表中每项的 `name` 和 `value` 均不得为 null 或空字符串，`remove` 列表中每项不得为 null 或空字符串

### 需求 7：IP 访问控制过滤器

**用户故事：** 作为安全管理员，我希望网关能基于客户端 IP 进行访问控制，以便快速封禁恶意 IP 或限制只有特定 IP 才能访问敏感路由。

#### 验收标准

1. THE IpAccessControl_Config SHALL 支持在 application.yml 中按 Route 维度配置 IP 访问控制规则
2. THE IpAccessControl_Config SHALL 支持配置白名单模式（allowlist）：仅允许列表中的 IP 访问，其余全部拒绝
3. THE IpAccessControl_Config SHALL 支持配置黑名单模式（denylist）：拒绝列表中的 IP 访问，其余全部允许
4. THE IpAccessControl_Config SHALL 支持 IP 地址（如 192.168.1.1）和 CIDR 网段（如 192.168.1.0/24）两种格式
5. WHEN IpAccessControl_Filter 使用白名单模式且客户端 IP 在白名单中, THE IpAccessControl_Filter SHALL 将请求传递至 Filter_Chain 的下一个 Filter
6. WHEN IpAccessControl_Filter 使用白名单模式且客户端 IP 不在白名单中, THE IpAccessControl_Filter SHALL 返回 HTTP 403 状态码及错误描述
7. WHEN IpAccessControl_Filter 使用黑名单模式且客户端 IP 在黑名单中, THE IpAccessControl_Filter SHALL 返回 HTTP 403 状态码及错误描述
8. WHEN IpAccessControl_Filter 使用黑名单模式且客户端 IP 不在黑名单中, THE IpAccessControl_Filter SHALL 将请求传递至 Filter_Chain 的下一个 Filter
9. THE IpAccessControl_Filter SHALL 按以下优先级确定客户端 IP：优先读取请求中 X-Forwarded-For 头的第一个值（适用于网关前置有代理的场景），若不存在则使用 TCP 连接的远端地址（ChannelHandlerContext.channel().remoteAddress()）；注意 X-Real-IP 由 ProxyHeaderUtil 注入到转发给 Upstream 的请求头中，在过滤器链执行阶段尚不存在，不可使用
10. THE IpAccessControl_Filter SHALL 在拒绝请求时记录日志，包含客户端 IP、请求路径和拒绝原因（黑名单/白名单）
11. WHEN 应用启动时 IpAccessControl_Config 格式不合法, THE Gateway SHALL 记录错误日志并终止启动；合法性规则：`mode` 不得为 null，`rules` 列表不得为 null 或空，每条规则须为合法的 IPv4 地址或 CIDR（格式：`a.b.c.d` 或 `a.b.c.d/n`，其中 n 在 0-32 之间）

### 需求 8：过滤器链可观测性

**用户故事：** 作为运维人员，我希望能监控过滤器链的执行情况，以便快速定位鉴权失败、限流触发和重试异常等问题。

#### 验收标准

1. THE Gateway SHALL 记录 Auth_Filter 的鉴权失败计数（按失败类型分类：无凭证、凭证无效、权限不足）
2. THE Gateway SHALL 记录 RateLimit_Filter 的限流触发计数（按限流维度分类：IP、路由、userId）
3. THE Gateway SHALL 记录 Retry_Filter 的重试总次数、重试成功次数（重试后最终成功）和重试耗尽次数（达到 maxAttempts 仍失败）
4. THE Gateway SHALL 记录 CircuitBreaker_Filter 的熔断触发次数、当前 Circuit_State 和状态变更次数（按路由维度）；THE Gateway SHALL 记录 CircuitBreaker_Filter 的慢调用计数，以便运维区分是错误率触发还是慢调用率触发的熔断
5. THE Gateway SHALL 记录 IpAccessControl_Filter 的拒绝请求计数（按黑名单/白名单模式分类）
6. WHEN 指标采集已启用, THE Gateway SHALL 通过 Micrometer 将上述过滤器指标暴露至 Prometheus 端点
7. THE Gateway SHALL 在访问日志中记录过滤器链的执行结果；过滤器链相关字段扩展到现有 AccessLogEntry 中（新增字段：ipAccessResult、authResult、rateLimited、retryAttempts、circuitBreakerState），不单独输出一条日志；未经过对应过滤器时相关字段为 null，JSON 序列化时忽略 null 字段（@JsonInclude(NON_NULL) 已在现有 AccessLogEntry 上配置）
8. THE Gateway SHALL 记录过滤器链超时计数（filterChainTimeoutMs 触发的 HTTP 504，按路由维度），通过 Micrometer 暴露至 Prometheus 端点
