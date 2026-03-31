# Requirements Document

## Introduction

为 netty-gateway 构建通用插件系统，参考 APISIX 插件模型。插件覆盖请求生命周期的四个阶段（request、proxy、response、error），支持全局 + 路由级编排，通过 YAML 声明式配置管理。本 spec 包含插件框架骨架（接口、执行链、上下文、配置体系、注册表、GatewayPluginProcessor 集成）以及现有 SecurityFilter 迁移为 REQUEST 阶段插件。GatewayPluginProcessor 完全替代 GatewaySecurityProcessor，不做并行共存。

## Glossary

- **Plugin**：网关插件，实现特定阶段的请求处理逻辑，拥有唯一名称和优先级。
- **PluginChain**：插件执行链，按优先级排序的插件有序列表，负责依次调用插件并处理短路逻辑。
- **PluginContext**：插件执行上下文，携带当前请求的 ChannelHandlerContext、HttpRequest、Route、配置快照等信息，在整个请求生命周期内共享。
- **PluginPhase**：插件执行阶段枚举，包含 REQUEST、PROXY、RESPONSE、ERROR 四个值。
- **PluginResult**：插件执行结果，包含 CONTINUE（继续执行下一个插件）、SHORT_CIRCUIT（短路终止并返回响应）、ERROR（执行异常）三种类型。
- **PluginConfig**：插件实例的 YAML 配置数据，以 Map 形式传递给插件。
- **PluginRegistry**：插件注册表，管理所有可用插件的元数据和工厂方法。
- **GlobalPluginConfig**：全局插件配置，定义所有路由默认启用的插件列表及其配置。
- **RoutePluginConfig**：路由级插件配置，可覆盖或追加全局插件配置。
- **PluginConfigResolver**：插件配置解析器，合并全局配置与路由级覆盖，生成最终生效的插件列表。
- **GatewayPluginProcessor**：网关插件处理器，替代现有 GatewaySecurityProcessor，在 RoutingHandler 中调用，负责构建并执行 request 阶段插件链。

## Requirements

### Requirement 1: 插件接口定义

**User Story:** As a 网关开发者, I want 一个统一的插件接口, so that 所有扩展逻辑都遵循相同的契约，便于开发和维护。

#### Acceptance Criteria

1. THE Plugin interface SHALL define a `name()` method that returns the unique plugin name as a non-null, non-blank String。
2. THE Plugin interface SHALL define a `phase()` method that returns the PluginPhase indicating which execution phase the plugin belongs to。
3. THE Plugin interface SHALL define a `defaultPriority()` method that returns an integer value, where a smaller number indicates higher execution priority。
4. THE Plugin interface SHALL define an `execute(PluginContext context, PluginConfig config)` method that returns a PluginResult。
5. WHEN two plugins belong to the same phase, THE PluginChain SHALL execute the plugin with the smaller priority value first。

### Requirement 2: 插件执行阶段

**User Story:** As a 网关开发者, I want 插件覆盖请求生命周期的四个阶段, so that 不同类型的处理逻辑在正确的时机执行。

#### Acceptance Criteria

1. THE PluginPhase enum SHALL define exactly four values: REQUEST, PROXY, RESPONSE, ERROR。
2. WHEN a route is matched and before the request is forwarded to upstream, THE GatewayPluginProcessor SHALL execute all enabled REQUEST phase plugins in priority order。
3. WHEN selecting an upstream node for forwarding, THE GatewayPluginProcessor SHALL execute all enabled PROXY phase plugins in priority order。
4. WHEN an upstream response is received, THE GatewayPluginProcessor SHALL execute all enabled RESPONSE phase plugins in priority order。
5. IF an upstream connection failure or timeout occurs, THEN THE GatewayPluginProcessor SHALL execute all enabled ERROR phase plugins in priority order。

### Requirement 3: 插件执行结果与短路

**User Story:** As a 网关开发者, I want 插件能够短路终止请求处理, so that 认证失败或限流触发时能立即返回响应。

#### Acceptance Criteria

1. THE PluginResult SHALL support three result types: CONTINUE, SHORT_CIRCUIT, ERROR。
2. WHEN a plugin returns CONTINUE, THE PluginChain SHALL proceed to execute the next plugin in the chain。
3. WHEN a plugin returns SHORT_CIRCUIT, THE PluginChain SHALL stop executing remaining plugins and return the SHORT_CIRCUIT result containing the HTTP response status and response body。
4. IF a plugin throws an exception during execution, THEN THE PluginChain SHALL catch the exception, log it with the exception object, and return an ERROR result with HTTP 500 status。
5. WHEN all plugins in a phase return CONTINUE, THE PluginChain SHALL return a final CONTINUE result indicating the phase completed successfully。

### Requirement 4: 插件上下文

**User Story:** As a 插件开发者, I want 一个共享的执行上下文, so that 插件之间可以传递数据（如解析后的客户端 IP、认证后的用户 ID）。

#### Acceptance Criteria

1. THE PluginContext SHALL provide read access to the ChannelHandlerContext, HttpRequest, Route, and trace ID。
2. THE PluginContext SHALL provide a mutable key-value attribute store for plugins to share data within a single request lifecycle。
3. WHEN a plugin sets an attribute in the PluginContext, THE PluginContext SHALL make that attribute available to all subsequent plugins in the same request。
4. THE PluginContext SHALL provide setter and getter methods for clientIp and userId as first-class fields, maintaining backward compatibility with the existing SecurityRequestContext contract。

### Requirement 5: 插件配置 YAML 声明

**User Story:** As a 网关运维人员, I want 通过 YAML 声明式管理插件配置, so that 插件的启用、禁用和参数调整无需修改代码。

#### Acceptance Criteria

1. THE GatewayProperties SHALL support a `plugins` section under the `gateway` namespace for declaring global plugin configuration。
2. WHEN a global plugin entry specifies a plugin name and enabled flag, THE PluginConfigResolver SHALL include or exclude that plugin from the global plugin list accordingly。
3. THE Route SHALL support a `plugins` section for declaring route-level plugin configuration that overrides or extends the global configuration。
4. WHEN a route-level plugin entry specifies a plugin name that also exists in the global configuration, THE PluginConfigResolver SHALL use the route-level configuration to override the global configuration for that plugin。
5. WHEN a route-level plugin entry specifies a plugin name that does not exist in the global configuration, THE PluginConfigResolver SHALL add that plugin to the route's effective plugin list。
6. THE plugin configuration entry SHALL support an `enabled` boolean field (default true), a `priority` integer field (optional, overrides default), and a `config` map field for plugin-specific parameters。

### Requirement 6: 全局与路由级插件编排

**User Story:** As a 网关运维人员, I want 全局插件配置作为默认值并允许路由级覆盖, so that 通用策略全局生效而特定路由可以定制。

#### Acceptance Criteria

1. THE PluginConfigResolver SHALL merge global plugin configuration with route-level plugin configuration to produce the effective plugin list for each request。
2. WHEN a route does not declare any plugin configuration, THE PluginConfigResolver SHALL use the global plugin configuration as the effective configuration。
3. WHEN a route declares a plugin with `enabled: false` that is enabled globally, THE PluginConfigResolver SHALL exclude that plugin from the route's effective plugin list。
4. THE PluginConfigResolver SHALL sort the effective plugin list by priority (smaller value first) within each phase before execution。

### Requirement 7: 插件注册表

**User Story:** As a 网关开发者, I want 一个集中的插件注册表, so that 系统启动时自动发现和注册所有可用插件。

#### Acceptance Criteria

1. THE PluginRegistry SHALL maintain a mapping from plugin name to plugin factory, allowing lookup by name。
2. WHEN the gateway starts, THE PluginRegistry SHALL discover and register all Plugin implementations available in the application context。
3. IF a plugin configuration references a plugin name not found in the PluginRegistry, THEN THE PluginRegistry SHALL log a warning and skip that plugin entry。
4. THE PluginRegistry SHALL reject registration of two plugins with the same name, throwing an IllegalStateException。

### Requirement 8: GatewayPluginProcessor 集成

**User Story:** As a 网关开发者, I want GatewayPluginProcessor 替代 GatewaySecurityProcessor 集成到 RoutingHandler, so that 插件系统成为请求处理的核心扩展点。

#### Acceptance Criteria

1. THE RoutingHandler SHALL invoke GatewayPluginProcessor instead of GatewaySecurityProcessor after route matching。
2. WHEN the REQUEST phase plugin chain returns SHORT_CIRCUIT, THE RoutingHandler SHALL send the corresponding HTTP error response to the client and stop further processing。
3. WHEN the REQUEST phase plugin chain returns CONTINUE, THE RoutingHandler SHALL proceed to add ProxyHandler and forward the request to upstream。
4. THE GatewayPluginProcessor SHALL record plugin execution duration metrics per plugin name and route ID, consistent with the existing security filter metrics pattern。
5. THE GatewayPluginProcessor SHALL write plugin execution trace tags to the PluginContext, consistent with the existing security trace tag pattern。

### Requirement 9: SecurityFilter 迁移为 REQUEST 阶段插件

**User Story:** As a 网关开发者, I want 现有 SecurityFilter 实现迁移为 REQUEST 阶段插件, so that 安全过滤逻辑统一纳入插件体系，GatewaySecurityProcessor 被完全替代。

#### Acceptance Criteria

1. THE RealIpPlugin SHALL implement the Plugin interface with phase REQUEST and default priority 1000, providing the same client IP resolution logic as the existing RealIpFilter。
2. THE IpAccessPlugin SHALL implement the Plugin interface with phase REQUEST and default priority 2000, providing the same IP allow/deny list logic as the existing IpAccessFilter。
3. THE IpRateLimitPlugin SHALL implement the Plugin interface with phase REQUEST and default priority 3000, providing the same IP-based rate limiting logic as the existing IpRateLimitFilter。
4. THE AuthPlugin SHALL implement the Plugin interface with phase REQUEST and default priority 4000, providing the same JWT authentication logic as the existing AuthenticationFilter。
5. THE UserRateLimitPlugin SHALL implement the Plugin interface with phase REQUEST and default priority 5000, providing the same user-based rate limiting logic as the existing UserRateLimitFilter。
6. WHEN all five security plugins are migrated, THE GatewayPluginProcessor SHALL produce equivalent Channel Attribute outputs (clientIp, authRequired, authPassed, securityDecision, securityFilter, securityReason) as the existing GatewaySecurityProcessor for the same input。
7. THE GatewaySecurityProcessor, SecurityFilter interface, and all related classes (SecurityRequestContext, SecurityEvaluationResult, SecurityDecision, SecurityDecisionType, RouteSecurityConfigResolver) SHALL be removed after migration is complete。
