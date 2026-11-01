#include "../../main/cpp/HorizonMorphUploadCache.h"

#include <cassert>
#include <limits>

int main() {
    using horizon::MorphUploadCache;
    using horizon::MorphUploadState;
    const std::vector<float> neutral{0.f, 0.f, 0.f};
    const std::vector<float> expression{0.f, .4f, .2f};
    {
        MorphUploadCache cache;
        // A freshly allocated VBO still requires the first SDK-weight upload.
        assert(cache.inspect(neutral) == MorphUploadState::Changed);
        assert(cache.inspect(neutral) == MorphUploadState::Changed);
        assert(cache.confirmUpload(neutral));
        assert(cache.inspect(neutral) == MorphUploadState::Unchanged);
        assert(cache.inspect(expression) == MorphUploadState::Changed);
        // Inspecting another frame never marks it uploaded.
        assert(cache.inspect(neutral) == MorphUploadState::Unchanged);
        assert(cache.confirmUpload(expression));
        assert(cache.inspect(expression) == MorphUploadState::Unchanged);
        assert(cache.inspect(neutral) == MorphUploadState::Changed);
    }
    {
        MorphUploadCache cache;
        assert(cache.confirmUpload(expression));
        auto tinyChange = expression;
        tinyChange[0] = .000001f;
        assert(cache.inspect(tinyChange) == MorphUploadState::Changed);
        auto adjacentChange = expression;
        adjacentChange[1] = std::nextafter(.4f, 1.f);
        assert(cache.inspect(adjacentChange) == MorphUploadState::Changed);
        assert(cache.inspect({0.f, .4f}) == MorphUploadState::Changed);
    }
    {
        MorphUploadCache cache;
        assert(cache.confirmUpload(expression));
        for (float invalid : {std::numeric_limits<float>::quiet_NaN(),
                              std::numeric_limits<float>::infinity(),
                              -std::numeric_limits<float>::infinity()}) {
            auto weights = expression;
            weights.back() = invalid;
            assert(cache.inspect(weights) == MorphUploadState::Invalid);
            assert(!cache.confirmUpload(weights));
            assert(cache.inspect(expression) == MorphUploadState::Unchanged);
        }
        // Failed uploads or resource deletion force a retry even if the next
        // SDK frame has the same weights as the last successful frame.
        cache.reset();
        assert(cache.inspect(expression) == MorphUploadState::Changed);
        assert(cache.confirmUpload(expression));
        assert(cache.inspect(expression) == MorphUploadState::Unchanged);
    }
    {
        // Each primitive owns its own cached buffer; one mesh's upload cannot
        // establish the contents of another mesh or a newly loaded resource.
        MorphUploadCache first, second;
        assert(first.confirmUpload(expression));
        assert(first.inspect(expression) == MorphUploadState::Unchanged);
        assert(second.inspect(expression) == MorphUploadState::Changed);
        assert(second.confirmUpload({}));
        assert(second.inspect({}) == MorphUploadState::Unchanged);
        second.reset();
        assert(second.inspect({}) == MorphUploadState::Changed);
    }
    return 0;
}
