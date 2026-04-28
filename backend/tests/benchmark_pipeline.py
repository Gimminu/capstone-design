import csv
import os
import statistics
import sys
import time
from pathlib import Path

import torch


ROOT = Path(__file__).resolve().parents[3]
API_DIR = ROOT / "add" / "backend" / "api"
UNSMILE_PATH = ROOT / "add" / "backend" / "tests" / "unsmile_valid.csv"
KMHAS_PATH = ROOT / "add" / "backend" / "tests" / "kmhas_test.csv"

if str(API_DIR) not in sys.path:
    sys.path.insert(0, str(API_DIR))

os.environ.setdefault("MODEL_BASE", str(ROOT / "add" / "backend"))

from classifier import TextClassifier  # noqa: E402
from normalizer import normalize  # noqa: E402
from pipeline import ProfanityPipeline  # noqa: E402
from span_detector import SpanDetector  # noqa: E402


MAX_SAMPLES = 80
WARMUP_SAMPLES = 5


def load_unsmile_texts(limit: int) -> list[str]:
    texts = []
    with UNSMILE_PATH.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            text = (row.get("문장") or "").strip()
            if not text:
                continue
            texts.append(text)
            if len(texts) >= limit:
                break
    return texts


def load_kmhas_texts(limit: int) -> list[str]:
    texts = []
    with KMHAS_PATH.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            text = (row.get("document") or "").strip()
            if not text:
                continue
            texts.append(text)
            if len(texts) >= limit:
                break
    return texts


def load_texts(limit: int) -> tuple[list[str], list[str]]:
    per_source = max(1, limit // 2)
    unsmile = load_unsmile_texts(per_source)
    kmhas = load_kmhas_texts(limit - len(unsmile))
    texts = (unsmile + kmhas)[:limit]
    sources = [str(UNSMILE_PATH), str(KMHAS_PATH)]
    if not texts:
        raise RuntimeError("No benchmark texts found in unsmile_valid.csv or kmhas_test.csv")
    return texts, sources


def describe(times: list[float]) -> dict:
    values_ms = [t * 1000 for t in times]
    values_ms.sort()
    p95_index = max(0, min(len(values_ms) - 1, int(len(values_ms) * 0.95) - 1))
    return {
        "count": len(values_ms),
        "avg_ms": statistics.mean(values_ms),
        "avg_sec": statistics.mean(times),
        "median_ms": statistics.median(values_ms),
        "min_ms": min(values_ms),
        "max_ms": max(values_ms),
        "p95_ms": values_ms[p95_index],
        "total_sec": sum(times),
    }


def measure_classifier(classifier: TextClassifier, texts: list[str]) -> dict:
    times = []
    for text in texts:
        normalized = normalize(text)
        t0 = time.perf_counter()
        classifier.predict(normalized)
        times.append(time.perf_counter() - t0)
    return describe(times)


def measure_span(span_detector: SpanDetector, texts: list[str]) -> dict:
    times = []
    non_empty = 0
    for text in texts:
        normalized = normalize(text)
        t0 = time.perf_counter()
        spans = span_detector.detect(normalized)
        times.append(time.perf_counter() - t0)
        if spans:
            non_empty += 1
    result = describe(times)
    result["non_empty_spans"] = non_empty
    return result


def measure_pipeline(pipe: ProfanityPipeline, texts: list[str]) -> dict:
    times = []
    offensive = 0
    with_spans = 0
    for text in texts:
        t0 = time.perf_counter()
        result = pipe.analyze(text)
        times.append(time.perf_counter() - t0)
        if result["is_offensive"]:
            offensive += 1
        if result["evidence_spans"]:
            with_spans += 1
    desc = describe(times)
    desc["offensive_count"] = offensive
    desc["with_spans_count"] = with_spans
    return desc


def format_block(title: str, stats: dict) -> str:
    lines = [f"## {title}"]
    for key, value in stats.items():
        if isinstance(value, float):
            lines.append(f"- {key}: {value:.3f}")
        else:
            lines.append(f"- {key}: {value}")
    return "\n".join(lines)


def build_analysis(classifier_stats: dict, span_stats: dict, pipeline_stats: dict) -> str:
    lines = ["## Analysis"]
    if classifier_stats["avg_ms"] < span_stats["avg_ms"]:
        lines.append("- classifier가 span보다 빠릅니다.")
    else:
        lines.append("- span이 classifier보다 빠릅니다.")

    if pipeline_stats["avg_ms"] < span_stats["avg_ms"]:
        lines.append("- 전체 파이프라인은 span을 모든 문장에 돌리지 않아 span 단독 평균보다 낮습니다.")
    else:
        lines.append("- 전체 파이프라인 평균이 span 단독과 비슷하거나 더 높습니다.")

    lines.append("- 병목은 평균 시간이 더 큰 모델이며, 현재는 span 쪽일 가능성이 큽니다.")
    return "\n".join(lines)


def main():
    texts, dataset_sources = load_texts(MAX_SAMPLES)
    warmup = texts[:WARMUP_SAMPLES]
    bench = texts[WARMUP_SAMPLES:]

    init_t0 = time.perf_counter()
    classifier = TextClassifier(model_path=str(ROOT / "add" / "backend" / "models" / "v2"))
    classifier_init = time.perf_counter() - init_t0

    init_t1 = time.perf_counter()
    span_detector = SpanDetector(model_dir=str(ROOT / "add" / "backend" / "models" / "span_large_combined_crf"))
    span_init = time.perf_counter() - init_t1

    init_t2 = time.perf_counter()
    pipe = ProfanityPipeline(
        classifier_path=str(ROOT / "add" / "backend" / "models" / "v2"),
        span_model_path=str(ROOT / "add" / "backend" / "models" / "span_large_combined_crf"),
    )
    pipeline_init = time.perf_counter() - init_t2

    for text in warmup:
        normalized = normalize(text)
        classifier.predict(normalized)
        span_detector.detect(normalized)
        pipe.analyze(text)

    classifier_stats = measure_classifier(classifier, bench)
    span_stats = measure_span(span_detector, bench)
    pipeline_stats = measure_pipeline(pipe, bench)

    comparison = {
        "samples_measured": len(bench),
        "torch_device": str(classifier.device),
        "mps_available": getattr(torch.backends, "mps", None) is not None and torch.backends.mps.is_available(),
        "cuda_available": torch.cuda.is_available(),
        "classifier_init_ms": classifier_init * 1000,
        "span_init_ms": span_init * 1000,
        "pipeline_init_ms": pipeline_init * 1000,
        "pipeline_minus_classifier_avg_ms": pipeline_stats["avg_ms"] - classifier_stats["avg_ms"],
        "pipeline_minus_span_avg_ms": pipeline_stats["avg_ms"] - span_stats["avg_ms"],
    }

    report = "\n\n".join([
        "# Benchmark Report",
        f"- datasets: {', '.join(dataset_sources)}",
        f"- samples_total: {len(texts)}",
        f"- warmup_samples: {len(warmup)}",
        f"- measured_samples: {len(bench)}",
        format_block("Full Pipeline", pipeline_stats),
        format_block("Classifier Only", classifier_stats),
        format_block("Span Only", span_stats),
        format_block("Comparison", comparison),
        build_analysis(classifier_stats, span_stats, pipeline_stats),
    ])

    print(report)


if __name__ == "__main__":
    main()
