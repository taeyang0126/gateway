# 实现任务

## 任务列表

- [x] 1. 过滤器链框架
  - [x] 1.1 新增 Filter 接口（含 `getOrder()` 默认方法）、FilterResult 枚举、FilterContext 类（含 CIRCUIT_BREAKER_START_NS 常量）
  - [x] 1.2 新增 WrappingFilter 接口和 ProxyInvoker 函数式接口
  - [x] 1.3 实现 FilterChainHandler（Netty Handler，驱动 pre/post 链，捕获异常返回 HTTP 500；pre 链完成后检测 WrappingFilter 并委托；支持 filterChainTimeoutMs 超时返回 HTTP 504；通过 Channel Attribute FILTER_CONTEXT_KEY 暴露 FilterContext 供 ProxyHandler 读取；post 链在 ProxyHandler 收到首个 HttpResponse 帧时触发）
  - [x] 1.4 实现 FilterChainFactory（根据 Route 配置构建过滤器链，路由级覆盖全局默认；未显式配置顺序时按 getOrder() 升序排列；RetryFilter 单独提取不参与排序）
  - [x] 1.5 新增 FilterProperties 配置类（绑定 `gateway.filters` 前缀，含 filterChainTimeoutMs 字段）
  - [x] 1.6 扩展 Route 类，新增过滤器配置字段（filters 列表及各过滤器 Config 对象）
  - [x] 1.7 在 GatewayAutoConfiguration 中注册 FilterChainFactory Bean，并在 @PostConstruct 中校验过滤器配置（非法配置抛异常终止启动）
  - [x] 1.8a 修改 RoutingHandler，在路由匹配成功后动态插入 FilterChainHandler（在 ProxyHandler 之前）
  - [x] 1.8b 修改 ProxyHandler，在收到 Upstream 首个 HttpResponse 帧时通过 `ctx.pipeline().get(FilterChainHandler.class)` 获取 FilterChainHandler 引用并调用 `onUpstreamResponse(response)`（触发 post 链）；若获取结果为 null 则跳过
  - [x] 1.9 为配置校验逻辑编写单元测试（验证各过滤器非法配置能正确抛出异常终止启动；可扩展现有 InvalidConfigTest）

- [ ] 2. IP 访问控制过滤器
  - [ ] 2.1 新增 IpAccessControlConfig 配置类（mode: ALLOWLIST/DENYLIST，rules: List<String>）
  - [ ] 2.2 实现 IpAccessControlFilter（IP/CIDR 匹配，写入 FilterContext.IP_ACCESS_RESULT，拒绝时返回 HTTP 403）
  - [ ] 2.3 实现 CIDR 匹配工具方法（位运算，不引入第三方库）
  - [ ] 2.4 为 IpAccessControlFilter 编写单元测试（精确 IP、CIDR 网段、白名单/黑名单各场景）
  - [ ] 2.5 为 IpAccessControlFilter 编写 PBT 属性测试（验证 P3：IP 访问控制完备性）

- [ ] 3. 鉴权过滤器
  - [ ] 3.1 新增 AuthConfig 配置类（enabled、type: AuthType 枚举、authServiceUrl、authServiceTimeoutMs、authCacheTtlSeconds）
  - [ ] 3.2 定义 AuthTokenHandler 接口（extractToken、buildAuthRequest），实现 JwtTokenHandler
  - [ ] 3.3 实现 AuthFilter（无 Token → 401，通过 UpstreamConnectionPool 调用 Auth_Service，注入 X-User-Id，回调在 EventLoop 线程；超时通过 ctx.executor().schedule() + ScheduledFuture.cancel() 实现）
  - [ ] 3.4 实现鉴权结果本地缓存（ConcurrentHashMap + CacheEntry，缓存 key 优先用 JWT jti claim，不存在时用 SHA-256 哈希；惰性过期清理；最大条目数 10000，超出时拒绝写入并记录 warn 日志）
  - [ ] 3.5 新增 auth-jwt-example 模块（独立 Spring Boot 应用，POST /token 签发含 jti claim 的 JWT（HS256）、POST /verify 验证签名；在根 pom.xml 的 modules 列表中新增 `<module>auth-jwt-example</module>`；application.yml 顶部须写明 WARNING 注释）
  - [ ] 3.6 为 AuthFilter 编写单元测试（无 Token、401/403/502 各响应场景、缓存命中/过期、jti 缓存 key/SHA-256 回退）
  - [ ] 3.7 为 AuthFilter 编写 PBT 属性测试（验证 P8：鉴权缓存一致性）

- [ ] 4. 限流过滤器
  - [ ] 4.1 新增 RateLimitConfig 配置类（dimensions 列表，含 limit/windowSeconds/burstCapacity；移除 algorithm/buckets 字段）
  - [ ] 4.2 实现 RateLimitEngine（封装令牌桶算法，PreAuth/PostAuth 共享；令牌补充速率 = limit/windowSeconds 个/秒，桶容量 = burstCapacity；TokenBucket 构造函数接受 `LongSupplier clock` 参数，默认 `System::currentTimeMillis`，供 PBT 测试注入固定时钟）
  - [ ] 4.3 实现 PreAuthRateLimitFilter（支持 IP/ROUTE 维度，在 Auth 之前执行）
  - [ ] 4.4 实现 PostAuthRateLimitFilter（支持 USER_ID/IP/ROUTE 维度，在 Auth 之后执行，userId 不存在时跳过 USER_ID 维度）
  - [ ] 4.5 实现 RateLimitFilter 的 pre() 中 429 响应头写入（X-RateLimit-Limit/Remaining/Reset + Retry-After，基于 ConsumeResult）；实现 post() 中放行场景的响应头写入（X-RateLimit-Limit/Remaining/Reset，从 FilterContext 读取 ConsumeResult）
  - [ ] 4.6 为 RateLimitEngine 编写单元测试（令牌桶补充速率、burstCapacity 上限、桶空时拒绝、并发安全）
  - [ ] 4.7 为 PreAuthRateLimitFilter/PostAuthRateLimitFilter 编写单元测试（维度隔离、userId 缺失时跳过）
  - [ ] 4.8 为 RateLimitFilter 编写 PBT 属性测试（验证 P4：令牌桶限流正确性；P5：响应头正确性）

- [ ] 5. 请求/响应头改写过滤器
  - [ ] 5.1 新增 HeaderTransformConfig 配置类（request/response 各含 add/set/remove 规则）
  - [ ] 5.2 实现 HeaderTransformFilter（pre 改写请求头，post 改写响应头，顺序：remove → set → add）
  - [ ] 5.3 为 HeaderTransformFilter 编写单元测试（add 追加、set 覆盖/新增、remove 静默忽略各场景）

- [ ] 6. 重试过滤器
  - [ ] 6.1 新增 RetryConfig 配置类（maxAttempts、retryOnStatus、retryOnConnectFailure、retryDelayMs、retryOnMethods；无退避策略字段）
  - [ ] 6.2 实现 RetryFilter（实现 WrappingFilter 接口，通过 executeWithProxy() 包装 ProxyHandler 调用；固定间隔用 EventLoop schedule，将 ScheduledFuture 存入 FilterContext（key: RETRY_DELAY_FUTURE）供超时取消；注入 X-Retry-Attempt 头；有请求体时不重试；每次重试前重置 FilterContext.CIRCUIT_BREAKER_START_NS；重试不重新执行 pre 链，不额外消耗 HALF_OPEN 的 halfOpenPassedCount）
  - [ ] 6.3 为 RetryFilter 编写单元测试（GET 重试、POST 不重试、有请求体不重试、固定间隔延迟、maxAttempts 耗尽）
  - [ ] 6.4 为 RetryFilter 编写 PBT 属性测试（验证 P7：POST/PATCH 始终不重试；额外验证 PUT/DELETE 在未配置 retryOnMethods 时不重试、配置后可重试）

- [ ] 7. 熔断过滤器
  - [ ] 7.1 新增 CircuitBreakerConfig 配置类（failureRateThreshold、slowCallRateThreshold、slowCallDurationThresholdMs、minimumNumberOfCalls、slidingWindowType/Size、waitDurationInOpenState、permittedCallsInHalfOpenState）
  - [ ] 7.2 实现 CircuitBreaker 状态机（COUNT_BASED/TIME_BASED 滑动窗口；HALF_OPEN 放行计数用 AtomicInteger CAS 控制，超额请求直接返回 503；halfOpenCompletedCount 与 halfOpenPassedCount 分离，避免逻辑歧义）
  - [ ] 7.3 实现 CircuitBreakerFilter（pre 检查状态；post 记录结果含慢调用判断，错误率或慢调用率超阈值均触发 CLOSED→OPEN；状态变更记录日志）
  - [ ] 7.4 为 CircuitBreakerFilter 编写单元测试（CLOSED→OPEN 错误率触发、CLOSED→OPEN 慢调用率触发、OPEN→HALF_OPEN、HALF_OPEN→CLOSED/OPEN 各转换场景，以及 HALF_OPEN 并发超额请求返回 503）
  - [ ] 7.5 为 CircuitBreakerFilter 编写 PBT 属性测试（验证 P6：熔断状态机合法转换）

- [ ] 8. 可观测性集成
  - [ ] 8.1 扩展 AccessLogEntry，新增 ipAccessResult、authResult、rateLimited、retryAttempts、circuitBreakerState 字段
  - [ ] 8.2 在 ProxyHandler.completeRequest() 中，从 Channel Attribute（FILTER_CONTEXT_KEY）读取 FilterContext 并将结果写入 AccessLogEntry 扩展字段（注：此处仅做日志收尾，与任务 1.8b 的 post 链触发是两个独立时机，不要混淆）
  - [ ] 8.3 在各过滤器中注册 Micrometer 指标（Counter/Gauge，参照设计文档指标表，含新增的 `gateway.filter.chain.timeout` 和 `gateway.filter.circuit_breaker.slow_calls`）
  - [ ] 8.4 在 GatewayAutoConfiguration 中初始化过滤器指标 Bean

- [ ] 9. 集成测试
  - [ ] 9.1 编写过滤器链顺序性集成测试（验证 pre 顺序、post 逆序、ABORT 终止链；验证 getOrder() 排序生效）
  - [ ] 9.2 编写 IP 访问控制集成测试（白名单/黑名单 + CIDR）
  - [ ] 9.3 编写鉴权过滤器集成测试（Mock Auth_Service，验证 Token 传递和 X-User-Id 注入；验证 jti 缓存 key 和 SHA-256 回退）
  - [ ] 9.4 编写限流过滤器集成测试（并发请求验证 PreAuth/PostAuth 令牌桶隔离和准确性；验证 burstCapacity 突发允许）
  - [ ] 9.5 编写重试过滤器集成测试（Mock Upstream 返回 502，验证重试次数和固定间隔；验证 WrappingFilter 接口解耦）
  - [ ] 9.6 编写熔断过滤器集成测试（模拟持续失败触发错误率熔断；模拟慢响应触发慢调用率熔断；验证状态转换）
  - [ ] 9.7 编写 FilterContext 隔离性 PBT 测试（验证 P2：并发请求上下文不互串）
  - [ ] 9.8 编写过滤器链超时集成测试（验证 filterChainTimeoutMs 超时返回 HTTP 504）
