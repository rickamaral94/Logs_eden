# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

import json
import os
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import compare_reports  # noqa: E402


def report(driver, vsync, misses, p99, duration_ms=60000, extensions=("VK_KHR_swapchain",)):
    return {
        "schema_version": 1,
        "session": {"session_id": f"s-{driver}", "level": "standard"},
        "build": {"version": "v1"},
        "game": {"frontend": {"title": "T", "program_id": "0100000000010000"}},
        "settings": [{"category": "Renderer", "name": "use_vsync", "value": vsync}],
        "vulkan": {"device": {"driver": {"name": driver}, "extensions_loaded": list(extensions),
                              "eden_feature_flags": {"extended_dynamic_state": True}}},
        "native_summary": {
            "duration_ms": duration_ms,
            "counters": {"graphics_pipeline_cache_miss": misses},
            "timers": {"pipeline_build_wait": {"total_us": misses * 1000, "max_us": 500}},
            "frames": {"guest_present_interval": {"p99_ms": p99}},
        },
        "events": {"native": [{"severity": "error", "category": "vulkan", "name": "check_failed",
                               "repeat": 2, "fields": {"result": "VK_ERROR_OUT_OF_DATE_KHR"}}]},
        "markers": [],
    }


class CompareReportsTest(unittest.TestCase):
    def test_lists_differences(self):
        a = report("Turnip", "1", 60, 20.0)
        b = report("Qualcomm", "2", 30, 40.0, duration_ms=30000,
                   extensions=("VK_KHR_swapchain", "VK_EXT_memory_budget"))
        text = compare_reports.compare(a, b)
        self.assertIn("Driver name: Turnip  ->  Qualcomm", text)
        self.assertIn("Renderer.use_vsync: 1  ->  2", text)
        self.assertIn("VK_EXT_memory_budget: not loaded  ->  loaded", text)
        # 60 misses in 1 min vs 30 misses in 0.5 min: same rate.
        self.assertIn("graphics_pipeline_cache_miss: 60.00  ->  60.00 (+0.0%)", text)
        self.assertIn("Guest frame interval p99: 20.00  ->  40.00 (+100.0%)", text)
        self.assertIn("check_failed x2", text)
        self.assertIn("not causation", text)

    def test_identical_reports_have_no_setting_differences(self):
        a = report("Turnip", "1", 10, 16.0)
        text = compare_reports.compare(a, a)
        self.assertIn("== Settings ==\n  (no differences)", text)

    def test_reads_bundles_and_warns_on_unknown_schema(self):
        b = report("Turnip", "1", 10, 16.0)
        b["schema_version"] = 99
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "bundle.zip")
            with zipfile.ZipFile(path, "w") as bundle:
                bundle.writestr("report.json", json.dumps(b))
            loaded = compare_reports.load_report(path)
        text = compare_reports.compare(report("Turnip", "1", 10, 16.0), loaded)
        self.assertIn("schema_version 99", text)


if __name__ == "__main__":
    unittest.main()
