"""
모델 평가 스크립트
- 주 평가셋: K-MHaS (jeanlee/kmhas_korean_hate_speech) test split (21,939개)
- 보조 검증셋: UnSmile (smilegate-ai/kor_unsmile) valid split (3,737개)

라벨 매핑:
  [비속어(P), 공격성(A)*, 혐오(H)]
  * 공격성은 직접 라벨이 아닌 유도 라벨임 (아래 매핑표 참고)

K-MHaS 매핑:
  비속어 <- label 3 (혐오욕설)               [직접]
  공격성 <- label != 8 (해당사항없음이 아닌 모든 것) [유도]
  혐오   <- label 0,1,2,4,5,6,7 (차별 카테고리)  [직접]

UnSmile 매핑:
  비속어 <- '악플/욕설' 컬럼                  [직접]
  공격성 <- clean이 아닌 모든 것               [유도]
  혐오   <- 7개 타겟그룹 + '기타 혐오' 중 하나라도 1 [직접]
"""

import torch
import numpy as np
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from huggingface_hub import hf_hub_download
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score
import pandas as pd
import time

MODEL_PATH = "./models"
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

LABEL_NAMES = ["비속어(P)", "공격성(A)*", "혐오(H)"]


# ── K-MHaS 매핑 ──────────────────────────────────────────────
def map_kmhas(labels):
    """K-MHaS 라벨 -> [비속어, 공격성*, 혐오]"""
    p, a, h = 0, 0, 0
    for lb in (labels if isinstance(labels, list) else [labels]):
        if lb == 3: p = 1
        if lb != 8: a = 1
        if lb in [0, 1, 2, 4, 5, 6, 7]: h = 1
    return [p, a, h]


# ── UnSmile 매핑 ─────────────────────────────────────────────
UNSMILE_HATE_COLS = [
    "여성/가족", "남성", "성소수자", "인종/국적", "연령", "지역", "종교", "기타 혐오"
]

def map_unsmile(row):
    """UnSmile row -> [비속어, 공격성*, 혐오]"""
    p = int(row.get("악플/욕설", 0))
    h = int(any(row.get(c, 0) for c in UNSMILE_HATE_COLS))
    a = int(row.get("clean", 0) == 0)  # clean이 아니면 공격성
    return [p, a, h]


# ── 공통 평가 함수 ────────────────────────────────────────────
def evaluate(model, tokenizer, texts, gt_labels, dataset_name):
    print(f"\n{'=' * 70}")
    print(f"  {dataset_name} 평가 결과")
    print(f"  * 공격성(A)은 직접 라벨이 아닌 유도 라벨입니다")
    print(f"{'=' * 70}")

    gt_labels = np.array(gt_labels)

    # GT 분포
    print(f"\n[GT 라벨 분포] (총 {len(gt_labels)}개)")
    for i, name in enumerate(LABEL_NAMES):
        c = gt_labels[:, i].sum()
        print(f"  {name}: {c:,} ({c/len(gt_labels)*100:.1f}%)")
    clean = np.all(gt_labels == 0, axis=1).sum()
    print(f"  정상: {clean:,} ({clean/len(gt_labels)*100:.1f}%)")

    # 배치 추론
    print(f"\n추론 중... ({len(texts):,}개)")
    all_probs = []
    batch_size = 32
    t0 = time.time()

    for i in range(0, len(texts), batch_size):
        batch = texts[i:i+batch_size]
        inputs = tokenizer(batch, return_tensors="pt", truncation=True,
                          max_length=128, padding=True).to(DEVICE)
        with torch.no_grad():
            probs = torch.sigmoid(model(**inputs).logits).cpu().numpy()
        all_probs.append(probs)
        if (i // batch_size) % 20 == 0:
            print(f"  {min(i+batch_size, len(texts)):,}/{len(texts):,}", flush=True)

    elapsed = time.time() - t0
    print(f"완료: {elapsed:.1f}초 ({elapsed/len(texts)*1000:.1f}ms/샘플)")

    all_probs = np.concatenate(all_probs, axis=0)
    all_preds = (all_probs >= 0.5).astype(int)

    # 라벨별 리포트
    print(f"\n[라벨별 Classification Report] (threshold=0.5)")
    for i, name in enumerate(LABEL_NAMES):
        suffix = " (유도 라벨)" if i == 1 else ""
        print(f"\n--- {name}{suffix} ---")
        print(classification_report(
            gt_labels[:, i], all_preds[:, i],
            target_names=["정상", name], zero_division=0
        ))

    # Multi-label 전체
    print(f"[전체 Multi-label Metrics]")
    print(classification_report(
        gt_labels, all_preds, target_names=LABEL_NAMES, zero_division=0
    ))

    # Subset Accuracy
    exact = np.all(all_preds == gt_labels, axis=1).mean()
    print(f"Subset Accuracy (완전 일치율): {exact*100:.1f}%")

    # ROC-AUC
    print(f"\n[ROC-AUC]")
    for i, name in enumerate(LABEL_NAMES):
        if len(np.unique(gt_labels[:, i])) > 1:
            auc = roc_auc_score(gt_labels[:, i], all_probs[:, i])
            print(f"  {name}: {auc:.4f}")
        else:
            print(f"  {name}: N/A")

    # Confusion Matrix
    print(f"\n[Confusion Matrix]")
    for i, name in enumerate(LABEL_NAMES):
        cm = confusion_matrix(gt_labels[:, i], all_preds[:, i])
        tn, fp, fn, tp = cm.ravel() if cm.size == 4 else (cm[0][0], 0, 0, 0)
        recall = tp/(tp+fn)*100 if (tp+fn) > 0 else 0
        prec = tp/(tp+fp)*100 if (tp+fp) > 0 else 0
        print(f"  {name}: TN={tn:,} FP={fp:,} FN={fn:,} TP={tp:,} "
              f"(Recall={recall:.1f}%, Precision={prec:.1f}%)")

    # Threshold 분석
    print(f"\n[Threshold 민감도 분석]")
    print(f"  {'t':>4s} | {'Exact':>6s} | {'F1-P':>6s} {'F1-A*':>6s} {'F1-H':>6s} | {'macro':>6s}")
    print(f"  {'-'*4}-+-{'-'*6}-+-{'-'*6}-{'-'*6}-{'-'*6}-+-{'-'*6}")
    for t in [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
        pt = (all_probs >= t).astype(int)
        ex = np.all(pt == gt_labels, axis=1).mean()
        f1s = []
        for j in range(3):
            tp = ((pt[:, j] == 1) & (gt_labels[:, j] == 1)).sum()
            fp = ((pt[:, j] == 1) & (gt_labels[:, j] == 0)).sum()
            fn = ((pt[:, j] == 0) & (gt_labels[:, j] == 1)).sum()
            pr = tp/(tp+fp) if (tp+fp) else 0
            rc = tp/(tp+fn) if (tp+fn) else 0
            f1s.append(2*pr*rc/(pr+rc) if (pr+rc) else 0)
        print(f"  {t:.1f}  | {ex*100:5.1f}% | {f1s[0]:.4f} {f1s[1]:.4f} {f1s[2]:.4f} | {np.mean(f1s):.4f}")

    # 확률 분포
    print(f"\n[확률 분포 (양성 vs 음성)]")
    for i, name in enumerate(LABEL_NAMES):
        pos = gt_labels[:, i] == 1
        neg = ~pos
        pm = all_probs[pos, i].mean() if pos.sum() else 0
        nm = all_probs[neg, i].mean() if neg.sum() else 0
        print(f"  {name}: 양성 mean={pm:.4f}(n={pos.sum():,}) | 음성 mean={nm:.4f}(n={neg.sum():,})")

    return all_probs, all_preds, gt_labels


def main():
    print(f"Device: {DEVICE}\n")

    # 모델 로드
    print("모델 로딩 중...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_PATH).to(DEVICE)
    model.eval()

    # ── 1) K-MHaS (주 평가셋) ────────────────────────────────
    print("\n[1/2] K-MHaS 로딩 (로컬 CSV)...")
    df_kmhas = pd.read_csv("kmhas_test.csv")
    print(f"  크기: {len(df_kmhas):,}개")

    kmhas_texts = df_kmhas["document"].tolist()
    kmhas_gt = []
    for raw in df_kmhas["label"]:
        labels = [int(x.strip()) for x in str(raw).split(",")]
        kmhas_gt.append(map_kmhas(labels))

    evaluate(model, tokenizer, kmhas_texts, kmhas_gt, "K-MHaS (주 평가셋)")

    # ── 2) UnSmile (보조 검증셋) ──────────────────────────────
    print("\n\n[2/2] UnSmile 로딩 (로컬 CSV)...")
    df_unsmile = pd.read_csv("unsmile_valid.csv")
    print(f"  크기: {len(df_unsmile):,}개")

    # 컬럼명 찾기 (인코딩 문제 대비)
    text_col = df_unsmile.columns[0]  # 첫 번째 컬럼 = 문장
    print(f"  텍스트 컬럼: '{text_col}'")

    unsmile_texts = df_unsmile[text_col].tolist()
    unsmile_gt = []
    for _, row in df_unsmile.iterrows():
        unsmile_gt.append(map_unsmile(row.to_dict()))

    evaluate(model, tokenizer, unsmile_texts, unsmile_gt, "UnSmile (보조 검증셋)")

    print("\n" + "=" * 70)
    print("평가 완료")
    print("=" * 70)


if __name__ == "__main__":
    main()
