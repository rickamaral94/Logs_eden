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

## Offline tools

Python standard library only; bundles are read, never modified. Tests:
`python3 -m unittest discover -s tools/diagnostics/tests` (set `EDEN_DIAG_FIXTURES` to a
directory of device bundles to also run the device-bundle regression tests).

- `tools/diagnostics/eden_diag.py validate BUNDLE...` checks manifest sizes and SHA-256,
  completeness flags, events outside the capture, time order, log coverage (mid-line end, stop
  line), GPU timestamp coverage, sample gaps, unavailable metrics and unredacted console serials,
  storage paths or e-mail addresses. Exit code 1 on errors.
- `eden_diag.py compare A B` (or `compare_reports.py A B`) lists differences between two
  bundles of schema 1 or 2: build, driver selection and identity, pipeline cache UUID, settings
  hash and settings, extensions and Eden flags, swapchain and shader cache, frame and GPU time
  percentiles, counters and timers per minute, process/system metrics, native error events.
- `eden_diag.py abba --a A1.zip A2.zip ... --b B1.zip B2.zip ... [--variable driver|build|settings|none]
  [--warmup-s N]` for repeated runs of the same scene. Blockers (comparison invalid): any
  identity field other than the declared variable differs (game, game build, Eden revision,
  driver, settings hash, resolution, present mode, CPU backend, capture level) or the variable
  differs within a group. Caveats: shader cache state, thermal status above 0, battery
  temperature spread above 5 °C, GPU clock limit differences, durations differing by more than
  25 %, incomplete or schema 1 bundles, all A runs before all B runs. Prints raw per-run values,
  then per group the median, min-max and n, and the change of the B median against A. Whole
  capture percentiles come from the merged native histograms; with `--warmup-s` only
  count-based metrics are recomputed from the windows after the cut (FPS, share of frames over
  20 ms, mean frame GPU time), because window percentiles cannot be merged. Segmenting by
  markers is not automated yet; use separate captures per scene or `--warmup-s`.

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

## GPU time (Vulkan timestamps)

On by default during a capture (checkbox "Measure GPU time" in the capture dialog), off outside
captures. Support is checked on the device actually created: `timestampValidBits` of the
graphics queue family and `timestampPeriod`; otherwise `gpu_timing.available` is false with the
reason.

- What is measured: each scheduler submission (upload + main command buffer, one
  `vkQueueSubmit`) gets a `TOP_OF_PIPE` timestamp at the start of the upload command buffer and a
  `BOTTOM_OF_PIPE` timestamp at the end of the main command buffer. The two queries are reset with
  `vkCmdResetQueryPool` in the same submission, before the writes and outside any render pass, so
  `host_query_reset` is not needed. The present blit (`PresentManager`, own command buffers) is
  not measured.
- No waits: results are read with `VK_QUERY_RESULT_64_BIT | WITH_AVAILABILITY` (never `WAIT`) on
  the scheduler worker thread, only for ticks the existing fence/timeline tracking already
  reports as complete. No submits, fences, barriers or idle waits are added. A ring of 256 slots
  is never reset while a slot is in flight; when it is full the submission is counted as
  unmeasured (`dropped_ring_full`). Results unavailable after 64 polls count as
  `dropped_unavailable`; on device loss pending results are dropped (`dropped_lost`).
- Conversion: differences use only `timestampValidBits` (48 on Adreno 740/Turnip), masked, so
  wraparound is handled; ticks are multiplied by `timestampPeriod`. GPU timestamps are never
  subtracted from CPU clocks (no calibration is used).
- Per window (`samples[].native.gpu_timing`): submissions, measured, results, `coverage_pct`,
  pending, drops, `submit_sum_ms` (sum of submission intervals: counts overlap twice and includes
  idle time inside command buffers), `submit_busy_union_ms` (union of the intervals), per-submit
  distribution, `frame_gpu` distribution, `frames_complete`/`frames_partial`,
  `instrumentation_cpu_ms` (CPU time spent recording and reading queries).
- Per frame: `frame_gpu` is the union of the submissions attributed to one host frame (the
  host frame counter at submit time) and is only computed for frames whose submissions were all
  measured; other frames count as `frames_partial`. Session percentiles come from a 0.25 ms
  histogram (merged, not averaged per window).
- Not: GPU utilization, the frame's critical path or display latency. `gpu_timing.cemu_aliases`
  maps `gpuTimePerFrameMs` (frame p50) and `gpuCoveragePct` for comparison with Cemu reports.
- Cost per submission: one query reset, two timestamp writes, and on read one
  `vkGetQueryPoolResults` for two queries.

## CPU waits, presentation and workload

- `fence_wait` is split by `fence_wait_by_reason` (per window and per capture): callers declare
  the reason with a thread-local scope, the innermost scope wins and waits without one are
  `unspecified`. Reasons: `scheduler_finish` (a `Scheduler::Finish` without a more specific
  reason), `guest_sync` (fence manager, guest syncpoints), `swapchain_image` (resources of the
  next swapchain image, i.e. frames in flight, including the pacing variants), `present_resources`
  (present layer reuse/recreation), `descriptor_recycle`, `buffer_recycle`,
  `texture_download` (texture cache readbacks), `buffer_download` (buffer cache `Finish`:
  downloads and synchronizing copies) and `buffer_write_sync` (waiting for pending GPU writes to
  a buffer). Each has count, total, max, mean and
  a distribution (`lt_1ms` … `ge_33ms`). `fence_wait_by_thread` gives the same per thread name
  (at most 16, the rest as `other`). Scopes are inclusive: waits on different threads overlap and
  are not summed into a critical path.
- Frame intervals that began before the capture started or before an emulation pause/resume
  are not recorded; they are counted as `skipped_across_pause` and the pause and resume appear as
  `emulation/paused` and `emulation/resumed` events. A 13.3 s "frame" that spanned the export
  dialog between two device captures motivated this.
- Presentation timers (CPU time inside the call): `swapchain_acquire_cpu`
  (`vkAcquireNextImageKHR`), `swapchain_present_cpu` (`vkQueuePresentKHR`),
  `present_queue_submit`, `frame_pacing_throttle` (sleep of the scheduler pacing limiter). No
  present-timing extension is used, so nothing here is display or scanout latency.
- Swapchain: `vulkan.swapchain.present_mode_inputs` records the vsync setting, speed limit,
  speed mode and frame generation that decide the present mode, and the selection rule;
  `swapchain/recreate` events record each recreation with its reason (`frame_size_changed`,
  `present_mode_changed`, `suboptimal`, `acquire_out_of_date_or_suboptimal`) and sizes.
- Workload counters: `scheduler_submits`, `draw_calls` (guest draws reaching the rasterizer
  with a pipeline), `indexed_draw_calls`, `indirect_draw_calls`, `compute_dispatches`,
  `render_passes`, `staging_upload_bytes`, `staging_download_bytes` (requested sizes). The report
  `workload.per_host_frame` divides them by host frames.
- Pipeline creation feedback (Vulkan 1.3 devices, only while capturing): every runtime
  pipeline creation chains `VkPipelineCreationFeedbackCreateInfo` for the whole pipeline (no
  per-stage array). `pipeline_feedback_valid` counts reports with the valid bit and
  `driver_pipeline_cache_hits` those with `APPLICATION_PIPELINE_CACHE_HIT`; slow creation events
  carry `driver_cache` (`hit`, `miss`, `unknown`). The feedback is informational and does not
  change the pipeline; the size of the driver cache file alone does not prove hits.
- Not instrumented yet: Maxwell command processing and descriptor preparation timers, scheduler
  queue depth, flush reasons, triangle/index counts, barriers and layout transitions.

## Driver identity and effective Vulkan paths

- `graphics_driver.selected` is the package chosen in Eden (system or custom driver metadata);
  `graphics_driver.loaded` lists the driver and driver-loading shared objects actually mapped by
  the process (`/proc/self/maps` at capture start) with size, SHA-256 and ELF GNU build-id when
  the file is readable by the app; `vulkan.device.driver` is what the created device reports.
- `vulkan.device` adds `pipeline_cache_uuid`, `driver_version_vulkan_encoding` next to the raw
  value (Mesa uses the Vulkan encoding; proprietary drivers do not),
  `extensions_removed_by_eden` (`required_features_missing` or `eden_workaround_or_setting`),
  `effective_paths` (extended dynamic state level and its setting, vertex input dynamic state,
  descriptor buffer, buffer device address, timeline semaphore, synchronization2, push
  descriptors, native ASTC and BCn) and `device_fault` (advertised/enabled; Eden does not enable
  `VK_EXT_device_fault`, so nothing is queried on device loss). `extensions_supported` is what
  the driver advertises and `extensions_loaded` what was enabled on the `VkDevice`; neither proves
  a code path uses a feature. Decisions internal to the driver (GMEM vs sysmem, register spilling)
  are not visible through Vulkan and need driver-side instrumentation.
- `configuration_hashes`: SHA-256 of the sorted `category.name=value` lines of the exported
  settings (personal identifiers and wall-clock RTC values excluded), of the per-game subset and
  of the enabled add-ons, plus the game build id and the observed shader cache state
  (`disk_cache_present`/`disk_cache_empty` from the pipeline cache file at title load).

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
| `graphics_driver` | `selected` (Eden setting: `system` or `custom` + metadata) and `loaded` (mapped libraries with hashes); schema 1 had the selection at the top level |
| `configuration_hashes` | Settings, per-game settings and add-on hashes, game build id, shader cache state |
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
| `gpu_timing` | See [GPU time](#gpu-time-vulkan-timestamps) |
| `cpu_waits`, `workload` | See [CPU waits, presentation and workload](#cpu-waits-presentation-and-workload) |
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

What each kind of test proves:

- Host native tests (Catch2 under ASan/UBSan and TSan, in CI): capture isolation (sequential
  captures, event scoping, final summary after stop), wait-reason scopes and thread breakdown,
  GPU timing bookkeeping (48-bit wrap, unwrapping, ring saturation, pending/unavailable/lost
  results, partial frames, sum vs union). They prove the logic, not Vulkan behavior.
- JVM tests: log segment extraction (two captures in one process log, previous process log,
  missing stop line, mid-line end, size limit), event scoping for schema 1 and 2, exit linking
  and end classification, identity changes, manifest hashes, configuration hashes, console
  serial redaction, ELF build-id parsing, `/proc/self/maps` filtering, native thread stat
  parsing, report sections and summary.
- Device-bundle regression (`EDEN_DIAG_FIXTURES`, not committed because bundles hold user data):
  the two schema 1 bundles from an Odin 2 Portal (Hollow Knight, Little Nightmares) are read
  unchanged; the 17 earlier-capture events are classified as context; both logs are detected as
  ending mid-line without their stop line; `abba` refuses them as an A/B (different games, same
  driver).
- Offline tools: validate, compare and abba on synthetic bundles.
- Native Vulkan/JNI code: differential NDK clang syntax check (aarch64, Eden warning flags) and
  the CI APK build. The native thread reader was also run on a Linux host.
- Not validated: anything on a device with these changes, including that timestamps return
  plausible values on Turnip and the proprietary driver, timeline-semaphore devices, pipeline
  creation feedback results, the overhead of each level, and crash/ANR recovery paths.

### Proposed overhead budget (not measured yet)

Standard with GPU time: at most 1 % lower guest FPS and at most 0.2 ms added to the median
guest frame interval against diagnostics off, sampler cost under 5 ms per sample, GPU
instrumentation CPU time under 0.1 % of wall time. Detailed: at most 2 % lower FPS. To be
confirmed or revised with five AB/BA pairs per level on the reference device.

## Schema changelog

| Version | Change |
| --- | --- |
| 1 | Initial format |
| 1 (additive) | `hitches`; `sampler_cost_breakdown_ms` per sample; `level_name` on `trim_memory` events; `slow_graphics_pipeline_create` / `slow_compute_pipeline_create` native events (driver creation ≥ 250 ms, with the shader or pipeline hash); `availability` now also records `available`, so mixed results read `intermittent` |
| 1 (additive) | `pacing` buckets (`lt_12ms`, `12_20ms`, `20_28ms`, `28_38ms`, `38_55ms`, `ge_55ms`) per window and per session frame series; `long_frame` native events (interval ≥ 100 ms, at most 100 per capture) with the exact time of each long frame |
| 1 (additive) | `system.gpu_max_clock_mhz_kgsl`, `system.gpu_min_clock_mhz_kgsl` per sample; `gpu_busy_pct_kgsl`, `gpu_clock_mhz_kgsl` and `gpu_max_clock_mhz_kgsl` statistics in `android_summary`; `gpu_clock_mhz_kgsl` in `hitches`. The Eden log is flushed before export, so the bundled log now includes its newest lines |
| 2 | Capture scoping: `events.native` holds only this capture (schema 1 mixed earlier captures in and kept `during_capture=true` for them), new `events.native_context`/`native_scope`; `identity`, `session_end`, `identity_changes`, `log_coverage`, `log_digest`, `manifest.json`; identity fields frozen at capture start; `exit_reasons` gain `pid`, `category`, `relation`; `native_summary.capturing` is false and `duration_ms` final after stop; `logs/eden_log.txt` holds the capture's lines instead of the file tail; `serial_unit`/`serial_battery` removed |
| 2 (additive) | `gpu_timing` (report), `native_summary.gpu_timing`, `samples[].native.gpu_timing`; `frame_gpu_max_ms` in `hitches`; `session.gpu_timestamps_enabled`; `device_lost` events carry `host_frame_id` and `game_run_id` |
| 2 (additive) | `fence_wait_by_reason`, `fence_wait_by_thread` (samples and native summary); timers `swapchain_acquire_cpu`, `swapchain_present_cpu`, `frame_pacing_throttle`; counters `scheduler_submits`, `draw_calls`, `indexed_draw_calls`, `indirect_draw_calls`, `compute_dispatches`, `render_passes`, `staging_upload_bytes`, `staging_download_bytes`; report `cpu_waits`, `workload`; `vulkan.swapchain.present_mode_inputs`; `swapchain/recreate` events |
| 2 | `graphics_driver` split into `selected` (the schema 1 object) and `loaded`; `vulkan.device` adds `pipeline_cache_uuid`, `driver_version_vulkan_encoding`, `extensions_removed_by_eden`, `effective_paths`, `device_fault`; report `configuration_hashes` |
| 2 (additive) | `process.thread_source` (`native` or `java`); threads are keyed by tid and start time so a reused tid is not compared with another thread; `vulkan.memory_budget_peak.note` |
| 2 (additive) | counters `pipeline_feedback_valid`, `driver_pipeline_cache_hits`; `driver_cache` field on `slow_*_pipeline_create` events |
| 2 | `cache_sync` wait reason split into `texture_download`, `buffer_download`, `buffer_write_sync`; frame series gain `skipped_across_pause`; `emulation/paused` and `emulation/resumed` events |
