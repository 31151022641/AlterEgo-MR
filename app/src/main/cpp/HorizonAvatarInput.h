#pragma once
#include "HorizonAvatarTypes.h"
#include <algorithm>
#include <cmath>

namespace horizon {
// OpenXR FB_face_tracking2 and Avatar SDK 40 do not use the same expression
// layout after index 49. FB's tongue weights must never drive brows or lips.
constexpr int faceExpressionIndex(int sdkIndex) {
    if (sdkIndex < 0 || sdkIndex >= 72) return -1;
    if (sdkIndex < 50) return sdkIndex;
    constexpr int tail[] = {50,50,50,50,51,52,53,54,-1,-1,55,56,-1,-1,-1,-1,57,58,59,60,61,62};
    return tail[sdkIndex-50];
}
inline FacePose mapFaceExpressions(const float* weights, size_t count) {
    FacePose result{};
    if (!weights || count < 70) return result;
    for (int sdk=0;sdk<72;++sdk) {
        const int source=faceExpressionIndex(sdk);
        if (source>=0 && std::isfinite(weights[source])) {
            result.weights[sdk]=std::clamp(weights[source],0.f,1.f);
            result.confidence[sdk]=1.f;
        }
    }
    return result;
}
}
