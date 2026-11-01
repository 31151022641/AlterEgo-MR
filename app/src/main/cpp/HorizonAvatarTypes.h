#pragma once
#include <cstddef>
#include <cstdint>

// ABI subset of Meta Avatars SDK 40.0.1 CAPI. Derived from the official
// OvrAvatarAPI_{Types,Entity,Asset,Render,Tracking}.cs declarations, distributed
// under the Meta SDK license in assets/licenses/Meta-Avatars-SDK.md.
// All opaque Avatar IDs are int32, unlike Platform SDK account/request IDs.
namespace horizon {
struct Vec2 { float x, y; };
struct Vec3 { float x, y, z; };
struct Vec4 { float x, y, z, w; };
using Quat = Vec4;
struct Transform { Vec3 position; Quat orientation; Vec3 scale; };
struct Matrix { float v[16]; }; // column major
struct Filters {
    int32_t lod, manifestation, view, inclusion, quality;
    bool loadRigZip;
    uint8_t reserved[32];
    int32_t vertexPreference;
};
struct CreateInfo { int32_t features; Filters filters; };
struct NetworkSettings { uint32_t timeout, lowSpeedSeconds, lowSpeedBytes; };
struct LoadSettings {
    Filters filters;
    NetworkSettings specification, asset;
    uint32_t textureBytes, morphLods;
    uint8_t reserved1;
    bool prefetch, validateCache;
    uint8_t reserved2;
};
struct LoadInfo { int32_t id, entity, state, failure, type; int64_t response; };
struct Resource { int32_t status, id; };
struct Primitive {
    int32_t id, vertices, morphs, compact;
    uint32_t indexCount;
    uint16_t minIndex, maxIndex;
    int32_t alpha;
    uint32_t textureCount, jointCount, skeleton, skinningCost;
};
struct Image { int32_t id; uint32_t format, width, height, mips, bytes; };
struct MaterialTexture { int32_t type; Vec4 factor; int32_t image; };
struct Pose { uint32_t count; const Transform *local, *object; const int32_t *parents, *nodes; };
struct RenderState {
    Transform root;
    uint32_t count;
    int32_t hierarchy, allVersion, visibleVersion;
    const int32_t *all, *visible;
    uint32_t allCount, visibleCount;
};
struct PrimitiveState {
    int32_t instance, primitive, node;
    Transform local, world;
    Pose pose;
    uint32_t morphCount;
    Transform skinningOrigin;
};
struct InputTracking {
    bool headActive, leftActive, rightActive, leftVisible, rightVisible;
    Transform head, left, right;
};
struct FacePose { float weights[72], confidence[72]; };
struct EyePose { Quat orientation; Vec3 position; bool valid; };
struct EyesPose { EyePose left, right; };
struct HandState {
    Transform left, right;
    Quat rotations[34];
    float leftScale, rightScale;
    bool leftTracked, rightTracked, leftConfident, rightConfident;
};
struct TrackingBone { int32_t joint; int16_t parent; };
struct TrackingPose { uint32_t count; int32_t space; const Transform* bones; float leftScale, rightScale; };
struct TrackingSkeleton { uint32_t count; Vec3 forward; const TrackingBone* bones; TrackingPose reference; };
template<class T> struct Provider { void* context; bool (*callback)(T*, void*); };

static_assert(sizeof(Transform) == 40);
static_assert(sizeof(Filters) == 60 && offsetof(Filters, vertexPreference) == 56);
static_assert(sizeof(CreateInfo) == 64 && sizeof(LoadSettings) == 96);
static_assert(sizeof(LoadInfo) == 32 && sizeof(Primitive) == 44);
static_assert(sizeof(Image) == 24 && sizeof(MaterialTexture) == 24);
static_assert(sizeof(Pose) == 40 && sizeof(RenderState) == 80);
static_assert(sizeof(PrimitiveState) == 184 && offsetof(PrimitiveState, pose) == 96);
static_assert(sizeof(InputTracking) == 128 && sizeof(FacePose) == 576 && sizeof(EyesPose) == 64);
static_assert(sizeof(HandState) == 636 && offsetof(HandState, rotations) == 80);
static_assert(sizeof(TrackingBone) == 8 && sizeof(TrackingPose) == 24 && sizeof(TrackingSkeleton) == 48);
}
