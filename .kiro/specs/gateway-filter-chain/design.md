# 技术设计文档：gateway-filter-chain

## 概述

本文档描述在现有 Netty 网关（netty-gateway）基础上新增过滤器链（Filter Chain）机制的技术设计。

过滤器链在 `RoutingHandler` 完成路由匹配后、`ProxyHandler` 转发请求前执行前置处理，在 `ProxyHandler` 收到 Upstream 响应后、写回 Client 前执行后置处理。本次迭代实现六个内置过滤器：IP 访问控制、鉴权、限流、请求/响应头改写、重试和熔断。

**设计目标：**
- 不侵入 `ProxyHandler` 的流式转发核心逻辑
- 过滤器链在 Netty EventLoop 线程上同步执行前置处理，异步操作（如鉴权 HTTP 调用）通过 `CompletableFuture` 回调回到 EventLoop
- 所有过滤器状态（限流计数器、熔断器）使用本地内存，并发安全

---

## 架构

### 请求处理流程

```
Client
  │
  ▼
HttpServerCodec
  │
  ▼
IdleStateHandler
  │
  ▼
TraceContextHandler
  │
  ▼
RoutingHandler  ──── 路由匹配 ────► 404 Not Found
  │ 匹配成功
  ▼
FilterChainHandler（新增）
  │
  ├─► pre: IpAccessControl → PreAuthRateLimit → Auth → PostAuthRateLimit → HeaderTransform → CircuitBreaker
  │         （任意 Filter 拒绝 → 直接返回错误，不进入 ProxyHandler）
  │
  ▼
ProxyHandler（流式转发至 Upstream）
  │
  ▼
FilterChainHandler（post 回调）
  │  注：post 链由 ProxyHandler 在收到 Upstream 首个 HttpResponse 帧时
  │      回调 FilterChainHandler.onUpstreamResponse() 触发，
  │      此时响应体尚未传输，HeaderTransformFilter 等可安全修改响应头。
  │
  └─► post（逆序）: CircuitBreaker → HeaderTransform → PostAuthRateLimit → Auth → PreAuthRateLimit → IpAccessControl
        （Retry 在 ProxyHandler 层面触发重试循环）
  │
  ▼
Client
```

**post 链触发时机说明（两阶段）：**

post 链分两个阶段触发，不是单一时机：

1. **响应头阶段**（ProxyHandler 收到 Upstream 首个 `HttpResponse` 帧时）：ProxyHandler 调用 `FilterChainHandler.onUpstreamResponse(HttpResponse)`，触发 post 链逆序执行。此时响应体尚未传输，`HeaderTransformFilter`、`CircuitBreakerFilter`、`RateLimitFilter` 等可安全修改响应头。post 链执行完毕后，ProxyHandler 再将修改后的响应头写出给 Client，随后流式转发响应体。

2. **请求完成阶段**（ProxyHandler 的 `completeRequest()` 中）：从 Channel Attribute 读取 `FilterContext`，将过滤器执行结果写入 `AccessLogEntry` 扩展字段。此阶段不再执行 post 链，仅做日志收尾。

**ABORT 场景下 post 链不执行：**

当任意 pre 过滤器返回 ABORT 时，请求未到达 ProxyHandler，`onUpstreamResponse()` 不会被调用，post 链不执行。此时 FilterContext 中已写入的字段（如 `IP_ACCESS_RESULT`、`AUTH_RESULT`）仍会在 `completeRequest()` 中被读取并写入 AccessLogEntry（若 ProxyHandler 因 ABORT 未被调用，则 completeRequest 也不执行，AccessLogEntry 不输出）。

注意：CircuitBreakerFilter.pre() 在 ABORT 场景下可能已将 `CIRCUIT_BREAKER_START_NS` 写入 FilterContext，但 post() 不会执行，该字段不会被消费。这是正常行为，不需要清理。

**重试场景下 post 链的执行次数：**

当 `RetryFilter` 触发重试时，每次 `ProxyInvoker.invoke()` 完成后均会触发一次 post 链（响应头阶段）。**中间重试的响应不会写出给 Client，其 post 链执行结果（如响应头修改）随响应一起被丢弃；只有最后一次（最终）响应的 post 链结果才会写出给 Client。** 这意味着：
- `CircuitBreakerFilter.post()`：每次重试结果均记录到滑动窗口，符合预期（任意一次失败即可触发状态转换）
- `HeaderTransformFilter.post()`：每次重试的响应头均会被改写，但只有最后一次重试的响应才会写出给 Client；中间重试的响应被丢弃，其 post 处理结果也随之丢弃，不会累积
- `RateLimitFilter.post()`：`X-RateLimit-*` 头写入最终响应，重试中间结果被丢弃，不影响最终响应头

### 重试机制特殊说明

`RetryFilter` 不参与标准 pre/post 链，而是包装 `ProxyHandler` 的执行：在 `FilterChainHandler` 执行完所有 pre 过滤器后，由 `RetryFilter` 控制"发起请求 → 判断是否重试 → 再次发起请求"的循环，最终将结果交还给 post 链。

### Pipeline 变化

原有 Pipeline：
```
HttpServerCodec → IdleStateHandler → TraceContextHandler → RoutingHandler → [动态] ProxyHandler
```

新 Pipeline：
```
HttpServerCodec → IdleStateHandler → TraceContextHandler → RoutingHandler → [动态] FilterChainHandler → [动态] ProxyHandler
```

`FilterChainHandler` 由 `RoutingHandler` 在路由匹配成功后动态插入，与 `ProxyHandler` 的插入方式一致。

---

## 组件与接口

### Filter 接口

```java
package com.lei.gateway.core.filter;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * 过滤器接口，定义请求前置处理和响应后置处理两个扩展点。
 *
 * <p>pre() 返回 CompletableFuture<FilterResult>，支持异步操作（如鉴权 HTTP 调用）。
 * post() 为同步操作，在 Netty EventLoop 线程上执行。
 */
public interface Filter {

    /**
     * 过滤器名称，用于日志和配置标识。
     */
    String name();

    /**
     * 过滤器执行优先级，值越小越先执行。
     * 路由显式配置顺序时忽略此值；未配置时按此值升序排列。
     * 内置过滤器默认 order 常量：
     *   IpAccessControl=100, PreAuthRateLimit=200, Auth=300,
     *   PostAuthRateLimit=400, HeaderTransform=500, CircuitBreaker=600
     * 注：RetryFilter 实现 WrappingFilter 接口，不参与 pre/post 链排序，
     *     不定义 order 常量，FilterChainFactory 构建链时将其单独提取。
     */
    default int getOrder() {
        return 0;
    }

    /**
     * 前置处理：在请求转发至 Upstream 前执行。
     *
     * @param ctx     Netty ChannelHandlerContext
     * @param request 原始 HTTP 请求（可修改 headers）
     * @param context 请求级 FilterContext
     * @return CompletableFuture<FilterResult>，CONTINUE 表示继续，ABORT 表示终止并已写出响应
     */
    CompletableFuture<FilterResult> pre(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context);

    /**
     * 后置处理：在 Upstream 响应返回后执行（逆序）。
     * 默认空实现，无需后置处理的过滤器无需覆盖。
     *
     * @param ctx      Netty ChannelHandlerContext
     * @param response Upstream 返回的 HTTP 响应（可修改 headers）
     * @param context  请求级 FilterContext
     */
    default void post(ChannelHandlerContext ctx, HttpResponse response,
            FilterContext context) {
        // 默认空实现
    }
}
```

### FilterResult

```java
package com.lei.gateway.core.filter;

/**
 * 过滤器前置处理结果。
 */
public enum FilterResult {
    /** 继续执行下一个过滤器或转发请求 */
    CONTINUE,
    /** 终止过滤器链，已直接写出错误响应 */
    ABORT
}
```

### FilterContext

```java
package com.lei.gateway.core.filter;

import java.util.HashMap;
import java.util.Map;

/**
 * 请求级过滤器上下文，生命周期与单次请求一致。
 * 线程安全：所有操作均在 Netty EventLoop 线程上执行，无需额外同步。
 */
public class FilterContext {

    private final Map<String, Object> attributes = new HashMap<>();

    public void set(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    public boolean contains(String key) {
        return attributes.containsKey(key);
    }

    /** 常用 key 常量 */
    public static final String USER_ID = "userId";
    public static final String AUTH_RESULT = "authResult";
    public static final String RETRY_ATTEMPT = "retryAttempt";
    public static final String RETRY_DELAY_FUTURE = "retryDelayFuture";  // ScheduledFuture，供超时时取消 RetryFilter 延迟任务
    public static final String CIRCUIT_BREAKER_STATE = "circuitBreakerState";
    public static final String CIRCUIT_BREAKER_START_NS = "circuitBreakerStartNs";  // CircuitBreakerFilter 慢调用计时起点（nanoTime）
    public static final String IP_ACCESS_RESULT = "ipAccessResult";
    public static final String RATE_LIMITED = "rateLimited";
    public static final String RATE_LIMIT_RESULT = "rateLimitResult";  // ConsumeResult，供 post() 写响应头用
}
```

### FilterChainHandler

```java
package com.lei.gateway.core.filter;

/**
 * 过滤器链 Netty Handler，负责：
 * 1. 在 channelRead(HttpRequest) 时依次执行 pre 过滤器
 * 2. 所有 pre 通过后，检测链中是否存在 WrappingFilter（如 RetryFilter），
 *    若存在则委托其控制请求发送循环；否则直接 fireChannelRead 给 ProxyHandler
 * 3. 提供 onUpstreamResponse(HttpResponse) 回调，由 ProxyHandler 在收到
 *    Upstream 首个 HttpResponse 帧时调用，触发 post 链（逆序执行）；
 *    post 链在 HttpResponse 帧阶段执行，此时响应体尚未传输，
 *    HeaderTransformFilter 等可安全修改响应头后再由 ProxyHandler 写出
 * 4. 捕获过滤器未处理异常，返回 HTTP 500
 * 5. 若配置了 filterChainTimeoutMs，在超时后返回 HTTP 504 并释放 Channel
 *    超时时：先写出 HTTP 504 响应，再调用 channel.close()；
 *    同时取消所有正在等待的异步任务（RetryFilter 的 schedule、AuthFilter 的超时任务）
 *    通过 CompletableFuture.complete(TIMEOUT_SENTINEL) 通知异步链短路
 */
public class FilterChainHandler extends ChannelInboundHandlerAdapter {

    /** Channel Attribute key，用于在 FilterChainHandler 和 ProxyHandler 之间共享 FilterContext */
    public static final AttributeKey<FilterContext> FILTER_CONTEXT_KEY =
            AttributeKey.valueOf("filterContext");

    /**
     * 由 ProxyHandler 在收到 Upstream 首个 HttpResponse 帧时调用。
     * 触发 post 链逆序执行；post 链执行完毕后 ProxyHandler 再将响应头写出给 Client。
     * 中间重试的响应（非最终响应）也会触发此方法，但其响应头修改结果随响应丢弃，不写出给 Client。
     *
     * @param response Upstream 返回的 HttpResponse（可被 post 过滤器修改 headers）
     */
    public void onUpstreamResponse(HttpResponse response) { ... }
}
```

**ProxyHandler 获取 FilterChainHandler 引用的方式：**

`ProxyHandler` 通过 `ctx.pipeline().get(FilterChainHandler.class)` 从 Netty Pipeline 中获取 `FilterChainHandler` 实例，调用 `onUpstreamResponse()`。由于 `FilterChainHandler` 在 `RoutingHandler` 中动态插入到 Pipeline，且在 `ProxyHandler` 之前，`pipeline().get()` 可以安全获取到该实例。若获取结果为 null（路由未配置过滤器链），则跳过 post 链调用。

### WrappingFilter

```java
package com.lei.gateway.core.filter;

/**
 * 标记接口：实现此接口的 Filter 可包装 ProxyHandler 的调用，控制请求发送循环。
 * FilterChainHandler 在 pre 链完成后检测链中是否存在 WrappingFilter，
 * 若存在则委托其 executeWithProxy()，不直接 fireChannelRead。
 * 这样 FilterChainHandler 无需感知具体的 RetryFilter 类型。
 */
public interface WrappingFilter extends Filter {
    CompletableFuture<Void> executeWithProxy(ChannelHandlerContext ctx,
            HttpRequest request, FilterContext context, ProxyInvoker proxyInvoker);
}

/** ProxyInvoker 封装对 ProxyHandler 的单次调用，由 FilterChainHandler 提供实现 */
@FunctionalInterface
public interface ProxyInvoker {
    CompletableFuture<HttpResponse> invoke(HttpRequest request);
}
```

### FilterChainFactory

```java
package com.lei.gateway.core.filter;

/**
 * 根据 Route 配置构建过滤器链实例。
 * 优先使用路由级过滤器列表，不存在时使用全局默认列表。
 */
public class FilterChainFactory { ... }
```

**过滤器实例生命周期（重要）：**

有状态过滤器（`CircuitBreakerFilter`、`PreAuthRateLimitFilter`、`PostAuthRateLimitFilter`）的实例必须与 Route 绑定，每个 Route 持有独立的单例实例，不能在每次请求时新建。`FilterChainFactory` 在应用启动时（`GatewayAutoConfiguration.@PostConstruct`）为每个 Route 预构建并缓存过滤器链，后续每次请求直接复用缓存的实例列表。

无状态过滤器（`IpAccessControlFilter`、`AuthFilter`、`HeaderTransformFilter`、`RetryFilter`）同样在启动时构建并缓存，避免重复创建对象。

```java
// FilterChainFactory 内部缓存结构
Map<String /*routeId*/, FilterChain> routeFilterChains = new ConcurrentHashMap<>();

record FilterChain(List<Filter> prePostFilters, WrappingFilter wrappingFilter) {}
```

`FilterChainFactory.getChain(Route route)` 返回缓存的 `FilterChain`，不每次重新构建。

### 内置过滤器

| 类名 | 包 | 说明 |
|---|---|---|
| `IpAccessControlFilter` | `filter.builtin` | IP 黑白名单 |
| `PreAuthRateLimitFilter` | `filter.builtin` | 认证前限流（IP、Route 维度） |
| `AuthFilter` | `filter.builtin` | 外部鉴权服务调用 |
| `PostAuthRateLimitFilter` | `filter.builtin` | 认证后限流（userId 维度，亦可含 IP/Route） |
| `HeaderTransformFilter` | `filter.builtin` | 请求/响应头改写 |
| `RetryFilter` | `filter.builtin` | 重试（包装 ProxyHandler 执行） |
| `CircuitBreakerFilter` | `filter.builtin` | 熔断器 |

---

## 数据模型

### 配置扩展：Route

在现有 `Route` 类中新增过滤器配置字段：

```java
// 路由级过滤器列表（null 表示使用全局默认，空列表表示不启用任何过滤器）
private List<String> filters;

// 各过滤器的路由级配置
private IpAccessControlConfig ipAccessControl;
private AuthConfig auth;
private RateLimitConfig preAuthRateLimit;   // 认证前限流（IP/Route 维度）
private RateLimitConfig postAuthRateLimit;  // 认证后限流（userId 维度）
private HeaderTransformConfig headerTransform;
private RetryConfig retry;
private CircuitBreakerConfig circuitBreaker;
```

### FilterProperties（全局默认过滤器配置）

绑定 `gateway.filters` 前缀：

```java
@ConfigurationProperties(prefix = "gateway.filters")
public class FilterProperties {
    // 过滤器链总超时（毫秒），0 = 不限制
    private long filterChainTimeoutMs = 0;

    // 全局默认过滤器列表（按执行顺序）
    private List<String> defaultFilters = List.of();

    // 全局默认各过滤器配置（路由级配置优先覆盖）
    private IpAccessControlConfig ipAccessControl;
    private AuthConfig auth;
    private RateLimitConfig preAuthRateLimit;
    private RateLimitConfig postAuthRateLimit;
    private HeaderTransformConfig headerTransform;
    private RetryConfig retry;
    private CircuitBreakerConfig circuitBreaker;
}
```

### IpAccessControlConfig

```java
public class IpAccessControlConfig {
    private Mode mode;           // ALLOWLIST | DENYLIST
    private List<String> rules;  // IP 或 CIDR，如 "192.168.1.1", "10.0.0.0/8"

    public enum Mode { ALLOWLIST, DENYLIST }
}
```

### AuthConfig

```java
public class AuthConfig {
    private boolean enabled = false;
    private AuthType type = AuthType.JWT;  // 鉴权协议类型，当前支持 JWT
    private String authServiceUrl;         // Auth_Service 在 routes 中注册的路由 ID
    private int authServiceTimeoutMs = 3000;
    private int authCacheTtlSeconds = 0;   // 0 = 不缓存

    public enum AuthType { JWT }           // 预留扩展点：后续可增加 OAUTH2、OIDC
}
```

**Auth_Service 调用方式：** Auth_Service 须在 `GatewayProperties.routes` 中注册为独立路由（如 `id: auth-service`）。`AuthFilter` 通过 `UpstreamConnectionPool` 获取连接，复用网关现有连接池，不另起 `java.net.http.HttpClient`。异步回调通过 `ctx.executor().execute()` 切回 EventLoop 线程。

**AuthTokenHandler 接口（扩展点）：**
```java
// 不同鉴权协议对应不同的 TokenHandler 实现
public interface AuthTokenHandler {
    // 从请求中提取 Token（如 Bearer Token）
    Optional<String> extractToken(HttpRequest request);
    // 构造发往 Auth_Service 的请求
    HttpRequest buildAuthRequest(String token, String authServiceUrl);
}
// JwtTokenHandler 实现 JWT Bearer Token 提取和转发
```

### RateLimitConfig

```java
public class RateLimitConfig {
    private List<DimensionConfig> dimensions = List.of();

    public static class DimensionConfig {
        private Dimension dimension;   // IP | ROUTE | USER_ID
        private int limit;             // 令牌补充速率基准（limit 个/windowSeconds 秒）
        private int windowSeconds;     // 令牌补充周期（秒）
        private int burstCapacity = -1; // 桶容量上限，-1 表示等于 limit（不允许突发）

        public enum Dimension { IP, ROUTE, USER_ID }
    }
}
```

**令牌桶算法说明：** 令牌以 `limit / windowSeconds` 个/秒的速率持续补充，桶容量上限为 `burstCapacity`。每个请求消耗一个令牌，桶中无令牌时拒绝请求并返回 HTTP 429。令牌桶天然支持突发流量平滑，内存占用 O(维度数)，与请求量无关。

### RetryConfig

```java
public class RetryConfig {
    private int maxAttempts = 3;
    private List<Integer> retryOnStatus = List.of(502, 503, 504);
    private boolean retryOnConnectFailure = true;
    private int retryDelayMs = 0;          // 固定间隔，不支持退避策略
    private List<String> retryOnMethods = List.of(); // 额外允许重试的方法（PUT/DELETE）
    // 注：不提供 backoffMultiplier，网关场景下退避会导致客户端超时
}
```

### CircuitBreakerConfig

```java
public class CircuitBreakerConfig {
    private double failureRateThreshold = 50.0;
    private double slowCallRateThreshold = 100.0;  // 100% = 不启用慢调用熔断
    private int slowCallDurationThresholdMs = 0;   // 0 = 不启用慢调用检测
    private int minimumNumberOfCalls = 10;
    private SlidingWindowType slidingWindowType = SlidingWindowType.COUNT_BASED;
    private int slidingWindowSize = 10;
    private int waitDurationInOpenState = 30;  // 秒
    private int permittedCallsInHalfOpenState = 3;

    public enum SlidingWindowType { COUNT_BASED, TIME_BASED }
}
```

### HeaderTransformConfig

```java
public class HeaderTransformConfig {
    private HeaderRules request = new HeaderRules();
    private HeaderRules response = new HeaderRules();

    public static class HeaderRules {
        private List<HeaderEntry> add = List.of();    // 追加（不覆盖）
        private List<HeaderEntry> set = List.of();    // 覆盖（不存在则新增）
        private List<String> remove = List.of();      // 删除（不存在静默忽略）
    }

    public static class HeaderEntry {
        private String name;
        private String value;
    }
}
```

### AccessLogEntry 扩展

在现有 `AccessLogEntry` 中新增字段（`@JsonInclude(NON_NULL)` 已配置，null 字段不输出）：

```java
@JsonProperty("ipAccessResult")
private String ipAccessResult;   // "allowed" | "denied"

@JsonProperty("authResult")
private String authResult;       // "passed" | "no_token" | "invalid" | "forbidden" | "upstream_error"

@JsonProperty("rateLimited")
private Boolean rateLimited;     // true = 被限流

@JsonProperty("retryAttempts")
private Integer retryAttempts;   // 实际重试次数（0 = 未重试）

@JsonProperty("circuitBreakerState")
private String circuitBreakerState;  // "CLOSED" | "OPEN" | "HALF_OPEN"
```

### application.yml 配置示例

```yaml
gateway:
  filters:
    filter-chain-timeout-ms: 5000   # 过滤器链总超时，0 = 不限制
    default-filters:
      - ip-access-control
      - pre-auth-rate-limit
      - auth
      - post-auth-rate-limit
      - header-transform
      - circuit-breaker
      - retry
    auth:
      auth-service-url: auth-service   # routes 中注册的路由 ID
      auth-service-timeout-ms: 3000
      auth-cache-ttl-seconds: 60
    pre-auth-rate-limit:
      dimensions:
        - dimension: IP
          limit: 100
          window-seconds: 60
          burst-capacity: 150
        - dimension: ROUTE
          limit: 1000
          window-seconds: 60
    post-auth-rate-limit:
      dimensions:
        - dimension: USER_ID
          limit: 50
          window-seconds: 60
    circuit-breaker:
      failure-rate-threshold: 50
      slow-call-rate-threshold: 80
      slow-call-duration-threshold-ms: 2000
      minimum-number-of-calls: 10
      sliding-window-size: 10
      wait-duration-in-open-state: 30
    retry:
      max-attempts: 3
      retry-on-status: [502, 503, 504]
      retry-delay-ms: 0

  routes:
    - id: auth-service              # Auth_Service 注册为普通路由
      path-prefix: /auth/**
      upstream: http://auth-svc:8080
    - id: api-route
      path-prefix: /api/**
      upstream: http://backend:8080
      filters:
        - ip-access-control
        - pre-auth-rate-limit
        - auth
        - post-auth-rate-limit
      ip-access-control:
        mode: ALLOWLIST
        rules:
          - 10.0.0.0/8
          - 192.168.1.100
      auth:
        enabled: true
        type: JWT
      pre-auth-rate-limit:
        dimensions:
          - dimension: IP
            limit: 200
            window-seconds: 60
      post-auth-rate-limit:
        dimensions:
          - dimension: USER_ID
            limit: 50
            window-seconds: 60
```

---

## 正确性属性

*属性（Property）是在系统所有合法执行中都应成立的特征或行为——本质上是对系统应做什么的形式化陈述。属性是人类可读规范与机器可验证正确性保证之间的桥梁。*


### 属性列表

#### P1：过滤器链顺序性
对任意请求，pre 过滤器按配置顺序执行，post 过滤器按配置逆序执行。若第 i 个 pre 过滤器返回 ABORT，则第 i+1 及之后的 pre 过滤器不执行，ProxyHandler 不被调用，post 链不执行。

#### P2：FilterContext 隔离性
对任意两个并发请求 R1、R2，R1 的 FilterContext 中写入的数据不会出现在 R2 的 FilterContext 中。

#### P3：IP 访问控制完备性
对任意客户端 IP 和任意 IpAccessControlConfig：
- ALLOWLIST 模式：IP 匹配规则列表中任意条目 → CONTINUE；否则 → HTTP 403
- DENYLIST 模式：IP 匹配规则列表中任意条目 → HTTP 403；否则 → CONTINUE
- CIDR 匹配：属于该网段的所有 IP 与精确 IP 等价处理

#### P4：令牌桶限流正确性
对令牌桶算法，在桶中令牌数为 0 时，任意新请求均被拒绝（HTTP 429），直到下一个令牌补充周期到来；在桶中令牌数 > 0 时，请求被允许通过且令牌数原子递减 1。burstCapacity 为桶容量上限，令牌数不超过 burstCapacity。

#### P5：限流响应头正确性
对任意请求，X-RateLimit-Remaining = max(0, floor(当前桶中令牌数))。X-RateLimit-Reset 的计算方式为：当令牌数 < 1 时，Reset = ceil((1 - tokens) / refillRate) 毫秒后转换为 Unix 秒；当令牌数 ≥ 1 时，Reset = 当前时间戳（Unix 秒）。`TokenBucket` 通过构造函数接受 `LongSupplier clock`（默认 `System::currentTimeMillis`），PBT 测试时注入固定时钟，直接断言上述计算逻辑，不依赖时间误差范围。

#### P6：熔断状态机合法转换
Circuit_State 的合法转换仅为：CLOSED → OPEN、OPEN → HALF_OPEN、HALF_OPEN → CLOSED、HALF_OPEN → OPEN。不存在其他状态转换路径。错误率超阈值和慢调用率超阈值均可触发 CLOSED → OPEN 转换。

#### P7：重试幂等性约束
对 POST、PATCH 方法，无论 Upstream 返回何种状态码或连接失败，Retry_Filter 均不执行重试（retryAttempts 始终为 0）。

#### P8：鉴权缓存一致性
当 authCacheTtlSeconds > 0 时，对同一 Token 在 TTL 内的多次请求，Auth_Filter 返回的 userId 与首次调用 Auth_Service 返回的 userId 相同。TTL 过期后，下一次请求重新调用 Auth_Service。缓存 key 优先使用 JWT `jti` claim，不存在时使用 Token 的 SHA-256 哈希。

#### P9：异常隔离性
当任意 Filter 的 pre() 方法抛出未捕获异常时，FilterChainHandler 返回 HTTP 500，不影响其他并发请求的处理。

#### P10：PreAuth/PostAuth 限流维度隔离性
PreAuthRateLimitFilter 的计数器与 PostAuthRateLimitFilter 的计数器相互独立，同一请求触发 PreAuth 限流不影响 PostAuth 计数器，反之亦然。PostAuthRateLimitFilter 在 FilterContext 中不存在 userId 时，跳过 USER_ID 维度检查，不返回错误。

---

## 详细实现设计

### IpAccessControlFilter

**核心逻辑：**
1. 从请求头读取 `X-Forwarded-For`，取第一个值；不存在则取 `ctx.channel().remoteAddress()`
2. 将 IP 字符串解析为 `InetAddress`
3. 遍历 `IpAccessControlConfig.rules`，对每条规则：
   - 若为精确 IP（不含 `/`）：直接比较字符串
   - 若为 CIDR（含 `/`）：计算网络地址和掩码，判断 IP 是否在网段内
4. 根据 mode 和匹配结果决定 CONTINUE 或返回 HTTP 403
5. 写入 `FilterContext.IP_ACCESS_RESULT`（"allowed" 或 "denied"）
6. 拒绝时记录日志（IP、路径、原因）并更新 Micrometer 计数器

**CIDR 匹配实现：** 使用位运算，无需引入第三方库：
```java
// 示例：判断 ip 是否属于 cidr（如 "10.0.0.0/8"）
byte[] ipBytes = InetAddress.getByName(ip).getAddress();
String[] parts = cidr.split("/");
byte[] networkBytes = InetAddress.getByName(parts[0]).getAddress();
int prefixLen = Integer.parseInt(parts[1]);
// 比较前 prefixLen 位
```

**不实现：** IPv6 支持（需求未要求）。

---

### AuthFilter

**核心逻辑：**
1. 检查请求头 `Authorization`，不存在则返回 HTTP 401，写入 `authResult = "no_token"`
2. 委托 `AuthTokenHandler`（根据 `AuthConfig.type` 选择实现）提取 Token
3. 若 `authCacheTtlSeconds > 0`，计算缓存 key（优先取 JWT `jti` claim，不存在则 SHA-256 哈希 Token），查本地缓存（`ConcurrentHashMap<String, CacheEntry>`）：
   - 命中且未过期：取 userId，跳至步骤 6
   - 未命中或已过期：继续步骤 4
4. 通过 `UpstreamConnectionPool` 获取连接，向 Auth_Service 发起异步请求（复用网关连接池，不另起 HttpClient）
5. 处理响应：
   - HTTP 200 + userId：写入缓存（若启用），继续步骤 6
   - HTTP 401：返回 HTTP 401，`authResult = "invalid"`
   - HTTP 403：返回 HTTP 403，`authResult = "forbidden"`
   - 连接失败/超时：返回 HTTP 502，`authResult = "upstream_error"`，`log.error("Auth service call failed", e)`
6. 将 `X-User-Id: {userId}` 注入请求头，写入 `FilterContext.USER_ID`，`authResult = "passed"`，返回 CONTINUE

**缓存 key 计算：**
```java
// 优先使用 JWT jti claim（无需解码签名，仅 Base64 解码 payload）
String cacheKey = extractJti(token).orElseGet(() -> sha256Hex(token));

// jti 提取：Base64 解码 JWT payload 部分，取 "jti" 字段
private Optional<String> extractJti(String token) {
    try {
        String[] parts = token.split("\\.");
        if (parts.length < 2) return Optional.empty();
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
        // 简单 JSON 解析取 jti，无需引入 JSON 库
        // 使用正则或字符串查找即可，jti 值通常为 UUID
        return Optional.ofNullable(extractJsonField(payload, "jti"));
    } catch (Exception e) {
        return Optional.empty();
    }
}
```

**Auth_Service 超时实现：** `AuthFilter` 在发起异步请求后，通过 `ctx.executor().schedule()` 注册一个超时任务（延迟 `authServiceTimeoutMs` 毫秒）。若超时任务触发时 `CompletableFuture` 尚未完成，则 complete 该 Future 并返回 HTTP 502，`authResult = "upstream_error"`，同时关闭 upstream channel 避免泄漏。若响应先到达，则取消超时任务（`ScheduledFuture.cancel(false)`）。

**异步回调回 EventLoop：**
```java
// UpstreamConnectionPool 回调在 Netty EventLoop 线程，无需额外切换
pool.acquire(authServiceRoute).addListener(future -> {
    // 已在 EventLoop 线程
    Channel ch = (Channel) future.getNow();
    // 发送请求，在 channelRead 回调中处理响应
});
```

**Auth_Service 响应格式：** Auth_Service 的 POST /verify 端点须返回 JSON 格式响应体：
- 成功（HTTP 200）：`{"userId": "<string>"}` — `userId` 字段为字符串类型，不得为 null 或空字符串
- 失败（HTTP 401/403）：响应体可为空或任意格式，AuthFilter 不解析失败响应体
- `AuthFilter` 使用简单字符串查找解析 `userId` 字段（无需引入 JSON 库），与 `extractJti` 的实现方式一致

**AuthTokenHandler 接口：**
```java
public interface AuthTokenHandler {
    Optional<String> extractToken(HttpRequest request);
    FullHttpRequest buildAuthRequest(String token, String authServiceUrl);
}
// JwtTokenHandler：提取 Authorization: Bearer <token>，POST /verify {"token":"..."}
```

**缓存结构：**
```java
record CacheEntry(String userId, long expireAt) {}
ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
```
缓存清理：惰性清理（访问时检查 expireAt），同时限制缓存最大条目数为 10000；超出时拒绝写入新条目（不淘汰旧条目，避免引入 LRU 锁竞争），并记录 warn 日志。不启动后台清理线程。

---

### RateLimitFilter

`RateLimitFilter` 的算法实现由 `RateLimitEngine` 单独封装，`PreAuthRateLimitFilter` 和 `PostAuthRateLimitFilter` 共享同一个 `RateLimitEngine` 实例，区别仅在于支持的维度不同。

**PreAuthRateLimitFilter**：支持 IP、ROUTE 维度，在 Auth 之前执行，不读取 FilterContext.USER_ID。

**PostAuthRateLimitFilter**：支持 USER_ID、IP、ROUTE 维度，在 Auth 之后执行，从 FilterContext.USER_ID 读取 userId；若 userId 不存在（Auth 未启用或鉴权失败），跳过 USER_ID 维度检查。

**令牌桶实现：**

```java
class TokenBucket {
    final long capacity;           // burstCapacity，桶容量上限
    final double refillRate;       // 令牌补充速率（个/毫秒）= limit / (windowSeconds * 1000)
    double tokens;                 // 当前令牌数（double 支持亚毫秒精度补充）
    long lastRefillTime;           // 上次补充时间戳（毫秒）
    final LongSupplier clock;      // 时钟函数，默认 System::currentTimeMillis，PBT 测试时注入固定时钟
    // 所有字段通过 synchronized(this) 保护
}
```

消费逻辑：
1. 计算距上次补充的时间差，按 `refillRate` 补充令牌，不超过 `capacity`
2. 若当前令牌数 ≥ 1，令牌数 -1，返回允许
3. 否则返回拒绝，Retry-After = `ceil(1 / refillRate)` 毫秒转秒

**多维度处理：** 按配置顺序依次检查各维度，任意维度超限则返回 HTTP 429。

**响应头写入时机（重要）：**

`X-RateLimit-Limit`、`X-RateLimit-Remaining`、`X-RateLimit-Reset` 三个响应头分两种情况写入：

- **请求被放行（令牌充足）**：在 post() 中写入，此时可以拿到最终响应对象。
- **请求被拒绝（HTTP 429，pre() 返回 ABORT）**：post 链不执行，因此这三个响应头必须在 pre() 中直接写入 429 响应，不能依赖 post()。`Retry-After` 头同样在 pre() 的 429 响应中写入。

实现时，`TokenBucket.tryConsume()` 返回一个结果对象，包含是否允许、当前剩余令牌数和 reset 时间戳，供 pre() 和 post() 共用：

```java
record ConsumeResult(boolean allowed, int remaining, long resetEpochSeconds, long retryAfterSeconds) {}
```

pre() 将 `ConsumeResult` 写入 `FilterContext`（key: `RATE_LIMIT_RESULT`），post() 从 FilterContext 读取并写入响应头。429 场景下 pre() 直接用 `ConsumeResult` 构造 429 响应并写入响应头。

---

### HeaderTransformFilter

**pre() 处理请求头：**
1. 执行 `request.remove` 列表：`headers.remove(name)`（不存在静默忽略）
2. 执行 `request.set` 列表：`headers.set(name, value)`（覆盖或新增）
3. 执行 `request.add` 列表：`headers.add(name, value)`（追加）

**post() 处理响应头：**
1. 执行 `response.remove` 列表
2. 执行 `response.set` 列表
3. 执行 `response.add` 列表

操作顺序：remove → set → add，确保语义清晰（先删后改后增）。

---

**filterChainTimeoutMs 与 RetryFilter 的交互：**

`filterChainTimeoutMs` 的计时从 `FilterChainHandler.channelRead(HttpRequest)` 开始，覆盖整个过滤器链执行周期（包含 pre 链、RetryFilter 的重试循环、post 链）。

超时触发时的处理：
- 若 pre 链仍在执行（如 AuthFilter 等待 Auth_Service 响应），通过 `CompletableFuture.complete(TIMEOUT_SENTINEL)` 通知异步链短路，写出 HTTP 504，调用 `channel.close()`
- 若 RetryFilter 正在等待 `retryDelayMs` 延迟：RetryFilter 在调度 schedule 任务时，将返回的 `ScheduledFuture` 存入 `FilterContext`（key: `RETRY_DELAY_FUTURE`）；超时触发时 `FilterChainHandler` 从 `FilterContext` 读取该 Future 并调用 `cancel(false)`，随后写出 HTTP 504，调用 `channel.close()`
- 若 ProxyHandler 正在等待 Upstream 响应，关闭 upstream Channel（触发连接失败回调），写出 HTTP 504，调用 client `channel.close()`

超时计时器通过 `ctx.executor().schedule()` 实现，返回 `ScheduledFuture`。超时触发时通过 `CompletableFuture.complete(TIMEOUT_SENTINEL)` 通知正在等待的异步链，由链的 `thenApply` 检测哨兵值并短路后续处理。正常完成时调用 `timeoutFuture.cancel(false)` 取消计时器。

```java
// FilterChainHandler 中
ScheduledFuture<?> timeoutTask = ctx.executor().schedule(
    () -> handleTimeout(ctx), filterChainTimeoutMs, TimeUnit.MILLISECONDS);
// 链完成时
timeoutTask.cancel(false);
```

**filterChainTimeoutMs 与 retryDelayMs 叠加场景：** 若 `filterChainTimeoutMs=5000`、`retryDelayMs=2000`、`maxAttempts=3`，理论上重试延迟（最多 4000ms）加上请求耗时可能超过总超时。此时超时计时器优先触发，通过上述机制取消 RetryFilter 的 schedule 任务并返回 HTTP 504。`FilterProperties` 校验时不强制要求 `filterChainTimeoutMs > retryDelayMs * maxAttempts`，但在启动时若检测到该配置组合，记录 warn 日志提示可能导致重试无法完成。

### RetryFilter

**设计说明：** RetryFilter 实现 `WrappingFilter` 接口，不参与标准 pre/post 链，而是通过 `executeWithProxy()` 包装 ProxyHandler 的调用。`FilterChainHandler` 在 pre 链完成后检测链中是否存在 `WrappingFilter`，若存在则委托其控制请求发送循环，无需感知具体的 `RetryFilter` 类型。

**重试判断逻辑：**
```
isRetryable(method, hasBody, attempt, response/exception):
  if method in [POST, PATCH]: return false
  if hasBody (Content-Length > 0 or Transfer-Encoding: chunked): return false
  if attempt >= maxAttempts: return false
  if method in [PUT, DELETE] and method not in retryOnMethods: return false
  if exception != null && retryOnConnectFailure: return true
  if response.status in retryOnStatus: return true
  return false
```

**Content-Length 缺失时的 hasBody 判断策略：**

`hasBody` 的判断基于 HttpRequest 的 headers（不含 body 数据）：
- `Content-Length > 0`：有 body，不重试
- `Transfer-Encoding: chunked` 存在：有 body，不重试
- `Content-Length` 缺失且无 `Transfer-Encoding: chunked`：**按无 body 处理，允许重试**

这是有意为之的取舍：Content-Length 缺失通常意味着 GET/HEAD 等无 body 请求，或客户端未规范设置 header。若按有 body 处理会导致合法的 GET 请求无法重试。实现者无需额外处理此边界，直接按上述规则实现即可。

**固定间隔延迟（不支持退避）：**
```java
// retryDelayMs = 0 时立即重试，> 0 时用 EventLoop schedule 实现非阻塞等待
if (retryDelayMs > 0) {
    ctx.executor().schedule(() -> doRetry(ctx, request, attempt + 1),
            retryDelayMs, TimeUnit.MILLISECONDS);
} else {
    doRetry(ctx, request, attempt + 1);
}
```

**重试时 FilterContext 状态说明：** 重试循环由 `RetryFilter.executeWithProxy()` 控制，pre 链不重新执行。重试时需注意以下字段的处理：
- `RETRY_ATTEMPT`：每次重试前由 RetryFilter 累加，最终值反映实际重试次数
- `RETRY_DELAY_FUTURE`：每次调度 schedule 延迟任务时写入，超时触发时由 FilterChainHandler 读取并 cancel；任务执行完毕后清除该字段（设为 null）
- `CIRCUIT_BREAKER_START_NS`：每次重试前由 RetryFilter 重置为当前 `System.nanoTime()`，确保 CircuitBreakerFilter.post() 计算的是本次尝试的耗时
- `RATE_LIMITED`、`AUTH_RESULT`、`IP_ACCESS_RESULT`：pre 链已写入，重试时不重置，保持首次执行结果

**RetryFilter 与 CircuitBreakerFilter 的交互边界：**

pre 链不在重试时重新执行，因此 CircuitBreakerFilter.pre() 的熔断状态检查也不会在重试前重新执行。这意味着：若首次请求时熔断器为 CLOSED，重试期间熔断器切换为 OPEN，RetryFilter 不会感知该变化，仍会继续重试。这是有意为之的设计取舍——重试循环通常在毫秒级完成，熔断器在此期间切换的概率极低；且 CircuitBreakerFilter.post() 会在每次重试结果返回后更新滑动窗口，若连续失败达到阈值，下一个新请求进入时熔断器已为 OPEN 状态，可正常拦截。

**RetryFilter 在 HALF_OPEN 状态下的行为：**

HALF_OPEN 状态下，`halfOpenPassedCount` 通过 CAS 控制放行数量。若一个探测请求触发了 RetryFilter 重试，每次重试均通过 `ProxyInvoker.invoke()` 直接调用 ProxyHandler，**不经过** CircuitBreakerFilter.pre()，因此不会额外消耗 `halfOpenPassedCount`。CircuitBreakerFilter.post() 在每次重试结果返回时均会被调用（因为 post 链在每次 ProxyInvoker 完成后执行），任意一次失败即触发 HALF_OPEN → OPEN 转换。

**X-Retry-Attempt 头：** 每次重试前注入，值为当前重试次数（首次为 0）。

---

### CircuitBreakerFilter

**熔断器状态机（每个 Route 独立实例）：**
```java
class CircuitBreaker {
    volatile CircuitState state = CircuitState.CLOSED;
    volatile long openedAt;                           // OPEN 状态开始时间（毫秒）
    final AtomicInteger halfOpenPassedCount            // HALF_OPEN 已放行请求数（CAS 控制）
            = new AtomicInteger(0);
    final AtomicInteger halfOpenCompletedCount         // HALF_OPEN 已完成探测数（与放行数分离）
            = new AtomicInteger(0);
    // 滑动窗口统计（synchronized(this) 保护）
    Deque<CallResult> window;                          // 每次调用结果
    int totalCalls;
    int failureCalls;
    int slowCalls;
}

enum CallResult { SUCCESS, FAILURE, SLOW }
```

**pre() 逻辑：**
- CLOSED：允许通过，记录到 FilterContext
- OPEN：检查 `System.currentTimeMillis() - openedAt >= waitDurationInOpenState * 1000`，满足则 CAS 将 state 切换为 HALF_OPEN 并重置 `halfOpenPassedCount = 0`；否则返回 HTTP 503
- HALF_OPEN：使用 CAS 原子递增 `halfOpenPassedCount`：
  ```java
  int current = halfOpenPassedCount.get();
  if (current >= permittedCallsInHalfOpenState) {
      // 超额请求直接返回 503，不等待探测结果
      return HTTP 503;
  }
  if (halfOpenPassedCount.compareAndSet(current, current + 1)) {
      // CAS 成功，允许通过
  } else {
      // CAS 失败（并发竞争），直接返回 503（保守策略，避免超额放行）
      return HTTP 503;
  }
  ```

**慢调用计时：** pre() 执行时将 `System.nanoTime()` 写入 `FilterContext.CIRCUIT_BREAKER_START_NS`，post() 取差值判断是否超过 `slowCallDurationThresholdMs`。

**post() 逻辑（记录结果并判断状态转换）：**
- 记录本次请求结果：
  - 失败：5xx 响应或连接失败
  - 慢调用：`(System.nanoTime() - context.get(CIRCUIT_BREAKER_START_NS)) / 1_000_000 > slowCallDurationThresholdMs`（仅当 `slowCallDurationThresholdMs > 0` 时判断；若 `CIRCUIT_BREAKER_START_NS` 不存在则跳过慢调用判断，不抛 NPE）
  - 成功：其他情况
- CLOSED 状态：`synchronized(this)` 更新滑动窗口，若 `totalCalls >= minimumNumberOfCalls` 且（`failureRate > failureRateThreshold` 或 `slowCallRate > slowCallRateThreshold`）→ 切换为 OPEN，记录 `openedAt`
- HALF_OPEN 状态：`synchronized(this)` 用独立的 `halfOpenCompletedCount` 计数（与 `halfOpenPassedCount` 分离）判断所有探测请求是否已完成：
  - 全部成功 → 切换为 CLOSED，重置窗口，同时重置 `halfOpenPassedCount = 0` 和 `halfOpenCompletedCount = 0`
  - 任意失败 → 切换为 OPEN，记录 `openedAt`，同时重置 `halfOpenPassedCount = 0` 和 `halfOpenCompletedCount = 0`

**halfOpenPassedCount 和 halfOpenCompletedCount 的重置时机：**
- OPEN → HALF_OPEN 切换时：`halfOpenPassedCount.set(0)`，`halfOpenCompletedCount.set(0)`
- HALF_OPEN → CLOSED 切换时：同上重置（为下次可能进入 HALF_OPEN 做准备）
- HALF_OPEN → OPEN 切换时：同上重置（下次 OPEN → HALF_OPEN 时会再次重置，此处重置为防御性清理）
- 重置操作在 `synchronized(this)` 块内完成，与状态切换原子执行

**状态变更日志：** 每次状态转换记录 `log.info("CircuitBreaker state changed: {} -> {}, route={}, reason={}", ...)`

**COUNT_BASED 窗口：** 固定大小的环形缓冲区（`ArrayDeque` 限制大小为 `slidingWindowSize`）。
**TIME_BASED 窗口：** 保留最近 `slidingWindowSize` 秒内的请求记录，使用时间戳队列。

---

## 可观测性设计

### Micrometer 指标

所有指标通过 `MetricsCollector`（现有类）注册，在 `GatewayAutoConfiguration` 中初始化。

| 指标名 | 类型 | 标签 | 说明 |
|---|---|---|---|
| `gateway.filter.ip_access.denied` | Counter | `route`, `mode`(allowlist/denylist) | IP 拒绝计数 |
| `gateway.filter.auth.failure` | Counter | `route`, `reason`(no_token/invalid/forbidden/upstream_error) | 鉴权失败计数 |
| `gateway.filter.ratelimit.rejected` | Counter | `route`, `dimension`(ip/route/user_id) | 限流拒绝计数 |
| `gateway.filter.retry.attempts` | Counter | `route` | 重试总次数 |
| `gateway.filter.retry.success` | Counter | `route` | 重试后最终成功次数 |
| `gateway.filter.retry.exhausted` | Counter | `route` | 重试耗尽次数 |
| `gateway.filter.circuit_breaker.state` | Gauge | `route` | 当前熔断状态（0=CLOSED, 1=OPEN, 2=HALF_OPEN） |
| `gateway.filter.circuit_breaker.transitions` | Counter | `route`, `from`, `to` | 状态变更次数 |
| `gateway.filter.chain.timeout` | Counter | `route` | 过滤器链超时次数（返回 HTTP 504） |
| `gateway.filter.circuit_breaker.slow_calls` | Counter | `route` | 慢调用计数（用于区分错误率触发与慢调用率触发） |

### AccessLogEntry 扩展

FilterContext 中的结果在请求结束时由 `ProxyHandler.completeRequest()` 写入 `AccessLogEntry`。`ProxyHandler` 在构建 `AccessLogEntry` 时，从 `FilterContext` 读取过滤器执行结果并填充扩展字段。`FilterContext` 通过 Channel Attribute（`AttributeKey<FilterContext> FILTER_CONTEXT_KEY`）在 `FilterChainHandler` 和 `ProxyHandler` 之间共享，无需额外传参。

```java
// ProxyHandler.completeRequest() 中
FilterContext filterCtx = ctx.channel().attr(FilterChainHandler.FILTER_CONTEXT_KEY).get();
if (filterCtx != null) {
    logEntry.setIpAccessResult(filterCtx.get(FilterContext.IP_ACCESS_RESULT));
    logEntry.setAuthResult(filterCtx.get(FilterContext.AUTH_RESULT));
    logEntry.setRateLimited(filterCtx.get(FilterContext.RATE_LIMITED));
    logEntry.setRetryAttempts(filterCtx.get(FilterContext.RETRY_ATTEMPT));
    logEntry.setCircuitBreakerState(filterCtx.get(FilterContext.CIRCUIT_BREAKER_STATE));
}
```

---

## 包结构

```
gateway-core/src/main/java/com/lei/gateway/core/
├── filter/
│   ├── Filter.java                    # 过滤器接口（含 getOrder() 默认方法）
│   ├── FilterResult.java              # 枚举：CONTINUE / ABORT
│   ├── FilterContext.java             # 请求级上下文
│   ├── WrappingFilter.java            # 标记接口：包装 ProxyHandler 调用（RetryFilter 实现）
│   ├── ProxyInvoker.java              # 函数式接口：封装单次 ProxyHandler 调用
│   ├── FilterChainHandler.java        # Netty Handler，驱动过滤器链
│   ├── FilterChainFactory.java        # 根据 Route 构建过滤器链
│   └── builtin/
│       ├── IpAccessControlFilter.java
│       ├── PreAuthRateLimitFilter.java    # 认证前限流（IP/Route 维度）
│       ├── AuthFilter.java
│       ├── AuthTokenHandler.java          # 鉴权协议扩展接口
│       ├── JwtTokenHandler.java           # JWT Bearer Token 实现
│       ├── PostAuthRateLimitFilter.java   # 认证后限流（userId 维度）
│       ├── RateLimitEngine.java           # 令牌桶算法实现（两个 Filter 共享）
│       ├── HeaderTransformFilter.java
│       ├── RetryFilter.java               # 实现 WrappingFilter
│       └── CircuitBreakerFilter.java
└── config/
    ├── FilterProperties.java          # 全局过滤器配置（新增，含 filterChainTimeoutMs）
    ├── IpAccessControlConfig.java     # （新增）
    ├── AuthConfig.java                # （新增，含 AuthType 枚举）
    ├── RateLimitConfig.java           # （新增，PreAuth/PostAuth 共用，令牌桶参数）
    ├── HeaderTransformConfig.java     # （新增）
    ├── RetryConfig.java               # （新增）
    ├── CircuitBreakerConfig.java      # （新增，含慢调用率字段）
    └── Route.java                     # （扩展，新增过滤器配置字段）

auth-jwt-example/                      # 独立 Spring Boot 模块，用于集成测试
│   # parent: 根 pom.xml（与 gateway-core、gateway-app 同级）
│   # 不依赖 gateway-core 内部实现；集成测试时作为独立进程启动
│   # JWT 签名算法：HS256，密钥从 application.yml 读取（仅供测试，禁止用于生产环境）
└── src/main/java/com/lei/gateway/auth/
    ├── AuthJwtExampleApplication.java
    ├── TokenController.java           # POST /token（签发）、POST /verify（验证签名，返回 {"userId":"<string>"}）
    └── JwtService.java                # JWT 签发与验证逻辑（HS256，生成含 jti claim 的 Token，密钥从配置读取）
```

**auth-jwt-example 模块的 JWT 密钥与签名算法：**

- 签名算法：**HS256**（HMAC-SHA256），密钥为对称密钥字符串
- 密钥配置：在 `auth-jwt-example/src/main/resources/application.yml` 中以明文配置，如 `jwt.secret: test-secret-key-for-integration-test-only`
- 密钥长度：≥ 32 字节（HS256 要求）
- `JwtService` 使用该密钥签发和验证 JWT，`/verify` 端点验证签名后返回 `{"userId": "<string>"}`
- 网关本身（`AuthFilter`）不验证 JWT 签名，仅将 Token 透传给 Auth_Service；密钥只在 `auth-jwt-example` 内部使用，网关无需知道密钥
- 警告注释须写在 `application.yml` 顶部：`# WARNING: hardcoded secret for testing only, DO NOT use in production`

---

## 关键设计决策

1. **不引入第三方限流/熔断库**（如 Resilience4j）：保持依赖简洁，自行实现满足需求的最小化版本。
2. **RetryFilter 不缓存请求体**：与现有 ProxyHandler 的流式转发设计保持一致，避免内存压力；有请求体的请求（Content-Length > 0 或 Transfer-Encoding: chunked）一律不重试。
3. **FilterContext 不加锁**：所有过滤器操作均在 Netty EventLoop 线程上执行（异步回调也切回 EventLoop），无并发写入风险。
4. **配置校验在启动时完成**：在 `GatewayAutoConfiguration` 的 `@PostConstruct` 中校验所有过滤器配置，格式不合法则抛出异常终止启动。各配置的合法性规则：
   - `RateLimitConfig`：`limit` ≥ 1，`windowSeconds` ≥ 1，`burstCapacity`（若显式配置）≥ `limit`
   - `RetryConfig`：`maxAttempts` ≥ 1，`retryDelayMs` ≥ 0
   - `CircuitBreakerConfig`：`failureRateThreshold` ∈ (0, 100]，`slowCallRateThreshold` ∈ (0, 100]，`minimumNumberOfCalls` ≥ 1，`slidingWindowSize` ≥ 1，`waitDurationInOpenState` ≥ 1，`permittedCallsInHalfOpenState` ≥ 1，`slowCallDurationThresholdMs` ≥ 0
   - `IpAccessControlConfig`：`mode` 不为 null，`rules` 非空，每条规则为合法 IPv4 地址或 CIDR（`a.b.c.d` 或 `a.b.c.d/n`，n ∈ [0, 32]）
   - `HeaderTransformConfig`：`add`/`set` 每项的 `name` 和 `value` 均非 null 非空，`remove` 每项非 null 非空
   - `AuthConfig`：`authCacheTtlSeconds` ≥ 0，`authServiceTimeoutMs` ≥ 1；若 `enabled = true` 则 `authServiceUrl` 不得为 null 或空
   - `PostAuthRateLimitConfig` 配置了 USER_ID 维度但对应路由未启用 AuthFilter：记录 warn 日志，不终止启动
5. **路由级配置完全覆盖全局配置**：不做合并，简化推理复杂度。
6. **Auth_Service 复用 UpstreamConnectionPool**：Auth_Service 注册为普通路由，AuthFilter 通过连接池调用，与业务 Upstream 共享连接管理，不另起 HttpClient，保持线程模型一致。
7. **限流拆分为 PreAuth/PostAuth 两个过滤器**：避免未鉴权请求消耗已鉴权用户的 userId 配额；两者共享 `RateLimitEngine` 算法实现，区别仅在于支持的维度。使用令牌桶算法，天然支持突发流量平滑，内存占用 O(维度数)。
8. **重试不支持退避策略**：网关场景下客户端超时通常为秒级，指数退避会导致重试成功时客户端已超时，固定间隔（默认 0）更实用。
9. **RetryFilter 通过 WrappingFilter 接口解耦**：FilterChainHandler 只感知 `WrappingFilter` 接口，不直接依赖 `RetryFilter` 类型，符合开闭原则；未来新增其他包装型过滤器无需修改 FilterChainHandler。
10. **HALF_OPEN 探测请求并发控制用 CAS**：`AtomicInteger.compareAndSet` 原子控制放行数量，超额请求直接返回 503，无需加锁，符合 Netty 无锁化风格；CAS 失败（并发竞争）时同样返回 503（保守策略，避免超额放行）；`halfOpenCompletedCount` 与 `halfOpenPassedCount` 分离，避免用放行数判断完成数的逻辑歧义。
11. **Auth 缓存 key 优先使用 JWT jti claim**：`jti` 是 JWT 标准唯一标识字段，长度固定（通常为 UUID），语义清晰；不存在 `jti` 时退回 SHA-256 哈希，避免直接用原始 Token 字符串作 key 导致的内存浪费。
12. **熔断支持慢调用率触发**：Upstream 降级后可能返回 200 但响应极慢，纯错误率熔断器无法感知；慢调用率触发作为补充，与错误率触发并列，任意一个超阈值均可触发 CLOSED → OPEN。
13. **过滤器实例与 Route 绑定，启动时预构建**：有状态过滤器（CircuitBreakerFilter、RateLimitFilter）的状态必须跨请求持久化，因此 FilterChainFactory 在启动时为每个 Route 预构建并缓存过滤器链实例，每次请求复用缓存，不重新构建。
14. **filterChainTimeoutMs 覆盖整个过滤器链生命周期**：计时从 FilterChainHandler.channelRead 开始，包含 pre 链、RetryFilter 重试循环和 post 链。超时时关闭 upstream Channel 并返回 HTTP 504，不等待正在进行的异步操作完成。
15. **限流响应头在 ABORT 场景下由 pre() 直接写入**：429 响应的 X-RateLimit-* 和 Retry-After 头必须在 pre() 中写入，不能依赖 post()（ABORT 后 post 链不执行）。TokenBucket.tryConsume() 返回 ConsumeResult 供 pre() 和 post() 共用，避免重复计算。
16. **Content-Length 缺失时按无 body 处理**：RetryFilter 判断 hasBody 时，Content-Length 缺失且无 Transfer-Encoding: chunked 视为无 body，允许重试。这是有意为之的取舍，避免合法的 GET 请求因 header 不规范而无法重试。
