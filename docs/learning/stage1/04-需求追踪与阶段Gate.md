# 阶段 1 需求追踪与 Gate

## 1. 为什么必须做追踪
阶段文档、代码、测试、证据必须一一映射，否则阶段完成不可验证。

## 2. 关键文件
- Gate：`docs/requirements/stage-gates/阶段1-Gate.md`
- 追踪矩阵：`docs/requirements/traceability/阶段1-需求追踪矩阵.tsv`
- 验证脚本：`scripts/requirements/verify-stage.sh`

## 3. 阶段执行约束
1. 先完成阶段概要设计与任务分解。
2. 再按任务依赖顺序实现与测试。
3. 最后回填矩阵与证据，执行严格校验。

## 4. 阶段完成判定
必须同时满足：
1. GateStatus=`PASS`
2. 追踪矩阵关键项状态为 `PASS`
3. `./scripts/requirements/verify-stage.sh 1 --strict` 通过

## 5. 学习者实操要求
- 随机抽 3 条需求，独立定位对应代码和测试。
- 复述该条需求的验收证据放在哪个文件。
