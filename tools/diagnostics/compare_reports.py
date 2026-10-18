#!/usr/bin/env python3
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

"""Compares two Eden Android diagnostic reports (bundle .zip or report.json).

Prints what differs between two sessions: build, driver, device, settings, Vulkan
configuration, frame times, pipeline counters, timers and memory. Counters and timers are
normalized per minute of capture so sessions of different length can be compared. The output
lists differences only; it does not attribute causes.
"""

import argparse
import json
import sys
import zipfile

SUPPORTED_SCHEMA = 1


def load_report(path):
    if path.endswith(".zip"):
        with zipfile.ZipFile(path) as bundle:
            with bundle.open("report.json") as report:
                return json.load(report)
    with open(path, encoding="utf-8") as report:
        return json.load(report)


def get(obj, *keys, default=None):
    for key in keys:
        if not isinstance(obj, dict) or key not in obj:
            return default
        obj = obj[key]
    return obj


def available(section):
    return isinstance(section, dict) and section.get("available", True) is not False


def fmt(value):
    if value is None:
        return "n/a"
    if isinstance(value, float):
        return f"{value:.2f}"
    return str(value)


def duration_minutes(report):
    duration_ms = get(report, "native_summary", "duration_ms") or get(report, "session", "duration_ms")
    if not duration_ms:
        return None
    return duration_ms / 60000.0


def per_minute(value, minutes):
    if value is None or not minutes:
        return None
    return value / minutes


def compare_fields(title, pairs, a, b, out):
    rows = []
    for label, keys in pairs:
        va, vb = get(a, *keys), get(b, *keys)
        if va != vb:
            rows.append((label, fmt(va), fmt(vb)))
    section(title, rows, out)


def section(title, rows, out):
    out.append(f"== {title} ==")
    if not rows:
        out.append("  (no differences)")
    for label, va, vb in rows:
        out.append(f"  {label}: {va}  ->  {vb}")
    out.append("")


def settings_map(report):
    result = {}
    for entry in get(report, "settings", default=[]) or []:
        if isinstance(entry, dict):
            result[f"{entry.get('category')}.{entry.get('name')}"] = entry.get("value")
    return result


def compare_settings(a, b, out):
    sa, sb = settings_map(a), settings_map(b)
    rows = []
    for key in sorted(set(sa) | set(sb)):
        if sa.get(key) != sb.get(key):
            rows.append((key, fmt(sa.get(key)), fmt(sb.get(key))))
    section("Settings", rows, out)


def compare_extensions(a, b, out):
    ea = set(get(a, "vulkan", "device", "extensions_loaded", default=[]) or [])
    eb = set(get(b, "vulkan", "device", "extensions_loaded", default=[]) or [])
    rows = [(name, "loaded", "not loaded") for name in sorted(ea - eb)]
    rows += [(name, "not loaded", "loaded") for name in sorted(eb - ea)]
    flags_a = get(a, "vulkan", "device", "eden_feature_flags", default={}) or {}
    flags_b = get(b, "vulkan", "device", "eden_feature_flags", default={}) or {}
    for name in sorted(set(flags_a) | set(flags_b)):
        if flags_a.get(name) != flags_b.get(name):
            rows.append((f"flag {name}", fmt(flags_a.get(name)), fmt(flags_b.get(name))))
    section("Vulkan extensions and Eden feature flags", rows, out)


def compare_numbers(title, keys_list, a, b, out, normalize=False):
    ma, mb = duration_minutes(a), duration_minutes(b)
    rows = []
    for label, keys in keys_list:
        va, vb = get(a, *keys), get(b, *keys)
        if normalize:
            va, vb = per_minute(va, ma), per_minute(vb, mb)
        if va is None and vb is None:
            continue
        delta = ""
        if isinstance(va, (int, float)) and isinstance(vb, (int, float)) and va:
            delta = f" ({(vb - va) / va * 100:+.1f}%)"
        rows.append((label, fmt(va), fmt(vb) + delta))
    out.append(f"== {title} ==")
    if not rows:
        out.append("  (not available in either report)")
    for label, va, vb in rows:
        out.append(f"  {label}: {va}  ->  {vb}")
    out.append("")


def compare(a, b):
    out = []
    for name, report in (("A", a), ("B", b)):
        version = report.get("schema_version")
        if version != SUPPORTED_SCHEMA:
            out.append(f"warning: report {name} has schema_version {version}, "
                       f"this tool understands {SUPPORTED_SCHEMA}")
    out.append(f"A: {get(a, 'session', 'session_id')}  {fmt(get(a, 'game', 'frontend', 'title'))}")
    out.append(f"B: {get(b, 'session', 'session_id')}  {fmt(get(b, 'game', 'frontend', 'title'))}")
    out.append("")

    compare_fields("Build and device", [
        ("Eden version", ("build", "version")),
        ("Eden revision", ("build", "revision")),
        ("CPU backend", ("cpu_backend",)),
        ("SoC", ("device", "soc_model")),
        ("Android API", ("device", "android_api")),
        ("Kernel", ("device", "kernel")),
        ("Program id", ("game", "frontend", "program_id")),
        ("Game build id", ("game", "native", "build_id")),
        ("Capture level", ("session", "level")),
    ], a, b, out)
    compare_fields("Driver", [
        ("Selection", ("graphics_driver", "selection")),
        ("Custom driver", ("graphics_driver", "custom", "name")),
        ("Custom driver version", ("graphics_driver", "custom", "version")),
        ("VkDriverId", ("vulkan", "device", "driver", "id")),
        ("Driver name", ("vulkan", "device", "driver", "name")),
        ("Driver info", ("vulkan", "device", "driver", "info")),
        ("Driver version (raw)", ("vulkan", "device", "driver_version_raw")),
        ("Vulkan API", ("vulkan", "device", "api_version")),
    ], a, b, out)
    compare_settings(a, b, out)
    compare_extensions(a, b, out)
    compare_fields("Swapchain and shader cache", [
        ("Format", ("vulkan", "swapchain", "format")),
        ("Present mode", ("vulkan", "swapchain", "present_mode")),
        ("Width", ("vulkan", "swapchain", "width")),
        ("Height", ("vulkan", "swapchain", "height")),
        ("Image count", ("vulkan", "swapchain", "image_count")),
        ("Pipelines in disk cache", ("vulkan", "shader_cache", "pipelines_in_file")),
        ("Pipelines restored", ("vulkan", "shader_cache", "pipelines_restored")),
        ("Driver pipeline cache bytes", ("vulkan", "shader_cache", "vulkan_driver_cache_bytes")),
        ("Asynchronous shaders", ("vulkan", "shader_cache", "asynchronous_shaders")),
    ], a, b, out)

    guest = ("native_summary", "frames", "guest_present_interval")
    compare_numbers("Frames (CPU wall clock, ms)", [
        ("Guest present FPS avg", ("frame_rate", "guest_present_fps_avg")),
        ("Game FPS avg", ("frame_rate", "game_fps_avg")),
        ("Guest frame interval p50", guest + ("p50_ms",)),
        ("Guest frame interval p90", guest + ("p90_ms",)),
        ("Guest frame interval p99", guest + ("p99_ms",)),
        ("Guest frame interval p99.9", guest + ("p999_ms",)),
        ("Guest frame interval max", guest + ("max_ms",)),
    ], a, b, out)

    counter_names = sorted(set(get(a, "native_summary", "counters", default={}) or {})
                           | set(get(b, "native_summary", "counters", default={}) or {}))
    compare_numbers("Counters per minute", [
        (name, ("native_summary", "counters", name)) for name in counter_names
    ], a, b, out, normalize=True)

    timer_names = sorted(set(get(a, "native_summary", "timers", default={}) or {})
                         | set(get(b, "native_summary", "timers", default={}) or {}))
    compare_numbers("CPU time inside/blocked on operation, µs per minute", [
        (name, ("native_summary", "timers", name, "total_us")) for name in timer_names
    ], a, b, out, normalize=True)
    compare_numbers("Longest single operation, µs", [
        (name, ("native_summary", "timers", name, "max_us")) for name in timer_names
    ], a, b, out)

    compare_numbers("Process and system", [
        ("Process CPU avg (% of one core)", ("android_summary", "process_cpu_pct_of_one_core", "avg")),
        ("RSS peak KiB", ("android_summary", "rss_peak_kb_max")),
        ("Native heap max KiB", ("android_summary", "native_heap_allocated_kb", "max")),
        ("Min available system memory KiB", ("android_summary", "system_mem_available_kb_min")),
        ("Max thermal status", ("android_summary", "thermal_status_max")),
        ("Max battery temperature °C", ("android_summary", "battery_temp_c_max")),
        ("Sampler cost avg ms", ("android_summary", "sampler_cost_ms", "avg")),
    ], a, b, out)

    for name, report in (("A", a), ("B", b)):
        markers = report.get("markers") or []
        native_events = get(report, "events", "native", default=[]) or []
        errors = [e for e in native_events if e.get("severity") in ("error", "critical")]
        out.append(f"{name}: {len(markers)} marker(s), {len(errors)} native error/critical event(s)")
        for event in errors[:10]:
            out.append(f"    {event.get('category')}/{event.get('name')} x{event.get('repeat', 1)} "
                       f"{json.dumps(event.get('fields', {}), ensure_ascii=False)}")
    out.append("")
    out.append("Differences only; correlation between them is not causation.")
    if not available(get(a, "native_summary")) or not available(get(b, "native_summary")):
        out.append("note: one capture did not finish; native totals may be missing.")
    return "\n".join(out) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("a", help="baseline bundle (.zip) or report.json")
    parser.add_argument("b", help="bundle (.zip) or report.json to compare")
    args = parser.parse_args(argv)
    sys.stdout.write(compare(load_report(args.a), load_report(args.b)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
