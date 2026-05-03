# 개발 이력 및 개선 근거 기록

이 문서는 청마루 프로젝트가 단순히 AI 도구로 코드를 생성한 것이 아니라, 실제 문제를 관찰하고 제약을 확인한 뒤 개선 방향을 선택했다는 근거를 남기기 위한 문서입니다.
결과 보고서에는 이 문서의 표를 요약해서 “문제 정의 -> 참고 근거 -> 개선 내용 -> 검증 결과” 흐름으로 옮기면 됩니다.

## 1. 기록 원칙

1. 문제는 증상만 적지 않고 사용자가 실제로 겪은 상황과 함께 기록합니다.
2. 참고 근거는 화면 캡처, Chrome extension 오류, backend 응답, GitHub PR, Linear 코멘트, 테스트 결과 중 하나 이상으로 남깁니다.
3. 개선 내용은 “무엇을 바꿨는지”보다 “왜 그 방식이 적절했는지”를 함께 적습니다.
4. 검증은 성공 여부뿐 아니라 아직 남은 제약도 같이 남깁니다.
5. 한 번에 큰 결론을 쓰지 않고, 반복 개선의 흐름이 보이도록 작은 단위로 누적합니다.
6. GitHub Issue와 PR을 공식 근거로 삼고, Notion은 보고서용 요약본으로만 사용합니다.

## 1-1. 빠른 기록 규격

개선 중에는 아래 6개만 먼저 남겨도 됩니다.
나중에 GitHub 이슈, PR, 커밋, 테스트 로그를 참조해 상세 설명을 채웁니다.

```md
- 증거:
- 제약 코드:
- 원인 분해:
- 선택한 해결:
- 검증:
- 남은 과제:
```

단, `증거`와 `검증`이 비어 있으면 공식 개선 이력으로 보지 않습니다.
`제약 코드`는 `docs/constraints-and-decisions.md`의 C1~C8 중 하나 이상을 사용합니다.

## 2. 핵심 개선 흐름

| 단계 | 관찰된 문제 | 참고한 근거 | 제약 사항 | 개선 방향 | 검증 방법 | 남은 과제 |
| --- | --- | --- | --- | --- | --- | --- |
| 초기 연동 | Extension이 실제 모델이 아니라 임시 판단 또는 불안정한 backend 호출에 의존 | Chrome console의 `Failed to fetch`, `/health`, `/analyze_batch` 응답 확인 | 로컬 FastAPI 서버를 유지해야 하며 public API를 크게 바꾸면 Android와 계약 충돌 가능 | Extension -> service worker -> `/analyze_batch` 흐름으로 정렬하고 backend 상태를 진단값으로 노출 | `/health`, `/analyze_batch`, Chrome popup 상태 확인 | backend 미실행 또는 timeout 시 사용자에게 어떻게 보일지 정리 필요 |
| 전면 마스킹 문제 | 검색 결과 제목, URL, snippet 전체가 검은 박스처럼 가려짐 | Google 검색 화면 캡처, content script DOM 적용 결과 | 유해 단어만 가려야 하며 정상 문맥과 UI 요소는 유지해야 함 | element 전체가 아니라 `evidence_spans` 기반 exact span 마스킹으로 변경 | Google 검색 결과, GitHub repo 화면, test lab 비교 | span이 없는 positive 결과의 표시 정책 유지 필요 |
| 문맥 오탐 | `카필 시발(Kapil Sibal)`, `시발 - 위키낱말사전`, `국제차량제작 시발`, `scripts`, `README`, `warp theme` 같은 정상 문맥이 가려짐 | backend regression case, 실제 Google/GitHub 화면 | 단순 금칙어 차단만으로는 프로젝트 차별성이 약해짐 | safe-context 후처리와 browser false-positive regression set 추가 | backend regression unittest, `/analyze_batch` 직접 호출 | 새로운 UI 단어가 오탐될 때 regression set에 계속 누적 |
| 실시간성 부족 | 사용자가 먼저 텍스트를 읽은 뒤 늦게 마스킹됨 | Google/YouTube 실사용 캡처, popup의 first mask latency 및 backend reconcile latency | 모든 DOM을 즉시 전체 분석하면 느리고 flicker가 발생 | visible container 우선, dirty queue, IntersectionObserver, foreground/reconcile 분리 | popup 진단값, 실제 스크롤/페이지 진입 테스트 | CPU-only 환경에서는 sub-250ms 보장이 어려움 |
| 입력창 마스킹 흔들림 | Google 검색창에서 마스킹 박스가 움직이거나 위치가 어긋남 | 검색창 캡처, textarea DOM 상태 확인 | native input/textarea는 내부 text range에 직접 span을 씌울 수 없음 | 검색창은 fixed-token 방식으로 분리하고, 현재 `***` UI는 고정 UX로 유지 | 검색창 입력, 이동, 포커스 변경 테스트 | 향후 입력창 UX 변경 시 별도 PR로만 수정 |
| 민감도 반영 문제 | sensitivity 0에서도 기존 mask가 남거나 깜빡이는 것처럼 보임 | popup sensitivity 조작, backend 직접 호출 결과 | backend 판정과 frontend stale cache가 충돌하면 신뢰성이 낮아짐 | apply-time 설정 revision guard, disabled/sensitivity stale response drop, cache schema 분리, popup -> content script 설정 스냅샷 직접 전달 | sensitivity 0 direct backend response, extension reload 후 확인, 민감도 sweep 평가 | 설정 UI와 content script 간 중복 이벤트가 다시 생기지 않도록 회귀 확인 필요 |
| 동적 Google 영역 누락 | AI 개요, SFC, 동적 검색 카드 일부가 후보 큐에 늦게 들어감 | 사용자가 제공한 Google AI Overview DOM, PR #17 | selector를 무작정 넓히면 성능 저하와 오탐 위험 증가 | Google high-signal rescue pass를 추가하되 fingerprint/backoff로 반복 분석 제한 | `node --check`, backend regression, 실제 Google AI 개요 테스트 | 실제 Google DOM 변경에 맞춘 selector 유지보수 필요 |
| 서비스 차별성 약함 | 욕설 마스킹과 모델 비교만으로는 흔한 프로젝트처럼 보일 수 있음 | 교수 피드백, 현재 `service-definition.md`, `evaluation/api-vs-ml` 구조 확인 | 기능을 더 늘리기 전에 무엇이 차별점인지 먼저 고정해야 함 | 청마루를 문맥/불확실성/플랫폼 수집/근거 기반 중재 파이프라인으로 재정의 | 서비스 정의서, pipeline 평가 기준, 제약 문서 일관성 확인 | 실제 평가 표와 발표 자료에 같은 프레임을 반영해야 함 |
| Android 분석 루프 부재 | Android 앱이 댓글 수집 JSON 업로드는 하지만 backend 모델 분석 결과가 앱에서 확인되지 않음 | Android 코드의 `ServerUploader`, backend `/analyze_android`, 빌드/테스트 결과 | 접근성 API는 DOM span을 직접 제어하지 못하므로 차단 UX 전에 수집/분석 품질을 분리 검증해야 함 | 별도 분석 API 주소 설정, `/analyze_android` 클라이언트, 분석 결과 저장, 최근 분석 진단 UI 추가 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `/analyze_android` 직접 호출 | 실기기 접근성 이벤트 기반 수집 품질 검증 필요 |
| Android no-span positive 구분 | backend가 `is_offensive=true`를 주더라도 `evidence_spans`가 비어 있는 경우가 있어 모바일 진단에서 차단 가능 항목처럼 보일 수 있음 | `/analyze_android` 직접 호출, `abstract factory pattern 설명` no-span positive 사례, Android unit test | Android 접근성 overlay는 좌표 기반이라 span이 없는 결과를 화면에 안정적으로 적용할 수 없음 | backend 원 응답은 저장하되 모바일 진단의 “마스킹 가능 댓글 수”는 `is_offensive && evidence_spans`가 있는 결과만 계산 | `AndroidAnalysisClientTest`, `:app:testDebugUnitTest`, `:app:assembleDebug` | 모델 positive와 실제 마스킹 가능 결과를 UI에서 더 세분화해 보여줄지 검토 필요 |
| Android YouTube 제목 누락 | YouTube 검색 결과 제목과 영상 카드 제목이 마스킹되지 않음 | `uiautomator dump`, `parse_results`, `analysis_results` 비교 결과 제목이 일반 text node가 아니라 `content-desc`에 들어 있거나 아예 썸네일 이미지 안에만 존재 | 접근성 API는 이미지 내부 글자를 제공하지 않고, composite card bounds를 그대로 쓰면 카드 전체가 가려짐 | `content-desc`에서 제목만 분리해 backend 후보로 보내고, card bounds는 제목 strip으로 축소 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `adb install`, 최신 parse/analysis JSON bounds 비교 | 썸네일/영상 프레임 내부 텍스트는 OCR 없이는 처리 불가 |
| Android overlay 표현 과함 | 모바일에서 `***` 또는 큰 검은 박스가 카드 전체를 덮어 사용성이 낮음 | YouTube Shorts/grid 화면 캡처, MaskOverlayController signature, bounds 비교 | Android는 DOM span이 아니라 좌표 overlay라 정확한 단어 위치가 없는 경우가 있음 | 작은 영역은 compact token, 긴 제목 영역은 `민감 표현` label로 분리하고 oversized card는 제한 | `MaskOverlayPlannerTest`, 실제 APK 설치 후 YouTube 화면 확인 | OCR 또는 더 세밀한 텍스트 bounds 없이는 이미지 내부 텍스트에 exact masking 불가 |

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
Android 앱에서는 Chrome extension과 달리 접근성 노드 기반으로 텍스트와 좌표를 수집해야 하므로, 바로 마스킹 UX를 구현하기보다 `/analyze_android` 분석 결과를 저장하고 앱에서 최근 분석 진단을 확인하는 루프를 먼저 구축했다.
또한 Android에서는 backend가 유해 boolean을 반환하더라도 `evidence_spans`가 없으면 실제 화면에 단어 단위로 안전하게 적용할 근거가 부족하므로, 원 응답은 보존하되 “마스킹 가능 댓글 수”는 span이 있는 결과만 집계하도록 분리했다.
YouTube 모바일 앱에서는 검색 결과 제목이 일반 text node가 아니라 `contentDescription`에 합쳐져 노출되거나, 썸네일 내부 이미지 텍스트처럼 접근성 트리에 아예 나타나지 않는 경우가 있었다.
따라서 접근성으로 확보 가능한 제목은 backend 후보로 분리해 보내고, 좌표는 카드 전체가 아니라 제목 영역으로 축소했으며, 이미지 내부 텍스트는 OCR이 필요한 별도 제약으로 기록했다.
다만 교수 피드백을 반영하면 “욕설 마스킹”이나 “모델 비교”만으로는 흔한 주제처럼 보일 수 있으므로, 청마루의 차별점은 문맥 민감성, 불확실성 관리, 플랫폼별 수집 제약 대응, evidence span 기반 중재로 정리했다.
이 과정에서 `시발 - 위키낱말사전`, `카필 시발(Kapil Sibal)`, `scripts`, `README`, `warp theme` 등 정상 문맥 오탐 사례와 `시발 뭐하는 거야`, `병신아 꺼져`, romanized 표현 등 유해 사례를 regression set으로 관리했다.
평가는 단순 API vs ML 비교가 아니라 keyword-only, ML-only, ML+normalization, ML+safe-context, full pipeline이 각각 어떤 실패 유형에서 다른지 확인하는 방식으로 확장한다.

## 5. 매 반복마다 남길 기록 형식

```md
### YYYY-MM-DD / 개선 제목

- GitHub Issue:
- GitHub PR:
- 문제:
- 실제 증거:
- 참고한 기준:
- 어려움:
- 제약:
- 사용 기술:
- 선택한 해결책:
- 대안과 보류 이유:
- 변경 파일 또는 PR:
- 검증:
- 결과:
- 남은 위험:
```

## 6. Notion 동기화 기준

Notion의 `Chungmaru` 데이터베이스는 보고서 작성용 인덱스입니다.
이 문서에 개선 흐름을 추가할 때 Notion에도 같은 단위로 항목을 남기며, 다음 필드는 반드시 채웁니다.

| 필드 | 작성 기준 |
| --- | --- |
| 어려움 | 왜 단순 구현으로 해결되지 않았는지 기록 |
| 제약 사항 | public API, backend contract, DOM 한계, 팀 작업 범위 등 유지해야 할 조건 기록 |
| 사용 기술 | FastAPI, Content Script, Service Worker, Evidence Span, Cache, Regression Test처럼 실제 사용 기술 선택 |
| 선택한 해결책 | 최종 적용한 방식과 적용 범위 기록 |
| 대안과 보류 이유 | 검토했지만 쓰지 않은 방식을 남겨 의사결정 근거 확보 |
| 검증 | 자동 테스트, 직접 backend 호출, 실제 브라우저 확인 중 하나 이상 기록 |
| 결과 | 해결, 부분 해결, 보류, 재현 필요 중 하나로 상태 고정 |

이 기준을 지키면 결과 보고서에서 “AI가 대신 구현했다”가 아니라 “문제를 관찰하고 제약을 분석한 뒤 기술 선택과 검증을 반복했다”는 흐름으로 설명할 수 있습니다.
