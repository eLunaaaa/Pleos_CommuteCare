#!/usr/bin/env bash
# ==============================================================================
# Pleos Flo (비서: 플로/Plo) 미리보기 시연 영상 자동 녹화 스크립트
# 1) 2D 미니멀 첨벙 상어 마스코트 + Pleos Flo 부팅 스플래시
# 2) 유튜브 뮤직 스타일 실시간 마이크 펄스 팝업 (사용자 TTS 음성 인식)
# 3) 노래방(가라오케) 스타일 실시간 볼드 하이라이트 브리핑 (플로 TTS 응답)
# 4) YouTube Music 연동 스트리밍 재생 & 실시간 GPS·차속 주행
# ==============================================================================
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS_DIR="${PROJECT_ROOT}/artifacts"
RAW_DIR="${PROJECT_ROOT}/android/app/src/main/res/raw"
ADB="/Users/minseok/Library/Android/sdk/platform-tools/adb"
mkdir -p "${ARTIFACTS_DIR}" /tmp/plo_audio

echo "=========================================================="
echo "🦈 Pleos Flo 미리보기 시연 영상 자동 녹화 시작"
echo "=========================================================="

say -v "Flo (한국어(한국))" -o "/tmp/plo_audio/greet_1.aiff" "민석님 안녕하세요! 모빌리티 비서 플로입니다. 무엇을 도와드릴까요?"
say -v "Flo (한국어(한국))" -o "/tmp/plo_audio/reply_1.aiff" "민석님, 오늘 서울은 18.5도 맑음입니다! 실내를 21도로 맞추고 9시 30분 주간 회의 브리핑과 출근 에너지 음악을 재생합니다."

ffmpeg -y \
    -f lavfi -t 29 -i "anullsrc=r=24000:cl=mono" \
    -i "/tmp/plo_audio/greet_1.aiff" \
    -i "${RAW_DIR}/user_voice_1_morning_clear.wav" \
    -i "/tmp/plo_audio/reply_1.aiff" \
    -i "${RAW_DIR}/music_viva_commute.wav" \
    -filter_complex "
        [1:a]adelay=3600|3600,volume=1.2[g];
        [2:a]adelay=6400|6400,volume=1.4[u];
        [3:a]adelay=10600|10600,volume=1.2[r];
        [4:a]volume=0.55,adelay=11000|11000[m];
        [0:a][g][u][r][m]amix=inputs=5:duration=first:dropout_transition=0[out]
    " -map "[out]" -c:a aac -b:a 128k "/tmp/plo_audio/preview_1.m4a" -loglevel error

# 액티비티를 미리 포그라운드에 예열하여 콜드스타트 없이 2D 첨벙 부팅 스플래시부터 즉시 녹화
"${ADB}" shell am start --user 10 -n ai.pleos.commutecare/.presentation.MainActivity >/dev/null
sleep 4

REMOTE_MP4="/sdcard/raw_preview.mp4"
RAW_LOCAL="/tmp/plo_audio/raw_preview.mp4"
FINAL_MP4="${ARTIFACTS_DIR}/demo_showcase.mp4"

"${ADB}" shell rm -f "${REMOTE_MP4}"
echo "🎥 화면 녹화 시작 (29초, 2D 상어 첨벙 부팅 -> 마이크 팝업 -> 노래방 브리핑 -> YouTube Music)..."
"${ADB}" shell screenrecord --size 1280x720 --bit-rate 4000000 --time-limit 29 "${REMOTE_MP4}" &
REC_PID=$!
sleep 1

"${ADB}" shell am start --user 10 --activity-single-top -n ai.pleos.commutecare/.presentation.MainActivity --ei demo_scenario 1 >/dev/null
sleep 29
wait "${REC_PID}" 2>/dev/null || true
sleep 1

"${ADB}" pull "${REMOTE_MP4}" "${RAW_LOCAL}" >/dev/null
ffmpeg -y -i "${RAW_LOCAL}" -i "/tmp/plo_audio/preview_1.m4a" \
    -c:v copy -c:a aac -shortest "${FINAL_MP4}" -loglevel error

echo "=========================================================="
echo "✅ 미리보기 시연 영상 완료: ${FINAL_MP4} ($(du -h "${FINAL_MP4}" | cut -f1))"
echo "=========================================================="
