import os
import sys
import types
import unittest

_HERE = os.path.abspath(os.path.dirname(__file__))
_BACKEND = os.path.join(_HERE, "..")
sys.path.insert(0, os.path.join(_BACKEND, "api"))

classifier_stub = types.ModuleType("classifier")
classifier_stub.TextClassifier = object
sys.modules.setdefault("classifier", classifier_stub)

span_detector_stub = types.ModuleType("span_detector")
span_detector_stub.SpanDetector = object
sys.modules.setdefault("span_detector", span_detector_stub)

from pipeline import ProfanityPipeline


DEFAULT_SAFE_RESULT = {
    "is_profane": False,
    "is_toxic": False,
    "is_hate": False,
    "scores": {"profanity": 0.01, "toxicity": 0.02, "hate": 0.01},
}


class FakeClassifier:
    def __init__(self):
        self.predict_batch_calls = []
        self.results_by_text = {
            "시발 뭐하는 거야": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.97, "toxicity": 0.96, "hate": 0.02},
            },
            "병신아 꺼져": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.95, "toxicity": 0.94, "hate": 0.01},
            },
            "scripts": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.599, "toxicity": 0.622, "hate": 0.02},
            },
            "Security and quality": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.957, "toxicity": 0.970, "hate": 0.03},
            },
            "Contributor script quality": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.93, "toxicity": 0.91, "hate": 0.02},
            },
            "warp theme": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.94, "toxicity": 0.92, "hate": 0.01},
            },
            "abstract factory": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.91, "toxicity": 0.89, "hate": 0.01},
            },
            "factory pattern": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.88, "toxicity": 0.86, "hate": 0.01},
            },
            "abstract factory pattern": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.92, "toxicity": 0.9, "hate": 0.01},
            },
            "Warp Terminal": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.88, "toxicity": 0.86, "hate": 0.01},
            },
            "dotdev/themes: Custom themes repository for Warp": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.97, "toxicity": 0.95, "hate": 0.02},
            },
            "repository for Warp terminal themes": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.91, "toxicity": 0.89, "hate": 0.01},
            },
            "카필 시발(Kapil Sibal)은 인도의 변호사이자, 정치인이다.": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.95, "toxicity": 0.98, "hate": 0.01},
            },
            "시발 - 위키낱말사전": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.97, "toxicity": 0.96, "hate": 0.02},
            },
            "국제차량제작 시발": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.94, "toxicity": 0.93, "hate": 0.02},
            },
            "직역하면 시발 현에 사는 노비가 아무 색깔 없는 기를 건다는 뜻이다.": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.91, "toxicity": 0.92, "hate": 0.01},
            },
            "병신 - 위키백과, 우리 모두의 백과사전": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.97, "toxicity": 0.98, "hate": 0.02},
            },
            "병신 (r692 판) 개념이 없는 사람을 일컫는 낱말로, 장애인처럼 신체나 정신주로 정신이 모자라거나 이상한) 사람을 빗대는 말인 만큼 때때로 장애인 혐오표현이 될 수 있.": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.96, "toxicity": 0.97, "hate": 0.02},
            },
            "시발 (r385 판) 카필 시발(Kapil Sibal)은 인도의 변호사이자, 정치인이다.": {
                "is_profane": True,
                "is_toxic": True,
                "is_hate": False,
                "scores": {"profanity": 0.97, "toxicity": 0.98, "hate": 0.02},
            },
        }

    def predict(self, text, threshold=0.5):
        return self.predict_batch([text], threshold=threshold)[0]

    def predict_batch(self, texts, threshold=0.5):
        self.predict_batch_calls.append(list(texts))
        return [self.results_by_text.get(text, DEFAULT_SAFE_RESULT) for text in texts]


class FakeSpanDetector:
    def __init__(self):
        self.detect_calls = []

    def detect(self, text, confidence_override=None):
        self.detect_calls.append(text)
        if text == "시발 뭐하는 거야":
            return [{"text": "시발", "start": 0, "end": 2, "score": 0.99}]
        if text == "병신아 꺼져":
            return [
                {"text": "병신", "start": 0, "end": 2, "score": 0.98},
                {"text": "꺼져", "start": 4, "end": 6, "score": 0.97},
            ]
        if text == "Security and quality":
            return [{"text": "urity and qualit", "start": 3, "end": 19, "score": 0.91}]
        if text == "Contributor script quality":
            return [{"text": "script", "start": 12, "end": 18, "score": 0.89}]
        if text == "dotdev/themes: Custom themes repository for Warp":
            return [{"text": "for War", "start": 40, "end": 47, "score": 0.89}]
        return []


class PipelineContextTests(unittest.TestCase):
    def make_pipeline(self):
        pipeline = ProfanityPipeline.__new__(ProfanityPipeline)
        pipeline.classifier = FakeClassifier()
        pipeline.span_detector = FakeSpanDetector()
        pipeline.threshold = 0.5
        return pipeline

    def test_proper_noun_and_brand_contexts_stay_safe(self):
        pipeline = self.make_pipeline()
        texts = [
            "카필 시발(Kapil Sibal)은 인도의 변호사이자, 정치인이다.",
            "국제차량제작 시발",
        ]

        results = pipeline.analyze_batch(texts)

        self.assertEqual(len(results), len(texts))
        self.assertTrue(all(result["text"] == text for result, text in zip(results, texts)))
        self.assertTrue(all(result["is_offensive"] is False for result in results))
        self.assertTrue(all(result["evidence_spans"] == [] for result in results))
        self.assertEqual(pipeline.span_detector.detect_calls, [])

    def test_dictionary_title_is_not_forced_safe_at_default_sensitivity(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("시발 - 위키낱말사전", sensitivity=60)

        self.assertTrue(result["is_offensive"])
        self.assertEqual([span["text"] for span in result["evidence_spans"]], ["시발"])

    def test_dictionary_title_can_relax_at_low_sensitivity(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("시발 - 위키낱말사전", sensitivity=20)

        self.assertFalse(result["is_offensive"])
        self.assertEqual(result["evidence_spans"], [])

    def test_explicit_dictionary_definition_is_blocked_at_default_sensitivity(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("여성의 성기를 일컫는말.", sensitivity=60)

        self.assertTrue(result["is_offensive"])
        self.assertEqual([span["text"] for span in result["evidence_spans"]], ["성기"])

    def test_dictionary_title_and_explicit_definition_are_both_returned(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("시발 - 위키낱말사전\n여성의 성기를 일컫는말.", sensitivity=60)

        self.assertTrue(result["is_offensive"])
        self.assertEqual([span["text"] for span in result["evidence_spans"]], ["시발", "성기"])

    def test_browser_ui_regressions_are_safe(self):
        pipeline = self.make_pipeline()
        texts = ["scripts", "README", "android", "Pull requests", "Security and quality"]

        results = pipeline.analyze_batch(texts)

        self.assertEqual([result["text"] for result in results], texts)
        self.assertTrue(all(result["is_offensive"] is False for result in results))
        self.assertTrue(all(result["evidence_spans"] == [] for result in results))
        self.assertEqual(pipeline.span_detector.detect_calls, [])

    def test_browser_result_regressions_are_safe(self):
        pipeline = self.make_pipeline()
        texts = [
            "warp theme",
            "Warp Terminal",
            "dotdev/themes: Custom themes repository for Warp",
            "repository for Warp terminal themes",
        ]

        results = pipeline.analyze_batch(texts)

        self.assertEqual([result["text"] for result in results], texts)
        self.assertTrue(all(result["is_offensive"] is False for result in results))
        self.assertTrue(all(result["evidence_spans"] == [] for result in results))

    def test_technical_design_pattern_terms_are_safe(self):
        pipeline = self.make_pipeline()
        texts = [
            "abstract factory",
            "factory pattern",
            "abstract factory pattern",
        ]

        results = pipeline.analyze_batch(texts)

        self.assertEqual([result["text"] for result in results], texts)
        self.assertTrue(all(result["is_offensive"] is False for result in results))
        self.assertTrue(all(result["evidence_spans"] == [] for result in results))

    def test_romanized_and_keyboard_variants_use_dictionary_spans(self):
        pipeline = self.make_pipeline()

        ssibal = pipeline.analyze("ssibal")
        tlqkf = pipeline.analyze("TLQKF 티셔츠")
        qudtls = pipeline.analyze("Qudtls 뜻")

        self.assertTrue(ssibal["is_offensive"])
        self.assertEqual([span["text"] for span in ssibal["evidence_spans"]], ["ssibal"])
        self.assertTrue(tlqkf["is_offensive"])
        self.assertEqual([span["text"] for span in tlqkf["evidence_spans"]], ["TLQKF"])
        self.assertTrue(qudtls["is_offensive"])
        self.assertEqual([span["text"] for span in qudtls["evidence_spans"]], ["Qudtls"])

    def test_batch_path_preserves_order_and_deduplicates_model_work(self):
        pipeline = self.make_pipeline()
        texts = ["시발 뭐하는 거야", "병신아 꺼져", "시발 뭐하는 거야"]

        results = pipeline.analyze_batch(texts)

        self.assertEqual([result["text"] for result in results], texts)
        self.assertTrue(results[0]["is_offensive"])
        self.assertTrue(results[1]["is_offensive"])
        self.assertTrue(results[2]["is_offensive"])
        self.assertEqual(len(pipeline.classifier.predict_batch_calls), 1)
        self.assertEqual(len(pipeline.classifier.predict_batch_calls[0]), 2)
        self.assertEqual(pipeline.span_detector.detect_calls, [])

    def test_exact_spans_are_preserved_for_explicit_profanity(self):
        pipeline = self.make_pipeline()

        first = pipeline.analyze("시발 뭐하는 거야")
        second = pipeline.analyze("병신아 꺼져")

        self.assertTrue(first["is_offensive"])
        self.assertEqual(first["evidence_spans"][0]["text"], "시발")
        self.assertTrue(second["is_offensive"])
        self.assertEqual([span["text"] for span in second["evidence_spans"]], ["병신", "꺼져"])

    def test_wiki_and_definition_contexts_for_explicit_slurs_are_not_globally_forced_safe(self):
        pipeline = self.make_pipeline()

        wiki_result = pipeline.analyze("병신 - 위키백과, 우리 모두의 백과사전")
        definition_result = pipeline.analyze(
            "병신 (r692 판)\n개념이 없는 사람을 일컫는 낱말로, 장애인처럼 신체나 정신(주로 정신이 모자라거나 이상한) 사람을 빗대는 말인 만큼 때때로 장애인 혐오표현이 될 수 있..."
        )

        self.assertTrue(wiki_result["is_offensive"])
        self.assertEqual([span["text"] for span in wiki_result["evidence_spans"]], ["병신"])
        self.assertTrue(definition_result["is_offensive"])
        self.assertEqual([span["text"] for span in definition_result["evidence_spans"]], ["병신"])

    def test_safe_transliteration_does_not_hide_title_plus_snippet_card(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("시발 (r385 판) 카필 시발(Kapil Sibal)은 인도의 변호사이자, 정치인이다.")

        self.assertTrue(result["is_offensive"])
        self.assertEqual([span["text"] for span in result["evidence_spans"]], ["시발"])

    def test_ascii_ui_like_span_is_forced_safe_when_evidence_is_invalid(self):
        pipeline = self.make_pipeline()

        result = pipeline.analyze("Contributor script quality")

        self.assertFalse(result["is_offensive"])
        self.assertEqual(result["evidence_spans"], [])


if __name__ == "__main__":
    unittest.main()
