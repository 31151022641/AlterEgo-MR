#include <jni.h>
#include <android/log.h>
#include <time.h>
#include <cmath>
#include <cstdint>
#include <memory>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

namespace {
constexpr const char* TAG = "AlterEgoEyeGaze";
constexpr XrDuration MAX_SAMPLE_AGE_NS = 150000000;

struct EyeTracker {
    XrInstance instance = XR_NULL_HANDLE;
    XrEyeTrackerFB eyes = XR_NULL_HANDLE;
    XrSpace viewSpace = XR_NULL_HANDLE;
    PFN_xrGetEyeGazesFB getGazes = nullptr;
    PFN_xrDestroyEyeTrackerFB destroyEyes = nullptr;
    PFN_xrDestroySpace destroySpace = nullptr;
    PFN_xrConvertTimespecTimeToTimeKHR convertTime = nullptr;
    int lastStatus = -1;

    ~EyeTracker() {
        if (eyes != XR_NULL_HANDLE && destroyEyes) destroyEyes(eyes);
        if (viewSpace != XR_NULL_HANDLE && destroySpace) destroySpace(viewSpace);
    }
};

template <typename Function>
bool load(XrInstance instance, PFN_xrGetInstanceProcAddr loader, const char* name, Function& function) {
    const XrResult result = loader(instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&function));
    if (XR_FAILED(result) || function == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s unavailable (%d)", name, result);
        return false;
    }
    return true;
}

bool succeeded(XrResult result, const char* operation) {
    if (XR_FAILED(result)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s failed (%d)", operation, result);
        return false;
    }
    return true;
}

// Rotate the OpenXR canonical forward vector (0, 0, -1) by a unit quaternion.
XrVector3f eyeForward(const XrQuaternionf& input) {
    const float norm = input.x * input.x + input.y * input.y + input.z * input.z + input.w * input.w;
    if (!std::isfinite(norm) || norm < 0.00001f) return {NAN, NAN, NAN};
    const float inv = 1.f / std::sqrt(norm);
    const float x = input.x * inv, y = input.y * inv, z = input.z * inv, w = input.w * inv;
    return {-2.f * (x * z + w * y), 2.f * (w * x - y * z), 2.f * (x * x + y * y) - 1.f};
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oculus_camerademo_eyetracking_NativeEyeGazeBridge_create(
    JNIEnv*, jobject, jlong instanceHandle, jlong sessionHandle, jlong procAddrHandle) {
    if (instanceHandle == 0 || sessionHandle == 0 || procAddrHandle == 0) return 0;
    auto tracker = std::make_unique<EyeTracker>();
    tracker->instance = reinterpret_cast<XrInstance>(static_cast<intptr_t>(instanceHandle));
    const auto session = reinterpret_cast<XrSession>(static_cast<intptr_t>(sessionHandle));
    const auto loader = reinterpret_cast<PFN_xrGetInstanceProcAddr>(static_cast<intptr_t>(procAddrHandle));
    PFN_xrGetSystem getSystem = nullptr;
    PFN_xrGetSystemProperties getProperties = nullptr;
    PFN_xrCreateEyeTrackerFB createEyes = nullptr;
    PFN_xrCreateReferenceSpace createSpace = nullptr;
    if (!load(tracker->instance, loader, "xrGetSystem", getSystem) ||
        !load(tracker->instance, loader, "xrGetSystemProperties", getProperties) ||
        !load(tracker->instance, loader, "xrCreateEyeTrackerFB", createEyes) ||
        !load(tracker->instance, loader, "xrGetEyeGazesFB", tracker->getGazes) ||
        !load(tracker->instance, loader, "xrDestroyEyeTrackerFB", tracker->destroyEyes) ||
        !load(tracker->instance, loader, "xrCreateReferenceSpace", createSpace) ||
        !load(tracker->instance, loader, "xrDestroySpace", tracker->destroySpace) ||
        !load(tracker->instance, loader, "xrConvertTimespecTimeToTimeKHR", tracker->convertTime)) return 0;

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    if (!succeeded(getSystem(tracker->instance, &systemInfo, &systemId), "xrGetSystem")) return 0;
    XrSystemEyeTrackingPropertiesFB eyeProperties{};
    eyeProperties.type = XR_TYPE_SYSTEM_EYE_TRACKING_PROPERTIES_FB;
    XrSystemProperties properties{};
    properties.type = XR_TYPE_SYSTEM_PROPERTIES;
    properties.next = &eyeProperties;
    if (!succeeded(getProperties(tracker->instance, systemId, &properties), "xrGetSystemProperties") ||
        !eyeProperties.supportsEyeTracking) return 0;

    XrEyeTrackerCreateInfoFB createInfo{};
    createInfo.type = XR_TYPE_EYE_TRACKER_CREATE_INFO_FB;
    if (!succeeded(createEyes(session, &createInfo, &tracker->eyes), "xrCreateEyeTrackerFB")) return 0;
    XrReferenceSpaceCreateInfo spaceInfo{};
    spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.f;
    if (!succeeded(createSpace(session, &spaceInfo, &tracker->viewSpace), "xrCreateReferenceSpace")) return 0;
    __android_log_print(ANDROID_LOG_INFO, TAG, "OpenXR eye tracker initialized; runtime reports eye tracking support");
    return reinterpret_cast<jlong>(tracker.release());
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_oculus_camerademo_eyetracking_NativeEyeGazeBridge_sample(JNIEnv* env, jobject, jlong handle) {
    const auto tracker = reinterpret_cast<EyeTracker*>(handle);
    if (tracker == nullptr) return nullptr;
    timespec monotonicNow{};
    if (clock_gettime(CLOCK_MONOTONIC, &monotonicNow) != 0) return nullptr;
    XrTime now = 0;
    if (XR_FAILED(tracker->convertTime(tracker->instance, &monotonicNow, &now))) return nullptr;
    XrEyeGazesInfoFB info{};
    info.type = XR_TYPE_EYE_GAZES_INFO_FB;
    info.baseSpace = tracker->viewSpace;
    info.time = now;
    XrEyeGazesFB gazes{};
    gazes.type = XR_TYPE_EYE_GAZES_FB;
    const auto result=tracker->getGazes(tracker->eyes, &info, &gazes);
    const bool fresh=XR_SUCCEEDED(result) && gazes.time>0 && std::abs(now-gazes.time)<=MAX_SAMPLE_AGE_NS;
    const int status=fresh?((gazes.gaze[0].isValid?1:0)|(gazes.gaze[1].isValid?2:0)):0;
    if(status!=tracker->lastStatus){tracker->lastStatus=status;
        __android_log_print(ANDROID_LOG_INFO,TAG,"Eye sensor status=%d result=%d fresh=%d",status,result,fresh);}
    if(!fresh)return nullptr;

    XrVector3f origin{}, direction{};
    float confidenceSum = 0.f;
    int count = 0;
    for (const auto& eye : gazes.gaze) {
        if (!eye.isValid || !std::isfinite(eye.gazeConfidence) || eye.gazeConfidence <= 0.f) continue;
        const XrVector3f forward = eyeForward(eye.gazePose.orientation);
        const auto& position = eye.gazePose.position;
        if (!std::isfinite(forward.x) || !std::isfinite(forward.y) || !std::isfinite(forward.z) ||
            !std::isfinite(position.x) || !std::isfinite(position.y) || !std::isfinite(position.z)) continue;
        origin.x += position.x; origin.y += position.y; origin.z += position.z;
        direction.x += forward.x; direction.y += forward.y; direction.z += forward.z;
        confidenceSum += eye.gazeConfidence;
        ++count;
    }
    if (count == 0) return nullptr;
    const float length = std::sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z);
    if (!std::isfinite(length) || length < 0.00001f) return nullptr;
    const jfloat output[] = {
        origin.x / count, origin.y / count, origin.z / count,
        direction.x / length, direction.y / length, direction.z / length,
        confidenceSum / count,
    };
    const auto array = env->NewFloatArray(7);
    if (array) env->SetFloatArrayRegion(array, 0, 7, output);
    return array;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_eyetracking_NativeEyeGazeBridge_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<EyeTracker*>(handle);
}
