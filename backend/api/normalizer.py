"""
[1단계] 텍스트 정규화
- 영타→한글 변환 (inko)
- 반복 문자 축약
- 특수문자/숫자 끼워넣기 제거
- 초성 분리 처리
"""
import re
import unicodedata

try:
    from inko import Inko
    _inko = Inko()
except ImportError:
    _inko = None

ASCII_PROFANITY_MARKERS = re.compile(
    r"\b(?:"
    r"s{1,2}[\W_]*(?:h[\W_]*)?i[\W_]*b[\W_]*a[\W_]*l|tlqkf|"
    r"q[\W_]*u[\W_]*d[\W_]*t[\W_]*l[\W_]*s|qudtkf|"
    r"by[eou]+ng[\W_]*s?in|gae[\W_]*s(?:ae|e|a)[\W_]*k{1,2}i|rotori|"
    r"jiral|wlfkf|jonna|whssk|michin|alcls|k{1,2}eoj(?:ye)?o|rjwu|"
    r"f[\W_]*u[\W_]*c[\W_]*k|s[\W_]*h[\W_]*i[\W_]*t|"
    r"b[\W_]*i[\W_]*t[\W_]*c[\W_]*h|bastard|asshole|"
    r"dick|pussy|slut|whore|cunt|prick|twat|wanker|mother[\W_]*fucker|douchebag|"
    r"puta|puto|mierda|joder|cabron|cabr[oó]n|pendejo|gilipollas|co[nñ]o|chingad[ao]|maric[oó]n|"
    r"putain|merde|connard|salope|encul[eé]|ta[\W_]+gueule|nique[\W_]+ta[\W_]+m[eè]re|"
    r"schei(?:ss|ß)e|arschloch|wichser|fotze|"
    r"porra|caralho|viado|"
    r"orospu|siktir|"
    r"nigg(?:er|a)|faggot|retard"
    r")\b",
    re.IGNORECASE,
)
NON_ASCII_PROFANITY_MARKERS = re.compile(
    r"(?:"
    r"くそ|クソ|馬鹿|バカ|死ね|"
    r"操你妈|草你妈|傻逼|他妈的|去死|"
    r"бля(?:дь|ть)?|сука|хуй|пизд[аеуы]?|еба(?:ть|н[а-я]*)|мудак|долбо[её]б|"
    r"كسمك|كس امك|ابن الكلب"
    r")",
    re.IGNORECASE,
)


def normalize(text: str) -> str:
    """텍스트 정규화 파이프라인. 원문을 정규화된 텍스트로 변환."""
    result = text

    # 1) 영타 → 한글 변환
    result = convert_engtypo(result)

    # 2) 유니코드 정규화
    result = unicodedata.normalize("NFC", result)

    # 3) 특수문자 끼워넣기 제거 (한글 사이의 ., !, *, 숫자 등)
    result = remove_inserted_chars(result)

    # 4) 반복 문자 축약 ("씨이이이발" → "씨발", "ㅋㅋㅋㅋ" → "ㅋㅋ")
    result = collapse_repeats(result)

    # 5) 공백 정리
    result = re.sub(r"\s+", " ", result).strip()

    return result


def convert_engtypo(text: str) -> str:
    """영문 키보드로 입력된 한글을 변환"""
    if _inko is None:
        return text
    if ASCII_PROFANITY_MARKERS.search(text or "") or NON_ASCII_PROFANITY_MARKERS.search(text or ""):
        return text
    converted = _inko.en2ko(text)
    return converted


def remove_inserted_chars(text: str) -> str:
    """한글 글자 사이에 끼워넣은 특수문자/숫자를 제거
    예: 병.신 → 병신, 시1발 → 시발, 개★새끼 → 개새끼
    """
    # 한글 범위: 가-힣, 자음: ㄱ-ㅎ, 모음: ㅏ-ㅣ
    hangul = r"[가-힣ㄱ-ㅎㅏ-ㅣ]"
    # 한글 사이의 비한글/비공백 단일 문자 제거
    result = re.sub(
        f"({hangul})[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z\\s]({hangul})",
        r"\1\2",
        text
    )
    # 두 번 적용 (연속된 끼워넣기: 병..신)
    result = re.sub(
        f"({hangul})[^가-힣ㄱ-ㅎㅏ-ㅣa-zA-Z\\s]({hangul})",
        r"\1\2",
        result
    )
    return result


def collapse_repeats(text: str) -> str:
    """반복 문자 축약
    - 동일 문자 3회 이상 → 2회로 (ㅋㅋㅋㅋ → ㅋㅋ)
    - 모음 늘리기 축약 (씨이이발 → 씨발)
    """
    # 동일 문자 3회 이상 반복 → 1회로 (욕설 매칭을 위해)
    result = re.sub(r"(.)\1{2,}", r"\1", text)

    # 한글 모음 늘리기 제거: 씨이이발 → 씨발
    # "이", "으", "아" 등이 반복 삽입된 경우
    vowel_fillers = r"[이으아어우오]"
    result = re.sub(f"({vowel_fillers})\\1+", r"\1", result)

    return result
