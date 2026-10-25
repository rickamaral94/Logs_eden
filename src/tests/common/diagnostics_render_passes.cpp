// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <set>
#include <string_view>

#include <catch2/catch_test_macros.hpp>
#include <nlohmann/json.hpp>

#include "common/diagnostics_render_passes.h"

namespace Common::Diagnostics::RenderPasses {
namespace {

using json = nlohmann::json;

PassInfo Pass(u64 render_pass, u64 target, u32 color_clear_mask = 0) {
    return PassInfo{
        .render_pass = render_pass,
        .target = target,
        .width = 1920,
        .height = 1080,
        .samples = 1,
        .color_attachments = 2,
        .depth_stencil = true,
        .color_clear_mask = color_clear_mask,
        .depth_stencil_clear = false,
        .color_discard_mask = 0,
        .depth_stencil_discard = false,
        .resolve = false,
    };
}

} // Anonymous namespace

TEST_CASE("RenderPasses: draw buckets", "[common]") {
    REQUIRE(Profiler::DrawBucket(0) == 0);
    REQUIRE(Profiler::DrawBucket(1) == 1);
    REQUIRE(Profiler::DrawBucket(4) == 2);
    REQUIRE(Profiler::DrawBucket(5) == 3);
    REQUIRE(Profiler::DrawBucket(64) == 4);
    REQUIRE(Profiler::DrawBucket(65) == 5);
}

TEST_CASE("RenderPasses: passes, attachments, reopen and shapes", "[common]") {
    Profiler profiler;
    // Frame 1: a pass on target 10 with 3 draws, split by a copy and reopened on the same
    // target with one draw, then an empty clear pass on target 11.
    REQUIRE(profiler.Begin(Pass(1, 10), 1));
    profiler.Describe(Pass(1, 10), "color[A8B8G8R8_UNORM load]");
    for (int i = 0; i < 3; ++i) {
        profiler.NoteDraw();
    }
    profiler.End(EndReason::Copy);
    REQUIRE_FALSE(profiler.Begin(Pass(1, 10), 1)); // shape already known
    profiler.NoteDraw();
    profiler.End(EndReason::NextTarget);
    REQUIRE(profiler.Begin(Pass(2, 11, 0b11), 1));
    profiler.End(EndReason::Submit);
    // Frame 2 closes frame 1.
    profiler.Begin(Pass(1, 10), 2);
    profiler.NoteDraw();
    profiler.End(EndReason::Submit);

    const json window = json::parse(profiler.TakeWindowJson());
    REQUIRE(window["passes"] == 4);
    REQUIRE(window["empty_passes"] == 1);
    REQUIRE(window["clear_only_passes"] == 1);
    REQUIRE(window["draws_in_passes"] == 5);
    REQUIRE(window["draws_per_pass_histogram"]["0"] == 1);
    REQUIRE(window["draws_per_pass_histogram"]["1"] == 2);
    REQUIRE(window["draws_per_pass_histogram"]["2_4"] == 1);
    // 3 attachments per pass; the clear pass clears two colors.
    REQUIRE(window["attachment_loads"] == 10);
    REQUIRE(window["attachment_clears"] == 2);
    REQUIRE(window["attachment_stores"] == 12);
    REQUIRE(window["same_target_reopen"] == 1);
    REQUIRE(window["same_target_reopen_after"]["copy"] == 1);
    REQUIRE(window["same_target_reopen_after"]["unknown"] == 0);
    REQUIRE(window["ended_by"]["submit"] == 2);
    REQUIRE(window["frames"] == 1);
    REQUIRE(window["passes_per_frame"]["max"] == 3);
    REQUIRE(window["pixels"] == 4ULL * 1920 * 1080);

    const json again = json::parse(profiler.TakeWindowJson());
    REQUIRE(again["passes"] == 0);

    const json session = json::parse(profiler.SessionJson());
    REQUIRE(session["passes"] == 4);
    REQUIRE(session["passes_per_frame"]["p50"] == 3);
    REQUIRE(session["shapes"]["distinct"] == 2);
    const json& top = session["shapes"]["top"][0];
    REQUIRE(top["passes"] == 3);
    REQUIRE(top["description"] == "color[A8B8G8R8_UNORM load]");
    REQUIRE(top["same_target_reopen"] == 1);

    profiler.Reset();
    REQUIRE(json::parse(profiler.SessionJson())["passes"] == 0);
}

TEST_CASE("RenderPasses: shape table is bounded", "[common]") {
    Profiler profiler;
    for (u64 i = 0; i < Profiler::MaxShapes + 10; ++i) {
        profiler.Begin(Pass(100 + i, 100 + i), 1);
        profiler.End(EndReason::NextTarget);
    }
    const json session = json::parse(profiler.SessionJson());
    REQUIRE(session["shapes"]["distinct"] == Profiler::MaxShapes);
    REQUIRE(session["shapes"]["untracked_passes"] == 10);
    REQUIRE(session["shapes"]["top"].size() == Profiler::TopShapes);
}

TEST_CASE("RenderPasses: every end reason has a distinct name and totals keep them", "[common]") {
    std::set<std::string_view> names;
    for (size_t i = 0; i < EndReasonCount; ++i) {
        names.insert(EndReasonName(static_cast<EndReason>(i)));
    }
    REQUIRE(names.size() == EndReasonCount);
    REQUIRE(EndReasonName(EndReason::Readback) == "readback");
    REQUIRE(EndReasonName(EndReason::Unknown) == "unknown");

    Profiler profiler;
    // A dispatch and a readback split passes on target 1; a pass begun while another is open
    // is closed as unknown, never attributed to a guessed operation.
    profiler.Begin(Pass(1, 1), 1);
    profiler.NoteDraw();
    profiler.End(EndReason::Dispatch);
    profiler.Begin(Pass(1, 1), 1);
    profiler.End(EndReason::Readback);
    profiler.Begin(Pass(1, 1), 1);
    profiler.Begin(Pass(2, 2), 1);
    profiler.End(EndReason::Submit);

    const Totals totals = profiler.SessionTotals();
    REQUIRE(totals.passes == 4);
    REQUIRE(totals.draws == 1);
    REQUIRE(totals.empty == 3);
    REQUIRE(totals.ended_by[static_cast<size_t>(EndReason::Dispatch)] == 1);
    REQUIRE(totals.ended_by[static_cast<size_t>(EndReason::Readback)] == 1);
    REQUIRE(totals.ended_by[static_cast<size_t>(EndReason::Unknown)] == 1);
    REQUIRE(totals.same_target_reopen == 2);
    REQUIRE(totals.reopen_after[static_cast<size_t>(EndReason::Dispatch)] == 1);
    REQUIRE(totals.reopen_after[static_cast<size_t>(EndReason::Readback)] == 1);

    const json session = json::parse(profiler.SessionJson());
    REQUIRE(session["ended_by"].size() == EndReasonCount); // zero reasons are kept
    REQUIRE(session["ended_by"]["unknown"] == 1);
}

TEST_CASE("RenderPasses: shapes are registered again after a reset", "[common]") {
    Profiler profiler;
    REQUIRE(profiler.Begin(Pass(7, 7), 1));
    profiler.End(EndReason::Submit);
    REQUIRE_FALSE(profiler.Begin(Pass(7, 7), 1)); // known: no lock, no new entry
    profiler.End(EndReason::Submit);
    profiler.Reset();
    REQUIRE(profiler.Begin(Pass(7, 7), 1)); // new capture: described again
    profiler.Describe(Pass(7, 7), "again");
    profiler.End(EndReason::Submit);
    const json session = json::parse(profiler.SessionJson());
    REQUIRE(session["shapes"]["top"][0]["description"] == "again");
    REQUIRE(session["shapes"]["top"][0]["passes"] == 1);
}

TEST_CASE("RenderPasses: a draw outside a pass is not counted", "[common]") {
    Profiler profiler;
    profiler.NoteDraw();
    profiler.End(EndReason::Other);
    REQUIRE(json::parse(profiler.TakeWindowJson())["passes"] == 0);
}

} // namespace Common::Diagnostics::RenderPasses
