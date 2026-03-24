## 1. 等待队列无锁化与状态机

- [x] 1.1 将 `pendingBorrows` 从 `LinkedBlockingDeque` 替换为 `ConcurrentLinkedQueue`
- [x] 1.2 重构 `PendingBorrow`，引入原子状态并封装 `complete/timeout/cancel/close` 单次完成逻辑
- [x] 1.3 调整 `enqueueWaiter` 超时与取消处理，去除线性 `remove` 依赖并采用惰性清理

## 2. 归还交接与创建竞态修复

- [x] 2.1 重构 `requite`：优先轮询并交接有效异步等待者，失败后回退同步 handoff/ThreadLocal
- [x] 2.2 修复 `createAsync` 成功回调中的关闭竞态，确保关闭后新建条目立即关闭并回滚计数
- [x] 2.3 将异步扩容 CAS 调整为受控重试，减少可创建场景下的过早排队

## 3. 回归测试与验证

- [x] 3.1 新增/调整单元测试：取消先发生、超时与归还并发、关闭取消全部等待者
- [x] 3.2 新增并发场景测试：`close` 与 `createAsync` 竞态下不泄漏条目、计数一致
- [x] 3.3 新增高并发等待队列测试：无丢条目、无永久挂起、等待者可持续被清理
- [x] 3.4 运行 `gateway-pool` 模块测试并修复回归，确认行为与错误语义保持兼容
