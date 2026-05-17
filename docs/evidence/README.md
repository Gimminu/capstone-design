# Chungmaru Evidence Ledger

이 폴더는 발표와 최종 보고서에 바로 옮길 수 있는 개발 증거를 한 곳에 모으는 공간입니다.
원시 실행 JSON을 계속 늘리는 용도가 아니라, 의미 있는 실패/실험/개선/검증만 선별해서 남기는 ledger입니다.

## 파일

| 파일 | 역할 |
| --- | --- |
| `chungmaru-progress-log.jsonl` | 한 줄이 하나의 실패, 실험, 개선, 회귀, 검증, 의사결정입니다. |
| `chungmaru-progress-index.md` | JSONL에서 생성되는 사람이 읽는 인덱스입니다. 직접 편집하지 않습니다. |
| `evidence-matrix.md` | 심사 대응 To-do-list를 모델/파서/통합 흐름 증거판으로 재구성한 추적 문서입니다. |
| `model-evaluation-snapshot.md` | 현재 모델 성능, FP/FN, topic-bias, score bucket을 보고서용으로 축약한 문서입니다. |
| `parser-extractor-before-after.md` | raw JSONL과 cleaned JSONL의 before/after 표와 정제 규칙 증거입니다. |

## 기록 단위

기록은 커밋 수가 아니라 “문제 단위”로 남깁니다.

| 유형 | 남기는 경우 |
| --- | --- |
| `failure` | 화면 캡처, 로그, 테스트에서 새로운 실패가 관찰된 경우 |
| `experiment` | 해결 후보를 시도했지만 검증 결과가 아직 확실하지 않은 경우 |
| `improvement` | 이전과 다른 개선이 구현 또는 검증된 경우 |
| `regression` | 기존에 되던 동작이 깨진 경우 |
| `verification` | 테스트, 빌드, 실기기, API 호출로 확인한 결과 |
| `decision` | 한계, 보류 이유, 대안 선택처럼 설계 판단이 생긴 경우 |

## 명령

현재 브랜치 커밋을 evidence ledger에 분류해 추가합니다.

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

새 실패나 개선을 수동으로 추가합니다.

```bash
python3 scripts/chungmaru_evidence.py add \
  --lane android \
  --surface youtube-search \
  --event-type failure \
  --title "YouTube scroll leaves stale visual masks on wrong regions" \
  --problem "스크롤 중 이전 mask가 남거나 엉뚱한 썸네일/metadata 위에 붙는다." \
  --cause "접근성 bounds, OCR ROI, overlay retention이 같은 수명주기로 관리되지 않는다." \
  --outcome observed \
  --constraints C4,C5,C7 \
  --linear-issue BBA-79 \
  --github-pr https://github.com/BbangYi/ChungMaru/pull/33 \
  --source-session-id 019e1b02-4966-7d50-ac1b-ec191478dcd6 \
  --registrar-session-id 019e3546-cfae-7450-880d-006c2f1102d5 \
  --worktree-role experiment \
  --source-branch codex/android-mask-latency-diagnostics \
  --integration-branch main \
  --evidence-quality screenshot-backed \
  --tag android \
  --tag youtube \
  --tag scroll \
  --tag stale-mask
```

검증합니다.

```bash
python3 scripts/chungmaru_evidence.py validate
python3 scripts/chungmaru_evidence.py summarize
```

Worktree 상태를 확인할 때:

```bash
python3 scripts/chungmaru_evidence.py audit-worktrees
python3 scripts/chungmaru_evidence.py audit-worktrees --show-status
```

## 커밋/태그 정책

작업 브랜치에서는 실험 커밋을 허용합니다. 다만 main에는 PR 단위로 의미가 분명한 변경만 들어가야 합니다.

- failure: 이전과 다른 실패가 관찰됨
- experiment: 해결 후보를 시도함
- improvement: 실패가 줄었거나 검증 기준이 좋아짐
- regression: 기존 동작이 깨짐
- verification: 테스트/빌드/실기기 확인 결과
- decision: 한계와 대안을 분명히 정리함

태그는 모든 커밋에 붙이지 않습니다. 발표 또는 데모 기준점처럼 다시 돌아가야 하는 상태에만 붙입니다.

## 운영 원칙

- 원시 JSON/스크린샷은 필요한 것만 `docs/evidence/artifacts/`에 curated artifact로 넣습니다.
- 자동 backfill 항목은 커밋 추적용입니다. 최종 보고서에 쓰려면 캡처, 로그, 테스트, PR 중 하나를 보강해야 합니다.
- Android 마스킹 문제는 `C4` 실시간성, `C5` 마스킹 UI, `C7` 접근성/OCR 제약을 함께 봅니다.
- GitHub PR과 Linear는 같은 evidence key를 참조하도록 맞춥니다.
- `source_session_id`는 실제 오류/개발/검증이 발생한 Codex 세션입니다.
- `registrar_session_id`는 나중에 evidence ledger나 Notion에 정리한 세션 또는 자동화 실행입니다.
- `evidence_quality=commit-only`는 추적용일 뿐 보고서 증거가 아닙니다. 보고서에는 `log-backed`, `screenshot-backed`, `test-backed`, `artifact-backed`, `report-ready` 항목만 사용합니다.
- 긴 Android/backend/extension 개발은 worktree에서 격리하고, official repo는 ledger, Notion, PR 정리 기준으로 씁니다.
- `worktree_role=experiment`는 진행 중 실험, `verification`은 merge 전 검증, `reporting`은 문서/evidence 정리, `integration`은 main 반영 전 통합 확인입니다.
- ledger에는 `worktree_path`, `source_branch`, `integration_branch`를 남겨서 계속 진행되는 개발 세션과 최종 정리 위치를 분리합니다.
