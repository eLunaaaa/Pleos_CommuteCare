#!/usr/bin/env bash
#
# @file record_demo.sh
# @brief Pleos CommuteCare 에뮬레이터 화면 녹화 및 시연 비디오(MP4) 추출 스크립트
#

set -euo pipefail

ADB="/Users/minseok/Library/Android/sdk/platform-tools/adb"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${PROJECT_DIR}/artifacts"
OUTPUT_FILE="${OUTPUT_DIR}/demo_showcase.mp4"
REMOTE_FILE="/sdcard/demo_showcase.mp4"

mkdir -p "${OUTPUT_DIR}"

echo "=========================================================="
echo "🎬 Pleos CommuteCare 심사 시연 비디오 자동 녹화 시작"
echo "=========================================================="

# 1. 기기 연결 확인
if ! "${ADB}" get-state 1>/dev/null 2>&1; then
    echo "❌ ADB 기기가 연결되어 있지 않습니다."
    exit 1
fi

# 2. MainActivity를 최상단 포커스로 가져오기
echo "📱 [1/5] MainActivity 포커스 활성화 중..."
"${ADB}" shell am start --user 10 -n ai.pleos.commutecare/.presentation.MainActivity --activity-brought-to-front
sleep 2

# 3. 기존 원격 녹화 파일 정리
"${ADB}" shell rm -f "${REMOTE_FILE}"

# 4. 백그라운드에서 screenrecord 실행 (1280x720 16:9 비율, 35초 제한)
echo "🎥 [2/5] 화면 녹화 시작 (35초 자동 제한)..."
"${ADB}" shell screenrecord --size 1280x720 --bit-rate 4000000 --time-limit 35 "${REMOTE_FILE}" &
RECORD_PID=$!

sleep 3

# 5. 심사 5단계 원클릭 투어 버튼 클릭 ([2250, 377][2391, 415] -> x=2320, y=396)
echo "[3/5] '심사 5단계 시연 투어' 자동 트리거..."
"${ADB}" shell input tap 2320 396

echo "⏳ [4/5] 5단계 시연 진행 대기 중 (약 26초)..."
# 1단계: 출근 의도 자율 추론 (4s)
# 2단계: 프리컨디셔닝 & AI 브리핑 (5s)
# 3단계: 85km/h 소음 마스킹 EQ & 퀵패널 (5s)
# 4단계: 속초 델피노 리조트 내비 여행 전환 (5s)
# 5단계: 미탑승 안전 배터리 보호 (5s)
sleep 27

echo "🛑 녹화 종료 대기..."
wait "${RECORD_PID}" 2>/dev/null || true
sleep 2

# 6. 비디오 파일 로컬로 추출
echo "📥 [5/5] 녹화 파일 PC로 추출 중 -> ${OUTPUT_FILE}"
"${ADB}" pull "${REMOTE_FILE}" "${OUTPUT_FILE}"

FILE_SIZE=$(du -h "${OUTPUT_FILE}" | cut -f1)
echo "=========================================================="
echo "✅ 시연 영상 녹화 완료!"
echo "📍 저장 위치: ${OUTPUT_FILE} (크기: ${FILE_SIZE})"
echo "=========================================================="
