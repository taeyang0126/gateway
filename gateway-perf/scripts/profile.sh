#!/usr/bin/env bash
# 用法：./scripts/profile.sh <gateway-pid> [duration-seconds]
# 依赖：async-profiler（ASYNC_PROFILER_HOME 环境变量），jcmd（JDK 内置）
set -euo pipefail

GATEWAY_PID="${1:-}"
DURATION="${2:-120}"
OUTPUT_DIR="target/profiling"

# ── 参数校验 ──────────────────────────────────────────────────────────────────

if [[ -z "$GATEWAY_PID" ]]; then
    echo "用法：$0 <gateway-pid> [duration-seconds]" >&2
    exit 1
fi

if ! kill -0 "$GATEWAY_PID" 2>/dev/null; then
    echo "错误：PID $GATEWAY_PID 不存在" >&2
    exit 1
fi

if [[ -z "${ASYNC_PROFILER_HOME:-}" ]] || [[ ! -x "$ASYNC_PROFILER_HOME/bin/asprof" ]]; then
    echo "错误：async-profiler 未安装，请设置 ASYNC_PROFILER_HOME 并确保 \$ASYNC_PROFILER_HOME/bin/asprof 可执行" >&2
    exit 1
fi

# ── jcmd 可用性检测 ───────────────────────────────────────────────────────────

JCMD_AVAILABLE=true
if ! command -v jcmd &>/dev/null; then
    echo "警告：jcmd 不可用，跳过 JFR 录制和堆内存采样"
    JCMD_AVAILABLE=false
fi

# ── 准备输出目录 ──────────────────────────────────────────────────────────────

mkdir -p "$OUTPUT_DIR"

# ── 启动 async-profiler（CPU + 分配，并行后台运行）────────────────────────────

echo "=== 启动 async-profiler CPU 采集（${DURATION}s）==="
"$ASYNC_PROFILER_HOME/bin/asprof" -e cpu -d "$DURATION" \
    -f "$OUTPUT_DIR/cpu-flamegraph.svg" "$GATEWAY_PID" &
ASPROF_CPU_PID=$!

echo "=== 启动 async-profiler 分配采集（${DURATION}s）==="
"$ASYNC_PROFILER_HOME/bin/asprof" -e alloc -d "$DURATION" \
    -f "$OUTPUT_DIR/alloc-flamegraph.svg" "$GATEWAY_PID" &
ASPROF_ALLOC_PID=$!

# ── 启动 JFR 录制 ─────────────────────────────────────────────────────────────

if [[ "$JCMD_AVAILABLE" == "true" ]]; then
    echo "=== 启动 JFR 录制（${DURATION}s）==="
    jcmd "$GATEWAY_PID" JFR.start \
        settings=profile \
        name=gateway-perf \
        "filename=$OUTPUT_DIR/gateway.jfr" \
        "duration=${DURATION}s" \
        "+jdk.ObjectAllocationSample#enabled=true" \
        "+jdk.GarbageCollection#enabled=true" \
        "+jdk.GCPhasePause#enabled=true" \
        "+jdk.ThreadPark#enabled=true" \
        "+jdk.MonitorWait#enabled=true" \
        "+jdk.CPULoad#enabled=true" \
        "+jdk.ObjectAllocationInNewTLAB#enabled=true" \
        "+jdk.ObjectAllocationOutsideTLAB#enabled=true" \
        "+jdk.ClassLoad#enabled=true" || true
fi

# ── 资源采样循环（每 5 秒） ───────────────────────────────────────────────────

CSV_FILE="$OUTPUT_DIR/resource-usage.csv"
echo "timestamp,cpu_percent,heap_used_mb,heap_max_mb" > "$CSV_FILE"

ITERATIONS=$(( DURATION / 5 ))
SOAK_COUNTER=0

for i in $(seq 1 "$ITERATIONS"); do
    TIMESTAMP=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    CPU=$(ps -p "$GATEWAY_PID" -o %cpu= 2>/dev/null | tr -d ' ' || echo "0")

    HEAP_USED_MB=0
    HEAP_MAX_MB=0
    if [[ "$JCMD_AVAILABLE" == "true" ]]; then
        HEAP_INFO=$(jcmd "$GATEWAY_PID" GC.heap_info 2>/dev/null || true)
        if [[ -n "$HEAP_INFO" ]]; then
            HEAP_USED_BYTES=$(echo "$HEAP_INFO" | grep -oP 'used \K[0-9]+' | head -1 || echo "0")
            HEAP_MAX_BYTES=$(echo "$HEAP_INFO" | grep -oP 'capacity \K[0-9]+' | head -1 || echo "0")
            HEAP_USED_MB=$(( ${HEAP_USED_BYTES:-0} / 1024 / 1024 ))
            HEAP_MAX_MB=$(( ${HEAP_MAX_BYTES:-0} / 1024 / 1024 ))
        fi
    fi

    echo "$TIMESTAMP,$CPU,$HEAP_USED_MB,$HEAP_MAX_MB" >> "$CSV_FILE"

    # Soak 测试期间每 60 秒额外采样一次堆内存（追加同一行格式）
    SOAK_COUNTER=$(( SOAK_COUNTER + 5 ))
    if (( SOAK_COUNTER >= 60 )) && [[ "$JCMD_AVAILABLE" == "true" ]]; then
        SOAK_COUNTER=0
        SOAK_TS=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        SOAK_INFO=$(jcmd "$GATEWAY_PID" GC.heap_info 2>/dev/null || true)
        if [[ -n "$SOAK_INFO" ]]; then
            SOAK_USED=$(echo "$SOAK_INFO" | grep -oP 'used \K[0-9]+' | head -1 || echo "0")
            SOAK_MAX=$(echo "$SOAK_INFO" | grep -oP 'capacity \K[0-9]+' | head -1 || echo "0")
            SOAK_USED_MB=$(( ${SOAK_USED:-0} / 1024 / 1024 ))
            SOAK_MAX_MB=$(( ${SOAK_MAX:-0} / 1024 / 1024 ))
            echo "$SOAK_TS,soak-sample,$SOAK_USED_MB,$SOAK_MAX_MB" >> "$CSV_FILE"
        fi
    fi

    sleep 5
done

# ── 等待 async-profiler 完成 ──────────────────────────────────────────────────

wait "$ASPROF_CPU_PID" 2>/dev/null || true
wait "$ASPROF_ALLOC_PID" 2>/dev/null || true

# ── 计算摘要 ──────────────────────────────────────────────────────────────────

# 只取正常采样行（排除 soak-sample 行）
NORMAL_ROWS=$(awk -F',' 'NR>1 && $2 != "soak-sample"' "$CSV_FILE")

PEAK_CPU=$(echo "$NORMAL_ROWS" | awk -F',' '{print $2}' | sort -n | tail -1)
AVG_CPU=$(echo "$NORMAL_ROWS" | awk -F',' '{sum+=$2; count++} END { if(count>0) printf "%.1f", sum/count; else print "0" }')
PEAK_HEAP=$(echo "$NORMAL_ROWS" | awk -F',' '{print $3}' | sort -n | tail -1)

# 内存趋势：起始 / 结束 / 最大
START_HEAP=$(echo "$NORMAL_ROWS" | awk -F',' 'NR==1 {print $3}')
END_HEAP=$(echo "$NORMAL_ROWS" | awk -F',' 'END {print $3}')

echo ""
echo "=== 资源利用率摘要 ==="
echo "峰值 CPU: ${PEAK_CPU}%  平均 CPU: ${AVG_CPU}%  峰值堆内存: ${PEAK_HEAP}MB"
echo ""
echo "=== 内存趋势摘要 ==="
echo "起始堆占用: ${START_HEAP}MB  结束堆占用: ${END_HEAP}MB  最大堆占用: ${PEAK_HEAP}MB"

# ── 堆内存增长检测（需求 12.4）────────────────────────────────────────────────

if [[ "$JCMD_AVAILABLE" == "true" ]] && [[ "${START_HEAP:-0}" -gt 0 ]]; then
    THRESHOLD=$(echo "$START_HEAP * 1.5" | bc | awk '{printf "%d", $1}')
    if [[ "${END_HEAP:-0}" -gt "$THRESHOLD" ]]; then
        echo ""
        echo "警告：堆内存增长超过 150%，可能存在内存泄漏（起始: ${START_HEAP}MB，结束: ${END_HEAP}MB，阈值: ${THRESHOLD}MB）"
        exit 1
    fi
fi

# ── CPU 利用率过低警告（需求 19.3）───────────────────────────────────────────

if (( $(echo "${AVG_CPU:-0} < 50" | bc -l) )); then
    echo ""
    echo "警告：网关 CPU 利用率过低（${AVG_CPU}%），测试可能未达到性能瓶颈，建议增加并发用户数"
fi

echo ""
echo "=== Profiling 完成 ==="
echo "CPU 火焰图：$OUTPUT_DIR/cpu-flamegraph.svg"
echo "分配火焰图：$OUTPUT_DIR/alloc-flamegraph.svg"
if [[ "$JCMD_AVAILABLE" == "true" ]]; then
    echo "JFR 录制：$OUTPUT_DIR/gateway.jfr"
fi
echo "资源监控：$CSV_FILE"
