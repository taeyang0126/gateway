# Gateway 项目

本仓库是一个全新的网关项目。  
当前阶段优先完善工程质量基线：建立可执行规范、自动化检查和 CI 门禁，
为后续业务模块开发提供稳定基础。

## 当前范围
- 质量基线：`codequality/`
- CI 工作流：`.github/workflows/`
- Maven Wrapper 与根构建配置：`mvnw`、`.mvn/`、`pom.xml`
- 本地与 CI 对齐脚本：`scripts/ci/run-quality.sh`
- 阶段 1 业务模块：`gateway-server/`（Netty HTTP Server 启动链路）
  - 模块详细说明：`gateway-server/README.md`

## 分阶段需求文档
- 网关需求总入口：`docs/requirements/README.md`
- 采用从小到大的迭代方式，按阶段拆分需求，供后续概要设计与详细设计直接引用。

## 分阶段学习文档
- 学习总入口：[docs/learning/README.md](docs/learning/README.md)
- 开发流程总览：[docs/learning/开发流程总览.md](docs/learning/开发流程总览.md)
- 阶段 1 学习导航：[docs/learning/stage1/00-阶段1-学习导航.md](docs/learning/stage1/00-阶段1-学习导航.md)
- 阶段 1 可视化流程页：[docs/learning/stage1/06-阶段1开发流程可视化.html](docs/learning/stage1/06-阶段1开发流程可视化.html)
- 阶段 1 学习指导看板：[docs/learning/stage1/07-阶段1学习指导看板.html](docs/learning/stage1/07-阶段1学习指导看板.html)
- 学习内容覆盖：架构、代码规范、压测实战、需求追踪、CI 与打包流程。

## 阶段执行流程（强约束）
```text
需求(FR/NFR/AC)
  -> 设计(01/02/03)
  -> 编码+测试(按任务依赖)
  -> 回填矩阵+Gate
  -> 双校验:
       1) ./scripts/ci/run-quality.sh
       2) ./scripts/requirements/verify-stage.sh <stage> --strict
  -> commit/push
```
- 严格按阶段串行推进：前一阶段未完成，禁止进入下一阶段。
- 每阶段必须先过 Gate（`docs/requirements/stage-gates/`）再进入下一阶段。
- 每阶段必须维护需求追踪矩阵（`docs/requirements/traceability/`），确保需求到代码和测试可追溯。
- 本地自动化校验脚本：`./scripts/requirements/verify-stage.sh`
  - 基础校验：`./scripts/requirements/verify-stage.sh 1`
  - 严格校验：`./scripts/requirements/verify-stage.sh stage1 --strict`
- 进入下一阶段前，必须满足：
  - 当前阶段 GateStatus=`PASS`
  - 追踪矩阵关键条目状态为 `PASS`
  - 严格校验通过（`--strict`）

## 研发基线
- JDK：`JDK 24`
- 压测工具：`Gatling`
- 阶段设计和实现必须优先保证“可验证性”：每条需求都有对应任务、代码实现、测试用例和验收证据。

## 质量门禁
本地推荐执行：

```bash
./scripts/ci/run-quality.sh
```

手动模式：

```bash
# 启动阶段（尚无业务模块）
./mvnw -Pquality -Dquality.bootstrap=true verify

# 严格模式（引入业务模块后）
./mvnw -Pquality -Dquality.bootstrap=false verify
```

质量门禁包含：
- Spotless（格式化）
- Checkstyle（代码风格与结构）
- PMD（代码质量规则）
- SpotBugs（字节码缺陷扫描）
- JaCoCo（覆盖率阈值）
- Surefire/Failsafe（单元/集成测试边界）

## CI 工作流
- `quality-gate.yml`：执行完整质量校验。
- `quality-config.yml`：校验质量配置文件和 Maven 配置可解析性。
- `package-build.yml`：执行打包、fat-jar 启动冒烟并上传构建产物（JAR Artifact）。

## 双看板同步（强制流程）
- 当需求/设计/实现变更时，必须同步更新阶段双看板：
  - `docs/learning/stageX/06-阶段X开发流程可视化.html`
  - `docs/learning/stageX/07-阶段X学习指导看板.html`
- 该规则由 `AGENTS.md` 强制执行，不再通过单独 GitHub Action 校验。

## 贡献说明
- 提交信息建议使用 Conventional Commit：`type(scope): summary`。
- 每次提交保持单一逻辑变更。
- 重大变动、技术选型调整、项目启动策略更新时，需在同一 PR 同步更新 `README.md`。
