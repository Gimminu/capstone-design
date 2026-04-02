import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
from inko import Inko 

MODEL_PATH = "./models"
DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")

def main():
    inko = Inko()
    
    tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
    model = AutoModelForSequenceClassification.from_pretrained(MODEL_PATH).to(DEVICE)
    model.eval()

    label_names = ["비속어(P)", "공격성(A)", "혐오(H)"]

    print("--- 🛡️ v4 멀티 라벨 필터 테스트 (영타 대응 버전) ---")
    print("(종료하려면 'q'를 입력하세요)")

    while True:
        original_text = input("\n입력: ").strip()
        if original_text.lower() == 'q': break
        if not original_text: continue
        
        converted_text = inko.en2ko(original_text)
        
        if original_text != converted_text:
            print(f"🔍 변환 감지: {original_text} -> {converted_text}")
            input_text = converted_text
        else:
            input_text = original_text
        
        inputs = tokenizer(input_text, return_tensors="pt", truncation=True, max_length=128).to(DEVICE)
        
        with torch.no_grad():
            logits = model(**inputs).logits
            probs = torch.sigmoid(logits).cpu().numpy()[0]
        
        detected = []
        for i, p in enumerate(probs):
            if p >= 0.5:
                detected.append(f"{label_names[i]} ({p*100:.2f}%)")
        
        if detected:
            status = "🚨 감지됨"
            detail = " | ".join(detected)
            print(f"결과: {status} -> {detail}")
        else:
            max_p = max(probs) * 100
            print(f"결과: ✅ 정상 (최대 유해 확률: {max_p:.2f}%)")

if __name__ == "__main__":
    main()