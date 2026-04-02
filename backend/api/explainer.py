"""
XAI 기반 욕설 위치 추출 (Integrated Gradients)
- 모델이 "왜 이 문장을 욕설로 판단했는가"를 토큰 단위로 설명
- 사전 없이 모델 자체에서 기여도를 추출
"""
import torch
import numpy as np
from transformers import AutoTokenizer, AutoModelForSequenceClassification

MODEL_PATH = "./models_v2"
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")


class ProfanityExplainer:
    def __init__(self, model_path: str = MODEL_PATH, device=DEVICE):
        self.device = device
        self.tokenizer = AutoTokenizer.from_pretrained(model_path)
        self.model = AutoModelForSequenceClassification.from_pretrained(model_path).to(device)
        self.model.eval()
        self.label_names = ["profanity", "toxicity", "hate"]
        # 임베딩 레이어 참조
        self.embeddings = self.model.roberta.embeddings.word_embeddings

    def analyze(self, text: str, threshold: float = 0.5, n_steps: int = 50) -> dict:
        """텍스트 분석: 분류 + XAI 기반 토큰별 기여도 추출.

        Returns:
            {
                "text": str,
                "is_profane": bool,
                "is_toxic": bool,
                "is_hate": bool,
                "scores": {"profanity": float, "toxicity": float, "hate": float},
                "tokens": [{"token": str, "attribution": float}, ...],
                "flagged_tokens": [{"token": str, "attribution": float, "position": int}, ...],
            }
        """
        # 토크나이즈
        inputs = self.tokenizer(
            text, return_tensors="pt", truncation=True, max_length=128
        ).to(self.device)
        input_ids = inputs["input_ids"]

        # 1) 분류 점수
        with torch.no_grad():
            logits = self.model(**inputs).logits
            probs = torch.sigmoid(logits).cpu().numpy()[0]

        scores = {name: float(probs[i]) for i, name in enumerate(self.label_names)}
        is_profane = scores["profanity"] >= threshold
        is_toxic = scores["toxicity"] >= threshold
        is_hate = scores["hate"] >= threshold

        # 유해하지 않으면 attribution 계산 생략
        if not (is_profane or is_toxic or is_hate):
            return {
                "text": text,
                "is_profane": False,
                "is_toxic": False,
                "is_hate": False,
                "scores": scores,
                "tokens": [],
                "flagged_tokens": [],
            }

        # 2) 가장 높은 유해 라벨 기준으로 Integrated Gradients 계산
        target_idx = int(np.argmax(probs))
        attributions = self._integrated_gradients(inputs, target_idx, n_steps)

        # 3) 토큰별 기여도 정리
        tokens = self.tokenizer.convert_ids_to_tokens(input_ids[0])
        token_attrs = attributions.cpu().numpy()

        # 특수 토큰(<s>, </s>, <pad>) 제거
        token_results = []
        for i, (tok, attr) in enumerate(zip(tokens, token_attrs)):
            if tok in ("<s>", "</s>", "<pad>"):
                continue
            token_results.append({
                "token": tok,
                "attribution": float(attr),
                "position": i,
            })

        # 4) 기여도 정규화 (0~1)
        if token_results:
            max_attr = max(abs(t["attribution"]) for t in token_results)
            if max_attr > 0:
                for t in token_results:
                    t["attribution"] = t["attribution"] / max_attr

        # 5) 전체 토큰을 단어 단위로 병합 후 높은 기여도 단어 추출
        flagged_words = self._merge_subwords(token_results, text, tokens)

        return {
            "text": text,
            "is_profane": is_profane,
            "is_toxic": is_toxic,
            "is_hate": is_hate,
            "scores": scores,
            "tokens": token_results,
            "flagged_tokens": flagged_words,
        }

    def _integrated_gradients(self, inputs, target_idx: int, n_steps: int = 50) -> torch.Tensor:
        """Integrated Gradients 계산.

        baseline(zero embedding) → input embedding까지 경로를 따라
        gradient를 적분하여 각 토큰의 기여도를 계산.
        """
        input_ids = inputs["input_ids"]
        attention_mask = inputs["attention_mask"]

        # baseline: zero embedding
        baseline_embeds = torch.zeros_like(
            self.embeddings(input_ids)
        ).to(self.device)
        input_embeds = self.embeddings(input_ids).detach()

        # 경로를 따라 gradient 적분
        total_grads = torch.zeros_like(input_embeds)

        for step in range(n_steps + 1):
            alpha = step / n_steps
            interpolated = baseline_embeds + alpha * (input_embeds - baseline_embeds)
            interpolated.requires_grad_(True)

            # forward pass (inputs_embeds 사용)
            outputs = self.model(
                inputs_embeds=interpolated,
                attention_mask=attention_mask,
            )
            logits = outputs.logits
            score = torch.sigmoid(logits[0, target_idx])

            # backward
            self.model.zero_grad()
            score.backward(retain_graph=True)

            total_grads += interpolated.grad.detach()
            interpolated.requires_grad_(False)

        # 적분 근사: 평균 gradient × (input - baseline)
        avg_grads = total_grads / (n_steps + 1)
        integrated_grads = (input_embeds - baseline_embeds) * avg_grads

        # 토큰별로 합산 (embedding 차원 → 스칼라)
        token_attributions = integrated_grads.sum(dim=-1).squeeze(0)

        return token_attributions

    def _merge_subwords(self, token_results: list, original_text: str, all_tokens: list) -> list:
        """서브워드 토큰을 원문 단어 단위로 병합 후 높은 기여도 단어 추출.

        핵심: 모든 토큰을 먼저 단어로 묶고, 단어 내 최대 attribution으로 필터링.
        이전 방식(flagged 토큰만 병합)은 같은 단어의 일부 서브워드가 누락되는 버그가 있었음.
        """
        if not token_results:
            return []

        # 1) 전체 토큰을 단어 단위로 그룹화
        #    XLM-RoBERTa: '▁'로 시작하면 새 단어, 아니면 이전 단어에 이어붙임
        words = []  # [{word_text, max_attr, tokens: [...]}]
        current_word = {"text": "", "max_attr": 0, "tokens": []}

        for t in token_results:
            tok = t["token"]
            attr = t["attribution"]

            if tok.startswith("▁"):
                # 이전 단어 저장
                if current_word["text"]:
                    words.append(current_word)
                # 새 단어 시작 (▁ 제거)
                clean = tok.lstrip("▁")
                current_word = {"text": clean, "max_attr": attr, "tokens": [t]}
            else:
                # 서브워드 이어붙이기
                current_word["text"] += tok
                current_word["max_attr"] = max(current_word["max_attr"], attr)
                current_word["tokens"].append(t)

        # 마지막 단어 저장
        if current_word["text"]:
            words.append(current_word)

        # 2) 원문에서 각 단어의 start/end 위치 찾기 + 기여도 필터링
        flagged_words = []
        search_start = 0

        for w in words:
            if w["max_attr"] < 0.3:
                continue
            if not w["text"]:
                continue

            # 원문에서 해당 단어 위치 찾기
            idx = original_text.find(w["text"], search_start)
            if idx == -1:
                # 토크나이저가 변형한 경우 — 원문 전체에서 재탐색
                idx = original_text.find(w["text"])

            if idx >= 0:
                flagged_words.append({
                    "word": w["text"],
                    "attribution": round(w["max_attr"], 3),
                    "start": idx,
                    "end": idx + len(w["text"]),
                })
                search_start = idx + len(w["text"])
            else:
                # 원문에서 못 찾아도 단어 자체는 반환
                flagged_words.append({
                    "word": w["text"],
                    "attribution": round(w["max_attr"], 3),
                })

        return flagged_words

    def analyze_batch(self, texts: list[str], threshold: float = 0.5) -> list[dict]:
        """배치 분석."""
        return [self.analyze(text, threshold=threshold) for text in texts]
