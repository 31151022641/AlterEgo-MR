#pragma once
#include "HorizonAvatarTypes.h"
#include "HorizonHandReference.h"
#include <array>
#include <cmath>

namespace horizon {
constexpr int WearerPacketSize = 1060;
constexpr int HandBoneCount = 20;
// OpenXR joints retain their own axes. A runtime bind skeleton lets Avatar SDK
// retarget them without treating absolute XR rotations as local OVR rotations.
constexpr int XrHandBones[HandBoneCount] = {1,2,3,4,6,7,8,9,11,12,13,14,16,17,18,19,21,22,23,24};
constexpr int AvatarLeftHandBones[HandBoneCount] = {19,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43};
constexpr int HandParents[HandBoneCount] = {-1,0,1,2,0,4,5,6,0,8,9,10,0,12,13,14,0,16,17,18};
inline Quat handMultiply(Quat a, Quat b) {
    return {a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y,a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,
        a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w,a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z};
}
inline Vec3 handRotate(Quat q, Vec3 v) {
    auto r=handMultiply(handMultiply(q,{v.x,v.y,v.z,0}),{-q.x,-q.y,-q.z,q.w});return {r.x,r.y,r.z};
}
inline bool handTransform(const float* p, Transform& out) {
    for(int n=0;n<7;++n)if(!std::isfinite(p[n]))return false;
    const float norm=p[3]*p[3]+p[4]*p[4]+p[5]*p[5]+p[6]*p[6];
    if(norm<.5f || norm>1.5f)return false;
    const float inv=1/std::sqrt(norm);
    out={{p[0],p[1],p[2]},{p[3]*inv,p[4]*inv,p[5]*inv,p[6]*inv},{1,1,1}};return true;
}
inline Transform relativeHand(Transform child, Transform parent) {
    const Quat inv{-parent.orientation.x,-parent.orientation.y,-parent.orientation.z,parent.orientation.w};
    return {handRotate(inv,{child.position.x-parent.position.x,child.position.y-parent.position.y,child.position.z-parent.position.z}),
        handMultiply(inv,child.orientation),{1,1,1}};
}
using HandTransforms=std::array<Transform,HandBoneCount>;
inline bool readHandBind(const float* p, size_t size, int side, HandTransforms& out) {
    if(!p || size!=WearerPacketSize || side<0 || side>1 || p[642+side]!=1)return false;
    HandTransforms world{};
    for(int n=0;n<HandBoneCount;++n) {
        const int at=644+side*208+XrHandBones[n]*8;
        if(!handTransform(p+at+1,world[n]))return false;
    }
    for(int n=0;n<HandBoneCount;++n)out[n]=n?relativeHand(world[n],world[HandParents[n]]):Transform{{0,0,0},{0,0,0,1},{1,1,1}};
    return true;
}
inline bool readHandPose(const float* p, size_t size, int side, HandTransforms& out) {
    if(!p || (size!=642 && size!=WearerPacketSize) || side<0 || side>1 || p[3+side]!=1)return false;
    HandTransforms world{};
    for(int n=0;n<HandBoneCount;++n) {
        const int joint=XrHandBones[n], at=156+side*104+joint*4, q=434+side*104+joint*4;
        if(p[at]!=1)return false;
        float raw[]={p[at+1],p[at+2],p[at+3],p[q],p[q+1],p[q+2],p[q+3]};
        if(!handTransform(raw,world[n]))return false;
    }
    // Wrist motion is supplied through input tracking, not this finger override.
    for(int n=0;n<HandBoneCount;++n)out[n]=n?relativeHand(world[n],world[HandParents[n]]):Transform{{0,0,0},{0,0,0,1},{1,1,1}};
    return true;
}
inline Quat handInverse(Quat q) {return {-q.x,-q.y,-q.z,q.w};}
inline Vec3 subtractHand(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline Vec3 crossHand(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline bool normalizeHand(Vec3& p) {
    const float l=std::sqrt(p.x*p.x+p.y*p.y+p.z*p.z);if(!std::isfinite(l) || l<.00001f)return false;
    p={p.x/l,p.y/l,p.z/l};return true;
}
inline Quat basisHand(Vec3 x,Vec3 y,Vec3 z) {
    // Columns are a right-handed orthonormal basis. Stable at 180-degree turns.
    const float m[3][3]={{x.x,y.x,z.x},{x.y,y.y,z.y},{x.z,y.z,z.z}};
    Quat q{};float* a=&q.x;const float trace=m[0][0]+m[1][1]+m[2][2];
    if(trace>0){const float s=2*std::sqrt(trace+1);q={ (m[2][1]-m[1][2])/s,(m[0][2]-m[2][0])/s,(m[1][0]-m[0][1])/s,s/4};}
    else {int i=0;if(m[1][1]>m[i][i])i=1;if(m[2][2]>m[i][i])i=2;const int j=(i+1)%3,k=(i+2)%3;
        const float s=2*std::sqrt(1+m[i][i]-m[j][j]-m[k][k]);a[i]=s/4;a[j]=(m[i][j]+m[j][i])/s;a[k]=(m[i][k]+m[k][i])/s;q.w=(m[k][j]-m[j][k])/s;}
    return q;
}
inline bool palmBasisHand(Vec3 forward,Vec3 radial,Quat& q) {
    if(!normalizeHand(forward))return false;
    Vec3 normal=crossHand(forward,radial);if(!normalizeHand(normal))return false;
    radial=crossHand(normal,forward);if(!normalizeHand(radial))return false;
    q=basisHand(radial,normal,forward);return true;
}
inline Transform composeHand(Transform parent,Transform local) {
    const auto p=handRotate(parent.orientation,local.position);
    return {{parent.position.x+p.x,parent.position.y+p.y,parent.position.z+p.z},handMultiply(parent.orientation,local.orientation),{1,1,1}};
}
using CanonicalHandTransforms=std::array<Transform,CanonicalHandCount>;
struct HandRetarget {
    Quat correction{0,0,0,1};
    CanonicalHandTransforms canonicalRelative{}, xrRelative{};
    Quat wristOrientation(Quat xrWrist) const {
        // The SDK hand provider's neutral wrist faces opposite the custom
        // reference prefab. Keep that provider basis change out of finger
        // deltas, which are evaluated in the prefab's own local axes.
        return handMultiply(handMultiply(xrWrist,correction),{0,1,0,0});
    }
    bool initialize(const float* p,size_t size,int side) {
        if(!p || size!=WearerPacketSize || side<0 || side>1 || p[642+side]!=1)return false;
        CanonicalHandTransforms world{};Transform xrWrist{};
        if(!handTransform(p+644+side*208+8+1,xrWrist))return false;
        for(int n=0;n<CanonicalHandCount;++n) {
            world[n]=n?composeHand(world[CanonicalParents[n]],CanonicalHands[side][n]):CanonicalHands[side][n];
            const int joint=CanonicalXrJoints[n];
            if(joint>=0){Transform xr{};if(!handTransform(p+644+side*208+joint*8+1,xr))return false;xrRelative[n]=relativeHand(xr,xrWrist);}
        }
        for(int n=1;n<CanonicalHandCount;++n)canonicalRelative[n]=relativeHand(world[n],world[1]);
        Quat canonicalBasis{},xrBasis{};
        if(!palmBasisHand(canonicalRelative[11].position,subtractHand(canonicalRelative[6].position,canonicalRelative[18].position),canonicalBasis) ||
            !palmBasisHand(xrRelative[11].position,subtractHand(xrRelative[6].position,xrRelative[18].position),xrBasis))return false;
        correction=handMultiply(xrBasis,handInverse(canonicalBasis));return true;
    }
    bool pose(const float* p,size_t size,int side,CanonicalHandTransforms& out) const {
        if(!p || (size!=642 && size!=WearerPacketSize) || side<0 || side>1 || p[3+side]!=1)return false;
        Transform wrist{};const int at=156+side*104+4,q=434+side*104+4;
        float raw[]={p[at+1],p[at+2],p[at+3],p[q],p[q+1],p[q+2],p[q+3]};
        if(p[at]!=1 || !handTransform(raw,wrist))return false;
        std::array<Quat,CanonicalHandCount> target{};target[1]={0,0,0,1};
        for(int n=0;n<CanonicalHandCount;++n)out[n]=CanonicalHands[side][n];
        for(int n=2;n<CanonicalHandCount;++n) {
            const int joint=CanonicalXrJoints[n];target[n]=canonicalRelative[n].orientation;
            if(joint>=0) {
                const int at=156+side*104+joint*4,q=434+side*104+joint*4;
                float raw[]={p[at+1],p[at+2],p[at+3],p[q],p[q+1],p[q+2],p[q+3]};Transform current{};
                if(p[at]!=1 || !handTransform(raw,current))return false;
                const Quat delta=handMultiply(relativeHand(current,wrist).orientation,handInverse(xrRelative[n].orientation));
                target[n]=handMultiply(handMultiply(handMultiply(handInverse(correction),delta),correction),target[n]);
            }
            out[n].orientation=handMultiply(handInverse(target[CanonicalParents[n]]),target[n]);
        }
        return true;
    }
};
}
