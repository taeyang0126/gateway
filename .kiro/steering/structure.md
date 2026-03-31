---
inclusion: always
---
# Project Structure

## 模块依赖与边界

gateway → gateway-pool

- gateway-pool：零 Spring 依赖（依赖 netty-common），可独立使用
- gateway：依赖 Spring Boot（仅配置绑定和自动装配）+ Netty，含启动类和运行时配置

## 代码放置规则

| 新增代码类型 | 目标模块 | 目标包 |
|---|---|---|
| 通用池逻辑（与网关无关） | gateway-pool | `com.lei.gateway.pool` |
| 配置类 / Properties | gateway | `com.lei.gateway.core.config` |
| Netty Handler / 代理转发 / 连接池管理 | gateway | `com.lei.gateway.core.proxy` |
| 认证、授权、限流、IP 控制 | gateway | `com.lei.gateway.core.security` |
| 指标、日志、追踪 | gateway | `com.lei.gateway.core.observability` |
| Spring Boot 自动装配 | gateway | `config.com.lei.gateway.GatewayAutoConfiguration` |

新增包前先确认现有包是否已覆盖该职责，避免包膨胀。

## 包命名

所有模块根包：`com.lei.gateway`，子模块追加 `.pool` / `.core`

## 测试组织

| 类型 | 命名 | 位置 | 说明 |
|---|---|---|---|
| 单元测试 | `*Test.java` | 与源码同包路径 | 线程安全（surefire parallel=classes） |
| 属性测试 | `*PropertyTest.java` | 与源码同包路径 | jqwik，验证不变量 |
| 集成测试 | `*IntegrationTest.java` | `gateway/.../integration/` | 可通过 `-Dexclude` 跳过 |

- 集成测试继承 `IntegrationTestBase`，使用内置 `MockUpstreamServer`
- 属性测试与单元测试放同一个包，不单独建 integration 目录
