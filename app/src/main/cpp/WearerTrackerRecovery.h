#pragma once

#include <cstdint>

namespace wearer {
enum Capability : uint32_t {
    Body = 1u << 0,
    Face = 1u << 1,
    LeftHand = 1u << 2,
    RightHand = 1u << 3,
    LeftBind = 1u << 4,
    RightBind = 1u << 5,
};

// A handle or immutable bind mesh can be missing while the other sensors work.
// Tracking validity is deliberately absent: occlusion never recreates a tracker.
constexpr uint32_t missingCapabilities(uint32_t permitted, uint32_t supported, uint32_t ready) {
    return permitted & supported & ~ready;
}

class RecoverySchedule {
public:
    static constexpr int64_t IntervalMs = 2000;

    bool claim(uint32_t missing, int64_t nowMs) {
        if (!missing || nowMs < 0 || nowMs < nextAttemptAtMs) return false;
        nextAttemptAtMs = nowMs + IntervalMs;
        return true;
    }

private:
    int64_t nextAttemptAtMs = 0;
};
} // namespace wearer
