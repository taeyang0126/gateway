# Netty Gateway

基于 Netty 实现的高性能分布式长连接网关服务，用于管理和维护客户端与服务端之间的长连接通信。支持网关集群化部署，为业务服务提供可靠的消息推送能力。

## 项目简介

本项目是一个基于 Netty 构建的分布式网关系统，主要用于解决大规模长连接场景下的连接管理、消息路由、集群扩展等问题。适用于即时通讯、物联网、实时数据推送等场景。

## 系统架构

```ascii
+-------------+     +---------------+     +-----------------+
|             | <=> |   Gateway     | <=> |   Upstream     |
|   Client    |     |   Cluster     |     |   Services     |
|             |     |               |     |                 |
+-------------+     +-------+-------+     +-----------------+
                            ^                      ^
                            |                      |
                            v                      v
                    +---------------+     +-----------------+
                    |    Nacos      |     |    Gateway     |
                    |   Registry    | <=> |     SDK        |
                    +---------------+     +-----------------+
```

- Client <=> Gateway：TCP 长连接，支持双向通信
- Gateway <=> Upstream Services：
  - 上游服务通过 Gateway SDK 接入
  - 支持消息推送和响应
  - 支持服务发现和负载均衡
- Nacos Registry：
  - 网关节点注册与发现
  - 上游服务注册与发现
  - 配置管理

## 核心功能

### 连接管理
- 支持 TCP 长连接管理
- 客户端身份认证
- 会话状态维护
- 心跳保活机制
- 连接优雅关闭

### 消息处理
- 自定义二进制协议
- 消息编解码
- 消息校验和机制
- 支持多种消息类型
- 双向通信支持

### 服务治理
- 基于 Nacos 的服务发现
- 负载均衡
- ~~故障转移~~
- ~~限流熔断~~
- ~~监控告警~~

## 快速开始

### 环境要求
- JDK 24+
- Maven 3.11+
- Nacos 2.5.1+
- Docker

### 构建项目
```bash
# 完整构建（包括代码风格检查、单元测试、集成测试等）
mvn clean verify

# 仅构建（跳过测试）
mvn clean install -DskipTests
```

### 启动服务
1. 启动 Nacos
```bash
docker run --name nacos -p 8848:8848 -d nacos/nacos-server:v2.5.1
```

2. 启动网关服务
```bash
cd gateway-server
java -jar target/gateway-server-${version}.jar
```

## 项目结构
```
gateway/
├── gateway-server/           # 网关服务端
├── gateway-common/           # 公共模块
├── gateway-sdk-core/         # SDK核心模块
├── gateway-sdk-spring/       # Spring SDK模块
├── gateway-client-example/   # 客户端示例
└── gateway-upstream-server-spring-example/  # 上游服务示例
```

## 模块说明

### gateway-server
网关服务的核心实现，包含：
- 连接管理
- 消息处理
- 路由转发
- 集群管理

### gateway-common
公共基础模块，包含：
- 协议定义
- 工具类
- 常量定义
- 异常定义

### gateway-sdk-core
核心 SDK 实现，提供：
- 连接管理
- 消息发送
- 事件处理
- 重试机制

### gateway-sdk-spring
Spring 框架集成支持，提供：
- 自动配置
- 注解支持

## 开发规范

### 代码质量
使用工具：
- JUnit 5：单元测试
- AssertJ：断言库
- Mockito：Mock 框架
- TestContainers：集成测试
- Spotbugs：静态分析
- Surefire：单元测试执行
- Failsafe：集成测试执行

### 代码风格
```bash
# 检查代码风格
mvn spotless:check -P checkstyle

# 自动格式化
mvn spotless:apply
```

### 测试要求
- 测试类名以 Tests 结尾
- 集成测试类名以 IT 结尾
- 测试方法名清晰表达测试意图
- 集成测试覆盖关键路径
- 性能测试覆盖核心场景

## 技术栈
- Netty 4.2.2
- Nacos 2.5.1
- JUnit 5.12.1
- AssertJ
- Mockito 5.18.0
- TestContainers 1.19.3
- SLF4J & Log4j2 2.23.1
- Jackson 2.13.5

## 开源协议
[MIT License](LICENSE) 