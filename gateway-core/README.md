# gateway-core

基于 Netty 4.2.x 的 HTTP 反向代理网关核心模块。

## 定位

提供完整的反向代理能力：路由匹配、请求转发、连接池管理、可观测性（metrics / access log / trace）、请求限流。通过 Spring Boot 自动配置集成。

## 模块结构

| 包 | 职责 |
|---|---|
| `config` | 网关配置（路由、连接池、限流、可观测性）及 Spring Boot 自动配置 |
| `proxy` | Netty pipeline：服务端引导、路由处理、代理转发、上游连接池 |
| `observability` | Access Log、Metrics（Micrometer/Prometheus）、Trace Context |

## 主要依赖

- Netty 4.2.x（HTTP codec、transport）
- Spring Boot 3.4.x（配置绑定、生命周期）
- Micrometer + Prometheus（指标采集）
- gateway-pool（上游连接池底层实现）

## 构建 & 测试

```bash
mvn clean test -pl gateway-core
```
