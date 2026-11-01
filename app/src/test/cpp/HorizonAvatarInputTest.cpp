#include "HorizonAvatarInput.h"
#include "HorizonAvatarHands.h"
#include <cassert>
#include <cstdio>
#include <limits>

int main() {
    float xr[70]{};
    // Native tongue channels must not leak into SDK brows or lips.
    for (int i=63;i<70;++i) xr[i]=1.f;
    auto face=horizon::mapFaceExpressions(xr,70);
    for (float weight:face.weights) assert(weight==0.f);
    // The single OpenXR lips-toward channel feeds all four SDK quadrants.
    xr[50]=.6f;
    face=horizon::mapFaceExpressions(xr,70);
    for (int i=50;i<54;++i) assert(face.weights[i]==.6f);
    xr[57]=.3f; xr[61]=.8f;
    face=horizon::mapFaceExpressions(xr,70);
    assert(face.weights[66]==.3f && face.weights[70]==.8f);
    // SDK-only nasolabial/nostril channels remain explicitly unsupported.
    for (int i:{58,59,62,63,64,65}) assert(face.confidence[i]==0.f);
    xr[24]=2.f; xr[12]=-.4f; xr[13]=std::numeric_limits<float>::quiet_NaN();
    face=horizon::mapFaceExpressions(xr,70);
    assert(face.weights[24]==1.f && face.weights[12]==0.f);
    assert(face.weights[13]==0.f && face.confidence[13]==0.f);
    assert(horizon::mapFaceExpressions(xr,69).confidence[0]==0.f);
    assert(horizon::mapFaceExpressions(nullptr,70).confidence[0]==0.f);
    assert(horizon::faceExpressionIndex(-1)==-1 && horizon::faceExpressionIndex(72)==-1);
    // Retarget finger motion in parent space, independent of a wrist/world turn.
    float packet[horizon::WearerPacketSize]{};
    packet[3]=packet[642]=1;
    const horizon::Quat turn{0,.70710678f,0,.70710678f};
    for(int n=0;n<horizon::HandBoneCount;++n) {
        const int joint=horizon::XrHandBones[n], at=156+joint*4, quat=434+joint*4, bind=644+joint*8;
        packet[at]=1;packet[at+1]=.02f*n;packet[at+2]=1.2f;packet[at+3]=-.3f;packet[quat+3]=1;
        packet[bind]=n?horizon::XrHandBones[horizon::HandParents[n]]:-1;
        packet[bind+1]=.02f*n;packet[bind+7]=1;
    }
    horizon::HandTransforms baseline{}, rotated{}, bind{};
    assert(horizon::readHandBind(packet,horizon::WearerPacketSize,0,bind));
    assert(horizon::readHandPose(packet,horizon::WearerPacketSize,0,baseline));
    for(int n=0;n<horizon::HandBoneCount;++n) {
        const int joint=horizon::XrHandBones[n], at=156+joint*4, quat=434+joint*4;
        const auto pos=horizon::handRotate(turn,{packet[at+1],packet[at+2],packet[at+3]});
        packet[at+1]=pos.x;packet[at+2]=pos.y;packet[at+3]=pos.z;
        packet[quat]=turn.x;packet[quat+1]=turn.y;packet[quat+2]=turn.z;packet[quat+3]=turn.w;
    }
    assert(horizon::readHandPose(packet,horizon::WearerPacketSize,0,rotated));
    for(int n=0;n<horizon::HandBoneCount;++n) {
        assert(std::abs(baseline[n].position.x-rotated[n].position.x)<.00001f);
        assert(std::abs(baseline[n].position.z-rotated[n].position.z)<.00001f);
        assert(std::abs(rotated[n].orientation.w-1)<.00001f);
    }
    // Flexing one index joint changes that finger, without moving the wrist.
    const int index=horizon::XrHandBones[6], q=434+index*4;
    const auto curl=horizon::handMultiply(turn,{.5f,0,0,.8660254f});
    packet[q]=curl.x;packet[q+1]=curl.y;packet[q+2]=curl.z;packet[q+3]=curl.w;
    assert(horizon::readHandPose(packet,horizon::WearerPacketSize,0,rotated));
    assert(std::abs(rotated[6].orientation.x-.5f)<.00001f);
    assert(rotated[0].orientation.w==1);
    assert(rotated[10].orientation.w>.999f);
    // Partial/invalid/lost hands never become a claimed articulated hand.
    packet[156+index*4]=0;assert(!horizon::readHandPose(packet,horizon::WearerPacketSize,0,rotated));
    packet[156+index*4]=1;packet[3]=0;assert(!horizon::readHandPose(packet,horizon::WearerPacketSize,0,rotated));
    packet[642]=0;assert(!horizon::readHandBind(packet,horizon::WearerPacketSize,0,bind));
    assert(!horizon::readHandPose(packet,434,0,rotated));
    // A canonical open hand must stay canonical after mapping from XR axes.
    float neutral[horizon::WearerPacketSize]{};
    neutral[3]=neutral[642]=1;
    for(int j=0;j<26;++j) {
        const int at=156+j*4,q=434+j*4,b=644+j*8;
        neutral[at]=1;neutral[q+3]=neutral[b+7]=1;
        neutral[at+1]=neutral[b+1]=.001f*j;
        neutral[at+3]=neutral[b+3]=-.004f*j;
    }
    // Non-collinear middle/radial directions define a proper wrist basis.
    neutral[156+12*4+1]=neutral[644+12*8+1]=0;
    neutral[156+12*4+3]=neutral[644+12*8+3]=-.09f;
    neutral[156+6*4+1]=neutral[644+6*8+1]=-.025f;
    neutral[156+21*4+1]=neutral[644+21*8+1]=.025f;
    horizon::HandRetarget retarget;
    assert(retarget.initialize(neutral,horizon::WearerPacketSize,0));
    horizon::CanonicalHandTransforms canonical{};
    assert(retarget.pose(neutral,horizon::WearerPacketSize,0,canonical));
    for(int n=0;n<horizon::CanonicalHandCount;++n) {
        const auto expected=horizon::CanonicalHands[0][n].orientation,actual=canonical[n].orientation;
        assert(std::abs(actual.x-expected.x)<.00002f && std::abs(actual.y-expected.y)<.00002f);
        assert(std::abs(actual.z-expected.z)<.00002f && std::abs(actual.w-expected.w)<.00002f);
    }
    // SDK provider axes differ from the custom prefab by a half turn. Check
    // both sides through a pitched/yawed wrist, including the radial axis so
    // a correction that merely points the fingers up cannot hide a palm twist.
    neutral[4]=neutral[643]=1;
    for(int j=0;j<26;++j) {
        std::copy_n(neutral+156+j*4,4,neutral+260+j*4);
        std::copy_n(neutral+434+j*4,4,neutral+538+j*4);
        std::copy_n(neutral+644+j*8,8,neutral+852+j*8);
        neutral[260+j*4+1]*=-1;neutral[852+j*8+1]*=-1;
    }
    const auto wristTurn=horizon::handMultiply(turn,{.70710678f,0,0,.70710678f});
    for(int side=0;side<2;++side) {
        horizon::HandRetarget calibrated;
        assert(calibrated.initialize(neutral,horizon::WearerPacketSize,side));
        horizon::Quat nativeBasis{},xrBasis{};
        assert(horizon::palmBasisHand(calibrated.canonicalRelative[11].position,
            horizon::subtractHand(calibrated.canonicalRelative[6].position,calibrated.canonicalRelative[18].position),nativeBasis));
        assert(horizon::palmBasisHand(calibrated.xrRelative[11].position,
            horizon::subtractHand(calibrated.xrRelative[6].position,calibrated.xrRelative[18].position),xrBasis));
        for(const auto axis:{horizon::Vec3{0,0,1},horizon::Vec3{1,0,0}}) {
            const auto providerAxis=horizon::handRotate({0,1,0,0},horizon::handRotate(nativeBasis,axis));
            const auto actual=horizon::handRotate(calibrated.wristOrientation(wristTurn),providerAxis);
            const auto expected=horizon::handRotate(wristTurn,horizon::handRotate(xrBasis,axis));
            assert(std::abs(actual.x-expected.x)<.00003f && std::abs(actual.y-expected.y)<.00003f);
            assert(std::abs(actual.z-expected.z)<.00003f);
        }
    }
    // Missing calibration cannot produce an arbitrary wrist correction.
    neutral[642]=0;assert(!retarget.initialize(neutral,horizon::WearerPacketSize,0));
    std::puts("Native avatar expression mapping: passed");
    std::puts("Native articulated-hand retargeting: passed");
}
