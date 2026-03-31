# netty-gateway

[![codecov](https://codecov.io/gh/taeyang0126/gateway/branch/rewrite%2Fv4/graph/badge.svg)](https://codecov.io/gh/taeyang0126/gateway)

基于 Netty 4.2.x + Spring Boot 3.4.x 的 HTTP 反向代理网关。

## 核心能力

- 路由匹配与反向代理转发（path-prefix → upstream）
- 高性能连接池（ConcurrentBag 模式，灵感来自 HikariCP）
- 安全层：JWT 认证、IP 黑白名单（CIDR）、令牌桶限流
- 可观测性：Micrometer/Prometheus 指标、Access Log、Trace Context
- 大文件上传/下载支持（默认 50MB 限制）
- 优雅停机：分阶段停机编排、排空拒绝新请求、等待在途请求完成
- K8s 健康检查：`/health/live`、`/health/ready`（与排空状态联动）、`/health`
- 启动预热：内部热路径 + upstream 预热请求，配合 `startup-delay-seconds` 兜底

## 技术栈

- Java 21 / Netty 4.2.x / Spring Boot 3.4.x
- Micrometer + Prometheus
- Jackson 2.18.x
- nimbus-jose-jwt（JWT 验签）
- jqwik 1.9.x（属性测试）/ JUnit 5

## 模块

| 模块 | 说明 |
|---|---|
| `gateway-pool` | 通用并发资源池（无 Spring 依赖），灵感来自 HikariCP ConcurrentBag |
| `gateway` | 网关本体：路由、代理转发、连接池、安全、可观测性、优雅停机（端口 8080） |
| `example-upstream` | Mock 上游服务（端口 8082），用于本地开发测试 |
| `example-auth` | JWT 认证示例服务（端口 8091），签发/验证 JWT token |

## 快速开始

```bash
# 终端 1：启动 mock 上游服务
mvn spring-boot:run -pl example-upstream

# 终端 2：启动网关
mvn spring-boot:run -pl gateway
```

网关监听 `8080`，将 `/api/example/**` 路由到 `http://localhost:8082`。

## 构建与测试

```bash
# 完整构建（Checkstyle + forbidden-apis + 测试 + 覆盖率）
mvn clean verify -T 2C -U -pl '!gateway-perf'

# 日常开发：跳过集成测试
mvn clean test -Dexclude="**/*IntegrationTest.java" -T 1C

# 单模块测试
mvn test -pl gateway-pool
mvn test -pl gateway
```

覆盖率报告：`gateway/target/site/jacoco/index.html`

## 代码质量

`mvn clean verify` 通过 = Checkstyle 零违规 + forbidden-apis 零违规 + 所有测试绿。

| 工具 | 触发阶段 | 说明 |
|---|---|---|
| Checkstyle | `validate` | Google Java Style，任何 warning 即 fail |
| forbidden-apis | `compile` | 禁止不安全/平台相关/已废弃 JDK API |
| JaCoCo | `test` | 生成覆盖率报告 |

```bash
mvn checkstyle:check   # 仅 Checkstyle
```
