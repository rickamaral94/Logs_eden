// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <algorithm>
#include <array>
#include <cmath>
#include <deque>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>

#include <nlohmann/json.hpp>

#include "common/session_diagnostics.h"

namespace Common::Diagnostics {

namespace Detail {
std::atomic<bool> g_capturing{false};
}

namespace {

using json = nlohmann::json;

constexpr size_t TimerCount = static_cast<size_t>(Timer::Count);
constexpr size_t CounterCount = static_cast<size_t>(Counter::Count);
constexpr size_t SeriesCount = static_cast<size_t>(FrameSeries::Count);

constexpr size_t MaxEvents = 256;
constexpr size_t MaxWindowFrames = 16384;
constexpr u32 HistogramBucketUs = 250;
constexpr size_t HistogramBuckets = 1000; // 0 - 250 ms, plus one overflow bucket

struct TimerStat {
    std::atomic<u64> count{};
    std::atomic<u64> total_ns{};
    std::atomic<u64> max_ns{};

    void Add(u64 ns) noexcept {
        count.fetch_add(1, std::memory_order_relaxed);
        total_ns.fetch_add(ns, std::memory_order_relaxed);
        u64 current = max_ns.load(std::memory_order_relaxed);
        while (ns > current &&
               !max_ns.compare_exchange_weak(current, ns, std::memory_order_relaxed)) {
        }
    }

    void Reset() noexcept {
        count.store(0, std::memory_order_relaxed);
        total_ns.store(0, std::memory_order_relaxed);
        max_ns.store(0, std::memory_order_relaxed);
    }

    json TakeJson(bool reset) noexcept {
        const u64 c = reset ? count.exchange(0, std::memory_order_relaxed)
                            : count.load(std::memory_order_relaxed);
        const u64 t = reset ? total_ns.exchange(0, std::memory_order_relaxed)
                            : total_ns.load(std::memory_order_relaxed);
        const u64 m = reset ? max_ns.exchange(0, std::memory_order_relaxed)
                            : max_ns.load(std::memory_order_relaxed);
        return json{
            {"count", c},
            {"total_us", t / 1000},
            {"max_us", m / 1000},
            {"mean_us", c != 0 ? static_cast<double>(t) / 1000.0 / static_cast<double>(c) : 0.0},
        };
    }
};

struct FrameWindow {
    std::vector<u32> intervals_us;
    u64 dropped{};
};

struct FrameSession {
    std::array<u64, HistogramBuckets + 1> histogram{};
    u64 count{};
    u64 total_us{};
    u32 max_us{};
};

struct Event {
    u64 seq{};
    s64 mono_ms{};
    s64 last_mono_ms{};
    bool during_capture{};
    Severity severity{};
    Stage stage{};
    std::string category;
    std::string name;
    Fields fields;
    u32 repeat{1};
};

struct State {
    std::array<TimerStat, TimerCount> window_timers{};
    std::array<TimerStat, TimerCount> session_timers{};
    std::array<std::atomic<u64>, CounterCount> window_counters{};
    std::array<std::atomic<u64>, CounterCount> session_counters{};

    std::mutex frames_mutex;
    std::array<FrameWindow, SeriesCount> frame_windows{};
    std::array<FrameSession, SeriesCount> frame_sessions{};

    std::mutex events_mutex;
    std::deque<Event> events;
    u64 next_event_seq{1};
    u64 last_sampled_seq{};
    u64 events_dropped{};

    std::mutex sections_mutex;
    std::map<std::string, std::string, std::less<>> sections;

    std::mutex samplers_mutex;
    std::map<std::string, std::function<std::string()>, std::less<>> samplers;

    std::mutex capture_mutex;
    std::string session_dir;
    s64 capture_start_ms{};
    s64 last_sample_ms{};
    u64 sample_index{};

    std::atomic<Stage> stage{Stage::Unknown};
};

State& GetState() {
    static State state;
    return state;
}

constexpr std::array<std::string_view, TimerCount> TIMER_NAMES{
    "fence_wait",
    "queue_submit",
    "present_queue_submit",
    "pipeline_build_wait",
    "graphics_shader_translate",
    "compute_shader_translate",
    "graphics_pipeline_create",
    "compute_pipeline_create",
};

constexpr std::array<std::string_view, CounterCount> COUNTER_NAMES{
    "graphics_pipeline_cache_miss",  "compute_pipeline_cache_miss",
    "draw_skipped_pipeline_pending", "pipeline_create_failed",
    "shader_translate_failed",       "swapchain_created",
    "disk_pipelines_loaded",         "game_frames",
};

constexpr std::array<std::string_view, SeriesCount> SERIES_NAMES{
    "guest_present_interval",
    "host_present_interval",
};

std::string_view SeverityName(Severity severity) {
    switch (severity) {
    case Severity::Info:
        return "info";
    case Severity::Warning:
        return "warning";
    case Severity::Error:
        return "error";
    case Severity::Critical:
        return "critical";
    }
    return "unknown";
}

// Nearest-rank percentile on a sorted vector.
u32 Percentile(const std::vector<u32>& sorted, double p) {
    if (sorted.empty()) {
        return 0;
    }
    const auto rank = static_cast<size_t>(std::ceil(p * static_cast<double>(sorted.size())));
    return sorted[std::clamp<size_t>(rank, 1, sorted.size()) - 1];
}

// Upper bound of the histogram bucket holding the requested percentile.
double HistogramPercentileMs(const FrameSession& session, double p) {
    if (session.count == 0) {
        return 0.0;
    }
    const auto target = static_cast<u64>(std::ceil(p * static_cast<double>(session.count)));
    u64 seen = 0;
    for (size_t bucket = 0; bucket < session.histogram.size(); ++bucket) {
        seen += session.histogram[bucket];
        if (seen >= target) {
            if (bucket == HistogramBuckets) {
                return static_cast<double>(session.max_us) / 1000.0;
            }
            return static_cast<double>((bucket + 1) * HistogramBucketUs) / 1000.0;
        }
    }
    return static_cast<double>(session.max_us) / 1000.0;
}

json WindowFrameJson(std::vector<u32> intervals, u64 dropped) {
    json out{{"count", intervals.size()}, {"dropped", dropped}};
    if (intervals.empty()) {
        return out;
    }
    std::sort(intervals.begin(), intervals.end());
    u64 total = 0;
    for (const u32 value : intervals) {
        total += value;
    }
    const u32 median = Percentile(intervals, 0.5);
    const auto count_over = [&](u32 threshold_us) {
        return static_cast<u64>(intervals.end() -
                                std::upper_bound(intervals.begin(), intervals.end(), threshold_us));
    };
    const auto to_ms = [](u32 us) { return static_cast<double>(us) / 1000.0; };
    out["mean_ms"] = static_cast<double>(total) / 1000.0 / static_cast<double>(intervals.size());
    out["min_ms"] = to_ms(intervals.front());
    out["p50_ms"] = to_ms(median);
    out["p90_ms"] = to_ms(Percentile(intervals, 0.90));
    out["p99_ms"] = to_ms(Percentile(intervals, 0.99));
    out["max_ms"] = to_ms(intervals.back());
    out["over_33ms"] = count_over(33'333);
    out["over_50ms"] = count_over(50'000);
    out["over_100ms"] = count_over(100'000);
    out["over_2x_median"] = count_over(median * 2);
    return out;
}

json SessionFrameJson(const FrameSession& session) {
    json out{{"count", session.count}};
    if (session.count == 0) {
        return out;
    }
    out["mean_ms"] =
        static_cast<double>(session.total_us) / 1000.0 / static_cast<double>(session.count);
    out["p50_ms"] = HistogramPercentileMs(session, 0.50);
    out["p90_ms"] = HistogramPercentileMs(session, 0.90);
    out["p99_ms"] = HistogramPercentileMs(session, 0.99);
    out["p999_ms"] = HistogramPercentileMs(session, 0.999);
    out["max_ms"] = static_cast<double>(session.max_us) / 1000.0;
    out["percentile_resolution_ms"] = static_cast<double>(HistogramBucketUs) / 1000.0;
    return out;
}

json EventJson(const Event& event) {
    json fields = json::object();
    for (const auto& [key, value] : event.fields) {
        fields[key] = value;
    }
    return json{
        {"seq", event.seq},
        {"mono_ms", event.mono_ms},
        {"last_mono_ms", event.last_mono_ms},
        {"during_capture", event.during_capture},
        {"severity", SeverityName(event.severity)},
        {"stage", StageName(event.stage)},
        {"category", event.category},
        {"name", event.name},
        {"repeat", event.repeat},
        {"fields", std::move(fields)},
    };
}

json ParseOrString(const std::string& text) {
    json parsed = json::parse(text, nullptr, false);
    if (parsed.is_discarded()) {
        return json{{"unparsed", text}};
    }
    return parsed;
}

json SectionsObject(State& state) {
    std::scoped_lock lock{state.sections_mutex};
    json out = json::object();
    for (const auto& [key, value] : state.sections) {
        out[key] = ParseOrString(value);
    }
    return out;
}

json EventsArray(State& state) {
    std::scoped_lock lock{state.events_mutex};
    json out = json::array();
    for (const auto& event : state.events) {
        out.push_back(EventJson(event));
    }
    return out;
}

json SummaryObject(State& state) {
    json timers = json::object();
    json counters = json::object();
    for (size_t i = 0; i < TimerCount; ++i) {
        timers[std::string{TIMER_NAMES[i]}] = state.session_timers[i].TakeJson(false);
    }
    for (size_t i = 0; i < CounterCount; ++i) {
        counters[std::string{COUNTER_NAMES[i]}] =
            state.session_counters[i].load(std::memory_order_relaxed);
    }
    json frames = json::object();
    {
        std::scoped_lock lock{state.frames_mutex};
        for (size_t i = 0; i < SeriesCount; ++i) {
            frames[std::string{SERIES_NAMES[i]}] = SessionFrameJson(state.frame_sessions[i]);
        }
    }
    s64 start_ms;
    u64 samples;
    {
        std::scoped_lock lock{state.capture_mutex};
        start_ms = state.capture_start_ms;
        samples = state.sample_index;
    }
    u64 dropped_events;
    {
        std::scoped_lock lock{state.events_mutex};
        dropped_events = state.events_dropped;
    }
    return json{
        {"capturing", IsCapturing()},
        {"capture_start_mono_ms", start_ms},
        {"duration_ms", start_ms != 0 ? MonotonicMs() - start_ms : 0},
        {"samples", samples},
        {"events_dropped", dropped_events},
        {"timers", std::move(timers)},
        {"counters", std::move(counters)},
        {"frames", std::move(frames)},
    };
}

void ResetCaptureData(State& state) {
    for (auto& timer : state.window_timers) {
        timer.Reset();
    }
    for (auto& timer : state.session_timers) {
        timer.Reset();
    }
    for (auto& counter : state.window_counters) {
        counter.store(0, std::memory_order_relaxed);
    }
    for (auto& counter : state.session_counters) {
        counter.store(0, std::memory_order_relaxed);
    }
    std::scoped_lock lock{state.frames_mutex};
    for (auto& window : state.frame_windows) {
        window.intervals_us.clear();
        window.dropped = 0;
    }
    for (auto& session : state.frame_sessions) {
        session = FrameSession{};
    }
}

} // Anonymous namespace

s64 MonotonicMs() noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

std::string_view TimerName(Timer timer) noexcept {
    return TIMER_NAMES[static_cast<size_t>(timer)];
}

std::string_view CounterName(Counter counter) noexcept {
    return COUNTER_NAMES[static_cast<size_t>(counter)];
}

std::string_view FrameSeriesName(FrameSeries series) noexcept {
    return SERIES_NAMES[static_cast<size_t>(series)];
}

std::string_view StageName(Stage stage) noexcept {
    switch (stage) {
    case Stage::Unknown:
        return "unknown";
    case Stage::RendererInit:
        return "renderer_init";
    case Stage::ShaderCacheLoad:
        return "shader_cache_load";
    case Stage::Running:
        return "running";
    case Stage::Shutdown:
        return "shutdown";
    }
    return "unknown";
}
bool StartCapture(std::string session_dir) {
    auto& state = GetState();
    std::scoped_lock lock{state.capture_mutex};
    if (IsCapturing()) {
        return false;
    }
    ResetCaptureData(state);
    {
        std::scoped_lock events_lock{state.events_mutex};
        state.last_sampled_seq = 0;
    }
    state.session_dir = std::move(session_dir);
    state.capture_start_ms = MonotonicMs();
    state.last_sample_ms = state.capture_start_ms;
    state.sample_index = 0;
    Detail::g_capturing.store(true, std::memory_order_relaxed);
    return true;
}

void StopCapture() {
    auto& state = GetState();
    std::scoped_lock lock{state.capture_mutex};
    Detail::g_capturing.store(false, std::memory_order_relaxed);
}

void SetStage(Stage stage) noexcept {
    GetState().stage.store(stage, std::memory_order_relaxed);
}

Stage GetStage() noexcept {
    return GetState().stage.load(std::memory_order_relaxed);
}

void AddTiming(Timer timer, std::chrono::nanoseconds duration) noexcept {
    if (!IsCapturing()) {
        return;
    }
    auto& state = GetState();
    const auto ns =
        static_cast<u64>((std::max)(duration, std::chrono::nanoseconds::zero()).count());
    state.window_timers[static_cast<size_t>(timer)].Add(ns);
    state.session_timers[static_cast<size_t>(timer)].Add(ns);
}

void Increment(Counter counter, u64 amount) noexcept {
    if (!IsCapturing()) {
        return;
    }
    auto& state = GetState();
    state.window_counters[static_cast<size_t>(counter)].fetch_add(amount,
                                                                  std::memory_order_relaxed);
    state.session_counters[static_cast<size_t>(counter)].fetch_add(amount,
                                                                   std::memory_order_relaxed);
}

void RecordFrameInterval(FrameSeries series, std::chrono::nanoseconds interval) {
    if (!IsCapturing()) {
        return;
    }
    const auto us_count = std::chrono::duration_cast<std::chrono::microseconds>(interval).count();
    if (us_count <= 0) {
        return;
    }
    const auto us = us_count > UINT32_MAX ? UINT32_MAX : static_cast<u32>(us_count);
    auto& state = GetState();
    std::scoped_lock lock{state.frames_mutex};
    auto& window = state.frame_windows[static_cast<size_t>(series)];
    if (window.intervals_us.size() < MaxWindowFrames) {
        window.intervals_us.push_back(us);
    } else {
        ++window.dropped;
    }
    auto& session = state.frame_sessions[static_cast<size_t>(series)];
    session.histogram[(std::min)(static_cast<size_t>(us / HistogramBucketUs), HistogramBuckets)]++;
    ++session.count;
    session.total_us += us;
    session.max_us = (std::max)(session.max_us, us);
}

void RecordEvent(Severity severity, std::string_view category, std::string_view name,
                 Fields fields) {
    auto& state = GetState();
    const s64 now = MonotonicMs();
    const Stage stage = GetStage();
    std::scoped_lock lock{state.events_mutex};
    if (!state.events.empty()) {
        Event& last = state.events.back();
        if (last.seq > state.last_sampled_seq && last.severity == severity && last.stage == stage &&
            last.category == category && last.name == name && last.fields == fields) {
            ++last.repeat;
            last.last_mono_ms = now;
            return;
        }
    }
    if (state.events.size() >= MaxEvents) {
        state.events.pop_front();
        ++state.events_dropped;
    }
    state.events.push_back(Event{
        .seq = state.next_event_seq++,
        .mono_ms = now,
        .last_mono_ms = now,
        .during_capture = IsCapturing(),
        .severity = severity,
        .stage = stage,
        .category = std::string{category},
        .name = std::string{name},
        .fields = std::move(fields),
    });
}

void SetSection(std::string key, std::string json_text) {
    auto& state = GetState();
    std::scoped_lock lock{state.sections_mutex};
    state.sections.insert_or_assign(std::move(key), std::move(json_text));
}

void RegisterSampler(std::string key, std::function<std::string()> sampler) {
    auto& state = GetState();
    std::scoped_lock lock{state.samplers_mutex};
    state.samplers.insert_or_assign(std::move(key), std::move(sampler));
}

void UnregisterSampler(std::string_view key) {
    auto& state = GetState();
    std::scoped_lock lock{state.samplers_mutex};
    if (const auto it = state.samplers.find(key); it != state.samplers.end()) {
        state.samplers.erase(it);
    }
}

std::string TakeSampleJson() {
    auto& state = GetState();
    const s64 now = MonotonicMs();
    json out;
    {
        std::scoped_lock lock{state.capture_mutex};
        out["sample_index"] = state.sample_index++;
        out["mono_ms"] = now;
        out["t_ms"] = state.capture_start_ms != 0 ? now - state.capture_start_ms : 0;
        out["window_ms"] = now - state.last_sample_ms;
        state.last_sample_ms = now;
    }
    out["capturing"] = IsCapturing();
    out["stage"] = StageName(GetStage());

    json timers = json::object();
    for (size_t i = 0; i < TimerCount; ++i) {
        timers[std::string{TIMER_NAMES[i]}] = state.window_timers[i].TakeJson(true);
    }
    out["timers"] = std::move(timers);

    json counters = json::object();
    for (size_t i = 0; i < CounterCount; ++i) {
        counters[std::string{COUNTER_NAMES[i]}] =
            state.window_counters[i].exchange(0, std::memory_order_relaxed);
    }
    out["counters"] = std::move(counters);

    std::array<FrameWindow, SeriesCount> windows;
    {
        std::scoped_lock lock{state.frames_mutex};
        for (size_t i = 0; i < SeriesCount; ++i) {
            windows[i] = std::exchange(state.frame_windows[i], FrameWindow{});
        }
    }
    json frames = json::object();
    for (size_t i = 0; i < SeriesCount; ++i) {
        frames[std::string{SERIES_NAMES[i]}] =
            WindowFrameJson(std::move(windows[i].intervals_us), windows[i].dropped);
    }
    out["frames"] = std::move(frames);

    json samplers = json::object();
    {
        std::scoped_lock lock{state.samplers_mutex};
        for (const auto& [key, sampler] : state.samplers) {
            try {
                samplers[key] = ParseOrString(sampler());
            } catch (const std::exception& e) {
                samplers[key] = json{{"error", e.what()}};
            }
        }
    }
    out["samplers"] = std::move(samplers);

    json events = json::array();
    {
        std::scoped_lock lock{state.events_mutex};
        for (const auto& event : state.events) {
            if (event.seq > state.last_sampled_seq) {
                events.push_back(EventJson(event));
            }
        }
        state.last_sampled_seq = state.next_event_seq - 1;
    }
    out["events"] = std::move(events);
    return out.dump();
}

std::string SessionSummaryJson() {
    return SummaryObject(GetState()).dump();
}

std::string SectionsJson() {
    return SectionsObject(GetState()).dump();
}

std::string EventsJson() {
    return EventsArray(GetState()).dump();
}

void WriteCriticalMarker(std::string_view reason) {
    auto& state = GetState();
    std::string dir;
    {
        std::scoped_lock lock{state.capture_mutex};
        dir = state.session_dir;
    }
    if (!IsCapturing() || dir.empty()) {
        return;
    }
    try {
        const json marker{
            {"reason", reason},
            {"mono_ms", MonotonicMs()},
            {"stage", StageName(GetStage())},
            {"summary", SummaryObject(state)},
            {"sections", SectionsObject(state)},
            {"events", EventsArray(state)},
        };
        const auto path = std::filesystem::path{dir} /
                          ("native_critical_" + std::to_string(MonotonicMs()) + ".json");
        std::ofstream file{path, std::ios::binary | std::ios::trunc};
        file << marker.dump(1);
        file.flush();
    } catch (...) {
        // Best effort: this runs on fatal paths and must never throw.
    }
}

} // namespace Common::Diagnostics
