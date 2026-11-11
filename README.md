# Pleos CommuteCare (플레오스 커뮤트케어)

> **현대자동차 SDV 커넥티드카 맞춤형 지능형 프리컨디셔닝 & 인포테인먼트 에이전트**  
> *Hyundai Motor Pleos Connect Ecosystem Application*

---

## 🚘 1. 프로젝트 개요 (Overview)

**Pleos CommuteCare**는 현대자동차의 차세대 SDV(Software Defined Vehicle) OS 생태계인 **Pleos Connect** 환경에서 동작하는 프리미엄 차량용 인포테인먼트(IVI) 에이전트입니다.

운전자의 출퇴근 및 주말 드라이브 여정을 시작부터 끝까지 심리스(Seamless)하게 케어하기 위해, 차량의 5대 핵심 도메인(**공조/시트/조명 제어, 내비게이션 경로 분석, 실시간 GPS 측위, 온디바이스 음성 대화, 기상청/생성형 AI 브리핑**)을 유기적으로 결합하여 완성했습니다.

```
                           [ 탑승 전 / 출발 대기 ]
                                      │
          ┌───────────────────────────┴───────────────────────────┐
          ▼                                                       ▼
[ 전략 1: 내비 목적지 연동 (명시적) ]           [ 전략 2: 시계열+GPS 자율 추론 (암시적) ]
Pleos NaviHelper 목적지 POI 분석                 내비를 켜지 않는 통근자(65%+) 대상
("현대차 남양연구소", "속초 리조트" 등)          시간대(07~10시) + GPS 이동방향 벡터 + 패턴 학습
          │                                                       │
          └───────────────────────────┬───────────────────────────┘
                                      ▼
                      [ 지능형 통근/여행 의도 감지 & 제안 ]
                  "출근 중이십니까?" (음성 TTS 질문 + 대화면 터치 퀵패널)
                                      │
                                      ▼
                      [ 쾌적한 출퇴근 케어 올인원 실행 ]
  ├─ ① Pleos Vehicle: 맞춤 공조(21.5℃) 가동 + 운전석 열선 2단 (10분 미탑승 안전소화)
  ├─ ② Pleos Voice: OpenAI GPT-4o-mini + 기상청 실황 + 캘린더/메일 AI 음성 브리핑
  ├─ ③ Psychoacoustics: 차속(0~120km/h) 주행 로드노이즈 마스킹 EQ & YouTube Music
  ├─ ④ Interior Lighting: 디 올 뉴 그랜저(GN7) 인터랙티브 무드램프 색상 동기화
  └─ ⑤ Cabin Noise-Safe: 고속 주행 소음 시 STT 마이크 명령 + 원터치 퀵 리플라이
```

---

## 🏆 2. 현대자동차 Pleos Playground 5대 SDK 통합 명세

본 프로젝트는 현대자동차 Pleos Connect 생태계의 공식 SDK 5종을 100% 준수하여 완벽하게 통합했습니다.

| SDK 명칭 | 공식 버전 | 패키지/클래스 | 구현 기능 및 역할 |
|:---|:---:|:---|:---|
| **Vehicle SDK** | `2.3.2` | `ai.pleos.playground:Vehicle` | • 전체 캐빈 비트마스크(`0x75`) 공조 전원 및 목표 온도 제어<br>• 운전석 시트 열선(`0x1`) 3단계 제어<br>• 미탑승 배터리 방전 방지 10분 안전 타이머 및 주행 차속 연동 자동 해제 |
| **NaviHelper SDK** | `3.0.5` | `ai.pleos.playground:NaviHelper` | • `INaviService` AIDL 바인딩 및 목적지(`DestinationInfo`) 실시간 분석<br>• 목적지 POI 기반 출근/퇴근/주말 여행 모드 즉각 판정<br>• 실시간 도로 제한속도 수신 |
| **FusedLocation SDK** | `1.1.3` | `ai.pleos.playground:FusedLocation` | • 고도, 위도, 경도, 헤딩, 타임스탬프 실시간 수신<br>• 10분 주기 또는 2.0km 이동 시 Haversine 거리 기반 기상청 날씨 자동 동기화 |
| **TextToSpeech SDK** | `3.3.1` | `ai.pleos.playground:TextToSpeech` | • Pleos 온디바이스 음성 합성 엔진(`Mode.ON_DEVICE`) 기반 브리핑 음성 낭독<br>• 안드로이드 표준 TTS 엔진과의 하이브리드 폴백 구조 보장 |
| **SpeechToText SDK** | `3.3.1` | `ai.pleos.playground:SpeechToText` | • Pleos 음성인식 엔진(`Mode.ON_DEVICE`) 기반 자연어 명령 디스패치<br>• "출근 시작", "온도 올려줘", "뉴스 요약", "여행 모드" 등 음성 제어 |

---

## 💡 3. 하이브리드 출퇴근 감지 전략 (Hybrid Commute Intent Detection)

많은 운전자는 매일 다니는 익숙한 출근길에서 내비게이션을 설정하지 않는 경향이 있습니다. Pleos CommuteCare는 이 점에 착안하여 **이원화된 하이브리드 감지 파이프라인**을 제공합니다.

### 🎯 [전략 1] 내비게이션을 찍었을 때 (Explicit Intent - 우선순위 1)
- 운전자가 Pleos 내비게이션 앱에 목적지를 설정하면 `NaviHelperEventListener.onDestinationInfo`를 통해 목적지 POI와 주소를 실시간 수신합니다.
- 지능형 키워드 사전 매칭:
  - **출근 목적지**: "회사", "직장", "연구소", "사옥", "남양연구소", "양재본사", "테크노밸리" 등 → `CommuteType.MORNING`
  - **퇴근 목적지**: "자택", "우리집", "집", "아파트" 등 → `CommuteType.EVENING`
  - **주말 여행**: "리조트", "호텔", "콘도", "펜션", "해수욕장", "국립공원", "속초", "강릉" 등 → `CommuteType.TRAVEL` (여행 모드 자동 전환)

### 💡 [전략 2] 내비게이션을 찍지 않았을 때 (Implicit Inference - 우선순위 2)
- 내비 목적지가 비어있는 경우, 자체 개발한 **시계열 패턴 학습 + GPS 벡터 자율 추론 엔진**이 즉시 가동됩니다.
  1. **시간대 필터링**: 평일 오전 출근 시간대(07:00 ~ 10:30) 또는 저녁 퇴근 시간대(17:00 ~ 21:30)
  2. **GPS 이동 방향 벡터**: `FusedLocation`의 차량 현재 좌표와 자택/회사 위치 간의 상대 거리 및 벡터 방향성 감지
  3. **시계열 패턴 이력**: `CommutePatternEngine`에 축적된 요일별/시간대별 반복 통근 확률 결합
- 복합 조건 충족 시: `"[지능형 자율 추론] 평소 출근 시간대입니다. 현대차 본사로 출근 중이십니까?"` 능동 제안 및 음성 질문 발행.

---

## 🔬 4. 학술 및 공학적 근거 (Academic & Engineering Foundations)

| 분야 | 적용 기술 및 메커니즘 | 학술/공학적 근거 및 레퍼런스 |
|:---|:---|:---|
| **차량 조명공학** | 그랜저 GN7 무드램프 색상 튜닝<br>(모닝 사이언 `#00B0FF` vs 소프트 앰버 `#FFD54F`) | • **ipRGC (망막 감광성 신경절 세포)**: 460~480nm 청색광 파장을 통해 수면 관성(Sleep Inertia)을 해소하고 집중 각성 촉진.<br>• **Hue-Heat Hypothesis**: 겨울철 온색계(Warm Amber) 조명으로 주관적 체감 온도를 약 1.5~2.0℃ 상승시킴.<br>• **SAE 2018-01-0842**: 야간 주행 시 윈드실드 반사 및 동공의 암적응(Dark Adaptation) 방해 최소화. |
| **심리음향학** | 차속 연동 이퀄라이저 (EQ) & 소음 마스킹 | • **Fletcher-Munson 등감곡선**: 고속 주행(85km/h 이상) 시 발생하는 타이어/노면 저주파(100Hz) 로드노이즈를 극복하기 위해 베이스(+4.5dB) 및 보컬 대역(+3.0dB) 능동 보정.<br>• 우천 시 빗방울 충격음(2k~5kHz) 피로 완화를 위한 고음 감쇄(-1.0dB). |
| **기상공학** | 기상청 초단기실황 API 실시간 연동 | • 기상청 단기예보 격자 투영 수식(**Lambert Conformal Conic Projection**)을 순수 코틀린 수학 함수로 직접 구현하여 위경도 $\leftrightarrow$ 기상청 (nx, ny) 격자 좌표 무오차 변환. |
| **시계열 학습** | 개인화 공조 온도 수렴 (RNN-Style) | • 지수이동평균(EMA): $H_t = (1 - \alpha) \cdot H_{t-1} + \alpha \cdot X_t$ 공식을 적용하여 운전자의 수동 조절 피드백을 반영한 최적 온도로 점진적 수렴. |

---

## 🎬 5. 심사위원 5단계 원클릭 시연 투어 (Judge Demo Tour)

앱 화면 상단의 **`[🎬 심사 5단계 원클릭 투어]`** 버튼을 터치하면, 심사위원이 30초 안에 시스템의 전 과정을 한눈에 체감할 수 있는 오토메이션 시연이 실행됩니다.

1. **[1단계: 스마트 자율 추론]**: 아침 8:15 탑승 감지 $\rightarrow$ 내비 미설정 상황에서 시간대/GPS 이동 패턴 자율 추론 $\rightarrow$ `"출근 중이십니까?"` 능동 제안
2. **[2단계: 프리컨디셔닝 & AI 브리핑]**: 사용자 수락 $\rightarrow$ 맞춤 공조 21.5℃ 가동 + 기상청 실황 + 캘린더 일정 GPT-4o-mini 하이브리드 음성 브리핑(TTS) 자동 낭독
3. **[3단계: 소음 대응 하이브리드]**: 고속도로 진입 (가상 차속 85km/h) $\rightarrow$ 심리음향학 로드노이즈 보정(+4.5dB) + 소음 대비 대화면 원터치 퀵 리플라이 패널 등장
4. **[4단계: 내비 연동 여행 모드]**: Pleos NaviHelper: '속초 델피노 리조트' 목적지 설정 수신 $\rightarrow$ 주말 여행 모드 즉각 전환 (오렌지 앰비언트, 여행 플레이리스트, 주변 맛집/POI 브리핑)
5. **[5단계: 안전 배터리 보호]**: 정차 후 10분 미탑승 감지 $\rightarrow$ 배터리 방전 방지 자동 시동 꺼짐 세이프티 메커니즘 동작 완료

---

## 🧪 6. 단위 테스트 (Unit Tests) 검증 결과

코드의 신뢰성과 공학적 정확성을 입증하기 위해 도메인 핵심 로직에 대한 **14개 단위 테스트**를 구축하였으며, 100% 통과를 검증했습니다.

```bash
./scripts/build-android.sh testDebugUnitTest
```

- `SmartCommuteDetectorTest` (5 tests):
  - `testExplicitNaviDestination_Work` (내비 출근 목적지 연동): **PASS**
  - `testExplicitNaviDestination_Travel` (내비 주말 여행 목적지 연동): **PASS**
  - `testImplicitInference_MorningCommute` (내비 미설정 오전 출근 자율 추론): **PASS**
  - `testImplicitInference_EveningCommute` (내비 미설정 저녁 퇴근 자율 추론): **PASS**
  - `testNoCommute_Midday` (낮 시간대 오탐 방지): **PASS**
- `CommutePatternEngineTest` (3 tests):
  - `testInitialState_NoAutoGreeting`: **PASS**
  - `testAutoGreetingUnlockAfterThreeTrips`: **PASS**
  - `testTemperatureConvergence` (EMA 온도 수렴): **PASS**
- `PsychoacousticEngineTest` (3 tests):
  - `testRainyAcousticPreset` (우천 소음 마스킹): **PASS**
  - `testHotSummerHVACMaskingPreset` (폭염 블로워 노이즈 마스킹): **PASS**
  - `testMorningCommutePreset` (수면 관성 해소): **PASS**
- `AmbientLightingEngineTest` (3 tests):
  - `testMorningCircadianLighting` (ipRGC 사이언 파장): **PASS**
  - `testWinterHueHeatLighting` (Hue-Heat 웜 앰버): **PASS**
  - `testEveningRelaxLighting` (SAE 2018-01-0842 야간 암적응): **PASS**

---

## 🚀 7. 빌드 및 에뮬레이터 설치 가이드

### 1) 빌드 실행
```bash
./scripts/build-android.sh :app:assembleDebug
```
산출물: `android/app/build/outputs/apk/debug/app-debug.apk`

### 2) Pleos Connect 에뮬레이터 설치 & 실행
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p ai.pleos.commutecare -c android.intent.category.LAUNCHER 1
```

---

## 🔒 8. 보안 및 환경변수 설정
- 기상청 공공데이터 포털 API 키 및 OpenAI API 키는 프로젝트 루트의 `.env` 파일에서 관리되며, Git 커밋에 절대 포함되지 않습니다.
```properties
OPENAI_API_KEY=your_openai_key_here
KMA_SERVICE_KEY=your_kma_service_key_here
```
