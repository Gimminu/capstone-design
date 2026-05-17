# Chungmaru Evidence Index

Generated: 2026-05-17T11:17:13Z
Source log: `docs/evidence/chungmaru-progress-log.jsonl`

This file is generated from the JSONL ledger. Edit the ledger through `scripts/chungmaru_evidence.py`.

## Summary

| Metric | Count |
| --- | ---: |
| Total entries | 17 |
| Report-ready entries | 5 |

### Event type

| Value | Count |
| --- | ---: |
| decision | 3 |
| failure | 1 |
| improvement | 11 |
| verification | 2 |

### Lane

| Value | Count |
| --- | ---: |
| android | 12 |
| docs-evaluation | 5 |

### Outcome

| Value | Count |
| --- | ---: |
| committed | 10 |
| improved | 1 |
| observed | 1 |
| verified | 5 |

### Evidence quality

| Value | Count |
| --- | ---: |
| legacy | 12 |
| report-ready | 5 |

### Worktree role

| Value | Count |
| --- | ---: |
| legacy | 15 |
| reporting | 2 |

## Recent Evidence

| Date | Type | Lane | Title | Quality | Ready | Worktree | Constraints | Outcome | Evidence / Risk |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-05-17 | decision | docs-evaluation | Keep full history in ledger and curate Notion around review focus | report-ready | yes | reporting | C1, C8 | verified | The Notion board can become noisy if every development event is copied there, while the capstone review needs a small set of representative model, parser, and ... |
| 2026-05-17 | decision | docs-evaluation | Adopt worktree isolation for ongoing development sessions | report-ready | yes | reporting | C1, C8 | verified | Development sessions will continue in parallel, so the official checkout can become dirty with Android/backend/extension experiments while report evidence and ... |
| 2026-05-17 | verification | docs-evaluation | Freeze parser raw-to-cleaned before-after fixtures | report-ready | yes | legacy | C1, C5, C7, C8 | verified | The review checklist requires raw JSONL vs cleaned JSONL examples, UI text removal rules, cleaned JSONL to model input flow, and Android boundsInScreen usage e... |
| 2026-05-17 | verification | docs-evaluation | Freeze current model evaluation baseline snapshot | report-ready | yes | legacy | C1, C8 | verified | The capstone review checklist requires Accuracy, Precision, Recall, F1, FP, FN, score bucket errors, topic-bias checks, and FP/FN examples, but those facts wer... |
| 2026-05-17 | decision | docs-evaluation | Split development source sessions from evidence registration | report-ready | yes | legacy | C1, C8 | verified | Git history was becoming too deep while failures, experiments, and report evidence were not consistently recoverable across Codex sessions, repo docs, Notion, ... |
| 2026-05-17 | improvement | android | Clear untranslatable masks during YouTube scroll recapture gaps | legacy | no | legacy | C4, C5, C7, C8 | improved | Scroll translation could return ALL_OFFSCREEN, NO_TRANSLATABLE_MASKS, or REJECTED_DELTA without removing the existing overlay views, leaving stale masks visibl... |
| 2026-05-17 | failure | android | YouTube scroll and mini-player leave stale masks on wrong regions | legacy | no | legacy | C4, C5, C7 | observed | User screenshot shows masks remaining on profile/channel metadata and thumbnail regions while visible harmful text can still remain after scrolling; the mini-p... |
| 2026-05-17 | improvement | android | `8fd464c` Keep visual OCR alive during content churn | legacy | no | legacy | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `cd22bea` Tighten Android realtime visual masking | legacy | no | legacy | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `bb1e078` Stop rendering semantic visual fallback masks | legacy | no | legacy | C5 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `459c2b4` Promote cached semantic YouTube visual masks during scroll | legacy | no | legacy | C3, C4, C5, C6 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `33cbe7b` Reuse Android YouTube analysis across visual geometry changes | legacy | no | legacy | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `402ed74` Preserve visual masks during Android scroll refresh | legacy | no | legacy | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-16 | improvement | android | `1a98bca` Improve Android Shorts thumbnail OCR coverage | legacy | no | legacy | C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-16 | improvement | android | `6f7d8f4` Stabilize Android YouTube visual masking | legacy | no | legacy | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-15 | improvement | android | `ecc7bda` Use accessibility bounds for YouTube comments | legacy | no | legacy | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-15 | improvement | android | `d4ba7dd` Improve Android YouTube hybrid masking | legacy | no | legacy | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |

## Operating Rule

- Add one ledger entry per distinct failure, experiment, verified improvement, regression, or decision.
- Keep raw runtime dumps out of Git unless they are compact curated fixtures.
- Use commit history for traceability, but use this index for report-ready meaning.
- Before using an auto-backfilled commit in the final report, add concrete screenshot, log, test, or PR evidence.
- Use worktrees for risky or long-running development, but record durable evidence here with the source worktree and intended integration branch.
