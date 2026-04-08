"""
모델 weights 다운로드 스크립트

사용법:
  pip install gdown
  python scripts/download_models.py

모델 파일은 .gitignore 대상이므로 최초 세팅 시 반드시 실행해야 합니다.
"""
import os
import sys

# TODO: 실제 Google Drive 폴더 ID로 교체
CLASSIFIER_DRIVE_ID = "YOUR_CLASSIFIER_FOLDER_ID"
SPAN_MODEL_DRIVE_ID = "YOUR_SPAN_MODEL_FOLDER_ID"

BASE = os.path.join(os.path.dirname(__file__), "..")
MODELS_DIR = os.path.join(BASE, "models")


def download():
    try:
        import gdown
    except ImportError:
        print("gdown이 설치되어 있지 않습니다.")
        print("  pip install gdown")
        sys.exit(1)

    # 분류 모델 (v2)
    clf_dir = os.path.join(MODELS_DIR, "v2")
    if not os.path.exists(os.path.join(clf_dir, "model.safetensors")):
        print("분류 모델 다운로드 중...")
        os.makedirs(clf_dir, exist_ok=True)
        gdown.download_folder(id=CLASSIFIER_DRIVE_ID, output=clf_dir, quiet=False)
        print("분류 모델 다운로드 완료")
    else:
        print("분류 모델이 이미 존재합니다. 건너뜀.")

    # Span CRF 모델
    span_dir = os.path.join(MODELS_DIR, "span_large_combined_crf")
    if not os.path.exists(os.path.join(span_dir, "extra_weights.pt")):
        print("Span 모델 다운로드 중...")
        os.makedirs(span_dir, exist_ok=True)
        gdown.download_folder(id=SPAN_MODEL_DRIVE_ID, output=span_dir, quiet=False)
        print("Span 모델 다운로드 완료")
    else:
        print("Span 모델이 이미 존재합니다. 건너뜀.")

    print("\n모든 모델 준비 완료!")
    print(f"  분류 모델: {clf_dir}")
    print(f"  Span 모델: {span_dir}")


if __name__ == "__main__":
    download()
