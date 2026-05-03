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
| Android YouTube 제목 누락 | YouTube 검색 결과 제목과 영상 카드 제목이 마스킹되지 않음 | `uiautomator dump`, `parse_results`, `analysis_results` 비교 결과 제목이 일반 text node가 아니라 `content-desc`에 들어 있거나 아예 썸네일 이미지 안에만 존재 | 접근성 API는 이미지 내부 글자를 제공하지 않고, composite card bounds를 그대로 쓰면 카드/썸네일 위에 떠 있는 오버레이가 생김 | `content-desc` only composite card는 화면 마스킹 후보에서 제외하고, 실제 text node bounds가 있는 제목/댓글만 적용 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `adb install`, 최신 parse/analysis JSON bounds 비교 | 썸네일/영상 프레임 내부 텍스트는 OCR 없이는 처리 불가 |
| Android overlay 표현 과함 | 모바일에서 `***` 또는 큰 검은 박스가 카드 전체를 덮어 사용성이 낮음 | YouTube Shorts/grid 화면 캡처, MaskOverlayController signature, bounds 비교 | Android는 DOM span이 아니라 좌표 overlay라 정확한 단어 위치가 없는 경우가 있음 | 작은 영역은 compact token, 긴 제목 영역은 `민감 표현` label로 분리하고 oversized card는 제한 | `MaskOverlayPlannerTest`, 실제 APK 설치 후 YouTube 화면 확인 | OCR 또는 더 세밀한 텍스트 bounds 없이는 이미지 내부 텍스트에 exact masking 불가 |
| Android Shorts/grid 마스크 어긋남 | Shorts/grid 썸네일 위에 `***`가 실제 글자와 맞지 않는 위치에 표시됨 | YouTube 모바일 검색 grid 캡처, content-desc only 카드 후보 비교 | content-desc만 있는 Shorts/grid 카드는 접근성 트리에 제목 문자열은 있지만 글자별 화면 좌표가 없음 | exact bounds가 없는 Shorts/grid composite card는 foreground 후보에서 제외하고, 실제 text node나 영상 상세 제목처럼 좌표가 있는 후보만 마스킹 | `YoutubeAnalysisTargetExtractorTest`, `MaskOverlayPlannerTest`, `:app:testDebugUnitTest` | 썸네일 내부 자막/이미지 글자 마스킹은 OCR 기반 별도 기능으로 분리 필요 |
| Android 이미지 내부 텍스트 인식 경로 부재 | YouTube 썸네일/영상 프레임 안의 `Tlqkf`, 한글 욕설 자막은 접근성 text node가 아니어서 안 가려짐 | YouTube grid/Shorts 캡처, Android SDK `AccessibilityService.takeScreenshot` capability 확인 | 정확한 위치 기반 마스킹은 접근성 bounds가 아니라 OCR이 반환한 text box가 필요하며, 기존 overlay에 섞으면 다시 떠다니는 mask가 생김 | OCR 결과를 `VisualTextCandidate`로 분리하고, backend span 결과를 OCR text bounds에만 투영하는 `VisualTextMaskPlanner`와 단위 테스트 추가 | `VisualTextMaskPlannerTest`, `:app:testDebugUnitTest` | 실제 OCR 엔진/성능/배터리/개인정보 검증은 다음 단계 spike로 분리 |
| Android 이미지 텍스트 캡처 상태 불명 | 썸네일/영상 내부 글자 누락이 backend 미탐인지 캡처/OCR 경로 미지원인지 앱에서 구분하기 어려움 | Android 접근성 service metadata, 진단 UI, BBA-79 | 스크린샷 기반 OCR은 Android 11+와 accessibility screenshot capability가 필요하며, 지원 여부 확인 없이 OCR을 붙이면 기기별 실패 원인 추적이 어려움 | `VisualTextCaptureSupport`로 SDK/capability를 판별하고 최근 분석 진단에 이미지 텍스트 캡처 가능 여부와 사유를 노출 | `VisualTextCaptureSupportTest`, `:app:testDebugUnitTest` | OCR 엔진 선택과 viewport 2~4개 후보 제한 정책은 다음 실험에서 별도 검증 |
| Android overlay 재부착 지연 | YouTube 스크롤 후 마스크가 사라진 뒤 늦게 다시 붙어 사용자가 먼저 텍스트를 볼 수 있음 | APK 설치 후 YouTube 스크롤 이벤트, 최신 parse/analysis JSON 후보 비교 | 스크롤 중 기존 overlay 좌표를 유지하면 잘못된 위치를 가릴 수 있어 즉시 clear 자체는 유지해야 함 | 스크롤/콘텐츠/윈도우 이벤트의 재분석 예약 지연과 in-flight 재시도 지연을 줄여 clear 이후 재부착 시간을 단축 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `adb install`, YouTube 스크롤 후 최신 JSON 확인 | 실시간 좌표 추적은 접근성 이벤트 주기와 앱 렌더링 속도에 의존하므로 완전 무지연은 보장 불가 |
| Android raw node 로그 비용 | 후보가 적을 때마다 접근성 raw node를 대량으로 `Log.d` 출력해 스크롤 중 지연과 로그 노이즈가 커질 수 있음 | `YoutubeAccessibilityService`의 `YT_RAW`, `IG_RAW`, `TT_RAW` 덤프 경로 확인 | 후보 추출 실패 디버깅 수단은 유지하되 기본 실사용 경로를 느리게 만들면 안 됨 | raw node 덤프를 `Log.isLoggable(TAG, Log.VERBOSE)` 조건으로 제한해 필요할 때만 켜도록 변경 | `:app:testDebugUnitTest`, `:app:assembleDebug`, `adb install` | 실제 기기에서 로그 레벨을 올린 상태로 테스트할 때는 다시 성능 저하 가능 |
| Android 지원 앱 overlay 불일치 | Instagram/TikTok은 후보 수집과 backend 분석은 수행하지만 실제 overlay는 YouTube에서만 렌더링됨 | `YoutubeAccessibilityService.updateMaskOverlay` 조건과 각 플랫폼 extractor 경로 비교 | 플랫폼별 extractor가 이미 좌표를 정제한 결과만 overlay에 넘겨야 하며 새 이미지/OCR 마스킹을 섞으면 안 됨 | YouTube 전용 조건을 지원 앱 공통 조건으로 바꾸고 기존 exact-span overlay renderer를 그대로 재사용 | `:app:testDebugUnitTest`, `:app:assembleDebug` | Instagram/TikTok 실제 앱 화면별 좌표 품질은 별도 실기기 검증 필요 |
| Android 여러 줄 overlay 위치 오차 | 긴 제목/댓글에서 evidence span을 한 줄처럼 가로 비율로만 계산해 마스크가 실제 단어와 다른 줄에 찍힐 수 있음 | `MaskOverlayController`의 `toSpanSpec` 계산 방식과 긴 YouTube 제목 캡처 비교 | 접근성 API는 글자별 bounding box를 주지 않으므로 완전한 glyph-level exact masking은 불가 | bounds 높이로 줄 수를 보수적으로 추정하고, span 시작 위치가 속한 줄 안에서만 가로 좌표를 계산하도록 planner 보정 | `MaskOverlayPlannerTest`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `adb install` | 실제 앱 폰트/줄바꿈과 추정 줄 수가 다르면 OCR 또는 앱별 텍스트 레이아웃 추정 고도화 필요 |
| Android backend 실패 지연 | backend가 멈추거나 네트워크가 불안정하면 분석 in-flight 상태가 길어져 다음 화면 후보 분석이 늦어짐 | `AndroidAnalysisClient`의 connect/read timeout과 사용자가 보고한 timeout/느린 반응 사례 비교 | 모바일 foreground UX에서는 오래 기다리는 것보다 빠르게 실패하고 다음 이벤트에서 재시도하는 편이 낫지만, cold model에는 불리할 수 있음 | Android 분석 요청 timeout을 실시간 경로 기준으로 낮춰 실패 시 루프가 빨리 풀리도록 변경 | `:app:testDebugUnitTest`, `:app:assembleDebug` | cold start 모델이 2초 이상 걸리면 첫 요청이 실패할 수 있으므로 backend warm 상태 유지 필요 |
| Android emoji offset 불일치 | 이모지가 앞에 있는 제목/댓글에서 backend evidence span 위치보다 Android overlay가 앞쪽에 찍힐 수 있음 | backend는 Python code point offset을 반환하고 Android `String.length`는 UTF-16 code unit을 세는 차이 확인 | HTTP contract는 바꾸지 않고 Android renderer에서 backend offset 단위에 맞춰야 함 | overlay planner가 원문 길이를 UTF-16 길이가 아니라 code point 길이로 계산하도록 변경 | `MaskOverlayPlannerTest`, `:app:testDebugUnitTest` | 복잡한 조합형 grapheme cluster까지 완전한 시각 단위로 맞추려면 추가 layout/OCR 보정 필요 |
| Android 민감도 계약 누락 | Android에서는 앱 설정 민감도가 backend `/analyze_android`로 전달되지 않아 민감도 0에서도 기본 threshold로 분석될 수 있음 | Chrome/evaluation은 sensitivity 0을 “차단하지 않음”으로 정의하지만 AndroidRequest schema와 client payload에는 sensitivity가 없음 | 기존 `/analyze_android` 응답 shape와 모델 라벨 체계는 유지해야 하며 Android cache도 설정별로 분리되어야 함 | Android 요청에 optional sensitivity를 추가하고, 앱 설정 UI/저장소/cache key/backend 분석 호출을 같은 값으로 정렬 | backend regression, `AnalysisSensitivityStoreTest`, `:app:testDebugUnitTest` | Android UI의 민감도 변경은 다음 접근성 이벤트부터 반영되므로 이미 떠 있는 overlay는 재분석 이벤트가 필요함 |
| Android contentDescription overlay 오차 | YouTube 카드/썸네일에서 `***`가 실제 텍스트가 아닌 이미지나 카드 상단에 떠 보임 | 모바일 YouTube 캡처, `contentDescriptionOnly` composite card fallback, overlay planner bounds 비교 | contentDescription은 문자열 설명일 뿐 글자별 bounds가 아니므로 exact span overlay 근거로 사용할 수 없음 | composite contentDescription-only 후보는 제외하고, overlay view는 제거 후 재생성이 아니라 기존 view update로 갱신해 flicker를 줄임 | `YoutubeAnalysisTargetExtractorTest`, `MaskOverlayPlannerTest`, `:app:testDebugUnitTest`, `:app:assembleDebug` | 정확한 썸네일/이미지 내부 텍스트 차단은 OCR 또는 앱별 렌더링 분석이 필요함 |
| Android overlay fallback 오차 | span 좌표 계산이 실패한 경우 전체 bounds fallback이 카드/행/검색창 일부를 크게 덮어 위치가 떠 보임 | 사용자 캡처, `AndroidMaskOverlayPlanner.toSpecs` fallback 경로, sensitivity 변경 후 stale overlay 사례 | 접근성 bounds는 노드 사각형이지 글자별 좌표가 아니므로 실패 시 전체를 덮으면 오히려 오탐처럼 보임 | fallback 전체 마스킹을 제거하고, 작은 고신뢰 text bounds에서 계산 가능한 span만 렌더링하며 sensitivity 변경 시 cache/overlay를 즉시 무효화 | `MaskOverlayPlannerTest`, `:app:testDebugUnitTest`, `:app:assembleDebug` | 더 넓은 Android 화면 차단은 OCR 또는 앱별 layout 추정 없이는 정확도/속도 동시 보장 불가 |
| Android 미탐/좌표제외 구분 어려움 | 화면에서 안 가려진 항목이 backend 미탐인지 overlay 좌표 불안정으로 제외된 것인지 구분하기 어려움 | Android 진단 UI가 댓글 수/마스킹 가능 수만 보여주고 overlay 적용 가능성을 따로 보여주지 않음 | 좌표 기반 UX는 실패 원인을 분해하지 않으면 개선 방향을 정할 수 없음 | overlay 후보 수, 실제 렌더 수, 좌표 불안정 제외 수를 진단에 저장/표시 | `MaskOverlayPlannerTest`, `:app:testDebugUnitTest`, `:app:assembleDebug` | 후보 수가 많고 렌더 수가 적으면 OCR/layout spike가 필요하고, 후보 수 자체가 적으면 extractor 문제로 분리 |

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
