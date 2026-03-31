# example-auth

JWT 认证最小示例模块，仅提供 Token 签发与 Token 校验能力。

## 启动

```bash
mvn -pl example-auth spring-boot:run
```

默认端口：`8091`

## 接口

- `POST /api/auth-jwt/token`：签发 JWT
- `POST /api/auth-jwt/verify`：校验 JWT

## 配置模式

1. 本地密钥模式（默认）
- 配置 `auth.jwt.private-key`（签发）与 `auth.jwt.public-key`（校验）
- 认证主路径无远程调用

2. JWKS 模式
- 使用 profile：`jwks`
- 配置 `auth.jwt.jwks-url`
- 校验阶段通过本地缓存 + 定时刷新加载公钥（签发仍使用本地私钥）

## 联调示例

```bash
# 1) 生成 token（userId 可选，默认 demo-user）
curl -s -X POST http://localhost:8091/api/auth-jwt/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"1000001"}'

# 2) 校验 token（替换 <jwt-token>）
curl -s -X POST http://localhost:8091/api/auth-jwt/verify \
  -H "Content-Type: application/json" \
  -d '{"token":"<jwt-token>"}'

# 3) 校验非法 token（valid=false）
curl -s -X POST http://localhost:8091/api/auth-jwt/verify \
  -H "Content-Type: application/json" \
  -d '{"token":"not-a-jwt"}'
```

说明：
- 默认配置内置一组示例 RSA 密钥，可直接本地联调。
- 网关侧验签时，需要使用同一把公钥（见 `gateway` 配置）。
