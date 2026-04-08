"""문장 분류 + Span 추출 통합 데모"""

import os
import sys

BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
API_DIR = os.path.join(BASE, "api")
TRAINING_DIR = os.path.join(BASE, "training")
for path in (API_DIR, TRAINING_DIR):
    if path not in sys.path:
        sys.path.insert(0, path)

import torch
from transformers import AutoTokenizer
from train_span import SpanCRFModel, NUM_LABELS, ID2LABEL
from classifier import TextClassifier
from normalizer import normalize

SPAN_MODEL_DIR = os.path.join(BASE, "models", "span_large_combined_crf")
CLF_MODEL_DIR = os.path.join(BASE, "models", "v2")


def load_models():
    # 1) 문장 분류 모델 (v2)
    classifier = TextClassifier(model_path=CLF_MODEL_DIR)

    # 2) Span 추출 모델 (large + CRF)
    enc_dir = os.path.join(SPAN_MODEL_DIR, "encoder")
    span_model = SpanCRFModel(enc_dir, NUM_LABELS).cuda()
    extra = torch.load(os.path.join(SPAN_MODEL_DIR, "extra_weights.pt"), map_location="cuda")
    span_model.classifier.load_state_dict(extra["classifier"])
    span_model.crf.load_state_dict(extra["crf"])
    span_model.eval()
    span_tokenizer = AutoTokenizer.from_pretrained(enc_dir)

    return classifier, span_model, span_tokenizer


def extract_spans(model, tokenizer, text):
    enc = tokenizer(text, return_tensors="pt", truncation=True,
                    max_length=128, return_offsets_mapping=True)
    offsets = enc.pop("offset_mapping").squeeze(0).tolist()
    input_ids = enc["input_ids"].cuda()
    attention_mask = enc["attention_mask"].cuda()

    with torch.no_grad():
        preds = model(input_ids, attention_mask)
    pred_tags = preds[0]

    spans = []
    current_start = None
    current_end = None
    for i, tag_id in enumerate(pred_tags):
        if i >= len(offsets):
            break
        char_start, char_end = offsets[i]
        if char_start == 0 and char_end == 0:
            continue

        label = ID2LABEL[tag_id]

        if label == "B-OFF":
            if current_start is not None:
                spans.append((current_start, current_end))
            current_start = char_start
            current_end = char_end
        elif label == "I-OFF" and current_start is not None:
            current_end = char_end
        else:
            if current_start is not None:
                spans.append((current_start, current_end))
                current_start = None

    if current_start is not None:
        spans.append((current_start, current_end))

    return spans


def analyze(classifier, span_model, span_tokenizer, text):
    """분류 + span 추출 통합 분석."""
    # 1단계: 정규화
    normalized = normalize(text)

    # 2단계: 문장 분류
    cls_result = classifier.predict(normalized)

    # 3단계: Span 추출
    spans = extract_spans(span_model, span_tokenizer, normalized)

    # span 텍스트 추출
    evidence = []
    for start, end in spans:
        evidence.append({
            "text": normalized[start:end],
            "start": start,
            "end": end,
        })

    is_offensive = cls_result["is_profane"] or cls_result["is_toxic"] or cls_result["is_hate"]

    return {
        "original": text,
        "normalized": normalized,
        "is_offensive": is_offensive,
        "is_profane": cls_result["is_profane"],
        "is_toxic": cls_result["is_toxic"],
        "is_hate": cls_result["is_hate"],
        "scores": cls_result["scores"],
        "evidence_spans": evidence,
    }


def main():
    print("모델 로딩 중...")
    classifier, span_model, span_tokenizer = load_models()
    print("분류 모델 + Span 모델 로딩 완료!\n")
    print("문장을 입력하세요 (종료: q)\n")

    while True:
        text = input(">>> ")
        if text.strip().lower() == "q":
            break
        if not text.strip():
            continue

        result = analyze(classifier, span_model, span_tokenizer, text)

        # 분류 결과
        labels = []
        if result["is_profane"]:
            labels.append("욕설")
        if result["is_toxic"]:
            labels.append("공격")
        if result["is_hate"]:
            labels.append("혐오")

        scores = result["scores"]
        print(f"  분류: {', '.join(labels) if labels else '정상'}")
        print(f"  점수: profanity={scores['profanity']:.3f}  "
              f"toxicity={scores['toxicity']:.3f}  "
              f"hate={scores['hate']:.3f}")

        # Span 결과
        if result["evidence_spans"]:
            words = [s["text"] for s in result["evidence_spans"]]
            print(f"  추출: {words}")
        else:
            print(f"  추출: (없음)")
        print()


if __name__ == "__main__":
    main()
