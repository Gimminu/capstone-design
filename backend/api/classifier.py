"""
[3단계] XLM-RoBERTa 문장 분류 래퍼
- 기존 ./models 모델 로드
- 출력: is_profane, is_toxic, (is_hate)
"""
import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification

MODEL_PATH = "./models_v2"


def _get_device() -> torch.device:
    if getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available():
        return torch.device("mps")
    if torch.cuda.is_available():
        return torch.device("cuda")
    return torch.device("cpu")


DEVICE = _get_device()


class TextClassifier:
    def __init__(self, model_path: str = MODEL_PATH, device=DEVICE):
        self.device = device
        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(model_path).to(device)
        self.model.eval()
        # 모델 라벨: [비속어(P), 공격성(A), 혐오(H)]
        self.label_names = ["profanity", "toxicity", "hate"]

    def predict(self, text: str, threshold: float = 0.5) -> dict:
        """단일 텍스트 분류.

        Returns:
            {
                "is_profane": bool,
                "is_toxic": bool,
                "is_hate": bool,
                "scores": {"profanity": float, "toxicity": float, "hate": float}
            }
        """
        inputs = self.tokenizer(
            text, return_tensors="pt", truncation=True, max_length=128
        ).to(self.device)

        with torch.no_grad():
            logits = self.model(**inputs).logits
            probs = torch.sigmoid(logits).cpu().numpy()[0]

        scores = {name: float(probs[i]) for i, name in enumerate(self.label_names)}

        return {
            "is_profane": scores["profanity"] >= threshold,
            "is_toxic": scores["toxicity"] >= threshold,
            "is_hate": scores["hate"] >= threshold,
            "scores": scores,
        }

    def predict_batch(self, texts: list[str], threshold: float = 0.5) -> list[dict]:
        """배치 텍스트 분류."""
        inputs = self.tokenizer(
            texts, return_tensors="pt", truncation=True,
            max_length=128, padding=True
        ).to(self.device)

        with torch.no_grad():
            logits = self.model(**inputs).logits
            probs = torch.sigmoid(logits).cpu().numpy()

        results = []
        for prob in probs:
            scores = {name: float(prob[i]) for i, name in enumerate(self.label_names)}
            results.append({
                "is_profane": scores["profanity"] >= threshold,
                "is_toxic": scores["toxicity"] >= threshold,
                "is_hate": scores["hate"] >= threshold,
                "scores": scores,
            })
        return results
