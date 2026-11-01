#pragma once

#include <cmath>
#include <vector>

namespace horizon {
enum class MorphUploadState { Invalid, Changed, Unchanged };

// This caches only the morph vertices already uploaded to a mesh's VBO. Bone
// animation and the SDK frame update must still run when the weights match.
class MorphUploadCache {
public:
    MorphUploadState inspect(const std::vector<float>& weights) const {
        if (!finite(weights)) return MorphUploadState::Invalid;
        if (!uploaded || weights != uploadedWeights) return MorphUploadState::Changed;
        return MorphUploadState::Unchanged;
    }

    // Call only after a successful GL upload. Failed uploads must reset the
    // cache, since their buffer contents cannot be used to skip the retry.
    bool confirmUpload(const std::vector<float>& weights) {
        if (!finite(weights)) return false;
        uploadedWeights = weights;
        uploaded = true;
        return true;
    }

    void reset() {
        uploaded = false;
        uploadedWeights.clear();
    }

private:
    static bool finite(const std::vector<float>& weights) {
        for (const float weight : weights) if (!std::isfinite(weight)) return false;
        return true;
    }

    bool uploaded = false;
    std::vector<float> uploadedWeights;
};
} // namespace horizon
