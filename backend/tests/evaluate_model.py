import torch
import numpy as np
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from sklearn.metrics import classification_report, confusion_matrix, roc_auc_score
import time

MODEL_PATH = "./models"
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

# 수동 테스트 데이터셋
# 각 샘플: (텍스트, [비속어, 공격성, 혐오])  1=해당, 0=비해당
test_data = [
    # === 비속어 (Profanity) ===
    ("씨발 진짜 짜증나네", [1, 0, 0]),
    ("개새끼 뭐하는 거야", [1, 1, 0]),
    ("지랄하고 있네", [1, 0, 0]),
    ("병신같은 소리 하지 마", [1, 1, 0]),
    ("ㅅㅂ 이게 뭐야", [1, 0, 0]),
    ("존나 웃기네", [1, 0, 0]),
    ("미친놈이 또 시작이야", [1, 1, 0]),
    ("꺼져 이 쓰레기야", [1, 1, 0]),
    ("닥쳐 좀", [1, 1, 0]),
    ("뒤질래?", [1, 1, 0]),

    # === 공격성 (Aggressiveness) ===
    ("너 한번만 더 그러면 죽여버린다", [0, 1, 0]),
    ("입 다물어 안 그러면 가만 안 둬", [0, 1, 0]),
    ("네가 뭔데 나한테 이래라 저래라야", [0, 1, 0]),
    ("다음에 또 이러면 진짜 각오해", [0, 1, 0]),
    ("그따위로 할 거면 그만둬라", [0, 1, 0]),
    ("너 때문에 다 망했어", [0, 1, 0]),
    ("그렇게 살다가 큰일 난다", [0, 1, 0]),
    ("제발 사라져 줄래", [0, 1, 0]),

    # === 혐오 (Hate speech) ===
    ("한남충은 답이 없다", [0, 0, 1]),
    ("여자가 무슨 운전을 해", [0, 0, 1]),
    ("이슬람은 다 테러리스트", [0, 0, 1]),
    ("장애인이 뭘 안다고", [0, 0, 1]),
    ("틀딱들은 버스에서 내려라", [0, 0, 1]),
    ("외노자들 다 추방해야 해", [0, 0, 1]),
    ("맘충들 때문에 카페 못 가겠다", [0, 0, 1]),
    ("동남아 출신은 믿을 수 없어", [0, 0, 1]),

    # === 복합 (비속어 + 공격성 + 혐오) ===
    ("씨발 한남충 죽어라", [1, 1, 1]),
    ("개같은 틀딱 꺼져", [1, 1, 1]),
    ("병신 같은 외노자 나가라", [1, 1, 1]),

    # === 정상 문장 ===
    ("오늘 날씨가 참 좋다", [0, 0, 0]),
    ("점심 뭐 먹을까", [0, 0, 0]),
    ("이 영화 진짜 재미있었어", [0, 0, 0]),
    ("내일 시험 잘 봐!", [0, 0, 0]),
    ("같이 카페 갈래?", [0, 0, 0]),
    ("프로젝트 마감일이 다가오네", [0, 0, 0]),
    ("주말에 부산 여행 갈 예정이야", [0, 0, 0]),
    ("고마워 덕분에 많이 배웠어", [0, 0, 0]),
    ("수고하셨습니다", [0, 0, 0]),
    ("이 책 추천할게 정말 좋아", [0, 0, 0]),
    ("운동하니까 기분이 좋아졌어", [0, 0, 0]),
    ("생일 축하해!", [0, 0, 0]),
]


def main():
    print(f"Device: {DEVICE}")
    print(f"테스트 샘플 수: {len(test_data)}\n")

    print("모델 로딩 중...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_PATH).to(DEVICE)
    model.eval()

    label_names = ["비속어(P)", "공격성(A)", "혐오(H)"]

    all_preds = []
    all_probs = []
    all_labels = []

    print("추론 중...\n")
    start_time = time.time()

    for text, labels in test_data:
        inputs = tokenizer(text, return_tensors="pt", truncation=True, max_length=128).to(DEVICE)
        with torch.no_grad():
            logits = model(**inputs).logits
            probs = torch.sigmoid(logits).cpu().numpy()[0]

        preds = (probs >= 0.5).astype(int)
        all_preds.append(preds)
        all_probs.append(probs)
        all_labels.append(labels)

    elapsed = time.time() - start_time
    print(f"추론 완료: {elapsed:.2f}초 ({elapsed/len(test_data)*1000:.1f}ms/샘플)\n")

    all_preds = np.array(all_preds)
    all_probs = np.array(all_probs)
    all_labels = np.array(all_labels)

    # === 개별 샘플 결과 출력 ===
    print("=" * 80)
    print("개별 샘플 결과")
    print("=" * 80)
    for i, (text, labels) in enumerate(test_data):
        pred = all_preds[i]
        prob = all_probs[i]
        match = "O" if np.array_equal(pred, labels) else "X"
        prob_str = " | ".join([f"{label_names[j]}:{prob[j]*100:5.1f}%" for j in range(3)])
        gt_str = str(labels)
        pred_str = str(pred.tolist())
        print(f"[{match}] \"{text[:30]:<30s}\" GT={gt_str} Pred={pred_str}  ({prob_str})")

    # === 라벨별 Classification Report ===
    print("\n" + "=" * 80)
    print("라벨별 Classification Report (threshold=0.5)")
    print("=" * 80)
    for i, name in enumerate(label_names):
        print(f"\n--- {name} ---")
        print(classification_report(
            all_labels[:, i], all_preds[:, i],
            target_names=["정상", name], zero_division=0
        ))

    # === 전체 Multi-label Metrics ===
    print("=" * 80)
    print("전체 Multi-label Metrics")
    print("=" * 80)
    print(classification_report(
        all_labels, all_preds,
        target_names=label_names, zero_division=0
    ))

    # === Subset Accuracy (완전 일치율) ===
    exact_match = np.all(all_preds == all_labels, axis=1).mean()
    print(f"Subset Accuracy (완전 일치율): {exact_match*100:.1f}%")

    # === ROC-AUC ===
    print("\n라벨별 ROC-AUC:")
    for i, name in enumerate(label_names):
        if len(np.unique(all_labels[:, i])) > 1:
            auc = roc_auc_score(all_labels[:, i], all_probs[:, i])
            print(f"  {name}: {auc:.4f}")
        else:
            print(f"  {name}: N/A (단일 클래스)")

    # === Confusion Matrix ===
    print("\n라벨별 Confusion Matrix [TN, FP / FN, TP]:")
    for i, name in enumerate(label_names):
        cm = confusion_matrix(all_labels[:, i], all_preds[:, i])
        print(f"\n  {name}:")
        print(f"    TN={cm[0][0]:3d}  FP={cm[0][1]:3d}")
        print(f"    FN={cm[1][0]:3d}  TP={cm[1][1]:3d}")

    # === Threshold 민감도 분석 ===
    print("\n" + "=" * 80)
    print("Threshold 민감도 분석")
    print("=" * 80)
    for threshold in [0.3, 0.4, 0.5, 0.6, 0.7]:
        preds_t = (all_probs >= threshold).astype(int)
        exact = np.all(preds_t == all_labels, axis=1).mean()
        print(f"  threshold={threshold:.1f} -> Subset Accuracy: {exact*100:.1f}%")


if __name__ == "__main__":
    main()
