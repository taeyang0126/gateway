# Implementation Plan: upstream-h2-connection

## Overview

将网关到上游服务的连接协议从 HTTP/1.1 升级为 HTTP/2（h2c 明文）。改动按依赖顺序编排：先改 gateway-pool（新增 retire），再改 gateway-core 的底层组件（ChannelPoolEntry、ChannelPoolEntryFactory、H2ResponseDemuxHandler），然后改写 ProxyHandler 和 UpstreamConnectionPool，最后处理 ShutdownCoordinator 和 gateway-example。每步都有对应的测试子任务，确保增量可验证。

## Tasks

- [x] 1. ConcurrentPool 新增 retire() 方法
  - [x] 1.1 在 `gateway-pool/.../ConcurrentPool.java` 中新增 `retire(T entry)` 方法
    - CAS 状态为 REMOVED → 从 sharedList 移除 → 从 ThreadLocal 缓存移除 → totalEntries 减一
    - 与 `remove()` 的唯一区别：不调用 `entry.close()`
    - _Requirements: 2.2_
  - [x] 1.2 编写 ConcurrentPoolRetireTest 单元测试
    - 验证 retire() 的 CAS 状态转换（NOT_IN_USE→REMOVED、IN_USE→REMOVED）
    - 验证从 sharedList 和 ThreadLocal 移除、totalEntries 减一
    - 验证 retire() 后 entry.close() 未被调用（资源未关闭）
    - 验证 retire() 后该 entry 不会被 borrowAsync() 获取到
    - 测试文件：`gateway-pool/src/test/java/com/lei/gateway/pool/ConcurrentPoolRetireTest.java`
    - _Requirements: 2.2, 2.3_

- [x] 2. ChannelPoolEntry 新增 streamId 自增器
  - [x] 2.1 在 `gateway-core/.../proxy/ChannelPoolEntry.java` 中新增 `nextStreamId()` 方法
    - 新增 `AtomicInteger streamIdGenerator`，初始值 -1，每次 `addAndGet(2)` 得到奇数序列 1, 3, 5, ...
    - 溢出（结果为负数）时返回 -1
    - _Requirements: 3.1, 3.2_
  - [x] 2.2 编写 ChannelPoolEntryPropertyTest 属性测试
    - **Property 1: StreamId 不变量——始终为正奇数且严格递增**
    - **Validates: Requirements 3.1, 3.2**
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/proxy/ChannelPoolEntryPropertyTest.java`
  - [ ]* 2.3 编写 ChannelPoolEntry 的 nextStreamId 单元测试
    - 验证初始值为 1、连续调用递增、溢出边界（从接近 MAX_VALUE 开始）
    - 在现有 `ChannelPoolEntryTest.java` 中追加（如不存在则新建）
    - _Requirements: 3.1, 3.2_

- [x] 3. Checkpoint - 确保 gateway-pool 和 ChannelPoolEntry 测试通过
  - 运行 `mvn test -pl gateway-pool` 和 `mvn test -pl gateway-core -Dtest=ChannelPoolEntry*`，确保所有测试通过，有问题询问用户。

- [x] 4. 新增 H2ResponseDemuxHandler
  - [x] 4.1 创建 `gateway-core/.../proxy/H2ResponseDemuxHandler.java`
    - 继承 `ChannelInboundHandlerAdapter`，非 @Sharable，每连接一个实例
    - 维护 `ConcurrentHashMap<Integer, ProxyHandler>` 映射表
    - 维护 `maxConcurrentStreams`（volatile int，默认 Integer.MAX_VALUE）和 `activeStreamCount`（AtomicInteger）
    - 实现 `canCreateStream()`、`register(int, ProxyHandler)`、`remove(int)`、`hasActiveStreams()`、`activeStreamCount()`
    - 处理 `Http2SettingsFrame`：解析并更新 maxConcurrentStreams
    - 处理 `Http2HeadersFrame`：查映射表 → 转换为 DefaultHttpResponse → 调用 ProxyHandler 回调；END_STREAM 时发送 LastHttpContent 并 remove
    - 处理 `Http2DataFrame`：查映射表 → 转换为 DefaultHttpContent/DefaultLastHttpContent → 调用 ProxyHandler 回调；END_STREAM 时 remove
    - 处理 `Http2ResetFrame`：查映射表 → 调用 ProxyHandler.onH2Error() → remove
    - 处理 `Http2GoAwayFrame`：标记 goawayReceived → retire 连接 → streamId > lastStreamId 的触发 502 → 映射表空时关闭连接
    - 实现 `channelInactive()`：遍历映射表对所有 stream 触发 onH2Error → 清空映射表
    - _Requirements: 3.3, 3.5, 3.6, 4.1, 4.2, 5.3, 5.4, 6.1, 6.2, 6.3_

  - [x] 4.2 编写 H2ResponseDemuxHandlerPropertyTest 属性测试
    - **Property 2: Stream 生命周期——注册、路由、移除与流控计数**
    - **Validates: Requirements 3.5, 3.6, 4.1, 4.2**
    - **Property 5: 连接断开时映射表全清理**
    - **Validates: Requirements 6.2**
    - **Property 6: GOAWAY 后 streamId 分区处理**
    - **Validates: Requirements 6.1**
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/proxy/H2ResponseDemuxHandlerPropertyTest.java`
  - [x] 4.3 编写 H2ResponseDemuxHandlerTest 单元测试
    - 验证 SETTINGS 帧更新 maxConcurrentStreams
    - 验证 GOAWAY 处理（含 lastStreamId 分区、存量 stream 继续、映射表空后关闭）
    - 验证 RST_STREAM 处理（查映射表 → onH2Error → remove）
    - 验证未知 streamId 的帧被忽略/丢弃
    - 验证 canCreateStream() 阈值判断
    - 验证 channelInactive 时所有 stream 收到 onH2Error
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/proxy/H2ResponseDemuxHandlerTest.java`
    - _Requirements: 3.5, 3.6, 4.1, 4.2, 6.1, 6.2, 6.3_

- [x] 5. 重写 ChannelPoolEntryFactory 为 H2 pipeline
  - [x] 5.1 重写 `gateway-core/.../proxy/ChannelPoolEntryFactory.java`
    - 将 H1 pipeline（HttpClientCodec + UpstreamResponseHandler）替换为 H2 pipeline
    - 配置 `Http2FrameCodecBuilder.forClient()` + `H2ResponseDemuxHandler`
    - 不使用 Http2MultiplexHandler
    - Prior Knowledge 模式（h2c 明文），直接发送 H2 Connection Preface
    - H2ResponseDemuxHandler 需要 UpstreamConnectionPool 引用，调整构造函数传参
    - _Requirements: 1.1, 1.2, 1.3_

- [x] 6. H1 ↔ H2 协议转换工具方法
  - [x] 6.1 在 ProxyHandler 或独立工具类中实现 H1→H2 请求头转换
    - HttpRequest → Http2Headers：生成伪头部（:method, :path, :scheme, :authority）
    - 去除 hop-by-hop 头（Connection, Transfer-Encoding, Keep-Alive, Proxy-Connection, Upgrade）
    - 普通头部直接映射
    - _Requirements: 5.1_
  - [x] 6.2 在 H2ResponseDemuxHandler 中实现 H2→H1 响应头转换
    - Http2HeadersFrame → DefaultHttpResponse：:status → 状态码，去除伪头部
    - Http2DataFrame → DefaultHttpContent / DefaultLastHttpContent
    - _Requirements: 5.3_
  - [x] 6.3 编写 H1ToH2ConversionPropertyTest 属性测试
    - **Property 3: H1 → H2 请求头转换保留语义**
    - **Validates: Requirements 5.1**
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/proxy/H1ToH2ConversionPropertyTest.java`
  - [x] 6.4 编写 H2ToH1ConversionPropertyTest 属性测试
    - **Property 4: H2 → H1 响应头转换保留语义**
    - **Validates: Requirements 5.3**
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/proxy/H2ToH1ConversionPropertyTest.java`

- [x] 7. Checkpoint - 确保新增组件编译通过且单元测试通过
  - 运行 `mvn test -pl gateway-core -Dexclude="**/*IntegrationTest.java"`，确保所有测试通过，有问题询问用户。

- [x] 8. 改写 ProxyHandler 为 H2 模式
  - [x] 8.1 改写 `gateway-core/.../proxy/ProxyHandler.java` 的请求转发逻辑
    - 移除 UpstreamResponseHandler 相关逻辑（H2 下响应通过映射表回来，不再通过 pipeline handler）
    - 新增字段：streamId、h2Channel、demuxHandler、clientCtx、acquireRetryCount
    - 改写 `onAcquireComplete()`：borrow → 流控检查（canCreateStream，超限 requite 重试，上限 3 次返回 503）→ nextStreamId（溢出 retire 重试）→ register 映射 → write HEADERS 帧 → 立即 requite
    - 改写 `channelRead()` 中 HttpContent 处理：requite 后通过 h2Channel 引用将 HttpContent 转换为 H2 DATA 帧写入
    - 无 body 请求：HEADERS 帧直接带 END_STREAM
    - 有 body 请求：HEADERS 帧不带 END_STREAM，后续 DATA 帧序列，LastHttpContent 对应 END_STREAM
    - 新增回调方法：`onH2Response(HttpResponse)`、`onH2Content(HttpContent)`、`onH2Error(Throwable)`
    - 客户端断开时从映射表移除 streamId
    - _Requirements: 3.4, 4.3, 4.4, 4.5, 5.1, 5.2, 5.4, 6.4_
  - [x]* 8.2 编写 ProxyHandler H2 模式单元测试
    - 更新现有 `ProxyHandlerTest.java`
    - 验证 borrow→流控检查→write HEADERS→requite 流程
    - 验证 MAX_CONCURRENT_STREAMS 超限重试（最多 3 次后返回 503）
    - 验证 streamId 溢出时 retire 并重新 borrow
    - 验证 DATA 帧异步写入（requite 后通过 Channel 引用写入）
    - 验证客户端断开时从映射表移除 streamId
    - 验证无 body 请求 HEADERS 帧带 END_STREAM
    - _Requirements: 3.4, 4.3, 4.4, 5.1, 5.2, 5.4, 6.4_

- [x] 9. 适配 UpstreamConnectionPool
  - [x] 9.1 修改 `gateway-core/.../proxy/UpstreamConnectionPool.java`
    - acquire() 返回 H2 父 Channel（语义不变）
    - release() 调用 requite 归还（不再需要移除 pipeline handler）
    - remove() 调用 remove 移除并关闭
    - 新增 `retire(Channel)` 方法：通过 Channel Attribute 反查 PoolEntry → 调用 ConcurrentPool.retire()
    - ChannelPoolEntryFactory 构造时传入 UpstreamConnectionPool 引用（供 H2ResponseDemuxHandler 使用）
    - _Requirements: 2.4, 2.5, 2.6, 2.7_
  - [x] 9.2 更新 UpstreamConnectionPoolTest 单元测试
    - 验证 retire() 方法的正确性
    - 验证 release() 不再移除 pipeline handler
    - _Requirements: 2.4, 2.5, 2.6, 2.7_

- [x] 10. 修改 ConnectionPoolProperties 默认值
  - 将 `maxConnectionsPerHost` 默认值从 50 改为 5
  - 文件：`gateway-core/.../config/ConnectionPoolProperties.java`
  - _Requirements: 8.1, 8.2_

- [x] 11. Checkpoint - 确保 ProxyHandler 和连接池改动测试通过
  - 运行 `mvn test -pl gateway-core -Dexclude="**/*IntegrationTest.java"`，确保所有测试通过，有问题询问用户。
  - Done: 217 tests run, 4 failures (all UpstreamResponseHandlerTest — expected, task 14 will delete). ObservabilityPropertiesDefaultTest fixed (50→5). All H2 tests pass.

- [x] 12. 适配 ShutdownCoordinator 优雅停机
  - [x] 12.1 修改 `gateway-core/.../proxy/ShutdownCoordinator.java`
    - 在阶段3（等待在途请求完成）中，额外遍历所有连接池的 ChannelPoolEntry
    - 通过 Channel pipeline 获取 H2ResponseDemuxHandler，调用 `hasActiveStreams()` 检查
    - 等待所有映射表清空后再进入阶段4关闭连接
    - 停机超时到达且仍有未完成 stream 时强制关闭连接
    - _Requirements: 7.1, 7.2_
  - [x] 12.2 更新 ShutdownCoordinatorTest 单元测试
    - 验证停机时等待 H2 活跃 stream 完成
    - 验证超时后强制关闭
    - _Requirements: 7.1, 7.2_

- [x] 13. gateway-example 启用 h2c
  - 在 `gateway-example/src/main/resources/application.yml` 中添加 `server.http2.enabled: true`
  - _Requirements: 9.1, 9.2_

- [x] 14. 删除 UpstreamResponseHandler
  - 删除 `gateway-core/.../proxy/UpstreamResponseHandler.java`（H2 下响应通过 H2ResponseDemuxHandler 映射表回来，不再需要）
  - 删除对应的 `UpstreamResponseHandlerTest.java`
  - 清理所有引用该类的 import 和代码
  - _Requirements: 1.2, 3.5_

- [x] 15. Checkpoint - 完整构建验证
  - 运行 `mvn clean verify -T 1C -U`，确保编译、Checkstyle、单元测试、属性测试全部通过，有问题询问用户。
  - Done: 编译、Checkstyle、forbidden-apis 全部通过。217 个单元测试/属性测试全部通过。集成测试 26 个失败（MockUpstreamServer 不支持 h2c，Task 16 解决）。JaCoCo 覆盖率 0.77 < 0.80（集成测试排除后下降，Task 16 完成后恢复）。

- [x] 16. 集成测试
  - [x] 16.1 更新 MockUpstreamServer 支持 h2c
    - 在 `gateway-core/.../integration/MockUpstreamServer.java` 中新增 h2c 支持
    - 配置 Http2FrameCodec + 响应逻辑，支持 Prior Knowledge 模式
    - _Requirements: 1.1_
  - [x] 16.2 编写 H2UpstreamIntegrationTest
    - 端到端 H1→H2→H1 转发验证
    - 多并发请求验证（同一连接上多路复用）
    - GOAWAY 处理验证（存量 stream 完成后关闭）
    - RST_STREAM 处理验证
    - 连接断开恢复验证
    - 大 body chunked 转发验证
    - 无 body 请求验证
    - 测试文件：`gateway-core/src/test/java/com/lei/gateway/core/integration/H2UpstreamIntegrationTest.java`
    - _Requirements: 1.1, 1.2, 3.4, 5.1, 5.2, 5.3, 5.4, 6.1, 6.2, 6.3_

- [x] 17. Final checkpoint - 确保所有测试通过
  - 运行 `mvn clean verify -T 1C -U`，确保全部通过，有问题询问用户。

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- 改动模块顺序：gateway-pool → gateway-core（ChannelPoolEntry → H2ResponseDemuxHandler → ChannelPoolEntryFactory → ProxyHandler → UpstreamConnectionPool → ShutdownCoordinator）→ gateway-example
- 属性测试使用 jqwik，每个属性测试最少 100 次迭代
- 所有测试类须线程安全（surefire parallel=classes, threadCount=4）
- UpstreamResponseHandler 在 H2 模式下不再需要，任务 14 负责清理
