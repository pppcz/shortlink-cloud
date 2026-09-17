#!/usr/bin/env bash
# =====================================================================
# 短链跳转压测脚本（wrk）
#
# 用法：
#   ./loadtest/wrk/redirect.sh [shortCode] [duration] [connections] [threads]
#
# 例：
#   ./loadtest/wrk/redirect.sh abc1234 30s 100 4
#
# 前置：
#   1. docker compose up -d 已启动全部服务
#   2. 已通过管理后台或 curl 创建一个短链，并拿到 shortCode
#   3. 已安装 wrk（macOS: brew install wrk；Ubuntu: apt install wrk）
#
# 说明：
#   * 压测前建议先把限流阈值调大，否则会被 429 挡住而测不出真实吞吐：
#       .env 中 RATE_LIMIT_PERMIT_PER_SECOND 设为 10000000
#   * 跳转是 302，wrk 默认不跟随重定向，能真实反映短链服务的处理能力。
# =====================================================================
set -euo pipefail

SHORT_CODE="${1:-${SHORT_CODE:-}}"
DURATION="${2:-30s}"
CONNECTIONS="${3:-100}"
THREADS="${4:-4}"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [[ -z "${SHORT_CODE}" ]]; then
  echo "用法: $0 <shortCode> [duration] [connections] [threads]"
  echo "或先 export SHORT_CODE=xxx"
  exit 1
fi

if ! command -v wrk >/dev/null 2>&1; then
  echo "错误: 未找到 wrk，请先安装（brew install wrk / apt install wrk）" >&2
  exit 1
fi

TARGET="${BASE_URL}/${SHORT_CODE}"
RESULT_DIR="$(dirname "$0")/../results"
mkdir -p "${RESULT_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_FILE="${RESULT_DIR}/wrk-redirect-${STAMP}.txt"

echo "=============================================="
echo " 目标        : ${TARGET}"
echo " 线程/连接   : ${THREADS} / ${CONNECTIONS}"
echo " 时长        : ${DURATION}"
echo " 结果文件    : ${OUT_FILE}"
echo "=============================================="

# 预热：让 JIT 与缓存先热起来，避免把冷启动误差算进结果
echo "[1/2] 预热 5 秒..."
wrk -t2 -c10 -d5s "${TARGET}" > /dev/null 2>&1 || true

echo "[2/2] 正式压测..."
wrk -t"${THREADS}" -c"${CONNECTIONS}" -d"${DURATION}" --latency "${TARGET}" | tee "${OUT_FILE}"

echo
echo "关键指标（请把下面三项填入 docs/benchmark.md）："
grep -E "Requests/sec|Latency|99%" "${OUT_FILE}" || true
