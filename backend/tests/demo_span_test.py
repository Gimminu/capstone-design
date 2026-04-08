"""문장 분류 + Span 추출 비대화형 테스트 (새 파이프라인)"""

import os, sys

_HERE = os.path.abspath(os.path.dirname(__file__))
_BACKEND = os.path.join(_HERE, "..")
sys.path.insert(0, os.path.join(_BACKEND, "api"))

from pipeline import ProfanityPipeline

BASE = _BACKEND
CLF_MODEL_DIR  = os.path.join(BASE, "models", "v2")
SPAN_MODEL_DIR = os.path.join(BASE, "models", "span_large_combined_crf")


def test(pipeline, text, expected_offensive=None):
    """단일 문장 테스트. expected_offensive: True/False/None(미지정)."""
    result = pipeline.analyze(text)

    labels = []
    if result["is_profane"]:
        labels.append("욕설")
    if result["is_toxic"]:
        labels.append("공격")
    if result["is_hate"]:
        labels.append("혐오")

    is_offensive = result["is_profane"] or result["is_toxic"] or result["is_hate"]

    # 정답 비교
    status = ""
    if expected_offensive is not None:
        if is_offensive == expected_offensive:
            status = " ✓"
        else:
            status = " ✗ (예상과 다름)"

    scores = result["scores"]
    print(f"\n입력: \"{text}\"{status}")
    print(f"  분류: {', '.join(labels) if labels else '정상'}")
    print(f"  점수: P={scores['profanity']:.3f}  "
          f"T={scores['toxicity']:.3f}  "
          f"H={scores['hate']:.3f}")

    if result["evidence_spans"]:
        for s in result["evidence_spans"]:
            print(f"  추출: \"{s['text']}\" [{s['start']}:{s['end']}] (신뢰도={s['score']:.3f})")
    else:
        print(f"  추출: (없음)")


if __name__ == "__main__":
    print("모델 로딩 중...")
    pipeline = ProfanityPipeline(
        classifier_path=CLF_MODEL_DIR,
        span_model_path=SPAN_MODEL_DIR,
    )
    print("로딩 완료!\n")

    print("=" * 60)
    print("1. 욕설/혐오 문장 (span 검출 기대)")
    print("=" * 60)
    offensive_cases = [
        "병신아 꺼져",
        "시발 뭐하는 거야 진짜",
        "너 같은 쓰레기는 죽어야 해",
        "ㅅㅂ 개같네 ㅋㅋ",
        "야 이 미친놈아",
        "닥쳐 꺼져 병신",
        "한남충 페미충 다 똑같아",
    ]
    for text in offensive_cases:
        test(pipeline, text, expected_offensive=True)

    print("\n" + "=" * 60)
    print("2. 정상 문장 (span 없어야 함)")
    print("=" * 60)
    normal_cases = [
        "이 영상 진짜 좋다",
        "오늘 날씨 좋네요",
        "감사합니다 좋은 정보네요",
        "떡꼬치 맛있겠다",
        "시베리아 허스키같네",
        "병원 가야 하는데",
        "오늘 시발점이 어디야?",
    ]
    for text in normal_cases:
        test(pipeline, text, expected_offensive=False)

    print("\n" + "=" * 60)
    print("3. 경계 케이스")
    print("=" * 60)
    edge_cases = [
        "바보",
        "멍청이",
        "asdfghjkl",
        "ㅋㅋㅋㅋㅋ",
        "ㅁㄴㅇㄹ",
    ]
    for text in edge_cases:
        test(pipeline, text)

    print("\n" + "=" * 60)
    print("테스트 완료")
