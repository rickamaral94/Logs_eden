# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

import copy
import glob
import hashlib
import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import eden_diag  # noqa: E402


def report(driver="Turnip", fps=60.0, gpu_p50=10.0, started="2026-01-01T00:00:00Z",
           thermal=0, game="0100000000010000"):
    return {
        "schema_version": 2,
        "session": {"session_id": "s", "state": "completed", "started_at_utc": started,
                    "duration_ms": 60000, "interval_ms": 2000, "level": "standard"},
        "identity": {"native_capture_seq": 2},
        "build": {"revision": "abc"},
        "cpu_backend": "NCE",
        "game": {"frontend": {"program_id": game}, "native": {"build_id": "B1"}},
        "configuration_hashes": {"effective_settings_sha256": "h1",
                                 "shader_cache_state": {"observed": "disk_cache_present"}},
        "vulkan": {"device": {"driver": {"id": driver, "info": driver},
                              "driver_version_raw": 1},
                   "swapchain": {"width": 1920, "height": 1080, "present_mode": "FIFO"}},
        "native_summary": {"capturing": False, "capture_start_mono_ms": 1000,
                           "capture_stop_mono_ms": 61000, "duration_ms": 60000,
                           "frames": {"guest_present_interval": {"p50_ms": 1000 / fps,
                                                                 "p99_ms": 2000 / fps}},
                           "timers": {"fence_wait": {"total_us": 60000}}},
        "frame_rate": {"guest_present_fps_avg": fps},
        "gpu_timing": {"available": True, "session": {"coverage_pct": 99.0,
                                                      "frame_gpu": {"p50_ms": gpu_p50}}},
        "android_summary": {"thermal_status_max": thermal, "battery_temp_c_max": 30.0},
        "events": {"native": [{"name": "long_frame", "capture_seq": 2, "mono_ms": 2000}]},
        "log_coverage": {"available": True, "complete": True},
        "availability": {"thermal_headroom": "unavailable: not reported"},
    }


def write_bundle(directory, name, rep, extra=None, corrupt=None):
    files = {"report.json": json.dumps(rep).encode(), "summary.txt": b"ok\n"}
    files.update(extra or {})
    manifest = {"complete": True, "incomplete_reasons": [], "files": [
        {"name": n, "bytes": len(d), "sha256": hashlib.sha256(d).hexdigest()}
        for n, d in files.items()]}
    if corrupt:
        files[corrupt] = files[corrupt] + b"tampered"
    path = os.path.join(directory, name)
    with zipfile.ZipFile(path, "w") as bundle:
        for n, d in files.items():
            bundle.writestr(n, d)
        bundle.writestr("manifest.json", json.dumps(manifest))
    return path


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def levels(self, path):
        return eden_diag.validate(eden_diag.Bundle(path))

    def test_clean_bundle_has_no_errors(self):
        path = write_bundle(self.tmp.name, "a.zip", report())
        self.assertFalse([f for f in self.levels(path) if f[0] == "error"])

    def test_manifest_mismatch_is_an_error(self):
        path = write_bundle(self.tmp.name, "a.zip", report(), corrupt="summary.txt")
        messages = [m for level, m in self.levels(path) if level == "error"]
        self.assertTrue(any("SHA-256" in m for m in messages))

    def test_events_of_another_capture_are_an_error_in_schema_2(self):
        rep = report()
        rep["events"]["native"].append({"name": "long_frame", "capture_seq": 1, "mono_ms": 500})
        path = write_bundle(self.tmp.name, "a.zip", rep)
        messages = [m for level, m in self.levels(path) if level == "error"]
        self.assertIn("1 of 2 events.native lie outside this capture", messages)

    def test_privacy_leaks_are_reported(self):
        log = b"[ 1.0] Config <Info> LogSettings: M- Debugging.serial_unit: 217324292\n"
        path = write_bundle(self.tmp.name, "a.zip", report(), {"logs/eden_log.txt": log})
        messages = [m for level, m in self.levels(path) if level == "error"]
        self.assertTrue(any("console serial" in m for m in messages))
        redacted = b"[ 1.0] Config <Info> LogSettings: M- Debugging.serial_unit: <redacted>\n"
        path = write_bundle(self.tmp.name, "b.zip", report(), {"logs/eden_log.txt": redacted})
        self.assertFalse([m for level, m in self.levels(path) if level == "error"])

    def test_low_gpu_coverage_is_a_warning(self):
        rep = report()
        rep["gpu_timing"]["session"]["coverage_pct"] = 40.0
        path = write_bundle(self.tmp.name, "a.zip", rep)
        self.assertIn(("warning", "GPU timestamp coverage 40.0% (< 90%)"), self.levels(path))


class AbbaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.tmp.cleanup()

    def runs(self, reports):
        out = []
        for i, (label, rep) in enumerate(reports):
            path = write_bundle(self.tmp.name, f"{i}.zip", rep)
            out.append((label, eden_diag.Bundle(path)))
        return out

    def test_driver_ab_reports_medians_without_blockers(self):
        runs = self.runs([
            ("A", report("Turnip", 60.0, 10.0, "2026-01-01T00:00:00Z")),
            ("B", report("Proprietary", 50.0, 12.0, "2026-01-01T00:10:00Z")),
            ("B", report("Proprietary", 52.0, 12.0, "2026-01-01T00:20:00Z")),
            ("A", report("Turnip", 58.0, 11.0, "2026-01-01T00:30:00Z")),
        ])
        text, blocked = eden_diag.abba(runs, "driver")
        self.assertFalse(blocked)
        self.assertIn("guest_fps_avg: A 59.00 (58.00 - 60.00, n=2); B 51.00", text)
        self.assertIn("B vs A -13.6%", text)
        self.assertNotIn("all A runs precede all B runs", text)

    def test_other_differences_block_the_comparison(self):
        other_game = report("Proprietary", game="0100000000020000")
        runs = self.runs([("A", report()), ("B", other_game)])
        text, blocked = eden_diag.abba(runs, "driver")
        self.assertTrue(blocked)
        self.assertIn("BLOCKER: game differs between runs", text)

    def test_caveats_for_order_heat_and_unchanged_variable(self):
        runs = self.runs([
            ("A", report(started="2026-01-01T00:00:00Z")),
            ("A", report(started="2026-01-01T00:10:00Z")),
            ("B", report(started="2026-01-01T00:20:00Z", thermal=2)),
            ("B", report(started="2026-01-01T00:30:00Z")),
        ])
        text, _ = eden_diag.abba(runs, "driver")
        self.assertIn("all A runs precede all B runs", text)
        self.assertIn("thermal status reached 2", text)
        self.assertIn("the declared variable (driver) is identical in all runs", text)

    def test_warmup_uses_count_based_metrics(self):
        rep = report()
        samples = [
            {"t_ms": 2000, "native": {"window_ms": 2000, "frames": {"guest_present_interval": {
                "count": 30, "pacing": {"12_20ms": 0, "28_38ms": 30}}}}},
            {"t_ms": 12000, "native": {"window_ms": 2000, "frames": {"guest_present_interval": {
                "count": 120, "pacing": {"12_20ms": 114, "28_38ms": 6}}},
                "gpu_timing": {"frame_gpu": {"count": 120, "mean_ms": 9.0}}}},
        ]
        path = write_bundle(self.tmp.name, "w.zip", rep, {
            "samples.jsonl": "\n".join(json.dumps(s) for s in samples).encode()})
        metrics = eden_diag.run_metrics(eden_diag.Bundle(path), warmup_s=10)
        self.assertAlmostEqual(metrics["guest_fps_avg"], 60.0)
        self.assertAlmostEqual(metrics["guest_frames_over_20ms_pct"], 5.0)
        self.assertAlmostEqual(metrics["frame_gpu_mean_ms"], 9.0)


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


class DeviceBundleTest(unittest.TestCase):
    """Reads device bundles from EDEN_DIAG_FIXTURES when set; they are not committed."""

    def test_schema_one_device_bundles(self):
        directory = os.environ.get("EDEN_DIAG_FIXTURES")
        if not directory:
            self.skipTest("EDEN_DIAG_FIXTURES not set")
        bundles = sorted(glob.glob(os.path.join(directory, "*.zip")))
        before = {p: digest(p) for p in bundles}
        for path in bundles:
            findings = eden_diag.validate(eden_diag.Bundle(path))
            self.assertTrue(any("schema 1" in m for _, m in findings))
            self.assertTrue(any("ends mid-line" in m for _, m in findings))
        runs = [("A", eden_diag.Bundle(bundles[0])), ("B", eden_diag.Bundle(bundles[-1]))]
        _, blocked = eden_diag.abba(copy.copy(runs), "driver")
        self.assertTrue(blocked)  # different games, same driver: not an A/B
        after = {p: digest(p) for p in bundles}
        self.assertEqual(before, after)


if __name__ == "__main__":
    unittest.main()
