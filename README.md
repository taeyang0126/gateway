# netty-gateway

基于 Netty 4.2.x + Spring Boot 的 HTTP 反向代理网关。

## 技术栈

- Java 21
- Netty 4.2.x
- Spring Boot 3.4.x
- Micrometer + Prometheus
- jqwik（属性测试）

## 模块

| 模块 | 说明 |
|---|---|
| [gateway-pool](gateway-pool/) | 通用并发资源池，灵感来自 HikariCP ConcurrentBag |
| [gateway-core](gateway-core/) | 网关核心：路由、代理转发、连接池、可观测性、限流 |
| [gateway-app](gateway-app/) | 网关启动入口，包含 application.yml 和 main 方法 |
| [gateway-example](gateway-example/) | Mock 上游服务，用于本地开发测试 |

## 快速开始

```bash
# 构建
mvn clean package

# 启动 mock 上游服务
mvn spring-boot:run -pl gateway-example

# 启动网关
mvn spring-boot:run -pl gateway-app

# 运行测试
mvn clean test
```

## 代码质量

项目集成了 Checkstyle（Google Java Style）、SpotBugs、JaCoCo，构建时自动执行检查。
