# Pipeline Evaluation

이 디렉터리는 단순히 “API와 ML 중 무엇이 더 좋은가”를 비교하는 곳이 아니라, 청마루의 판단 파이프라인이 어떤 조건에서 강하고 어떤 조건에서 실패하는지 기록하는 평가 기준 위치입니다.
교수님 피드백 대응 관점에서는 모델 자체보다 **문맥, 정규화, evidence span, 플랫폼 수집, 실시간 지연**을 함께 비교해야 차별점이 분명해집니다.

## 책임 범위
- 데이터셋 및 실제 화면 기반 회귀 사례 관리
- keyword-only, ML-only, ML+normalization, ML+safe-context, full pipeline 비교
- 정확도, 재현율, F1, 오탐, 미탐, 지연시간 기록
- evidence span 정확도와 전체 요소 과차단 여부 기록
- Chrome Extension과 Android 접근성 수집에서 발생하는 플랫폼별 실패 사례 기록

## 실행 방법

backend 서버를 먼저 실행한 뒤, 이 디렉터리의 회귀 케이스를 `/analyze_batch`에 보내 결과를 확인합니다.

```bash
cd backend/api
../../.venv/bin/python -m uvicorn app:app --host 127.0.0.1 --port 8000
```

다른 터미널에서 실행합니다.

```bash
python3 evaluation/api-vs-ml/run_pipeline_eval.py --backend http://127.0.0.1:8000 --sensitivity 60
```

민감도별 정책 동작과 cold/warm 지연을 같이 보려면 다음처럼 실행합니다.

```bash
python3 evaluation/api-vs-ml/run_pipeline_eval.py --backend http://127.0.0.1:8000 --sensitivities 0,20,60,100 --repeat 3
```

`sensitivity=0`은 제품 설정상 “필터 비활성”으로 해석합니다. 따라서 이 평가에서는 유해 케이스도 노출되는 것이 정상 동작이며, 민감도 0에서 마스킹이 남아 있으면 extension 캐시/원복 경로 문제로 봅니다.

보고서나 자동 비교에 쓰려면 JSON으로 출력합니다.

```bash
python3 evaluation/api-vs-ml/run_pipeline_eval.py --json
```

이 스크립트는 backend 내부 함수를 직접 import하지 않고 public `/analyze_batch` 계약만 사용합니다.
따라서 Chrome Extension, Android App과 같은 실제 클라이언트 관점에서 backend가 어떻게 응답하는지 확인할 수 있습니다.

## 비교 대상

| 비교 단위 | 목적 | 확인할 한계 |
| --- | --- | --- |
| Keyword-only | 단순 금칙어 기준 baseline | 사전/고유명사/인용 문맥 오탐 |
| ML-only | 모델 분류 성능 기준 | 우회 표현, 짧은 문장, span 부재 |
| ML + normalization | qwerty, romanized, 초성, 변형 대응 | 정상 영문 UI 단어 오탐 |
| ML + safe-context | 사전/위키/고유명사/설명 문맥 보정 | 직접 공격 문맥까지 과하게 허용할 위험 |
| Full pipeline | 실제 서비스 기준 | latency, DOM 수집, UI 적용, cache race |

## 평가 케이스 분류

| 분류 | 예시 | 기대 결과 |
| --- | --- | --- |
| 직접 공격 | `시발 뭐하는 거야`, `병신아 꺼져`, `개새끼 꺼져` | offensive + exact span |
| 설명/사전 문맥 | `시발 - 위키낱말사전`, `한국어의 대표적인 욕설이다` | 정책에 따라 safe 또는 warning, 전체 블랭크 금지 |
| 고유명사/역사 문맥 | `카필 시발(Kapil Sibal)`, `국제차량제작 시발` | safe |
| 우회 표현 | `ssibal`, `tlqkf`, `qudtls`, 초성 표현 | offensive 여부와 span 확인 |
| 일반 UI/기술 용어 | `scripts`, `README`, `warp theme`, `abstract factory` | safe |
| 플랫폼 수집 실패 | Google AI Overview, YouTube 댓글, Android 접근성 트리 | 누락/지연/오탐 원인 기록 |

현재 기본 케이스는 `cases.jsonl`에 기록합니다.
이 파일은 public `/analyze_batch` 평가뿐 아니라 `backend.tests.test_pipeline_regression`에서도 그대로 사용합니다.
따라서 새 회귀 사례를 추가할 때는 테스트 코드에 별도 하드코딩하지 말고 `cases.jsonl`만 확장하는 것을 기본 원칙으로 합니다.
새 케이스는 아래 원칙으로 추가합니다.

- `expected_offensive=false`: 정상 문맥이므로 가리면 오탐입니다.
- `expected_offensive=true`: 유해 문맥이므로 `evidence_spans`에 기대 span이 포함되어야 합니다.
- `notes`: 보고서에 옮길 수 있도록 왜 이 케이스가 중요한지 한 줄로 적습니다.

## 기록해야 할 지표

- `classification`: accuracy, precision, recall, F1
- `false_positive`: 정상 문맥이 가려진 비율과 대표 사례
- `false_negative`: 유해 문맥이 노출된 비율과 대표 사례
- `span_quality`: evidence span이 실제 유해 구간과 얼마나 맞는지
- `latency`: first mask latency, backend round-trip, reconcile latency
- `platform_reliability`: Chrome DOM 후보 수집 성공률, Android 접근성 추출 성공률
- `uncertainty`: 모델/정책이 확신하지 못해 경고 또는 보류해야 하는 비율

## 보고서 작성 관점

평가 결과는 “모델 A가 모델 B보다 좋다”로 끝내지 않습니다.
아래 질문에 답할 수 있어야 합니다.

1. 단순 키워드 차단은 어떤 문맥에서 실패했는가?
2. 모델만 사용했을 때 어떤 우회 표현이나 짧은 문장에서 실패했는가?
3. safe-context를 넣으면 어떤 오탐이 줄고 어떤 미탐 위험이 생기는가?
4. evidence span을 사용하면 전체 요소 마스킹 문제를 얼마나 줄일 수 있는가?
5. Chrome Extension과 Android 앱에서 같은 backend를 쓰더라도 플랫폼 수집 제약이 결과에 어떤 영향을 주는가?
