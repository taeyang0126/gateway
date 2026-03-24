## Purpose
定义 gateway-auth-jwt 能力相关需求。

## Requirements

### Requirement: 网关必须支持认证类型配置并默认 JWT
系统 SHALL 提供 `auth.type` 配置项（枚举），并在未显式配置时默认使用 `JWT` 认证类型。

#### Scenario: 未配置 auth.type 时走 JWT
- **WHEN** 认证功能开启且未配置 `auth.type`
- **THEN** 系统使用 JWT 认证提供方执行认证

#### Scenario: 配置有效认证类型时按类型路由
- **WHEN** `auth.type` 配置为已注册的认证类型
- **THEN** 系统选择对应认证提供方执行认证流程

### Requirement: Token 提取配置必须与 provider 配置解耦
系统 MUST 将 token 提取策略配置在 `auth.token-extractor` 下，认证提供器专属配置放在 `auth.providers.<provider>` 下。

#### Scenario: 使用默认 token 提取策略
- **WHEN** 认证启用且未显式配置 token 提取策略
- **THEN** 系统默认使用请求头 `Authorization` 与前缀 `Bearer `

### Requirement: JWT 认证必须校验关键声明与签名
系统 MUST 对 JWT 至少执行签名、过期时间（`exp`）、发行方（`iss`）和受众（`aud`）校验。

#### Scenario: JWT 校验通过
- **WHEN** 请求携带签名与声明均合法的 JWT
- **THEN** 系统认证通过并在请求上下文写入认证主体
- **AND** 系统将认证主体写入上游请求头 `x-userId`

#### Scenario: JWT 过期或声明不匹配
- **WHEN** JWT 过期或 `iss`/`aud` 校验失败
- **THEN** 系统判定认证失败并终止后续需要认证的流程

### Requirement: JWT 验签密钥必须支持公钥与 JWKS 两种来源
系统 SHALL 支持静态公钥与 `jwks-url` 两种密钥来源，用于本地 JWT 验签。

#### Scenario: 配置静态公钥时完成本地验签
- **WHEN** 系统配置了静态公钥且请求携带 JWT
- **THEN** 系统使用本地公钥完成签名校验

#### Scenario: 配置 JWKS 时按 kid 选择密钥
- **WHEN** 系统配置了 `jwks-url` 且 JWT Header 包含 `kid`
- **THEN** 系统从本地 JWKS 缓存选择匹配公钥完成验签

### Requirement: JWT 认证主路径不得依赖每请求远程鉴权调用
系统 MUST 在请求认证主路径使用本地密钥材料完成 JWT 验签，并避免每请求调用远程鉴权接口。

#### Scenario: JWKS 缓存命中时不发起远程调用
- **WHEN** JWT 验签所需 `kid` 在本地 JWKS 缓存中命中
- **THEN** 系统在本地完成验签且不发起远程鉴权请求

#### Scenario: JWKS 缓存未命中时执行受控刷新
- **WHEN** JWT 验签所需 `kid` 未命中本地 JWKS 缓存
- **THEN** 系统触发受控 JWKS 刷新并在刷新后继续本地验签

### Requirement: 认证失败必须返回统一 401 响应
系统 MUST 在认证失败时返回 `401` 状态码及统一 JSON 错误响应格式。

#### Scenario: 缺失认证凭证
- **WHEN** 受保护路由收到不包含认证凭证的请求
- **THEN** 系统返回 `401` 且响应体符合统一错误结构

#### Scenario: 认证提供方执行异常
- **WHEN** 认证提供方运行异常或依赖不可用
- **THEN** 系统按 `fail-closed` 拒绝请求并返回 `401`

### Requirement: 认证主体请求头必须由网关托管
系统 MUST 在认证启用时托管 `x-userId` 请求头，覆盖客户端同名头并仅在认证成功后写入主体标识。

#### Scenario: 客户端伪造 x-userId
- **WHEN** 请求携带 `x-userId` 且认证尚未通过
- **THEN** 系统移除该请求头，防止伪造主体透传到上游
