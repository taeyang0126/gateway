## Context

`gateway-pool` 的 `ConcurrentPool` 当前为同步借用 + 异步借用混合设计。异步路径已使用 `CompletableFuture`，但等待队列采用 `LinkedBlockingDeque`，并在超时完成后执行 `remove(pending)`。在高并发场景下，这条路径会引入锁竞争和线性删除开销。与此同时，`close` 与 `createAsync` 之间存在竞态窗口，可能导致关闭后仍有新建条目挂入共享池。

本次优化限定在 `gateway-pool` 模块内，目标是在不改变外部 API 的前提下，提升异步借用吞吐与并发安全性。

## Goals / Non-Goals

**Goals:**
- 将异步等待队列改为无锁结构，降低等待路径竞争开销。
- 在超时、取消、归还、关闭并发情况下，保证等待者仅完成一次且不会长期残留。
- 减少归还与再借用之间的竞争窗口，提升等待者获得条目的确定性。
- 修复关闭场景下异步建连完成导致的资源泄漏。
- 补齐并发边界测试，形成稳定回归网。

**Non-Goals:**
- 不修改 `PoolEntry` 与 `PoolEntryFactory` 的公共接口。
- 不引入 Netty 或第三方计时器依赖。
- 不重构同步 `borrow` 的三级策略与整体池模型。

## Decisions

### Decision 1: 等待队列改为 `ConcurrentLinkedQueue` + 惰性清理

决策：
- `pendingBorrows` 从 `LinkedBlockingDeque` 替换为 `ConcurrentLinkedQueue`。
- 不再主动 `remove(pending)`；无效节点由 `requite` 轮询时跳过并惰性清理。

理由：
- 避免加锁队列和线性删除导致的热点竞争。
- 与 `CompletableFuture` 一次性完成模型更匹配。

备选方案：
- 保持 `LinkedBlockingDeque` 并微调超时逻辑。
  - 缺点：核心锁竞争问题仍在，高并发尾延迟改善有限。

### Decision 2: 引入等待者原子状态机，统一完成语义

决策：
- 在 `PendingBorrow` 中新增原子状态（`WAITING`, `COMPLETED`, `TIMED_OUT`, `CANCELLED`）。
- `complete/timeout/cancel/close` 统一通过 CAS 竞争状态，只允许一个路径成功完成 future。

理由：
- 明确并发边界，避免重复完成与队列残留。
- 保证取消语义可预测，简化 `requite` 的跳过逻辑。

备选方案：
- 继续依赖 `future.isDone()` 判断。
  - 缺点：状态意图不清晰，难以覆盖复杂竞态和统计。

### Decision 3: `requite` 优先尝试直接交接有效等待者

决策：
- 归还时先轮询等待队列，找到第一个有效等待者并直接完成。
- 若所有等待者无效或完成失败，再回退到同步 handoff / ThreadLocal 缓存。

理由：
- 减少条目在“可借但尚未交接”窗口被并发扫描抢走的概率。
- 提升异步等待场景公平性与稳定性。

备选方案：
- 维持先置回池再通知等待者。
  - 缺点：竞争窗口更大，等待者抖动明显。

### Decision 4: `createAsync` 成功后二次校验 `closed`

决策：
- 异步创建成功回调中检查池关闭状态；若已关闭，立即关闭条目并回滚计数，不加入共享列表。

理由：
- 消除 close/createAsync 竞态导致的“关闭后漏挂条目”。

备选方案：
- close 后等待所有 createAsync 完成再统一清理。
  - 缺点：实现复杂，关闭耗时不可控。

### Decision 5: 扩容 CAS 采用受控重试

决策：
- 对 `totalEntries` 扩容使用有限循环重试，CAS 冲突时优先重试再决定入队等待。

理由：
- 降低瞬时竞争导致的“本可创建却直接排队”。

备选方案：
- 维持单次 CAS。
  - 缺点：高并发下创建机会损失较多。

## Risks / Trade-offs

- [无锁队列可能积累历史无效节点] → 在 `requite` 和超时回调中做惰性推进，增加压力测试验证节点收敛。
- [状态机复杂度上升] → 通过枚举常量与单点状态迁移封装降低维护成本，并补充并发测试。
- [直接交接改变局部调度顺序] → 保持“异步等待优先”策略不变，并验证同步借用不会饥饿。

## Migration Plan

1. 替换等待队列与 `PendingBorrow` 状态机，实现新等待语义。
2. 重构 `enqueueWaiter`、`requite`、`close` 相关路径，统一处理完成/取消/超时。
3. 修复 `createAsync` 关闭竞态与扩容重试逻辑。
4. 补充并发测试并执行 `gateway-pool` 模块测试集。
5. 若出现回归，按提交粒度回退到旧等待逻辑并保留测试用例用于复盘。

## Open Questions

- `borrowAsync` 的调用方是否依赖严格 FIFO，还是只要求“无饥饿”即可？
- 是否需要额外暴露等待队列长度指标用于运行时观测？
