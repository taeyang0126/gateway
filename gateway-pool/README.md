# gateway-pool

通用并发资源池，设计灵感来自 HikariCP 的 ConcurrentBag。

## 定位

提供与框架无关的高性能资源池实现，仅依赖 SLF4J，不依赖 Spring 或 Netty。

## 核心组件

| 类 | 职责 |
|---|---|
| `ConcurrentPool` | 资源池主体，负责借出/归还/淘汰 |
| `PoolEntry` | 池中资源条目的抽象 |
| `PoolEntryFactory` | 资源创建/销毁/校验的工厂接口 |
| `PoolConfig` | 池容量、超时等配置 |
| `IdleEvictor` | 空闲资源定时淘汰 |

## 构建

```bash
mvn clean test -pl gateway-pool
```
