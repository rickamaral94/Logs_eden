#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Offline checks for Eden Android diagnostic bundles.

  validate BUNDLE...          integrity, coverage and privacy checks of each bundle
  compare A B                 differences between two bundles (compare_reports.py)
  abba --a A... --b B...      repeated A/B (or AB/BA) runs: comparability and per-run medians

Bundles are read only. Nothing here attributes causes: comparisons list differences,
blockers that make a comparison invalid and caveats that weaken it.
"""

import argparse
import hashlib
import json
import os
import re
import statistics
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import compare_reports  # noqa: E402
from compare_reports import fmt, get  # noqa: E402

READABLE_SCHEMAS = (1, 2)
TEXT_FILES = (".json", ".jsonl", ".txt", ".log")
# Values that must never appear in a bundle; settings are matched as printed by LogSettings.
PRIVACY_PATTERNS = [
    ("console serial setting", re.compile(r"serial_(?:unit|battery)\D{1,8}[1-9]\d{3,}")),
    ("storage path", re.compile(r"/storage/emulated/\d+/|/sdcard/|/data/user/\d+/")),
    ("e-mail address", re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")),
]


class Bundle:
    def __init__(self, path):
        self.path = path
        self.files = {}
        if path.endswith(".zip"):
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    self.files[name] = archive.read(name)
        else:
            with open(path, "rb") as handle:
                self.files["report.json"] = handle.read()
        self.report = json.loads(self.files["report.json"])
        manifest = self.files.get("manifest.json")
        self.manifest = json.loads(manifest) if manifest else None

    @property
    def schema(self):
        return self.report.get("schema_version")

    def samples(self):
        raw = self.files.get("samples.jsonl", b"").decode("utf-8", "replace")
        out = []
        for line in raw.splitlines():
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
        return out


# ---------------------------------------------------------------------------------------------
# validate


def validate(bundle):
    """Returns a list of (level, message); level is error, warning or info."""
    findings = []
    report = bundle.report

    def add(level, message):
        findings.append((level, message))

    if bundle.schema not in READABLE_SCHEMAS:
        add("error", f"schema_version {bundle.schema} is not readable by this tool")
    if bundle.schema == 1:
        add("warning", "schema 1: native events may include earlier captures "
                       "(during_capture is not reliable) and logs/eden_log.txt is the file tail")

    if bundle.manifest is None:
        if bundle.schema and bundle.schema >= 2:
            add("error", "manifest.json missing")
    else:
        for entry in bundle.manifest.get("files", []):
            name = entry.get("name")
            data = bundle.files.get(name)
            if data is None:
                add("error", f"manifest lists {name} but the bundle does not contain it")
                continue
            if len(data) != entry.get("bytes"):
                add("error", f"{name}: size {len(data)} differs from manifest {entry.get('bytes')}")
            if hashlib.sha256(data).hexdigest() != entry.get("sha256"):
                add("error", f"{name}: SHA-256 differs from manifest")
        for reason in bundle.manifest.get("incomplete_reasons", []):
            add("warning", f"bundle marked incomplete: {reason}")

    session = report.get("session") or {}
    if session.get("state") == "completed" and get(report, "native_summary", "capturing") is True:
        add("warning", "native summary was taken while still capturing (schema 1 stop order)")

    start = get(report, "native_summary", "capture_start_mono_ms")
    stop = get(report, "native_summary", "capture_stop_mono_ms")
    duration = session.get("duration_ms")
    if stop is None and start is not None and duration is not None:
        stop = start + duration
    seq = get(report, "identity", "native_capture_seq")
    native = get(report, "events", "native", default=[]) or []
    outside = []
    for event in native:
        mono = event.get("mono_ms")
        if seq is not None and event.get("capture_seq") is not None:
            if event.get("capture_seq") != seq:
                outside.append(event)
        elif start is not None and mono is not None and (mono < start or (stop and mono > stop)):
            outside.append(event)
    if outside:
        add("error" if bundle.schema and bundle.schema >= 2 else "warning",
            f"{len(outside)} of {len(native)} events.native lie outside this capture")
    monos = [e.get("mono_ms") for e in native if isinstance(e.get("mono_ms"), (int, float))]
    if monos != sorted(monos):
        add("warning", "events.native are not in time order")

    log = report.get("log_coverage")
    if isinstance(log, dict):
        if log.get("available") is False:
            add("warning", f"log not included: {log.get('reason')}")
        elif not log.get("complete"):
            add("warning", "log incomplete: start found={} stop found={} omitted={} B".format(
                log.get("capture_start_found"), log.get("capture_stop_found"),
                log.get("omitted_bytes")))
    elif "logs/eden_log.txt" in bundle.files:
        text = bundle.files["logs/eden_log.txt"]
        if text and not text.endswith(b"\n"):
            add("warning", "logs/eden_log.txt ends mid-line (copied while being written)")
        sid = session.get("session_id")
        if sid and f"Capture {sid} stopped".encode() not in text:
            add("warning", "logs/eden_log.txt does not contain the capture stop line")

    gpu = report.get("gpu_timing")
    if isinstance(gpu, dict):
        if gpu.get("available") is False:
            add("info", f"GPU timing unavailable: {gpu.get('reason')}")
        else:
            coverage = get(gpu, "session", "coverage_pct")
            if isinstance(coverage, (int, float)) and coverage < 90:
                add("warning", f"GPU timestamp coverage {coverage:.1f}% (< 90%)")
    else:
        add("info", "no GPU timing in this report (schema 1)")

    samples = bundle.samples()
    times = [s.get("t_ms") for s in samples if isinstance(s.get("t_ms"), (int, float))]
    if times != sorted(times):
        add("error", "samples.jsonl is not in time order")
    interval = session.get("interval_ms")
    if interval and len(times) > 1:
        gaps = [b - a for a, b in zip(times, times[1:]) if b - a > interval * 1.5]
        if gaps:
            add("warning", f"{len(gaps)} sample gap(s), longest {max(gaps)} ms")

    for name, status in sorted((report.get("availability") or {}).items()):
        if isinstance(status, str) and status.startswith("unavailable"):
            add("info", f"metric {name}: {status}")

    for name, data in bundle.files.items():
        if not name.endswith(TEXT_FILES):
            continue
        text = data.decode("utf-8", "replace")
        for label, pattern in PRIVACY_PATTERNS:
            match = pattern.search(text)
            if match:
                add("error", f"{name}: possible {label} not redacted")
    return findings


# ---------------------------------------------------------------------------------------------
# abba


def run_metrics(bundle, warmup_s=0.0):
    """Per-run metrics. Whole-capture percentiles come from the merged native histograms;
    a warm-up cut recomputes only count-based metrics from the sample windows, since window
    percentiles cannot be merged."""
    report = bundle.report
    minutes = compare_reports.duration_minutes(report)
    guest = get(report, "native_summary", "frames", "guest_present_interval", default={}) or {}
    gpu = get(report, "gpu_timing", "session", "frame_gpu", default={}) or {}
    fence_us = get(report, "native_summary", "timers", "fence_wait", "total_us")
    metrics = {
        "guest_fps_avg": get(report, "frame_rate", "guest_present_fps_avg"),
        "guest_interval_p50_ms": guest.get("p50_ms"),
        "guest_interval_p99_ms": guest.get("p99_ms"),
        "frame_gpu_p50_ms": gpu.get("p50_ms"),
        "frame_gpu_p99_ms": gpu.get("p99_ms"),
        "fence_wait_ms_per_min": (fence_us / 1000.0 / minutes) if fence_us and minutes else None,
        "process_cpu_pct_avg": get(report, "android_summary", "process_cpu_pct_of_one_core", "avg"),
        "gpu_clock_mhz_avg": get(report, "android_summary", "gpu_clock_mhz_kgsl", "avg"),
        "sampler_cost_ms_avg": get(report, "android_summary", "sampler_cost_ms", "avg"),
    }
    if warmup_s > 0:
        frames = window_ms = slow = 0
        gpu_sum = gpu_count = 0.0
        for sample in bundle.samples():
            if (sample.get("t_ms") or 0) < warmup_s * 1000:
                continue
            native = sample.get("native") or {}
            interval = get(native, "frames", "guest_present_interval", default={}) or {}
            frames += interval.get("count") or 0
            window_ms += native.get("window_ms") or 0
            pacing = interval.get("pacing") or {}
            slow += sum(v for k, v in pacing.items() if k not in ("lt_12ms", "12_20ms"))
            frame_gpu = get(native, "gpu_timing", "frame_gpu", default={}) or {}
            if frame_gpu.get("count"):
                gpu_sum += frame_gpu["count"] * frame_gpu.get("mean_ms", 0.0)
                gpu_count += frame_gpu["count"]
        metrics = {
            "guest_fps_avg": frames * 1000.0 / window_ms if window_ms else None,
            "guest_frames_over_20ms_pct": slow * 100.0 / frames if frames else None,
            "frame_gpu_mean_ms": gpu_sum / gpu_count if gpu_count else None,
            "process_cpu_pct_avg": metrics["process_cpu_pct_avg"],
        }
    return metrics


def identity(report):
    swapchain = get(report, "vulkan", "swapchain", default={}) or {}
    driver = get(report, "vulkan", "device", "driver", default={}) or {}
    return {
        "game": get(report, "game", "frontend", "program_id"),
        "game_build": get(report, "game", "native", "build_id"),
        "eden_revision": get(report, "build", "revision"),
        "driver": (driver.get("id"), driver.get("info"), get(report, "vulkan", "device",
                                                              "driver_version_raw")),
        "settings": get(report, "configuration_hashes", "effective_settings_sha256")
        or json.dumps(compare_reports.settings_map(report), sort_keys=True),
        "resolution": (swapchain.get("width"), swapchain.get("height")),
        "present_mode": swapchain.get("present_mode"),
        "cpu_backend": report.get("cpu_backend"),
        "capture_level": get(report, "session", "level"),
    }


VARIABLE_FIELDS = {
    "driver": {"driver"},
    "build": {"eden_revision"},
    "settings": {"settings", "present_mode", "resolution"},
    "none": set(),
}


def comparability(runs, variable):
    """Blockers make the comparison invalid; caveats weaken it."""
    blockers, caveats = [], []
    allowed = VARIABLE_FIELDS[variable]
    ids = [(label, identity(bundle.report)) for label, bundle in runs]
    for field in ids[0][1]:
        values = {json.dumps(i[field], sort_keys=True, default=str) for _, i in ids}
        if len(values) > 1:
            groups = {}
            for label, i in ids:
                groups.setdefault(label, set()).add(json.dumps(i[field], default=str))
            if field in allowed:
                if any(len(v) > 1 for v in groups.values()):
                    blockers.append(f"{field} differs within a group")
            else:
                blockers.append(f"{field} differs between runs: {sorted(values)}")
    if allowed and all(
        len({json.dumps(i[f], default=str) for _, i in ids}) == 1 for f in allowed
    ):
        caveats.append(f"the declared variable ({variable}) is identical in all runs")

    def per_run(path):
        return [(label, get(bundle.report, *path)) for label, bundle in runs]

    cache = {get(b.report, "configuration_hashes", "shader_cache_state", "observed")
             or ("present" if (get(b.report, "vulkan", "shader_cache", "pipelines_in_file") or 0)
                 > 0 else "empty") for _, b in runs}
    if len(cache) > 1:
        caveats.append(f"shader cache state differs: {sorted(map(str, cache))}")
    thermal = [v for _, v in per_run(("android_summary", "thermal_status_max")) if v is not None]
    if thermal and max(thermal) > 0:
        caveats.append(f"thermal status reached {max(thermal)} in at least one run")
    temps = [v for _, v in per_run(("android_summary", "battery_temp_c_max")) if v is not None]
    if temps and max(temps) - min(temps) > 5:
        caveats.append(f"battery temperature spread {max(temps) - min(temps):.1f} °C")
    limits = {v for _, v in per_run(("android_summary", "gpu_max_clock_mhz_kgsl", "min"))
              if v is not None}
    if len(limits) > 1:
        caveats.append(f"GPU clock upper limit differs: {sorted(limits)} MHz")
    durations = [v for _, v in per_run(("session", "duration_ms")) if v]
    if durations and max(durations) > 1.25 * min(durations):
        caveats.append("capture durations differ by more than 25%")
    for label, bundle in runs:
        if bundle.schema == 1:
            caveats.append(f"{label} {bundle.path}: schema 1 bundle")
        if bundle.manifest and not bundle.manifest.get("complete", True):
            caveats.append(f"{label} {bundle.path}: incomplete "
                           f"({', '.join(bundle.manifest.get('incomplete_reasons', []))})")
    order = [label for label, bundle in sorted(
        runs, key=lambda r: get(r[1].report, "session", "started_at_utc") or "")]
    if len(order) >= 4 and order == sorted(order):
        caveats.append("all A runs precede all B runs; interleave (AB/BA) to spread drift")
    return blockers, caveats


def abba(runs, variable, warmup_s=0.0):
    out = []
    blockers, caveats = comparability(runs, variable)
    out.append("== Comparability ==")
    for blocker in blockers:
        out.append(f"  BLOCKER: {blocker}")
    for caveat in caveats:
        out.append(f"  caveat: {caveat}")
    if not blockers and not caveats:
        out.append("  (no blockers or caveats found)")
    out.append("")
    metrics = [(label, bundle.path, run_metrics(bundle, warmup_s)) for label, bundle in runs]
    names = list(metrics[0][2])
    out.append("== Runs ==" + (f" (after {warmup_s:g} s warm-up)" if warmup_s else ""))
    for label, path, values in metrics:
        out.append(f"  {label} {path}")
        out.append("    " + ", ".join(f"{k}={fmt(v)}" for k, v in values.items()))
    out.append("")
    out.append("== Median per group (min - max), delta of medians ==")
    for name in names:
        groups = {}
        for label, _, values in metrics:
            if isinstance(values.get(name), (int, float)):
                groups.setdefault(label, []).append(values[name])
        if not groups:
            continue
        parts = []
        medians = {}
        for label in sorted(groups):
            vals = groups[label]
            medians[label] = statistics.median(vals)
            parts.append(f"{label} {fmt(medians[label])} ({fmt(min(vals))} - {fmt(max(vals))}, "
                         f"n={len(vals)})")
        delta = ""
        if "A" in medians and "B" in medians and medians["A"]:
            delta = f"  B vs A {(medians['B'] - medians['A']) / medians['A'] * 100:+.1f}%"
        out.append(f"  {name}: " + "; ".join(parts) + delta)
    out.append("")
    if blockers:
        out.append("Blockers present: the differences above are not a valid comparison.")
    out.append("Raw per-run values are listed above; differences are not causes.")
    return "\n".join(out) + "\n", bool(blockers)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)
    v = sub.add_parser("validate", help="check bundle integrity, coverage and privacy")
    v.add_argument("bundles", nargs="+")
    c = sub.add_parser("compare", help="differences between two bundles")
    c.add_argument("a")
    c.add_argument("b")
    r = sub.add_parser("abba", help="repeated A/B runs with medians and comparability checks")
    r.add_argument("--a", nargs="+", required=True, help="bundles of condition A")
    r.add_argument("--b", nargs="+", required=True, help="bundles of condition B")
    r.add_argument("--variable", choices=sorted(VARIABLE_FIELDS), default="driver",
                   help="what is meant to differ between A and B (default: driver)")
    r.add_argument("--warmup-s", type=float, default=0.0,
                   help="ignore sample windows before this many seconds")
    args = parser.parse_args(argv)

    if args.command == "validate":
        status = 0
        for path in args.bundles:
            print(f"== {path} ==")
            findings = validate(Bundle(path))
            for level, message in findings:
                print(f"  {level}: {message}")
            if not findings:
                print("  ok")
            if any(level == "error" for level, _ in findings):
                status = 1
        return status
    if args.command == "compare":
        sys.stdout.write(compare_reports.compare(Bundle(args.a).report, Bundle(args.b).report))
        return 0
    runs = [("A", Bundle(p)) for p in args.a] + [("B", Bundle(p)) for p in args.b]
    text, blocked = abba(runs, args.variable, args.warmup_s)
    sys.stdout.write(text)
    return 2 if blocked else 0


if __name__ == "__main__":
    sys.exit(main())
