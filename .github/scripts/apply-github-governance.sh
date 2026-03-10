#!/usr/bin/env bash
set -euo pipefail

REPO="${1:-}"
BRANCH="${2:-main}"

if [[ -z "${REPO}" ]]; then
  REPO="$(git config --get remote.origin.url | sed -E 's#(git@github.com:|https://github.com/)##; s#\.git$##')"
fi

if [[ -z "${REPO}" ]]; then
  echo "[ERROR] Repository를 찾을 수 없습니다. 예: ./apply-github-governance.sh Gimminu/capstone-design"
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

echo "[1/2] Repository merge 정책 적용: ${REPO}"
gh api \
  --method PATCH \
  -H "Accept: application/vnd.github+json" \
  "repos/${REPO}" \
  -f allow_merge_commit=false \
  -f allow_rebase_merge=false \
  -f allow_squash_merge=true \
  -f delete_branch_on_merge=true >/dev/null

echo "[2/2] Branch protection 적용: ${REPO} (${BRANCH})"
read -r -d '' payload <<JSON || true
{
  "required_status_checks": null,
  "enforce_admins": true,
  "required_pull_request_reviews": {
    "dismiss_stale_reviews": true,
    "require_code_owner_reviews": true,
    "required_approving_review_count": 1,
    "require_last_push_approval": true
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
1) Settings > Collaborators and teams 에서 팀원 권한을 Read로 설정
2) direct push 금지 여부가 main 브랜치 보호에 반영되었는지 확인
3) PR merge 전에 CODEOWNER(@Gimminu / @haapppy23) 리뷰 승인 흐름 점검
4) 본 설정은 status check 강제를 두지 않습니다 (사람 리뷰 중심 운영)
EOF
