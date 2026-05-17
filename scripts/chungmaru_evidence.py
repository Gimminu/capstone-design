#!/usr/bin/env python3
"""Capture report-ready Chungmaru engineering evidence in one JSONL ledger."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOG_PATH = REPO_ROOT / "docs" / "evidence" / "chungmaru-progress-log.jsonl"
DEFAULT_INDEX_PATH = REPO_ROOT / "docs" / "evidence" / "chungmaru-progress-index.md"

LANES = {
    "android",
    "backend",
    "extension",
    "shared-contract",
    "docs-evaluation",
    "cross-lane",
}
EVENT_TYPES = {
    "failure",
    "experiment",
    "improvement",
    "regression",
    "verification",
    "decision",
}
OUTCOMES = {
    "observed",
    "committed",
    "improved",
    "fixed",
    "partial",
    "blocked",
    "verified",
    "needs-follow-up",
}
EVIDENCE_QUALITIES = {
    "commit-only",
    "log-backed",
    "screenshot-backed",
    "test-backed",
    "artifact-backed",
    "report-ready",
}
WORKTREE_ROLES = {
    "official",
    "experiment",
    "verification",
    "reporting",
    "integration",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def run_git_at(cwd: Path, args: list[str]) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def git_or_unknown(args: list[str], fallback: str = "unknown") -> str:
    try:
        value = run_git(args)
    except (subprocess.CalledProcessError, FileNotFoundError):
        return fallback
    return value or fallback


def current_branch() -> str:
    return git_or_unknown(["branch", "--show-current"])


def current_commit() -> str:
    return git_or_unknown(["rev-parse", "--short", "HEAD"])


def current_worktree_path() -> str:
    return git_or_unknown(["rev-parse", "--show-toplevel"], str(REPO_ROOT))


def normalize_worktree_path(value: str | None) -> str:
    return (value or "").strip() or current_worktree_path()


def normalize_worktree_role(value: str | None, worktree_path: str) -> str:
    explicit = (value or "").strip()
    if explicit:
        return explicit
    try:
        if Path(worktree_path).resolve() == REPO_ROOT.resolve():
            return "official"
    except OSError:
        pass
    return "experiment"


def split_csv(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def parse_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    if value is None:
        return False
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y", "on", "__yes__"}
    return bool(value)


def normalize_session_ids(
    legacy_session_id: str | None,
    source_session_id: str | None,
    registrar_session_id: str | None,
) -> tuple[str, str, str]:
    """Keep old session_id usable while separating origin from registration."""
    legacy = (legacy_session_id or "").strip()
    source = (source_session_id or "").strip()
    registrar = (
        (registrar_session_id or "").strip()
        or legacy
        or os.environ.get("CODEX_SESSION_ID", "")
    )
    return legacy or registrar or source, source, registrar


def compact_text(value: Any, limit: int = 120) -> str:
    text = " ".join(str(value or "").split())
    if len(text) <= limit:
        return text
    return text[: limit - 1] + "..."


def md_escape(value: Any) -> str:
    return compact_text(value, 160).replace("|", "\\|")


def load_entries(log_path: Path) -> list[dict[str, Any]]:
    if not log_path.exists():
        return []

    entries: list[dict[str, Any]] = []
    with log_path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            stripped = line.strip()
            if not stripped:
                continue
            try:
                value = json.loads(stripped)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{log_path}:{line_number}: invalid JSON: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{log_path}:{line_number}: expected a JSON object")
            entries.append(value)
    return entries


def event_key(entry: dict[str, Any]) -> str:
    explicit = str(entry.get("key") or "").strip()
    if explicit:
        return explicit
    commit = str(entry.get("commit") or "").strip()
    if commit:
        return f"commit:{commit}"
    title = str(entry.get("title") or "").strip()
    timestamp = str(entry.get("timestamp") or "").strip()
    return f"manual:{timestamp}:{title}"


def existing_keys(entries: list[dict[str, Any]]) -> set[str]:
    return {event_key(entry) for entry in entries}


def append_entries(log_path: Path, entries: list[dict[str, Any]]) -> None:
    if not entries:
        return
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as handle:
        for entry in entries:
            handle.write(json.dumps(entry, ensure_ascii=False, sort_keys=True))
            handle.write("\n")


def classify_lane(subject: str) -> str:
    lowered = subject.lower()
    if any(
        word in lowered
        for word in (
            "android",
            "youtube",
            "ocr",
            "visual",
            "overlay",
            "bounds",
            "scroll",
            "accessibility",
            "thumbnail",
            "shorts",
        )
    ):
        return "android"
    if "backend" in lowered or "model" in lowered or "analyze" in lowered:
        return "backend"
    if "extension" in lowered or "google" in lowered or "chrome" in lowered:
        return "extension"
    if "shared" in lowered or "contract" in lowered:
        return "shared-contract"
    if "doc" in lowered or "evaluation" in lowered or "report" in lowered:
        return "docs-evaluation"
    return "cross-lane"


def classify_event_type(subject: str) -> str:
    lowered = subject.lower()
    if any(word in lowered for word in ("regress", "revert")):
        return "regression"
    if any(word in lowered for word in ("verify", "diagnostic", "evaluation", "report")):
        return "verification"
    if any(word in lowered for word in ("decision", "policy", "document")):
        return "decision"
    if any(
        word in lowered
        for word in (
            "fix",
            "improve",
            "stabilize",
            "tighten",
            "guard",
            "prevent",
            "stop",
            "constrain",
            "preserve",
            "reuse",
            "promote",
            "keep",
            "add",
            "use",
        )
    ):
        return "improvement"
    if any(word in lowered for word in ("retry", "fallback", "experiment", "probe", "spike")):
        return "experiment"
    return "experiment"


def classify_constraints(subject: str) -> list[str]:
    lowered = subject.lower()
    constraints: set[str] = set()
    if any(word in lowered for word in ("model", "backend", "analyze", "span")):
        constraints.add("C2")
    if any(word in lowered for word in ("youtube", "google", "chrome", "candidate", "parser")):
        constraints.add("C3")
    if any(word in lowered for word in ("scroll", "stale", "realtime", "latency", "alive", "content churn")):
        constraints.add("C4")
    if any(word in lowered for word in ("mask", "overlay", "bounds", "geometry", "visual")):
        constraints.add("C5")
    if any(word in lowered for word in ("settings", "sensitivity", "cache")):
        constraints.add("C6")
    if any(word in lowered for word in ("android", "accessibility", "ocr", "thumbnail", "shorts")):
        constraints.add("C7")
    if any(word in lowered for word in ("test", "build", "diagnostic", "jbr", "gradle")):
        constraints.add("C8")
    return sorted(constraints or {"C8"})


def classify_tags(subject: str) -> list[str]:
    lowered = subject.lower()
    pairs = {
        "android": "android",
        "youtube": "youtube",
        "ocr": "ocr",
        "visual": "visual-text",
        "mask": "masking",
        "overlay": "overlay",
        "scroll": "scroll",
        "stale": "stale-state",
        "bounds": "bounds",
        "geometry": "geometry",
        "accessibility": "accessibility",
        "comment": "comments",
        "parser": "parser",
        "backend": "backend",
        "extension": "extension",
        "google": "google",
        "chrome": "chrome",
        "diagnostic": "diagnostics",
    }
    return sorted({tag for keyword, tag in pairs.items() if keyword in lowered})


def git_log_entries(revision_range: str, limit: int) -> list[dict[str, str]]:
    pretty = "%H%x1f%h%x1f%s%x1f%ci"
    output = run_git(["log", f"--pretty=format:{pretty}", f"--max-count={limit}", revision_range])
    rows: list[dict[str, str]] = []
    if not output:
        return rows
    for line in output.splitlines():
        parts = line.split("\x1f")
        if len(parts) != 4:
            continue
        full, short, subject, committed_at = parts
        rows.append(
            {
                "commit": full,
                "commit_short": short,
                "subject": subject,
                "committed_at": committed_at,
            }
        )
    return rows


def build_commit_entry(
    row: dict[str, str],
    issue: str | None,
    pr_url: str | None,
    session_id: str | None,
    source_session_id: str | None,
    registrar_session_id: str | None,
    worktree_path: str | None,
    worktree_role: str | None,
    source_branch: str | None,
    integration_branch: str | None,
) -> dict[str, Any]:
    subject = row["subject"]
    event_type = classify_event_type(subject)
    legacy_session, source_session, registrar_session = normalize_session_ids(
        session_id,
        source_session_id,
        registrar_session_id,
    )
    normalized_worktree_path = normalize_worktree_path(worktree_path)
    normalized_worktree_role = normalize_worktree_role(worktree_role, normalized_worktree_path)
    normalized_source_branch = (source_branch or "").strip() or current_branch()
    normalized_integration_branch = (integration_branch or "").strip() or "main"
    return {
        "key": f"commit:{row['commit']}",
        "timestamp": utc_now(),
        "source": "git-backfill",
        "session_id": legacy_session,
        "source_session_id": source_session,
        "registrar_session_id": registrar_session,
        "branch": current_branch(),
        "source_branch": normalized_source_branch,
        "integration_branch": normalized_integration_branch,
        "worktree_path": normalized_worktree_path,
        "worktree_role": normalized_worktree_role,
        "commit": row["commit"],
        "commit_short": row["commit_short"],
        "committed_at": row["committed_at"],
        "github_pr": pr_url,
        "linear_issue": issue,
        "lane": classify_lane(subject),
        "surface": "auto-classified",
        "event_type": event_type,
        "title": subject,
        "problem": "Backfilled from branch commit history; expand this entry if it becomes report-critical.",
        "cause": "",
        "change": subject,
        "verification": "",
        "outcome": "committed",
        "constraint_codes": classify_constraints(subject),
        "tags": classify_tags(subject),
        "evidence_quality": "commit-only",
        "report_ready": False,
        "remaining_risk": "Backfilled entry needs screenshot/log/test evidence before it is used as final report proof.",
        "evidence_artifacts": [],
    }


def validate_entry(entry: dict[str, Any], line_number: int | None = None) -> list[str]:
    prefix = f"line {line_number}: " if line_number is not None else ""
    errors: list[str] = []
    for field in ("timestamp", "event_type", "lane", "title", "outcome"):
        if not entry.get(field):
            errors.append(f"{prefix}missing {field}")
    if entry.get("event_type") and entry["event_type"] not in EVENT_TYPES:
        errors.append(f"{prefix}invalid event_type {entry['event_type']!r}")
    if entry.get("lane") and entry["lane"] not in LANES:
        errors.append(f"{prefix}invalid lane {entry['lane']!r}")
    if entry.get("outcome") and entry["outcome"] not in OUTCOMES:
        errors.append(f"{prefix}invalid outcome {entry['outcome']!r}")
    constraints = entry.get("constraint_codes", [])
    if constraints and not isinstance(constraints, list):
        errors.append(f"{prefix}constraint_codes must be a list")
    tags = entry.get("tags", [])
    if tags and not isinstance(tags, list):
        errors.append(f"{prefix}tags must be a list")
    quality = entry.get("evidence_quality")
    if quality and quality not in EVIDENCE_QUALITIES:
        errors.append(f"{prefix}invalid evidence_quality {quality!r}")
    if "report_ready" in entry and not isinstance(entry.get("report_ready"), bool):
        errors.append(f"{prefix}report_ready must be a boolean")
    worktree_role = entry.get("worktree_role")
    if worktree_role and worktree_role not in WORKTREE_ROLES:
        errors.append(f"{prefix}invalid worktree_role {worktree_role!r}")
    return errors


def render_index(entries: list[dict[str, Any]], index_path: Path, log_path: Path) -> None:
    index_path.parent.mkdir(parents=True, exist_ok=True)
    sorted_entries = sorted(
        entries,
        key=lambda item: str(item.get("committed_at") or item.get("timestamp") or ""),
        reverse=True,
    )
    by_type = Counter(str(entry.get("event_type", "unknown")) for entry in entries)
    by_lane = Counter(str(entry.get("lane", "unknown")) for entry in entries)
    by_outcome = Counter(str(entry.get("outcome", "unknown")) for entry in entries)
    by_quality = Counter(str(entry.get("evidence_quality", "legacy")) for entry in entries)
    by_worktree_role = Counter(str(entry.get("worktree_role", "legacy")) for entry in entries)
    report_ready_count = sum(1 for entry in entries if parse_bool(entry.get("report_ready")))

    lines: list[str] = [
        "# Chungmaru Evidence Index",
        "",
        f"Generated: {utc_now()}",
        f"Source log: `{log_path.relative_to(REPO_ROOT)}`",
        "",
        "This file is generated from the JSONL ledger. Edit the ledger through `scripts/chungmaru_evidence.py`.",
        "",
        "## Summary",
        "",
        "| Metric | Count |",
        "| --- | ---: |",
        f"| Total entries | {len(entries)} |",
        f"| Report-ready entries | {report_ready_count} |",
    ]

    for label, counter in (
        ("Event type", by_type),
        ("Lane", by_lane),
        ("Outcome", by_outcome),
        ("Evidence quality", by_quality),
        ("Worktree role", by_worktree_role),
    ):
        lines.extend(["", f"### {label}", "", "| Value | Count |", "| --- | ---: |"])
        for key, count in sorted(counter.items()):
            lines.append(f"| {md_escape(key)} | {count} |")

    lines.extend(
        [
            "",
            "## Recent Evidence",
            "",
            "| Date | Type | Lane | Title | Quality | Ready | Worktree | Constraints | Outcome | Evidence / Risk |",
            "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |",
        ]
    )

    for entry in sorted_entries[:80]:
        date = str(entry.get("committed_at") or entry.get("timestamp") or "")[:10]
        constraints = ", ".join(entry.get("constraint_codes", []) or [])
        quality = str(entry.get("evidence_quality") or "legacy")
        ready = "yes" if parse_bool(entry.get("report_ready")) else "no"
        worktree_role = str(entry.get("worktree_role") or "legacy")
        evidence_bits = [
            entry.get("problem", ""),
            entry.get("verification", ""),
            entry.get("remaining_risk", ""),
        ]
        evidence = compact_text(" / ".join(bit for bit in evidence_bits if bit), 180)
        title = entry.get("title", "")
        commit = entry.get("commit_short") or ""
        if commit:
            title = f"`{commit}` {title}"
        lines.append(
            "| "
            f"{md_escape(date)} | "
            f"{md_escape(entry.get('event_type', ''))} | "
            f"{md_escape(entry.get('lane', ''))} | "
            f"{md_escape(title)} | "
            f"{md_escape(quality)} | "
            f"{md_escape(ready)} | "
            f"{md_escape(worktree_role)} | "
            f"{md_escape(constraints)} | "
            f"{md_escape(entry.get('outcome', ''))} | "
            f"{md_escape(evidence)} |"
        )

    lines.extend(
        [
            "",
            "## Operating Rule",
            "",
            "- Add one ledger entry per distinct failure, experiment, verified improvement, regression, or decision.",
            "- Keep raw runtime dumps out of Git unless they are compact curated fixtures.",
            "- Use commit history for traceability, but use this index for report-ready meaning.",
            "- Before using an auto-backfilled commit in the final report, add concrete screenshot, log, test, or PR evidence.",
            "- Use worktrees for risky or long-running development, but record durable evidence here with the source worktree and intended integration branch.",
            "",
        ]
    )
    index_path.write_text("\n".join(lines), encoding="utf-8")


def cmd_add(args: argparse.Namespace) -> int:
    log_path = Path(args.log_path)
    index_path = Path(args.index_path)
    entries = load_entries(log_path)
    legacy_session, source_session, registrar_session = normalize_session_ids(
        args.session_id,
        args.source_session_id,
        args.registrar_session_id,
    )

    entry = {
        "key": args.key,
        "timestamp": utc_now(),
        "source": "manual",
        "session_id": legacy_session,
        "source_session_id": source_session,
        "registrar_session_id": registrar_session,
        "branch": current_branch(),
        "source_branch": args.source_branch or current_branch(),
        "integration_branch": args.integration_branch or "main",
        "worktree_path": normalize_worktree_path(args.worktree_path),
        "worktree_role": normalize_worktree_role(
            args.worktree_role,
            normalize_worktree_path(args.worktree_path),
        ),
        "head_commit": current_commit(),
        "commit": args.commit,
        "commit_short": args.commit[:7] if args.commit else "",
        "github_pr": args.github_pr,
        "linear_issue": args.linear_issue,
        "lane": args.lane,
        "surface": args.surface,
        "event_type": args.event_type,
        "title": args.title,
        "problem": args.problem,
        "cause": args.cause,
        "change": args.change,
        "verification": args.verification,
        "outcome": args.outcome,
        "constraint_codes": split_csv(args.constraints),
        "tags": sorted(set(args.tag or [])),
        "evidence_quality": args.evidence_quality,
        "report_ready": bool(args.report_ready),
        "remaining_risk": args.remaining_risk,
        "evidence_artifacts": args.artifact or [],
    }

    errors = validate_entry(entry)
    if errors:
        for error in errors:
            print(f"[ERROR] {error}", file=sys.stderr)
        return 2

    key = event_key(entry)
    if key in existing_keys(entries):
        print(f"[SKIP] duplicate evidence key: {key}")
        render_index(entries, index_path, log_path)
        return 0

    append_entries(log_path, [entry])
    entries.append(entry)
    render_index(entries, index_path, log_path)
    print(f"[OK] added evidence: {key}")
    print(f"[OK] regenerated {index_path.relative_to(REPO_ROOT)}")
    return 0


def cmd_backfill_git(args: argparse.Namespace) -> int:
    log_path = Path(args.log_path)
    index_path = Path(args.index_path)
    existing = load_entries(log_path)
    keys = existing_keys(existing)
    rows = git_log_entries(args.revision_range, args.limit)
    new_entries = [
        build_commit_entry(
            row,
            args.linear_issue,
            args.github_pr,
            args.session_id,
            args.source_session_id,
            args.registrar_session_id,
            args.worktree_path,
            args.worktree_role,
            args.source_branch,
            args.integration_branch,
        )
        for row in rows
        if f"commit:{row['commit']}" not in keys
    ]

    print(f"[INFO] range={args.revision_range} commits={len(rows)} new={len(new_entries)}")
    for entry in new_entries:
        print(
            f"- {entry['commit_short']} "
            f"{entry['event_type']} "
            f"{entry['lane']} "
            f"{entry['title']}"
        )

    if args.write:
        append_entries(log_path, new_entries)
        all_entries = [*existing, *new_entries]
        render_index(all_entries, index_path, log_path)
        print(f"[OK] appended {len(new_entries)} entries")
        print(f"[OK] regenerated {index_path.relative_to(REPO_ROOT)}")
    else:
        print("[DRY-RUN] pass --write to append these entries")
    return 0


def cmd_summarize(args: argparse.Namespace) -> int:
    log_path = Path(args.log_path)
    index_path = Path(args.index_path)
    entries = load_entries(log_path)
    render_index(entries, index_path, log_path)
    print(f"[OK] regenerated {index_path.relative_to(REPO_ROOT)} from {len(entries)} entries")
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    log_path = Path(args.log_path)
    entries = load_entries(log_path)
    errors: list[str] = []
    seen: set[str] = set()
    for line_number, entry in enumerate(entries, start=1):
        errors.extend(validate_entry(entry, line_number))
        key = event_key(entry)
        if key in seen:
            errors.append(f"line {line_number}: duplicate key {key}")
        seen.add(key)

    if errors:
        for error in errors:
            print(f"[ERROR] {error}", file=sys.stderr)
        return 1
    print(f"[OK] {log_path.relative_to(REPO_ROOT)} entries={len(entries)}")
    return 0


def parse_worktree_porcelain(output: str) -> list[dict[str, str]]:
    worktrees: list[dict[str, str]] = []
    current: dict[str, str] = {}
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line:
            if current:
                worktrees.append(current)
                current = {}
            continue
        if line.startswith("worktree "):
            current["path"] = line.removeprefix("worktree ")
        elif line.startswith("HEAD "):
            current["head"] = line.removeprefix("HEAD ")[:12]
        elif line.startswith("branch "):
            current["branch"] = line.removeprefix("branch ").removeprefix("refs/heads/")
        elif line == "detached":
            current["branch"] = "detached"
        elif line == "bare":
            current["bare"] = "true"
    if current:
        worktrees.append(current)
    return worktrees


def worktree_status_lines(path: str) -> list[str]:
    try:
        output = run_git_at(Path(path), ["status", "--short"])
    except (subprocess.CalledProcessError, FileNotFoundError, OSError):
        return ["<status unavailable>"]
    return [line for line in output.splitlines() if line.strip()]


def cmd_audit_worktrees(args: argparse.Namespace) -> int:
    try:
        output = run_git(["worktree", "list", "--porcelain"])
    except (subprocess.CalledProcessError, FileNotFoundError) as exc:
        print(f"[ERROR] failed to list worktrees: {exc}", file=sys.stderr)
        return 1

    rows = []
    for item in parse_worktree_porcelain(output):
        path = item.get("path", "")
        status_lines = worktree_status_lines(path)
        role = normalize_worktree_role("", path)
        rows.append(
            {
                "path": path,
                "branch": item.get("branch", "unknown"),
                "head": item.get("head", "unknown"),
                "role": role,
                "dirty_count": len(status_lines),
                "dirty": bool(status_lines),
                "status": status_lines if args.show_status else [],
            }
        )

    if args.json:
        print(json.dumps({"generated_at": utc_now(), "worktrees": rows}, ensure_ascii=False, indent=2))
        return 0

    print("# Chungmaru Worktree Audit")
    print()
    print(f"Generated: {utc_now()}")
    print()
    print("| Role | Branch | Dirty | Path |")
    print("| --- | --- | ---: | --- |")
    for row in rows:
        print(
            f"| {row['role']} | {row['branch']} | "
            f"{row['dirty_count']} | `{row['path']}` |"
        )
        if args.show_status:
            for status_line in row["status"]:
                print(f"|  |  |  | `{status_line}` |")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log-path", default=str(DEFAULT_LOG_PATH))
    parser.add_argument("--index-path", default=str(DEFAULT_INDEX_PATH))
    subparsers = parser.add_subparsers(dest="command", required=True)

    add = subparsers.add_parser("add", help="append one manual evidence event")
    add.set_defaults(func=cmd_add)
    add.add_argument("--key", default="")
    add.add_argument("--session-id", default="")
    add.add_argument("--source-session-id", default="")
    add.add_argument("--registrar-session-id", default="")
    add.add_argument("--worktree-path", default="")
    add.add_argument("--worktree-role", default="", choices=["", *sorted(WORKTREE_ROLES)])
    add.add_argument("--source-branch", default="")
    add.add_argument("--integration-branch", default="main")
    add.add_argument("--linear-issue", default="")
    add.add_argument("--github-pr", default="")
    add.add_argument("--commit", default="")
    add.add_argument("--lane", required=True, choices=sorted(LANES))
    add.add_argument("--surface", required=True)
    add.add_argument("--event-type", required=True, choices=sorted(EVENT_TYPES))
    add.add_argument("--title", required=True)
    add.add_argument("--problem", default="")
    add.add_argument("--cause", default="")
    add.add_argument("--change", default="")
    add.add_argument("--verification", default="")
    add.add_argument("--outcome", required=True, choices=sorted(OUTCOMES))
    add.add_argument("--constraints", default="")
    add.add_argument("--evidence-quality", default="log-backed", choices=sorted(EVIDENCE_QUALITIES))
    add.add_argument("--report-ready", action="store_true")
    add.add_argument("--remaining-risk", default="")
    add.add_argument("--tag", action="append")
    add.add_argument("--artifact", action="append")

    backfill = subparsers.add_parser("backfill-git", help="append classified git commits")
    backfill.set_defaults(func=cmd_backfill_git)
    backfill.add_argument("--revision-range", default="origin/main..HEAD")
    backfill.add_argument("--limit", type=int, default=30)
    backfill.add_argument("--linear-issue", default="")
    backfill.add_argument("--github-pr", default="")
    backfill.add_argument("--session-id", default="")
    backfill.add_argument("--source-session-id", default="")
    backfill.add_argument("--registrar-session-id", default="")
    backfill.add_argument("--worktree-path", default="")
    backfill.add_argument("--worktree-role", default="", choices=["", *sorted(WORKTREE_ROLES)])
    backfill.add_argument("--source-branch", default="")
    backfill.add_argument("--integration-branch", default="main")
    backfill.add_argument("--write", action="store_true")

    summarize = subparsers.add_parser("summarize", help="regenerate the Markdown index")
    summarize.set_defaults(func=cmd_summarize)

    validate = subparsers.add_parser("validate", help="validate the JSONL ledger")
    validate.set_defaults(func=cmd_validate)

    worktrees = subparsers.add_parser("audit-worktrees", help="print a read-only git worktree hygiene report")
    worktrees.set_defaults(func=cmd_audit_worktrees)
    worktrees.add_argument("--json", action="store_true")
    worktrees.add_argument("--show-status", action="store_true")

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
