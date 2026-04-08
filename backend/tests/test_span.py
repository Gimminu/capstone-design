"""Best 모델로 Test Set 3종 평가 (combined / KOLD / K-HATERS)"""

import os
import sys

_HERE = os.path.abspath(os.path.dirname(__file__))
_BACKEND = os.path.join(_HERE, "..")
sys.path.insert(0, os.path.join(_BACKEND, "api"))
sys.path.insert(0, os.path.join(_BACKEND, "training"))

import torch
from torch.utils.data import DataLoader
from train_span import (
    SpanCRFModel, SpanDataset, evaluate, load_best_model,
    NUM_LABELS, LABEL2ID, ID2LABEL,
)

BASE = _BACKEND
MODEL_DIR = os.path.join(BASE, "models", "span_large_combined_crf")
DATA_DIR = os.path.join(BASE, "data", "train")

BATCH_SIZE = 8


def main():
    print("=" * 60)
    print("Best 모델 Test 평가")
    print("=" * 60)

    # 모델 로드
    print(f"\n모델 로드: {MODEL_DIR}")
    model = load_best_model(MODEL_DIR, use_crf=True)
    print("모델 로드 완료\n")

    # 토크나이저
    from transformers import AutoTokenizer
    enc_dir = os.path.join(MODEL_DIR, "encoder")
    tokenizer = AutoTokenizer.from_pretrained(enc_dir)

    # CRF용 dummy criterion (evaluate에서 loss 계산용)
    criterion = None  # CRF 모드에서는 모델 내부에서 loss 계산

    test_files = [
        ("Combined", os.path.join(DATA_DIR, "combined_test.jsonl")),
        ("KOLD",     os.path.join(DATA_DIR, "kold_test.jsonl")),
        ("K-HATERS", os.path.join(DATA_DIR, "khaters_test.jsonl")),
    ]

    for name, path in test_files:
        if not os.path.exists(path):
            print(f"[SKIP] {name}: {path} 없음")
            continue

        print(f"=== {name} Test Set 평가 ===")
        ds = SpanDataset(path, tokenizer)
        loader = DataLoader(ds, batch_size=BATCH_SIZE, num_workers=0)

        _, tok, spn = evaluate(model, loader, criterion, use_crf=True)

        print(f"  Token F1 : P={tok['precision']:.4f}  "
              f"R={tok['recall']:.4f}  F1={tok['f1']:.4f}")
        print(f"  Span  F1 : P={spn['precision']:.4f}  "
              f"R={spn['recall']:.4f}  F1={spn['f1']:.4f}")
        print()

    print("=" * 60)
    print("평가 완료")


if __name__ == "__main__":
    main()
