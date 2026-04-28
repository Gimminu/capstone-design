# Toxicity API

문맥 기반 유해성 분류 + 욕설 span 추출 API

## 아키텍처

```
입력 텍스트
    │
    ▼
[정규화] normalizer.py
    │
    ▼
[문장 분류] classifier.py (XLM-RoBERTa-base)
    │   → is_profane / is_toxic / is_hate + scores
    │
    ├── 정상 판정 → 종료 (evidence_spans = [])
    │
    └── 유해 판정 ↓
                  │
                  ▼
          [Span 추출] span_detector.py (XLM-RoBERTa-large + CRF)
                  │   → 욕설 위치 (start, end, text, score)
                  │
                  ▼
              최종 결과 반환
```

- **분류기가 주 판정자**, span은 근거 제공자 (판정을 뒤집지 않음)
- 마스킹은 프론트(Android App / Chrome Extension)가 `evidence_spans` 기반으로 처리

## 파일 구조

| 파일 | 역할 |
|------|------|
| `app.py` | FastAPI 서버, 엔드포인트 정의 |
| `pipeline.py` | 메인 파이프라인 (분류 → span 추출 오케스트레이션) |
| `classifier.py` | XLM-RoBERTa-base 문장 분류 래퍼 |
| `span_detector.py` | XLM-RoBERTa-large + CRF 기반 span 추출기 |
| `normalizer.py` | 텍스트 정규화 (영타 변환, 특수문자, 반복 축약) |
| `input_filter.py` | Android 입력 전처리 (UI 배지, 날짜 등 제거) |
| `profanity_dict.py` | 욕설 사전/패턴 (보조 용도) |
| `llm_agent.py` | LangGraph 기반 LLM Agent |
| `agent_service.py` | 분석 파이프라인 + Agent 연결 |

## 설치 및 실행

```bash
# 1. 의존성 설치
cd backend
pip install -r requirements.txt

# 2. 모델 다운로드
python scripts/download_models.py

# 3. 서버 실행
cd api
uvicorn app:app --host 0.0.0.0 --port 8000
```

## API 엔드포인트

### `GET /health`
```json
{"status": "ok"}
```

### `POST /analyze` — 단일 텍스트 분석
요청:
```json
{"text": "분석할 텍스트"}
```
응답:
```json
{
  "original": "분석할 텍스트",
  "is_offensive": false,
  "is_profane": false,
  "is_toxic": false,
  "is_hate": false,
  "scores": {"profanity": 0.01, "toxicity": 0.02, "hate": 0.01},
  "evidence_spans": []
}
```

### `POST /analyze_batch` — 배치 분석
요청:
```json
{"texts": ["텍스트1", "텍스트2"]}
```
응답:
```json
{"results": [/* AnalyzeResponse 배열 */]}
```

### `POST /analyze_android` — Android 전용
요청:
```json
{
  "timestamp": 1712500000,
  "comments": [
    {
      "commentText": "댓글 내용",
      "boundsInScreen": {"top": 100, "bottom": 200, "left": 0, "right": 1080}
    }
  ]
}
```

### `POST /agent/analyze` — LangGraph 기반 설명
요청:
```json
{"text": "메갈년들 다 꺼져라"}
```
응답:
```json
{
  "analysis": {
    "original": "메갈년들 다 꺼져라",
    "is_offensive": true,
    "is_profane": true,
    "is_toxic": true,
    "is_hate": true,
    "scores": {"profanity": 0.96, "toxicity": 0.99, "hate": 0.98},
    "evidence_spans": [{"text": "메갈년", "start": 0, "end": 3, "score": 0.95}]
  },
  "agent": {
    "mode": "langgraph",
    "model": "gpt-4o-mini",
    "reason": null,
    "response": "1. 판단 ... 2. 근거 ... 3. 권고 ..."
  }
}
```

`OPENAI_API_KEY`가 없거나 LangGraph 의존성이 없으면 `fallback` 모드로 동작합니다.
응답:
```json
{
  "timestamp": 1712500000,
  "filtered_count": 0,
  "results": [
    {
      "original": "댓글 내용",
      "boundsInScreen": {"top": 100, "bottom": 200, "left": 0, "right": 1080},
      "is_offensive": false,
      "is_profane": false,
      "is_toxic": false,
      "is_hate": false,
      "scores": {"profanity": 0.01, "toxicity": 0.02, "hate": 0.01},
      "evidence_spans": []
    }
  ]
}
```

## 모델 정보

| 모델 | 구조 | 용도 |
|------|------|------|
| 분류 모델 (v2) | XLM-RoBERTa-base (278M params) | 문장 수준 유해성 판정 |
| Span 모델 | XLM-RoBERTa-large + CRF (560M params) | 욕설 단어 위치 추출 |

## 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `MODEL_BASE` | `backend/` | 모델 루트 디렉토리 |
| `MODEL_CLASSIFIER_PATH` | `{MODEL_BASE}/models/v2` | 분류 모델 경로 |
| `MODEL_SPAN_PATH` | `{MODEL_BASE}/models/span_large_combined_crf` | Span 모델 경로 |
| `OPENAI_API_KEY` | 없음 | LangGraph Agent 실행용 API 키 |
| `OPENAI_MODEL` | `gpt-4o-mini` | Agent에 사용할 OpenAI 모델명 |

## 요구사항

- Python 3.10+
- CUDA 지원 GPU 권장 (CPU도 가능하나 느림)
- 최소 VRAM: ~4GB (두 모델 동시 로드)
