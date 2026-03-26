---
inclusion: always
---

# Tech

## 与标准 Google Java Style 的差异（Checkstyle 强制）

- 缩进 4 空格（非 Google 默认 2 空格），续行和 throws 缩进 8 空格
- 行宽 120（非 Google 默认 100）
- Javadoc 句末用中文句号 `。`（非英文 `.`）
- public/protected 方法和类必须有 Javadoc（`@Override` 和 `@Test` 除外）
- 成员名至少 2 字符（`^[a-z][a-z0-9][a-zA-Z0-9]*$`），单字母变量不通过
- 缩写词按驼峰拆分，写 `HttpUrl` 不写 `HTTPURL`
- 抑制检查：`// CHECKSTYLE.SUPPRESS: RuleName` 行内注释，或 `CHECKSTYLE.OFF/ON` 块注释

## forbidden-apis

- 禁止依赖平台默认编码的 API：用 `new String(bytes, StandardCharsets.UTF_8)` 而非 `new String(bytes)`
- 禁止 `sun.misc.Unsafe` 等内部 API、已废弃 JDK API
- main 和 test 代码均检查

## 构建

```bash
mvn clean verify -T 1C -U          # 完整构建
mvn clean test -Dexclude="**/*IntegrationTest.java" -T 1C  # 跳过集成测试
mvn test -pl gateway-core           # 单模块
mvn checkstyle:check                # 仅 Checkstyle
```

## 注意事项

- surefire 配置 `parallel=classes, threadCount=4`，测试类须线程安全
- 异常日志必须传异常对象：`log.error("msg", e)`，禁止只打 `e.getMessage()`
