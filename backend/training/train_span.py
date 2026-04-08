"""
Span 추출 모델 학습 (TokenClassification + Optional CRF)
- 모델: XLM-RoBERTa-base/large
- 라벨: O(0), B-OFF(1), I-OFF(2)
- Loss: Focal Loss (CRF 없을 때) / CRF NLL (CRF 있을 때)
- fp16 + GradScaler + gradient checkpointing (large 자동)
- Weighted Sampler: span 포함 샘플 2x, kold 소스 2x (K-HATERS 압도 방지)
- use_for_span=False 샘플: BIO loss 마스킹 (span 없는 offensive)
- Early Stopping (span F1 기준)
- Resume checkpoint 지원
- KOLD / K-HATERS test 분리 평가

사용법:
  cd src
  python train_span.py                                                 # base + kold
  python train_span.py --data combined                                 # base + combined
  python train_span.py --model xlm-roberta-large --data combined --use_crf
  python train_span.py --model xlm-roberta-large --data combined --use_crf --resume
"""
import argparse
import json
import os
import random
import time
from glob import glob

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader, WeightedRandomSampler
from transformers import (
    AutoTokenizer,
    AutoModel,
    AutoModelForTokenClassification,
    get_linear_schedule_with_warmup,
)

try:
    from torchcrf import CRF
    HAS_CRF = True
except ImportError:
    HAS_CRF = False

# ── 고정 설정 ─────────────────────────────────────────────────
MAX_LENGTH = 128
DEVICE     = torch.device("cuda" if torch.cuda.is_available() else "cpu")
LABEL2ID   = {"O": 0, "B-OFF": 1, "I-OFF": 2}
ID2LABEL   = {0: "O", 1: "B-OFF", 2: "I-OFF"}
NUM_LABELS = 3
IGNORE_IDX = -100
SEED       = 42


def set_seed(seed: int):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    os.environ["CUBLAS_WORKSPACE_CONFIG"] = ":4096:8"
    torch.use_deterministic_algorithms(True, warn_only=True)


# ═══════════════════════════════════════════════════════════════
# 경로 / 배치 자동 결정
# ═══════════════════════════════════════════════════════════════

def resolve_paths(args) -> argparse.Namespace:
    prefix = args.data  # "kold" or "combined"
    if args.output_dir is None:
        size = "large" if "large" in args.model else "base"
        crf  = "_crf" if args.use_crf else ""
        args.output_dir = f"../models/span_{size}_{args.data}{crf}"
    args.train_path       = f"../data/train/{prefix}_train.jsonl"
    args.val_path         = f"../data/train/{prefix}_val.jsonl"
    args.test_path        = f"../data/train/{prefix}_test.jsonl"
    args.kold_test_path   = "../data/train/kold_test.jsonl"
    args.khaters_test_path = "../data/train/khaters_test.jsonl"
    return args


def resolve_batch(args) -> argparse.Namespace:
    if "large" in args.model:
        if args.batch_size == 16:
            args.batch_size = 4
            args.grad_accum = 8
            print("[auto] large 모델 → batch_size=4, grad_accum=8")
        if abs(args.lr - 2e-5) < 1e-10:
            args.lr = 1e-5
            print("[auto] large 모델 → lr=1e-5")
    return args


# ═══════════════════════════════════════════════════════════════
# Focal Loss (CRF 없을 때 사용)
# ═══════════════════════════════════════════════════════════════

class FocalLoss(nn.Module):
    """O 클래스 지배 문제 완화. gamma=2: 쉬운 토큰 기여도 자동 감소."""
    def __init__(self, gamma: float = 2.0, ignore_index: int = IGNORE_IDX):
        super().__init__()
        self.gamma        = gamma
        self.ignore_index = ignore_index

    def forward(self, logits: torch.Tensor, targets: torch.Tensor) -> torch.Tensor:
        ce = F.cross_entropy(
            logits.view(-1, logits.size(-1)),
            targets.view(-1),
            ignore_index=self.ignore_index,
            reduction="none",
        )
        pt   = torch.exp(-ce)
        loss = (1 - pt) ** self.gamma * ce
        mask = targets.view(-1) != self.ignore_index
        return loss[mask].mean()


# ═══════════════════════════════════════════════════════════════
# SpanCRF 모델 (CRF 있을 때 사용)
# ═══════════════════════════════════════════════════════════════

class SpanCRFModel(nn.Module):
    """XLM-RoBERTa + Linear projection + CRF."""
    def __init__(self, backbone: str, num_labels: int, dropout: float = 0.1):
        super().__init__()
        self.encoder    = AutoModel.from_pretrained(backbone)
        hidden          = self.encoder.config.hidden_size
        self.dropout    = nn.Dropout(dropout)
        self.classifier = nn.Linear(hidden, num_labels)
        self.crf        = CRF(num_tags=num_labels, batch_first=True)

    def forward(self, input_ids, attention_mask, labels=None):
        outputs   = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        emissions = self.classifier(self.dropout(outputs.last_hidden_state))

        if labels is not None:
            # IGNORE_IDX(-100) → 0(O) 으로 변환 후 attention_mask로 마스킹
            safe_labels = labels.clone()
            safe_labels[safe_labels == IGNORE_IDX] = 0
            crf_mask = attention_mask.bool()
            loss = -self.crf(emissions, safe_labels, mask=crf_mask, reduction="mean")
            return loss, emissions
        else:
            return self.crf.decode(emissions.float(), mask=attention_mask.bool())


# ═══════════════════════════════════════════════════════════════
# Dataset
# ═══════════════════════════════════════════════════════════════

class SpanDataset(Dataset):
    def __init__(self, path: str, tokenizer, max_length: int = MAX_LENGTH):
        self.tokenizer  = tokenizer
        self.max_length = max_length
        self.samples    = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                self.samples.append(json.loads(line))

    def __len__(self):
        return len(self.samples)

    def has_span(self, idx: int) -> bool:
        return bool(self.samples[idx].get("OFF_spans"))

    def get_source(self, idx: int) -> str:
        return self.samples[idx].get("source", "unknown")

    def __getitem__(self, idx):
        item    = self.samples[idx]
        comment = item["comment"]
        spans   = item.get("OFF_spans", [])

        enc = self.tokenizer(
            comment,
            truncation=True,
            max_length=self.max_length,
            padding="max_length",
            return_offsets_mapping=True,
            return_tensors="pt",
        )

        input_ids      = enc["input_ids"].squeeze(0)
        attention_mask = enc["attention_mask"].squeeze(0)
        offset_mapping = enc["offset_mapping"].squeeze(0)

        # use_for_span=False → BIO loss 계산 제외 (span 없는 offensive 샘플)
        if not item.get("use_for_span", True):
            labels = [IGNORE_IDX] * self.max_length
        else:
            labels = self._align_labels(offset_mapping, spans)

        return {
            "input_ids":      input_ids,
            "attention_mask": attention_mask,
            "labels":         torch.tensor(labels, dtype=torch.long),
        }

    def _align_labels(self, offset_mapping, spans) -> list[int]:
        """offset_mapping 기반 문자 단위 span → BIO 토큰 라벨 변환."""
        labels = []
        for char_start, char_end in offset_mapping:
            cs, ce = char_start.item(), char_end.item()

            if cs == 0 and ce == 0:      # 특수 토큰 / 패딩
                labels.append(IGNORE_IDX)
                continue

            label = LABEL2ID["O"]
            for span in spans:
                s, e = span["start"], span["end"]
                if cs >= s and ce <= e:  # 완전 포함
                    label = LABEL2ID["B-OFF"] if cs == s else LABEL2ID["I-OFF"]
                    break
                elif cs < e and ce > s:  # 경계 걸침 fallback
                    label = LABEL2ID["B-OFF"] if cs <= s else LABEL2ID["I-OFF"]
                    break

            labels.append(label)
        return labels


# ═══════════════════════════════════════════════════════════════
# Weighted Sampler
# ═══════════════════════════════════════════════════════════════

def make_weighted_sampler(dataset: SpanDataset) -> WeightedRandomSampler:
    """span 포함 샘플 2x, kold 소스 2x (K-HATERS 압도 방지)."""
    weights = []
    for i in range(len(dataset)):
        w = 1.0
        if dataset.has_span(i):
            w *= 2.0
        if dataset.get_source(i) == "kold":
            w *= 2.0
        weights.append(w)
    return WeightedRandomSampler(
        weights=weights,
        num_samples=len(dataset),
        replacement=True,
    )


# ═══════════════════════════════════════════════════════════════
# 평가 유틸
# ═══════════════════════════════════════════════════════════════

def extract_spans(labels: list[int]) -> list[tuple[int, int]]:
    spans, in_span, start = [], False, 0
    for i, lbl in enumerate(labels):
        if lbl == LABEL2ID["B-OFF"]:
            if in_span:
                spans.append((start, i))
            in_span, start = True, i
        elif lbl == LABEL2ID["I-OFF"]:
            if not in_span:
                in_span, start = True, i
        else:
            if in_span:
                spans.append((start, i))
            in_span = False
    if in_span:
        spans.append((start, len(labels)))
    return spans


def span_f1(gold_batch, pred_batch) -> dict:
    tp = fp = fn = 0
    for gold, pred in zip(gold_batch, pred_batch):
        g, p = set(gold), set(pred)
        tp += len(g & p); fp += len(p - g); fn += len(g - p)
    prec = tp / (tp + fp + 1e-9)
    rec  = tp / (tp + fn + 1e-9)
    f1   = 2 * prec * rec / (prec + rec + 1e-9)
    return {"precision": prec, "recall": rec, "f1": f1}


def token_f1(all_gold, all_pred) -> dict:
    tp = fp = fn = 0
    for g, p in zip(all_gold, all_pred):
        if g == IGNORE_IDX: continue
        go, po = g != LABEL2ID["O"], p != LABEL2ID["O"]
        if go and po:  tp += 1
        elif po:       fp += 1
        elif go:       fn += 1
    prec = tp / (tp + fp + 1e-9)
    rec  = tp / (tp + fn + 1e-9)
    f1   = 2 * prec * rec / (prec + rec + 1e-9)
    return {"precision": prec, "recall": rec, "f1": f1}


# ═══════════════════════════════════════════════════════════════
# 평가 루프
# ═══════════════════════════════════════════════════════════════

def evaluate(model, loader, criterion, use_crf: bool = False):
    model.eval()
    total_loss = 0
    all_gold_tok, all_pred_tok = [], []
    all_gold_spn, all_pred_spn = [], []

    with torch.no_grad():
        for batch in loader:
            input_ids      = batch["input_ids"].cuda()
            attention_mask = batch["attention_mask"].cuda()
            labels         = batch["labels"].cuda()

            with torch.amp.autocast("cuda"):
                if use_crf:
                    loss, emissions = model(input_ids, attention_mask, labels)
                else:
                    outputs = model(input_ids=input_ids, attention_mask=attention_mask)
                    loss    = criterion(outputs.logits, labels)

            total_loss += loss.item()

            if use_crf:
                # decode는 autocast 밖에서 float32로 (수치 안정성)
                preds_list = model.crf.decode(
                    emissions.float(), mask=attention_mask.bool())
            else:
                preds_list = outputs.logits.argmax(dim=-1).cpu().tolist()

            golds    = labels.cpu().tolist()
            att_mask = attention_mask.cpu().tolist()

            if use_crf:
                # preds_list[i]: attention_mask=1인 토큰만 (유효 토큰 수)
                # → full-length로 복원 후 gold와 비교
                for g_seq, p_valid, mask_row in zip(golds, preds_list, att_mask):
                    p_full, vi = [], 0
                    for m in mask_row:
                        if m:
                            p_full.append(p_valid[vi] if vi < len(p_valid) else 0)
                            vi += 1
                        else:
                            p_full.append(0)
                    all_gold_tok.extend(g_seq)
                    all_pred_tok.extend(p_full)
                    g_clean = [l for l in g_seq if l != IGNORE_IDX]
                    p_clean = [p for p, l in zip(p_full, g_seq) if l != IGNORE_IDX]
                    all_gold_spn.append(extract_spans(g_clean))
                    all_pred_spn.append(extract_spans(p_clean))
            else:
                for g_seq, p_seq in zip(golds, preds_list):
                    all_gold_tok.extend(g_seq)
                    all_pred_tok.extend(p_seq)
                    g_clean = [l for l in g_seq if l != IGNORE_IDX]
                    p_clean = [p_seq[i] for i, l in enumerate(g_seq) if l != IGNORE_IDX]
                    all_gold_spn.append(extract_spans(g_clean))
                    all_pred_spn.append(extract_spans(p_clean))

    return (
        total_loss / len(loader),
        token_f1(all_gold_tok, all_pred_tok),
        span_f1(all_gold_spn, all_pred_spn),
    )


# ═══════════════════════════════════════════════════════════════
# 모델 저장 / 체크포인트
# ═══════════════════════════════════════════════════════════════

def save_best_model(model, tokenizer, output_dir: str, use_crf: bool):
    os.makedirs(output_dir, exist_ok=True)
    if use_crf:
        enc_dir = os.path.join(output_dir, "encoder")
        model.encoder.save_pretrained(enc_dir)
        tokenizer.save_pretrained(enc_dir)
        torch.save({
            "classifier": model.classifier.state_dict(),
            "crf":        model.crf.state_dict(),
        }, os.path.join(output_dir, "extra_weights.pt"))
        with open(os.path.join(output_dir, "model_config.json"), "w") as f:
            json.dump({"use_crf": True, "num_labels": NUM_LABELS,
                       "label2id": LABEL2ID, "id2label": ID2LABEL},
                      f, ensure_ascii=False, indent=2)
    else:
        model.save_pretrained(output_dir)
        tokenizer.save_pretrained(output_dir)


def save_checkpoint(epoch, model, optimizer, scheduler, scaler,
                    best_f1, patience_cnt, ckpt_dir: str):
    os.makedirs(ckpt_dir, exist_ok=True)
    path = os.path.join(ckpt_dir, f"ckpt_epoch{epoch:02d}.pt")
    torch.save({
        "epoch":     epoch,
        "model":     model.state_dict(),
        "optimizer": optimizer.state_dict(),
        "scheduler": scheduler.state_dict(),
        "scaler":    scaler.state_dict(),
        "best_f1":   best_f1,
        "patience":  patience_cnt,
    }, path)
    # 최근 2개만 유지
    ckpts = sorted(glob(os.path.join(ckpt_dir, "ckpt_epoch*.pt")))
    for old in ckpts[:-2]:
        os.remove(old)


def load_checkpoint(ckpt_dir: str, model, optimizer, scheduler, scaler):
    ckpts = sorted(glob(os.path.join(ckpt_dir, "ckpt_epoch*.pt")))
    if not ckpts:
        print("체크포인트 없음, 처음부터 학습")
        return 0, 0.0, 0
    ckpt = torch.load(ckpts[-1], map_location="cuda")
    model.load_state_dict(ckpt["model"])
    optimizer.load_state_dict(ckpt["optimizer"])
    scheduler.load_state_dict(ckpt["scheduler"])
    scaler.load_state_dict(ckpt["scaler"])
    start_epoch = ckpt["epoch"] + 1
    print(f"[resume] epoch {start_epoch}부터 재개  "
          f"(best F1={ckpt['best_f1']:.4f}  patience={ckpt['patience']})")
    return start_epoch, ckpt["best_f1"], ckpt["patience"]


def load_best_model(output_dir: str, use_crf: bool):
    if use_crf:
        enc_dir = os.path.join(output_dir, "encoder")
        model   = SpanCRFModel(enc_dir, NUM_LABELS).cuda()
        extra   = torch.load(os.path.join(output_dir, "extra_weights.pt"),
                             map_location="cuda")
        model.classifier.load_state_dict(extra["classifier"])
        model.crf.load_state_dict(extra["crf"])
    else:
        model = AutoModelForTokenClassification.from_pretrained(output_dir).cuda()
    return model.eval()


# ═══════════════════════════════════════════════════════════════
# 학습 루프
# ═══════════════════════════════════════════════════════════════

def train(args):
    args = resolve_paths(args)
    args = resolve_batch(args)
    set_seed(SEED)

    if args.use_crf and not HAS_CRF:
        raise ImportError("CRF 사용하려면 먼저 설치: pip install pytorch-crf")

    print(f"Device         : {DEVICE}")
    print(f"Model          : {args.model}")
    print(f"Data           : {args.data}  →  {args.train_path}")
    print(f"CRF            : {args.use_crf}")
    print(f"Output         : {args.output_dir}")
    print(f"Epochs         : {args.epochs}  LR: {args.lr}  "
          f"Batch: {args.batch_size}  GradAccum: {args.grad_accum}")
    print(f"Effective batch: {args.batch_size * args.grad_accum}\n")

    tokenizer = AutoTokenizer.from_pretrained(args.model)

    train_ds = SpanDataset(args.train_path, tokenizer)
    val_ds   = SpanDataset(args.val_path,   tokenizer)
    test_ds  = SpanDataset(args.test_path,  tokenizer)

    sampler      = make_weighted_sampler(train_ds)
    train_loader = DataLoader(train_ds, batch_size=args.batch_size,
                              sampler=sampler, num_workers=0)
    val_loader   = DataLoader(val_ds,   batch_size=args.batch_size * 2, num_workers=0)
    test_loader  = DataLoader(test_ds,  batch_size=args.batch_size * 2, num_workers=0)

    print(f"Train: {len(train_ds):,}  Val: {len(val_ds):,}  Test: {len(test_ds):,}")

    # ── 모델 초기화 ────────────────────────────────────────────
    if args.use_crf:
        model     = SpanCRFModel(args.model, NUM_LABELS).cuda()
        criterion = None
        # 계층별 lr: backbone 낮게, CRF+classifier 높게 (새로 초기화된 레이어)
        optimizer = torch.optim.AdamW([
            {"params": model.encoder.parameters(),
             "lr": args.lr, "weight_decay": 0.01},
            {"params": list(model.classifier.parameters()) +
                       list(model.crf.parameters()),
             "lr": args.lr * 5, "weight_decay": 0.0},
        ], eps=1e-8)
    else:
        model = AutoModelForTokenClassification.from_pretrained(
            args.model,
            num_labels=NUM_LABELS,
            id2label=ID2LABEL,
            label2id=LABEL2ID,
        ).cuda()
        criterion = FocalLoss(gamma=2.0)
        optimizer = torch.optim.AdamW(
            model.parameters(), lr=args.lr, weight_decay=0.01)

    # large 모델: gradient checkpointing으로 VRAM 절약
    if "large" in args.model:
        encoder = model.encoder if hasattr(model, "encoder") else model
        encoder.gradient_checkpointing_enable()
        print("[auto] large 모델 → gradient checkpointing 활성화")

    scaler = torch.amp.GradScaler("cuda")

    total_steps  = (len(train_loader) // args.grad_accum) * args.epochs
    warmup_steps = int(total_steps * 0.06)
    scheduler = get_linear_schedule_with_warmup(
        optimizer,
        num_warmup_steps=warmup_steps,
        num_training_steps=total_steps,
    )
    print(f"Total steps: {total_steps}  Warmup: {warmup_steps}\n")

    # ── Resume ─────────────────────────────────────────────────
    ckpt_dir     = os.path.join(args.output_dir, "checkpoints")
    best_span_f1 = 0.0
    patience_cnt = 0
    start_epoch  = 0

    if args.resume:
        start_epoch, best_span_f1, patience_cnt = \
            load_checkpoint(ckpt_dir, model, optimizer, scheduler, scaler)

    for epoch in range(start_epoch, args.epochs):
        # ── Train ──────────────────────────────────────────────
        model.train()
        total_loss = 0
        optimizer.zero_grad()
        t0 = time.time()

        for step, batch in enumerate(train_loader):
            input_ids      = batch["input_ids"].cuda()
            attention_mask = batch["attention_mask"].cuda()
            labels         = batch["labels"].cuda()

            with torch.amp.autocast("cuda"):
                if args.use_crf:
                    loss, _ = model(input_ids, attention_mask, labels)
                else:
                    outputs = model(input_ids=input_ids, attention_mask=attention_mask)
                    loss    = criterion(outputs.logits, labels)
                loss = loss / args.grad_accum

            scaler.scale(loss).backward()

            if (step + 1) % args.grad_accum == 0:
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                scale_before = scaler.get_scale()
                scaler.step(optimizer)
                scaler.update()
                if scaler.get_scale() == scale_before:
                    scheduler.step()
                optimizer.zero_grad()

            total_loss += loss.item() * args.grad_accum

            if step % 200 == 0:
                print(f"  Epoch {epoch+1}/{args.epochs} | "
                      f"Step {step}/{len(train_loader)} | "
                      f"Loss: {loss.item() * args.grad_accum:.4f}", flush=True)

        avg_loss = total_loss / len(train_loader)
        elapsed  = time.time() - t0

        # ── Validate ───────────────────────────────────────────
        val_loss, tok, spn = evaluate(model, val_loader, criterion, args.use_crf)

        print(f"\nEpoch {epoch+1} | train_loss={avg_loss:.4f} | "
              f"val_loss={val_loss:.4f} | {elapsed:.0f}s")
        print(f"  Token F1 : P={tok['precision']:.4f}  "
              f"R={tok['recall']:.4f}  F1={tok['f1']:.4f}")
        print(f"  Span  F1 : P={spn['precision']:.4f}  "
              f"R={spn['recall']:.4f}  F1={spn['f1']:.4f}")

        # ── Best 저장 & Early Stopping ─────────────────────────
        if spn["f1"] > best_span_f1:
            best_span_f1 = spn["f1"]
            patience_cnt = 0
            save_best_model(model, tokenizer, args.output_dir, args.use_crf)
            print(f"  → Best 모델 저장 (Span F1={best_span_f1:.4f})")
        else:
            patience_cnt += 1
            print(f"  → 개선 없음 ({patience_cnt}/{args.patience})")
            if patience_cnt >= args.patience:
                print(f"\nEarly stopping! (patience={args.patience})")
                save_checkpoint(epoch, model, optimizer, scheduler, scaler,
                                best_span_f1, patience_cnt, ckpt_dir)
                break

        save_checkpoint(epoch, model, optimizer, scheduler, scaler,
                        best_span_f1, patience_cnt, ckpt_dir)
        print()

    # ── 최종 Test 평가 ─────────────────────────────────────────
    print(f"\n학습 완료! Best Span F1: {best_span_f1:.4f}")
    best_model = load_best_model(args.output_dir, args.use_crf)

    def _print_eval(name, loader):
        _, tok, spn = evaluate(best_model, loader, criterion, args.use_crf)
        print(f"  Token F1 : P={tok['precision']:.4f}  "
              f"R={tok['recall']:.4f}  F1={tok['f1']:.4f}")
        print(f"  Span  F1 : P={spn['precision']:.4f}  "
              f"R={spn['recall']:.4f}  F1={spn['f1']:.4f}")

    print(f"\n=== Test Set 평가 ({args.data}) ===")
    _print_eval(args.data, test_loader)

    if os.path.exists(args.kold_test_path):
        kold_test_ds = SpanDataset(args.kold_test_path, tokenizer)
        kold_loader  = DataLoader(kold_test_ds,
                                  batch_size=args.batch_size * 2, num_workers=0)
        print("\n=== KOLD Test Set 평가 ===")
        _print_eval("kold", kold_loader)

    if args.data == "combined" and os.path.exists(args.khaters_test_path):
        kh_test_ds = SpanDataset(args.khaters_test_path, tokenizer)
        kh_loader  = DataLoader(kh_test_ds,
                                batch_size=args.batch_size * 2, num_workers=0)
        print("\n=== K-HATERS Test Set 평가 ===")
        _print_eval("khaters", kh_loader)

    print(f"\n모델 저장 위치: {args.output_dir}")


# ═══════════════════════════════════════════════════════════════
# 진입점
# ═══════════════════════════════════════════════════════════════

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--model",      type=str,   default="xlm-roberta-base",
                        choices=["xlm-roberta-base", "xlm-roberta-large"])
    parser.add_argument("--data",       type=str,   default="kold",
                        choices=["kold", "combined"])
    parser.add_argument("--use_crf",    action="store_true")
    parser.add_argument("--output_dir", type=str,   default=None)
    parser.add_argument("--resume",     action="store_true")
    parser.add_argument("--epochs",     type=int,   default=10)
    parser.add_argument("--lr",         type=float, default=2e-5)
    parser.add_argument("--batch_size", type=int,   default=16)
    parser.add_argument("--grad_accum", type=int,   default=2)
    parser.add_argument("--patience",   type=int,   default=3)
    args = parser.parse_args()
    train(args)
