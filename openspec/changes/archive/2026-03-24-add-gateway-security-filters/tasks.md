## 1. 配置模型与基础骨架

- [x] 1.1 在 `gateway-core` 增加 `gateway.security` 配置根，定义全局安全配置结构
- [x] 1.2 在路由配置中增加安全策略块，支持路由级覆盖
- [x] 1.3 实现 `merge`/`replace` 组合解析器，产出每个路由的生效安全配置
- [x] 1.4 增加配置校验与默认值（`auth.type=JWT`、限流默认 `local`）

## 2. 安全过滤器链框架

- [x] 2.1 定义统一过滤器接口与决策模型（ALLOW/DENY/ERROR + reason）
- [x] 2.2 定义请求级 `SecurityRequestContext` 并接入请求生命周期
- [x] 2.3 在路由转发前接入固定顺序过滤器编排器
- [x] 2.4 实现拒绝短路机制，确保被拒绝请求不进入上游转发

## 3. 真实 IP 与 IP 访问控制

- [x] 3.1 实现可信代理配置解析（单 IP/CIDR）
- [x] 3.2 实现真实 IP 解析逻辑（trusted proxy 才解析转发头）
- [x] 3.3 实现 IP 黑白名单匹配器（支持单 IP 与 CIDR）
- [x] 3.4 实现黑名单优先判定与策略拒绝响应

## 4. 本地 JWT 认证（公钥/JWKS）

- [x] 4.1 定义 `AuthProvider` SPI 与 `auth.type` 路由逻辑
- [x] 4.2 实现 `jwt` provider 的本地验签流程（签名、`exp`、`iss`、`aud`）
- [x] 4.3 实现静态公钥加载能力
- [x] 4.4 实现 `jwks-url` 拉取、缓存与定时刷新机制
- [x] 4.5 实现 `kid` 未命中时的受控刷新逻辑（避免并发抖动）
- [x] 4.6 落地认证失败 `fail-closed` 与统一 `401` 错误响应

## 5. 限流模式与降级

- [x] 5.1 定义统一 `RateLimiterEngine` 接口
- [x] 5.2 实现本地令牌桶限流引擎（默认模式）
- [x] 5.3 实现分布式限流引擎适配层与配置开关
- [x] 5.4 实现分布式限流故障降级到本地限流
- [x] 5.5 实现独立限流器：IP 限流（按 IP）与用户限流（按 userId）
- [x] 5.6 统一限流拒绝响应（`429` + `Retry-After`）

## 6. 统一错误语义与可观测性

- [x] 6.1 实现过滤器统一错误体映射（`401`/`403`/`429`）
- [x] 6.2 增加过滤器阶段指标（总量、拒绝、失败、耗时）
- [x] 6.3 增加结构化审计日志字段（traceId、clientIp、routeId、filterName、decision、reason）
- [x] 6.4 在追踪上下文写入过滤阶段标签与决策结果

## 7. auth-jwt-example 模块

- [x] 7.1 新建 `auth-jwt-example` 模块并接入父工程构建
- [x] 7.2 提供最小 JWT 配置示例（公钥与 JWKS 两种模式）
- [x] 7.3 提供 JWT token 签发接口与校验接口
- [x] 7.4 编写示例联调说明，覆盖认证成功与失败路径

## 8. 测试与验收

- [x] 8.1 增加配置解析测试（merge/replace、生效优先级、默认值）
- [x] 8.2 增加真实 IP 与黑白名单测试（含伪造转发头场景）
- [x] 8.3 增加 JWT 认证测试（静态公钥、JWKS 缓存命中/未命中刷新、异常 fail-closed）
- [x] 8.4 增加限流测试（local/distributed、降级、`Retry-After`）
- [x] 8.5 增加端到端集成测试（过滤顺序、短路行为、统一错误码）
- [x] 8.6 增加可观测性断言（指标标签、审计日志字段）

## 9. 缺陷修复与增强

- [x] 9.1 真实 IP 解析支持多级可信代理链右向左剥离，避免固定取 XFF 第一个地址
- [x] 9.2 新增 `trustedProxyHops` 配置，支持前置 LB 地址动态变化场景
- [x] 9.3 认证成功后注入 `x-userId`，并覆盖客户端同名头防止伪造透传
- [x] 9.4 补充 `SecurityProperties` 内部配置字段注释并完善相关测试

## 10. 限流与命名重构

- [x] 10.1 将限流配置从单体模型拆分为 `rate-limit.ip` 与 `rate-limit.user` 两组独立配置
- [x] 10.2 将限流过滤器重命名为 `ip-rate-limit` 与 `user-rate-limit`，每个过滤器仅承担单一职责
- [x] 10.3 限流键重构为 `ip:{clientIp}` 与 `user:{userId}`，移除 routeId 作为判定维度
- [x] 10.4 将 `consumerId` 相关字段统一更名为 `userId`，并同步代码、测试与规格文档

## 11. 认证配置模型重构

- [x] 11.1 将 `auth.type` 从字符串改为枚举，提升配置约束与可读性
- [x] 11.2 将 `headerName`/`bearerPrefix` 重构为 `auth.token-extractor` 子配置
- [x] 11.3 将 `auth.jwt` 重构为 `auth.providers.jwt`，解耦通用认证配置与 provider 专属配置
- [x] 11.4 同步更新路由覆盖配置、生效配置模型、解析器与相关测试

## 12. 访问日志安全语义增强

- [x] 12.1 在访问日志增加 `authRequired`、`authPassed`、`securityDecision`、`securityFilter`、`securityReason` 字段
- [x] 12.2 为安全链拒绝请求补充访问日志输出，避免仅有审计日志导致排障割裂
