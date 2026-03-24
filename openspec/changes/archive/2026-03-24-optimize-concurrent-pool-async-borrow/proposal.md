## Why

`ConcurrentPool.borrowAsync` 当前已具备异步能力，但在高并发下仍存在等待队列锁竞争与若干并发边界问题（取消残留节点、close 与 createAsync 竞态）。这些问题会影响吞吐、尾延迟和资源安全，需要在保持语义一致的前提下优化为更稳定的无锁等待路径。

## What Changes

- 将异步等待队列从 `LinkedBlockingDeque` 改为无锁 `ConcurrentLinkedQueue`，移除线性 `remove` 依赖，改为惰性清理。
- 重构异步等待者状态机（等待中/已完成/已取消），确保超时、取消、close、requite 并发下仅一次生效。
- 优化 `requite` 交接策略：优先尝试把归还条目直接交给有效异步等待者，减少“先回池再被抢占”的窗口。
- 修复 `close` 与 `createAsync` 并发竞态：池关闭后异步创建成功的条目立即关闭并回滚计数，避免资源泄漏。
- 优化创建容量竞争路径：`totalEntries` 扩容改为受控重试，降低 CAS 单次失败后过早入队概率。
- 补充并发测试：取消清理、close/createAsync 竞态、等待队列高并发公平性与不丢连接验证。

## Capabilities

### New Capabilities
- `gateway-pool-async-borrow-optimization`: 连接池异步借用等待路径的无锁化与并发安全增强能力。

### Modified Capabilities
- 无（当前 `openspec/specs/` 下无既有能力规格）

## Impact

- Affected code:
- `gateway-pool/src/main/java/com/lei/gateway/pool/ConcurrentPool.java`
- `gateway-pool/src/test/java/com/lei/gateway/pool/ConcurrentPoolTest.java`
- `gateway-pool/src/test/java/com/lei/gateway/pool/ConcurrentPoolPropertyTest.java`

- Affected APIs/behavior:
- `borrowAsync`、`requite`、`close` 的外部方法签名不变。
- 超时与关闭错误语义保持现有约定（Timeout / Pool is closed），并增强取消与竞态场景一致性。

- Affected dependencies/systems:
- 不新增外部依赖；仅使用 JDK 并发集合与原子状态控制。
