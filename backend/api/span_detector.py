"""
[2단계] 욕설 span 추출
- 정규화된 텍스트에서 사전/정규식 기반 매칭
- 원문 기준 start/end 위치 반환
- 겹치는 span 병합
"""
from profanity_dict import COMPILED_PATTERNS, CHOSUNG_PROFANITY


def detect_spans(original_text: str, normalized_text: str) -> list[dict]:
    """원문과 정규화된 텍스트 모두에서 욕설 span을 추출.

    Returns:
        [{"start": int, "end": int, "text": str, "canonical": str, "label": str}, ...]
    """
    spans = []

    # 1) 정규식 패턴 매칭 (원문 기준)
    for pattern, canonical, category in COMPILED_PATTERNS:
        for match in pattern.finditer(original_text):
            spans.append({
                "start": match.start(),
                "end": match.end(),
                "text": match.group(),
                "canonical": canonical,
                "label": category,
            })

    # 2) 정규화된 텍스트에서도 매칭 (원문에서 못 잡은 변형 포착)
    if normalized_text != original_text:
        for pattern, canonical, category in COMPILED_PATTERNS:
            for match in pattern.finditer(normalized_text):
                # 정규화된 텍스트에서 찾은 경우, 원문 위치를 역매핑
                mapped = _map_to_original(
                    original_text, normalized_text,
                    match.start(), match.end()
                )
                if mapped and not _overlaps(spans, mapped[0], mapped[1]):
                    spans.append({
                        "start": mapped[0],
                        "end": mapped[1],
                        "text": original_text[mapped[0]:mapped[1]],
                        "canonical": canonical,
                        "label": category,
                    })

    # 3) 초성 패턴 매칭 (원문에서)
    for chosung, (canonical, category) in CHOSUNG_PROFANITY.items():
        idx = 0
        while idx < len(original_text):
            pos = original_text.find(chosung, idx)
            if pos == -1:
                break
            end = pos + len(chosung)
            if not _overlaps(spans, pos, end):
                spans.append({
                    "start": pos,
                    "end": end,
                    "text": chosung,
                    "canonical": canonical,
                    "label": category,
                })
            idx = pos + 1

    # 정렬 및 병합
    spans = _merge_overlapping(spans)
    return spans


def _map_to_original(original: str, normalized: str, norm_start: int, norm_end: int):
    """정규화 텍스트의 위치를 원문 위치로 대략적 역매핑.
    단순 비율 기반 + 주변 탐색으로 근사치를 반환.
    """
    if len(normalized) == 0:
        return None

    ratio = len(original) / len(normalized)
    est_start = max(0, int(norm_start * ratio) - 2)
    est_end = min(len(original), int(norm_end * ratio) + 2)

    # 추출된 canonical 텍스트가 원문에서도 찾아지는지 확인
    norm_text = normalized[norm_start:norm_end]
    # 원문 주변에서 직접 매칭 시도
    search_start = max(0, est_start - 5)
    search_end = min(len(original), est_end + 5)
    search_region = original[search_start:search_end]

    pos = search_region.find(norm_text)
    if pos != -1:
        return (search_start + pos, search_start + pos + len(norm_text))

    # 못 찾으면 추정 범위 반환
    return (est_start, est_end)


def _overlaps(spans: list, start: int, end: int) -> bool:
    """기존 span 목록과 겹치는지 확인"""
    for s in spans:
        if start < s["end"] and end > s["start"]:
            return True
    return False


def _merge_overlapping(spans: list) -> list:
    """겹치는 span 병합"""
    if not spans:
        return []

    spans.sort(key=lambda s: (s["start"], -s["end"]))
    merged = [spans[0]]

    for s in spans[1:]:
        prev = merged[-1]
        if s["start"] < prev["end"]:
            # 겹침 → 더 넓은 범위를 유지
            if s["end"] > prev["end"]:
                prev["end"] = s["end"]
                prev["text"] = s["text"] if len(s["text"]) > len(prev["text"]) else prev["text"]
        else:
            merged.append(s)

    return merged
