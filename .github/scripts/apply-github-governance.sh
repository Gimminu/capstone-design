#!/usr/bin/env bash
set -euo pipefail

REPO="${1:-}"
BRANCH="${2:-main}"

if [[ -z "${REPO}" ]]; then
  REPO="$(git config --get remote.origin.url | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
fi

if [[ -z "${REPO}" ]]; then
  echo "[ERROR] Repository를 찾을 수 없습니다. 예: ./apply-github-governance.sh BbangYi/ChungMaru"
  exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
  echo "[ERROR] gh CLI가 필요합니다. https://cli.github.com/"
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  echo "[ERROR] GitHub 인증이 필요합니다. 먼저 'gh auth login'을 실행해주세요."
  exit 1
fi

upsert_label() {
  local name="$1"
  local color="$2"
  local description="$3"

  if gh api -H "Accept: application/vnd.github+json" "repos/${REPO}/labels/${name}" >/dev/null 2>&1; then
    gh api \
      --method PATCH \
      -H "Accept: application/vnd.github+json" \
      "repos/${REPO}/labels/${name}" \
      -f color="${color}" \
      -f description="${description}" >/dev/null
  else
    gh api \
      --method POST \
      -H "Accept: application/vnd.github+json" \
      "repos/${REPO}/labels" \
      -f name="${name}" \
      -f color="${color}" \
      -f description="${description}" >/dev/null
  fi
}

echo "[1/3] Repository merge 정책 적용: ${REPO}"
gh api \
  --method PATCH \
  -H "Accept: application/vnd.github+json" \
  "repos/${REPO}" \
  -f allow_merge_commit=false \
  -f allow_rebase_merge=false \
  -f allow_squash_merge=true \
  -f delete_branch_on_merge=true >/dev/null

echo "[2/3] Issue/PR label 정책 적용: ${REPO}"
upsert_label "false-positive" "D73A4A" "정상 문맥이 유해로 처리된 오탐 사례"
upsert_label "false-negative" "B60205" "유해 문맥이 누락된 미탐 사례"
upsert_label "needs-regression" "5319E7" "회귀 테스트 또는 수동 검증 근거가 필요한 항목"
upsert_label "realtime" "FBCA04" "실시간성, 지연, visible pipeline 관련 항목"
upsert_label "needs-evidence" "C5DEF5" "URL, 캡처, 로그 등 증거 보강이 필요한 항목"
upsert_label "ui-rendering" "1D76DB" "마스킹 위치, 입력창, flicker 등 렌더링 항목"
upsert_label "extension" "0E8A16" "Chrome Extension 영역"
upsert_label "reporting" "6F42C1" "보고서 근거, 문서화, 발표 자료 정리"

echo "[3/3] Branch protection 적용: ${REPO} (${BRANCH})"
read -r -d '' payload <<JSON || true
{
  "required_status_checks": null,
  "enforce_admins": false,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": false,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1,
    "require_last_push_approval": false
  },
  "restrictions": null,
  "required_linear_history": true,
  "allow_force_pushes": false,
  "allow_deletions": false,
  "required_conversation_resolution": true,
  "lock_branch": false,
  "allow_fork_syncing": true
}
JSON

gh api \
  --method PUT \
  -H "Accept: application/vnd.github+json" \
  "repos/${REPO}/branches/${BRANCH}/protection" \
  --input - <<<"${payload}" >/dev/null

cat <<EOF
[DONE] GitHub 보호 정책이 적용되었습니다.

추가 수동 설정(필수):
1) 기본 작업 흐름은 feature branch -> PR -> main 으로 유지
2) CODEOWNER(@Gimminu / @haapppy23) 리뷰 승인 흐름 점검
3) 관리자 직접 수정은 긴급 수정/정리 작업에만 사용
4) 본 설정은 status check 강제를 두지 않습니다 (사람 리뷰 중심 운영)
5) 이슈 분류는 Issue Form과 label 정책을 기준으로 유지
EOF
