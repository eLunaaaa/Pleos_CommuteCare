# Pleos CommuteCare (플레오스 커뮤트케어)

현대자동차 Pleos Connect 생태계를 기반으로 하는 **출퇴근 맞춤형 스마트 프리컨디셔닝 & 인포테인먼트 에이전트**입니다.

## 📌 주요 기능
1. **스마트 프리컨디셔닝 (차량 공조 & 시트 케어)**
   - 출퇴근 일정에 맞춰 탑승 전 공조(HVAC) 사전 가동 및 목표 온도 설정
   - 계절/날씨에 맞춘 운전석 시트 열선 및 통풍 단계 자동 제어 (`ai.pleos.playground.vehicle.api.HVAC`, `Seat`)
2. **모닝 & 이브닝 데일리 브리핑**
   - 출근길: 당일 주요 일정, 기상 예보 및 미세먼지 수치, 관심사/테크 뉴스 요약 브리핑
   - 퇴근길: 저녁 일정, 퇴근길 교통 및 날씨, 릴랙스 시사 브리핑
3. **출퇴근 상황별 사운드 & 이퀄라이징**
   - 출근 모드: 집중력 및 활력을 돕는 에너지 사운드 & 보컬/베이스 중심 EQ 프리셋
   - 퇴근 모드: 피로 해소와 편안함을 주는 칠아웃/어쿠스틱 사운드 & 웜톤 EQ 프리셋
4. **AAOS 차량 주행 안전성 보장**
   - `CarUxRestrictionsManager` 연동을 통한 주행 중(Distraction Optimization) 화면 간소화 모드 전환
   - 차속 센서 실시간 모니터링 (`VehiclePropertyIds.PERF_VEHICLE_SPEED`)

## 🛠️ 기술 스택 & 환경
- **Target OS / SDK**: Android Automotive OS (AAOS), Android SDK 34 (minSdk 28)
- **Language**: Kotlin 1.9.24, Java 17
- **Vehicle SDK**: Pleos Vehicle SDK 2.3.2 (`ai.pleos.playground:Vehicle:2.3.2`)
- **Location SDK**: Pleos FusedLocation 1.1.3 (`ai.pleos.playground:FusedLocation:1.1.3`)
- **CI/CD**: GitHub Actions (Debug APK 빌드 검증 및 Artifact 업로드)

## 🚀 로컬 빌드 방법
```bash
./scripts/build-android.sh :app:assembleDebug
```
빌드 산출물: `android/app/build/outputs/apk/debug/app-debug.apk`
