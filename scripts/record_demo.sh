#!/usr/bin/env bash
set -euo pipefail

ADB="${HOME}/Library/Android/sdk/platform-tools/adb"
ARTIFACTS_DIR="$(pwd)/artifacts"
RAW_DIR="$(pwd)/android/app/src/main/res/raw"
mkdir -p "${ARTIFACTS_DIR}"
TMP_DIR="$(mktemp -d)"

echo "Waiting for emulator..."
"${ADB}" wait-for-device

"${ADB}" shell settings put global window_animation_scale 1
"${ADB}" shell settings put global transition_animation_scale 1
"${ADB}" shell settings put global animator_duration_scale 1

# Warm up the app process once so subsequent single-top intents trigger onNewIntent immediately with zero cold-start delay
"${ADB}" shell am start -W -n ai.pleos.commutecare/.presentation.MainActivity --ei demo_scenario 1 >/dev/null 2>&1 || true
sleep 3

NAMES=(
  "demo_1_morning_clear"
  "demo_2_morning_rain"
  "demo_3_evening_clear"
  "demo_4_evening_rain"
  "demo_5_travel_clear"
  "demo_6_travel_rain"
)

USER_WAVS=(
  "user_voice_1_morning_clear.wav"
  "user_voice_2_morning_rain.wav"
  "user_voice_3_evening_clear.wav"
  "user_voice_4_evening_rain.wav"
  "user_voice_5_travel_clear.wav"
  "user_voice_6_travel_rain.wav"
)

PLO_GREETS=(
  "민석님 안녕하세요! 플로예요. 오늘 아침은 쾌청한 날씨네요, 어디로 출발할까요?"
  "민석님 안녕하세요! 플로예요. 오늘 아침은 비가 내리고 있어요, 어디로 안내할까요?"
  "민석님 오늘 하루도 정말 고생 많으셨어요! 플로가 편안한 퇴근길을 준비했어요."
  "민석님 비 오는 저녁 퇴근길이네요. 플로가 따뜻하고 안전하게 모실게요."
  "민석님 설레는 주말 여행 날이네요! 플로와 함께 신나게 떠나볼까요?"
  "민석님 눈비가 내리는 여행길이네요. 플로가 안전을 최우선으로 케어할게요."
)

PLO_REPLIES=(
  "네 민석님! 강남 오피스로 안내할게요. 맑은 아침에 맞춰 실내를 상쾌한 21도로 맞추고 신나는 출근 음악을 재생합니다!"
  "네 민석님! 빗길 안전을 위해 김서림을 방지하고 따뜻한 24.5도 앰버 무드로 맞췄어요. 감성적인 빗길 음악을 틀어드릴게요!"
  "네 민석님! 판교에서 우리 집으로 출발합니다. 피로를 풀어줄 22도 릴랙스 공조와 잔잔한 로파이 음악을 켰어요!"
  "네 민석님! 비 오는 밤길 시야 확보를 위해 김서림을 제거하고 25도 온풍과 어쿠스틱 음악으로 포근하게 맞춰드릴게요!"
  "네 민석님! 속초 델피노 리조트로 출발합니다! 해안도로 드라이브에 어울리는 20.5도 쾌적 공조와 청량한 여행 음악을 재생할게요!"
  "네 민석님! 미시령 눈비 구간을 피해 안전 경로로 안내합니다. 25.5도 히터와 열선 시트를 켜고 포근한 인디 음악을 틀어드릴게요!"
)

for idx in 1 2 3 4 5 6; do
  arr_idx=$((idx - 1))
  name="${NAMES[$arr_idx]}"
  user_wav="${RAW_DIR}/${USER_WAVS[$arr_idx]}"
  greet_text="${PLO_GREETS[$arr_idx]}"
  reply_text="${PLO_REPLIES[$arr_idx]}"

  echo "=== Recording Scenario ${idx}: ${name} ==="

  say -v Yuna -r 185 -o "${TMP_DIR}/greet_${idx}.aiff" "${greet_text}"
  say -v Yuna -r 182 -o "${TMP_DIR}/reply_${idx}.aiff" "${reply_text}"
  ffmpeg -y -i "${TMP_DIR}/greet_${idx}.aiff" -ar 22050 -ac 1 "${TMP_DIR}/greet_${idx}.wav" >/dev/null 2>&1
  ffmpeg -y -i "${TMP_DIR}/reply_${idx}.aiff" -ar 22050 -ac 1 "${TMP_DIR}/reply_${idx}.wav" >/dev/null 2>&1

  # Start screenrecord first, then immediately send single-top intent so t=0s captures the 2D shark splash boot
  "${ADB}" shell "screenrecord --bit-rate 8000000 --time-limit 24 /sdcard/${name}.mp4" &
  REC_PID=$!
  sleep 0.5
  "${ADB}" shell am start --activity-single-top -n ai.pleos.commutecare/.presentation.MainActivity --ei demo_scenario "${idx}" >/dev/null 2>&1

  wait "${REC_PID}" || true
  sleep 1
  "${ADB}" pull "/sdcard/${name}.mp4" "${TMP_DIR}/${name}_raw.mp4" >/dev/null

  # Mix audio timeline matching MainActivity timings:
  # 3.0s: Plo greeting | 8.2s: User voice | 13.0s: Plo reply (karaoke bold) | 18.8s: YouTube Music track
  ffmpeg -y \
    -i "${TMP_DIR}/${name}_raw.mp4" \
    -i "${TMP_DIR}/greet_${idx}.wav" \
    -i "${user_wav}" \
    -i "${TMP_DIR}/reply_${idx}.wav" \
    -i "${RAW_DIR}/music_viva_commute.wav" \
    -filter_complex \
      "[1:a]adelay=3000|3000,volume=1.4[g]; \
       [2:a]adelay=8200|8200,volume=1.5[u]; \
       [3:a]adelay=13000|13000,volume=1.4[r]; \
       [4:a]adelay=18800|18800,volume=0.85[m]; \
       [g][u][r][m]amix=inputs=4:duration=longest:dropout_transition=0[aout]" \
    -map 0:v -map "[aout]" \
    -c:v libx264 -pix_fmt yuv420p -preset fast -crf 22 \
    -c:a aac -b:a 128k -shortest \
    "${ARTIFACTS_DIR}/${name}.mp4" >/dev/null 2>&1

  ls -lh "${ARTIFACTS_DIR}/${name}.mp4"
done

cp "${ARTIFACTS_DIR}/demo_1_morning_clear.mp4" "${ARTIFACTS_DIR}/demo_showcase.mp4"
rm -rf "${TMP_DIR}"
echo "All 6 scenario videos updated with 2D shark boot + mic popup + karaoke briefing + YouTube Music!"
