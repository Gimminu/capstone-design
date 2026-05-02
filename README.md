# Chungmaru / 청마루

모바일 앱 + 브라우저 익스텐션 기반 실시간 텍스트 유해성 필터링 에이전트 프로젝트입니다.

## 현재 구조

```text
android/                  Android Studio 메인 앱
extension/chrome/         Chrome Extension
backend/api/              유해성 분류 API 초안
backend/tests/            API 검증 및 회귀 테스트
shared/contracts/         앱-익스텐션-백엔드 공통 JSON 계약
shared/normalization/     텍스트 정규화
shared/policy/            민감도/카테고리 정책
shared/rules/             룰 기반 필터
evaluation/api-vs-ml/     판단 파이프라인 비교 실험
docs/                     협업 및 작업 가이드
scripts/                  보조 스크립트
```

## Android 위치 정리

댓글 수집용 접근성 서비스는 별도 `tools/` 프로젝트가 아니라 Android 메인 앱 안에서 관리합니다.

- 앱 진입점: `android/app/src/main/java/com/capstone/design/MainActivity.kt`
- 접근성 수집 서비스: `android/app/src/main/java/com/capstone/design/youtubeparser/YoutubeAccessibilityService.kt`
- YouTube 추출 로직: `android/app/src/main/java/com/capstone/design/youtubeparser/YoutubeCommentExtractor.kt`
- Instagram 추출 로직: `android/app/src/main/java/com/capstone/design/youtubeparser/InstagramCommentExtractor.kt`
- TikTok 추출 로직: `android/app/src/main/java/com/capstone/design/youtubeparser/TiktokCommentExtractor.kt`
- 업로드 주소 저장: `android/app/src/main/java/com/capstone/design/youtubeparser/UploadEndpointStore.kt`
- 접근성 서비스 설정: `android/app/src/main/res/xml/accessibility_service_config.xml`

즉, Android 관련 기능은 앞으로 `android/app/src/main/...` 아래에서만 수정하면 됩니다.

## Android 실행 방법

1. Android Studio에서 `android/`를 엽니다.
2. 앱을 설치한 뒤 첫 화면에서 업로드 서버 주소를 저장합니다.
3. `접근성 설정 열기` 버튼으로 접근성 권한을 켭니다.
4. YouTube, Instagram, TikTok 앱에서 댓글 화면을 열면 수집 로직이 동작합니다.

기본 업로드 주소는 Tailscale 기반 로컬 서버를 가정하며, 앱 첫 화면에서 변경할 수 있습니다.

## 협업 원칙

1. 기본 흐름은 `feature branch -> PR -> main` 입니다.
2. `main`은 가급적 직접 수정하지 않고, 리뷰 기록을 남깁니다.
3. 운영상 급한 수정은 관리자 확인 후 바로 반영할 수 있습니다.
4. 자세한 규칙은 `docs/github-collaboration-guide.md`에서 관리합니다.

## 관련 문서

처음 볼 문서는 아래 세 개만 보면 됩니다.

- [서비스 정의서](docs/service-definition.md): 프로젝트 범위와 구조
- [GitHub 협업 가이드](docs/github-collaboration-guide.md): 이슈/PR/머지 기준
- [개발 이력 및 개선 근거 기록](docs/engineering-history.md): 보고서에 쓸 개선 흐름

상세 기준은 필요할 때만 확인합니다.

- [제약 사항 및 의사결정 기록](docs/constraints-and-decisions.md)
- [보고서 작성 및 이력 관리 워크플로우](docs/reporting-workflow.md)
- [팀 작업 분담표](docs/team-ownership.md)
- [공통 계약 문서](shared/contracts/README.md)
