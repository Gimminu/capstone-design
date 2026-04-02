"""
대화형 데모 — XAI 기반 욕설 탐지
모델이 분류 + 판단 근거 구간을 동시에 설명
"""
import json
import sys
import os
from pipeline import ProfanityPipeline


def run_interactive(pipe: ProfanityPipeline):
    print("--- 욕설 탐지 파이프라인 (XAI + Integrated Gradients) ---")
    print("명령어: 'q'=종료, 'all'=따오기 전체 분석, 'file <경로>'=파일 분석")
    print()

    while True:
        try:
            text = input("입력: ").strip()
        except (EOFError, KeyboardInterrupt):
            break

        if not text:
            continue
        if text.lower() == "q":
            break
        if text.lower() == "all":
            analyze_all_comments(pipe)
            continue
        if text.startswith("file "):
            analyze_comment_file(pipe, text[5:].strip())
            continue

        result = pipe.analyze(text)
        print_result(result)


def print_result(result: dict):
    flags = []
    if result["is_profane"]:
        flags.append("욕설")
    if result["is_toxic"]:
        flags.append("공격적")
    if result["is_hate"]:
        flags.append("혐오")

    s = result["scores"]

    if flags:
        print(f"  판정: {' | '.join(flags)}")

        # 판단 근거 구간 표시
        spans = result.get("evidence_spans", [])
        reliable = result.get("span_reliable", False)

        if spans and reliable:
            spans_str = ", ".join(
                f"\"{sp['text']}\"({sp['score']:.2f})"
                + (f" [{sp['start']}:{sp['end']}]" if "start" in sp else "")
                for sp in spans
            )
            print(f"  근거 구간: {spans_str}")
        elif spans:
            print(f"  근거 구간: (신뢰도 낮음 — 문장 전체 유해 판정)")
        else:
            print(f"  근거 구간: (모델이 문맥 전체를 보고 판단)")

        print(f"  점수: P={s['profanity']:.3f} T={s['toxicity']:.3f} H={s['hate']:.3f}")
    else:
        max_score = max(s.values()) * 100
        print(f"  판정: 정상 (최대 유해 확률: {max_score:.1f}%)")
    print()


def analyze_comment_file(pipe: ProfanityPipeline, path: str):
    if not os.path.exists(path):
        print(f"  파일 없음: {path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)

    comments = data.get("comments", [])
    if not comments:
        print("  댓글이 없습니다.")
        return

    print(f"\n  [{os.path.basename(path)}] {len(comments)}개 댓글 분석")
    print("  " + "-" * 60)

    flagged_count = 0
    for comment in comments:
        text = comment.get("commentText", "").strip()
        if not text:
            continue

        result = pipe.analyze(text)
        is_flagged = result["is_profane"] or result["is_toxic"] or result["is_hate"]

        if is_flagged:
            flagged_count += 1
            flags = []
            if result["is_profane"]: flags.append("욕설")
            if result["is_toxic"]: flags.append("공격적")
            if result["is_hate"]: flags.append("혐오")

            author = comment.get("author_id", "?")
            spans = result.get("evidence_spans", [])
            reliable = result.get("span_reliable", False)

            if spans and reliable:
                spans_str = ", ".join(f"\"{sp['text']}\"" for sp in spans)
            else:
                spans_str = "(문장 전체 판정)"

            print(f"  [{author}] {text}")
            print(f"    -> {' | '.join(flags)} | 근거: {spans_str}")

    print(f"\n  결과: {len(comments)}개 중 {flagged_count}개 유해 ({flagged_count/len(comments)*100:.1f}%)")
    print()


def analyze_all_comments(pipe: ProfanityPipeline, directory: str = "./따오기"):
    if not os.path.isdir(directory):
        print(f"디렉토리 없음: {directory}")
        return

    files = sorted(f for f in os.listdir(directory) if f.endswith(".json"))
    print(f"\n=== {directory} 전체 분석 ({len(files)}개 파일) ===\n")
    for fname in files:
        analyze_comment_file(pipe, os.path.join(directory, fname))


if __name__ == "__main__":
    pipe = ProfanityPipeline()

    if len(sys.argv) > 1:
        arg = sys.argv[1]
        if os.path.isdir(arg):
            analyze_all_comments(pipe, arg)
        elif os.path.isfile(arg):
            analyze_comment_file(pipe, arg)
        else:
            result = pipe.analyze(arg)
            print_result(result)
    else:
        run_interactive(pipe)
