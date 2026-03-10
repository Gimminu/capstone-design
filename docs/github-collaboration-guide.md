# GitHub 협업 가이드 (포크 + PR + 리뷰 강제)

이 문서는 팀원이 GitHub 사용 경험이 적어도 동일한 방식으로 협업할 수 있도록 만든 운영 가이드입니다.

운영 원칙:
1. 최종 승인/머지 판단은 사람이 한다.
2. 자동화는 릴리즈 버전 태그 생성만 사용한다.

## 1) 우리가 적용한 기본 규칙

1. `upstream(main)`에 직접 push 금지
2. 작업은 반드시 개인 포크에서 진행
3. 모든 변경은 PR로만 반영
4. 최소 1명 승인 + CODEOWNER 승인 필요
5. PR 본문 템플릿 필수 작성
6. 머지는 `Squash merge`만 허용
7. `main`에 머지되면 자동으로 버전 태그(`v0.0.x`)와 Release 이력 생성

## 2) 관리자 1회 설정 방법

아래는 관리자 계정에서 한 번만 수행하면 됩니다.

```bash
gh auth login
./.github/scripts/apply-github-governance.sh Gimminu/capstone-design main
```

추가로 GitHub 웹 설정에서 반드시 확인:

1. `Settings > Collaborators and teams`
2. 팀원 권한을 `Read`로 설정 (포크 강제의 핵심)
3. 관리자만 `Write/Admin` 유지

권장: `@Gimminu`, `@haapppy23`는 리뷰/승인 권한(Write 이상)을 유지

## 3) 팀원 작업 방법 (반복)

역할/담당 경계는 [팀 작업 분담표](team-ownership.md)를 기준으로 한다.

### A. 포크 및 클론

1. GitHub에서 `Fork` 버튼 클릭
2. 개인 포크를 로컬에 클론

```bash
git clone https://github.com/<my-id>/capstone-design.git
cd capstone-design
git remote add upstream https://github.com/Gimminu/capstone-design.git
```

### B. 기능 브랜치 생성 및 작업

```bash
./scripts/new-branch.sh feat <short-topic> --push
# 코드 수정
git add .
git commit -m "feat: <what changed>"
```

참고:
1. GitHub 서버가 PR 시 브랜치를 자동 생성해주지는 않습니다.
2. 위 스크립트는 로컬에서 표준 브랜치를 자동 생성하고 필요 시 바로 push합니다.

### C. PR 생성

1. `origin(feat/...)` -> `upstream(main)` 으로 PR 생성
2. PR 템플릿 항목 작성
3. 리뷰 요청 후 피드백 반영

## 4) 리뷰/머지 흐름

1. 승인 규칙 충족
2. GitHub PR 화면에서 충돌(conflict)/오류 여부 확인
3. 필요한 경우 `upstream/main` 기준 rebase
4. `Squash merge`
5. 머지 후 브랜치 삭제
6. `main` push 트리거로 자동 버전 태그 + Release 생성

승인 규칙:

1. PR 작성자가 `@Gimminu`이면 `@haapppy23` 승인 필수
2. PR 작성자가 `@haapppy23`이면 `@Gimminu` 승인 필수
3. PR 작성자가 그 외 팀원이면 `@Gimminu` 또는 `@haapppy23` 중 1명 승인 필수

## 5) 충돌(conflict) 해결 기본

```bash
git fetch upstream
git checkout feat/<short-topic>
git rebase upstream/main
# 충돌 수정 후
git add <fixed-files>
git rebase --continue
git push --force-with-lease origin feat/<short-topic>
```

`--force-with-lease`는 rebase 후 내 원격 브랜치만 안전하게 갱신할 때 사용합니다.

## 6) 자주 발생하는 실패 원인

1. 포크가 아닌 upstream 브랜치에서 PR 생성함
2. PR 템플릿 미작성 또는 체크리스트 미체크
3. CODEOWNER 승인 없이 머지 시도
4. `main` 기준 최신 동기화 없이 오래된 브랜치로 PR
