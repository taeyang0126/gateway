---
inclusion: always
---

# Product

netty-gateway — 基于 Netty 4.2.x + Spring Boot 3.4.x 的 HTTP 反向代理网关。
Spring Boot 仅用于配置绑定和自动装配，所有请求处理由 Netty pipeline 完成，不经过 Spring MVC / WebFlux。

## Pipeline 顺序（固定）

```
HttpServerCodec → IdleStateHandler → TraceContextHandler → DrainHandler → RoutingHandler
```
- RoutingHandler：路径匹配 → 安全检查 → 动态添加 ProxyHandler
- ProxyHandler：从连接池获取上游 Channel → 转发请求/响应 → 归还连接（每请求新建）
- 上游响应通过 UpstreamResponseHandler 回写到客户端 Channel
- RoutingHandler / TraceContextHandler / DrainHandler 是 `@Sharable` 全局单例

## 关键设计约束

- gateway-pool 模块零 Spring 依赖，可独立使用
- 连接池按 upstream host 隔离（每个 host 独立池）
- 安全配置：全局默认 + 路由级覆盖（RouteSecurityConfigResolver 合并），不是简单替换
- 限流引擎通过 RateLimiterEngine 接口抽象，内置 LocalTokenBucketRateLimiter，预留分布式扩展点（DistributedRateLimiterAdapter）
- 所有网关配置在 `gateway.*` 命名空间，绑定到 GatewayProperties
