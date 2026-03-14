#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
用法:
  ./scripts/perf/stage1/sample-runtime-metrics.sh <pid> <durationSeconds> <intervalSeconds> <outputCsv>

示例:
  ./scripts/perf/stage1/sample-runtime-metrics.sh 12345 1800 5 reports/stage1/runtime/stage1-runtime.csv
USAGE
}

if [[ $# -ne 4 ]]; then
  usage
  exit 1
fi

pid="$1"
duration_seconds="$2"
interval_seconds="$3"
output_csv="$4"

if [[ ! "$duration_seconds" =~ ^[0-9]+$ ]] || [[ ! "$interval_seconds" =~ ^[0-9]+$ ]]; then
  echo "[ERROR] durationSeconds 和 intervalSeconds 必须是正整数"
  exit 2
fi
if (( duration_seconds <= 0 || interval_seconds <= 0 )); then
  echo "[ERROR] durationSeconds 和 intervalSeconds 必须 > 0"
  exit 3
fi
if ! kill -0 "$pid" >/dev/null 2>&1; then
  echo "[ERROR] 进程不存在: pid=$pid"
  exit 4
fi

mkdir -p "$(dirname "$output_csv")"

echo "timestamp,elapsedSeconds,rssKb,fdCount" >"$output_csv"

for (( elapsed=0; elapsed<=duration_seconds; elapsed+=interval_seconds )); do
  if ! kill -0 "$pid" >/dev/null 2>&1; then
    echo "[WARN] 进程已退出，结束采样: pid=$pid"
    break
  fi

  timestamp="$(date +%s)"
  rss_kb="$(ps -o rss= -p "$pid" | awk 'NF>0{print $1;exit} END{if (NR==0) print 0}')"
  fd_count="$(lsof -p "$pid" 2>/dev/null | wc -l | tr -d ' ')"

  echo "$timestamp,$elapsed,$rss_kb,$fd_count" >>"$output_csv"

  if (( elapsed + interval_seconds <= duration_seconds )); then
    sleep "$interval_seconds"
  fi
done

echo "[INFO] 采样完成: $output_csv"
