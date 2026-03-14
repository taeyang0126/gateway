# 网关需求文档目录

本目录用于沉淀“从小到大”迭代式网关需求，作为后续概要设计、详细设计和测试计划的唯一需求输入。

## 阅读顺序
1. [00-总览与迭代计划.md](./00-总览与迭代计划.md)
2. [01-阶段1-HTTP反向代理最小闭环.md](./01-阶段1-HTTP反向代理最小闭环.md)
3. [02-阶段2-路由与配置热更新.md](./02-阶段2-路由与配置热更新.md)
4. [03-阶段3-长连接网关能力.md](./03-阶段3-长连接网关能力.md)（含阶段3A/3B）
5. [04-阶段4-流量治理与稳定性.md](./04-阶段4-流量治理与稳定性.md)
6. [05-阶段5-安全与管理面.md](./05-阶段5-安全与管理面.md)
7. [06-阶段6-工业化收口与性能达标.md](./06-阶段6-工业化收口与性能达标.md)（含阶段6A/6B）
8. [CHANGELOG-REQUIREMENTS.md](./CHANGELOG-REQUIREMENTS.md)

## Gate 与追踪矩阵
- Gate 模板：`docs/requirements/templates/阶段-Gate-模板.md`
- 追踪矩阵模板：`docs/requirements/templates/需求追踪矩阵-模板.tsv`
- 阶段 1 Gate：`docs/requirements/stage-gates/阶段1-Gate.md`
- 阶段 1 追踪矩阵：`docs/requirements/traceability/阶段1-需求追踪矩阵.tsv`

## 阶段 1 设计文档
- 阶段 1 概要设计：`docs/design/stage1/01-阶段1-概要设计.md`
- 阶段 1 任务分解：`docs/design/stage1/02-阶段1-任务分解.md`
- 阶段 1 详细设计：`docs/design/stage1/03-阶段1-详细设计.md`

## 使用约定
- 每进入新阶段前，先冻结上一阶段需求版本，再启动下一阶段设计。
- 需求编号（FR/NFR/AC）必须在设计文档和测试用例中被引用。
- 若需求变更，必须更新对应阶段文档并追加变更记录。
- 需求变更记录统一维护在 `CHANGELOG-REQUIREMENTS.md`。

## 本地自动化校验
- 脚本：`./scripts/requirements/verify-stage.sh`
- 用法：
  - `./scripts/requirements/verify-stage.sh 1`
  - `./scripts/requirements/verify-stage.sh stage1 --strict`

## 阶段 1 压测与稳定性脚本
- 入口：`./scripts/perf/stage1/run-stage1-ac4.sh`
- 说明：`scripts/perf/stage1/README.md`
