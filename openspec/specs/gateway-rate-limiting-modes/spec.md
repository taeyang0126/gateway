## Purpose
定义 gateway-rate-limiting-modes 能力相关需求。

## Requirements

### Requirement: 网关必须支持可切换的限流模式
系统 SHALL 支持 `local` 与 `distributed` 两种限流模式，且默认模式为 `local`。

#### Scenario: 未配置限流模式时默认本地限流
- **WHEN** 用户未显式配置限流模式
- **THEN** 系统使用本地限流引擎进行请求判定

#### Scenario: 配置 distributed 时启用分布式限流
- **WHEN** 用户将限流模式配置为 `distributed`
- **THEN** 系统优先使用分布式限流引擎进行请求判定

### Requirement: 网关必须支持独立的 IP 与用户限流器
系统 MUST 将限流拆分为 `ip` 与 `user` 两个独立限流器，并分别拥有独立开关与阈值配置。

#### Scenario: IP 限流器按 IP 判定
- **WHEN** 请求进入 IP 限流阶段
- **THEN** 系统按客户端 `IP` 执行限流判定

#### Scenario: 用户限流器按 userId 判定
- **WHEN** 请求完成认证并获得 `userId`
- **THEN** 系统按 `userId` 维度执行限流判定

### Requirement: 分布式限流失败必须降级到本地限流
系统 MUST 在分布式限流不可用时自动降级本地限流，并产出降级可观测事件。

#### Scenario: 分布式后端超时触发降级
- **WHEN** 分布式限流后端请求超时或不可达
- **THEN** 系统切换到本地限流并继续返回限流判定结果

### Requirement: 限流拒绝必须返回统一语义
系统 MUST 在限流拒绝时返回 `429` 状态码并包含 `Retry-After` 响应头。

#### Scenario: 请求超出配额
- **WHEN** 请求在当前限流窗口或令牌桶中超出阈值
- **THEN** 系统返回 `429` 且响应头包含 `Retry-After`
