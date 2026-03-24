---
inclusion: always
---

# Tech Stack

- Java 21, Maven 多模块
- Netty 4.2.x（网络层，非 Spring WebFlux）
- Spring Boot 3.4.x（配置绑定、自动装配，不用 Spring MVC 做请求处理）
- Micrometer + Prometheus（指标）
- jqwik 1.9.x（属性测试 / Property-Based Testing）
- JUnit 5（单元测试 + 集成测试）
- Jackson 2.18.x（JSON 序列化）
- nimbus-jose-jwt（JWT 验签）

## 代码质量

| 工具 | 阶段 | 说明 |
|---|---|---|
| Checkstyle | validate | Google Java Style，warning 即 fail |
| forbidden-apis | compile | 禁止 jdk-unsafe / jdk-non-portable / jdk-deprecated |
| JaCoCo | test | 覆盖率报告 |

## 常用命令

```bash
# 完整构建（Checkstyle + forbidden-apis + 测试 + 覆盖率）
mvn clean verify -T 1C -U

# 日常开发：跳过集成测试
mvn clean test -Dexclude="**/*IntegrationTest.java" -T 1C

# 单模块测试
mvn test -pl gateway-pool
mvn test -pl gateway-core

# 仅 Checkstyle
mvn checkstyle:check
```

## 注意事项

- 测试并行：surefire 配置 `parallel=classes, threadCount=4`，测试类须线程安全
- 本地 Maven 仓库在项目根目录 `.m2/repository`（非默认 `~/.m2`）
- forbidden-apis 同时检查 main 和 test 代码（`check` + `testCheck`）
