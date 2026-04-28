"""
메인 파이프라인: 텍스트 → 정규화 → 분류 → Span 추출 → 결과 JSON

아키텍처:
  1) TextClassifier  — 문장 분류 (XLM-RoBERTa-base, 비속어/공격/혐오)
  2) SpanDetector    — 욕설 span 추출 (XLM-RoBERTa-large + CRF)
  3) 분류기 주도     — 분류기가 판정, span은 근거 제공 (판정을 뒤집지 않음)

플랫폼별 처리:
  Chrome Extension → analyze() / analyze_batch()       텍스트 직접 입력
  Android App      → analyze_android_batch()           JSON + boundsInScreen 입력/출력
"""
import os
import re
from difflib import SequenceMatcher

from classifier import TextClassifier
from input_filter import filter_android_json
from normalizer import normalize
from profanity_dict import COMPILED_PATTERNS, WHITELIST
from span_detector import SpanDetector


DEFAULT_EXTENSION_SENSITIVITY = 60
RELAXED_DICTIONARY_SAFE_SENSITIVITY = 35
STRICT_DICTIONARY_BLOCK_SENSITIVITY = 55
SENSITIVITY_THRESHOLD_STEP = 0.003
MIN_SENSITIVITY_ADJUSTED_THRESHOLD = 0.58
MAX_SENSITIVITY_ADJUSTED_THRESHOLD = 0.9

SAFE_CONTEXT_EXACT = frozenset(
    {
        "국제차량제작 시발",
    }
)

SAFE_SOURCE_MARKERS = (
    "위키낱말사전",
    "위키백과",
    "나무위키",
    "wikipedia",
    "wiktionary",
)

SAFE_DEFINITION_MARKERS = (
    "일컫는말",
    "일컫는 말",
    "뜻은",
    "뜻으로",
    "의미는",
    "의미를",
    "의역하면",
    "직역하면",
    "표제어",
    "낱말",
    "사전",
)

EXPLICIT_SEXUAL_DEFINITION_MARKERS = (
    "성기",
    "생식기",
    "음경",
    "음부",
    "자지",
    "보지",
    "질",
)

SAFE_ENTITY_MARKERS = (
    "정치인",
    "변호사",
    "자동차",
    "주식회사",
    "회사",
    "기업",
    "브랜드",
    "인물",
    "배우",
    "가수",
)

SAFE_AMBIGUOUS_CONTEXT_TERMS = (
    "시발",
    "kapil sibal",
    "국제차량제작 시발",
)

SAFE_TRANSLITERATION_PATTERN = re.compile(r"\([A-Za-z][A-Za-z .'\-]{2,}\)")
SAFE_KAPIL_SIBAL_PATTERN = re.compile(r"\(([A-Za-z .'\-]*sibal[A-Za-z .'\-]*)\)", re.IGNORECASE)
SAFE_KAPIL_SIBAL_PROPER_NOUN_PATTERN = re.compile(
    r"^kapil\s+sibal(?:\s*[-|]\s*(?:wikipedia|wiktionary|profile|official))?$",
    re.IGNORECASE,
)

SAFE_BROWSER_UI_LABELS = frozenset(
    {
        ".github",
        ".gitignore",
        "actions",
        "activity",
        "agents",
        "android",
        "backend",
        "code",
        "contributors",
        "docs",
        "fork",
        "insights",
        "issues",
        "packages",
        "projects",
        "public",
        "pull requests",
        "readme",
        "readme.md",
        "scripts",
        "security & quality",
        "security and quality",
        "settings",
        "shared",
        "star",
        "watch",
        "wiki",
    }
)

UI_ASCII_TEXT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 .&/_\-]{1,63}$")
ASCII_QUERY_TEXT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9 .&/_:+\-]{1,95}$")
SAFE_TECHNICAL_CONTEXT_TERMS = (
    "abstract factory",
    "factory method",
    "factory pattern",
    "strategy pattern",
)
SAFE_TECHNICAL_CONTEXT_MARKERS = (
    "문제",
    "정답",
    "설명",
    "패턴",
    "디자인 패턴",
    "정보처리기사",
)
ASCII_URL_OR_PATH_PATTERN = re.compile(
    r"(https?://|www\.|[A-Za-z0-9_-]+\.(com|dev|org|io|net|me|app|ai|wiki)\b| › |/|>)",
    re.IGNORECASE,
)
ASCII_PROFANITY_MARKERS = (
    "ssibal",
    "sibal",
    "tlqkf",
    "qudtls",
    "byungsin",
    "gaesaekki",
    "gaesaek",
    "jiral",
    "jonna",
    "nigaumma",
    "negeumma",
    "fuck",
    "fucking",
    "shit",
    "bitch",
    "asshole",
    "bastard",
    "motherfucker",
    "dick",
    "pussy",
    "slut",
    "whore",
    "nigger",
)

ASCII_DICTIONARY_PATTERN = re.compile(
    r"(?<![A-Za-z])("
    r"mother[\W_]*fucker|"
    r"fuck(?:ing|er|ed)?|"
    r"ass[\W_]*hole|"
    r"bastard(?:s)?|"
    r"bitch(?:es)?|"
    r"shit(?:ty|head|s)?|"
    r"ssibal|sibal|tlqkf|qudtls|"
    r"byungsin|gaesaekki|gaesaek|jiral|jonna|nigaumma|negeumma"
    r")(?![A-Za-z])",
    re.IGNORECASE,
)

CALIBRATED_SCORE_THRESHOLDS = {
    "profanity": 0.72,
    "toxicity": 0.72,
    "hate": 0.68,
}


def _build_norm_to_orig_map(original: str, normalized: str) -> list[int]:
    """정규화 텍스트의 각 문자 인덱스 → 원문 인덱스 매핑 배열 생성."""
    sm = SequenceMatcher(None, original, normalized, autojunk=False)
    n2o = [-1] * len(normalized)

    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            for k in range(j2 - j1):
                n2o[j1 + k] = i1 + k
        elif tag == "replace":
            orig_len = i2 - i1
            norm_len = j2 - j1
            for k in range(norm_len):
                n2o[j1 + k] = i1 + min(k * orig_len // norm_len, orig_len - 1)

    last = 0
    for index, value in enumerate(n2o):
        if value >= 0:
            last = value
        else:
            n2o[index] = last

    return n2o


def _normalize_space(text: str) -> str:
    return " ".join(str(text or "").split()).strip()


def _normalize_label(text: str) -> str:
    return _normalize_space(text).casefold()


def _is_kapil_sibal_proper_noun_context(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact or len(compact) > 80:
        return False
    return bool(SAFE_KAPIL_SIBAL_PROPER_NOUN_PATTERN.fullmatch(compact))


def _normalize_sensitivity(value: int | float | None) -> int:
    try:
        number = int(round(float(value)))
    except (TypeError, ValueError):
        return DEFAULT_EXTENSION_SENSITIVITY
    return max(0, min(100, number))


def _is_browser_ui_safe_label(text: str) -> bool:
    return _normalize_label(text) in SAFE_BROWSER_UI_LABELS


def _looks_like_ascii_ui_text(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact or len(compact) > 64:
        return False
    if re.search(r"[가-힣ㄱ-ㅎㅏ-ㅣ]", compact):
        return False
    if any(char in compact for char in ("!", "?", ":", ";", ",")):
        return False
    if not UI_ASCII_TEXT_PATTERN.fullmatch(compact):
        return False

    words = [token for token in re.split(r"[\s/_.-]+", compact) if token]
    return 0 < len(words) <= 5


def _contains_korean_text(text: str) -> bool:
    return bool(re.search(r"[가-힣ㄱ-ㅎㅏ-ㅣ]", str(text or "")))


def _contains_ascii_profanity_marker(text: str) -> bool:
    lower = _normalize_space(text).casefold()
    return any(marker in lower for marker in ASCII_PROFANITY_MARKERS)


def _looks_like_ascii_query_text(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact or len(compact) > 96:
        return False
    if _contains_korean_text(compact):
        return False
    if _contains_ascii_profanity_marker(compact):
        return False
    if any(char in compact for char in ("!", "?")):
        return False
    if not ASCII_QUERY_TEXT_PATTERN.fullmatch(compact):
        return False

    words = [token for token in re.split(r"\s+", compact) if token]
    return 0 < len(words) <= 4


def _looks_like_ascii_browser_result_text(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact or len(compact) > 240:
        return False
    if _contains_korean_text(compact):
        return False
    if _contains_ascii_profanity_marker(compact):
        return False
    return bool(ASCII_URL_OR_PATH_PATTERN.search(compact))


def _has_dictionary_signal(text: str) -> bool:
    normalized = normalize(text)
    if not normalized:
        return False

    for pattern, canonical, _category in COMPILED_PATTERNS:
        if pattern.search(normalized):
            if canonical in WHITELIST or normalized in WHITELIST:
                continue
            return True

    return False


def _count_safe_ambiguous_term_occurrences(text: str) -> int:
    compact = _normalize_space(text)
    return len(re.findall(r"시발", compact))


def _contains_explicit_sexual_definition(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact:
        return False
    if not any(marker in compact for marker in EXPLICIT_SEXUAL_DEFINITION_MARKERS):
        return False
    return any(marker in compact for marker in SAFE_DEFINITION_MARKERS)


def _is_safe_transliteration_usage(text: str) -> bool:
    compact = _normalize_space(text)
    if not compact:
        return False
    if len(compact) > 120:
        return False
    if _count_safe_ambiguous_term_occurrences(compact) != 1:
        return False
    if not SAFE_TRANSLITERATION_PATTERN.search(compact):
        return False
    if not any(marker in compact for marker in SAFE_ENTITY_MARKERS):
        return False
    return True


def _should_force_safe_without_evidence(text: str, sensitivity: int) -> bool:
    compact = _normalize_space(text)
    if not compact:
        return False
    if _is_safe_transliteration_usage(compact):
        return True
    if _is_kapil_sibal_proper_noun_context(compact):
        return True
    if _contains_ascii_profanity_marker(compact):
        return False
    if _is_context_safe_usage(compact, sensitivity):
        return True
    if _looks_like_ascii_ui_text(compact):
        return True
    if _looks_like_ascii_query_text(compact):
        return True
    if _looks_like_ascii_browser_result_text(compact):
        return True
    return False


def _is_context_safe_usage(text: str, sensitivity: int) -> bool:
    compact = _normalize_space(text)
    if not compact:
        return False

    lower = compact.casefold()
    normalized_sensitivity = _normalize_sensitivity(sensitivity)
    if compact in SAFE_CONTEXT_EXACT:
        return True

    if _is_browser_ui_safe_label(compact):
        return True

    if _is_kapil_sibal_proper_noun_context(compact):
        return True

    if (
        not _contains_ascii_profanity_marker(compact)
        and any(term in lower for term in SAFE_TECHNICAL_CONTEXT_TERMS)
        and any(marker in compact for marker in SAFE_TECHNICAL_CONTEXT_MARKERS)
        and len(compact) <= 160
    ):
        return True

    contains_safe_ambiguous_term = any(term in lower for term in SAFE_AMBIGUOUS_CONTEXT_TERMS)

    if (
        contains_safe_ambiguous_term
        and any(marker in lower for marker in SAFE_SOURCE_MARKERS)
        and " - " in compact
        and normalized_sensitivity < RELAXED_DICTIONARY_SAFE_SENSITIVITY
    ):
        return True

    if (
        contains_safe_ambiguous_term
        and any(marker in compact for marker in SAFE_DEFINITION_MARKERS)
        and normalized_sensitivity < RELAXED_DICTIONARY_SAFE_SENSITIVITY
        and not _contains_explicit_sexual_definition(compact)
    ):
        if len(compact) <= 160 and "?" not in compact and "!" not in compact:
            return True

    return False


def _detect_explicit_definition_spans(original_text: str) -> list[dict]:
    compact = _normalize_space(original_text)
    if not compact or not _contains_explicit_sexual_definition(compact):
        return []

    lowered_text = str(original_text or "")
    spans: list[dict] = []
    for marker in EXPLICIT_SEXUAL_DEFINITION_MARKERS:
        start = lowered_text.find(marker)
        if start < 0:
            continue
        spans.append(
            {
                "text": lowered_text[start : start + len(marker)],
                "start": start,
                "end": start + len(marker),
                "score": 0.96,
            }
        )

    return _merge_raw_spans(spans)


def _detect_dictionary_spans(normalized_text: str) -> list[dict]:
    if not normalized_text:
        return []

    matches: list[dict] = []
    for pattern, canonical, category in COMPILED_PATTERNS:
        for match in pattern.finditer(normalized_text):
            matched_text = match.group(0).strip()
            if not matched_text:
                continue
            if matched_text in WHITELIST or canonical in WHITELIST:
                continue

            matches.append(
                {
                    "text": matched_text,
                    "canonical": canonical,
                    "category": category,
                    "start": match.start(),
                    "end": match.end(),
                    "score": 0.99,
                }
            )

    matches.sort(key=lambda item: (item["start"], item["end"]))

    merged: list[dict] = []
    for match in matches:
        previous = merged[-1] if merged else None
        if previous and match["start"] < previous["end"]:
            if (match["end"] - match["start"]) > (previous["end"] - previous["start"]):
                merged[-1] = match
            continue
        merged.append(match)

    return merged


def _detect_ascii_dictionary_spans(original_text: str) -> list[dict]:
    if not original_text:
        return []

    matches: list[dict] = []
    for match in ASCII_DICTIONARY_PATTERN.finditer(str(original_text)):
        matched_text = match.group(0).strip()
        if not matched_text:
            continue

        matches.append(
            {
                "text": matched_text,
                "start": match.start(),
                "end": match.end(),
                "score": 0.99,
            }
        )

    return _merge_raw_spans(matches)


def _merge_raw_spans(raw_spans: list[dict]) -> list[dict]:
    ordered_spans = sorted(
        raw_spans,
        key=lambda span: (
            int(span.get("start", -1)),
            int(span.get("end", -1)),
            -len(str(span.get("text", ""))),
        ),
    )

    merged: list[dict] = []
    for span in ordered_spans:
        start = int(span.get("start", -1))
        end = int(span.get("end", -1))
        if start < 0 or end <= start:
            continue

        previous = merged[-1] if merged else None
        if previous and start < int(previous.get("end", -1)):
            previous_length = int(previous.get("end", -1)) - int(previous.get("start", -1))
            current_length = end - start
            if current_length > previous_length:
                merged[-1] = span
            continue

        merged.append(span)

    return merged


def _empty_scores() -> dict[str, float]:
    return {"profanity": 0.0, "toxicity": 0.0, "hate": 0.0}


# 모델 경로: 환경변수 > 기본값(backend/ 기준)
BASE = os.environ.get("MODEL_BASE", os.path.join(os.path.dirname(__file__), ".."))


class ProfanityPipeline:
    def __init__(
        self,
        classifier_path: str = None,
        span_model_path: str = None,
        threshold: float = 0.5,
    ):
        if classifier_path is None:
            classifier_path = os.environ.get(
                "MODEL_CLASSIFIER_PATH",
                os.path.join(BASE, "models", "v2"),
            )
        if span_model_path is None:
            span_model_path = os.environ.get(
                "MODEL_SPAN_PATH",
                os.path.join(BASE, "models", "span_large_combined_crf"),
            )

        self.classifier = TextClassifier(model_path=classifier_path)
        self.span_detector = SpanDetector(model_dir=span_model_path)
        self.threshold = threshold

    def _effective_thresholds(self, sensitivity: int | None = None) -> dict[str, float]:
        base_threshold = float(self.threshold)
        normalized_sensitivity = _normalize_sensitivity(sensitivity)
        sensitivity_delta = DEFAULT_EXTENSION_SENSITIVITY - normalized_sensitivity
        return {
            category: min(
                MAX_SENSITIVITY_ADJUSTED_THRESHOLD,
                max(
                    MIN_SENSITIVITY_ADJUSTED_THRESHOLD,
                    max(base_threshold, calibrated)
                    + (sensitivity_delta * SENSITIVITY_THRESHOLD_STEP),
                ),
            )
            for category, calibrated in CALIBRATED_SCORE_THRESHOLDS.items()
        }

    def _build_classifier_flags(self, cls_result: dict, sensitivity: int | None = None) -> dict[str, bool]:
        scores = cls_result.get("scores") or {}
        thresholds = self._effective_thresholds(sensitivity)
        return {
            "is_profane": float(scores.get("profanity", 0.0)) >= thresholds["profanity"],
            "is_toxic": float(scores.get("toxicity", 0.0)) >= thresholds["toxicity"],
            "is_hate": float(scores.get("hate", 0.0)) >= thresholds["hate"],
        }

    @staticmethod
    def _has_any_flag(flags: dict) -> bool:
        return bool(flags["is_profane"] or flags["is_toxic"] or flags["is_hate"])

    def _is_safe_ambiguous_entity_span(
        self,
        original_text: str,
        span_start: int,
        span_end: int,
        span_text: str,
    ) -> bool:
        compact_span = _normalize_space(span_text)
        if compact_span != "시발":
            return False

        before = _normalize_space(original_text[max(0, span_start - 24) : span_start])
        after = _normalize_space(original_text[span_end : min(len(original_text), span_end + 40)])
        lower_original = _normalize_space(original_text).casefold()

        if "국제차량제작" in before and any(
            marker in original_text for marker in ("자동차", "주식회사", "회사")
        ):
            return True

        if (
            "카필" in before
            and SAFE_KAPIL_SIBAL_PATTERN.search(after)
            and any(marker in lower_original for marker in SAFE_ENTITY_MARKERS)
        ):
            return True

        return False

    def _should_reject_span(self, original_text: str, span_text: str) -> bool:
        compact_span = _normalize_space(span_text)
        if not compact_span:
            return True
        if (
            compact_span.casefold() == "sibal"
            and _is_kapil_sibal_proper_noun_context(original_text)
        ):
            return True
        if _contains_ascii_profanity_marker(compact_span):
            compact_original = _normalize_space(original_text)
            if (
                _is_safe_transliteration_usage(original_text)
                or (
                    SAFE_TRANSLITERATION_PATTERN.search(compact_original)
                    and any(marker in compact_original for marker in SAFE_ENTITY_MARKERS)
                )
            ):
                return True
            return False
        if re.search(r"\s", compact_span) and not _has_dictionary_signal(compact_span):
            return True
        if _is_browser_ui_safe_label(compact_span):
            return True
        if _looks_like_ascii_ui_text(original_text) and _looks_like_ascii_ui_text(compact_span):
            return True
        if not _contains_korean_text(compact_span):
            return not _contains_ascii_profanity_marker(compact_span)
        if _looks_like_ascii_browser_result_text(original_text) and not _has_dictionary_signal(compact_span):
            return True
        return False

    def _map_spans_to_original(
        self,
        original_text: str,
        normalized_text: str,
        raw_spans: list[dict],
    ) -> list[dict]:
        if not raw_spans:
            return []

        n2o = _build_norm_to_orig_map(original_text, normalized_text)
        evidence_spans = []

        for span in raw_spans:
            mapped = {
                "text": span.get("text", ""),
                "start": int(span.get("start", -1)),
                "end": int(span.get("end", -1)),
                "score": float(span.get("score", 0.0)),
            }

            norm_start = mapped["start"]
            norm_end = mapped["end"]
            if not (0 <= norm_start < len(n2o) and 0 < norm_end <= len(n2o)):
                continue

            mapped["start"] = n2o[norm_start]
            mapped["end"] = n2o[norm_end - 1] + 1
            mapped["text"] = original_text[mapped["start"] : mapped["end"]]

            if mapped["end"] <= mapped["start"]:
                continue
            if self._is_safe_ambiguous_entity_span(
                original_text,
                mapped["start"],
                mapped["end"],
                mapped["text"],
            ):
                continue
            if self._should_reject_span(original_text, mapped["text"]):
                continue

            evidence_spans.append(mapped)

        return evidence_spans

    def _filter_direct_spans(self, original_text: str, raw_spans: list[dict]) -> list[dict]:
        filtered_spans: list[dict] = []

        for span in raw_spans:
            mapped = {
                "text": str(span.get("text", "")),
                "start": int(span.get("start", -1)),
                "end": int(span.get("end", -1)),
                "score": float(span.get("score", 0.0)),
            }

            if mapped["start"] < 0 or mapped["end"] <= mapped["start"]:
                continue
            if mapped["end"] > len(original_text):
                continue
            if self._should_reject_span(original_text, mapped["text"]):
                continue

            filtered_spans.append(mapped)

        return filtered_spans

    def _build_result(
        self,
        original_text: str,
        cls_result: dict,
        evidence_spans: list[dict] | None = None,
        *,
        force_safe: bool = False,
        override_flags: dict[str, bool] | None = None,
        sensitivity: int | None = None,
    ) -> dict:
        scores = cls_result.get("scores") or _empty_scores()
        if force_safe:
            return {
                "text": original_text,
                "is_offensive": False,
                "is_profane": False,
                "is_toxic": False,
                "is_hate": False,
                "scores": scores,
                "evidence_spans": [],
            }

        effective_flags = override_flags or self._build_classifier_flags(cls_result, sensitivity)
        return {
            "text": original_text,
            "is_offensive": self._has_any_flag(effective_flags),
            "is_profane": effective_flags["is_profane"],
            "is_toxic": effective_flags["is_toxic"],
            "is_hate": effective_flags["is_hate"],
            "scores": scores,
            "evidence_spans": evidence_spans or [],
        }

    def _build_analysis_result(
        self,
        original_text: str,
        normalized_text: str,
        cls_result: dict,
        sensitivity: int,
        span_cache: dict[str, list[dict]] | None = None,
    ) -> dict:
        if _is_context_safe_usage(original_text, sensitivity):
            return self._build_result(original_text, cls_result, force_safe=True)

        normalized_dictionary_spans = _merge_raw_spans(_detect_dictionary_spans(normalized_text))
        mapped_dictionary_spans: list[dict] = []
        if normalized_dictionary_spans:
            mapped_dictionary_spans = self._map_spans_to_original(
                original_text,
                normalized_text,
                normalized_dictionary_spans,
            )
        mapped_dictionary_spans = _merge_raw_spans(
            [
                *mapped_dictionary_spans,
                *self._filter_direct_spans(
                    original_text,
                    _detect_ascii_dictionary_spans(original_text),
                ),
            ]
        )

        explicit_definition_spans = _detect_explicit_definition_spans(original_text)
        if (
            _normalize_sensitivity(sensitivity) >= STRICT_DICTIONARY_BLOCK_SENSITIVITY
            and (mapped_dictionary_spans or explicit_definition_spans)
        ):
            evidence_spans = _merge_raw_spans(
                [*mapped_dictionary_spans, *explicit_definition_spans]
            )
            effective_flags = self._build_classifier_flags(cls_result, sensitivity)
            if not self._has_any_flag(effective_flags):
                effective_flags = {
                    **effective_flags,
                    "is_profane": True,
                }
            return self._build_result(
                original_text,
                cls_result,
                evidence_spans=evidence_spans,
                override_flags=effective_flags,
            )

        if mapped_dictionary_spans:
            effective_flags = self._build_classifier_flags(cls_result, sensitivity)
            if self._has_any_flag(effective_flags):
                return self._build_result(
                    original_text,
                    cls_result,
                    evidence_spans=mapped_dictionary_spans,
                    override_flags=effective_flags,
                )

        effective_flags = self._build_classifier_flags(cls_result, sensitivity)
        if _should_force_safe_without_evidence(original_text, sensitivity):
            return self._build_result(original_text, cls_result, force_safe=True)

        if not self._has_any_flag(effective_flags):
            return self._build_result(
                original_text,
                cls_result,
                evidence_spans=[],
                sensitivity=sensitivity,
            )

        if span_cache is None:
            model_spans = self.span_detector.detect(normalized_text)
        else:
            model_spans = span_cache.get(normalized_text)
            if model_spans is None:
                model_spans = self.span_detector.detect(normalized_text)
                span_cache[normalized_text] = model_spans

        raw_spans = _merge_raw_spans(model_spans)
        evidence_spans = self._map_spans_to_original(
            original_text,
            normalized_text,
            raw_spans,
        )

        return self._build_result(
            original_text,
            cls_result,
            evidence_spans=evidence_spans,
            sensitivity=sensitivity,
        )

    def analyze(self, text: str, sensitivity: int | None = None) -> dict:
        normalized = normalize(text)
        cls_result = self.classifier.predict(normalized, threshold=self.threshold)
        return self._build_analysis_result(
            text,
            normalized,
            cls_result,
            _normalize_sensitivity(sensitivity),
        )

    def analyze_batch(self, texts: list[str], sensitivity: int | None = None) -> list[dict]:
        if not texts:
            return []

        normalized_sensitivity = _normalize_sensitivity(sensitivity)
        normalized_texts = [normalize(text) for text in texts]

        unique_normalized = []
        seen_normalized = set()
        for normalized_text in normalized_texts:
            if normalized_text in seen_normalized:
                continue
            seen_normalized.add(normalized_text)
            unique_normalized.append(normalized_text)

        classified_batch = self.classifier.predict_batch(
            unique_normalized,
            threshold=self.threshold,
        )
        classified_by_normalized = {
            normalized_text: result
            for normalized_text, result in zip(unique_normalized, classified_batch)
        }

        span_cache: dict[str, list[dict]] = {}
        results = []
        for original_text, normalized_text in zip(texts, normalized_texts):
            results.append(
                self._build_analysis_result(
                    original_text,
                    normalized_text,
                    classified_by_normalized[normalized_text],
                    normalized_sensitivity,
                    span_cache=span_cache,
                )
            )

        return results

    def analyze_android_batch(self, raw: dict) -> dict:
        total = len(raw.get("comments", []))
        valid_comments = filter_android_json(raw)

        results = []
        for item in valid_comments:
            text = item["commentText"]
            bounds = item["boundsInScreen"]
            analysis = self.analyze(text, sensitivity=DEFAULT_EXTENSION_SENSITIVITY)

            results.append(
                {
                    "original": text,
                    "boundsInScreen": bounds,
                    "is_offensive": analysis["is_offensive"],
                    "is_profane": analysis["is_profane"],
                    "is_toxic": analysis["is_toxic"],
                    "is_hate": analysis["is_hate"],
                    "scores": analysis["scores"],
                    "evidence_spans": analysis["evidence_spans"],
                }
            )

        return {
            "timestamp": raw.get("timestamp"),
            "results": results,
            "filtered_count": total - len(valid_comments),
        }
