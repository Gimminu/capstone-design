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

from normalizer import normalize
from classifier import TextClassifier
from span_detector import SpanDetector
from input_filter import filter_android_json

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
        # [1단계] 정규화
        normalized = normalize(text)

        # [2단계] 문장 분류
        cls_result = self.classifier.predict(normalized, threshold=self.threshold)

        is_offensive = (
            cls_result["is_profane"]
            or cls_result["is_toxic"]
            or cls_result["is_hate"]
        )

        # [3단계] Span 추출 — 분류기가 유해 판정한 경우에만 실행
        evidence_spans = []
        if is_offensive:
            raw_spans = self.span_detector.detect(normalized)
            # 정규화 텍스트 → 원문 위치 매핑
            for s in raw_spans:
                idx = text.find(s["text"])
                if idx >= 0:
                    s["start"] = idx
                    s["end"] = idx + len(s["text"])
                else:
                    # 원문에서 직접 매칭되지 않으면 좌표를 신뢰할 수 없으므로 sentinel 처리
                    s["start"] = -1
                    s["end"] = -1
            evidence_spans = raw_spans

        return {
            "text": text,
            "is_offensive": is_offensive,
            "is_profane": cls_result["is_profane"],
            "is_toxic": cls_result["is_toxic"],
            "is_hate": cls_result["is_hate"],
            "scores": cls_result["scores"],
            "evidence_spans": evidence_spans,
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
