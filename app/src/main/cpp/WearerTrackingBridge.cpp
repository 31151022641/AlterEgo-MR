#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <dlfcn.h>
#include <time.h>
#include <cmath>
#include <memory>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "WearerTrackerRecovery.h"

namespace {
template<class T> bool proc(XrInstance i, PFN_xrGetInstanceProcAddr loader, const char* name, T& p) {
    return XR_SUCCEEDED(loader(i, name, reinterpret_cast<PFN_xrVoidFunction*>(&p))) && p;
}
struct Wearer {
    XrInstance instance{};
    XrSession session{};
    XrSpace local{}, view{};
    XrBodyTrackerFB body{};
    XrHandTrackerEXT hands[2]{};
    XrFaceTracker2FB face{};
    XrPosef handBind[2][XR_HAND_JOINT_COUNT_EXT]{};
    XrHandJointEXT handParents[2][XR_HAND_JOINT_COUNT_EXT]{};
    bool handBindValid[2]{};
    PFN_xrConvertTimespecTimeToTimeKHR time{};
    PFN_xrLocateSpace locate{};
    PFN_xrDestroySpace destroySpace{};
    PFN_xrCreateBodyTrackerFB createBody{};
    PFN_xrLocateBodyJointsFB locateBody{};
    PFN_xrDestroyBodyTrackerFB destroyBody{};
    PFN_xrCreateHandTrackerEXT createHand{};
    PFN_xrLocateHandJointsEXT locateHand{};
    PFN_xrDestroyHandTrackerEXT destroyHand{};
    PFN_xrGetHandMeshFB getHandMesh{};
    PFN_xrCreateFaceTracker2FB createFace{};
    PFN_xrGetFaceExpressionWeights2FB getFace{};
    PFN_xrDestroyFaceTracker2FB destroyFace{};
    uint32_t permittedCapabilities{};
    uint32_t supportedCapabilities{};
    wearer::RecoverySchedule recovery;
    int lastSampleStatus{-1};
    ~Wearer() {
        if (face && destroyFace) destroyFace(face);
        for (auto h : hands) if (h && destroyHand) destroyHand(h);
        if (body && destroyBody) destroyBody(body);
        if (view && destroySpace) destroySpace(view);
        if (local && destroySpace) destroySpace(local);
    }
};
bool valid(XrSpaceLocationFlags flags, const XrPosef& p) {
    constexpr auto bits = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    return (flags & bits) == bits && std::isfinite(p.position.x) && std::isfinite(p.position.y) &&
        std::isfinite(p.position.z) && std::isfinite(p.orientation.w) && std::isfinite(p.orientation.x) &&
        std::isfinite(p.orientation.y) && std::isfinite(p.orientation.z);
}
void pose(float* out, const XrPosef& p) {
    out[0]=p.position.x; out[1]=p.position.y; out[2]=p.position.z;
    out[3]=p.orientation.x; out[4]=p.orientation.y; out[5]=p.orientation.z; out[6]=p.orientation.w;
}
void loadHandBind(Wearer& t, int side, PFN_xrGetHandMeshFB getMesh) {
    XrHandTrackingMeshFB mesh{XR_TYPE_HAND_TRACKING_MESH_FB};
    const auto queryResult=getMesh(t.hands[side],&mesh);
    if(XR_FAILED(queryResult) || mesh.jointCountOutput!=XR_HAND_JOINT_COUNT_EXT ||
        mesh.vertexCountOutput>65536 || mesh.indexCountOutput>300000) {
        __android_log_print(ANDROID_LOG_WARN,"AlterEgoWearer",
            "Hand %d bind query: result=%d ready=0",side,queryResult);
        return;
    }
    std::vector<float> radii(mesh.jointCountOutput);
    std::vector<XrVector3f> positions(mesh.vertexCountOutput),normals(mesh.vertexCountOutput);
    std::vector<XrVector2f> uv(mesh.vertexCountOutput);
    std::vector<XrVector4sFB> blendIndices(mesh.vertexCountOutput);
    std::vector<XrVector4f> blendWeights(mesh.vertexCountOutput);
    std::vector<int16_t> indices(mesh.indexCountOutput);
    mesh.jointCapacityInput=XR_HAND_JOINT_COUNT_EXT;
    mesh.jointBindPoses=t.handBind[side];mesh.jointParents=t.handParents[side];mesh.jointRadii=radii.data();
    mesh.vertexCapacityInput=positions.size();mesh.vertexPositions=positions.data();mesh.vertexNormals=normals.data();
    mesh.vertexUVs=uv.data();mesh.vertexBlendIndices=blendIndices.data();mesh.vertexBlendWeights=blendWeights.data();
    mesh.indexCapacityInput=indices.size();mesh.indices=indices.data();
    const auto result=getMesh(t.hands[side],&mesh);
    t.handBindValid[side]=XR_SUCCEEDED(result);
    __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer","Hand %d bind skeleton: result=%d ready=%d",side,result,t.handBindValid[side]);
}
uint32_t readyCapabilities(const Wearer& t) {
    return (t.body?wearer::Body:0u) | (t.face?wearer::Face:0u) |
        (t.hands[0]?wearer::LeftHand:0u) | (t.hands[1]?wearer::RightHand:0u) |
        (t.handBindValid[0]?wearer::LeftBind:0u) | (t.handBindValid[1]?wearer::RightBind:0u);
}
int64_t milliseconds(const timespec& ts) {
    return static_cast<int64_t>(ts.tv_sec)*1000 + ts.tv_nsec/1000000;
}
bool retryMissingCapabilities(Wearer& t, int64_t nowMs) {
    const auto before=readyCapabilities(t);
    const auto missing=wearer::missingCapabilities(t.permittedCapabilities,t.supportedCapabilities,before);
    if(!t.recovery.claim(missing,nowMs))return false;
    if(missing & wearer::Body) {
        XrBodyTrackerCreateInfoFB info{XR_TYPE_BODY_TRACKER_CREATE_INFO_FB};
        info.bodyJointSet=XR_BODY_JOINT_SET_DEFAULT_FB;
        const auto result=t.createBody(t.session,&info,&t.body);
        if(XR_FAILED(result))t.body=XR_NULL_HANDLE;
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer","Body tracker creation: %d",result);
    }
    for(int side=0;side<2;++side) {
        const auto handBit=side?wearer::RightHand:wearer::LeftHand;
        const auto bindBit=side?wearer::RightBind:wearer::LeftBind;
        if(missing & handBit) {
            XrHandTrackerCreateInfoEXT info{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
            info.hand=side==0?XR_HAND_LEFT_EXT:XR_HAND_RIGHT_EXT;
            info.handJointSet=XR_HAND_JOINT_SET_DEFAULT_EXT;
            const auto result=t.createHand(t.session,&info,&t.hands[side]);
            if(XR_FAILED(result))t.hands[side]=XR_NULL_HANDLE;
            __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer","Hand %d tracker creation: %d",side,result);
        }
        if((missing & bindBit) && t.hands[side])loadHandBind(t,side,t.getHandMesh);
    }
    if(missing & wearer::Face) {
        // Visual sensors only. Retrying never enables an audio-derived source.
        XrFaceTrackingDataSource2FB source=XR_FACE_TRACKING_DATA_SOURCE2_VISUAL_FB;
        XrFaceTrackerCreateInfo2FB info{XR_TYPE_FACE_TRACKER_CREATE_INFO2_FB};
        info.faceExpressionSet=XR_FACE_EXPRESSION_SET2_DEFAULT_FB;
        info.requestedDataSourceCount=1;info.requestedDataSources=&source;
        const auto result=t.createFace(t.session,&info,&t.face);
        if(XR_FAILED(result))t.face=XR_NULL_HANDLE;
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer","Visual face tracker creation: %d",result);
    }
    __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer",
        "Sensor resource recovery: missingMask=%u readyBefore=%u readyAfter=%u intervalMs=2000",
        missing,before,readyCapabilities(t));
    return true;
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_oculus_camerademo_mirror_NativeWearerBridge_supportedExtensions(JNIEnv* env, jobject) {
    const auto cls=env->FindClass("java/lang/String");
    void* library=dlopen("libopenxr_loader.so", RTLD_NOW | RTLD_LOCAL);
    std::vector<XrExtensionProperties> props;
    if (library) {
        auto enumerate=reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(dlsym(library,"xrEnumerateInstanceExtensionProperties"));
        uint32_t count=0;
        if (enumerate && XR_SUCCEEDED(enumerate(nullptr,0,&count,nullptr)) && count < 2048) {
            props.resize(count);
            for (auto& p:props) p.type=XR_TYPE_EXTENSION_PROPERTIES;
            if (XR_FAILED(enumerate(nullptr,count,&count,props.data()))) props.clear();
        }
        dlclose(library);
    }
    auto output=env->NewObjectArray(static_cast<jsize>(props.size()),cls,nullptr);
    for (size_t n=0;n<props.size();n++) {
        auto str=env->NewStringUTF(props[n].extensionName);
        env->SetObjectArrayElement(output,static_cast<jsize>(n),str); env->DeleteLocalRef(str);
    }
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oculus_camerademo_mirror_NativeWearerBridge_create(JNIEnv*, jobject, jlong ih, jlong sh, jlong ph,
    jboolean allowBody, jboolean allowFace, jboolean allowHands) {
    if (!ih || !sh || !ph) return 0;
    auto t=std::make_unique<Wearer>();
    t->instance=reinterpret_cast<XrInstance>(static_cast<intptr_t>(ih));
    t->session=reinterpret_cast<XrSession>(static_cast<intptr_t>(sh));
    auto loader=reinterpret_cast<PFN_xrGetInstanceProcAddr>(static_cast<intptr_t>(ph));
    PFN_xrCreateReferenceSpace createSpace{};
    if (!proc(t->instance,loader,"xrConvertTimespecTimeToTimeKHR",t->time) ||
        !proc(t->instance,loader,"xrLocateSpace",t->locate) ||
        !proc(t->instance,loader,"xrDestroySpace",t->destroySpace) ||
        !proc(t->instance,loader,"xrCreateReferenceSpace",createSpace)) return 0;
    XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    space.poseInReferenceSpace.orientation.w=1;
    space.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_LOCAL;
    if (XR_FAILED(createSpace(t->session,&space,&t->local))) return 0;
    space.referenceSpaceType=XR_REFERENCE_SPACE_TYPE_VIEW;
    if (XR_FAILED(createSpace(t->session,&space,&t->view))) return 0;
    if(allowBody)t->permittedCapabilities|=wearer::Body;
    if(allowFace)t->permittedCapabilities|=wearer::Face;
    if(allowHands)t->permittedCapabilities|=wearer::LeftHand|wearer::RightHand|wearer::LeftBind|wearer::RightBind;
    if (allowBody && proc(t->instance,loader,"xrCreateBodyTrackerFB",t->createBody) &&
        proc(t->instance,loader,"xrLocateBodyJointsFB",t->locateBody) &&
        proc(t->instance,loader,"xrDestroyBodyTrackerFB",t->destroyBody)) {
        t->supportedCapabilities|=wearer::Body;
    }
    if (allowHands && proc(t->instance,loader,"xrCreateHandTrackerEXT",t->createHand) &&
        proc(t->instance,loader,"xrLocateHandJointsEXT",t->locateHand) &&
        proc(t->instance,loader,"xrDestroyHandTrackerEXT",t->destroyHand)) {
        t->supportedCapabilities|=wearer::LeftHand|wearer::RightHand;
        if(proc(t->instance,loader,"xrGetHandMeshFB",t->getHandMesh))
            t->supportedCapabilities|=wearer::LeftBind|wearer::RightBind;
    }
    if (allowFace && proc(t->instance,loader,"xrCreateFaceTracker2FB",t->createFace) &&
        proc(t->instance,loader,"xrGetFaceExpressionWeights2FB",t->getFace) &&
        proc(t->instance,loader,"xrDestroyFaceTracker2FB",t->destroyFace)) {
        t->supportedCapabilities|=wearer::Face;
    }
    __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer",
        "Sensor resource capabilities: permittedMask=%u supportedMask=%u; bits body=1 face=2 leftHand=4 rightHand=8 leftBind=16 rightBind=32",
        t->permittedCapabilities,t->supportedCapabilities);
    timespec initial{};
    if(clock_gettime(CLOCK_MONOTONIC,&initial)==0)retryMissingCapabilities(*t,milliseconds(initial));
    return reinterpret_cast<jlong>(t.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_oculus_camerademo_mirror_NativeWearerBridge_sample(JNIEnv* env, jobject, jlong handle) {
    auto t=reinterpret_cast<Wearer*>(handle); if (!t) return nullptr;
    timespec ts{}; XrTime now{};
    if (clock_gettime(CLOCK_MONOTONIC,&ts)) return nullptr;
    // Preserve existing trackers across occlusion. Only absent resources are
    // retried, on the already-active sampling path, at a bounded cadence.
    if(retryMissingCapabilities(*t,milliseconds(ts)) && clock_gettime(CLOCK_MONOTONIC,&ts))return nullptr;
    // Initialization work can be slower than a pose sample; acquire its request
    // time afterward instead of asking for the pre-retry timestamp.
    if(XR_FAILED(t->time(t->instance,&ts,&now)))return nullptr;
    // Flags 5, head pose 7, upper body 18*8, two hands 26*4 each, expression weights 70.
    // The original 434 fields are stable. Append all hand joint orientations.
    // Append immutable runtime bind skeletons for correctly retargeted fingers.
    float out[1060]{};
    for(int side=0;side<2;++side)if(t->handBindValid[side]) {
        out[642+side]=1;
        for(int j=0;j<XR_HAND_JOINT_COUNT_EXT;++j) {
            const int at=644+side*208+j*8;out[at]=static_cast<float>(t->handParents[side][j]);
            pose(out+at+1,t->handBind[side][j]);
        }
    }
    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
    if (XR_SUCCEEDED(t->locate(t->view,t->local,now,&head)) && valid(head.locationFlags,head.pose)) {
        out[0]=1; pose(out+5,head.pose);
    }
    XrResult bodyResult=XR_ERROR_HANDLE_INVALID, faceResult=XR_ERROR_HANDLE_INVALID;
    bool bodyActive=false, faceValid=false;
    XrDuration bodyAge=0, faceAge=0;
    if (t->body) {
        XrBodyJointLocationFB joints[XR_BODY_JOINT_COUNT_FB]{};
        XrBodyJointsLocateInfoFB info{XR_TYPE_BODY_JOINTS_LOCATE_INFO_FB}; info.baseSpace=t->local; info.time=now;
        XrBodyJointLocationsFB locations{XR_TYPE_BODY_JOINT_LOCATIONS_FB};
        locations.jointCount=XR_BODY_JOINT_COUNT_FB; locations.jointLocations=joints;
        bodyResult=t->locateBody(t->body,&info,&locations);
        bodyActive=locations.isActive; bodyAge=now-locations.time;
        if (XR_SUCCEEDED(bodyResult) && locations.isActive && locations.confidence>0 &&
            std::abs(now-locations.time)<150000000) {
            out[1]=1;
            for (int n=0;n<18;n++) if (valid(joints[n].locationFlags,joints[n].pose)) {
                out[12+n*8]=1; pose(out+13+n*8,joints[n].pose);
            }
        }
    }
    for (int n=0;n<2;n++) if (t->hands[n]) {
        XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};
        XrHandJointsLocateInfoEXT info{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT}; info.baseSpace=t->local; info.time=now;
        XrHandJointLocationsEXT locations{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
        locations.jointCount=XR_HAND_JOINT_COUNT_EXT; locations.jointLocations=joints;
        if (XR_SUCCEEDED(t->locateHand(t->hands[n],&info,&locations)) && locations.isActive) {
            out[3+n]=1;
            for (int j=0;j<XR_HAND_JOINT_COUNT_EXT;j++) if (valid(joints[j].locationFlags,joints[j].pose)) {
                int p=156+n*104+j*4; out[p]=1;
                out[p+1]=joints[j].pose.position.x; out[p+2]=joints[j].pose.position.y; out[p+3]=joints[j].pose.position.z;
                const auto q=joints[j].pose.orientation; const int at=434+n*104+j*4;
                out[at]=q.x;out[at+1]=q.y;out[at+2]=q.z;out[at+3]=q.w;
            }
        }
    }
    if (t->face) {
        float weights[XR_FACE_EXPRESSION2_COUNT_FB]{}, confidence[XR_FACE_CONFIDENCE2_COUNT_FB]{};
        XrFaceExpressionInfo2FB info{XR_TYPE_FACE_EXPRESSION_INFO2_FB}; info.time=now;
        XrFaceExpressionWeights2FB w{XR_TYPE_FACE_EXPRESSION_WEIGHTS2_FB};
        w.weightCount=XR_FACE_EXPRESSION2_COUNT_FB; w.weights=weights;
        w.confidenceCount=XR_FACE_CONFIDENCE2_COUNT_FB; w.confidences=confidence;
        faceResult=t->getFace(t->face,&info,&w);
        faceValid=w.isValid; faceAge=now-w.time;
        if (XR_SUCCEEDED(faceResult) && w.isValid &&
            w.dataSource==XR_FACE_TRACKING_DATA_SOURCE2_VISUAL_FB && std::abs(now-w.time)<150000000) {
            out[2]=1;
            for (int n=0;n<70;n++) out[364+n]=std::isfinite(weights[n])?std::fmax(0.f,std::fmin(1.f,weights[n])):0.f;
        }
    }
    const int status=(out[0]?1:0)|(out[1]?2:0)|(out[2]?4:0)|(out[3]?8:0)|(out[4]?16:0);
    if (status != t->lastSampleStatus) {
        t->lastSampleStatus=status;
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoWearer",
            "Sensor status=%d; body result=%d active=%d ageMs=%lld; face result=%d valid=%d ageMs=%lld",
            status,bodyResult,bodyActive,static_cast<long long>(bodyAge/1000000),
            faceResult,faceValid,static_cast<long long>(faceAge/1000000));
    }
    auto result=env->NewFloatArray(1060); if (result) env->SetFloatArrayRegion(result,0,1060,out); return result;
}
extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_mirror_NativeWearerBridge_destroy(JNIEnv*, jobject, jlong h) { delete reinterpret_cast<Wearer*>(h); }
