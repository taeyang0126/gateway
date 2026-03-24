## Purpose
定义 gateway-pool-async-borrow-optimization 能力相关需求。

## Requirements

### Requirement: Async Borrow Wait Path SHALL Be Lock-Free
`ConcurrentPool.borrowAsync` 在进入等待阶段时，系统 MUST 使用无锁并发队列维护等待者，且 MUST 避免对等待队列执行线性删除作为主路径。等待者清理 MUST 通过状态标记和惰性跳过完成。

#### Scenario: Waiter enqueued under high contention
- **WHEN** 多个线程并发调用 `borrowAsync` 且池已满
- **THEN** 每个请求 MUST 以无锁方式入队并返回未完成的 `CompletableFuture`
- **THEN** 等待路径 MUST 不依赖阻塞锁保护队列

#### Scenario: Timed out waiter cleanup
- **WHEN** 等待者超时后其 future 已完成异常
- **THEN** 后续 `requite` 轮询 MUST 跳过该等待者且不影响可用条目继续分配

### Requirement: Pending Borrow Completion SHALL Be Single-Winner
每个异步等待者 MUST 具备可并发竞争的单次完成语义。`requite` 完成、超时任务、调用方取消、池关闭之间 MUST 通过原子状态竞争保证只有一个路径完成 future。

#### Scenario: Timeout races with requite
- **WHEN** 等待者超时触发与 `requite` 交接同时发生
- **THEN** 系统 MUST 仅有一个路径成功完成该 future
- **THEN** 未获胜路径 MUST 不得重复完成或造成条目丢失

#### Scenario: Caller cancels before timeout
- **WHEN** 调用方主动取消 `borrowAsync` 返回的 future
- **THEN** 等待者 MUST 变为不可交接状态
- **THEN** 后续 `requite` MUST 跳过该等待者并继续寻找下一个有效等待者

### Requirement: Requite SHALL Prefer Direct Handoff To Async Waiters
归还条目时，系统 MUST 优先尝试交接给有效异步等待者；仅在无有效等待者时，才可回退到同步 handoff 与 ThreadLocal 缓存路径。

#### Scenario: Async waiter exists when entry requited
- **WHEN** 池中存在至少一个有效异步等待者且有条目被 `requite`
- **THEN** 该条目 MUST 优先用于完成某个有效等待者的 future
- **THEN** 完成后条目状态 MUST 为 `IN_USE`

#### Scenario: No valid async waiter remains
- **WHEN** 等待队列中节点均已超时或取消
- **THEN** `requite` MUST 回退到既有同步 handoff/本地缓存逻辑
- **THEN** 条目 MUST 保持可再次借用，不得丢失

### Requirement: Pool Close SHALL Not Leak Async-Created Entries
池关闭与异步创建并发时，系统 MUST 保证关闭后新完成的异步创建条目不会进入共享池并会被立即关闭。

#### Scenario: createAsync completes after pool close
- **WHEN** `close` 已执行完成后，先前发起的 `createAsync` 回调才返回条目
- **THEN** 该条目 MUST 被立即关闭
- **THEN** `totalEntries` MUST 回滚到正确值

#### Scenario: close cancels waiting borrows
- **WHEN** 池中仍存在异步等待者且执行 `close`
- **THEN** 所有等待 future MUST 以 “Pool is closed” 异常完成
- **THEN** 不得有等待 future 永久挂起

### Requirement: Capacity Expansion SHALL Retry Before Waiting
在池未达到 `maxPoolSize` 时，异步借用路径 MUST 对扩容 CAS 冲突执行受控重试；仅在确认无法扩容后才入等待队列。

#### Scenario: CAS conflict while pool not full
- **WHEN** 并发扩容导致单次 CAS 失败且当前总量仍小于 `maxPoolSize`
- **THEN** 系统 MUST 进行受控重试以争取创建机会
- **THEN** 不得在首次 CAS 失败后直接进入等待队列
