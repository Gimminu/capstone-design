"""
[Span 추출] 학습된 SpanCRF 모델 기반 욕설 span 추출 + 후처리
- SpanCRFModel (XLM-RoBERTa-large + CRF) 추론
- 후처리: 최소 길이, 화이트리스트, 스팬 병합, 신뢰도 필터링
"""
import math
import os
import re

import torch
import torch.nn as nn
from transformers import AutoModel, AutoTokenizer

try:
    from torchcrf import CRF
    HAS_CRF = True
except ImportError:
    HAS_CRF = False

# ── 상수 ──────────────────────────────────────────────────
LABEL2ID   = {"O": 0, "B-OFF": 1, "I-OFF": 2}
ID2LABEL   = {0: "O", 1: "B-OFF", 2: "I-OFF"}
NUM_LABELS = 3
MAX_LENGTH = 128


def _get_device() -> torch.device:
    if getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


DEVICE = _get_device()

# ── 화이트리스트 (오탐 방지) ──────────────────────────────
SPAN_WHITELIST = {
    # 음식
    "떡꼬치", "떡볶이", "꼬치", "닭꼬치", "붕어빵", "호떡",
    # 동물/지명
    "시베리아", "허스키", "시베리안",
    # 일반 단어 (욕설 서브스트링과 겹치는 것들)
    "씨앗", "씨름", "시리얼", "시원", "시작", "시간",
    "병원", "병아리", "병풍",
    "새끼손가락", "새끼줄", "새끼고양이", "새끼강아지",
    "시발점", "시발역", "시발택시",
    "존버", "존맛", "존예", "존잘",
}


# ═══════════════════════════════════════════════════════════════
# SpanCRF 모델 (train_span.py에서 복사 — 학습 의존성 제거)
# ═══════════════════════════════════════════════════════════════

class SpanCRFModel(nn.Module):
    """XLM-RoBERTa + Linear projection + CRF."""
    def __init__(self, backbone: str, num_labels: int, dropout: float = 0.1):
        super().__init__()
        self.encoder    = AutoModel.from_pretrained(backbone)
        hidden          = self.encoder.config.hidden_size
        self.dropout    = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden, num_labels)
        self.crf        = CRF(num_tags=num_labels, batch_first=True)

    def forward(self, input_ids, attention_mask, labels=None):
        outputs   = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        emissions = self.classifier(self.dropout(outputs.last_hidden_state))

        if labels is not None:
            safe_labels = labels.clone()
            safe_labels[safe_labels == -100] = 0
            crf_mask = attention_mask.bool()
            loss = -self.crf(emissions, safe_labels, mask=crf_mask, reduction="mean")
            return loss, emissions
        else:
            tags = self.crf.decode(emissions.float(), mask=attention_mask.bool())
            return tags, emissions


# ═══════════════════════════════════════════════════════════════
# SpanDetector — 학습된 모델 기반 span 추출기
# ═══════════════════════════════════════════════════════════════

class SpanDetector:
    """학습된 SpanCRF 모델로 욕설 span을 추출한다."""

    def __init__(self, model_dir: str, device=DEVICE,
                 min_span_chars: int = 2,
                 confidence_threshold: float = 0.5):
        """
        Args:
            model_dir: 모델 디렉토리 (encoder/ + extra_weights.pt)
            device: torch device
            min_span_chars: 최소 span 길이 (문자 수)
            confidence_threshold: CRF emission 기반 최소 신뢰도 (0~1)
        """
        self.device = device
        self.min_span_chars = min_span_chars
        self.confidence_threshold = confidence_threshold

        enc_dir = os.path.join(model_dir, "encoder")
        self.tokenizer = AutoTokenizer.from_pretrained(enc_dir)

        self.model = SpanCRFModel(enc_dir, NUM_LABELS).to(device)
        extra = torch.load(
            os.path.join(model_dir, "extra_weights.pt"),
            map_location=device,
        )
        self.model.classifier.load_state_dict(extra["classifier"])
        self.model.crf.load_state_dict(extra["crf"])
        self.model.eval()

    def detect(self, text: str, confidence_override: float = None) -> list[dict]:
        """텍스트에서 욕설 span을 추출한다.

        Args:
            text: 정규화된 텍스트
            confidence_override: 신뢰도 임계값 오버라이드 (None이면 기본값 사용)

        Returns:
            [{"text": str, "start": int, "end": int, "score": float}, ...]
        """
        if not text or not text.strip():
            return []

        # 토크나이즈
        enc = self.tokenizer(
            text, return_tensors="pt", truncation=True,
            max_length=MAX_LENGTH, return_offsets_mapping=True,
        )
        offsets = enc.pop("offset_mapping").squeeze(0).tolist()
        input_ids = enc["input_ids"].to(self.device)
        attention_mask = enc["attention_mask"].to(self.device)

        # 추론
        with torch.no_grad():
            tags_list, emissions = self.model(input_ids, attention_mask)

        pred_tags = tags_list[0]
        emissions = emissions.squeeze(0)  # [seq_len, num_labels]

        # BIO → character spans + 토큰 인덱스 수집
        raw_spans = self._bio_to_spans(pred_tags, offsets)

        # 신뢰도 계산
        span_results = []
        for char_start, char_end, token_indices in raw_spans:
            score = self._compute_confidence(emissions, pred_tags, token_indices)
            span_results.append({
                "text": text[char_start:char_end],
                "start": char_start,
                "end": char_end,
                "score": round(score, 3),
            })

        # 후처리
        conf = confidence_override if confidence_override is not None else self.confidence_threshold
        span_results = self._postprocess(span_results, text, confidence_threshold=conf)
        return span_results

    def _bio_to_spans(self, pred_tags, offsets):
        """BIO 태그 시퀀스를 (char_start, char_end, token_indices) 리스트로 변환."""
        spans = []
        current_start = None
        current_end = None
        current_tokens = []

        for i, tag_id in enumerate(pred_tags):
            if i >= len(offsets):
                break
            char_start, char_end = offsets[i]
            if char_start == 0 and char_end == 0:
                continue

            label = ID2LABEL[tag_id]

            if label == "B-OFF":
                if current_start is not None:
                    spans.append((current_start, current_end, current_tokens))
                current_start = char_start
                current_end = char_end
                current_tokens = [i]
            elif label == "I-OFF" and current_start is not None:
                current_end = char_end
                current_tokens.append(i)
            else:
                if current_start is not None:
                    spans.append((current_start, current_end, current_tokens))
                    current_start = None
                    current_tokens = []

        if current_start is not None:
            spans.append((current_start, current_end, current_tokens))

        return spans

    def _compute_confidence(self, emissions, pred_tags, token_indices):
        """CRF emission 점수로 span 신뢰도를 계산한다.

        predicted tag의 emission에서 O tag의 emission을 뺀 margin의 평균을
        sigmoid로 변환하여 0~1 범위의 confidence를 반환.
        """
        if not token_indices:
            return 0.0

        margins = []
        for idx in token_indices:
            tag_id = pred_tags[idx]
            predicted_emission = emissions[idx, tag_id].item()
            o_emission = emissions[idx, 0].item()  # O tag = 0
            margins.append(predicted_emission - o_emission)

        avg_margin = sum(margins) / len(margins)
        confidence = 1.0 / (1.0 + math.exp(-avg_margin))
        return confidence

    def _postprocess(self, spans: list[dict], text: str,
                     confidence_threshold: float = None) -> list[dict]:
        """후처리 파이프라인: 필터링 → 병합 → 최종 검증."""
        if confidence_threshold is None:
            confidence_threshold = self.confidence_threshold

        # 1) 최소 길이 필터
        spans = [s for s in spans if len(s["text"].strip()) >= self.min_span_chars]

        # 2) 화이트리스트 필터
        spans = [s for s in spans if s["text"].strip() not in SPAN_WHITELIST]

        # 3) 화이트리스트 부분 매칭 (span이 안전 단어의 일부인 경우)
        spans = self._filter_whitelist_substring(spans, text)

        # 4) 의미 없는 텍스트(gibberish) 필터
        if not self._is_meaningful_korean(text):
            return []

        # 5) 스팬 병합 (1자 이내 간격)
        spans = self._merge_nearby(spans, text, max_gap=1)

        # 6) 신뢰도 필터
        spans = [s for s in spans if s["score"] >= confidence_threshold]

        return spans

    def _filter_whitelist_substring(self, spans, text):
        """span 텍스트가 화이트리스트 단어의 일부인 경우 제거."""
        result = []
        for s in spans:
            span_text = s["text"].strip()
            # span 주변 컨텍스트를 확인하여 안전 단어에 포함되는지 체크
            context_start = max(0, s["start"] - 3)
            context_end = min(len(text), s["end"] + 3)
            context = text[context_start:context_end]

            is_safe = False
            for safe_word in SPAN_WHITELIST:
                if safe_word in context and span_text in safe_word:
                    is_safe = True
                    break
            if not is_safe:
                result.append(s)
        return result

    def _is_meaningful_korean(self, text: str, min_ratio: float = 0.2) -> bool:
        """텍스트가 의미 있는 한국어인지 판단 (gibberish 필터)."""
        clean = text.replace(" ", "")
        if not clean:
            return False
        korean = len(re.findall(r"[가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z0-9]", clean))
        return (korean / len(clean)) >= min_ratio

    def _merge_nearby(self, spans: list[dict], text: str,
                      max_gap: int = 1) -> list[dict]:
        """인접 span 병합 (gap이 max_gap 이하이고 공백만 있는 경우)."""
        if len(spans) <= 1:
            return spans

        spans = sorted(spans, key=lambda s: s["start"])
        merged = [spans[0].copy()]

        for s in spans[1:]:
            prev = merged[-1]
            gap = s["start"] - prev["end"]
            gap_text = text[prev["end"]:s["start"]]

            if gap <= max_gap and gap_text.strip() == "":
                # 병합
                prev["end"] = s["end"]
                prev["text"] = text[prev["start"]:prev["end"]]
                prev["score"] = max(prev["score"], s["score"])
            else:
                merged.append(s.copy())

        return merged
