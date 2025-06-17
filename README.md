# Netty Gateway

基于Netty实现的分布式长连接网关服务，用于管理和维护客户端与服务端之间的长连接通信。支持网关集群化部署，并为业务服务提供简单的消息推送能力。

## 功能特性

### 已实现功能
- 连接管理
  - 支持客户端与网关建立长连接
  - 提供客户端认证机制
  - 维护连接会话信息
  - 支持连接的优雅关闭
  - 实现心跳机制保活

- 消息处理
  - 自定义二进制消息协议
  - 支持消息校验和
  - 支持扩展字段
  - 支持不同类型消息：认证、心跳、业务消息等
  - 支持消息的双向传输

- 路由转发
  - 支持基于业务类型的消息路由
  - 支持路由的负载均衡
  - 支持 Nacos 服务发现

- 会话管理
  - 维护客户端会话状态
  - 支持会话属性的存储和读取
  - 支持会话的超时管理

### 计划功能
- 集群管理
- 分布式路由表
- 分布式会话存储
- 业务服务SDK
- 监控和告警
- 安全机制

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
```bash
# 启动网关服务端
cd gateway-server
java -jar target/gateway-server-${version}.jar
```

## 项目结构
```
gateway/
├── gateway-server/        # 网关服务端
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/
│   │   │   │   └── com/lei/java/gateway/
│   │   │   │       ├── codec/      # 消息编解码
│   │   │   │       ├── common/     # 公共工具
│   │   │   │       ├── config/     # 配置管理
│   │   │   │       ├── connection/ # 连接管理
│   │   │   │       ├── handler/    # 消息处理
│   │   │   │       ├── protocol/   # 协议定义
│   │   │   │       ├── route/      # 路由管理
│   │   │   │       └── session/    # 会话管理
│   │   │   └── resources/
│   │   └── test/
│   └── pom.xml
├── gateway-client/        # 网关客户端SDK
│   ├── src/
│   │   ├── main/
│   │   └── test/
│   └── pom.xml
└── pom.xml               # 父工程POM
```

## 开发规范

### 代码质量
项目使用以下工具确保代码质量：
- JUnit 5：单元测试框架
- AssertJ：流式断言库
- Mockito：测试模拟框架
- TestContainers：集成测试支持
- spotbugs: 静态代码bug查找工具
- surefire: 单元测试
- failsafe 集成测试

### 代码风格检查
- Checkstyle：代码风格检查
- spotless: 代码格式检查

#### 命令行检查
```bash
# 代码格式检查
mvn spotless:check -P checkstyle

# 代码格式化
mvn spotless:apply
```

### 测试规范
1. 单元测试要求
   - 测试类名以 Tests 结尾
   - 集成测试类名以 IT 结尾
   - 测试方法名清晰表达测试意图
   - 每个测试方法只测试一个场景
   - 使用 AssertJ 的流式断言提高可读性

2. 测试覆盖率要求
   - 整体行覆盖率不低于80%
   - 核心业务逻辑覆盖率不低于90%

3. 测试最佳实践
   - 使用 `@BeforeEach` 和 `@AfterEach` 管理测试资源
   - 使用 Mockito 模拟外部依赖
   - 使用 TestContainers 进行集成测试
   - 保持测试代码整洁，遵循与主代码相同的代码规范
   - 优先使用 AssertJ 的断言方式

### 提交规范
提交代码前需要：
1. 通过所有单元测试和集成测试
2. 通过代码风格检查
3. 确保测试覆盖率达标
4. 遵循Git commit message规范

## 技术栈
- Netty 4.2.2：网络通信框架
- Nacos 2.5.1：服务注册与发现
- JUnit 5.12.1：单元测试框架
- AssertJ：流式断言库
- Mockito 5.18.0：测试模拟框架
- TestContainers 1.19.3：集成测试支持
- SLF4J & Log4j2 2.23.1：日志框架
- Jackson 2.13.5：JSON处理

## 文档
- [需求文档](PRD.md)

## 开源协议
[MIT License](LICENSE) 