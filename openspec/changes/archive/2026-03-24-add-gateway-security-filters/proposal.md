## Why

当前网关已具备路由转发与基础请求限制能力，但缺少可配置、可扩展的安全过滤器体系，无法系统性满足生产环境对访问控制、认证与限流的要求。现在需要将已冻结需求落地为正式变更，统一安全策略行为并降低后续扩展成本。

## What Changes

- 在网关转发前引入统一过滤器链，固定执行顺序：真实 IP 解析 -> IP 访问控制 -> IP 限流 -> 认证 -> 用户限流 -> 路由转发。
- 新增认证配置 `auth.type`（枚举），默认 `JWT`，并定义可扩展认证框架。
- 将 token 提取策略独立为 `auth.token-extractor`（`token-header-name`、`token-value-prefix`）。
- 将提供器专属配置收敛为 `auth.providers.<provider>`（首期 `auth.providers.jwt`）。
- 支持 JWT 认证能力（签名、过期、发行方、受众校验），认证失败返回统一 `401` 错误语义。
- 认证通过后将主体标识写入上游请求头 `x-userId`，并覆盖客户端同名头避免伪造透传。
- 新增 IP 黑白名单能力（支持单 IP/CIDR），并要求基于可信代理解析真实客户端 IP，避免伪造 `X-Forwarded-For` 绕过。
- 真实 IP 解析补充多级代理场景：支持“可信代理 CIDR 链路剥离”与“固定可信跳数（trustedProxyHops）”两种模式。
- 新增可配置限流模式：默认单机限流，可切换分布式限流；分布式限流异常时降级本地限流；限流拒绝返回 `429` 和 `Retry-After`。
- 引入全局与路由级策略组合规则：默认 `merge`，支持显式 `replace`。
- 明确失败策略：认证与 IP 访问控制 `fail-closed`；分布式限流失败降级本地限流。
- 统一错误响应语义（`401`/`403`/`429`）与结构。
- 增加过滤器相关可观测性：拒绝计数、认证失败、限流命中、过滤器耗时，以及包含决策原因的审计日志。
- 增强访问日志字段：补充 `authRequired`、`authPassed`、`securityDecision`、`securityFilter`、`securityReason`。
- 对安全链拒绝请求输出访问日志，统一放行与拒绝请求的排障入口。
- 新增 `auth-jwt-example` 示例模块，用于 JWT 签发、校验与联调验证。

## Capabilities

### New Capabilities

- `gateway-security-filter-chain`: 统一过滤器执行顺序、策略组合规则（merge/replace）、失败策略与统一错误语义。
- `gateway-ip-access-control`: 真实 IP 可信解析与 IP 黑白名单访问控制能力。
- `gateway-rate-limiting-modes`: 单机/分布式限流模式切换、限流维度扩展与分布式失败降级策略。
- `gateway-auth-jwt`: 认证类型框架（默认 jwt）与 JWT 认证校验能力。
- `gateway-security-observability`: 安全过滤器阶段指标、日志与拒绝原因可追踪能力。
- `auth-jwt-example-module`: JWT 签发与校验示例模块能力，用于联调与回归验证。

### Modified Capabilities

- 无（当前 `openspec/specs/` 下无既有能力规格）

## Impact

- Affected code:
- `gateway-core`：过滤器链路、配置模型、认证/限流/IP 策略、错误响应与可观测性实现。
- 新增模块：`auth-jwt-example`。
- Affected APIs/behavior:
- 网关请求前置处理流程新增安全过滤阶段，部分请求会因策略被前置拒绝（`401`/`403`/`429`）。
- Affected dependencies/systems:
- 分布式限流模式依赖外部存储或服务（具体实现待 design 明确）。
- JWT 校验依赖密钥配置或密钥分发机制（具体实现待 design 明确）。
