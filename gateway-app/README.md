# gateway-app

网关启动入口模块。

## 定位

引用 gateway-core，提供 Spring Boot `main` 方法和运行时配置（`application.yml`）。不包含业务逻辑。

## 启动

```bash
mvn spring-boot:run -pl gateway-app
```

## JWT 认证联调（auth-jwt-example）

1. 先启动认证服务：

```bash
mvn -pl auth-jwt-example spring-boot:run
```

2. 启动资源服务：

```bash
mvn -pl gateway-example spring-boot:run
```

3. 再启动网关：

```bash
mvn spring-boot:run -pl gateway-app
```

4. 先从认证服务获取 token：

```bash
curl -s -X POST http://localhost:8091/api/auth-jwt/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"demo-user"}'
```

5. 验证普通路由（不需要 JWT）：
- `GET http://localhost:8080/api/example/hello`：返回 `200`

6. 验证受保护路由（需要 JWT）：
- `GET http://localhost:8080/api/example/private/profile`：未携带 token 返回 `401`
- `GET http://localhost:8080/api/example/private/profile` + `Authorization: Bearer <token>`：返回 `200`

7. 一键联调脚本（自动取 token 并访问受保护接口）：

```bash
TOKEN=$(curl -s -X POST http://localhost:8091/api/auth-jwt/token \
  -H "Content-Type: application/json" \
  -d '{"userId":"demo-user"}' \
  | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

echo "token length: ${#TOKEN}"

curl -i http://localhost:8080/api/example/private/profile \
  -H "Authorization: Bearer ${TOKEN}"
```

说明：
- `example-service`（`/api/example/**`）保持原样，不启用认证。
- 新增 `example-service-jwt`（`/api/example/private/**`）启用 JWT 认证。
- `auth-jwt-example` 作为纯认证服务，只负责签发和校验 JWT，不承载业务资源接口。
