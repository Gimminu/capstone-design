# Chungmaru Evidence Matrix

Updated: 2026-05-17

이 문서는 캡스톤 심사 대응 To-do-list를 repo evidence, Notion Evidence Hub, GitHub/Linear 이력과 연결하기 위한 추적 문서입니다.
`docs/evidence/chungmaru-progress-log.jsonl`은 공식 내부 ledger이고, Notion `Bugs & Issues`는 보고서 추출을 위한 evidence hub입니다.

## 운영 원칙

| 원칙 | 적용 방식 |
| --- | --- |
| Git history는 얕게 유지 | 작업 브랜치 실험 커밋은 허용하되 `main`은 PR squash/merge 단위로 읽히게 유지 |
| Evidence는 깊게 유지 | 실패, 실험, 검증, 의사결정은 `chungmaru-progress-log.jsonl`에 event 단위로 기록 |
| Worktree는 격리 장치 | 계속 진행되는 개발 세션은 worktree에서 격리하고, 공식 evidence는 ledger/Notion에 남김 |
| 세션 출처 분리 | `source_session_id`는 실제 개발/오류 세션, `registrar_session_id`는 정리 세션 또는 자동화 |
| 보고서 사용 가능성 분리 | `evidence_quality=commit-only`는 추적용, `report-ready`만 최종 보고서에 바로 사용 |
| Notion은 evidence hub | 상위 DB에는 토픽 Hub만 두고, 실제 사례/metric/screenshot/trace는 Hub 내부 child DB row로 유지 |

## History Policy

| 위치 | 남기는 것 | 운영 방식 |
| --- | --- | --- |
| Repo ledger | 의미 있는 실패, 실험, 개선, 회귀, 검증, 의사결정 전체 | 삭제하지 않고 append-only로 유지 |
| Evidence docs | 최종 보고서에 옮길 수 있는 표, 전후 비교, 스냅샷, screenshot pack | report-ready 또는 artifact-backed 기준으로 선별 |
| Notion Hub | 심사 대응 토픽, report-ready 후보, 현재 gap, 다음 수집 대상 | 상위 카드를 늘리지 않고 Hub 내부 child DB row를 갱신 |
| Git history | 최종 PR/merge에서 읽히는 의미 단위 | 작은 실험 커밋은 branch/worktree 안에서 허용하고 main은 얕게 유지 |

기존 Chungmaru evidence 상세 카드는 삭제하지 않고 관련 Hub 아래로 흡수합니다.
같은 목적의 증거가 추가되면 새 상위 카드를 만들지 않고 Hub 내부 child DB row를 갱신합니다.
매번 개발 세션 결과를 정리할 때는 전체 이력을 Notion에 모두 복제하지 않고, report-ready 후보와 open gap만 Hub에 반영합니다.

## 2026-05-14 Review Focus

| 우선순위 | Focus | 왜 먼저인가 | 대표 Notion Hub |
| --- | --- | --- | --- |
| P0 | 통합 흐름 E2E 증거 | 완성도 평가에서 실제 서비스 흐름을 보여주는 핵심 근거 | `Chrome / Android E2E Demo Hub`, `Demo Capability Matrix Hub` |
| P0 | Android `boundsInScreen` + final mask pack | Android는 DOM이 없으므로 좌표 기반 마스킹 난이도를 보여주는 기술성 핵심 근거 | `Android boundsInScreen Masking Hub` |
| P1 | 재학습 전/후 + FP/FN + score bucket | 모델이 실제로 개선됐는지와 threshold로 해결 가능한지 판단하는 핵심 근거 | `Model Evaluation Hub`, `Model Error Cases Hub` |
| P1 | clean-topic bias + span/masking trace | `차별금지법`, `성소수자` 토픽 오탐과 실제 마스킹 단어 선택 문제를 같이 설명 | `Clean-topic Bias Hub`, `Span & Masking Trace Hub` |
| P1 | parser raw/cleaned + 플랫폼별 오류 | 수집/정제 품질이 모델 입력 품질과 직결됨을 보여주는 기술성 근거 | `Parser / Extractor Hub`, `Platform Parser Issues Hub` |
| P2 | worktree/ledger/commit 운영 | 개발 과정 추적성과 발표 자료 재사용성을 높이는 운영 근거 | `Evidence / Git / Worktree Policy Hub` |

정리 우선순위는 `P0 완성도 증거 -> P1 기술성 증거 -> P2 운영 증거`입니다.
운영/자동화 자체가 발표의 중심이 되지 않도록 하고, 모델/파서/통합 흐름 증거가 먼저 채워지게 관리합니다.

## Notion Evidence Hub

`Bugs & Issues` 상위 DB에는 아래 Hub 카드만 유지합니다. 실제 evidence는 Hub 페이지 내부 child DB row로 누적하고, 기존 상세 카드는 삭제하지 않고 관련 Hub 아래로 흡수합니다.

| Hub | Child DB | 목적 |
| --- | --- | --- |
| [Model Evaluation Hub](https://www.notion.so/3630cc24fc2e81508039f27db86c82f7) | `Model Evaluation Runs`, `Model Comparison Summary` | 재학습 전후 성능과 유형별 지표 정리 |
| [Model Error Cases Hub](https://www.notion.so/3630cc24fc2e81419f41cbdb6bd06440) | `Model Error Cases`, `Score Bucket Analysis` | 대표 FP/FN과 threshold 한계 정리 |
| [Clean-topic Bias Hub](https://www.notion.so/3630cc24fc2e811c8637d308ca2b054e) | `Clean Topic Tests` | 민감 토픽 clean 문장 오탐 점검 |
| [Span & Masking Trace Hub](https://www.notion.so/3630cc24fc2e81ffae46eb0a8dbfed30) | `Masking Trace Examples` | score, span, 최종 마스킹 연결 예시 |
| [Parser / Extractor Hub](https://www.notion.so/3630cc24fc2e8161826ff7b47bf9fc05) | `Raw Cleaned Snapshots`, `UI Removal Rules` | 원본/정제 JSONL 전후 비교와 UI 잡음 제거 규칙 |
| [Platform Parser Issues Hub](https://www.notion.so/3630cc24fc2e81c5aed6cfac9ffc7898) | `Platform Parser Issues` | Instagram/TikTok/YouTube/Chrome 오류 분리 |
| [Android boundsInScreen Masking Hub](https://www.notion.so/3630cc24fc2e8119a711fdeff96f58ad) | `Android Bounds Masking Packs` | Android 좌표, backend score/span, overlay screenshot 증거 묶음 |
| [Chrome / Android E2E Demo Hub](https://www.notion.so/3630cc24fc2e8172a55af79e1f61900e) | `E2E Demo Traces` | 플랫폼별 end-to-end 예시 |
| [Demo Capability Matrix Hub](https://www.notion.so/3630cc24fc2e81dcbd26cd95c741fa66) | `Demo Capability Matrix` | 발표 범위와 향후 과제 분리 |
| [Evidence / Git / Worktree Policy Hub](https://www.notion.so/3630cc24fc2e81089677e3da966b2fcb) | `Evidence Decisions`, `Worktree / Branch Records` | Git/evidence/worktree 운영 정책 |

## Repo Evidence Artifacts

| Artifact | Status | Use |
| --- | --- | --- |
| `docs/evidence/model-evaluation-snapshot.md` | report-ready baseline snapshot | 현재 모델 기준 Accuracy/Precision/Recall/F1, FP/FN, score bucket, clean-topic bias |
| `docs/evidence/parser-extractor-before-after.md` | report-ready fixture snapshot | raw JSONL vs cleaned JSONL, UI 텍스트 제거, Android bounds 유지 |

## 1. 탐지 모델 Evidence

| 요청사항 | 현재 근거 | 부족한 것 | 다음 액션 | 품질 목표 |
| --- | --- | --- | --- | --- |
| 재학습 전/후 성능 비교, 데이터 구성 방법 | `docs/evidence/model-evaluation-snapshot.md`가 current/baseline 1007건 스냅샷을 고정 | 실제 재학습 전후를 같은 split으로 비교한 표 | retrained 결과 JSON을 같은 형식으로 추가 | `artifact-backed` |
| Accuracy, Precision, Recall, F1, FP, FN | `docs/evidence/model-evaluation-snapshot.md`에 Accuracy 78.55%, Precision 81.51%, Recall 74.46%, F1 77.82%, FP 86, FN 130 고정 | 재학습 후 같은 지표 | retrain 후 동일 표 추가 | `report-ready` |
| profanity/toxicity/hate별 성능 분리 | backend 응답은 label/scores/evidence span 구조를 가짐 | 유형별 confusion matrix | evaluation case에 category field를 보강하거나 기존 failure JSON에서 추출 | `artifact-backed` |
| `차별금지법`, `성소수자` clean 문장 테스트 | `docs/evidence/model-evaluation-snapshot.md`에 topic cases 32건, clean FP 20/21 고정 | 재학습 후 clean-topic 개선 여부 | clean-topic regression fixture 추가 | `test-backed` |
| FP 10개/FN 10개 대표 사례 | `docs/evidence/model-evaluation-snapshot.md`에 각 10개 대표 사례 고정 | 개인정보/저작권 최종 검토 | 최종 보고서 삽입 전 excerpt 길이 재확인 | `report-ready` |
| 스코어 구간별 오류 분포 | `docs/evidence/model-evaluation-snapshot.md`에 `>=0.70`, `0.50-0.70`, `0.30-0.50`, `<0.15` 오류 분포 고정 | 그래프 이미지 연결 | charts 또는 JSON source를 발표 자료에 연결 | `report-ready` |
| 최종 Demo 마스킹 기준 | sensitivity 0/20/60/100 평가 경로와 threshold 논의 존재 | 최종 demo threshold 결정 문장 | demo freeze 전 `decision` entry로 기준과 보류 이유 기록 | `report-ready` |
| span detection 성능 | backend `evidence_spans`, Android/extension span 적용 경로 존재 | span-level precision/recall 또는 적용 성공률 | exact span이 맞은/틀린 사례 표 작성 | `artifact-backed` |
| original -> normalized -> score -> span -> mask 예시 4종 | Notion card 생성됨, shared examples 일부 존재 | 정상/욕설/혐오/우회 표현 4행 완성 표 | backend 응답과 UI 적용 결과를 하나의 table로 묶기 | `report-ready` |

## 2. Parser / Extractor Evidence

| 요청사항 | 현재 근거 | 부족한 것 | 다음 액션 | 품질 목표 |
| --- | --- | --- | --- | --- |
| raw JSONL vs cleaned JSONL 비교 | `docs/evidence/parser-extractor-before-after.md`에 YouTube/Chrome before/after 표 고정 | Instagram/TikTok 전후 예시 | 플랫폼별 fixture 추가 | `report-ready` |
| UI 텍스트 제거 규칙 | `docs/evidence/parser-extractor-before-after.md`에 filter/metadata/browser chrome 제거 규칙 고정 | action/menu/시간 문구 추가 예시 | 플랫폼별 fixture 추가 | `report-ready` |
| 댓글 없음/다른 화면 감지 기준 | extractor 코드와 docs 일부 존재 | report용 decision 문장 | `decision` entry로 감지 기준과 한계 기록 | `log-backed` |
| 중복 댓글 제거 기준 | parser README에 same normalized text + nearby bounds + source 기준 존재 | 실제 중복 제거 전후 예시 | duplicate fixture 추가 | `artifact-backed` |
| 플랫폼별 문제 분리 | Notion card 생성됨, Android/YouTube 중심 기록 많음 | Instagram/TikTok 실제 오류 증거 | 플랫폼별 failure entry 추가 | `screenshot-backed` |
| cleaned JSONL -> 모델 입력 흐름 | `docs/evidence/parser-extractor-before-after.md`에 `/analyze_android` 후보 payload 예시 고정 | 실제 backend response 한 쌍 | fixture와 response를 묶은 E2E 표 추가 | `artifact-backed` |
| Android `boundsInScreen` 유지/활용 | `docs/evidence/parser-extractor-before-after.md`가 kept candidate bounds를 보여줌 | screenshot + JSON + overlay 결과 묶음 | `Android boundsInScreen 활용 증거 pack` 카드에 artifact 연결 | `screenshot-backed` |
| 제거된 텍스트와 남은 댓글 before/after | `docs/evidence/parser-extractor-before-after.md`에 YouTube/Chrome 제거/유지 표 고정 | Instagram/TikTok 표 | 플랫폼별 fixture 추가 | `report-ready` |

## 3. Integration / Demo Evidence

| 요청사항 | 현재 근거 | 부족한 것 | 다음 액션 | 품질 목표 |
| --- | --- | --- | --- | --- |
| Chrome end-to-end 예시 | Chrome extension/backend integration 이력과 `/analyze_batch` 계약 존재 | 최신 화면/응답 예시 | Chrome demo trace 1세트 수집 | `screenshot-backed` |
| Android end-to-end 예시 | `PR #32`, `BBA-79`, Android overlay/evidence ledger 12 entries | 실기기 runtime smoke와 screenshot | adb 연결 시 parse JSON, analysis JSON, screenshot 세트 수집 | `screenshot-backed` |
| cleaned -> normalized -> model -> evidence span -> mask | backend/extension/Android 경로는 존재 | 하나의 연결 표 | 4종 예시 표와 각 플랫폼 적용 여부 작성 | `report-ready` |
| 정상/욕설/혐오/우회 표현 예시 | evaluation README에 분류 기준 존재 | 플랫폼별 실제 결과 | 4개 예시를 Chrome/Android 각각 가능한 범위로 실행 | `artifact-backed` |
| Demo 가능/어려운 기능 구분 | Notion card 생성됨, docs/presentation 자료 일부 있음 | 최종 발표용 범위 선언 | demo freeze 시 `decision` entry 작성 | `report-ready` |

## Current Gaps

| Gap | 왜 문제인가 | 우선순위 |
| --- | --- | --- |
| Ledger가 아직 Android 중심으로 치우침 | 모델/파서 baseline은 보강됐지만 Chrome/E2E runtime 증거가 약함 | High |
| 기존 Android backfill 항목의 `session_id` 의미가 legacy 상태 | 개발 출처와 등록 출처가 구분되지 않은 과거 항목은 report-ready로 쓰기 전 보강 필요 | Medium |
| 모델 재학습 후 동일 split 비교가 없음 | 개선 여부를 아직 수치로 확정할 수 없음 | High |
| parser fixture가 YouTube/Chrome에만 있음 | Instagram/TikTok 오류 유형을 분리해 보여주기 어렵다 | Medium |
| Android runtime smoke가 일부 누락 | 실제 완성도 증거가 unit/build 중심으로 치우침 | Medium |

## Next Evidence Entries To Add

1. `verification`: Android boundsInScreen screenshot pack from the next attached-device run.
2. `verification`: Chrome end-to-end trace with cleaned text, backend response, evidence span, and final mask.
3. `verification`: Instagram/TikTok parser raw/cleaned fixture pairs.
4. `decision`: final demo masking threshold and capability limits.
5. `verification`: retrained model result on the same 1007-case split.
