# 개발 이력 및 개선 근거 기록

이 문서는 청마루 프로젝트가 단순히 AI 도구로 코드를 생성한 것이 아니라, 실제 문제를 관찰하고 제약을 확인한 뒤 개선 방향을 선택했다는 근거를 남기기 위한 문서입니다.
결과 보고서에는 이 문서의 표를 요약해서 “문제 정의 -> 참고 근거 -> 개선 내용 -> 검증 결과” 흐름으로 옮기면 됩니다.

## 1. 기록 원칙

1. 문제는 증상만 적지 않고 사용자가 실제로 겪은 상황과 함께 기록합니다.
2. 참고 근거는 화면 캡처, Chrome extension 오류, backend 응답, GitHub PR, Linear 코멘트, 테스트 결과 중 하나 이상으로 남깁니다.
3. 개선 내용은 “무엇을 바꿨는지”보다 “왜 그 방식이 적절했는지”를 함께 적습니다.
4. 검증은 성공 여부뿐 아니라 아직 남은 제약도 같이 남깁니다.
5. 한 번에 큰 결론을 쓰지 않고, 반복 개선의 흐름이 보이도록 작은 단위로 누적합니다.

## 2. 핵심 개선 흐름

| 단계 | 관찰된 문제 | 참고한 근거 | 제약 사항 | 개선 방향 | 검증 방법 | 남은 과제 |
| --- | --- | --- | --- | --- | --- | --- |
| 초기 연동 | Extension이 실제 모델이 아니라 임시 판단 또는 불안정한 backend 호출에 의존 | Chrome console의 `Failed to fetch`, `/health`, `/analyze_batch` 응답 확인 | 로컬 FastAPI 서버를 유지해야 하며 public API를 크게 바꾸면 Android와 계약 충돌 가능 | Extension -> service worker -> `/analyze_batch` 흐름으로 정렬하고 backend 상태를 진단값으로 노출 | `/health`, `/analyze_batch`, Chrome popup 상태 확인 | backend 미실행 또는 timeout 시 사용자에게 어떻게 보일지 정리 필요 |
| 전면 마스킹 문제 | 검색 결과 제목, URL, snippet 전체가 검은 박스처럼 가려짐 | Google 검색 화면 캡처, content script DOM 적용 결과 | 유해 단어만 가려야 하며 정상 문맥과 UI 요소는 유지해야 함 | element 전체가 아니라 `evidence_spans` 기반 exact span 마스킹으로 변경 | Google 검색 결과, GitHub repo 화면, test lab 비교 | span이 없는 positive 결과의 표시 정책 유지 필요 |
| 문맥 오탐 | `카필 시발(Kapil Sibal)`, `시발 - 위키낱말사전`, `국제차량제작 시발`, `scripts`, `README`, `warp theme` 같은 정상 문맥이 가려짐 | backend regression case, 실제 Google/GitHub 화면 | 단순 금칙어 차단만으로는 프로젝트 차별성이 약해짐 | safe-context 후처리와 browser false-positive regression set 추가 | backend regression unittest, `/analyze_batch` 직접 호출 | 새로운 UI 단어가 오탐될 때 regression set에 계속 누적 |
| 실시간성 부족 | 사용자가 먼저 텍스트를 읽은 뒤 늦게 마스킹됨 | Google/YouTube 실사용 캡처, popup의 first mask latency 및 backend reconcile latency | 모든 DOM을 즉시 전체 분석하면 느리고 flicker가 발생 | visible container 우선, dirty queue, IntersectionObserver, foreground/reconcile 분리 | popup 진단값, 실제 스크롤/페이지 진입 테스트 | CPU-only 환경에서는 sub-250ms 보장이 어려움 |
| 입력창 마스킹 흔들림 | Google 검색창에서 마스킹 박스가 움직이거나 위치가 어긋남 | 검색창 캡처, textarea DOM 상태 확인 | native input/textarea는 내부 text range에 직접 span을 씌울 수 없음 | 검색창은 fixed-token 방식으로 분리하고, 현재 `***` UI는 고정 UX로 유지 | 검색창 입력, 이동, 포커스 변경 테스트 | 향후 입력창 UX 변경 시 별도 PR로만 수정 |
| 민감도 반영 문제 | sensitivity 0에서도 기존 mask가 남거나 깜빡이는 것처럼 보임 | popup sensitivity 조작, backend 직접 호출 결과 | backend 판정과 frontend stale cache가 충돌하면 신뢰성이 낮아짐 | apply-time 설정 revision guard, disabled/sensitivity stale response drop, cache schema 분리 | sensitivity 0 direct backend response, extension reload 후 확인 | 사용자가 설정 변경 직후 기존 페이지를 새로고침하지 않은 경우 안내 필요 |
| 동적 Google 영역 누락 | AI 개요, SFC, 동적 검색 카드 일부가 후보 큐에 늦게 들어감 | 사용자가 제공한 Google AI Overview DOM, PR #17 | selector를 무작정 넓히면 성능 저하와 오탐 위험 증가 | Google high-signal rescue pass를 추가하되 fingerprint/backoff로 반복 분석 제한 | `node --check`, backend regression, 실제 Google AI 개요 테스트 | 실제 Google DOM 변경에 맞춘 selector 유지보수 필요 |

## 3. 최근 GitHub PR 기준 이력

| PR/커밋 | 목적 | 주요 개선 | 보고서에 쓸 수 있는 의미 |
| --- | --- | --- | --- |
| #8 Fix Google entry masking stability | Google 검색 진입 시 마스킹 안정화 | 초기 Google 검색 결과 적용 안정화 | 실제 웹 페이지 DOM 구조가 단순하지 않아 platform-specific 보정이 필요했음 |
| #9 Fix Google title and input masking gaps | 제목/검색창 누락 보완 | 검색 결과 title과 input 후보 보강 | 같은 검색어라도 위치와 DOM 역할에 따라 수집 방식이 달라짐 |
| #10 Broaden Google high-signal foreground wave | foreground 후보 확대 | 첫 화면 고위험 후보 우선 분석 | 전체 페이지 분석보다 첫 시야 우선순위가 실시간성에 유리함 |
| #11 Fix sensitivity zero and Google input masking | 민감도 0과 검색창 처리 | sensitivity 0 stale mask 방지, 입력창 마스킹 보정 | 사용자 설정은 단순 UI가 아니라 판정 적용 정책과 연결됨 |
| #12 Stabilize sensitivity and editable masking | 설정 변경/입력창 안정화 | editable masking과 sensitivity semantics 보강 | 입력창은 일반 DOM과 다른 렌더링 전략이 필요함 |
| #14 Broaden multilingual profanity coverage | 다국어/우회 표현 보강 | romanized, qwerty, English variants 보강 | 실제 사용자는 한글 욕설만 사용하지 않으므로 정규화와 span 탐지가 중요함 |
| #15 Sync Korean UI diagnostics and run guidance | 진단/운영 안내 개선 | popup/options 진단값 한글화, 실행 안내 정리 | 문제 재현과 보고서 작성을 위해 운영 상태가 보여야 함 |
| #16 Guard stale mask apply against disabled settings | stale response 방지 | 비활성/설정 변경 후 오래된 응답 적용 방지 | 비동기 분석에서는 race condition 관리가 핵심 제약임 |
| #17 Improve Google high-signal candidate rescue | Google 동적 영역 후보 누락 완화 | AI 개요/SFC high-signal 후보 rescue pass | 실제 플랫폼 DOM 변화에 맞춘 적응형 후보 수집이 필요함 |

## 4. 결과 보고서용 요약 문장 예시

청마루는 초기에는 단순 금칙어 또는 임시 판단으로도 동작을 확인할 수 있었지만, 실제 Google 검색 결과와 YouTube 댓글 환경에서는 문맥 오탐, DOM 구조 차이, 비동기 응답 지연, 입력창 렌더링 제약이 반복적으로 발생했다.
이에 따라 backend 모델의 `/analyze_batch` 결과를 최종 판단 기준으로 유지하고, extension은 visible container 우선 수집, dirty queue, exact span 마스킹, stale response guard를 통해 실사용 환경에서의 안정성과 반응 속도를 개선했다.
이 과정에서 `시발 - 위키낱말사전`, `카필 시발(Kapil Sibal)`, `scripts`, `README`, `warp theme` 등 정상 문맥 오탐 사례와 `시발 뭐하는 거야`, `병신아 꺼져`, romanized 표현 등 유해 사례를 regression set으로 관리했다.

## 5. 매 반복마다 남길 기록 형식

```md
### YYYY-MM-DD / 개선 제목

- 문제:
- 실제 증거:
- 참고한 기준:
- 제약:
- 선택한 해결책:
- 대안과 보류 이유:
- 변경 파일 또는 PR:
- 검증:
- 남은 위험:
```
