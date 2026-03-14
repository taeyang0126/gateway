# Code Quality Baseline

本目录定义网关重写阶段的质量基线，目标是对标一线 Java 开源项目的工程标准：
- **统一风格**：格式和命名一致，减少无效评审。
- **静态防线**：在编译前发现潜在缺陷。
- **测试约束**：保证可维护性和可回归性。

## 1. 质量门禁（必须通过）
- Spotless（格式化）
- Checkstyle（代码风格与结构规则）
- PMD（复杂度、坏味道、常见错误）
- SpotBugs（字节码级缺陷扫描）
- JaCoCo（覆盖率阈值）
- Surefire/Failsafe（单元/集成测试分离）

## 2. 推荐命令（当前可直接使用）
```bash
./scripts/ci/run-quality.sh
./mvnw -Pquality -Dquality.bootstrap=true verify
./mvnw -Pquality -Dquality.bootstrap=false verify
```

- `bootstrap=true`：仓库暂时没有业务模块时使用，跳过字节码/覆盖率门禁（SpotBugs/JaCoCo）。
- `bootstrap=false`：严格模式，完整质量门禁；有 Java 模块后默认使用该模式。

## 3. 覆盖率建议（默认阈值）
- 行覆盖率 >= 85%
- 分支覆盖率 >= 75%
- 核心模块（协议、路由、会话）建议 >= 90% 行覆盖率

## 4. 文件说明
- `checkstyle.xml`：主风格规则
- `checkstyle-suppressions.xml`：临时豁免（应最小化）
- `checkstyle-header.txt`：统一版权头模板
- `pmd-ruleset.xml`：PMD 规则集
- `spotbugs-exclude.xml`：SpotBugs 过滤规则
- `testing-standards.md`：单元测试标准
- `maven-quality-profile.xml`：父 POM 可复用的质量插件模板
- `../pom.xml`：当前仓库已接入的质量门禁入口
- `../scripts/ci/run-quality.sh`：本地与 CI 一致的执行脚本
