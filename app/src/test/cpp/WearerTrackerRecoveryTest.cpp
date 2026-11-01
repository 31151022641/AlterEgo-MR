#include "../../main/cpp/WearerTrackerRecovery.h"

#include <array>
#include <cassert>

namespace {
constexpr uint32_t All = wearer::Body | wearer::Face | wearer::LeftHand |
    wearer::RightHand | wearer::LeftBind | wearer::RightBind;

// Simulate only resource creation outcomes. Sample confidence/visibility cannot
// enter this policy, and successful resources remain in the ready set.
struct RecoveryFixture {
    wearer::RecoverySchedule schedule;
    uint32_t permitted = All;
    uint32_t supported = All;
    uint32_t ready = 0;
    std::array<int, 6> attempts{};

    bool attempt(int64_t now, uint32_t succeeds) {
        const auto missing = wearer::missingCapabilities(permitted, supported, ready);
        if (!schedule.claim(missing, now)) return false;
        for (int n = 0; n < 6; ++n) {
            const auto bit = 1u << n;
            if (missing & bit) { ++attempts[n]; if (succeeds & bit) ready |= bit; }
        }
        return true;
    }
};
}

int main() {
    {
        RecoveryFixture f;
        const auto firstSuccesses = wearer::Body | wearer::LeftHand | wearer::LeftBind;
        assert(f.attempt(0, firstSuccesses));
        assert(f.ready == firstSuccesses);
        assert(!f.attempt(1999, All));
        assert(f.attempt(2000, All));
        assert(f.ready == All);
        assert(f.attempts[0] == 1 && f.attempts[2] == 1 && f.attempts[4] == 1);
        assert(f.attempts[1] == 2 && f.attempts[3] == 2 && f.attempts[5] == 2);
        assert(!f.attempt(10000, All));
    }
    {
        RecoveryFixture f;
        f.permitted = wearer::LeftHand | wearer::RightHand | wearer::LeftBind | wearer::RightBind;
        f.supported = wearer::LeftHand | wearer::RightHand;
        assert(f.attempt(500, All));
        assert(f.ready == (wearer::LeftHand | wearer::RightHand));
        assert(f.attempts[0] == 0 && f.attempts[1] == 0);
        assert(f.attempts[4] == 0 && f.attempts[5] == 0);
        assert(!f.attempt(2500, All));
    }
    {
        RecoveryFixture f;
        f.ready = All & ~wearer::RightBind;
        assert(f.attempt(100, 0));
        assert(!f.attempt(2099, All));
        assert(f.attempt(2100, wearer::RightBind));
        for (int n = 0; n < 5; ++n) assert(f.attempts[n] == 0);
        assert(f.attempts[5] == 2);
        assert(!f.attempt(5000, All));
    }
    {
        RecoveryFixture f;
        f.permitted = 0;
        assert(!f.attempt(0, All));
        f.permitted = All;
        assert(f.attempt(1, All));
        // Once created, a lost observation does not make any handle missing.
        assert(!f.attempt(2001, 0));
    }
    {
        RecoveryFixture f;
        assert(!f.attempt(-1, All));
        assert(f.attempt(0, 0));
        assert(!f.attempt(33, 0));
        assert(f.attempt(2000, 0));
        assert(!f.attempt(2033, 0));
    }
    return 0;
}
