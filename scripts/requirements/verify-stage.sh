#!/usr/bin/env bash

set -euo pipefail

is_placeholder_or_empty() {
  local value="$1"
  local trimmed
  trimmed="$(echo "${value}" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
  [[ -z "${trimmed}" || "${trimmed}" == "待补充" ]]
}

usage() {
  cat <<'EOF'
用法:
  ./scripts/requirements/verify-stage.sh <stage> [--strict]

示例:
  ./scripts/requirements/verify-stage.sh 1
  ./scripts/requirements/verify-stage.sh stage1 --strict

说明:
  - 默认模式: 校验阶段文件完整性、需求ID覆盖、Gate状态字段存在。
  - strict模式: 额外要求追踪矩阵所有条目状态为 PASS 且证据不为空，GateStatus=PASS。
EOF
}

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

stage_raw="$1"
strict="false"
if [[ "${2:-}" == "--strict" ]]; then
  strict="true"
fi

case "${stage_raw}" in
  1|stage1|Stage1)
    stage_num="1"
    req_file="docs/requirements/01-阶段1-HTTP反向代理最小闭环.md"
    gate_file="docs/requirements/stage-gates/阶段1-Gate.md"
    matrix_file="docs/requirements/traceability/阶段1-需求追踪矩阵.tsv"
    ;;
  *)
    echo "[ERROR] 暂不支持阶段: ${stage_raw}。当前仅支持阶段1。"
    exit 2
    ;;
esac

echo "[INFO] 校验阶段: ${stage_raw} (stage=${stage_num})"

for f in "$req_file" "$gate_file" "$matrix_file"; do
  if [[ ! -f "$f" ]]; then
    echo "[ERROR] 文件不存在: $f"
    exit 3
  fi
done

if ! rg -q 'GateStatus：`(PENDING|PASS|FAIL)`' "$gate_file"; then
  echo "[ERROR] Gate 文件缺少 GateStatus 字段: $gate_file"
  exit 4
fi

if [[ "$strict" == "true" ]]; then
  if ! rg -q 'GateStatus：`PASS`' "$gate_file"; then
    echo "[ERROR] strict 模式要求 GateStatus=PASS: $gate_file"
    exit 5
  fi
fi

req_ids=()
while IFS= read -r line; do
  [[ -n "$line" ]] && req_ids+=("$line")
done < <(rg -o "(FR|NFR|AC)-${stage_num}-[0-9]+" "$req_file" | sort -u)
if [[ ${#req_ids[@]} -eq 0 ]]; then
  echo "[ERROR] 未从需求文档提取到需求ID: $req_file"
  exit 6
fi

matrix_ids=()
while IFS= read -r line; do
  [[ -n "$line" ]] && matrix_ids+=("$line")
done < <(tail -n +2 "$matrix_file" | awk -F '\t' '{print $1}' | sed '/^$/d' | sort -u)
if [[ ${#matrix_ids[@]} -eq 0 ]]; then
  echo "[ERROR] 追踪矩阵为空: $matrix_file"
  exit 7
fi

missing=0
for id in "${req_ids[@]}"; do
  if ! printf '%s\n' "${matrix_ids[@]}" | rg -q "^${id}$"; then
    echo "[ERROR] 追踪矩阵缺少需求ID: ${id}"
    missing=1
  fi
done
if [[ $missing -ne 0 ]]; then
  exit 8
fi

if [[ "$strict" == "true" ]]; then
  invalid=0
  error_count=0
  while IFS=$'\t' read -r id type task code test ac status evidence; do
    [[ "$id" == "需求ID" || -z "$id" ]] && continue
    row_errors=()

    if [[ "$status" != "PASS" ]]; then
      row_errors+=("状态!=PASS(当前=${status})")
    fi

    if is_placeholder_or_empty "${code}"; then
      row_errors+=("代码位置为空/待补充")
    fi
    if is_placeholder_or_empty "${test}"; then
      row_errors+=("测试用例为空/待补充")
    fi
    if is_placeholder_or_empty "${evidence}"; then
      row_errors+=("证据为空/待补充")
    fi

    if [[ ${#row_errors[@]} -gt 0 ]]; then
      echo "[ERROR] strict 失败: ${id} -> $(IFS='; '; echo "${row_errors[*]}")"
      invalid=1
      error_count=$((error_count + 1))
    fi
  done < "$matrix_file"
  if [[ $invalid -ne 0 ]]; then
    echo "[ERROR] strict 校验未通过，共 ${error_count} 条需求存在问题。"
    echo "[ERROR] 修复清单: 状态=PASS 且 代码位置/测试用例/证据 三列必须全部填写。"
    exit 9
  fi
fi

echo "[PASS] 阶段 ${stage_raw} 校验通过（strict=${strict}）"
