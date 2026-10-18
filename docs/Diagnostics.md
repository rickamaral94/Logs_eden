# Android diagnostic captures

The diagnostic capture records what the emulator, Vulkan and Android expose while a game runs, so
that rendering and performance problems can be reproduced and compared between devices, drivers
and builds. It measures; it does not change emulation behaviour.

User-facing instructions are in [user/DiagnosticCaptures.md](./user/DiagnosticCaptures.md).

## Components

| Layer | Files | Role |
| --- | --- | --- |
| Native core | `src/common/session_diagnostics.{h,cpp}` | Timers, counters, frame intervals, event ring, static sections, samplers |
| Vulkan | `vulkan_wrapper`, `vulkan_device`, `renderer_vulkan`, `vk_*` | Feeds the core (see [Native sources](#native-sources)) |
| JNI | `src/android/app/src/main/jni/native_diagnostics.cpp` | `org.yuzu.yuzu_emu.diagnostics.NativeDiagnostics` |
| Android | `src/android/app/src/main/java/org/yuzu/yuzu_emu/diagnostics/` | Session, Android metrics, report and bundle |
| UI | `fragments/Diagnostics*DialogFragment.kt` | In-game controls, capture list, export review |

### Overhead model

- Every native hook first reads a relaxed atomic flag. With no capture running a hook costs about
  1-2 ns (host x86_64 microbenchmark; not measured on a phone).
- While capturing, counters are relaxed atomic increments (~16 ns), timers read the steady clock
  twice (~100 ns) and frame intervals take a mutex once per frame (~20 ns). Timers only wrap
  operations that already take microseconds or longer (queue submits, fence waits, pipeline
  creation). Nothing is logged per frame.
- Static sections (Vulkan device, swapchain, shader cache) and the 256-entry event ring are always
  kept. They are written only on rare paths (device creation, swapchain creation, errors).
- Android metrics are sampled every 2 s (standard) or 1 s (detailed) on a background thread. Each
  sample records its own cost as `sampler_cost_ms`. `Debug.getPss()` walks `smaps`, so it only
  runs at the detailed level and only every 10 samples.
- `NativeLibrary.getPerfStats()` is **not** used: it resets the core statistics used by the
  performance overlay. Frame rates are derived from the native frame interval data instead.

### Limits and failure handling

- One capture at a time; it stops after 30 minutes, when `samples.jsonl` reaches 16 MiB, when
  free space drops below 64 MiB, on a write error, or when emulation stops.
- Samples and events are appended as JSON lines and `fsync`ed per sample, so a crash loses at most
  one interval. On Vulkan device loss the native core synchronously writes
  `native_critical_<mono_ms>.json` into the session directory.
- A session left in state `active` by a dead process is shown as `interrupted` and can still be
  exported. A truncated last JSON line is skipped.
- Exports are written to a `.partial` file and renamed only after the zip is complete. Free space
  is checked first. Failures are reported to the user with a reason
  (`insufficient_space`, `cannot_create_directory`, `write_failed`, `permission_denied`).
- Any metric the device does not expose is `null` and explained under `availability`. Missing
  metrics never stop the capture or the emulator.

## Storage layout

```
<user>/diagnostics/
  sessions/<yyyyMMdd-HHmmss>-<nnnn>/
    session.json          lifecycle (state, start/end, level, end_reason)
    static_start.json     app, device, driver, game, native static info at start
    static_end.json       native summary and static info at stop (missing if interrupted)
    samples.jsonl         one sample per line
    events.jsonl          capture lifecycle, user markers, Android system events
    native_critical_*.json
  exports/eden-diagnostics-<session>.zip
```

Nothing is uploaded. Sharing happens only through the Android share sheet after the user reviews
the bundle.

## Bundle contents

| File | Content |
| --- | --- |
| `report.json` | Structured report, `schema_version` 2 |
| `manifest.json` | Size and SHA-256 of every file, completeness flags, sample gaps, log and event coverage |
| `summary.txt` | Human readable summary of `report.json` |
| `README.txt` | Short description of the files |
| `samples.jsonl` | Raw samples (paths redacted) |
| `events.jsonl` | Events (fully redacted, includes user notes) |
| `native/native_critical_*.json` | Native state written on fatal errors |
| `logs/eden_log.txt` | Eden log lines of this capture, preceded by up to 64 KiB of labelled context; optional, see `log_coverage` |
| `logs/eden_gpu.log` | GPU logger output, optional, if present |
| `advanced/gpu_crashes/*` | GPU logger state dumps, opt-in only |

## Comparing reports

`tools/diagnostics/compare_reports.py A.zip B.zip` (or two `report.json` files) prints the
differences between two sessions: build, driver, settings, loaded extensions and Eden feature
flags, swapchain and shader cache, frame time percentiles, counters and timers normalized per
minute, process/system metrics and native error events. It uses only the Python standard
library and lists differences without attributing causes. Tests:
`python3 -m unittest discover -s tools/diagnostics/tests`.

## Session integrity (schema 2)

A process lifetime can run several titles and several captures, and its `eden_log.txt` and
native event ring hold all of them. Schema 2 ties every piece of data to its capture:

- Identifiers: `session_id` and `capture_id` (one session holds one capture), the native
  `native_capture_seq` (counts captures in the process; every native event carries
  `capture_seq`, 0 outside a capture), `game_run_id` (one emulated title from renderer
  initialization to shutdown; carried by every event) and `process_instance_id`
  (`boot<count>-pid<pid>-start<process start uptime>`).
- Clock: correlation uses `CLOCK_MONOTONIC` milliseconds (`mono_ms`; `SystemClock.uptimeMillis`
  on the Java side, `steady_clock` natively). UTC is presentation only. Log lines carry seconds
  since the logger started; `identity.clock.log_clock_origin_mono_ms` maps them.
- Frozen identity: build, game, CPU backend, settings and the Vulkan device come from capture
  start. `identity_changes` lists fields that differ at capture end; the start values are kept.
- Events: `events.native` holds only events of this capture; `events.native_context` holds
  older captures, renderer initialization and anything outside the capture, with
  `during_capture` recomputed. `events.native_scope` gives the ring capacity and how many events
  of this capture were overwritten. Schema 1 sessions are scoped by the monotonic capture
  interval (`scope_method: mono_interval`). Samples only carry events recorded after the capture
  started.
- Stop: the partial window since the last sample is written, then the native capture is stopped
  and the summary taken, so `native_summary.capturing` is `false` and `duration_ms` is final.
- Log: the log is flushed through the logger (the file backend writes through a stdio buffer;
  in the device captures that motivated this, about 3.7 KiB at the end of the file, including
  the stop line, had not been written yet). The capture is located by its
  `[Diagnostics] Capture <id> started/stopped` lines in the current or the previous process log
  (`eden_log.txt.old.txt`), not by blindly copying the file. `log_coverage` gives the source,
  byte offsets, context size, omitted bytes, whether both lines were found, whether the file
  ends mid-line and `complete`. A capture that cannot be located yields no log rather than the
  wrong one. `log_digest` counts levels, classes and known patterns (assert, guest panic,
  unmapped access, unimplemented/stubbed, Vulkan errors, device lost, shader errors) separately
  for the capture and for the context; it classifies text, it does not diagnose.
- Exits: `exit_reasons` entries get `category` (`native_crash`, `java_crash`, `anr`,
  `low_memory_kill`, `abort`, `killed_by_signal`, `normal_exit`, `stopped_by_user_or_system`,
  ...) and `relation`: `capture_process` only for the same pid ending after the capture started,
  otherwise `history`. `session_end.classification` separates `user_stopped_capture`,
  `game_exit`, `capture_limit_reached`, `vk_error_device_lost`, `process_ended:<category>` and
  `unknown`, with the evidence used.

## Report schema (version 2)

Field names are stable. Additive changes keep `schema_version`; renaming or removing a field, or
changing its meaning or unit, increments it and must be recorded in [Changelog](#schema-changelog).

Top-level fields of `report.json`:

| Field | Description |
| --- | --- |
| `schema_version` | Integer, currently `2`. Readers accept 1 and 2 |
| `identity` | Session, capture, game run and process ids; clock mapping |
| `session_end` | End classification and evidence |
| `identity_changes` | Fields that changed between capture start and end |
| `generated_at_utc`, `generator` | Export time and producer (`eden-android-diagnostics`) |
| `session` | Contents of `session.json`, or `{available:false}` for a snapshot |
| `app` | Application id, version name/code, build type, flavor |
| `device` | SoC, ABIs, Android release/API, kernel, cores, max frequencies, RAM, display. `identity` (manufacturer, model, device, product, fingerprint) is replaced by `"removed_by_user"` when the user opts out |
| `graphics_driver` | Driver selection as configured in Eden (`system` or `custom` + custom driver metadata) |
| `game.frontend` | Title, program id, homebrew flag, add-ons (name, version, type, enabled). Game files are never read |
| `game.native` | Program id and build id of the running process |
| `build` | Eden version, branch, description, revision, build id/date, compiler |
| `cpu_backend` | `NCE` or `JIT` |
| `settings` | `[ {category, name, value, is_default, per_game} ]`, filtered (see [Privacy](#privacy)) |
| `vulkan.device` | See [Vulkan device section](#vulkan-device-section) |
| `vulkan.swapchain` | Format, color space, present mode, available modes, extent, image counts, transform |
| `vulkan.shader_cache` | Pipelines in the disk cache, built, restored, load time, driver cache size, async shaders |
| `vulkan.memory_budget_peak` | Peak `VK_EXT_memory_budget` usage per heap during the capture |
| `native_summary` | Session totals: `timers`, `counters`, `frames` with histogram percentiles |
| `android_summary` | Aggregates of Android samples (avg/min/max), GC totals, sampler cost |
| `frame_rate` | Guest present, host present and game FPS averaged over the capture |
| `markers` | User markers with `context` built from samples within -10 s / +5 s |
| `hitches` | Up to 10 sample windows whose longest guest frame interval exceeds 100 ms, with the timers, counters, shader queue, CPU/GPU load and native events of the same window (correlation only) |
| `events.native` | Native events of this capture (Vulkan check failures, device loss, pipeline/shader failures, long frames, markers) |
| `events.native_context` | Native events outside this capture, labelled |
| `events.native_scope` | Capture sequence and interval used for scoping; ring capacity and drops |
| `events.android` | Capture lifecycle, `trim_memory`, `low_memory`, `thermal_status_changed` |
| `exit_reasons` | Last 5 `ApplicationExitInfo` entries (API 30+) with `pid`, `category` and `relation` |
| `log_coverage`, `log_digest` | See [Session integrity](#session-integrity-schema-2) |
| `availability` | Metric → `available`, `unavailable: <reason>` or `intermittent` |
| `metric_definitions` | Unit, scope, source and notes of every Android/native metric |
| `contents` | Files in the bundle with sizes |
| `privacy` | Redaction count, identity flag, excluded categories, never-included data |

### Units and meaning

- All `timers` are **CPU wall-clock** durations of the calling thread inside or blocked on an
  operation, in microseconds. They are **not GPU execution times**.
  - `fence_wait`: `MasterSemaphore::Wait` slow path.
  - `queue_submit`, `present_queue_submit`: inside `vkQueueSubmit`.
  - `pipeline_build_wait`: command recording blocked on a pipeline still being built
    (a direct measure of shader-compilation stalls).
  - `graphics_shader_translate`, `compute_shader_translate`: runtime cache misses, guest shader
    translation plus module creation on the emulated GPU thread.
  - `graphics_pipeline_create`, `compute_pipeline_create`: driver pipeline creation on worker
    threads. Creations of 250 ms or more are also recorded as events with the hash, so the
    shader can be identified and dumped.
- `counters` are event counts per window (samples) or per capture (summary):
  `graphics_pipeline_cache_miss`, `compute_pipeline_cache_miss`,
  `draw_skipped_pipeline_pending` (asynchronous shaders skipped a draw), `pipeline_create_failed`,
  `shader_translate_failed`, `swapchain_created`, `disk_pipelines_loaded`, `game_frames`.
- `frames.guest_present_interval`: interval between guest display flips (`nvdisp`), ms.
  `frames.host_present_interval`: interval between host composites in `RendererVulkan`, ms; this
  is not display scanout time. Window values are exact nearest-rank percentiles; session values
  come from a 0.25 ms histogram (`percentile_resolution_ms`). `over_33ms`, `over_50ms`,
  `over_100ms`, `over_2x_median` count slow frames.
- Android metrics declare `scope`: `process` (Eden only) or `system` (whole device, e.g. memory
  pressure, thermal state, CPU frequency, Adreno `kgsl` busy percentage and clock).
  `gpu_max_clock_mhz_kgsl` and `gpu_min_clock_mhz_kgsl` are the governor's current limits, so a
  clock held low by a lowered upper limit (thermal, power HAL, vendor performance modes) can be
  told apart from a low clock the governor chose.
- `process.memory.native_heap_allocated_kb` is the malloc heap only. Driver allocations that are
  mapped outside malloc are not included. GPU memory is only reported through
  `VK_EXT_memory_budget`, as the driver states it.
- Times: `mono_ms` is `CLOCK_MONOTONIC` in ms (`SystemClock.uptimeMillis()` on the Java side,
  `std::chrono::steady_clock` natively), `t_ms` is relative to the capture start.

### Vulkan device section

Produced once per renderer by `Device::GetDiagnosticsJson()`:

- `api_version`, `driver_version_raw`, `vendor_id`, `device_id`, `device_type`, `device_name`,
  `vendor_name`.
- `driver`: `VkDriverId`, driver name and info strings, Eden driver name, conformance version.
- `queue_families`: flags, counts, timestamp bits and which family Eden uses for graphics/present.
- `memory_heaps`, `memory_types`, `memory_budget_supported`, `eden_device_access_memory_bytes`.
- `limits`, `subgroup`, `push_descriptor_max`.
- `extensions_supported` (reported by the driver) vs `extensions_loaded` (enabled by Eden).
- `core_features_requested`: the `VkPhysicalDeviceFeatures` passed to `vkCreateDevice`.
- `eden_feature_flags`: Eden's own flags after suitability checks; `true` means in use.
- `quirks`: workarounds Eden detected for the device or driver.

### Native sources

| Hook | Location |
| --- | --- |
| Failed `vk::Check` / `vk::Filter` (result, function, line) | `vulkan_wrapper.{h,cpp}` |
| Device loss event + critical marker | `Device::ReportLoss` |
| Device section, memory budget sampler | `Device::GetDiagnosticsJson`, `GetMemoryBudgetJson`, `RendererVulkan::Report` |
| Renderer init failure | `RendererVulkan` constructor, `VideoCore::CreateGPU` |
| Swapchain section | `Swapchain::CreateSwapchain` |
| Submit timers | `Scheduler::SubmitExecution`, `PresentManager` |
| Fence wait timer | `MasterSemaphore::Wait` |
| Pipeline cache counters/timers, shader cache section, lifecycle stage | `PipelineCache` |
| Pipeline creation and build wait timers | `GraphicsPipeline`, `ComputePipeline` |
| Guest frame interval, game frames | `PerfStats::EndSystemFrame`, `EndGameFrame` |
| Host frame interval | `RendererVulkan::Composite` |

## Privacy

- Captures stay local. Sharing is user-initiated through the share sheet, after a preview.
- Never collected: ROMs, firmware, keys, saves, credentials, the web service token, memory dumps.
- `serial_unit` and `serial_battery` are left out of the settings list and redacted in logs;
  the emulator keeps using its values. Other console identifiers are not exported either.
- Settings in the `Paths`, `DataStorage`, `WebService`, `Multiplayer`, `Network`, `Controls`,
  `Shortcuts`, `Ui*` and `Screenshots` categories are excluded, as are all free-text settings
  except `log_filter`, `post_shader_chain` and `post_shader_preset`.
- Structured values have storage paths, `content://`/`file://` URIs and e-mail addresses
  redacted. Logs, events and user notes additionally have IPv4 and MAC addresses redacted. Known
  app directories are replaced by placeholders such as `<eden_user_dir>`.
- The device identity block can be removed at export time.
- Limitation: Switch profile nicknames that the emulated software itself prints to the log are not
  recognized by the redactor.

## Validation status

- Host: native core unit tests (Catch2, ASan/UBSan/TSan), JVM unit tests for redaction, `/proc`
  parsing, report building and zip writing, Kotlin compilation, ktlint on the new files.
- Native Vulkan/JNI changes: differential syntax check with NDK clang (aarch64, Eden `-Werror`
  flags) against the base commit.
- Not yet validated: a full APK build and on-device data collection.

## Schema changelog

| Version | Change |
| --- | --- |
| 1 | Initial format |
| 1 (additive) | `hitches`; `sampler_cost_breakdown_ms` per sample; `level_name` on `trim_memory` events; `slow_graphics_pipeline_create` / `slow_compute_pipeline_create` native events (driver creation ≥ 250 ms, with the shader or pipeline hash); `availability` now also records `available`, so mixed results read `intermittent` |
| 1 (additive) | `pacing` buckets (`lt_12ms`, `12_20ms`, `20_28ms`, `28_38ms`, `38_55ms`, `ge_55ms`) per window and per session frame series; `long_frame` native events (interval ≥ 100 ms, at most 100 per capture) with the exact time of each long frame |
| 1 (additive) | `system.gpu_max_clock_mhz_kgsl`, `system.gpu_min_clock_mhz_kgsl` per sample; `gpu_busy_pct_kgsl`, `gpu_clock_mhz_kgsl` and `gpu_max_clock_mhz_kgsl` statistics in `android_summary`; `gpu_clock_mhz_kgsl` in `hitches`. The Eden log is flushed before export, so the bundled log now includes its newest lines |
| 2 | Capture scoping: `events.native` holds only this capture (schema 1 mixed earlier captures in and kept `during_capture=true` for them), new `events.native_context`/`native_scope`; `identity`, `session_end`, `identity_changes`, `log_coverage`, `log_digest`, `manifest.json`; identity fields frozen at capture start; `exit_reasons` gain `pid`, `category`, `relation`; `native_summary.capturing` is false and `duration_ms` final after stop; `logs/eden_log.txt` holds the capture's lines instead of the file tail; `serial_unit`/`serial_battery` removed |
