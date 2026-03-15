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

## 阶段化执行约定（Gate + 追踪矩阵）
- 必须严格按阶段串行推进：前一阶段未完成，不得开始下一阶段。
- 每阶段必须维护 Gate 文档：`docs/requirements/stage-gates/`
- 每阶段必须维护需求追踪矩阵：`docs/requirements/traceability/`
- 每阶段进入开发前，先补齐本阶段 Task 分解；Task 支持子 Task，但必须按依赖顺序执行。
- 每阶段内部执行顺序（强约束）：
  1. 先完成阶段概要设计与 Task 分解（父 Task/子 Task/依赖/验收点）。
  2. 再按 Task 串行编码实现（前一 Task 未完成，不得开始下一 Task）。
  3. 最后回填追踪矩阵与验证证据（代码位置、测试用例、验收结果、报告链接）。
- 每阶段完成判定必须同时满足：
  - GateStatus=`PASS`
  - 需求追踪矩阵中对应需求状态为 `PASS`
  - 本地严格校验通过：`./scripts/requirements/verify-stage.sh <stage> --strict`
- 本地校验脚本：`scripts/requirements/verify-stage.sh`

## 阶段内标准工作流（默认执行，不做二选一确认）
- 触发条件：当 lei 明确要求“开始某阶段设计/开发”时，按以下顺序直接推进。
- 固定顺序：
  1. 产出阶段概要设计（架构、边界、关键策略、需求映射）。
  2. 产出阶段 Task 分解（父/子任务、依赖、DoD、FR/NFR/AC 映射）。
  3. 产出阶段详细设计（配置模型、时序、错误码、测试设计）。
  4. 在详细设计内补齐接口清单（类/方法签名级别）。
  5. 回填阶段追踪矩阵到“可追踪设计态”（任务ID、设计证据、计划测试）。
  6. 按 Task 依赖顺序进入编码与测试，不跨任务抢跑。
  7. 完成后回填追踪矩阵为 `PASS` 并补齐验收证据，最后更新 Gate 结论。
- 约束：
  - 默认不再向 lei 提供“先做A还是先做B”的流程选择题。
  - 若遇到设计冲突或高风险决策，再向 lei 单点确认后继续。

## 运行与验证基线
- JDK 固定为 `JDK 24`
- 压测工具固定为 `Gatling`
- 设计与实现优先可验证性：需求必须可映射到代码、测试与验收证据。

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

## 重大代码变更文档同步（强制）
- 任何“重大代码修改”（架构调整、核心链路变更、可观测性/压测/规范策略变化）必须在同一批改动中同步以下文档：
  - 阶段设计文档：`docs/design/stageX/01-阶段X-概要设计.md`、`docs/design/stageX/03-阶段X-详细设计.md`
  - 阶段追踪矩阵：`docs/requirements/traceability/阶段X-需求追踪矩阵.tsv`
  - 阶段学习文档：`docs/learning/stageX/`
  - 模块文档：`<module>/README.md`
  - 仓库总览：`README.md`（若对外行为、流程或入口发生变化）
- 未完成上述同步时，不得宣称“阶段完成”或进入提交阶段。
