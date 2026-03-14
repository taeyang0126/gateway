# Gateway 项目

本仓库是一个全新的网关项目。  
当前阶段优先完善工程质量基线：建立可执行规范、自动化检查和 CI 门禁，
为后续业务模块开发提供稳定基础。

## 当前范围
- 质量基线：`codequality/`
- CI 工作流：`.github/workflows/`
- Maven Wrapper 与根构建配置：`mvnw`、`.mvn/`、`pom.xml`
- 本地与 CI 对齐脚本：`scripts/ci/run-quality.sh`

当前仓库尚未引入具体业务模块，这是启动阶段的预期状态。

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

## 贡献说明
- 提交信息建议使用 Conventional Commit：`type(scope): summary`。
- 每次提交保持单一逻辑变更。
- 重大变动、技术选型调整、项目启动策略更新时，需在同一 PR 同步更新 `README.md`。
