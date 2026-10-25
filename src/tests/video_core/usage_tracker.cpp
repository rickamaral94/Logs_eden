// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include <catch2/catch_test_macros.hpp>

#include "video_core/buffer_cache/usage_tracker.h"

namespace VideoCommon {

TEST_CASE("UsageTracker: every touched 64-byte block is marked", "[video_core]") {
    UsageTracker tracker(1 << 20);
    tracker.Track(32, 64); // bytes 32-95: blocks 0 and 1
    REQUIRE(tracker.IsUsed(0, 1));
    REQUIRE(tracker.IsUsed(64, 32));
    REQUIRE(tracker.IsUsed(96, 32));
    REQUIRE_FALSE(tracker.IsUsed(128, 32));
}

TEST_CASE("UsageTracker: ranges smaller than a block", "[video_core]") {
    UsageTracker tracker(1 << 20);
    tracker.Track(10, 4);
    REQUIRE(tracker.IsUsed(12, 1));
    REQUIRE_FALSE(tracker.IsUsed(64, 64));
}

TEST_CASE("UsageTracker: ranges across and up to page ends", "[video_core]") {
    UsageTracker tracker(1 << 20);
    tracker.Track(4096 - 16, 64);
    REQUIRE(tracker.IsUsed(4096 - 8, 4));
    REQUIRE(tracker.IsUsed(4096, 8));
    REQUIRE_FALSE(tracker.IsUsed(4096 + 64, 8));
    REQUIRE_FALSE(tracker.IsUsed(0, 64));

    UsageTracker whole(1 << 20);
    whole.Track(0, 4096);
    REQUIRE(whole.IsUsed(4032, 64));
    REQUIRE_FALSE(whole.IsUsed(4096, 64));
    whole.Reset();
    REQUIRE_FALSE(whole.IsUsed(0, 4096));
}

} // namespace VideoCommon
