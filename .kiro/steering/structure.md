---
inclusion: always
---

# Project Structure

```
netty-gateway/                  # Maven 父 POM（pom packaging）
├── gateway-pool/               # 通用并发资源池（无 Spring 依赖）
│   └── com.lei.gateway.pool
│       ├── ConcurrentPool      # 核心池实现（ConcurrentBag 模式）
│       ├── PoolEntry           # 池条目基类
│       ├── PoolEntryFactory    # 条目创建/销毁/健康检查接口
│       ├── PoolConfig          # 池配置
│       └── IdleEvictor         # 空闲驱逐
├── gateway-core/               # 网关核心（依赖 gateway-pool）
│   └── com.lei.gateway.core
│       ├── config/             # Spring Boot 配置绑定（GatewayProperties, Route 等）
│       ├── proxy/              # Netty pipeline：路由 → 代理转发 → 连接池
│       ├── security/           # JWT 认证、IP 访问控制、限流
│       └── observability/      # 指标、Access Log、Trace
├── gateway-app/                # Spring Boot 启动入口（端口 8080）
├── gateway-example/            # Mock 上游服务（端口 8081/8082），仅本地开发
├── auth-jwt-example/           # JWT 认证示例
└── config/checkstyle/          # Checkstyle 配置文件
```

## 模块依赖

gateway-app → gateway-core → gateway-pool

## 包命名

所有模块根包：`com.lei.gateway`，子模块追加 `.pool` / `.core` / `.app`

## 测试组织

- 单元测试：与源码同包路径，`*Test.java`
- 属性测试：`*PropertyTest.java`（jqwik）
- 集成测试：`gateway-core/.../integration/*IntegrationTest.java`，可通过 `-Dexclude` 跳过
- 集成测试基类：`IntegrationTestBase`，内含 `MockUpstreamServer`
