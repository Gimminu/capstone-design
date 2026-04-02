"""
메인 파이프라인: 텍스트 → 정규화 → 모델 분류 + XAI Attribution → 결과 JSON
사전 없이 모델이 분류와 위치 추출을 동시에 수행
"""
from normalizer import normalize
from explainer import ProfanityExplainer


class ProfanityPipeline:
    def __init__(self, model_path: str = "./models_v2", threshold: float = 0.5,
                 span_min_score: float = 0.5):
        """
        Args:
            model_path: 모델 경로
            threshold: 분류 임계값 (is_profane 등 판정 기준)
            span_min_score: evidence_spans에 포함할 최소 attribution 점수.
                           이 값 미만이면 span 추출 보류 → 문장 전체 경고만 반환.
        """
        self.explainer = ProfanityExplainer(model_path=model_path)
        self.threshold = threshold
        self.span_min_score = span_min_score

    def analyze(self, text: str) -> dict:
        """단일 텍스트 분석.

        Returns:
            {
                "text": str,
                "is_profane": bool,
                "is_toxic": bool,
                "is_hate": bool,
                "scores": {"profanity": float, "toxicity": float, "hate": float},
                "evidence_spans": [
                    {"text": str, "start": int, "end": int, "score": float}
                ],
                "span_reliable": bool,  # span 신뢰도 충분 여부
            }
        """
        # [1단계] 정규화
        normalized = normalize(text)

        # [2단계] 모델 분류 + XAI attribution 추출
        raw = self.explainer.analyze(normalized, threshold=self.threshold)

        # [3단계] flagged_tokens → evidence_spans 변환 (원문 기준)
        evidence_spans = []
        for ft in raw.get("flagged_tokens", []):
            span = {"text": ft["word"], "score": ft["attribution"]}
            if "start" in ft:
                # 정규화 텍스트 기준 위치 → 원문에서 재탐색
                idx = text.find(ft["word"])
                if idx >= 0:
                    span["start"] = idx
                    span["end"] = idx + len(ft["word"])
                else:
                    span["start"] = ft.get("start", -1)
                    span["end"] = ft.get("end", -1)
            evidence_spans.append(span)

        # span 신뢰도 판정: 최소 1개 span이 span_min_score 이상이어야 신뢰
        span_reliable = any(s["score"] >= self.span_min_score for s in evidence_spans)

        return {
            "text": text,
            "is_profane": raw["is_profane"],
            "is_toxic": raw["is_toxic"],
            "is_hate": raw["is_hate"],
            "scores": raw["scores"],
            "evidence_spans": evidence_spans,
            "span_reliable": span_reliable,
        }

    def analyze_batch(self, texts: list[str]) -> list[dict]:
        """배치 텍스트 분석."""
        return [self.analyze(text) for text in texts]
