# 팀 작업 분담표 (단일 책임)

목표: 담당 기능을 분리해서 충돌과 부수효과를 최소화한다.

## 1) 사람별 단일 책임

- 김성현
  - 책임: 앱 내 텍스트 수집(Accessibility/수집 파이프라인), GitHub PR 모니터링
  - 주 작업 폴더: `android/` (수집 관련), `docs/`

- 김민우
  - 책임: 사용자 설정/마스킹/로깅 UI 제어, 에이전트 정책(민감도/카테고리)
  - 주 작업 폴더: `android/` (UI 관련), `extension/chrome/`, `shared/policy/`

- 이승우
  - 책임: 문맥 기반 유해성 분류 API, 판단 파이프라인 비교 테스트
  - 주 작업 폴더: `backend/api/`, `backend/tests/`, `evaluation/api-vs-ml/`

- 정선재
  - 책임: 텍스트 정규화, 욕설/혐오/스팸 패턴 탐지
  - 주 작업 폴더: `shared/normalization/`, `shared/rules/`

## 2) 영향 최소화 규칙

1. PR 1개는 기능 1개만 다룬다.
2. PR 1개에서 최상위 폴더는 가능하면 1개만 수정한다.
3. 팀 간 연결은 `shared/contracts/`를 통해서만 맞춘다.
4. 다른 사람 담당 폴더 수정이 필요하면 먼저 이슈/코멘트로 합의 후 진행한다.
5. `shared/contracts/` 변경 PR과 실제 구현 PR은 분리한다.

## 3) 브랜치 생성

GitHub 서버가 PR 시 브랜치를 자동 생성해주지는 않는다.
로컬에서 아래 스크립트로 표준 브랜치를 생성한다.

```bash
./scripts/new-branch.sh feat android-text-capture --push
```

- 기본 베이스: `upstream/main` (없으면 `origin/main`)
- 이름 규칙: `<type>/<short-topic>`
- type: `feat|fix|chore|docs|test`
