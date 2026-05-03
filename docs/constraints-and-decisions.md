# 제약 사항 및 의사결정 기록

이 문서는 청마루 프로젝트에서 반복적으로 영향을 준 기술 제약과 의사결정을 정리합니다.
보고서에서는 “개발 과정에서 고려한 제약 사항”과 “품질 향상을 위한 선택 근거”로 사용할 수 있습니다.

## 1. 핵심 제약 사항

| 구분 | 제약 | 영향 | 대응 방향 |
| --- | --- | --- | --- |
| 서비스 차별성 | 욕설 마스킹, 모델 비교, 키워드 필터는 이미 흔한 접근임 | 단순 기능 나열만으로는 교수 피드백과 결과 보고서에서 설득력이 약함 | “문맥/불확실성/플랫폼 수집/근거 기반 중재”를 핵심 차별점으로 재정의 |
| Backend runtime | 로컬 FastAPI 서버와 현재 `/analyze_batch` 계약을 유지해야 함 | 새 endpoint나 응답 구조 변경은 Android, extension, shared contract와 충돌 가능 | public API 변경 없이 내부 scheduling, cache, diagnostics 중심으로 개선 |
| 모델 품질 | 모델이 모든 문맥과 우회 표현을 완벽히 판별하지 못함 | 오탐/미탐이 발생할 수 있고, 단순 frontend 규칙만으로 해결하면 AI 기반 의미가 약해짐 | regression case를 누적하고 backend authoritative 원칙 유지 |
| Chrome extension | content script는 페이지 DOM과 같은 환경에서 동작하지만 native input 내부 text span을 직접 제어하기 어려움 | 검색창/textarea 마스킹 위치 흔들림, UI 깨짐 가능 | 입력창은 별도 fixed-token UX로 분리하고 일반 DOM은 exact span wrapper 적용 |
| Google DOM | Google 검색 결과, AI Overview, SFC 영역은 DOM 구조가 자주 바뀌고 동적으로 삽입됨 | page load 직후 누락, 스크롤 후 늦은 적용, selector 유지보수 비용 발생 | visible container 우선 수집, high-signal rescue, mutation/visibility queue 사용 |
| 실시간성 | backend 모델 호출은 네트워크, 모델 로딩, CPU/GPU 상태에 영향을 받음 | 사용자가 먼저 읽은 뒤 마스킹될 수 있음 | 첫 화면 후보를 작게 보내고, foreground/reconcile을 분리 |
| 정확도와 속도 | 빠르게 가리려면 적은 문맥으로 판단해야 하고, 정확히 판단하려면 더 넓은 문맥이 필요함 | 즉시성 강화가 오탐을 늘릴 수 있음 | foreground는 고위험 후보를 빠르게 처리하고, reconcile에서 문맥 보정 |
| 비동기 race | 페이지 변화, 설정 변경, backend 응답 도착 순서가 다를 수 있음 | 꺼진 상태에서도 가려지거나, 가려졌다가 풀리는 flicker 발생 | generation, settings revision, fingerprint 기반 stale response drop |
| 사용자 설정 | 민감도와 카테고리 설정은 사용자가 체감하는 결과에 직접 영향 | 설정 변경 후 이전 캐시가 남으면 신뢰도 하락 | cache key에 sensitivity 포함, schema bump, apply-time guard 적용 |
| Android 접근성 API | Android 앱 내부 DOM이나 text span에 직접 접근할 수 없고 접근성 노드의 텍스트/좌표만 얻을 수 있음 | Chrome extension처럼 단어 단위 DOM wrapper를 바로 적용하기 어렵고 앱별 댓글 UI 구조에 따라 누락 가능 | 먼저 “댓글 수집 -> backend 분석 -> 진단/결과 저장” 루프를 안정화하고, 차단 UX는 좌표 기반 overlay 후보로 별도 검증 |
| Android 네트워크 | Android 기기에서 `127.0.0.1`은 Mac이 아니라 기기 자신을 의미함 | 백엔드가 실행 중이어도 폰에서 연결 실패 가능 | 분석 API 주소를 별도 설정값으로 분리하고 Tailscale/LAN IP 또는 adb reverse를 검증 경로로 사용 |
| Android 검증 환경 | 단위 테스트/빌드와 실제 접근성 이벤트 검증이 분리됨 | 로컬 빌드 성공만으로 YouTube/Instagram/TikTok 댓글 수집 품질을 보장할 수 없음 | unit test와 APK build로 계약/컴파일을 검증하고, 실사용 수집 품질은 저장된 snapshot/result JSON으로 추적 |
| Android 빌드 JDK | 현재 시스템 기본 JDK 24에서 Gradle Android unit test 태스크 생성 오류가 발생함 | 테스트가 코드 오류처럼 보일 수 있고 재현성이 떨어짐 | Android Studio 내장 JBR을 `JAVA_HOME`으로 지정해 테스트/빌드 명령을 표준화 |
| Evidence span | backend boolean 판정과 실제 마스킹 가능한 span은 항상 일치하지 않음 | span 없는 positive를 바로 차단하면 오탐처럼 보이거나 Android overlay에서 적용 위치를 특정할 수 없음 | 원 분석 결과는 보존하되, 화면 적용/차단 가능 집계는 evidence span이 있는 결과로 제한 |
| Android YouTube 접근성 트리 | YouTube 검색 결과/Shorts 카드는 실제 제목이 일반 text node가 아니라 큰 `contentDescription` 안에 들어가는 경우가 있음 | backend가 분석할 후보 자체가 누락되거나, 반대로 큰 카드 bounds를 그대로 쓰면 썸네일/카드 위에 떠 있는 오버레이가 생김 | `contentDescription` only composite card는 마스킹 후보에서 제외하고, 실제 text node bounds가 있는 항목만 overlay 적용 |
| 이미지 내부 텍스트 | YouTube 썸네일/영상 프레임에 박힌 자막형 텍스트는 접근성 노드에 텍스트로 노출되지 않음 | 현재 접근성 기반 수집만으로는 모델에 전달할 문자열이 없어 마스킹 불가 | OCR/screenshot 분석은 별도 spike로 분리하고, 그 전까지는 이미지 전체 blanket masking 금지 |
| 모바일 overlay UX | Android는 DOM span wrapper가 불가능하고 좌표 기반 overlay만 가능함 | 정확한 단어 위치를 모르면 `***` 또는 큰 박스가 어색하게 보일 수 있음 | 입력/짧은 텍스트는 compact token, 제목/큰 영역은 작은 chip 또는 축소 bounds로 표시하고 전체 카드 마스킹은 피함 |

## 1-1. 제약 분류 코드

새 문제나 개선 PR은 아래 코드 중 하나 이상으로 분류합니다.
보고서에서는 이 코드를 “개발 과정에서 확인한 제약”의 축으로 사용합니다.

| 코드 | 제약 축 | 판정 기준 | 대표 증거 |
| --- | --- | --- | --- |
| C1 | 서비스 차별성 | 단순 욕설 차단과 구분되는 가치가 필요한 경우 | 교수 피드백, 서비스 정의서, 모델 비교 결과 |
| C2 | Backend 계약/모델 | `/analyze_batch`, `/analyze_android`, label, `evidence_spans`와 관련된 경우 | backend 응답, regression test, API 호출 로그 |
| C3 | 후보 수집/플랫폼 DOM | Google/YouTube/GitHub/일반 웹에서 텍스트 후보가 누락되거나 과다 수집되는 경우 | DOM snippet, 접근성 dump, 후보 JSON |
| C4 | 실시간성/비동기 | 사용자가 먼저 읽은 뒤 마스킹되거나 race/flicker가 생기는 경우 | popup latency, stale response log, 스크롤 재현 |
| C5 | 마스킹 UI/렌더링 | 입력창, 일반 DOM, Android overlay의 위치/표현이 깨지는 경우 | 캡처, 영상, bounds, zoom/viewport 정보 |
| C6 | 설정/민감도/캐시 | sensitivity, category, enabled 상태와 실제 적용 결과가 어긋나는 경우 | 설정 스냅샷, cache hit, schema/revision 로그 |
| C7 | Android 접근성/OCR | Android 접근성 트리, 좌표 overlay, 이미지 내부 텍스트 한계와 관련된 경우 | uiautomator dump, parse/analysis JSON, APK 검증 |
| C8 | 검증/운영 환경 | 로컬 서버, JDK, emulator, 네트워크, 재현 환경 문제인 경우 | 테스트 명령, build log, health check, device log |

제약 코드는 문제를 단순 증상으로 남기지 않기 위한 최소 분류입니다.
예를 들어 “YouTube 제목이 안 가려짐”은 C3 후보 수집 문제인지, C7 접근성 한계인지, C2 모델 미탐인지 먼저 구분해야 합니다.

## 2. 주요 의사결정

### Decision 1. 최종 판정은 backend 모델 기준으로 유지

- 선택: frontend는 최종 판단자가 아니라 backend 결과를 빠르게 적용하는 runtime으로 둔다.
- 이유: 프로젝트 주제가 AI/문맥 기반 필터링이므로 단순 frontend 금칙어 차단으로 흐르면 차별성이 약해진다.
- 대안: extension 내부 사전 기반 즉시 차단.
- 대안 보류 이유: 속도는 빠르지만 문맥 오탐이 커지고 backend 모델의 의미가 약해진다.

### Decision 2. 전체 요소가 아니라 exact span만 마스킹

- 선택: backend의 `evidence_spans`가 있는 부분만 마스킹한다.
- 이유: 제목, URL, snippet 전체가 사라지면 사용성 저하와 오탐 체감이 커진다.
- 대안: leaf element 또는 result card 전체 마스킹.
- 대안 보류 이유: 구현은 쉽지만 실제 Google/GitHub 화면에서 정상 정보까지 사라졌다.

### Decision 3. 입력창은 일반 DOM span 방식과 분리

- 선택: Google 검색창 같은 native editable은 fixed-token UX로 별도 처리한다.
- 이유: textarea 내부 텍스트에 DOM span을 삽입할 수 없고, overlay는 scroll/line-height/zoom 차이로 흔들릴 수 있다.
- 대안: mirror overlay로 exact span 위치를 맞춘다.
- 대안 보류 이유: 테스트 중 위치 흔들림이 반복되어 유지보수 비용이 컸다.

### Decision 4. 첫 화면 우선 수집

- 선택: 전체 페이지를 한 번에 분석하지 않고 visible container, active input, high-signal text를 우선 처리한다.
- 이유: 전체 DOM scan은 느리고 mutation-heavy 페이지에서 반복 분석을 유발한다.
- 대안: page load마다 full TreeWalker scan.
- 대안 보류 이유: 속도 저하, flicker, 불필요한 backend 요청 증가가 발생했다.

### Decision 5. Regression set을 품질 관리 기준으로 사용

- 선택: 오탐/미탐 사례를 regression case로 누적한다.
- 이유: “느낌상 이상함”을 줄이고, 보고서에서 개선 근거를 설명하기 쉽다.
- 예시 safe: `카필 시발(Kapil Sibal)`, `시발 - 위키낱말사전`, `국제차량제작 시발`, `scripts`, `README`, `warp theme`, `abstract factory`
- 예시 offensive: `시발 뭐하는 거야`, `병신아 꺼져`, `개새끼 꺼져`, romanized/qwerty 변형

### Decision 6. 모델 비교는 파이프라인 실패 분석으로 사용

- 선택: `API vs ML`을 단순 모델 성능 비교가 아니라 keyword-only, ML-only, ML+normalization, ML+safe-context, full pipeline 비교로 확장한다.
- 이유: 교수 피드백 기준으로 “모델을 비교했다”만으로는 흔하고, 실제 서비스 난점인 문맥 오탐, 플랫폼 수집, evidence span, 지연 문제를 설명하기 어렵다.
- 대안: 모델별 accuracy/F1만 표로 제시한다.
- 대안 보류 이유: 결과 보고서에서 청마루가 왜 Chrome Extension과 Android 수집까지 다뤘는지 설명하기 어렵고, 실제 사용 환경의 실패 원인을 드러내지 못한다.

### Decision 7. 불확실성은 실패가 아니라 관리 대상

- 선택: 모델이 애매하게 판단한 사례는 무조건 차단/허용으로 단순화하지 않고 warning, review, regression 후보로 기록한다.
- 이유: 문맥 기반 필터링에서 “잘 모르겠는 상황”은 필연적으로 발생하며, 이를 숨기면 오탐/미탐 개선 근거가 사라진다.
- 대안: 모든 판정을 binary mask/safe로만 처리한다.
- 대안 보류 이유: 구현은 단순하지만 결과 보고서에서 문맥 판단의 제약과 개선 과정을 설명하기 어렵다.

### Decision 8. Android는 먼저 분석 루프를 검증하고 차단 UX는 분리

- 선택: Android 1차 목표는 “앱 댓글 수집 -> `/analyze_android` 분석 -> 결과 저장/진단 표시”로 둔다.
- 이유: 접근성 API는 DOM span 제어가 불가능하고, 앱별 댓글 구조가 달라 바로 마스킹부터 구현하면 정확도 문제와 UI 깨짐을 구분하기 어렵다.
- 대안: 수집 직후 좌표 기반 오버레이로 바로 화면을 가린다.
- 대안 보류 이유: 수집 품질과 backend 분석 품질이 검증되지 않은 상태에서 overlay를 붙이면 오탐/미탐/좌표 오차의 원인을 분리하기 어렵다.
- 다음 단계: 저장된 `parse_results`와 `analysis_results` JSON을 비교해 수집 누락, 모델 오탐/미탐, 지연을 먼저 계측한 뒤 overlay 차단 UX를 별도 PR로 검증한다.

### Decision 9. span 없는 positive는 즉시 차단 가능 결과로 세지 않음

- 선택: backend 응답의 `is_offensive=true`만으로 화면 적용 가능 결과로 보지 않고, `evidence_spans`가 있는 결과만 “마스킹 가능”으로 집계한다.
- 이유: 모델이 문장 전체를 유해 후보로 보더라도 span이 없으면 extension exact masking이나 Android 좌표 overlay에 안정적으로 적용할 근거가 부족하다.
- 대안: boolean positive를 모두 차단 후보로 표시한다.
- 대안 보류 이유: 오탐처럼 보이는 결과가 늘고, 특히 Android에서는 어떤 화면 좌표를 가려야 하는지 분리하기 어렵다.
- 다음 단계: UI에서 “모델 유해 후보 수”와 “마스킹 가능 span 수”를 분리해서 보여줄지 검토한다.

### Decision 10. Android YouTube는 실제 text bounds와 OCR 대상을 분리

- 선택: 접근성 트리에 실제 text bounds로 노출된 텍스트와, `contentDescription` only 카드/이미지 내부 텍스트를 같은 문제로 다루지 않는다.
- 이유: `contentDescription`은 카드 설명 문자열일 뿐 글자별 좌표가 아니어서 evidence span을 화면 위치로 안정적으로 투영할 수 없다.
- 적용: YouTube `contentDescription` only composite card는 화면 마스킹 후보에서 제외하고, 실제 text node bounds가 있는 제목/댓글/입력 영역만 overlay로 렌더링한다.
- 대안: 썸네일 또는 카드 전체를 “민감 표현”으로 덮는다.
- 대안 보류 이유: 사용자가 정상 썸네일까지 볼 수 없고, 모델이 실제 이미지 텍스트를 본 것이 아니므로 근거 기반 마스킹이라고 보기 어렵다.
- 다음 단계: OCR/screenshot spike를 별도 이슈로 분리해 성능, 배터리, 개인정보, Android 권한 제약을 먼저 검증한다.

## 3. 보고서에 넣기 좋은 제약 요약

청마루의 핵심 제약은 “정확한 문맥 판단”과 “사용자가 읽기 전 빠른 반영”이 서로 충돌한다는 점이다.
문맥 판단을 위해 넓은 텍스트를 backend 모델에 보내면 정확도는 좋아지지만 지연이 생기고, 빠르게 가리기 위해 짧은 텍스트만 보면 오탐 가능성이 커진다.
또한 Chrome DOM과 Android 접근성 트리는 텍스트를 제공하는 방식이 달라 같은 모델을 사용해도 플랫폼 수집 단계에서 누락과 왜곡이 생길 수 있다.
따라서 본 프로젝트는 backend 모델을 최종 판정 기준으로 유지하면서, extension에서는 visible container 우선 수집, exact span 마스킹, stale response 방지, cache 분리로 실시간성을 보완했다.
Android에서는 같은 backend 모델을 사용하되, 접근성 노드의 텍스트와 좌표만 안정적으로 확보할 수 있으므로 먼저 분석 결과 저장과 진단 UI를 통해 수집 품질을 검증한다.
이때 boolean 판정과 evidence span을 구분해, 모델이 의심한 문장과 실제로 화면에 적용 가능한 마스킹 근거를 따로 관리한다.
평가에서는 단순 모델 비교가 아니라 문맥, 정규화, evidence span, 플랫폼 수집까지 포함한 pipeline 비교를 사용한다.

## 4. 앞으로의 관리 규칙

1. 새로운 오탐/미탐은 “문장 전체, 기대 결과, 실제 결과, 캡처 또는 URL”과 함께 기록한다.
2. backend 변경은 regression test가 추가될 때만 진행한다.
3. input/search UX는 현재 fixed-token 방식이 안정화된 기준이므로 별도 이슈 없이 수정하지 않는다.
4. Google DOM selector 변경은 실제 DOM 증거가 있을 때만 추가한다.
5. 성능 개선은 `first mask latency`, `backend reconcile latency`, `masked span count`, `cache hit`을 함께 본다.
6. Notion 기록은 문제 추적용이 아니라 보고서 근거용이므로 `어려움`, `사용 기술`, `대안과 보류 이유`, `결과`를 비워두지 않는다.
7. 새 개선을 시작할 때는 기존 제약과 충돌하지 않는지 먼저 확인하고, 충돌이 있으면 코드보다 의사결정 기록을 먼저 갱신한다.
8. GitHub Issue/PR이 없는 변경은 보고서에 쓰기 어려우므로, 의미 있는 개선은 반드시 GitHub 이력으로 남긴다.
9. main에는 squash merge된 의미 단위 커밋만 남기고, 실험성 반복 커밋은 작업 브랜치 밖으로 노출하지 않는다.
10. 새 기능을 추가하기 전, 해당 기능이 “문맥/불확실성/플랫폼 수집/근거 기반 중재” 중 어느 차별점에 기여하는지 먼저 확인한다.
11. Android/extension에서 화면에 적용하는 값은 backend boolean만이 아니라 `evidence_spans` 유효성까지 함께 확인한다.
12. Android에서 화면 좌표가 큰 카드/이미지 전체를 가리키는 경우, 그대로 마스킹하지 말고 제목/댓글/입력 영역으로 축소 가능한지 먼저 검토한다.
13. 접근성 트리에 없는 이미지 내부 텍스트 문제는 OCR 후보로 기록하고, 접근성 수집 로직의 실패로 혼동하지 않는다.
