---
name: chungmaru-evidence-capture
description: Use when working in the Chungmaru repo and the task needs report-ready tracking of failures, experiments, verified improvements, regressions, decisions, GitHub PR updates, or Linear updates without creating many dirty raw JSON files.
---

# Chungmaru Evidence Capture

Use this repo-local skill whenever Chungmaru work needs traceable evidence for commits, PRs, Linear, presentation material, or final reports.

## Workflow

1. Run `git status --short --branch` and choose one primary lane.
2. Before changing code, classify the observed issue as `failure`, `experiment`, `improvement`, `regression`, `verification`, or `decision`.
3. Record only meaningful evidence in `docs/evidence/chungmaru-progress-log.jsonl` through `scripts/chungmaru_evidence.py`.
4. Keep raw runtime dumps out of Git unless they are compact curated fixtures.
5. Regenerate and read `docs/evidence/chungmaru-progress-index.md` before writing a PR or Linear update.
6. If Android realtime masking is involved, link `BBA-79` unless a newer issue explicitly replaces it.
7. Use `source_session_id` for the development/debug session and `registrar_session_id` for the session or automation that records the evidence.
8. Treat worktrees as development isolation, not as the evidence source of truth. Record the source worktree and branch in the ledger.

## Commands

Backfill the active branch:

```bash
python3 scripts/chungmaru_evidence.py backfill-git \
  --revision-range origin/main..HEAD \
  --linear-issue BBA-79 \
  --github-pr https://github.com/BbangYi/ChungMaru/pull/33 \
  --source-session-id 019e1b02-4966-7d50-ac1b-ec191478dcd6 \
  --registrar-session-id 019e3546-cfae-7450-880d-006c2f1102d5 \
  --worktree-role experiment \
  --source-branch codex/android-mask-latency-diagnostics \
  --integration-branch main \
  --write
```

Add a manual failure or improvement:

```bash
python3 scripts/chungmaru_evidence.py add \
  --lane android \
  --surface youtube-search \
  --event-type failure \
  --title "Short report title" \
  --problem "Observed behavior with screenshot/log context." \
  --cause "Most likely root cause or unknown split." \
  --change "What changed, if any." \
  --verification "Command or manual check result." \
  --outcome observed \
  --constraints C4,C5,C7 \
  --linear-issue BBA-79 \
  --source-session-id 019e1b02-4966-7d50-ac1b-ec191478dcd6 \
  --registrar-session-id 019e3546-cfae-7450-880d-006c2f1102d5 \
  --worktree-role experiment \
  --source-branch codex/android-mask-latency-diagnostics \
  --integration-branch main \
  --evidence-quality screenshot-backed
```

Validate:

```bash
python3 scripts/chungmaru_evidence.py validate
python3 scripts/chungmaru_evidence.py summarize
python3 scripts/chungmaru_evidence.py audit-worktrees
```

## Evidence Quality Bar

An entry is report-ready only when it has:

- observed problem or decision,
- one or more constraint codes from `docs/constraints-and-decisions.md`,
- verification or explicit remaining risk,
- GitHub PR, commit, issue, Linear issue, screenshot, or log reference.
- correct `source_session_id` and `registrar_session_id`,
- correct `worktree_role`, `source_branch`, and intended `integration_branch`,
- `evidence_quality` above `commit-only`.

Auto-backfilled commits are traceability entries, not final proof. Expand them only when they matter for the report.
