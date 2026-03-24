## Purpose
定义 auth-jwt-example-module 能力相关需求。

## Requirements

### Requirement: 必须提供独立的 JWT 认证服务示例模块
系统 MUST 新增独立模块 `auth-jwt-example`，用于演示 JWT 的签发、校验以及与网关的联调流程。

#### Scenario: 工程包含独立示例模块
- **WHEN** 开发者检出项目并查看模块结构
- **THEN** 工程中存在可单独构建运行的 `auth-jwt-example` 模块

### Requirement: 示例模块必须提供最小可运行验证路径
系统 SHALL 在示例模块中提供最小配置与认证服务接口，用于验证 JWT 签发与校验路径。

#### Scenario: 生成 JWT token
- **WHEN** 调用方请求 token 签发接口
- **THEN** 系统返回可用于网关认证链路的 JWT token

#### Scenario: 校验非法 JWT
- **WHEN** 调用方提交非法或过期 JWT 到校验接口
- **THEN** 系统返回校验失败结果

### Requirement: 示例模块必须支持回归验证
系统 MUST 为示例模块提供可重复执行的验证方式，用于本地联调与回归检查。

#### Scenario: 执行示例验证流程
- **WHEN** 开发者按照示例说明执行验证步骤
- **THEN** 可稳定复现认证通过与认证失败两类结果
