#!/bin/bash

# ---
# run.sh - 构建并启动网关服务，已集成 OpenTelemetry
# agent下载: https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases
# 前置条件：prometheus、jaeger、grafana、Loki、k8s中安装 otel collector
#
# 该脚本会自动执行以下操作：
# 1. 切换到项目根目录
# 2. 使用 Maven 构建项目并生成 Fat JAR
# 3. 使用预设的 JVM 和 OpenTelemetry 参数启动应用
# ---

# 立刻退出：如果任何命令失败，脚本将立即停止执行。
# 这可以防止在构建失败后，仍然尝试启动一个旧的或损坏的 JAR 文件。
set -e

# --- 变量配置区 (在此处修改可以轻松调整脚本行为) ---

# 1. 项目与构建配置
# 动态获取脚本所在目录
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
# 获取到项目根目录
PROJECT_ROOT=$(cd "$SCRIPT_DIR/../../" &>/dev/null && pwd)

MVN_PROFILES="artifact-fat-jar,fast"
JAR_SUB_PATH="gateway-server/target/gateway-server-0.1.0-SNAPSHOT.jar"
JAR_FILE_PATH="$PROJECT_ROOT/$JAR_SUB_PATH"

# 2. JVM 配置
# 使用数组来安全地处理带空格的参数
JAVA_OPTS=(
    "-Xms256m"
    "-Xmx512m"
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=${PROJECT_ROOT}/heapdumps/"
)

# 3. OpenTelemetry 配置
# 允许通过环境变量覆盖默认值，极大地提高了灵活性
OTEL_AGENT_PATH="$PROJECT_ROOT/tools/opentelemetry-javaagent.jar"
OTEL_SERVICE_NAME="${OTEL_SERVICE_NAME:-gateway-server}" # 如果环境变量未设置，则使用默认值
# 新的目标地址：Mac 主机的 localhost，指向 K8s 里暴露出来的 Collector 服务
OTEL_EXPORTER_OTLP_ENDPOINT="${OTEL_EXPORTER_OTLP_ENDPOINT:-http://localhost:5318}"

# 将所有 OTel 参数放入数组，清晰且安全
OTEL_OPTS=(
    "-Dotel.service.name=${OTEL_SERVICE_NAME}"

    # 1. 将所有信号(traces, logs, metrics)的导出器都设置为 otlp
    "-Dotel.traces.exporter=otlp"
    "-Dotel.logs.exporter=otlp"
    "-Dotel.metrics.exporter=otlp"

    # 2. 统一指定 OTLP 的目标地址
    "-Dotel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT}"
)


# --- 执行区 ---

# 切换到项目根目录
cd "$PROJECT_ROOT"

# 创建 heapdumps 目录，如果它不存在
mkdir -p ./heapdumps/

echo ">>> 1. 正在使用 Maven 构建项目 (Profiles: $MVN_PROFILES)..."
mvn -q clean install -P "$MVN_PROFILES"

echo ">>> 2. 构建完成。JAR 包路径: $JAR_FILE_PATH"

if [ ! -f "$JAR_FILE_PATH" ]; then
    echo "!!! 错误: JAR 文件未找到，请检查构建过程和路径配置。"
    exit 1
fi

echo ">>> 3. 准备启动网关服务..."
echo "    - 服务名称: ${OTEL_SERVICE_NAME}"
echo "    - OTLP 端点: ${OTEL_EXPORTER_OTLP_ENDPOINT}"

# 启动应用
java "${JAVA_OPTS[@]}" \
     -javaagent:"$OTEL_AGENT_PATH" \
     "${OTEL_OPTS[@]}" \
     -jar "$JAR_FILE_PATH"

echo ">>> 网关服务已启动。"