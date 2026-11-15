# 🦈 Pleos Flo (플로) — 기상·생체리듬·차량 센서 융합형 AI 모빌리티 컴패니언

> **현대자동차그룹 차량용 앱 개발 공모전 (Pleos App Market) 출품작 (팀명: ACE)**  
> **한 줄 소개**: 운전자가 차량에 탑승하는 순간 귀여운 2D 상어 비서 **‘플로(Plo)’**가 실시간 기상·위치·주행 맥락을 선제 파악하여, 자연어 음성 대화 한 번으로 **공조(HVAC)·내비게이션·YouTube Music**과 **테슬라형 투명 글래스 대시보드**를 원샷 제어하는 AI 모빌리티 컴패니언 앱입니다.

---

## 1. 앱 이름 및 한 줄 소개

- **앱 이름**: **Pleos Flo** (음성 비서 호출명: **플로 / Plo**)
- **한 줄 소개**: 실시간 기상청 단기예보와 Pleos Connect 5대 공식 SDK(`Vehicle`, `FusedLocation`, `TextToSpeech`, `SpeechToText`, `NaviHelper`)를 결합해, 출근·퇴근·여행 및 날씨(맑음/악천후) 6대 시나리오에 맞춰 **음성 대화·노래방식 볼드 브리핑·글래스 공조 대시보드·YouTube Music**을 통합 제어하는 인포테인먼트 에이전트입니다.

---

## 2. 주요 산출물 및 APK / 시연 영상 위치

### 📦 실행 가능한 APK 파일 위치 (`apk/` 폴더)
- **공모전 제출 규격 APK**: [`apk/ACE_PleosFlo_release.apk`](apk/ACE_PleosFlo_release.apk) (동일 바이너리: [`apk/PleosFlo_release.apk`](apk/PleosFlo_release.apk), [`artifacts/Pleos_CommuteCare.apk`](artifacts/Pleos_CommuteCare.apk))

### 📄 공모전 접수양식(기획서) 및 소프트웨어 공학 설계 도표
- **공식 접수양식(기획서) Word 문서**: [`Pleos_Flo_공모전_접수양식_기획서.docx`](Pleos_Flo_공모전_접수양식_기획서.docx) (동일 사본: [`artifacts/Pleos_Flo_공모전_접수양식_기획서.docx`](artifacts/Pleos_Flo_공모전_접수양식_기획서.docx))
- **소프트웨어 공학 설계 분석 다이어그램 및 그래프 (`artifacts/report_figures/`)**:
  - [`artifacts/report_figures/fig1_system_concept.png`](artifacts/report_figures/fig1_system_concept.png) — 핵심 시스템 구상도 및 4단계 자율 케어 파이프라인
  - [`artifacts/report_figures/fig2_sw_architecture.png`](artifacts/report_figures/fig2_sw_architecture.png) — 4-계층 클린 아키텍처 및 Pleos 5대 SDK 인터페이스 설계도
  - [`artifacts/report_figures/fig3_uml_sequence.png`](artifacts/report_figures/fig3_uml_sequence.png) — 양방향 음성 대화 및 멀티도메인 제어 UML 시퀀스 다이어그램
  - [`artifacts/report_figures/fig4_engineering_graphs.png`](artifacts/report_figures/fig4_engineering_graphs.png) — 학술 근거(SAE 열쾌적성·ipRGC·심리음향 BPM) 및 하이브리드 폴백 응답성·MAU 분석 그래프
  - [`artifacts/report_figures/fig5_ui_showcase.png`](artifacts/report_figures/fig5_ui_showcase.png) — 4단계 핵심 UI/UX 인터랙션 흐름 (2D 상어 첨벙 부팅 → 마이크 팝업 → 노래방식 볼드 브리핑 → YouTube Music)
  - [`artifacts/report_figures/fig6_6scenarios_grid.png`](artifacts/report_figures/fig6_6scenarios_grid.png) — 출근·퇴근·여행 × 맑음·악천후 6대 시나리오 전수 검증 매트릭스 화면

### 🎬 6대 주행 시나리오 시연 영상 (`artifacts/` 폴더)
- **통합 대표 시연 영상**: [`artifacts/demo_showcase.mp4`](artifacts/demo_showcase.mp4)
- **시나리오 1 (출근 · 맑음)**: [`artifacts/demo_1_morning_clear.mp4`](artifacts/demo_1_morning_clear.mp4)
- **시나리오 2 (출근 · 우천)**: [`artifacts/demo_2_morning_rain.mp4`](artifacts/demo_2_morning_rain.mp4)
- **시나리오 3 (퇴근 · 맑음)**: [`artifacts/demo_3_evening_clear.mp4`](artifacts/demo_3_evening_clear.mp4)
- **시나리오 4 (퇴근 · 우천)**: [`artifacts/demo_4_evening_rain.mp4`](artifacts/demo_4_evening_rain.mp4)
- **시나리오 5 (여행 · 쾌청)**: [`artifacts/demo_5_travel_clear.mp4`](artifacts/demo_5_travel_clear.mp4)
- **시나리오 6 (여행 · 눈/비)**: [`artifacts/demo_6_travel_rain.mp4`](artifacts/demo_6_travel_rain.mp4)

---

## 3. 개발에 사용한 Pleos Connect 에뮬레이터 버전과 화면 규격

- **에뮬레이터 버전**: `Pleos_Connect_v2.1.0` (Android Automotive OS 14 / API Level 34, `x86_64`)
- **화면 규격 (해상도 및 밀도)**: `2560 × 1440` px (`160 dpi`, Landscape 와이드 차량용 디스플레이 규격, 좌측 32% Pleos 기본 시스템 설정 영역 비침범 레이아웃 적용)
- **연동 Pleos Connect 공식 SDK 5종**:
  - `ai.pleos.playground:Vehicle:2.3.2`
  - `ai.pleos.playground:FusedLocation:1.1.3`
  - `ai.pleos.playground:TextToSpeech:3.3.1`
  - `ai.pleos.playground:SpeechToText:3.3.1`
  - `ai.pleos.playground:NaviHelper:3.0.5`

---

## 4. 테스트 환경 및 필요한 설정

- **권장 개발/실행 환경**:
  - **JDK**: Java 17 (OpenJDK 17)
  - **Android SDK**: `compileSdk 34`, `targetSdk 34`, `minSdk 28`
- **선택적 환경 변수 설정 (`local.properties` 또는 `.env`)**:
  - 별도의 API 키 설정 없이도 **오프라인 하이브리드 룰베이스 폴백 엔진(`ComfortTuningAlgorithm`)** 및 **6대 시나리오 시뮬레이터**를 통해 모든 기능(음성 대화, 노래방식 브리핑, 테슬라형 30fps 공조/차선 애니메이션, YouTube Music 스트리밍)이 100% 정상 동작합니다.
  - 실시간 외부 API 연동을 원할 경우 `android/local.properties`에 아래 키를 선택적으로 입력할 수 있습니다:
    ```properties
    OPENAI_API_KEY=sk-...
    WEATHER_API_KEY=공공데이터포털_기상청_단기예보_인증키
    ```

---

## 5. 실행 방법

### 방법 A. 제출된 APK 바로 설치 및 실행 (권장)
`Pleos_Connect_v2.1.0` 에뮬레이터 실행 후 아래 명령어로 즉시 설치 및 실행할 수 있습니다.
```bash
adb install -r apk/ACE_PleosFlo_release.apk
adb shell am start -n ai.pleos.commutecare/.presentation.MainActivity
```

### 방법 B. 6대 시나리오(출근·퇴근·여행 × 맑음·악천후) 개별 자동 시연 실행
앱 우측 상단의 **`시나리오 1~6`** 배지를 터치하거나, 아래 ADB 명령어로 각 시나리오(2D 상어 첨벙 부팅 → 플로 음성 인사 → 음량 반응형 마이크 팝업 & 사용자 음성 인식 → 노래방식 볼드 브리핑 → 공조/내비/YouTube Music 제어)를 즉시 실행할 수 있습니다.
```bash
# 1: 출근·맑음 | 2: 출근·우천 | 3: 퇴근·맑음 | 4: 퇴근·우천 | 5: 여행·쾌청 | 6: 여행·눈비
adb shell am start --activity-single-top -n ai.pleos.commutecare/.presentation.MainActivity --ei demo_scenario 1
```

### 방법 C. 소스 코드 빌드 및 유닛 테스트 검증
```bash
./scripts/build-android.sh :app:assembleDebug testDebugUnitTest
```
