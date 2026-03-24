## Purpose
定义 gateway-ip-access-control 能力相关需求。

## Requirements

### Requirement: 网关必须基于可信代理解析真实客户端 IP
系统 MUST 提供可信代理地址配置，并仅在请求来源属于可信代理时解析 `X-Forwarded-For` 或 `Forwarded` 头得到真实客户端 IP。

#### Scenario: 不可信来源携带 XFF 不得被采信
- **WHEN** 请求来源地址不在可信代理列表且请求头包含 `X-Forwarded-For`
- **THEN** 系统使用 socket 源地址作为客户端 IP

#### Scenario: 可信代理来源可解析真实客户端 IP
- **WHEN** 请求来源地址在可信代理列表且请求头包含合法转发链
- **THEN** 系统按配置规则解析并返回真实客户端 IP

#### Scenario: 多级可信代理链按右向左剥离
- **WHEN** 请求来源地址在可信代理列表且 `X-Forwarded-For` 包含多级代理链
- **THEN** 系统从右向左跳过可信代理地址并返回第一个非可信地址作为客户端 IP

#### Scenario: 配置 trustedProxyHops 时按固定跳数解析
- **WHEN** 系统配置 `trustedProxyHops` 且请求包含合法转发链
- **THEN** 系统按固定可信跳数从右向左定位客户端 IP

### Requirement: 网关必须支持 IP 黑白名单匹配
系统 SHALL 支持单 IP 与 CIDR 形式的黑名单和白名单规则，并在请求阶段进行匹配判定。

#### Scenario: 命中黑名单时拒绝请求
- **WHEN** 请求解析后的客户端 IP 命中黑名单规则
- **THEN** 系统拒绝请求并返回策略拒绝响应

#### Scenario: 白名单启用且未命中时拒绝请求
- **WHEN** 白名单规则已配置且客户端 IP 未命中任何白名单规则
- **THEN** 系统拒绝请求并返回策略拒绝响应

### Requirement: 黑名单规则必须优先于白名单规则
系统 MUST 在黑白名单并存时优先执行黑名单判定。

#### Scenario: 同时命中黑名单和白名单
- **WHEN** 客户端 IP 同时命中黑名单与白名单规则
- **THEN** 系统按黑名单优先策略拒绝请求
