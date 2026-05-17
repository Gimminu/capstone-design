# Chungmaru Evidence Index

Generated: 2026-05-17T09:42:54Z
Source log: `docs/evidence/chungmaru-progress-log.jsonl`

This file is generated from the JSONL ledger. Edit the ledger through `scripts/chungmaru_evidence.py`.

## Summary

| Metric | Count |
| --- | ---: |
| Total entries | 11 |

### Event type

| Value | Count |
| --- | ---: |
| failure | 1 |
| improvement | 10 |

### Lane

| Value | Count |
| --- | ---: |
| android | 11 |

### Outcome

| Value | Count |
| --- | ---: |
| committed | 10 |
| observed | 1 |

## Recent Evidence

| Date | Type | Lane | Title | Constraints | Outcome | Evidence / Risk |
| --- | --- | --- | --- | --- | --- | --- |
| 2026-05-17 | failure | android | YouTube scroll and mini-player leave stale masks on wrong regions | C4, C5, C7 | observed | User screenshot shows masks remaining on profile/channel metadata and thumbnail regions while visible harmful text can still remain after scrolling; the mini-p... |
| 2026-05-17 | improvement | android | `8fd464c` Keep visual OCR alive during content churn | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `cd22bea` Tighten Android realtime visual masking | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `bb1e078` Stop rendering semantic visual fallback masks | C5 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `459c2b4` Promote cached semantic YouTube visual masks during scroll | C3, C4, C5, C6 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `33cbe7b` Reuse Android YouTube analysis across visual geometry changes | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-17 | improvement | android | `402ed74` Preserve visual masks during Android scroll refresh | C4, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-16 | improvement | android | `1a98bca` Improve Android Shorts thumbnail OCR coverage | C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-16 | improvement | android | `6f7d8f4` Stabilize Android YouTube visual masking | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-15 | improvement | android | `ecc7bda` Use accessibility bounds for YouTube comments | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |
| 2026-05-15 | improvement | android | `d4ba7dd` Improve Android YouTube hybrid masking | C3, C5, C7 | committed | Backfilled from branch commit history; expand this entry if it becomes report-critical. / Backfilled entry needs screenshot/log/test evidence before it is used... |

## Operating Rule

- Add one ledger entry per distinct failure, experiment, verified improvement, regression, or decision.
- Keep raw runtime dumps out of Git unless they are compact curated fixtures.
- Use commit history for traceability, but use this index for report-ready meaning.
- Before using an auto-backfilled commit in the final report, add concrete screenshot, log, test, or PR evidence.
