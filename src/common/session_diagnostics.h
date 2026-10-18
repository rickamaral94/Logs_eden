// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <atomic>
#include <chrono>
#include <functional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "common/common_types.h"

// Low overhead session diagnostics used by the frontend diagnostic capture.
// Timers, counters and frame intervals are only recorded while a capture is active; static
// sections and the bounded event ring are always kept so that a capture started later (or a
// report exported after a failure) still contains them.
namespace Common::Diagnostics {

/// CPU wall-clock durations measured around host operations. None of these are GPU execution
/// times: they measure how long the calling CPU thread spent inside or blocked on the operation.
enum class Timer : u32 {
    FenceWait,               ///< Blocked in MasterSemaphore::Wait waiting for a GPU tick.
    QueueSubmit,             ///< Inside vkQueueSubmit for emulated command buffers.
    PresentQueueSubmit,      ///< Inside vkQueueSubmit for the present blit.
    PipelineBuildWait,       ///< Recording blocked on a pipeline still being built.
    GraphicsShaderTranslate, ///< Runtime guest shader translation + module creation.
    ComputeShaderTranslate,  ///< Runtime guest compute shader translation + module creation.
    GraphicsPipelineCreate,  ///< Driver pipeline creation (MakePipeline), graphics.
    ComputePipelineCreate,   ///< Driver pipeline creation, compute.
    Count,
};

enum class Counter : u32 {
    GraphicsPipelineCacheMiss,  ///< New graphics pipeline created at runtime.
    ComputePipelineCacheMiss,   ///< New compute pipeline created at runtime.
    DrawSkippedPipelinePending, ///< Async shaders: draw skipped while the pipeline builds.
    PipelineCreateFailed,       ///< Driver rejected a pipeline.
    ShaderTranslateFailed,      ///< Shader recompiler raised an exception.
    SwapchainCreated,           ///< Swapchain (re)creations.
    DiskPipelinesLoaded,        ///< Pipelines restored from the disk shader cache.
    GameFrames,                 ///< Frames rendered by the game (PerfStats::EndGameFrame).
    Count,
};

enum class FrameSeries : u32 {
    GuestPresent, ///< Interval between guest display flips (nvdisp), CPU wall clock.
    HostPresent,  ///< Interval between host frames composited by the renderer.
    Count,
};

enum class Severity : u8 {
    Info,
    Warning,
    Error,
    Critical,
};

/// Coarse emulator lifecycle stage attached to every event.
enum class Stage : u8 {
    Unknown,
    RendererInit,
    ShaderCacheLoad,
    Running,
    Shutdown,
};

using Fields = std::vector<std::pair<std::string, std::string>>;

namespace Detail {
extern std::atomic<bool> g_capturing;
}

[[nodiscard]] inline bool IsCapturing() noexcept {
    return Detail::g_capturing.load(std::memory_order_relaxed);
}

/// Starts a capture. session_dir is where critical markers are written on fatal errors.
bool StartCapture(std::string session_dir);
void StopCapture();

void SetStage(Stage stage) noexcept;
[[nodiscard]] Stage GetStage() noexcept;

void AddTiming(Timer timer, std::chrono::nanoseconds duration) noexcept;
void Increment(Counter counter, u64 amount = 1) noexcept;
void RecordFrameInterval(FrameSeries series, std::chrono::nanoseconds interval);

/// Records an event in the bounded ring. Consecutive identical events are merged with a
/// repeat count instead of growing the ring.
void RecordEvent(Severity severity, std::string_view category, std::string_view name,
                 Fields fields = {});

/// Stores a static JSON document (e.g. Vulkan device or swapchain description).
void SetSection(std::string key, std::string json);

/// Registers a callback returning a JSON document, polled at every sample. Used for values that
/// must be queried from their owner, such as Vulkan memory budgets.
void RegisterSampler(std::string key, std::function<std::string()> sampler);
void UnregisterSampler(std::string_view key);

/// Returns the metrics accumulated since the previous call and resets the window.
[[nodiscard]] std::string TakeSampleJson();
/// Returns totals for the whole capture, including session-wide frame time percentiles.
[[nodiscard]] std::string SessionSummaryJson();
[[nodiscard]] std::string SectionsJson();
[[nodiscard]] std::string EventsJson();

/// Synchronously writes sections, summary and events to the capture directory. Intended for
/// fatal paths (e.g. device loss) where the process may not survive until the next sample.
void WriteCriticalMarker(std::string_view reason);

[[nodiscard]] std::string_view TimerName(Timer timer) noexcept;
[[nodiscard]] std::string_view CounterName(Counter counter) noexcept;
[[nodiscard]] std::string_view FrameSeriesName(FrameSeries series) noexcept;
[[nodiscard]] std::string_view StageName(Stage stage) noexcept;

/// Milliseconds on the monotonic clock (CLOCK_MONOTONIC on Android, same base as
/// System.nanoTime() on the Java side).
[[nodiscard]] s64 MonotonicMs() noexcept;

class ScopedTimer {
public:
    explicit ScopedTimer(Timer timer_) noexcept : timer{timer_}, active{IsCapturing()} {
        if (active) {
            start = std::chrono::steady_clock::now();
        }
    }
    ~ScopedTimer() {
        if (active) {
            AddTiming(timer, std::chrono::steady_clock::now() - start);
        }
    }

    ScopedTimer(const ScopedTimer&) = delete;
    ScopedTimer& operator=(const ScopedTimer&) = delete;

private:
    Timer timer;
    bool active;
    std::chrono::steady_clock::time_point start{};
};

} // namespace Common::Diagnostics
