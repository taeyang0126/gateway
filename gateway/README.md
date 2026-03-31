# gateway

基于 Netty 4.2.x 的 HTTP 反向代理网关。

## 定位

提供完整的反向代理能力：路由匹配、请求转发、连接池管理、安全层（JWT 认证、IP 黑白名单、令牌桶限流）、可观测性（metrics / access log / trace）、优雅停机与 K8s 健康检查。通过 Spring Boot 自动配置集成。

## 启动

```bash
mvn spring-boot:run -pl gateway
```

## JWT 认证联调（example-auth）

1. 先启动认证服务：

```bash
mvn -pl example-auth spring-boot:run
```

2. 启动资源服务：

```bash
mvn -pl example-upstream spring-boot:run
```

3. 再启动网关：

```bash
mvn spring-boot:run -pl gateway
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

## 路由配置

路由在 `application.yml` 的 `gateway.routes` 下配置，每条路由支持以下字段：

```yaml
gateway:
  routes:
    - id: my-service
      path-prefix: /api/**
      upstream: http://localhost:8082
      timeout-seconds: 30
      max-request-size: 10485760
      priority: 0
      rewrite-path: "^/api(.*)"
      rewrite-replacement: "/v2$1"
      security:
        enabled: true
        rate-limit:
          ip:
            enabled: true
            permits-per-second: 100
```

## 构建 & 测试

```bash
mvn clean test -pl gateway
```
