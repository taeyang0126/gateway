#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'USAGE'
用法:
  ./scripts/perf/stage1/evaluate-stability-thresholds.sh \
    --runtime-csv <path> \
    [--gc-log <path>] \
    [--rss-growth-threshold-pct 15] \
    [--fd-fluctuation-threshold-pct 10] \
    [--gc-p99-threshold-ms 200] \
    [--summary-out <path>]
USAGE
}

runtime_csv=""
gc_log=""
rss_threshold="15"
fd_threshold="10"
gc_threshold="200"
summary_out=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runtime-csv)
      runtime_csv="$2"
      shift 2
      ;;
    --gc-log)
      gc_log="$2"
      shift 2
      ;;
    --rss-growth-threshold-pct)
      rss_threshold="$2"
      shift 2
      ;;
    --fd-fluctuation-threshold-pct)
      fd_threshold="$2"
      shift 2
      ;;
    --gc-p99-threshold-ms)
      gc_threshold="$2"
      shift 2
      ;;
    --summary-out)
      summary_out="$2"
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

if [[ -z "$runtime_csv" || ! -f "$runtime_csv" ]]; then
  echo "[ERROR] runtime csv 不存在: $runtime_csv"
  exit 2
fi

if [[ -n "$gc_log" && ! -f "$gc_log" ]]; then
  echo "[ERROR] gc log 不存在: $gc_log"
  exit 3
fi

metrics_out="$(awk -F',' '
  NR == 1 { next }
  {
    count++;
    elapsedVals[count] = $2 + 0;
    rssVals[count] = $3 + 0;
    fdValsRaw[count] = $4 + 0;
    if (elapsedVals[count] > maxElapsed) maxElapsed = elapsedVals[count];
  }
  END {
    windowStart = 300;
    windowEnd = maxElapsed - 60;
    if (windowEnd <= windowStart) {
      print "ERROR:NO_STEADY_WINDOW";
      exit 1;
    }

    for (i = 1; i <= count; i++) {
      elapsed = elapsedVals[i];
      if (elapsed < windowStart || elapsed > windowEnd) {
        continue;
      }
      rss = rssVals[i];
      fd = fdValsRaw[i];

      if (baselineRss == 0) baselineRss = rss;
      if (rss > maxRss) maxRss = rss;

      fdCount++;
      fdSum += fd;
      fdVals[fdCount] = fd;
    }

    if (baselineRss == 0 || fdCount == 0) {
      print "ERROR:NO_BASELINE";
      exit 1;
    }
    if (maxRss == 0) maxRss = baselineRss;
    rssGrowthPct = ((maxRss - baselineRss) * 100.0) / baselineRss;

    fdMean = fdSum / fdCount;
    maxDeviationPct = 0.0;
    for (i=1; i<=fdCount; i++) {
      deviation = fdVals[i] - fdMean;
      if (deviation < 0) deviation = -deviation;
      deviationPct = (deviation * 100.0) / fdMean;
      if (deviationPct > maxDeviationPct) maxDeviationPct = deviationPct;
    }

    printf("baselineRssKb=%.0f\n", baselineRss);
    printf("maxRssKb=%.0f\n", maxRss);
    printf("rssGrowthPct=%.2f\n", rssGrowthPct);
    printf("fdMean=%.2f\n", fdMean);
    printf("fdMaxDeviationPct=%.2f\n", maxDeviationPct);
    printf("steadyWindowStartS=%.0f\n", windowStart);
    printf("steadyWindowEndS=%.0f\n", windowEnd);
  }
' "$runtime_csv")"

if [[ "$metrics_out" == ERROR:* ]]; then
  echo "[ERROR] 运行时指标计算失败: $metrics_out"
  exit 4
fi

eval "$metrics_out"

gc_p99_ms="N/A"
if [[ -n "$gc_log" ]]; then
  gc_values="$(awk '
    /Pause/ {
      value="";
      if (match($0, /[0-9]+(\.[0-9]+)?ms/)) {
        value=substr($0, RSTART, RLENGTH - 2);
      } else if (match($0, /[0-9]+(\.[0-9]+)?s/)) {
        value=substr($0, RSTART, RLENGTH - 1) * 1000;
      }
      if (value != "") print value;
    }
  ' "$gc_log" | sort -n)"

  if [[ -n "$gc_values" ]]; then
    gc_count="$(printf '%s\n' "$gc_values" | wc -l | tr -d ' ')"
    idx=$(( (gc_count * 99 + 99) / 100 ))
    gc_p99_ms="$(printf '%s\n' "$gc_values" | sed -n "${idx}p")"
  fi
fi

rss_pass="PASS"
fd_pass="PASS"
gc_pass="PASS"

awk -v value="$rssGrowthPct" -v threshold="$rss_threshold" 'BEGIN { exit (value <= threshold ? 0 : 1) }' || rss_pass="FAIL"
awk -v value="$fdMaxDeviationPct" -v threshold="$fd_threshold" 'BEGIN { exit (value <= threshold ? 0 : 1) }' || fd_pass="FAIL"
if [[ "$gc_p99_ms" != "N/A" ]]; then
  awk -v value="$gc_p99_ms" -v threshold="$gc_threshold" 'BEGIN { exit (value <= threshold ? 0 : 1) }' || gc_pass="FAIL"
fi

overall="PASS"
if [[ "$rss_pass" == "FAIL" || "$fd_pass" == "FAIL" || "$gc_pass" == "FAIL" ]]; then
  overall="FAIL"
fi

summary_text="$(cat <<SUMMARY
[INFO] 阈值评估结果
- rssGrowthPct: ${rssGrowthPct}% (阈值<=${rss_threshold}%) => ${rss_pass}
- fdMaxDeviationPct: ${fdMaxDeviationPct}% (阈值<=${fd_threshold}%) => ${fd_pass}
- gcPauseP99Ms: ${gc_p99_ms} (阈值<=${gc_threshold}) => ${gc_pass}
- overall: ${overall}
SUMMARY
)"

echo "$summary_text"

if [[ -n "$summary_out" ]]; then
  mkdir -p "$(dirname "$summary_out")"
  printf '%s\n' "$summary_text" >"$summary_out"
  echo "[INFO] 评估摘要输出: $summary_out"
fi

if [[ "$overall" != "PASS" ]]; then
  exit 5
fi
