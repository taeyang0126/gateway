#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'USAGE'
用法:
  ./scripts/perf/stage1/run-stage1-ac4-local.sh \
    [--duration-minutes 10] \
    [--rps 2000] \
    [--body-bytes 1024] \
    [--output-dir reports/stage1/ac4-local/<timestamp>] \
    [--gateway-port 18080] \
    [--upstream-port 19001]
USAGE
}

duration_minutes="10"
rps="2000"
body_bytes="1024"
output_dir=""
gateway_port="18080"
upstream_port="19001"
gateway_jvm_xms="${GATEWAY_JVM_XMS:-2g}"
gateway_jvm_xmx="${GATEWAY_JVM_XMX:-2g}"

while [[ $# -gt 0 ]]; do
  case "$1" in
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
    --output-dir)
      output_dir="$2"
      shift 2
      ;;
    --gateway-port)
      gateway_port="$2"
      shift 2
      ;;
    --upstream-port)
      upstream_port="$2"
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

if [[ -z "$output_dir" ]]; then
  output_dir="$ROOT_DIR/reports/stage1/ac4-local/$(date +%Y%m%d-%H%M%S)"
fi
mkdir -p "$output_dir"

gatling_home="${GATLING_HOME:-/tmp/gatling-dist/gatling-charts-highcharts-bundle-3.15.0}"
if [[ ! -x "$gatling_home/bin/gatling.sh" && ! -x "$gatling_home/mvnw" ]]; then
  echo "[ERROR] gatling 不可用: 未找到 $gatling_home/bin/gatling.sh 或 $gatling_home/mvnw"
  exit 2
fi

upstream_py="$output_dir/mock_upstream.py"
cat >"$upstream_py" <<PY
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        body = b"pong"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        _ = self.rfile.read(length) if length > 0 else b""
        body = b"pong"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return

if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", ${upstream_port}), Handler)
    server.serve_forever()
PY

pushd "$ROOT_DIR" >/dev/null

./mvnw -pl gateway-server -am -DskipTests compile >/dev/null
./mvnw -pl gateway-server -am -DskipTests -q dependency:build-classpath \
  -Dmdep.includeScope=runtime \
  -Dmdep.outputFile="$output_dir/runtime.classpath"

runtime_cp_file="$output_dir/runtime.classpath"
if [[ ! -f "$runtime_cp_file" ]]; then
  echo "[ERROR] 运行时 classpath 文件缺失: $runtime_cp_file"
  exit 3
fi
runtime_cp="$(cat "$runtime_cp_file")"
if [[ -z "$runtime_cp" ]]; then
  echo "[ERROR] 运行时 classpath 为空: $runtime_cp_file"
  exit 4
fi

echo "[INFO] 启动 gateway-server JVM 参数: -Xms${gateway_jvm_xms} -Xmx${gateway_jvm_xmx}"

python3 "$upstream_py" >"$output_dir/upstream.log" 2>&1 &
upstream_pid=$!

GATEWAY_PORT="$gateway_port" \
UPSTREAM_HOST="127.0.0.1" \
UPSTREAM_PORT="$upstream_port" \
ROUTE_PREFIX="/api/" \
CONNECT_TIMEOUT_MS="5000" \
READ_TIMEOUT_MS="5000" \
WRITE_TIMEOUT_MS="5000" \
java -Xms"${gateway_jvm_xms}" -Xmx"${gateway_jvm_xmx}" \
  -Xlog:gc*:file="$output_dir/gateway-gc.log":time \
  -cp "gateway-server/target/classes:${runtime_cp}" \
  com.lei.java.gateway.server.app.GatewayServerMain \
  >"$output_dir/gateway.log" 2>&1 &
gateway_pid=$!

cleanup() {
  kill "$gateway_pid" >/dev/null 2>&1 || true
  kill "$upstream_pid" >/dev/null 2>&1 || true
  wait "$gateway_pid" >/dev/null 2>&1 || true
  wait "$upstream_pid" >/dev/null 2>&1 || true
}
trap cleanup EXIT

for _ in {1..60}; do
  code="$(curl -sS -o /dev/null -w '%{http_code}' "http://127.0.0.1:${gateway_port}/health" || true)"
  if [[ "$code" == "200" ]]; then
    break
  fi
  sleep 1
done

echo "[INFO] gateway_pid=$gateway_pid upstream_pid=$upstream_pid output_dir=$output_dir"

GATLING_HOME="$gatling_home" "$SCRIPT_DIR/run-stage1-ac4.sh" \
  --gateway-url "http://127.0.0.1:${gateway_port}" \
  --gateway-pid "$gateway_pid" \
  --duration-minutes "$duration_minutes" \
  --rps "$rps" \
  --body-bytes "$body_bytes" \
  --scenario-path "/api/ping" \
  --scenario-method GET \
  --gc-log "$output_dir/gateway-gc.log" \
  --output-dir "$output_dir"

trap - EXIT
cleanup

echo "[INFO] 本地 AC-1-4 完成: $output_dir"

popd >/dev/null
