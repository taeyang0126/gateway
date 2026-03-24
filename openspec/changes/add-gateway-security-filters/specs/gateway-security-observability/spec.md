## ADDED Requirements

### Requirement: 网关必须暴露过滤器阶段指标
系统 SHALL 对每个过滤器阶段暴露指标，至少包含请求总量、拒绝次数、认证失败次数、限流命中次数与阶段耗时。

#### Scenario: 请求通过过滤器链
- **WHEN** 请求完整通过所有过滤器并进入转发
- **THEN** 系统记录各过滤阶段耗时与通过计数

#### Scenario: 请求在过滤阶段被拒绝
- **WHEN** 请求被任一过滤器拒绝
- **THEN** 系统记录对应过滤器的拒绝计数与拒绝原因维度

### Requirement: 网关必须记录结构化审计日志
系统 MUST 记录结构化过滤审计日志，并包含 `traceId`、`clientIp`、`routeId`、`filterName`、`decision`、`reason` 字段。

#### Scenario: 过滤器拒绝请求时写审计日志
- **WHEN** 请求被过滤器判定为拒绝
- **THEN** 系统输出包含必填字段的拒绝审计日志

#### Scenario: 过滤器放行请求时写审计日志
- **WHEN** 请求通过过滤器并继续下游处理
- **THEN** 系统输出包含必填字段的放行审计日志

### Requirement: 网关必须将过滤阶段纳入链路追踪
系统 SHALL 在追踪上下文中标记过滤阶段执行信息，包括过滤器名称、决策结果与原因码。

#### Scenario: 过滤器执行时写入追踪标签
- **WHEN** 请求处于追踪启用状态且执行过滤器链
- **THEN** 系统在当前追踪上下文记录过滤阶段标签

### Requirement: 网关访问日志必须包含认证与安全决策结果
系统 MUST 在访问日志中记录认证与安全链决策字段，至少包含 `authRequired`、`authPassed`、`securityDecision`、`securityFilter`、`securityReason`。

#### Scenario: 受保护路由认证通过
- **WHEN** 请求命中开启认证的路由且认证通过
- **THEN** 访问日志包含 `authRequired=true`、`authPassed=true` 与 `securityDecision=ALLOW`

#### Scenario: 请求被安全链拒绝
- **WHEN** 请求在安全过滤阶段被拒绝（如认证失败）
- **THEN** 系统仍输出访问日志，并包含拒绝过滤器和原因字段
