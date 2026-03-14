# 仓库贡献指南

## 项目结构与模块组织
当前仓库以质量基线和工程规范为核心，主要目录如下：
- `codequality/`：代码规范与质量规则（Checkstyle、PMD、测试标准等）。
- `.github/workflows/`：CI 工作流（`quality-gate.yml`、`quality-config.yml`）。
- `scripts/ci/`：本地与 CI 对齐脚本（`run-quality.sh`）。
- 根构建文件：`pom.xml`、`mvnw`、`mvnw.cmd`、`.mvn/`。

业务模块创建后，统一采用 Maven 标准目录：`src/main/java`、`src/test/java`、`src/main/resources`。

## 构建、测试与开发命令
- `./scripts/ci/run-quality.sh`：推荐本地入口，自动识别是否启用 bootstrap 模式。
- `./mvnw -Pquality -Dquality.bootstrap=true verify`：无业务模块时运行质量校验。
- `./mvnw -Pquality -Dquality.bootstrap=false verify`：严格门禁模式（完整质量检查）。
- `./mvnw spotless:apply`：自动格式化代码。

## 编码风格与命名规范
统一遵循 `codequality/checkstyle.xml`：
- Java 使用 4 空格缩进，禁止 Tab。
- 单行最大长度 120。
- 禁止通配符 import（`*`）。
- 每个文件只保留一个顶层类。
- 命名约定：类 `UpperCamelCase`，方法/字段 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。

公共 API 或关键行为应补充必要 Javadoc。

## 测试规范
遵循 `codequality/testing-standards.md`：
- 单元测试类：`*Tests.java`
- 集成测试类：`*IT.java` 或 `*ST.java`
- 建议方法命名：`should_<行为>_when_<条件>`

覆盖率目标：行覆盖率 >= 85%，分支覆盖率 >= 75%。

## 提交与 PR 规范
建议使用 Conventional Commit：`type(scope): summary`，每次提交只包含一类逻辑改动。

PR 说明需包含：
- 变更目的与影响范围
- 验证命令与结果
- 关联 issue 或背景说明
- CI 通过（`quality-gate` 与 `quality-config`）

## 文档同步要求
当发生重大变动、技术选型调整、项目启动策略变化时，必须在同一 PR 同步更新 `README.md`。
