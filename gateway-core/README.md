# gateway-core

基于 Netty 4.2.x 的 HTTP 反向代理网关核心模块。

## 定位

提供完整的反向代理能力：路由匹配、请求转发、连接池管理、安全层（JWT 认证、IP 黑白名单、令牌桶限流）、可观测性（metrics / access log / trace）、优雅停机与 K8s 健康检查。通过 Spring Boot 自动配置集成。

## 模块结构

| 包 | 职责 |
|---|---|
| `config` | 网关配置（路由、连接池、限流、安全、可观测性、停机、健康检查）及 Spring Boot 自动配置 |
| `proxy` | Netty pipeline：服务端引导、路由处理、代理转发、上游连接池、优雅停机编排、排空处理、启动预热 |
| `security` | JWT 认证、IP 黑白名单（CIDR）、令牌桶限流 |
| `observability` | Access Log、Metrics（Micrometer/Prometheus）、Trace Context |

## 主要依赖

- Netty 4.2.x（HTTP codec、transport）
- Spring Boot 3.4.x（配置绑定、生命周期）
- Micrometer + Prometheus（指标采集）
- gateway-pool（上游连接池底层实现）

## 路由配置

路由在 `application.yml` 的 `gateway.routes` 下配置，每条路由支持以下字段：

```yaml
gateway:
  routes:
    - id: my-service           # 路由唯一标识
      path-prefix: /api/**     # Ant 风格路径匹配（*, **, ?）
      upstream: http://localhost:8082  # 上游地址
      timeout-seconds: 30      # 上游超时（秒），可选，默认全局配置
      max-request-size: 10485760  # 请求体大小限制（字节），可选
      priority: 0              # 优先级，数字越小越优先，默认 0
      rewrite-path: "^/api(.*)"       # 路径重写正则，可选
      rewrite-replacement: "/v2$1"    # 路径重写替换字符串，可选
      security:                # 路由级安全配置，可选
        enabled: true
        rate-limit:
          ip:
            enabled: true
            permits-per-second: 100
```

### 路径重写

通过 `rewrite-path` + `rewrite-replacement` 实现路径重写，基于 Java 正则。

**规则**：
- `rewrite-path`：Java 正则，匹配完整请求 URI（含 query string）
- `rewrite-replacement`：替换字符串，用 `$1`、`$2` 引用捕获组
- 两个字段都不填则不做重写，原路径透传给上游
- 正则不匹配时原路径透传，不报错
- **不支持** `${name}` 命名捕获组引用（YAML 中会被 Spring 当 placeholder 解析）

**示例 1：前缀替换**

```yaml
# 客户端请求：GET /api/perf/route1/hello?foo=bar
# 上游收到：  GET /api/example/hello?foo=bar
rewrite-path: "^/api/perf/route1(.*)"
rewrite-replacement: "/api/example$1"
```

**示例 2：版本升级**

```yaml
# 客户端请求：GET /v1/users/123
# 上游收到：  GET /v2/users/123
rewrite-path: "^/v1(.*)"
rewrite-replacement: "/v2$1"
```

**示例 3：固定路径**

```yaml
# 所有请求都转发到同一个上游接口
# 客户端请求：GET /api/perf/hello
# 上游收到：  GET /api/example/hello
rewrite-path: "^/api/perf/hello(.*)"
rewrite-replacement: "/api/example/hello$1"
```

### 路由匹配规则

多条路由同时匹配时，按以下顺序决定优先级：
1. `priority` 数字越小越优先
2. `priority` 相同时，`path-prefix` 越长越优先（最精确匹配优先）

## 构建 & 测试

```bash
mvn clean test -pl gateway-core
```
