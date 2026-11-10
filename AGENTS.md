# Pleos_CommuteCare 개발 및 에이전트 지침서 (AGENTS.md)

## 0. 핵심 개발 원칙 (General Principles)

이 문서는 현대자동차 소프트웨어 공모전 출품 프로젝트인 **Pleos_CommuteCare(출퇴근 맞춤형 프리컨디셔닝 & 인포테인먼트 에이전트)**의 유지보수 및 추가 기능 개발 시 모든 AI 어시스턴트와 개발자가 준수해야 하는 규칙입니다.

1. **기존 코드를 먼저 분석한 뒤 수정한다.** 실제 Source Code를 README나 임의 추측보다 항상 우선한다.
2. **현대자동차 Pleos Connect 생태계 및 SDK 호환성 유지**:
   - `ai.pleos.playground:Vehicle:2.3.2` 및 `FusedLocation:1.1.3` 인터페이스 계약을 준수한다.
   - AAOS(Android Automotive OS) 주행 안전 제한(`CarUxRestrictionsManager`) 및 차속 모니터링 구조를 훼손하지 않는다.
3. **요구사항이 불명확하면 임의로 구조나 API/config를 남발하지 않는다.**
4. **불필요한 대규모 리팩터링 및 불필요한 외부 의존성(라이브러리) 추가를 금지한다.**
5. **학술/공학적 근거 보존**:
   - 심리음향학(Psychoacoustics) 및 차량 조명공학(ipRGC, Hue-Heat, SAE 2018-01-0842) 기반 튜닝 로직의 근거 주석을 유지한다.
6. **보안 및 비밀정보 보호**:
   - OpenAI API 키, 공공데이터포털 기상청 서비스 키 등 민감정보는 절대 Git 커밋에 포함하지 않고 `.env` 및 gitignore로 격리한다.
7. **하이브리드 가용성 보장**:
   - 네트워크 두절 시에도 앱이 비정상 종료(ANR/Crash)되지 않도록 로컬 룰베이스 폴백(Fallback) 구조를 필수로 유지한다.
8. **테스트 및 빌드 검증 필수**:
   - 코드 수정 후에는 반드시 `./scripts/build-android.sh :app:compileDebugKotlin` 또는 `:app:assembleDebug`로 빌드 성공 여부를 검증하고 보고한다.
9. **심사위원 친화적 표준 주석 규격 준수 (kingwooseok/2026ESWContest_mobility_ACE 스타일 준용)**:
   - 모든 파일 상단에는 **`@file` 요약 블록**을 작성하여 파일의 목적과 동작 순서를 한눈에 요약한다.
   - 모든 주요 함수에는 **`@brief` (목적 및 동작 순서), `@param` (입력 인자), `@return` (반환값), `@throws`**를 명시하여 처음 보는 사람/심사위원도 1분 안에 동작 구조를 파악할 수 있도록 표준화한다.

---

## 1. 프로젝트 주요 아키텍처 및 역할

```text
android/app/src/main/java/ai/pleos/commutecare/
  ├── data/
  │   ├── location/
  │   │   └── PleosLocationManager.kt      # Pleos FusedLocation SDK 기반 실시간 GPS 및 주기적 날씨 동기화
  │   └── weather/
  │       └── KmaWeatherClient.kt          # 기상청 단기예보 실황 API 연동 및 Lambert 투영 좌표 변환
  ├── domain/
  │   ├── ai/
  │   │   └── AiBriefingEngine.kt          # OpenAI GPT-4o-mini 기반 맞춤 브리핑 및 로컬 폴백
  │   ├── audio/
  │   │   └── PsychoacousticEngine.kt      # 심리음향학 & 차량 소음 마스킹 기반 사운드/EQ 엔진
  │   ├── calendar/
  │   │   └── CalendarMailManager.kt       # 구글/아웃룩 캘린더 일정 및 중요 미확인 메일 연동
  │   ├── commute/
  │   │   └── SmartCommuteDetector.kt      # 시간대 + GPS 이동 방향 기반 "출근/퇴근 중이십니까?" 지능형 감지
  │   ├── learning/
  │   │   └── CommutePatternEngine.kt      # 요일/시간대/온도 조절 히스토리 기반 시계열 패턴 학습 (RNN-style)
  │   ├── lighting/
  │   │   └── AmbientLightingEngine.kt     # 그랜저 GN7 인터랙티브 무드램프 색상 튜닝 엔진
  │   ├── music/
  │   │   └── VehicleMusicManager.kt       # Android Media/YouTube Music 연동 인터페이스 및 큐레이션
  │   ├── news/
  │   │   └── AdaptiveNewsEngine.kt        # 나이/성별 추천 및 "요약해드릴까요?" 피드백 기반 관심사 학습
  │   ├── travel/
  │   │   └── TravelModeEngine.kt          # 주말/외곽 콘도 목적지 감지 및 여행 모드 POI/맛집 큐레이션
  │   ├── voice/
  │   │   └── PleosVoiceManager.kt         # Pleos TextToSpeech SDK 기반 차량 음성 안내 및 로컬 TTS 폴백 매니저
  │   └── Models.kt                        # 공통 도메인 데이터 모델
  ├── presentation/
  │   └── MainActivity.kt                  # IVI 전용 대화면 UI 및 에이전트 오케스트레이션
  └── vehicle/
      └── CommuteVehicleManager.kt         # Pleos Vehicle SDK (HVAC, Seat, Light) 및 자동 시동/배터리 보호 타이머
```

---

## 2. CI/CD 및 저장소 정책

- **원격 저장소**: GitHub Private (`https://github.com/eLunaaaa/Pleos_CommuteCare.git`)
- **CI 워크플로우**: `.github/workflows/ci.yml` (JDK 17 + Android SDK 34 환경에서 assembleDebug 검증)
- 작업 완료 후 반드시 변경 사항을 요약하고, `git push origin main`으로 형상을 동기화한다.
