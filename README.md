# capstone-design

모바일 앱 + 브라우저 익스텐션 기반 실시간 텍스트 유해성 필터링 에이전트 프로젝트입니다.

## GitHub 협업 규칙 (필수)

1. 모든 변경은 포크에서 작업 후 PR로 반영
2. `main` 직접 push 금지
3. 승인 규칙: `@Gimminu`/`@haapppy23`는 서로 승인, 다른 팀원 PR은 두 명 중 1명 승인
4. PR 템플릿 작성 필수 + 사람 리뷰로 충돌/오류 확인
5. `main` 머지 시 자동 버전 태그(`v0.0.x`)와 Release 이력 생성
6. 최종 승인/머지 판단은 사람이 수행

## 최소 폴더 구조

```text
android/                  # Android Studio 앱
extension/chrome/         # Chrome Extension
backend/api/              # 유해성 분류 API
backend/tests/            # API 검증/회귀 테스트
shared/normalization/     # 텍스트 정규화
shared/rules/             # 욕설/혐오/스팸 룰 패턴
shared/policy/            # 민감도/카테고리 정책
shared/contracts/         # 앱-익스텐션-백엔드 공통 계약
evaluation/api-vs-ml/     # API vs ML 비교 실험
scripts/new-branch.sh     # 표준 브랜치 생성 보조 스크립트
docs/team-ownership.md    # 팀원별 작업 위치/역할
```

문서:
- [협업 가이드](docs/github-collaboration-guide.md)
- [팀 작업 분담표](docs/team-ownership.md)

브랜치 생성 보조(로컬):

```bash
./scripts/new-branch.sh feat android-text-capture --push
```
