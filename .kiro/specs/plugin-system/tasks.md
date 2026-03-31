# Implementation Plan: 插件系统

## Overview

将 netty-gateway 的安全过滤体系重构为通用插件系统。按依赖顺序实现：基础类型 → Plugin 接口 → 执行链/注册表/配置解析 → GatewayPluginProcessor → 安全插件迁移 → RoutingHandler 集成 → 删除旧类。所有代码使用 Java，插件放在 `com.lei.gateway.core.plugin` 包，配置类放在 `com.lei.gateway.core.config`。

## Tasks

- [-] 1. 实现插件框架基础类型和 Plugin 接口
  - [x] 1.1 创建 PluginPhase 枚举、PluginResultType 枚举、PluginResult 类
    - 在 `com.lei.gateway.core.plugin` 包创建 `PluginPhase`（REQUEST/PROXY/RESPONSE/ERROR）
    - 创建 `PluginResultType`（CONTINUE/SHORT_CIRCUIT/ERROR）
    - 创建 `PluginResult`，含 type、status、body、pluginName、reason、retryAfterSeconds 字段和工厂方法（doContinue/shortCircuit/error）
    - _Requirements: 2.1, 3.1_

  - [x] 1.2 创建 PluginConfig 和 PluginContext
    - 创建 `PluginConfig`（pluginName、enabled、priority、config Map）
    - 创建 `PluginContext`（channelHandlerContext、request、route、traceId、clientIp、userId、attributes HashMap、traceTags HashMap），含 setAttribute/getAttribute/putTraceTag 方法
    - _Requirements: 4.1, 4.2, 4.3, 4.4_

  - [x] 1.3 创建 Plugin 接口和 PluginConfigEntry 配置类
    - 在 `com.lei.gateway.core.plugin` 包创建 `Plugin` 接口（name/phase/defaultPriority/execute）
    - 在 `com.lei.gateway.core.config` 包创建 `PluginConfigEntry`（name、enabled Boolean 默认 true、priority Integer、config Map），含 getter/setter
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 5.6_

  - [x] 1.4 编写 PluginPhase、PluginResultType、PluginResult、PluginContext 单元测试
    - 验证 PluginPhase 恰好 4 个值
    - 验证 PluginResult 工厂方法（含 pluginName/reason/retryAfterSeconds 字段）
    - 验证 PluginContext 构造和字段访问
    - 验证 PluginConfigEntry 默认值（enabled 默认 true）
    - _Requirements: 2.1, 3.1, 4.1, 5.6_

  - [x] 1.5 编写 PluginContext 属性共享属性测试
    - **Property 4: PluginContext 属性在插件间共享**
    - **Validates: Requirements 4.2, 4.3, 4.4**

- [x] 2. 实现 PluginChain、PluginRegistry、PluginConfigResolver
  - [x] 2.1 实现 PluginChain 执行链
    - 在 `com.lei.gateway.core.plugin` 包创建 `PluginChain`，注入 MetricsCollector
    - 实现 execute 方法：遍历已排序插件列表，每个插件记录耗时和决策指标，CONTINUE 继续、SHORT_CIRCUIT 立即返回、异常捕获返回 ERROR(500)
    - 新增 MetricsCollector.recordPluginDuration 和 recordPluginDecision 方法
    - _Requirements: 1.5, 3.2, 3.3, 3.4, 3.5, 8.4_

  - [x] 2.2 编写 PluginChain 优先级执行属性测试
    - **Property 1: 插件链按优先级执行且全 CONTINUE 正确完成**
    - **Validates: Requirements 1.5, 3.2, 3.5, 6.4**

  - [x] 2.3 编写 PluginChain SHORT_CIRCUIT 属性测试
    - **Property 2: SHORT_CIRCUIT 终止后续插件执行**
    - **Validates: Requirements 3.3**

  - [x] 2.4 编写 PluginChain 异常处理属性测试
    - **Property 3: 插件异常产生 ERROR 结果**
    - **Validates: Requirements 3.4**

  - [x] 2.5 实现 PluginRegistry 注册表
    - 在 `com.lei.gateway.core.plugin` 包创建 `PluginRegistry`，内部 HashMap<String, Plugin>
    - 实现 register（同名抛 IllegalStateException）、find（返回 Optional）、discoverAndRegister（批量注册 Plugin Bean 列表）
    - _Requirements: 7.1, 7.2, 7.3, 7.4_

  - [x] 2.6 编写 PluginRegistry 注册查找属性测试
    - **Property 6: PluginRegistry 注册查找往返**
    - **Validates: Requirements 7.1**

  - [x] 2.7 编写 PluginRegistry 拒绝重复名称属性测试
    - **Property 7: PluginRegistry 拒绝重复名称**
    - **Validates: Requirements 7.4**

  - [x] 2.8 实现 PluginConfigResolver 配置解析器
    - 在 `com.lei.gateway.core.plugin` 包创建 `PluginConfigResolver`，注入 PluginRegistry 和 SecurityProperties
    - 实现 resolve 方法：合并全局 + 路由插件配置，按 phase 分组、按 priority 排序
    - 合并规则：全局为基础，路由同名覆盖，路由新增追加，enabled=false 排除
    - _Requirements: 5.2, 5.4, 5.5, 6.1, 6.2, 6.3, 6.4_

  - [x] 2.9 编写 PluginConfigResolver 合并正确性属性测试
    - **Property 5: 插件配置合并正确性**
    - **Validates: Requirements 5.2, 5.4, 5.5, 6.1, 6.4**

  - [x] 2.10 编写 PluginConfigResolver 单元测试
    - 路由无插件配置时使用全局配置
    - 路由 enabled:false 排除全局插件
    - _Requirements: 6.2, 6.3_

- [x] 3. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. 实现 GatewayPluginProcessor 和 PluginExecutionResult
  - [x] 4.1 创建 PluginExecutionResult 和 GatewayPluginProcessor
    - 创建 `PluginExecutionResult`（pluginResult、pluginContext、isContinue 委托）
    - 创建 `GatewayPluginProcessor`，注入 PluginRegistry、PluginConfigResolver、PluginChain、MetricsCollector、globalPluginConfigs
    - 实现 executeRequestPhase：调用 configResolver.resolve → 取 REQUEST 阶段配置 → 从 registry 查找插件实例 → 构建 PluginContext → 调用 pluginChain.execute → 返回 PluginExecutionResult
    - _Requirements: 2.2, 8.1, 8.4, 8.5_

  - [x] 4.2 编写 GatewayPluginProcessor 指标和追踪标签属性测试
    - **Property 8: GatewayPluginProcessor 记录指标和追踪标签**
    - **Validates: Requirements 8.4, 8.5**

  - [x] 4.3 编写 GatewayPluginProcessor 单元测试
    - 验证 REQUEST 阶段插件链执行
    - 验证未注册插件 log.warn 并跳过
    - _Requirements: 7.3, 8.1_

- [x] 5. 实现 5 个安全插件
  - [x] 5.1 实现 RealIpPlugin
    - 在 `com.lei.gateway.core.plugin` 包创建 `RealIpPlugin`（name=real-ip, phase=REQUEST, defaultPriority=1000）
    - 从 PluginConfig.config 读取 trusted-proxies/trusted-proxy-hops，调用 ClientIpResolver 解析客户端 IP，写入 PluginContext.clientIp
    - _Requirements: 9.1_

  - [x] 5.2 实现 IpAccessPlugin
    - 创建 `IpAccessPlugin`（name=ip-access, phase=REQUEST, defaultPriority=2000）
    - 从 config 读取 enabled/shadow/allow-list/deny-list，调用 CidrMatcher 检查，命中 deny 返回 SHORT_CIRCUIT(403)，shadow 模式返回 CONTINUE
    - _Requirements: 9.2_

  - [x] 5.3 实现 IpRateLimitPlugin
    - 创建 `IpRateLimitPlugin`（name=ip-rate-limit, phase=REQUEST, defaultPriority=3000）
    - 从 config 读取限流参数，按客户端 IP 限流，超限返回 SHORT_CIRCUIT(429) + retryAfterSeconds，shadow 模式返回 CONTINUE
    - _Requirements: 9.3_

  - [x] 5.4 实现 AuthPlugin
    - 创建 `AuthPlugin`（name=auth, phase=REQUEST, defaultPriority=4000）
    - 从 config 读取认证参数，调用 AuthProvider 验证 JWT，成功写入 PluginContext.userId 和 attributes(authRequired/authPassed)，失败返回 SHORT_CIRCUIT(401)
    - _Requirements: 9.4_

  - [x] 5.5 实现 UserRateLimitPlugin
    - 创建 `UserRateLimitPlugin`（name=user-rate-limit, phase=REQUEST, defaultPriority=5000）
    - 从 config 读取限流参数，按 userId 限流，未认证时跳过，超限返回 SHORT_CIRCUIT(429) + retryAfterSeconds
    - _Requirements: 9.5_

  - [x] 5.6 编写 5 个安全插件单元测试
    - RealIpPlugin：XFF 解析、trustedProxies/trustedProxyHops 模式
    - IpAccessPlugin：黑名单优先于白名单、shadow 模式
    - IpRateLimitPlugin：超限返回 429 + Retry-After、shadow 模式
    - AuthPlugin：JWT 验证成功写入 userId、验证失败返回 401、shadow 模式
    - UserRateLimitPlugin：按 userId 限流、未认证时跳过
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

  - [x] 5.7 编写安全插件行为等价性属性测试
    - **Property 9: 安全插件行为等价性**
    - **Validates: Requirements 9.6**

- [x] 6. Checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. 集成到 RoutingHandler 和配置类变更
  - [x] 7.1 GatewayProperties 和 Route 新增 plugins 字段
    - GatewayProperties 新增 `List<PluginConfigEntry> plugins` 字段和 getter/setter
    - Route 新增 `List<PluginConfigEntry> plugins` 字段和 getter/setter
    - _Requirements: 5.1, 5.3_

  - [x] 7.2 RoutingContext 替换 GatewaySecurityProcessor 为 GatewayPluginProcessor
    - 移除 `GatewaySecurityProcessor securityProcessor` 字段
    - 新增 `GatewayPluginProcessor pluginProcessor` 字段
    - 更新构造函数和 getter
    - _Requirements: 8.1_

  - [x] 7.3 RoutingHandler 集成 GatewayPluginProcessor
    - 移除 `gatewaySecurityProcessor` 字段和所有 SecurityEvaluationResult 相关逻辑
    - 路由匹配后调用 `pluginProcessor.executeRequestPhase()`
    - 从 PluginContext 提取 clientIp/userId/traceTags 写入 Channel Attribute（兼容访问日志）
    - SHORT_CIRCUIT 时写访问日志 + 返回对应 HTTP 响应（含 Retry-After）
    - CONTINUE 时继续添加 ProxyHandler 转发
    - 移除 attachSecurityAttributes/writeSecurityAccessLog/sendSecurityDecision/determineAuthPassed 方法，替换为 attachPluginAttributes/writePluginAccessLog/sendPluginResponse
    - _Requirements: 8.1, 8.2, 8.3, 9.6_

  - [x] 7.4 GatewayAutoConfiguration 变更
    - 移除 GatewaySecurityProcessor 相关 Bean 创建
    - 新增 PluginRegistry、PluginConfigResolver、GatewayPluginProcessor Bean
    - 新增 5 个安全插件 Bean（RealIpPlugin、IpAccessPlugin、IpRateLimitPlugin、AuthPlugin、UserRateLimitPlugin）
    - RoutingContext Bean 参数从 GatewaySecurityProcessor 替换为 GatewayPluginProcessor
    - _Requirements: 7.2, 8.1_

  - [x] 7.5 编写集成测试
    - RoutingHandler 路由匹配后调用 GatewayPluginProcessor
    - REQUEST 阶段 SHORT_CIRCUIT 时返回对应 HTTP 响应
    - REQUEST 阶段 CONTINUE 时继续转发
    - 访问日志中 securityFilter/securityReason/authRequired/authPassed 字段正确输出
    - _Requirements: 8.1, 8.2, 8.3, 9.6_

- [x] 8. 删除旧的 SecurityFilter 相关类
  - [x] 8.1 删除已废弃的安全过滤类
    - 删除 `GatewaySecurityProcessor`（含内部类 RealIpFilter、IpAccessFilter、IpRateLimitFilter、AuthenticationFilter、UserRateLimitFilter）
    - 删除 `SecurityFilter` 接口
    - 删除 `SecurityRequestContext`
    - 删除 `SecurityEvaluationResult`
    - 删除 `SecurityDecision`
    - 删除 `SecurityDecisionType`
    - 删除 `EffectiveSecurityConfig`
    - 删除 `RouteSecurityConfigResolver`
    - 保留 AuthProvider/JwtAuthProvider/JwksKeyProvider、CidrMatcher、ClientIpResolver、限流引擎、SecurityAuditLogger、SecurityProperties/RouteSecurityProperties
    - _Requirements: 9.7_

- [x] 9. Final checkpoint - 确保所有测试通过
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (jqwik, ≥100 tries)
- Unit tests validate specific examples and edge cases
- 属性测试文件命名 `*PropertyTest.java`，单元测试文件命名 `*Test.java`
- 4 空格缩进、120 行宽、Javadoc 中文句号、异常日志传异常对象、成员名至少 2 字符
