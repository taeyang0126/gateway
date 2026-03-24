---
inclusion: always
---

# Product

netty-gateway — 基于 Netty 4.2.x + Spring Boot 3.4.x 的 HTTP 反向代理网关。

核心能力：
- 路由匹配与反向代理转发（path-prefix → upstream）
- 高性能连接池（ConcurrentBag 模式，灵感来自 HikariCP）
- 安全层：JWT 认证、IP 黑白名单（CIDR）、令牌桶限流
- 可观测性：Micrometer/Prometheus 指标、Access Log、Trace Context
- 大文件上传/下载支持（默认 50MB 限制）
