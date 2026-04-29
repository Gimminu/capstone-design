import sys
import unittest
from pathlib import Path


API_DIR = Path(__file__).resolve().parents[1] / "api"
BACKEND_DIR = Path(__file__).resolve().parents[1]

if str(API_DIR) not in sys.path:
    sys.path.insert(0, str(API_DIR))


def has_model_weights() -> bool:
    return (
        (BACKEND_DIR / "models" / "v2" / "model.safetensors").exists()
        and (BACKEND_DIR / "models" / "span_large_combined_crf" / "extra_weights.pt").exists()
        and (BACKEND_DIR / "models" / "span_large_combined_crf" / "encoder" / "model.safetensors").exists()
    )


@unittest.skipUnless(has_model_weights(), "local model weights are required for pipeline regression tests")
class PipelineRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from pipeline import ProfanityPipeline

        cls.pipeline = ProfanityPipeline()

    def test_safe_regression_cases_do_not_block(self):
        safe_cases = [
            "abstract factory",
            "warp theme",
            "scripts",
            "README",
            "시발 - 위키낱말사전",
            "카필 시발(Kapil Sibal)은 인도의 변호사이자, 정치인이다.",
            "국제차량제작 시발",
        ]

        for text in safe_cases:
            with self.subTest(text=text):
                result = self.pipeline.analyze(text, sensitivity=60)
                self.assertFalse(result["is_offensive"])
                self.assertEqual(result["evidence_spans"], [])

    def test_dictionary_title_with_explicit_definition_blocks(self):
        result = self.pipeline.analyze("시발 - 위키낱말사전\n여성의 성기를 일컫는말.", sensitivity=60)

        self.assertTrue(result["is_offensive"])
        self.assertTrue(result["evidence_spans"])

    def test_offensive_regression_cases_return_exact_spans(self):
        offensive_cases = [
            ("씨발 뭐하는 거야", "씨발"),
            ("개새끼", "개새끼"),
            ("ssibal 뜻", "ssibal"),
            ("TLQKF 티셔츠", "TLQKF"),
        ]

        for text, expected_span in offensive_cases:
            with self.subTest(text=text):
                result = self.pipeline.analyze(text, sensitivity=60)
                self.assertTrue(result["is_offensive"])
                self.assertTrue(
                    any(span["text"] == expected_span for span in result["evidence_spans"]),
                    result["evidence_spans"],
                )

    def test_sensitivity_zero_suppresses_direct_spans(self):
        result = self.pipeline.analyze("씨발 뭐하는 거야", sensitivity=0)

        self.assertFalse(result["is_offensive"])
        self.assertEqual(result["evidence_spans"], [])


if __name__ == "__main__":
    unittest.main()
