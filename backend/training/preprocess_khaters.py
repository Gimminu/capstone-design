"""
K-HATERS 전처리 파이프라인
HuggingFace Hub (humane-lab/K-HATERS) → KOLD 호환 JSONL 변환

라벨 매핑:
  L2_hate / L1_hate / offensive → OFF="OFF"
  normal                         → OFF="NOT"

스팬: offensiveness_rationale = [[start, end], ...] (Python slice 방식)

사용법:
  cd src
  python preprocess_khaters.py

출력:
  data/train/khaters_train.jsonl
  data/train/khaters_val.jsonl
  data/train/khaters_test.jsonl
  data/train/combined_train.jsonl  ← KOLD + K-HATERS
  data/train/combined_val.jsonl
  data/train/combined_test.jsonl
"""
import json
import re
import unicodedata
from collections import Counter
from pathlib import Path

from datasets import load_dataset

# ── 경로 (src/ 기준) ───────────────────────────────────────────
DATA_DIR = Path("../data/train")

KOLD_TRAIN = DATA_DIR / "kold_train.jsonl"
KOLD_VAL   = DATA_DIR / "kold_val.jsonl"
KOLD_TEST  = DATA_DIR / "kold_test.jsonl"

KHATERS_TRAIN  = DATA_DIR / "khaters_train.jsonl"
KHATERS_VAL    = DATA_DIR / "khaters_val.jsonl"
KHATERS_TEST   = DATA_DIR / "khaters_test.jsonl"

COMBINED_TRAIN = DATA_DIR / "combined_train.jsonl"
COMBINED_VAL   = DATA_DIR / "combined_val.jsonl"
COMBINED_TEST  = DATA_DIR / "combined_test.jsonl"

# ── 라벨 매핑 ──────────────────────────────────────────────────
_OFF_LABELS = {"L2_hate", "L1_hate", "offensive"}

# ── 플래그 패턴 ────────────────────────────────────────────────
_URL_RE     = re.compile(r"https?://\S+")
_MENTION_RE = re.compile(r"@\w+")
_EMOJI_RE   = re.compile(r"[\U0001F000-\U0001FFFF\U00002600-\U000027BF]")
_ANON_RE    = re.compile(r"#@\w+#")   # K-HATERS 익명화 패턴 (#@이름#)


def detect_flags(comment: str) -> list[str]:
    flags = []
    stripped = comment.strip()
    if not stripped:
        flags.append("whitespace_only")
    elif _EMOJI_RE.sub("", stripped).strip() == "":
        flags.append("emoji_only")
    if _URL_RE.search(comment):
        flags.append("has_url")
    if _MENTION_RE.search(comment):
        flags.append("has_mention")
    if _ANON_RE.search(comment):
        flags.append("has_anon")
    return flags


# ═══════════════════════════════════════════════════════════════
# 변환
# ═══════════════════════════════════════════════════════════════

def convert_sample(idx: int, ex: dict) -> dict:
    """K-HATERS 샘플 → KOLD 호환 형식."""
    comment = unicodedata.normalize("NFC", ex["text"].strip())
    is_off  = ex["label"] in _OFF_LABELS

    off_spans = []
    for item in (ex.get("offensiveness_rationale") or []):
        if not (isinstance(item, (list, tuple)) and len(item) == 2):
            continue
        s, e = item
        if not (isinstance(s, int) and isinstance(e, int)):
            continue
        s = max(0, s)
        e = min(len(comment), e)
        if s >= e:
            continue
        text = comment[s:e].strip()
        if text:
            off_spans.append({"start": s, "end": e, "text": text})

    return {
        "guid":                f"khaters_{idx}",
        "source":              "khaters",
        "comment":             comment,
        "OFF":                 "OFF" if is_off else "NOT",
        "TGT":                 ex.get("target_label", []),
        "GRP":                 None,
        "OFF_spans":           off_spans,
        "TGT_spans":           [],
        "annotator_agreement": 1.0,
        "flags":               detect_flags(comment),
        "use_for_span":        is_off and len(off_spans) > 0,
    }


# ═══════════════════════════════════════════════════════════════
# I/O
# ═══════════════════════════════════════════════════════════════

def save_jsonl(samples: list, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"  저장: {path} ({len(samples):,}개)")


def load_jsonl(path: Path) -> list:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f]


def print_stats(name: str, samples: list):
    total     = len(samples)
    off       = sum(1 for s in samples if s["OFF"] == "OFF")
    not_off   = total - off
    with_span = sum(1 for s in samples if s["use_for_span"])
    span_lens = [sp["end"] - sp["start"]
                 for s in samples for sp in s["OFF_spans"]]
    sources   = Counter(s["source"] for s in samples)

    print(f"\n  [{name}] 총 {total:,}개")
    print(f"    OFF={off:,}  NOT={not_off:,}  (비율 {off/total*100:.1f}%/{not_off/total*100:.1f}%)")
    print(f"    span 있음: {with_span:,}  없음: {off - with_span:,}")
    if span_lens:
        sorted_lens = sorted(span_lens)
        mean = sum(span_lens) / len(span_lens)
        p50  = sorted_lens[len(sorted_lens) // 2]
        p95  = sorted_lens[int(len(sorted_lens) * 0.95)]
        print(f"    span 길이: mean={mean:.1f}  p50={p50}  p95={p95}  max={max(span_lens)}")
    print(f"    source: {dict(sources)}")


# ═══════════════════════════════════════════════════════════════
# 메인
# ═══════════════════════════════════════════════════════════════

def main():
    print("=" * 60)
    print("K-HATERS 전처리 시작")
    print("=" * 60)

    print("\nHuggingFace에서 K-HATERS 로드 중...")
    ds = load_dataset("humane-lab/K-HATERS")

    split_map = [
        ("train",      ds["train"],      KHATERS_TRAIN),
        ("validation", ds["validation"], KHATERS_VAL),
        ("test",       ds["test"],       KHATERS_TEST),
    ]

    khaters: dict[str, list] = {}
    print("\n=== K-HATERS 변환 ===")
    for split_key, split_ds, out_path in split_map:
        samples = [convert_sample(i, ex) for i, ex in enumerate(split_ds)]
        save_jsonl(samples, out_path)
        print_stats(f"K-HATERS {split_key.upper()}", samples)
        khaters[split_key] = samples

    # ── Combined 생성 ──────────────────────────────────────────
    print("\n=== KOLD + K-HATERS Combined 생성 ===")
    for split_key, kold_path, comb_path in [
        ("train",      KOLD_TRAIN, COMBINED_TRAIN),
        ("validation", KOLD_VAL,   COMBINED_VAL),
        ("test",       KOLD_TEST,  COMBINED_TEST),
    ]:
        kold     = load_jsonl(kold_path)
        combined = kold + khaters[split_key]
        save_jsonl(combined, comb_path)
        print_stats(f"COMBINED {split_key.upper()}", combined)

    print("\n전처리 완료.")
    print("\n다음 단계: train_span.py --data combined 로 재학습")


if __name__ == "__main__":
    main()
