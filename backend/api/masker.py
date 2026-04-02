"""
[4단계] 마스킹 + 결과 조합
- span 위치 기준으로 원문 마스킹
- 분류 결과 + span 결과를 하나의 JSON으로 통합
"""


def mask_text(text: str, spans: list[dict], mask_char: str = "*") -> str:
    """span 위치 기준으로 원문 텍스트 마스킹.

    Args:
        text: 원문 텍스트
        spans: [{"start": int, "end": int, ...}, ...]
        mask_char: 마스킹 문자 (기본 '*')

    Returns:
        마스킹된 텍스트
    """
    if not spans:
        return text

    chars = list(text)
    for span in spans:
        start = span["start"]
        end = span["end"]
        length = end - start
        for i in range(start, min(end, len(chars))):
            chars[i] = mask_char

    return "".join(chars)


def combine_result(
    original_text: str,
    spans: list[dict],
    classification: dict,
    masked_text: str,
) -> dict:
    """span 추출 결과와 분류 결과를 하나의 JSON으로 통합.

    Returns:
        {
            "text": str,
            "is_profane": bool,
            "is_toxic": bool,
            "is_hate": bool,
            "scores": {...},
            "spans": [...],
            "terms": [...],
            "masked_text": str,
        }
    """
    # span에서 발견되었으면 분류 모델 결과와 OR 처리
    has_span_profanity = any(s["label"] == "profanity" for s in spans)
    has_span_sexual = any(s["label"] == "sexual" for s in spans)
    has_span_slur = any(s["label"] == "slur" for s in spans)

    return {
        "text": original_text,
        "is_profane": classification.get("is_profane", False) or has_span_profanity or has_span_sexual,
        "is_toxic": classification.get("is_toxic", False) or has_span_profanity or has_span_sexual,
        "is_hate": classification.get("is_hate", False) or has_span_slur,
        "scores": classification.get("scores", {}),
        "spans": spans,
        "terms": list(dict.fromkeys(s["canonical"] for s in spans)),  # 중복 제거, 순서 유지
        "masked_text": masked_text,
    }
