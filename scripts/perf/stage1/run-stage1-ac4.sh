#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'USAGE'
用法:
  ./scripts/perf/stage1/run-stage1-ac4.sh \
    --gateway-url http://127.0.0.1:8080 \
    --gateway-pid 12345 \
    [--duration-minutes 10] \
    [--rps 2000] \
    [--body-bytes 1024] \
    [--scenario-path /api/ping] \
    [--scenario-method GET] \
    [--gc-log /path/to/gc.log] \
    [--output-dir reports/stage1/ac4/<timestamp>]

说明:
  - 依赖 Gatling，优先使用 GATLING_HOME/bin/gatling.sh。
  - 会并发执行运行时指标采样，并在结束后自动做阈值评估。
USAGE
}

gateway_url=""
gateway_pid=""
duration_minutes="10"
rps="2000"
body_bytes="1024"
scenario_path="/api/ping"
scenario_method="GET"
gc_log=""
output_dir=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --gateway-url)
      gateway_url="$2"
      shift 2
      ;;
    --gateway-pid)
      gateway_pid="$2"
      shift 2
      ;;
    --duration-minutes)
      duration_minutes="$2"
      shift 2
      ;;
    --rps)
      rps="$2"
      shift 2
      ;;
    --body-bytes)
      body_bytes="$2"
      shift 2
      ;;
    --scenario-path)
      scenario_path="$2"
      shift 2
      ;;
    --scenario-method)
      scenario_method="$2"
      shift 2
      ;;
    --gc-log)
      gc_log="$2"
      shift 2
      ;;
    --output-dir)
      output_dir="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] 未知参数: $1"
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$gateway_url" || -z "$gateway_pid" ]]; then
  echo "[ERROR] --gateway-url 和 --gateway-pid 必填"
  usage
  exit 2
fi

if ! kill -0 "$gateway_pid" >/dev/null 2>&1; then
  echo "[ERROR] 网关进程不存在: pid=$gateway_pid"
  exit 3
fi

gatling_mode=""
gatling_bin=""
gatling_home="${GATLING_HOME:-}"
if [[ -n "$gatling_home" && -x "$gatling_home/bin/gatling.sh" ]]; then
  gatling_mode="legacy-bin"
  gatling_bin="$gatling_home/bin/gatling.sh"
elif command -v gatling.sh >/dev/null 2>&1; then
  gatling_mode="legacy-bin"
  gatling_bin="$(command -v gatling.sh)"
elif [[ -n "$gatling_home" && -x "$gatling_home/mvnw" ]]; then
  gatling_mode="maven-bundle"
else
  echo "[ERROR] 未找到可用 Gatling，可选方式："
  echo "  1) 设置 GATLING_HOME 且包含 bin/gatling.sh"
  echo "  2) PATH 中存在 gatling.sh"
  echo "  3) 设置 GATLING_HOME 指向 Gatling Maven Bundle（含 mvnw）"
  exit 4
fi

if [[ -z "$output_dir" ]]; then
  output_dir="reports/stage1/ac4/$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$output_dir"

runtime_csv="$output_dir/runtime-metrics.csv"
summary_file="$output_dir/stability-summary.txt"
gatling_raw_dir="$output_dir/gatling-raw"
gatling_archive="$output_dir/gatling-report.tgz"
gatling_simulation_log_gz="$output_dir/gatling-simulation.log.gz"
duration_seconds=$(( duration_minutes * 60 ))

echo "[INFO] 输出目录: $output_dir"

echo "[INFO] 启动运行时采样..."
"$SCRIPT_DIR/sample-runtime-metrics.sh" "$gateway_pid" "$duration_seconds" 5 "$runtime_csv" &
sampler_pid=$!

cleanup() {
  if kill -0 "$sampler_pid" >/dev/null 2>&1; then
    kill "$sampler_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

echo "[INFO] 启动 Gatling 压测... mode=${gatling_mode}"
if [[ "$gatling_mode" == "legacy-bin" ]]; then
  BASE_URL="$gateway_url" \
  RPS="$rps" \
  DURATION_MINUTES="$duration_minutes" \
  BODY_BYTES="$body_bytes" \
  SCENARIO_PATH="$scenario_path" \
  SCENARIO_METHOD="$scenario_method" \
  "$gatling_bin" \
    -sf "$SCRIPT_DIR/gatling" \
    -s stage1.Stage1ProxySimulation \
    -rf "$gatling_raw_dir" \
    -rd "stage1-ac4"
else
  mkdir -p "$gatling_home/src/test/java/stage1"
  rm -f "$gatling_home/src/test/scala/stage1/Stage1ProxySimulation.scala"
  cp "$SCRIPT_DIR/gatling/Stage1ProxySimulation.java" \
    "$gatling_home/src/test/java/stage1/Stage1ProxySimulation.java"

  rm -rf "$gatling_home/target/gatling"
  (
    cd "$gatling_home"
    REPO_HOME="${REPO_HOME:-/tmp/gatling-m2}" \
    BASE_URL="$gateway_url" \
    RPS="$rps" \
    DURATION_MINUTES="$duration_minutes" \
    BODY_BYTES="$body_bytes" \
    SCENARIO_PATH="$scenario_path" \
    SCENARIO_METHOD="$scenario_method" \
    ./mvnw -q gatling:test -Dgatling.simulationClass=stage1.Stage1ProxySimulation
  )

  mkdir -p "$gatling_raw_dir"
  if [[ -d "$gatling_home/target/gatling" ]]; then
    cp -R "$gatling_home/target/gatling/." "$gatling_raw_dir/"
  fi
fi

if [[ -d "$gatling_raw_dir" ]]; then
  report_dir="$(find "$gatling_raw_dir" -mindepth 1 -maxdepth 1 -type d | head -n 1 || true)"
  if [[ -n "$report_dir" && -f "$report_dir/simulation.log" ]]; then
    gzip -c "$report_dir/simulation.log" >"$gatling_simulation_log_gz"
  fi
  tar -czf "$gatling_archive" -C "$gatling_raw_dir" .
  rm -rf "$gatling_raw_dir"
  echo "[INFO] Gatling 归档: $gatling_archive"
  if [[ -f "$gatling_simulation_log_gz" ]]; then
    echo "[INFO] Gatling simulation 日志: $gatling_simulation_log_gz"
  fi
fi

wait "$sampler_pid"
trap - EXIT

if [[ -n "$gc_log" ]]; then
  "$SCRIPT_DIR/evaluate-stability-thresholds.sh" \
    --runtime-csv "$runtime_csv" \
    --gc-log "$gc_log" \
    --summary-out "$summary_file"
else
  "$SCRIPT_DIR/evaluate-stability-thresholds.sh" \
    --runtime-csv "$runtime_csv" \
    --summary-out "$summary_file"
fi

echo "[INFO] AC-1-4 执行完成，结果目录: $output_dir"
