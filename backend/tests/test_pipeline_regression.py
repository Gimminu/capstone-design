import json
import sys
import unittest
from pathlib import Path


API_DIR = Path(__file__).resolve().parents[1] / "api"
BACKEND_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = BACKEND_DIR.parent
PIPELINE_CASE_FILE = REPO_ROOT / "evaluation" / "api-vs-ml" / "cases.jsonl"

if str(API_DIR) not in sys.path:
    sys.path.insert(0, str(API_DIR))


def has_model_weights() -> bool:
    return (
        (BACKEND_DIR / "models" / "v2" / "model.safetensors").exists()
        and (BACKEND_DIR / "models" / "span_large_combined_crf" / "extra_weights.pt").exists()
        and (BACKEND_DIR / "models" / "span_large_combined_crf" / "encoder" / "model.safetensors").exists()
    )


def load_pipeline_cases() -> list[dict]:
    cases = []
    with PIPELINE_CASE_FILE.open("r", encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            cases.append(json.loads(line))
    return cases


@unittest.skipUnless(has_model_weights(), "local model weights are required for pipeline regression tests")
class PipelineRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from pipeline import ProfanityPipeline

        cls.pipeline = ProfanityPipeline()
        cls.cases = load_pipeline_cases()

    def test_safe_regression_cases_do_not_block(self):
        safe_cases = [
            case for case in self.cases
            if case.get("expected_offensive") is False
        ]

        for case in safe_cases:
            with self.subTest(case=case.get("id"), text=case["text"]):
                text = case["text"]
                result = self.pipeline.analyze(text, sensitivity=60)
                self.assertFalse(result["is_offensive"])
                self.assertEqual(result["evidence_spans"], [])

    def test_dictionary_title_with_explicit_definition_blocks(self):
        result = self.pipeline.analyze("시발 - 위키낱말사전\n여성의 성기를 일컫는말.", sensitivity=60)

        self.assertTrue(result["is_offensive"])
        self.assertTrue(result["evidence_spans"])

    def test_offensive_regression_cases_return_exact_spans(self):
        offensive_cases = [
            case for case in self.cases
            if case.get("expected_offensive") is True
            and case.get("expected_spans")
        ]

        for case in offensive_cases:
            text = case["text"]
            expected_spans = case["expected_spans"]
            with self.subTest(case=case.get("id"), text=text):
                result = self.pipeline.analyze(text, sensitivity=60)
                self.assertTrue(result["is_offensive"])
                actual_spans = [span["text"] for span in result["evidence_spans"]]
                for expected_span in expected_spans:
                    self.assertTrue(
                        any(expected_span == span or expected_span in span for span in actual_spans),
                        result["evidence_spans"],
                    )

    def test_sensitivity_zero_suppresses_direct_spans(self):
        result = self.pipeline.analyze("씨발 뭐하는 거야", sensitivity=0)

        self.assertFalse(result["is_offensive"])
        self.assertEqual(result["evidence_spans"], [])

    def test_sensitivity_zero_suppresses_dictionary_variants(self):
        for text in ["f.u.c.k off", "くそ", "操你妈", "nigga", "ssibal 뜻", "개새끼 꺼져"]:
            with self.subTest(text=text):
                result = self.pipeline.analyze(text, sensitivity=0)
                self.assertFalse(result["is_offensive"])
                self.assertEqual(result["evidence_spans"], [])

    def test_android_batch_preserves_bounds_and_order(self):
        raw = {
            "timestamp": 1710000000000,
            "comments": [
                {
                    "commentText": "좋은 영상이네요",
                    "boundsInScreen": {"left": 0, "top": 10, "right": 300, "bottom": 60},
                },
                {
                    "commentText": "시발 뭐하는 거야",
                    "boundsInScreen": {"left": 0, "top": 70, "right": 300, "bottom": 120},
                },
            ],
        }

        result = self.pipeline.analyze_android_batch(raw)

        self.assertEqual(1710000000000, result["timestamp"])
        self.assertEqual(0, result["filtered_count"])
        self.assertEqual(
            ["좋은 영상이네요", "시발 뭐하는 거야"],
            [item["original"] for item in result["results"]],
        )
        self.assertEqual(
            [
                {"left": 0, "top": 10, "right": 300, "bottom": 60},
                {"left": 0, "top": 70, "right": 300, "bottom": 120},
            ],
            [item["boundsInScreen"] for item in result["results"]],
        )
        self.assertFalse(result["results"][0]["is_offensive"])
        self.assertTrue(result["results"][1]["is_offensive"])
        self.assertTrue(result["results"][1]["evidence_spans"])


if __name__ == "__main__":
    unittest.main()
