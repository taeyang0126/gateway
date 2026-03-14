# Stage1 压测与稳定性脚本

## 1. 目标
- 对齐 `T1-206` / `AC-1-4`：执行 Gatling 压测并采集运行时稳定性指标。

## 2. 前置条件
- 已启动网关进程（拿到 PID）。
- 可访问压测目标接口（默认 `/api/ping`）。
- 已安装 Gatling，且满足以下任一条件：
  - `GATLING_HOME` 指向 Gatling 传统发行版（包含 `bin/gatling.sh`）。
  - `gatling.sh` 已加入 `PATH`。
  - `GATLING_HOME` 指向 Gatling Maven Bundle（包含 `mvnw`）。

## 3. 执行
```bash
./scripts/perf/stage1/run-stage1-ac4.sh \
  --gateway-url http://127.0.0.1:8080 \
  --gateway-pid <gateway_pid> \
  --duration-minutes 10 \
  --rps 2000 \
  --body-bytes 1024 \
  --scenario-path /api/ping \
  --scenario-method GET \
  --gc-log reports/stage1/ac4/gateway-gc.log \
  --output-dir reports/stage1/ac4/20260314-220000
```

## 4. 产物
- `runtime-metrics.csv`：运行时采样（rss/fd）。
- `stability-summary.txt`：阈值评估结果。
- `gatling-report.tgz`：Gatling 原始报告归档（默认不展开 js/style 静态资源目录）。
- `gatling-simulation.log.gz`：Gatling simulation 日志压缩包（便于快速归档和检索）。

## 4.1 本地压测 JVM 约束
- `run-stage1-ac4-local.sh` 启动网关时固定指定 JVM 内存参数。
- 默认值：`-Xms2g -Xmx2g`。
- 可通过环境变量覆盖：`GATEWAY_JVM_XMS`、`GATEWAY_JVM_XMX`。
- 本地脚本会先打包 `gateway-server` fat-jar，并在输出目录生成 `gateway-config.yml` 作为外置配置再启动网关。

## 5. 阈值
- RSS 增幅：`<= 15%`
- FD 波动：`<= ±10%`（稳态窗口定义为 `第5分钟` 到 `结束前60秒`，按均值计算最大偏差）
- GC 暂停 p99：`<= 200ms`（提供 GC 日志时评估）
