# Pleos_CommuteCare 개발 및 에이전트 지침서 (AGENTS.md)

## 0. 핵심 개발 원칙 (General Principles)

이 문서는 현대자동차 소프트웨어 공모전 출품 프로젝트인 **Pleos_CommuteCare(출퇴근 맞춤형 프리컨디셔닝 & 인포테인먼트 에이전트)**의 유지보수 및 추가 기능 개발 시 모든 AI 어시스턴트(Codex 등)와 개발자가 준수해야 하는 규칙입니다.

> [!IMPORTANT]
> **모든 개발자 및 AI 에이전트(Codex 등)는 코드를 작성하거나 점검하기 전에 반드시 프로젝트 루트의 `architecture.md`를 먼저 정독하고 개발해야 합니다.**  
> 전체 아키텍처, 5대 SDK 인터페이스 계약, 모듈별 역할 및 폴더 구조는 `architecture.md`에 상세히 정의되어 있습니다.

1. **기존 코드를 먼저 분석한 뒤 수정한다.** 실제 Source Code를 README나 임의 추측보다 항상 우선한다.
2. **`architecture.md` 기반 개발 준수**:
   - 신규 기능 추가, 리팩터링, 인터페이스 점검 시 반드시 `architecture.md`에 명시된 아키텍처 설계와 데이터 흐름을 준수한다.
3. **현대자동차 Pleos Connect 생태계 및 SDK 호환성 유지**:
   - `ai.pleos.playground:Vehicle:2.3.2`, `FusedLocation:1.1.3`, `TextToSpeech:3.3.1`, `SpeechToText:3.3.1`, `NaviHelper:3.0.5` 5대 SDK 인터페이스 계약을 준수한다.
   - AAOS(Android Automotive OS) 주행 안전 제한(`CarUxRestrictionsManager`) 및 차속 모니터링 구조를 훼손하지 않는다.
4. **요구사항이 불명확하면 임의로 구조나 API/config를 남발하지 않는다.**
5. **불필요한 대규모 리팩터링 및 불필요한 외부 의존성(라이브러리) 추가를 금지한다.**
6. **학술/공학적 근거 보존**:
   - 심리음향학(Psychoacoustics) 및 차량 조명공학(ipRGC, Hue-Heat, SAE 2018-01-0842) 기반 튜닝 로직의 근거 주석을 유지한다.
7. **보안 및 비밀정보 보호**:
   - OpenAI API 키, 공공데이터포털 기상청 서비스 키 등 민감정보는 절대 Git 커밋에 포함하지 않고 `.env` 및 gitignore로 격리한다.
8. **하이브리드 가용성 보장**:
   - 네트워크 두절 시에도 앱이 비정상 종료(ANR/Crash)되지 않도록 로컬 룰베이스 폴백(Fallback) 구조를 필수로 유지한다.
9. **테스트 및 빌드 검증 필수**:
   - 코드 수정 후에는 반드시 `./scripts/build-android.sh :app:compileDebugKotlin` 또는 `./scripts/build-android.sh testDebugUnitTest`로 빌드 및 테스트 성공 여부를 검증하고 보고한다.
10. **심사위원 친화적 표준 주석 규격 준수 (kingwooseok/2026ESWContest_mobility_ACE 스타일 준용)**:
    - 모든 파일 상단에는 **`@file` 요약 블록**을 작성하여 파일의 목적과 동작 순서를 한눈에 요약한다.
    - 모든 주요 함수에는 **`@brief` (목적 및 동작 순서), `@param` (입력 인자), `@return` (반환값), `@throws`**를 명시하여 처음 보는 사람/심사위원도 1분 안에 동작 구조를 파악할 수 있도록 표준화한다.

---

## 1. 프로젝트 아키텍처 및 세부 모듈 구조

상세한 소스코드 디렉터리 구조, 모듈별 세부 책임, 5대 SDK 연결 구조 및 데이터 흐름도는 **`architecture.md`**를 참조하십시오.

---

## 2. CI/CD 및 저장소 정책

- **원격 저장소**: GitHub Private (`https://github.com/eLunaaaa/Pleos_CommuteCare.git`)
- **CI 워크플로우**: `.github/workflows/ci.yml` (JDK 17 + Android SDK 34 환경에서 assembleDebug 검증)
- 작업 완료 후 반드시 변경 사항을 요약하고, `git push origin main`으로 형상을 동기화한다.
