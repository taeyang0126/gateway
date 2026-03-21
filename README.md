# netty-gateway

基于 Netty 4.2.x + Spring Boot 的 HTTP 反向代理网关。

## 技术栈

- Java 21 / Netty 4.2.x / Spring Boot 3.4.x
- Micrometer + Prometheus
- jqwik（属性测试）

## 模块

| 模块 | 说明 |
|---|---|
| `gateway-pool` | 通用并发资源池，灵感来自 HikariCP ConcurrentBag |
| `gateway-core` | 网关核心：路由、代理转发、连接池、可观测性、限流 |
| `gateway-app` | 网关启动入口（端口 8080） |
| `gateway-example` | Mock 上游服务（端口 8081），用于本地开发测试 |

## 快速开始

```bash
# 终端 1：启动 mock 上游服务
mvn spring-boot:run -pl gateway-example

# 终端 2：启动网关
mvn spring-boot:run -pl gateway-app
```

网关监听 `8080`，将 `/api/example/**` 路由到 `http://localhost:8081`。

### 验证接口

```bash
# GET
curl http://localhost:8080/api/example/hello

# POST echo
curl -X POST http://localhost:8080/api/example/echo \
  -H "Content-Type: text/plain" -d "hello"

# 单文件上传
curl -X POST http://localhost:8080/api/example/upload \
  -F "file=@/path/to/file.txt"

# 多文件上传
curl -X POST http://localhost:8080/api/example/upload/multi \
  -F "files=@/path/to/a.txt" -F "files=@/path/to/b.txt"

# 文件 + 表单字段
curl -X POST http://localhost:8080/api/example/upload/with-fields \
  -F "file=@/path/to/file.txt" -F "name=test" -F "description=demo"

# 大文件下载（1MB）
curl http://localhost:8080/api/example/download -o testfile.bin
```

## 构建与测试

```bash
# 完整构建（Checkstyle + SpotBugs + 测试 + 覆盖率）
mvn clean verify -T 1C -U

# 日常开发：跳过集成测试和 SpotBugs
mvn clean test -Dexclude="**/*IntegrationTest.java" -T 1C

# 单模块测试
mvn test -pl gateway-pool
mvn test -pl gateway-core
```

覆盖率报告：`gateway-core/target/site/jacoco/index.html`

## 代码质量

`mvn clean verify` 通过 = Checkstyle 零违规 + SpotBugs 零 Medium 以上 bug + 所有测试绿。

| 工具 | 触发阶段 | 说明 |
|---|---|---|
| Checkstyle | `validate` | Google Java Style，任何 warning 即 fail |
| SpotBugs | `verify` | 字节码静态分析，effort=Max，threshold=Medium |
| JaCoCo | `test` | 生成覆盖率报告 |

```bash
mvn checkstyle:check   # 仅 Checkstyle
mvn spotbugs:check     # 仅 SpotBugs
mvn spotbugs:gui       # SpotBugs 图形化报告
```
