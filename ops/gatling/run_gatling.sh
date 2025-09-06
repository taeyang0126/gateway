#!/bin/bash

# ==========================================================
#  Gatling 测试启动脚本
# ==========================================================

# 获取脚本所在的目录的绝对路径
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &> /dev/null && pwd)
# 基于脚本目录计算出项目根目录的绝对路径
PROJECT_ROOT_DIR=$(cd -- "$SCRIPT_DIR/../.." &> /dev/null && pwd)

echo "--- Starting Gatling Test ---"
echo "Project Root detected at: $PROJECT_ROOT_DIR"

# --- 配置 ---
# 包含Gatling测试的Maven模块名
GATLING_MODULE="gateway-server"

# 需要运行的模拟类 (Simulation) 的完整类名
SIMULATION_CLASS="com.lei.java.gateway.server.gatling.HttpSimulation"

# 为本次测试报告添加一个简单的描述
RUN_DESCRIPTION="HttpSimulation on $(date)"


# --- 执行 ---
mvn -f "${PROJECT_ROOT_DIR}/${GATLING_MODULE}/pom.xml" gatling:test \
  -Dgatling.simulationClass="${SIMULATION_CLASS}" \
  -Dgatling.runDescription="${RUN_DESCRIPTION}"

# 检查上一个命令的退出码
if [ $? -eq 0 ]; then
  echo "--- Gatling Test Finished Successfully ---"
else
  echo "--- Gatling Test Failed ---"
fi