#!/usr/bin/env bash
set -euo pipefail

TYPE="${1:-}"
SLUG="${2:-}"
PUSH_FLAG="${3:-}"

if [[ -z "${TYPE}" || -z "${SLUG}" ]]; then
  echo "Usage: ./scripts/new-branch.sh <feat|fix|chore|docs|test> <short-topic> [--push]"
  exit 1
fi

case "${TYPE}" in
  feat|fix|chore|docs|test) ;;
  *)
    echo "[ERROR] Invalid type: ${TYPE}"
    echo "Allowed: feat, fix, chore, docs, test"
    exit 1
    ;;
esac

BRANCH="${TYPE}/${SLUG}"

if git show-ref --verify --quiet "refs/heads/${BRANCH}"; then
  echo "[ERROR] Local branch already exists: ${BRANCH}"
  exit 1
fi

if git remote get-url upstream >/dev/null 2>&1; then
  BASE_REMOTE="upstream"
else
  BASE_REMOTE="origin"
fi

BASE_REF="${BASE_REMOTE}/main"

echo "[1/3] Fetching ${BASE_REMOTE}/main"
git fetch "${BASE_REMOTE}" main

echo "[2/3] Creating branch ${BRANCH} from ${BASE_REF}"
git checkout -b "${BRANCH}" "${BASE_REF}"

if [[ "${PUSH_FLAG}" == "--push" ]]; then
  echo "[3/3] Pushing branch to origin"
  git push -u origin "${BRANCH}"
else
  echo "[3/3] Skip push (pass --push to publish immediately)"
fi

echo "[DONE] ${BRANCH}"
