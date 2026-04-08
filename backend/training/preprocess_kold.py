"""
KOLD 전처리 파이프라인
Data Scientist + AI Engineer + Data Engineer 설계 기반

입력: data/train/kold_v1.json
출력: data/train/kold_train.jsonl
      data/train/kold_val.jsonl
      data/train/kold_test.jsonl
      data/train/split_metadata.json

스팬 집계 전략: 문자 단위 투표 (2/3 이상 동의한 문자 위치 채택)
분할 전략:     Stratified 80/10/10 (source × OFF)
"""
import json
import hashlib
import unicodedata
from collections import Counter, defaultdict
from pathlib import Path

# ── 경로 설정 ──────────────────────────────────────────────────
INPUT_PATH    = Path("data/train/kold_v1.json")
TRAIN_PATH    = Path("data/train/kold_train.jsonl")
VAL_PATH      = Path("data/train/kold_val.jsonl")
TEST_PATH     = Path("data/train/kold_test.jsonl")
METADATA_PATH = Path("data/train/split_metadata.json")

# ── 하이퍼파라미터 ─────────────────────────────────────────────
SPLIT_RATIO   = (0.8, 0.1, 0.1)
MIN_VOTES     = 2      # 스팬 채택 최소 동의 annotator 수
RANDOM_SEED   = 42


# ═══════════════════════════════════════════════════════════════
# 1. 유효성 검사
# ═══════════════════════════════════════════════════════════════

def validate(item: dict) -> tuple[bool, list[str]]:
    """샘플 유효성 검사. (통과 여부, 오류 목록) 반환."""
    errors = []
    comment = item.get("comment", "")

    if not comment or not comment.strip():
        errors.append("empty_comment")

    try:
        unicodedata.normalize("NFC", comment)
    except Exception:
        errors.append("invalid_unicode")

    for label in item.get("raw_labels", []):
        starts = label.get("off_start_idx", [])
        ends   = label.get("off_end_idx", [])
        if len(starts) != len(ends):
            errors.append("mismatched_span_arrays")
            break
        for s, e in zip(starts, ends):
            if not (isinstance(s, int) and isinstance(e, int)):
                errors.append("non_int_span")
                break
            if not (0 <= s < e <= len(comment)):
                errors.append("span_out_of_bounds")
                break

    if item.get("source") not in ("naver_news", "youtube"):
        errors.append("unknown_source")

    return len(errors) == 0, errors


# ═══════════════════════════════════════════════════════════════
# 2. 스팬 집계 (문자 단위 투표)
# ═══════════════════════════════════════════════════════════════

def aggregate_spans(raw_labels: list, comment: str, min_votes: int = MIN_VOTES) -> list[dict]:
    """
    문자 단위 투표로 스팬 집계.
    각 문자 위치에 대해 'offensive'로 표시한 annotator 수를 셈.
    min_votes 이상이면 해당 위치를 offensive로 채택.
    인접한 offensive 문자를 하나의 스팬으로 묶어 반환.
    """
    char_votes = [0] * len(comment)

    for label in raw_labels:
        if not label.get("offensiveness", False):
            continue
        starts = label.get("off_start_idx", [])
        ends   = label.get("off_end_idx", [])
        for s, e in zip(starts, ends):
            if isinstance(s, int) and isinstance(e, int):
                s = max(0, s)
                e = min(len(comment), e)
                for i in range(s, e):
                    char_votes[i] += 1

    # 연속된 투표 수 >= min_votes 구간을 스팬으로 추출
    spans = []
    in_span, span_start = False, 0
    for i, vote in enumerate(char_votes):
        if vote >= min_votes and not in_span:
            in_span = True
            span_start = i
        elif vote < min_votes and in_span:
            in_span = False
            text = comment[span_start:i].strip()
            if text:
                spans.append({"start": span_start, "end": i, "text": text})
    if in_span:
        text = comment[span_start:].strip()
        if text:
            spans.append({"start": span_start, "end": len(comment), "text": text})

    return spans


def annotator_agreement(raw_labels: list) -> float:
    """offensiveness에 대한 annotator 동의율 (0.0~1.0)."""
    votes = [bool(l.get("offensiveness")) for l in raw_labels]
    if not votes:
        return 0.0
    majority = max(votes.count(True), votes.count(False))
    return majority / len(votes)


# ═══════════════════════════════════════════════════════════════
# 3. 플래그
# ═══════════════════════════════════════════════════════════════

import re

_URL_RE     = re.compile(r"https?://\S+")
_MENTION_RE = re.compile(r"@\w+")
_EMOJI_RE   = re.compile(r"[\U0001F000-\U0001FFFF\U00002600-\U000027BF]")


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
    return flags


# ═══════════════════════════════════════════════════════════════
# 4. 중복 제거
# ═══════════════════════════════════════════════════════════════

def dedup(samples: list[dict]) -> tuple[list[dict], int]:
    """NFC 정규화 기준 중복 제거.
    동일 comment에 OFF 충돌 시 annotator 동의율이 높은 쪽 유지.
    """
    seen: dict[str, dict] = {}
    for s in samples:
        key = unicodedata.normalize("NFC", s["comment"])
        if key not in seen:
            seen[key] = s
        else:
            # OFF 충돌: annotator 동의율이 높은 쪽 유지
            if s["annotator_agreement"] > seen[key]["annotator_agreement"]:
                seen[key] = s

    n_removed = len(samples) - len(seen)
    return list(seen.values()), n_removed


# ═══════════════════════════════════════════════════════════════
# 5. Stratified 분할
# ═══════════════════════════════════════════════════════════════

def stratified_split(samples: list[dict], ratio: tuple, seed: int) -> tuple:
    """source × OFF 기준 계층 분할."""
    import random
    rng = random.Random(seed)

    # 계층별 그룹화
    strata: dict[str, list] = defaultdict(list)
    for s in samples:
        key = f"{s['source']}_{s['OFF']}"
        strata[key].append(s)

    train, val, test = [], [], []
    for key, group in strata.items():
        rng.shuffle(group)
        n = len(group)
        n_train = int(n * ratio[0])
        n_val   = int(n * ratio[1])
        train.extend(group[:n_train])
        val.extend(group[n_train:n_train + n_val])
        test.extend(group[n_train + n_val:])

    # 최종 셔플 (계층 순서 제거)
    rng.shuffle(train)
    rng.shuffle(val)
    rng.shuffle(test)

    return train, val, test


# ═══════════════════════════════════════════════════════════════
# 6. 통계 출력
# ═══════════════════════════════════════════════════════════════

def print_stats(name: str, samples: list[dict]):
    total = len(samples)
    off   = sum(1 for s in samples if s["OFF"] == "OFF")
    not_  = total - off
    with_span  = sum(1 for s in samples if s["OFF"] == "OFF" and s["OFF_spans"])
    no_span    = sum(1 for s in samples if s["OFF"] == "OFF" and not s["OFF_spans"])
    span_lens  = [sp["end"] - sp["start"]
                  for s in samples for sp in s["OFF_spans"]]
    naver = sum(1 for s in samples if s["source"] == "naver_news")
    yt    = total - naver

    print(f"\n  [{name}] 총 {total}개")
    print(f"    OFF={off}  NOT={not_}  (비율 {off/total*100:.1f}%/{not_/total*100:.1f}%)")
    print(f"    span 있음: {with_span}  없음: {no_span}")
    print(f"    source: naver_news={naver}  youtube={yt}")
    if span_lens:
        mean = sum(span_lens) / len(span_lens)
        sorted_lens = sorted(span_lens)
        p50 = sorted_lens[len(sorted_lens)//2]
        p95 = sorted_lens[int(len(sorted_lens)*0.95)]
        print(f"    span 길이: mean={mean:.1f}  p50={p50}  p95={p95}  max={max(span_lens)}")


# ═══════════════════════════════════════════════════════════════
# 7. 저장
# ═══════════════════════════════════════════════════════════════

def save_jsonl(samples: list[dict], path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for s in samples:
            f.write(json.dumps(s, ensure_ascii=False) + "\n")
    print(f"  저장: {path} ({len(samples)}개)")


# ═══════════════════════════════════════════════════════════════
# 8. 메인
# ═══════════════════════════════════════════════════════════════

def main():
    print("=" * 60)
    print("KOLD 전처리 시작")
    print("=" * 60)

    with open(INPUT_PATH, encoding="utf-8") as f:
        raw = json.load(f)
    print(f"\n원본 로드: {len(raw)}개")

    # ── 전처리 ──────────────────────────────────────────────
    samples = []
    exclusion_counts = Counter()

    for item in raw:
        valid, errors = validate(item)
        if not valid:
            for e in errors:
                exclusion_counts[e] += 1
            continue

        comment  = unicodedata.normalize("NFC", item["comment"].strip())
        is_off   = item.get("OFF", False)
        raw_lbs  = item.get("raw_labels", [])

        off_spans = aggregate_spans(raw_lbs, comment) if is_off else []
        agr       = annotator_agreement(raw_lbs)
        flags     = detect_flags(comment)

        samples.append({
            "guid":                item.get("guid"),
            "source":              item.get("source"),
            "comment":             comment,
            "OFF":                 "OFF" if is_off else "NOT",
            "TGT":                 item.get("TGT"),
            "GRP":                 item.get("GRP"),
            "OFF_spans":           off_spans,
            "TGT_spans":           [],        # TGT_span은 span 모델 범위 밖
            "annotator_agreement": round(agr, 3),
            "flags":               flags,
            # 학습 플래그
            "use_for_span":        is_off and len(off_spans) > 0,
        })

    print(f"유효성 통과: {len(samples)}개")
    if exclusion_counts:
        print("  제외 이유:")
        for reason, cnt in exclusion_counts.most_common():
            print(f"    {reason}: {cnt}개")

    # ── 중복 제거 ────────────────────────────────────────────
    samples, n_dup = dedup(samples)
    print(f"중복 제거 후: {len(samples)}개 (제거: {n_dup}개)")

    # ── 분할 ─────────────────────────────────────────────────
    train, val, test = stratified_split(samples, SPLIT_RATIO, RANDOM_SEED)

    print("\n=== 분할 통계 ===")
    print_stats("TRAIN", train)
    print_stats("VAL",   val)
    print_stats("TEST",  test)

    # ── 저장 ─────────────────────────────────────────────────
    print("\n=== 저장 ===")
    save_jsonl(train, TRAIN_PATH)
    save_jsonl(val,   VAL_PATH)
    save_jsonl(test,  TEST_PATH)

    # ── 메타데이터 ───────────────────────────────────────────
    metadata = {
        "seed":         RANDOM_SEED,
        "split_ratio":  SPLIT_RATIO,
        "min_votes":    MIN_VOTES,
        "total":        len(samples),
        "train":        len(train),
        "val":          len(val),
        "test":         len(test),
        "exclusions":   dict(exclusion_counts),
        "duplicates_removed": n_dup,
        "label2id":     {"O": 0, "B-OFF": 1, "I-OFF": 2},
        "id2label":     {"0": "O", "1": "B-OFF", "2": "I-OFF"},
    }
    with open(METADATA_PATH, "w", encoding="utf-8") as f:
        json.dump(metadata, f, ensure_ascii=False, indent=2)
    print(f"  저장: {METADATA_PATH}")

    print("\n전처리 완료.")


if __name__ == "__main__":
    main()
