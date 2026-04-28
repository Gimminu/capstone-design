"""
모델 재학습 스크립트
- K-MHaS + UnSmile 데이터 결합
- XLM-RoBERTa fine-tuning
- 라벨: [욕설(P), 독성(T), 혐오(H)]

사용법:
  python train.py
  python train.py --epochs 5 --lr 2e-5 --batch_size 16
"""
import argparse
import os
import numpy as np
import pandas as pd
import torch
from torch.utils.data import Dataset, DataLoader
from transformers import (
    AutoTokenizer,
    AutoModelForSequenceClassification,
    get_linear_schedule_with_warmup,
)
from sklearn.metrics import classification_report, f1_score
from huggingface_hub import hf_hub_download
import time

# ── 설정 ─────────────────────────────────────────────
BASE_MODEL = "xlm-roberta-base"  # 처음부터 재학습
OUTPUT_DIR = "./models_v2"
DEVICE = torch.device("mps" if torch.mps.is_available() else "cpu")
LABEL_NAMES = ["profanity", "toxicity", "hate"]


# ── K-MHaS 라벨 매핑 ────────────────────────────────
def map_kmhas(raw_label) -> list:
    """K-MHaS label -> [profanity, toxicity, hate]"""
    labels = [int(x.strip()) for x in str(raw_label).split(",")]
    p = int(3 in labels)                                    # 혐오욕설
    t = int(any(lb != 8 for lb in labels))                  # 유해한 모든 것
    h = int(any(lb in [0, 1, 2, 4, 5, 6, 7] for lb in labels))  # 차별 카테고리
    return [p, t, h]


# ── UnSmile 라벨 매핑 ───────────────────────────────
UNSMILE_HATE_COLS = [
    "여성/가족", "남성", "성소수자", "인종/국적", "연령", "지역", "종교", "기타 혐오"
]

def map_unsmile(row) -> list:
    """UnSmile row -> [profanity, toxicity, hate]"""
    # 컬럼명이 인코딩에 따라 달라질 수 있으므로 인덱스로도 접근
    cols = list(row.keys()) if isinstance(row, dict) else row.index.tolist()

    # 악플/욕설 컬럼 찾기
    profanity_col = None
    clean_col = None
    for c in cols:
        if "악플" in str(c) or "욕설" in str(c):
            profanity_col = c
        if "clean" in str(c).lower():
            clean_col = c

    p = int(row.get(profanity_col, 0)) if profanity_col else 0
    t = int(row.get(clean_col, 1) == 0) if clean_col else 0

    # 혐오 컬럼들
    h = 0
    for hc in UNSMILE_HATE_COLS:
        for c in cols:
            if hc in str(c):
                if int(row.get(c, 0)) == 1:
                    h = 1
                break
    return [p, t, h]


# ── Dataset ─────────────────────────────────────────
class TextDataset(Dataset):
    def __init__(self, texts, labels, tokenizer, max_length=128):
        self.texts = texts
        self.labels = labels
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.texts)

    def __getitem__(self, idx):
        text = str(self.texts[idx])
        label = self.labels[idx]

        encoding = self.tokenizer(
            text, truncation=True, max_length=self.max_length,
            padding="max_length", return_tensors="pt"
        )
        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "labels": torch.tensor(label, dtype=torch.float),
        }


# ── 데이터 로드 ─────────────────────────────────────
def load_data():
    """K-MHaS + UnSmile 데이터 로드 및 결합"""
    all_texts = []
    all_labels = []

    # K-MHaS (train + valid)
    print("K-MHaS 로딩...")
    for split_name, url in [
        ("train", "https://raw.githubusercontent.com/adlnlp/K-MHaS/main/data/kmhas_train.txt"),
        ("valid", "https://raw.githubusercontent.com/adlnlp/K-MHaS/main/data/kmhas_valid.txt"),
    ]:
        df = pd.read_csv(url, sep="\t")
        texts = df["document"].tolist()
        labels = [map_kmhas(lb) for lb in df["label"]]
        all_texts.extend(texts)
        all_labels.extend(labels)
        print(f"  {split_name}: {len(texts)}개")

    # K-MHaS test (평가용으로 별도 보관)
    df_test_kmhas = pd.read_csv(
        "https://raw.githubusercontent.com/adlnlp/K-MHaS/main/data/kmhas_test.txt", sep="\t"
    )
    test_texts_kmhas = df_test_kmhas["document"].tolist()
    test_labels_kmhas = [map_kmhas(lb) for lb in df_test_kmhas["label"]]
    print(f"  test: {len(test_texts_kmhas)}개 (평가용)")

    # UnSmile (train)
    print("UnSmile 로딩...")
    parquet_train = hf_hub_download(
        "smilegate-ai/kor_unsmile",
        "data/train-00000-of-00001.parquet",
        repo_type="dataset"
    )
    df_unsmile = pd.read_parquet(parquet_train)
    text_col = df_unsmile.columns[0]  # 첫 번째 컬럼 = 문장
    for _, row in df_unsmile.iterrows():
        all_texts.append(row[text_col])
        all_labels.append(map_unsmile(row.to_dict()))
    print(f"  train: {len(df_unsmile)}개")

    # UnSmile valid (평가용)
    parquet_valid = hf_hub_download(
        "smilegate-ai/kor_unsmile",
        "data/valid-00000-of-00001.parquet",
        repo_type="dataset"
    )
    df_unsmile_val = pd.read_parquet(parquet_valid)
    test_texts_unsmile = df_unsmile_val[df_unsmile_val.columns[0]].tolist()
    test_labels_unsmile = [map_unsmile(row.to_dict()) for _, row in df_unsmile_val.iterrows()]
    print(f"  valid: {len(test_texts_unsmile)}개 (평가용)")

    # 학습 데이터 셔플
    combined = list(zip(all_texts, all_labels))
    np.random.seed(42)
    np.random.shuffle(combined)
    all_texts, all_labels = zip(*combined)

    # 학습/검증 분할 (95/5)
    split_idx = int(len(all_texts) * 0.95)
    train_texts, val_texts = list(all_texts[:split_idx]), list(all_texts[split_idx:])
    train_labels, val_labels = list(all_labels[:split_idx]), list(all_labels[split_idx:])

    print(f"\n총 학습: {len(train_texts):,}개 / 검증: {len(val_texts):,}개")
    print(f"K-MHaS test: {len(test_texts_kmhas):,}개 / UnSmile valid: {len(test_texts_unsmile):,}개")

    # 라벨 분포
    train_arr = np.array(train_labels)
    for i, name in enumerate(LABEL_NAMES):
        c = train_arr[:, i].sum()
        print(f"  {name}: {c:,} ({c/len(train_arr)*100:.1f}%)")

    return (train_texts, train_labels, val_texts, val_labels,
            test_texts_kmhas, test_labels_kmhas,
            test_texts_unsmile, test_labels_unsmile)


# ── 학습 ─────────────────────────────────────────────
def train(args):
    print(f"Device: {DEVICE}")
    print(f"Base model: {BASE_MODEL}")
    print(f"Epochs: {args.epochs}, LR: {args.lr}, Batch: {args.batch_size}\n")

    # 데이터 로드
    (train_texts, train_labels, val_texts, val_labels,
     test_texts_kmhas, test_labels_kmhas,
     test_texts_unsmile, test_labels_unsmile) = load_data()

    # 토크나이저 & 모델
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForSequenceClassification.from_pretrained(
        BASE_MODEL, num_labels=3, problem_type="multi_label_classification"
    ).to(DEVICE)

    # DataLoader
    train_ds = TextDataset(train_texts, train_labels, tokenizer)
    val_ds = TextDataset(val_texts, val_labels, tokenizer)
    train_loader = DataLoader(train_ds, batch_size=args.batch_size, shuffle=True, num_workers=0)
    val_loader = DataLoader(val_ds, batch_size=args.batch_size * 2, num_workers=0)

    # Optimizer & Scheduler
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    total_steps = len(train_loader) * args.epochs
    scheduler = get_linear_schedule_with_warmup(
        optimizer, num_warmup_steps=int(total_steps * 0.1), num_training_steps=total_steps
    )

    best_f1 = 0
    for epoch in range(args.epochs):
        # Train
        model.train()
        total_loss = 0
        t0 = time.time()

        for step, batch in enumerate(train_loader):
            input_ids = batch["input_ids"].to(DEVICE)
            attention_mask = batch["attention_mask"].to(DEVICE)
            labels = batch["labels"].to(DEVICE)

            outputs = model(input_ids=input_ids, attention_mask=attention_mask, labels=labels)
            loss = outputs.loss

            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad()

            total_loss += loss.item()

            if step % 100 == 0:
                print(f"  Epoch {epoch+1}/{args.epochs} | Step {step}/{len(train_loader)} | "
                      f"Loss: {loss.item():.4f}", flush=True)

        avg_loss = total_loss / len(train_loader)
        elapsed = time.time() - t0
        print(f"  Epoch {epoch+1} 완료: avg_loss={avg_loss:.4f}, {elapsed:.0f}초")

        # Validate
        model.eval()
        all_preds = []
        all_labels_val = []

        with torch.no_grad():
            for batch in val_loader:
                input_ids = batch["input_ids"].to(DEVICE)
                attention_mask = batch["attention_mask"].to(DEVICE)
                labels = batch["labels"]

                outputs = model(input_ids=input_ids, attention_mask=attention_mask)
                probs = torch.sigmoid(outputs.logits).cpu().numpy()
                preds = (probs >= 0.5).astype(int)

                all_preds.extend(preds)
                all_labels_val.extend(labels.numpy())

        all_preds = np.array(all_preds)
        all_labels_val = np.array(all_labels_val)

        macro_f1 = f1_score(all_labels_val, all_preds, average="macro", zero_division=0)
        print(f"  Val macro-F1: {macro_f1:.4f}")

        for i, name in enumerate(LABEL_NAMES):
            f1 = f1_score(all_labels_val[:, i], all_preds[:, i], zero_division=0)
            print(f"    {name}: F1={f1:.4f}")

        # Best model 저장
        if macro_f1 > best_f1:
            best_f1 = macro_f1
            os.makedirs(OUTPUT_DIR, exist_ok=True)
            model.save_pretrained(OUTPUT_DIR)
            tokenizer.save_pretrained(OUTPUT_DIR)
            print(f"  -> Best model 저장 (F1={best_f1:.4f})")

        print()

    print(f"학습 완료! Best macro-F1: {best_f1:.4f}")
    print(f"모델 저장 위치: {OUTPUT_DIR}")

    # 최종 평가 (K-MHaS test)
    print("\n=== K-MHaS Test Set 최종 평가 ===")
    evaluate_on(model, tokenizer, test_texts_kmhas, test_labels_kmhas, "K-MHaS")

    print("\n=== UnSmile Valid Set 최종 평가 ===")
    evaluate_on(model, tokenizer, test_texts_unsmile, test_labels_unsmile, "UnSmile")


def evaluate_on(model, tokenizer, texts, labels, name):
    """평가셋에서 성능 측정"""
    model.eval()
    ds = TextDataset(texts, labels, tokenizer)
    loader = DataLoader(ds, batch_size=64, num_workers=0)

    all_preds = []
    all_probs = []
    with torch.no_grad():
        for batch in loader:
            input_ids = batch["input_ids"].to(DEVICE)
            attention_mask = batch["attention_mask"].to(DEVICE)
            outputs = model(input_ids=input_ids, attention_mask=attention_mask)
            probs = torch.sigmoid(outputs.logits).cpu().numpy()
            all_preds.extend((probs >= 0.5).astype(int))
            all_probs.extend(probs)

    all_preds = np.array(all_preds)
    all_labels = np.array(labels)

    print(classification_report(all_labels, all_preds, target_names=LABEL_NAMES, zero_division=0))

    from sklearn.metrics import roc_auc_score
    for i, lname in enumerate(LABEL_NAMES):
        if len(np.unique(all_labels[:, i])) > 1:
            auc = roc_auc_score(all_labels[:, i], np.array(all_probs)[:, i])
            print(f"  {lname} ROC-AUC: {auc:.4f}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--batch_size", type=int, default=16)
    args = parser.parse_args()

    train(args)
