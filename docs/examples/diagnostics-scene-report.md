# Example: scene report and AB/BA comparison

All numbers below are **synthetic**. They come from the unit test fixtures
(`DiagnosticsReportBuilderTest`, `tools/diagnostics/tests`), not from a device. They show the
format, not a measurement or a result. Field meanings are in [Diagnostics.md](../Diagnostics.md),
section "Scenes and per host frame records".

## events.jsonl: scene markers

```json
{"t_ms":1500,"mono_ms":2500,"utc":"2026-01-01T00:00:01.500Z","type":"scene_marker","kind":"scene_start","scene_id":1,"name":"bridge","reason":"user","wall_ms":1767225602500,"mono_us":2500120,"host_frame_id":40,"capture_seq":2,"capturing":true}
{"t_ms":4500,"mono_ms":5500,"utc":"2026-01-01T00:00:04.500Z","type":"scene_marker","kind":"scene_end","scene_id":1,"name":"bridge","reason":"user","wall_ms":1767225605500,"mono_us":5500087,"host_frame_id":160,"capture_seq":2,"capturing":true}
```

The scene holds the whole host frames 41 to 159. Frames 40 and 160 were in progress at the
markers and are left out.

## frames.jsonl: one line per drain (columns shortened here)

```json
{"capture_id":"20260101-000000-0001","mono_ms":6000,"frames":{"columns":["frame","end_mono_us","interval_us","across_pause","game_frames","draws","passes","reopens","end_next_target","end_readback","reopen_after_readback","pipelines_created","fence_wait_us","gpu_us","gpu_state"],"gpu_states":["pending","complete","partial","missing"],"row_count":200,"rows":[[49,2225000,25000,0,1,450,120,41,79,2,2,0,4000,12000,1],[50,2250000,60000,0,1,450,120,41,79,12,12,0,4000,30000,1]]}}
```

Device captures have all columns: every `end_<reason>` and `reopen_after_<reason>`, the wait
reasons, submits, dispatches, staging bytes and pipeline build wait.

## summary.txt: Scenes section

```text
== Scenes (per host frame, see scenes in report.json) ==
Scene 1 "bridge": 119 frames, host FPS 35.4, interval p50/p90/p99 ms 25.0/25.0/60.0, GPU p50 12.0 ms (coverage 100.0%)
  per frame: passes 120.0, reopens 41.0, draws 450.0, pipelines 0.0
  pass ends per frame: next_target 79.0 (reopen n/a), readback 2.9 (reopen 2.9)
  slow frames (>= 37.5 ms): 11; higher in slow frames (hypothesis): end_readback x6.0, reopen_after_readback x6.0, gpu_us x2.5
```

How to read it: in this synthetic scene, the frames at least 1.5x the median interval have six
times as many render pass ends caused by image readbacks as the other frames. That is a
co-occurrence in one run. It becomes a hypothesis worth testing (for example: "readback splits
cost GPU time in this scene"). It is not a cause until a controlled AB/BA comparison of the same
scene changes that one thing and the frame time follows.

## report.json: scenes (excerpt)

```json
{
  "scene_id": 1,
  "name": "bridge",
  "start": {"mono_ms": 2500, "host_frame_id": 40, "capture_seq": 2, "capturing": true},
  "end": {"mono_ms": 5500, "host_frame_id": 160, "capture_seq": 2, "capturing": true},
  "frame_selection": "host frames with start.host_frame_id < frame < end.host_frame_id",
  "frames_expected": 119, "frames_recorded": 119, "frames_used": 119,
  "host_fps": 35.42,
  "frame_interval_ms": {"count": 119, "mean": 28.24, "p50": 25.0, "p90": 25.0, "p99": 60.0, "max": 60.0},
  "gpu_ms": {"count": 119, "mean": 13.66, "p50": 12.0, "p90": 12.0, "p99": 30.0, "max": 30.0},
  "gpu_coverage_pct": 100.0,
  "render_pass_ends": {
    "next_target": {"total": 9401, "per_frame": 79.0, "reopened_same_target": 0},
    "readback": {"total": 348, "per_frame": 2.92, "reopened_same_target": 348, "reopened_per_frame": 2.92}
  },
  "slow_frames": {
    "threshold_ms": 37.5, "slow": 11, "other": 108,
    "mean_per_frame": {
      "end_readback": {"slow": 12.0, "other": 2.0, "ratio": 6.0},
      "gpu_us": {"slow": 30000.0, "other": 12000.0, "ratio": 2.5},
      "passes": {"slow": 120.0, "other": 120.0, "ratio": 1.0}
    },
    "slowest": [{"frame": 50, "interval_ms": 60.0, "gpu_ms": 30.0, "passes": 120, "reopens": 41,
                 "ended_by": {"next_target": 79, "readback": 12}}],
    "note": "co-occurrence within this scene only; confirm with AB/BA runs of the same scene before attributing a cause"
  },
  "system": {"samples": 2, "thermal_status": {"count": 2, "avg": 1.5, "min": 1.0, "max": 2.0}},
  "coverage_notes": []
}
```

`comparison_key` sits next to it with the APK version, settings hashes, driver package, driver
ELF SHA-256 and build-id, shader cache state, `resolution_setup` and swapchain size.

## AB/BA comparison of the scene

Four synthetic runs (A and B differ only in the driver ELF):

```text
$ python3 tools/diagnostics/eden_diag.py abba --a run0-A.zip run3-A.zip --b run1-B.zip run2-B.zip --scene forest-run
== Comparability ==
  (no blockers or caveats found)

== Median per group (min - max), delta of medians ==
  host_fps: A 40.30 (40.20 - 40.40, n=2); B 40.35 (40.10 - 40.60, n=2)  B vs A +0.1%
  interval_p50_ms: A 24.81 (24.75 - 24.88, n=2); B 24.78 (24.63 - 24.94, n=2)  B vs A -0.1%
  gpu_p50_ms: A 12.00 (12.00 - 12.00, n=2); B 12.00 (12.00 - 12.00, n=2)  B vs A +0.0%
  passes_per_frame: A 121.00 (121.00 - 121.00, n=2); B 121.00 (121.00 - 121.00, n=2)  B vs A +0.0%
  reopens_per_frame: A 41.00 (41.00 - 41.00, n=2); B 41.00 (41.00 - 41.00, n=2)  B vs A +0.0%
  end_blit_per_frame: A 2.95 (2.90 - 3.00, n=2); B 2.85 (2.80 - 2.90, n=2)  B vs A -3.4%
  end_readback_per_frame: A 6.10 (6.10 - 6.10, n=2); B 6.10 (6.00 - 6.20, n=2)  B vs A +0.0%

Raw per-run values are listed above; differences are not causes.
```

(The per-run section is omitted here.) The tool blocks the comparison when anything other than
the declared variable differs (game, APK, settings hash, resolution, driver ELF when the variable
is not `driver`) or when a run has no scene with that name. Ranges that overlap, as here, mean
two pairs are not enough to call a difference; run five.
