# 제약 사항 및 의사결정 기록

이 문서는 청마루 프로젝트에서 반복적으로 영향을 준 기술 제약과 의사결정을 정리합니다.
보고서에서는 “개발 과정에서 고려한 제약 사항”과 “품질 향상을 위한 선택 근거”로 사용할 수 있습니다.

## 1. 핵심 제약 사항

| 구분 | 제약 | 영향 | 대응 방향 |
| --- | --- | --- | --- |
| Backend runtime | 로컬 FastAPI 서버와 현재 `/analyze_batch` 계약을 유지해야 함 | 새 endpoint나 응답 구조 변경은 Android, extension, shared contract와 충돌 가능 | public API 변경 없이 내부 scheduling, cache, diagnostics 중심으로 개선 |
| 모델 품질 | 모델이 모든 문맥과 우회 표현을 완벽히 판별하지 못함 | 오탐/미탐이 발생할 수 있고, 단순 frontend 규칙만으로 해결하면 AI 기반 의미가 약해짐 | regression case를 누적하고 backend authoritative 원칙 유지 |
| Chrome extension | content script는 페이지 DOM과 같은 환경에서 동작하지만 native input 내부 text span을 직접 제어하기 어려움 | 검색창/textarea 마스킹 위치 흔들림, UI 깨짐 가능 | 입력창은 별도 fixed-token UX로 분리하고 일반 DOM은 exact span wrapper 적용 |
| Google DOM | Google 검색 결과, AI Overview, SFC 영역은 DOM 구조가 자주 바뀌고 동적으로 삽입됨 | page load 직후 누락, 스크롤 후 늦은 적용, selector 유지보수 비용 발생 | visible container 우선 수집, high-signal rescue, mutation/visibility queue 사용 |
| 실시간성 | backend 모델 호출은 네트워크, 모델 로딩, CPU/GPU 상태에 영향을 받음 | 사용자가 먼저 읽은 뒤 마스킹될 수 있음 | 첫 화면 후보를 작게 보내고, foreground/reconcile을 분리 |
| 정확도와 속도 | 빠르게 가리려면 적은 문맥으로 판단해야 하고, 정확히 판단하려면 더 넓은 문맥이 필요함 | 즉시성 강화가 오탐을 늘릴 수 있음 | foreground는 고위험 후보를 빠르게 처리하고, reconcile에서 문맥 보정 |
| 비동기 race | 페이지 변화, 설정 변경, backend 응답 도착 순서가 다를 수 있음 | 꺼진 상태에서도 가려지거나, 가려졌다가 풀리는 flicker 발생 | generation, settings revision, fingerprint 기반 stale response drop |
| 사용자 설정 | 민감도와 카테고리 설정은 사용자가 체감하는 결과에 직접 영향 | 설정 변경 후 이전 캐시가 남으면 신뢰도 하락 | cache key에 sensitivity 포함, schema bump, apply-time guard 적용 |

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

## 3. 보고서에 넣기 좋은 제약 요약

청마루의 핵심 제약은 “정확한 문맥 판단”과 “사용자가 읽기 전 빠른 반영”이 서로 충돌한다는 점이다.
문맥 판단을 위해 넓은 텍스트를 backend 모델에 보내면 정확도는 좋아지지만 지연이 생기고, 빠르게 가리기 위해 짧은 텍스트만 보면 오탐 가능성이 커진다.
따라서 본 프로젝트는 backend 모델을 최종 판정 기준으로 유지하면서, extension에서는 visible container 우선 수집, exact span 마스킹, stale response 방지, cache 분리로 실시간성을 보완했다.

## 4. 앞으로의 관리 규칙

1. 새로운 오탐/미탐은 “문장 전체, 기대 결과, 실제 결과, 캡처 또는 URL”과 함께 기록한다.
2. backend 변경은 regression test가 추가될 때만 진행한다.
3. input/search UX는 현재 fixed-token 방식이 안정화된 기준이므로 별도 이슈 없이 수정하지 않는다.
4. Google DOM selector 변경은 실제 DOM 증거가 있을 때만 추가한다.
5. 성능 개선은 `first mask latency`, `backend reconcile latency`, `masked span count`, `cache hit`을 함께 본다.
