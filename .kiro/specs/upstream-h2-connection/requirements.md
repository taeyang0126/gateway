# Requirements Document

## Introduction

将网关到上游服务的连接协议从 HTTP/1.1 全面升级为 HTTP/2（h2c 明文），利用 H2 多路复用能力在单条 TCP 连接上并发多个请求，替代当前每请求独占一个 TCP 连接的 H1 模式。直接替换，不保留 H1 upstream 路径。

核心方案：ConcurrentPool 几乎不改（仅新增 retire()），利用 H2 下"borrow → 自增 streamId + write HEADERS 帧 → 立即 requite"的极短独占时间（微秒级），使同样数量的连接支撑远超 H1 的并发。请求体（DATA 帧）在归还连接后通过保存的 Channel 引用直接写入（Netty Channel.write 线程安全，无需独占）。响应通过 streamId 映射表关联回对应请求。

## Glossary

- **Gateway**: 基于 Netty 的 HTTP 反向代理网关系统
- **ConcurrentPool**: 通用高性能并发资源池（gateway-pool 模块），基于 ConcurrentBag 模式，三级获取策略（ThreadLocal → SharedList CAS → AsyncQueue）。H2 升级后新增 retire() 方法（从池中摘除条目但不关闭资源，由调用方决定关闭时机）
- **ChannelPoolEntry**: 将 Netty Channel 适配为 PoolEntry 的包装类。H2 升级后新增 streamId 自增器和 maxConcurrentStreams 跟踪
- **H2 父 Channel**: 一条到上游服务的 HTTP/2 TCP 连接（h2c 明文，prior knowledge 模式）。连接池 borrow/requite 操作的对象是 H2 父 Channel，不是 stream 子 Channel
- **Stream ID**: HTTP/2 中标识一对请求/响应的整数。客户端发起的 stream ID 为奇数（1, 3, 5, ...），通过 AtomicInteger 自增分配。上限为 2^31 - 1，溢出后该连接必须退役
- **StreamId 映射表**: ConcurrentHashMap<Integer, ProxyHandler>，安装在 H2 父 Channel 上，用于将收到的 H2 响应帧根据 stream ID 路由到对应的 ProxyHandler
- **MAX_CONCURRENT_STREAMS**: H2 SETTINGS 参数，服务端告知客户端单条连接上允许的最大并发 stream 数。borrow 后需检查当前活跃 stream 数是否已达上限，超限则 requite 并重新 borrow 另一条连接
- **Prior_Knowledge**: HTTP/2 的一种建连方式。客户端直接发送 H2 Connection Preface（24 字节），不经过 HTTP/1.1 Upgrade 协商。适用于内网场景
- **GOAWAY**: HTTP/2 协议帧，服务端发送 GOAWAY 通知客户端不再接受新 stream，已有 stream 继续处理
- **RST_STREAM**: HTTP/2 协议帧，用于立即终止单个 stream（不影响同连接上的其他 stream）
- **Connection_Pool_Properties**: 上游连接池配置属性，绑定 `gateway.connection-pool` 前缀。保留现有字段，不新增 H2 专用字段
- **Proxy_Handler**: 代理转发处理器。H2 下：borrow 连接 → 检查 MAX_CONCURRENT_STREAMS → 自增 streamId → 注册映射 → write HEADERS 帧 → 立即 requite 归还连接 → 后续 DATA 帧通过保存的 Channel 引用直接写入 → 响应通过 streamId 关联回来
- **H2ResponseDemuxHandler**: 安装在 H2 父 Channel pipeline 上的响应分发器（非 @Sharable，每连接一个实例），收到 H2 响应帧时根据 stream ID 查映射表找到对应 ProxyHandler 并转发。同时跟踪 MAX_CONCURRENT_STREAMS 和活跃 stream 计数

## Requirements

### Requirement 1: H2 上游连接建立

**User Story:** As a 网关系统, I want to 使用 HTTP/2 h2c 明文协议连接上游服务, so that 可以在单条 TCP 连接上多路复用多个请求。

#### Acceptance Criteria

1. WHEN 需要建立到某 host:port 的新连接时，THE ChannelPoolEntryFactory SHALL 通过 Netty `Http2FrameCodec` + Prior_Knowledge 模式建立 h2c 明文连接，直接发送 H2 Connection Preface，不走 HTTP/1.1 Upgrade 协商
2. WHEN H2 连接建立成功时，THE ChannelPoolEntryFactory SHALL 在 H2 父 Channel pipeline 中配置 Http2FrameCodec 和 H2ResponseDemuxHandler（不使用 Http2MultiplexHandler）
3. WHEN H2 连接建立失败时，THE ChannelPoolEntryFactory SHALL 将异常传播给等待该连接的请求方，不做静默吞没

### Requirement 2: ConcurrentPool 极短独占与 retire 支持

**User Story:** As a 网关系统, I want to 通过缩短 H2 下连接的独占时间来实现高并发，并支持"从池中摘除但延迟关闭"的退役语义, so that 同样数量的连接可以支撑远超 H1 的并发请求，且 GOAWAY 场景下存量 stream 不被中断。

#### 背景说明

博客作者 dreamlike-ocean 的方案：ConcurrentPool 的 CAS 独占逻辑、三级获取策略、PoolEntry 状态模型全部保留。H2 下连接的独占时间从"整个请求生命周期"（H1，几百毫秒）缩短到"自增 streamId + write HEADERS 帧"（H2，微秒级）。写完 HEADERS 后立即 requite 归还连接，后续 DATA 帧通过保存的 Channel 引用直接写入（Netty Channel.write 线程安全），响应通过 streamId 关联回对应请求。

新增 retire() 的原因：现有 `remove()` 会在从池中摘除后立即调用 `entry.close()` 关闭连接。但 GOAWAY 场景下，连接上可能还有正在飞行的 stream，不能立即关闭。`retire()` 只从池中摘除（CAS 状态为 REMOVED、从 sharedList/ThreadLocal 移除、totalEntries 减一），不调用 `entry.close()`，由调用方在存量 stream 全部完成后再关闭连接。这是通用的池操作语义（"退役但延迟关闭"），不是 H2 专用逻辑。

#### Acceptance Criteria

1. THE ConcurrentPool SHALL 保持现有 CAS 独占逻辑、三级获取策略（ThreadLocal → SharedList CAS → AsyncQueue）、PoolEntry 状态模型（NOT_IN_USE / IN_USE / REMOVED）不变
2. THE ConcurrentPool SHALL 新增 `retire(T entry)` 方法：CAS 状态为 REMOVED → 从 sharedList 移除 → 从 ThreadLocal 缓存移除 → totalEntries 减一，但不调用 `entry.close()`
3. THE PoolEntry 和 PoolConfig SHALL 保持零改动
4. THE UpstreamConnectionPool 的 acquire() SHALL 返回 H2 父 Channel（不是 stream 子 Channel），borrow 语义不变
5. THE UpstreamConnectionPool 的 release() SHALL 调用 ConcurrentPool.requite() 归还连接，语义不变
6. THE UpstreamConnectionPool 的 remove() SHALL 调用 ConcurrentPool.remove() 移除并关闭连接，语义不变
7. THE UpstreamConnectionPool SHALL 新增 `retire(Channel)` 方法，调用 ConcurrentPool.retire() 从池中摘除连接但不关闭，由调用方决定关闭时机

### Requirement 3: StreamId 分配与响应关联

**User Story:** As a 网关系统, I want to 通过 streamId 自增和映射表将 H2 响应关联回对应的请求, so that 多个请求可以共享同一条 H2 连接而互不干扰。

#### Acceptance Criteria

1. THE ChannelPoolEntry SHALL 维护一个 AtomicInteger streamId 自增器，客户端 stream ID 为奇数（1, 3, 5, ...），每次调用返回下一个奇数
2. WHEN streamId 自增后超过 Integer.MAX_VALUE（2^31 - 1）时，THE ChannelPoolEntry.nextStreamId() SHALL 返回 -1 表示溢出，调用方收到 -1 后 SHALL 触发 retire() 退役该连接并重新 borrow 另一条
3. THE H2ResponseDemuxHandler SHALL 维护 ConcurrentHashMap<Integer, ProxyHandler> 映射表，安装在 H2 父 Channel pipeline 上
4. WHEN ProxyHandler 发送请求时，SHALL 先自增 streamId → 注册 streamId 到映射表 → write H2 HEADERS 帧（帧中携带该 streamId）→ 立即 requite 归还连接 → 后续 DATA 帧通过保存的 Channel 引用直接写入
5. WHEN H2 父 Channel 收到响应帧时，THE H2ResponseDemuxHandler SHALL 根据帧的 stream ID 查映射表找到对应的 ProxyHandler，将响应转发给它
6. WHEN 响应接收完毕（收到带 END_STREAM 的帧）时，THE H2ResponseDemuxHandler SHALL 从映射表中移除该 streamId 条目

### Requirement 4: MAX_CONCURRENT_STREAMS 流控

**User Story:** As a 网关系统, I want to 遵守上游服务通告的 MAX_CONCURRENT_STREAMS 限制, so that 不会因超限导致上游发送 RST_STREAM（REFUSED_STREAM）或 GOAWAY。

#### 背景说明

H2 规范中，服务端通过 SETTINGS 帧告知 MAX_CONCURRENT_STREAMS（Nginx 默认 128，Go 默认 250）。如果客户端超过此限制发送新 stream，上游会拒绝。本方案在 ProxyHandler 层做检查（borrow 后判断），而非修改 ConcurrentPool 的 borrow 逻辑，保持池的通用性。

#### Acceptance Criteria

1. THE H2ResponseDemuxHandler SHALL 从上游 SETTINGS 帧中解析并记录 MAX_CONCURRENT_STREAMS 值，默认值为 Integer.MAX_VALUE（H2 规范默认无限制）
2. THE H2ResponseDemuxHandler SHALL 维护 activeStreamCount（AtomicInteger），register 时 +1，remove 时 -1
3. WHEN ProxyHandler borrow 到一条连接后，SHALL 检查该连接的 activeStreamCount < maxConcurrentStreams；如果不满足，SHALL requite 归还该连接并重新 borrow 下一条
4. WHEN 连续重试超过上限（3 次）仍未找到可用连接时，SHALL 返回 503 Service Unavailable
5. THE 流控逻辑 SHALL 全部在 gateway-core 的 ProxyHandler 层实现，gateway-pool 不感知 MAX_CONCURRENT_STREAMS

### Requirement 5: H1 ↔ H2 协议转换

**User Story:** As a 网关系统, I want to 在入站 H1 和出站 H2 之间进行协议转换, so that 客户端无需感知上游使用 H2。

#### Acceptance Criteria

1. THE ProxyHandler SHALL 将客户端 H1 请求头转换为 H2 HEADERS 帧写入 H2 父 Channel，包含伪头部（:method, :path, :scheme, :authority）并去除 hop-by-hop 头（Connection, Transfer-Encoding, Keep-Alive 等）
2. THE ProxyHandler SHALL 在 requite 归还连接后，通过保存的 Channel 引用将后续到达的 H1 HttpContent 逐块转换为 H2 DATA 帧写入，LastHttpContent 对应的 DATA 帧带 END_STREAM 标志
3. THE H2ResponseDemuxHandler SHALL 将 Http2HeadersFrame 转换为 H1 DefaultHttpResponse（:status → 状态码，去除伪头部），将 Http2DataFrame 转换为 H1 DefaultHttpContent / DefaultLastHttpContent
4. THE 协议转换 SHALL 正确处理无 body 请求（HEADERS 帧直接带 END_STREAM）和有 body 请求（HEADERS + DATA 帧序列）

### Requirement 6: H2 协议事件处理

**User Story:** As a 网关系统, I want to 正确处理 H2 协议级事件（GOAWAY、RST_STREAM、连接断开）, so that 连接池状态与实际连接状态保持一致。

#### Acceptance Criteria

1. WHEN 上游服务发送 GOAWAY 帧时，THE H2ResponseDemuxHandler SHALL 触发 UpstreamConnectionPool.retire() 从池中摘除该连接（不关闭），streamId ≤ lastStreamId 的存量 stream 继续处理，streamId > lastStreamId 的 stream 触发 502，映射表清空后再关闭连接
2. WHEN H2 连接意外断开时，THE H2ResponseDemuxHandler SHALL 对该连接映射表中所有未完成的 stream 触发错误处理（返回 502 Bad Gateway），并清空映射表
3. IF 上游发送 RST_STREAM 帧重置某个 stream，THEN THE H2ResponseDemuxHandler SHALL 根据 stream ID 找到对应 ProxyHandler，返回 HTTP 502 Bad Gateway，并从映射表移除
4. WHEN 客户端在请求处理过程中断开连接时，THE ProxyHandler SHALL 从映射表中移除对应 streamId 条目，上游响应到达后因映射表无对应条目而丢弃

### Requirement 7: 停机兼容

**User Story:** As a 网关系统, I want to 优雅停机流程能正确处理 H2 连接上的在途 stream, so that 停机时在途请求不被中断。

#### Acceptance Criteria

1. WHEN 优雅停机流程触发时，THE ShutdownCoordinator SHALL 遍历所有连接池中的 ChannelPoolEntry，通过其 Channel 上的 H2ResponseDemuxHandler 检查 hasActiveStreams()，等待所有映射表清空后再关闭连接
2. WHEN 停机超时到达且仍有未完成的 stream 时，THE ShutdownCoordinator SHALL 强制关闭连接

### Requirement 8: 连接池配置

**User Story:** As a 运维人员, I want to 通过现有配置项控制 H2 连接池行为, so that 无需新增 H2 专用配置。

#### Acceptance Criteria

1. THE ConnectionPoolProperties SHALL 保留现有字段（maxConnectionsPerHost、maxIdleTimeSeconds、connectTimeoutMillis、threadLocalCacheSize），不新增 H2 专用字段
2. THE maxConnectionsPerHost 默认值 SHALL 从 50 改为 5（H2 多路复用下 5 条连接即可支撑高并发，运维可按需调整）

### Requirement 9: gateway-example 启用 h2c

**User Story:** As a 开发者, I want to gateway-example 支持 h2c 明文 HTTP/2, so that 本地开发测试时网关可以通过 H2 连接到 mock 上游服务。

#### Acceptance Criteria

1. THE gateway-example SHALL 在 application.yml 中配置 `server.http2.enabled: true` 启用 h2c 支持
2. WHEN 网关以 Prior_Knowledge 模式发起 H2 连接时，THE gateway-example SHALL 正确接受并处理 H2 请求
