"""
[0단계] Android 앱 입력 필터
- Accessibility Service로 수집된 JSON에서 실제 댓글만 추출
- UI 요소, 뱃지, 날짜, 멘션 단독 등 노이즈 제거
- Chrome Extension 입력은 이 단계를 거치지 않음
"""
import re
from typing import Optional


# Android 수집 데이터에서 반복적으로 나타나는 UI 텍스트 (완전일치)
_UI_EXACT = frozenset([
    "작성자",
    "팔로우",
    "더보기",
    "답글",
    "좋아요",
    "싫어요",
    "공유",
    "신고",
])

# UI 패턴 정규식
_UI_PATTERNS = [
    re.compile(r"^‎?댓글\s+[\d,]+개$"),       # "댓글 1,042개"
    re.compile(r"^·\s*\d{2}-\d{2}$"),          # "· 03-02"
    re.compile(r"^@\S+$"),                      # "@username" 단독
    re.compile(r"^[\d,]+\s*개$"),               # "1,042개"
]

# 유효한 댓글 최소 길이 (공백·이모지 제거 후)
_MIN_CHARS = 2


def is_valid_comment(text: str) -> bool:
    """단일 commentText가 실제 댓글인지 판별.

    Returns:
        True  → 유효한 댓글, 파이프라인으로 전달
        False → UI 요소 또는 노이즈, 버림
    """
    if not text or not text.strip():
        return False

    stripped = text.strip()

    # 완전일치 UI 텍스트
    if stripped in _UI_EXACT:
        return False

    # 패턴 기반 UI 요소
    for pattern in _UI_PATTERNS:
        if pattern.match(stripped):
            return False

    # 말줄임표로 잘린 텍스트 (내용 불완전)
    if stripped.endswith("…") or stripped.endswith("..."):
        return False

    # 유효 문자 수 체크 (이모지·공백 제외)
    effective = re.sub(r"[\s\U00010000-\U0010FFFF]", "", stripped)  # 공백·이모지 제거
    effective = re.sub(r"[^\w가-힣a-zA-Z0-9]", "", effective)
    if len(effective) < _MIN_CHARS:
        return False

    return True


def _is_comment_bounds(bounds: dict) -> bool:
    """boundsInScreen으로 댓글 영역 여부 판별.

    댓글:     가로로 매우 넓고 세로로 얇음  (예: width=172, height=36)
    앱 아이콘: 작은 정사각형 또는 세로가 긴 셀 (예: width=205, height=318 or 124)

    조건: width < 250 이고 height > width * 0.4 이면 아이콘/버튼으로 간주.
    """
    if not bounds:
        return True  # bounds 없으면 통과 (Chrome Extension 등)
    width = bounds.get("right", 0) - bounds.get("left", 0)
    height = bounds.get("bottom", 0) - bounds.get("top", 0)
    if width < 250 and height > width * 0.4:
        return False
    return True


def filter_android_json(raw: dict) -> list[dict]:
    """Android 수집 JSON에서 유효한 댓글만 추출.

    Args:
        raw: {"comments": [{"commentText": str, "author_id": str, "boundsInScreen": {...}}, ...], "timestamp": int}

    Returns:
        [{"commentText": str, "author_id": str | None}, ...]
        유효한 댓글만, boundsInScreen 제거
    """
    result = []
    for item in raw.get("comments", []):
        text = item.get("commentText", "")
        bounds = item.get("boundsInScreen", {})
        if is_valid_comment(text) and _is_comment_bounds(bounds):
            result.append({
                "commentText": text.strip(),
                "author_id": item.get("author_id"),
                "boundsInScreen": bounds,   # 앱이 화면에 재배치할 때 사용
            })
    return result


def extract_texts(raw: dict) -> list[str]:
    """Android JSON에서 유효한 commentText 문자열 목록만 반환.
    파이프라인 직접 연결용.
    """
    return [c["commentText"] for c in filter_android_json(raw)]
