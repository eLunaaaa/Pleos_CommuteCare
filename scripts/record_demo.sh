#!/usr/bin/env bash
# ==============================================================================
# Pleos Flo (비서: 플로/Plo) 6대 시나리오 (출근/퇴근/여행 × 맑음/악천후)
# 바다 상어 마스코트 부팅 + 사전 생성 사용자 TTS 음성 인식 + 플로 TTS 응답 + 주행 녹화 스크립트
# ==============================================================================
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARTIFACTS_DIR="${PROJECT_ROOT}/artifacts"
RAW_DIR="${PROJECT_ROOT}/android/app/src/main/res/raw"
ADB="/Users/minseok/Library/Android/sdk/platform-tools/adb"
mkdir -p "${ARTIFACTS_DIR}" /tmp/plo_audio

echo "=========================================================="
echo "🦈 Pleos Flo (비서: 플로) 6대 시나리오 전체 자동 녹화 시작"
echo "=========================================================="

build_scenario_audio() {
    local idx="$1"
    local greet="$2"
    local user_wav="$3"
    local reply="$4"
    local out_m4a="/tmp/plo_audio/scenario_${idx}.m4a"

    say -v "Flo (한국어(한국))" -o "/tmp/plo_audio/greet_${idx}.aiff" "${greet}"
    say -v "Flo (한국어(한국))" -o "/tmp/plo_audio/reply_${idx}.aiff" "${reply}"

    # 29초 분량의 오디오 트랙 합성:
    # 0.5s: 부팅 물결 차임벨 / 3.2s: 플로 첫 인사 / 6.0s: 사용자 TTS 음성 / 10.2s: 플로 응답 TTS / 15.0s~28.0s: 배경 음악 화음
    ffmpeg -y \
        -f lavfi -t 29 -i "anullsrc=r=24000:cl=mono" \
        -i "/tmp/plo_audio/greet_${idx}.aiff" \
        -i "${user_wav}" \
        -i "/tmp/plo_audio/reply_${idx}.aiff" \
        -f lavfi -t 13 -i "sine=frequency=440:sample_rate=24000" \
        -filter_complex "
            [1:a]adelay=3200|3200,volume=1.2[g];
            [2:a]adelay=6000|6000,volume=1.4[u];
            [3:a]adelay=10200|10200,volume=1.2[r];
            [4:a]afade=t=in:st=0:d=1.5,afade=t=out:st=10:d=2.5,volume=0.06,adelay=15000|15000[m];
            [0:a][g][u][r][m]amix=inputs=5:duration=first:dropout_transition=0[out]
        " -map "[out]" -c:a aac -b:a 128k "${out_m4a}" -loglevel error
}

build_scenario_audio 1 \
    "민석님 안녕하세요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_1_morning_clear.wav" \
    "민석님, 오늘 서울은 18.5도 맑음입니다! 실내를 21도로 맞추고 9시 30분 주간 회의 브리핑과 출근 에너지 음악을 재생합니다."

build_scenario_audio 2 \
    "민석님 안녕하세요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_2_morning_rain.wav" \
    "현재 11도 강한 비가 내립니다. 실내를 포근한 24.5도 웜 앰버 공조로 높이고, 빗길 노이즈 마스킹 이퀄라이저와 출근 일정을 안내합니다."

build_scenario_audio 3 \
    "민석님 수고하셨어요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_3_evening_clear.wav" \
    "오늘 하루도 고생 많으셨어요 민석님! 성수동 자택으로 퇴근 경로를 안내하고 22도 릴랙스 공조와 저녁 드라이브 팝을 틀어드릴게요."

build_scenario_audio 4 \
    "민석님 수고하셨어요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_4_evening_rain.wav" \
    "비 오는 퇴근길 안전 운행하세요! 김서림 방지와 25도 온풍을 가동하고, 피로를 풀어줄 잔잔한 어쿠스틱 재즈를 재생합니다."

build_scenario_audio 5 \
    "민석님 즐거운 주말이에요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_5_travel_clear.wav" \
    "신나는 속초 여행이네요! 속초 델피노 리조트로 여행 모드를 켜고, 시원한 20.5도 해변 공조와 청량한 드라이브 뮤직을 재생합니다."

build_scenario_audio 6 \
    "민석님 안전 여행 되세요! 모빌리티 비서 플로입니다." \
    "${RAW_DIR}/user_voice_6_travel_rain.wav" \
    "미시령 구간 진눈깨비가 관측됩니다! 실내를 25.5도로 따뜻하게 데우고, 감속 안전 안내와 포근한 인디 포크 음악을 준비했습니다."

NAMES=(
    ""
    "demo_1_morning_clear.mp4"
    "demo_2_morning_rain.mp4"
    "demo_3_evening_clear.mp4"
    "demo_4_evening_rain.mp4"
    "demo_5_travel_clear.mp4"
    "demo_6_travel_rain.mp4"
)

for idx in 1 2 3 4 5 6; do
    OUT_NAME="${NAMES[$idx]}"
    REMOTE_MP4="/sdcard/raw_scenario_${idx}.mp4"
    RAW_LOCAL="/tmp/plo_audio/raw_scenario_${idx}.mp4"
    FINAL_MP4="${ARTIFACTS_DIR}/${OUT_NAME}"

    echo "----------------------------------------------------------"
    echo "🎬 [${idx}/6] ${OUT_NAME} 녹화 중 (바다 상어 부팅 + 음성 대화 + 주행)..."
    "${ADB}" shell am force-stop ai.pleos.commutecare
    "${ADB}" shell rm -f "${REMOTE_MP4}"
    sleep 1

    # 부팅 스플래시(상어 수면 상승)부터 빠짐없이 녹화하기 위해 screenrecord 선시작
    "${ADB}" shell screenrecord --size 1280x720 --bit-rate 4000000 --time-limit 29 "${REMOTE_MP4}" &
    REC_PID=$!
    sleep 1

    "${ADB}" shell am start --user 10 -n ai.pleos.commutecare/.presentation.MainActivity --ei demo_scenario "${idx}" >/dev/null
    sleep 29
    wait "${REC_PID}" 2>/dev/null || true
    sleep 1

    "${ADB}" pull "${REMOTE_MP4}" "${RAW_LOCAL}" >/dev/null
    ffmpeg -y -i "${RAW_LOCAL}" -i "/tmp/plo_audio/scenario_${idx}.m4a" \
        -c:v copy -c:a aac -shortest "${FINAL_MP4}" -loglevel error
    echo "✅ 완료: ${FINAL_MP4} ($(du -h "${FINAL_MP4}" | cut -f1))"
done

cp "${ARTIFACTS_DIR}/demo_1_morning_clear.mp4" "${ARTIFACTS_DIR}/demo_showcase.mp4"
echo "=========================================================="
echo "🎉 6대 시나리오 시연 영상 추출 및 오디오 합성 완료!"
echo "=========================================================="
