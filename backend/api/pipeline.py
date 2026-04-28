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
import time
from difflib import SequenceMatcher

from normalizer import normalize
from classifier import TextClassifier
from span_detector import SpanDetector
from input_filter import filter_android_json


def _build_norm_to_orig_map(original: str, normalized: str) -> list[int]:
    """정규화 텍스트의 각 문자 인덱스 → 원문 인덱스 매핑 배열 생성.

    difflib.SequenceMatcher로 두 문자열을 정렬하여
    normalized[i]가 original의 어느 위치에서 왔는지 추적한다.
    """
    sm = SequenceMatcher(None, original, normalized, autojunk=False)
    n2o = [-1] * len(normalized)

    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            for k in range(j2 - j1):
                n2o[j1 + k] = i1 + k
        elif tag == "replace":
            # 길이가 다를 수 있으므로 비례 배분
            orig_len = i2 - i1
            norm_len = j2 - j1
            for k in range(norm_len):
                n2o[j1 + k] = i1 + min(k * orig_len // norm_len, orig_len - 1)

    # -1 갭을 직전 유효 인덱스로 채움
    last = 0
    for i in range(len(n2o)):
        if n2o[i] >= 0:
            last = n2o[i]
        else:
            n2o[i] = last

    return n2o

# 모델 경로: 환경변수 > 기본값(backend/ 기준)
BASE = os.environ.get("MODEL_BASE", os.path.join(os.path.dirname(__file__), ".."))


class ProfanityPipeline:
    def __init__(self,
                 classifier_path: str = None,
                 span_model_path: str = None,
                 threshold: float = 0.5):
        """
        Args:
            classifier_path: 분류 모델 경로
            span_model_path: Span CRF 모델 경로
            threshold: 분류 임계값 (is_profane 등 판정 기준)
        """
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

    def analyze(self, text: str) -> dict:
        """단일 텍스트 분석.

        분류기가 주 판정자, span은 근거 제공자.
        분류기 판정(is_offensive)을 span이 뒤집지 않는다.
        """
        total_started = time.perf_counter()

        # [1단계] 정규화
        normalize_started = time.perf_counter()
        normalized = normalize(text)
        normalize_ms = (time.perf_counter() - normalize_started) * 1000

        # [2단계] 문장 분류
        classifier_started = time.perf_counter()
        cls_result = self.classifier.predict(normalized, threshold=self.threshold)
        classifier_ms = (time.perf_counter() - classifier_started) * 1000

        is_offensive = (
            cls_result["is_profane"]
            or cls_result["is_toxic"]
            or cls_result["is_hate"]
        )

        # [3단계] Span 추출 — 분류기가 유해 판정한 경우에만 실행
        evidence_spans = []
        span_ms = 0.0
        if is_offensive:
            span_started = time.perf_counter()
            raw_spans = self.span_detector.detect(normalized)
            span_ms = (time.perf_counter() - span_started) * 1000

            # 정규화↔원문 인덱스 매핑 (정규화로 좌표가 틀어지는 문제 해결)
            n2o = _build_norm_to_orig_map(text, normalized)

            for s in raw_spans:
                ns, ne = s["start"], s["end"]
                if 0 <= ns < len(n2o) and 0 < ne <= len(n2o):
                    s["start"] = n2o[ns]
                    s["end"] = n2o[ne - 1] + 1
                    s["text"] = text[s["start"]:s["end"]]
                else:
                    s["start"] = -1
                    s["end"] = -1
                evidence_spans.append(s)

        total_pipeline_ms = (time.perf_counter() - total_started) * 1000
        model_ms = classifier_ms + span_ms

        return {
            "text": text,
            "is_offensive": is_offensive,
            "is_profane": cls_result["is_profane"],
            "is_toxic": cls_result["is_toxic"],
            "is_hate": cls_result["is_hate"],
            "scores": cls_result["scores"],
            "evidence_spans": evidence_spans,
            "_timing": {
                "normalize_ms": round(normalize_ms, 3),
                "classifier_ms": round(classifier_ms, 3),
                "span_ms": round(span_ms, 3),
                "model_ms": round(model_ms, 3),
                "pipeline_ms": round(total_pipeline_ms, 3),
            },
        }

    def analyze_batch(self, texts: list[str]) -> list[dict]:
        """배치 텍스트 분석."""
        return [self.analyze(text) for text in texts]

    def analyze_android_batch(self, raw: dict) -> dict:
        """Android 앱 수집 JSON 전체 처리.

        0단계 필터 → 분석 → boundsInScreen 보존하여 반환.
        """
        total = len(raw.get("comments", []))
        valid_comments = filter_android_json(raw)

        results = []
        for item in valid_comments:
            text = item["commentText"]
            bounds = item["boundsInScreen"]

            analysis = self.analyze(text)

            results.append({
                "original": text,
                "boundsInScreen": bounds,
                "is_offensive": analysis["is_offensive"],
                "is_profane": analysis["is_profane"],
                "is_toxic": analysis["is_toxic"],
                "is_hate": analysis["is_hate"],
                "scores": analysis["scores"],
                "evidence_spans": analysis["evidence_spans"],
            })

        return {
            "timestamp": raw.get("timestamp"),
            "results": results,
            "filtered_count": total - len(valid_comments),
        }
