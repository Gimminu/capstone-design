# 보고서 작성 및 이력 관리 워크플로우

이 문서는 GitHub, Linear, Notion을 함께 사용할 때 어떤 정보를 어디에 남길지 정리한 운영 기준입니다.
목표는 교수님이 요구한 “어떤 문제를 참고했고, 어떤 제약 아래에서 무엇을 개선했는지”를 결과 보고서에 쉽게 옮기는 것입니다.

## 1. 도구별 역할

| 도구 | 역할 | 남길 내용 |
| --- | --- | --- |
| GitHub PR | 코드 변경의 공식 이력 | 변경 파일, 구현 이유, 검증 명령, merge commit |
| Linear | 반복 개선과 문제 추적 | 실제 문제, 캡처/URL, 가설, 실험 결과, 남은 이슈 |
| Notion | 보고서용 정리본 | 주차별 요약, 제약 사항, 개선 전후 비교, 발표 문장 |
| `docs/engineering-history.md` | repo 내부 근거 문서 | 핵심 개선 흐름, PR별 의미, 보고서용 문장 |
| `docs/constraints-and-decisions.md` | 제약/의사결정 문서 | 왜 특정 방식을 선택하거나 보류했는지 |

## 2. Notion 데이터베이스 권장 구조

Notion에는 아래 속성을 가진 데이터베이스 하나를 만들면 충분합니다.

| 속성 | 타입 | 예시 |
| --- | --- | --- |
| 날짜 | Date | 2026-04-30 |
| 영역 | Select | Backend, Extension, UI, Model, Docs |
| 문제 유형 | Select | 오탐, 미탐, 지연, UI 깨짐, 연동 실패, 문서화 |
| 실제 증거 | Text / URL | Google 검색 캡처, Chrome console error, PR 링크 |
| 참고 기준 | Text | `/analyze_batch` contract, regression test, 서비스 정의서 |
| 제약 사항 | Text | public API 변경 금지, input DOM span 불가 |
| 선택한 해결책 | Text | visible high-signal rescue pass |
| 대안 | Text | full-page scan, frontend-only dictionary |
| 보류 이유 | Text | 성능 저하, 오탐 증가 |
| 변경 링크 | URL | GitHub PR |
| 검증 | Text | unittest, node --check, manual browser test |
| 결과 | Select | 해결, 부분 해결, 보류, 재현 필요 |
| 보고서 반영 여부 | Checkbox | checked |

## 3. Linear 이슈/코멘트 템플릿

```md
## YYYY-MM-DD 개선 루프

### 문제
- 실제 사용자 화면 또는 콘솔에서 관찰된 문제:

### 증거
- URL:
- 캡처:
- 로그:

### 제약
- 유지해야 하는 계약:
- 건드리지 않아야 하는 영역:

### 가설
- 원인 후보:

### 수정
- 변경 파일:
- 선택한 방식:
- 대안과 보류 이유:

### 검증
- 자동 검증:
- 수동 검증:

### 결과
- 해결된 것:
- 남은 것:
```

## 4. GitHub PR 본문 템플릿

```md
## 요약
- 무엇을 개선했는지 2~3줄로 작성

## 문제와 근거
- 어떤 화면/로그/테스트에서 문제가 확인됐는지 작성

## 제약
- 유지한 API, 건드리지 않은 파일, UX 고정 사항 작성

## 변경 내용
- 실제 변경 사항 작성

## 검증
- 실행한 명령과 결과 작성

## 보고서 반영 메모
- 이 PR이 프로젝트 품질 향상에서 어떤 의미인지 작성
```

## 5. 주차별 보고서 정리 방식

매주 아래 순서로 정리합니다.

1. GitHub에서 해당 주 PR 목록을 확인합니다.
2. Linear 코멘트에서 실제 문제와 캡처를 확인합니다.
3. `docs/engineering-history.md`의 표에 핵심 개선 흐름을 한 줄 추가합니다.
4. `docs/constraints-and-decisions.md`에 새 제약 또는 의사결정이 있으면 추가합니다.
5. Notion에는 보고서 문장 형태로 요약합니다.

## 6. 결과 보고서 섹션 초안

### 개발 과정의 문제와 개선

본 프로젝트는 Chrome Extension과 FastAPI backend를 연동해 실제 웹 페이지의 텍스트를 분석하고 마스킹하는 구조로 개발되었다.
개발 중 단순 키워드 차단 방식은 구현이 쉽지만 문맥 오탐이 크다는 문제가 확인되었고, 반대로 backend 모델 기반 분석은 정확도는 높지만 실시간 반영에 지연이 발생할 수 있다는 제약이 있었다.
따라서 최종 판정은 backend 모델의 `/analyze_batch` 응답으로 유지하되, extension에서는 visible container 우선 수집, exact span 마스킹, stale response guard, cache 분리를 적용하여 실제 사용 환경에서의 반응 속도와 안정성을 개선했다.

### 제약 사항

Chrome Extension은 웹 페이지의 DOM 구조에 의존하기 때문에 Google 검색 결과, AI Overview, YouTube 댓글처럼 동적으로 변경되는 영역에서 후보 누락과 지연이 발생할 수 있었다.
또한 native input과 textarea는 일반 DOM text node처럼 부분 span을 삽입할 수 없어 검색창 마스킹은 별도의 fixed-token 방식으로 분리했다.
이러한 제약 때문에 전체 요소를 가리는 방식은 사용하지 않고, backend가 반환한 evidence span에만 마스킹을 적용하는 정책을 유지했다.

### 품질 관리

오탐과 미탐은 실제 화면 캡처와 함께 regression case로 관리했다.
예를 들어 `카필 시발(Kapil Sibal)`처럼 고유명사 문맥은 safe로 유지하고, `시발 뭐하는 거야`처럼 직접 공격 문맥은 offensive로 판정되도록 테스트했다.
또한 GitHub PR과 Linear 코멘트를 통해 어떤 문제가 어떤 수정으로 이어졌는지 추적 가능하게 관리했다.

## 7. 지금부터 지켜야 할 운영 규칙

1. 새로운 문제를 발견하면 먼저 Linear에 “증거 중심”으로 남깁니다.
2. 코드 수정 PR은 반드시 “문제와 근거”, “제약”, “검증”을 포함합니다.
3. 보고서에 쓸 수 있는 개선은 `docs/engineering-history.md`에 한 줄로 정리합니다.
4. 단순히 “AI가 수정함”이라고 쓰지 않고 “어떤 제약 때문에 어떤 구조로 개선했는지”를 씁니다.
5. 입력창 `***` UX처럼 안정화된 부분은 별도 이슈 없이 다시 건드리지 않습니다.
