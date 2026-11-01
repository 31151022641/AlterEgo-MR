#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <cstdint>
#include <string>
#include <algorithm>
#include <cmath>
#include <chrono>
#include "HorizonAvatarRenderer.h"

// Minimal native ABI declarations from the pinned Meta Platform 69 / Avatars 40.0.1
// SDKs. Account IDs and tokens never cross JNI or enter logs, preferences, or LAN.
namespace {
using Request = uint64_t;
using Message = void*;
struct Vec3 { float x, y, z; };
struct AvatarInit {
    uint32_t version[5];
    const char* clientVersion;
    int32_t platform, flags, loggingLevel;
    void* loggingCallback; void* loggingContext;
    void* memAllocCallback; void* memFreeCallback; void* memoryContext;
    void (*requestCallback)(int32_t, int32_t, void*);
    void* fileOpenCallback; void* fileReadCallback; void* fileCloseCallback; void* fileReaderContext;
    void* resourceLoadCallback; void* resourceLoadContext;
    const char* fallbackPath;
    uint32_t shouldCreateWorkerThreads;
    int64_t maxNetworkRequests, maxNetworkSendBytesPerSecond, maxNetworkReceiveBytesPerSecond;
    Vec3 defaultModelColor, clientSpaceRightAxis, clientSpaceUpAxis, clientSpaceForwardAxis;
    const char* clientName;
    uint32_t networkWorkerUpdateFrequency, reserved1;
    void* reserved2; void* reserved3; void* reserved4; void* reserved5; void* reserved6;
    void* loggingViewCallback; void* reserved7; int64_t reserved8;
    void* reserved9; void* reserved10; void* reserved11;
    const char* extraJsonPayload;
};
static_assert(sizeof(AvatarInit) == 344, "Pinned arm64 Avatar SDK ABI changed");

void* platformLib = nullptr;
void* avatarLib = nullptr;
Request (*initialize)(const char*, jobject, JNIEnv*) = nullptr;
Message (*popMessage)() = nullptr;
void (*freeMessage)(Message) = nullptr;
Request (*requestId)(Message) = nullptr;
bool (*isError)(Message) = nullptr;
void* (*getError)(Message) = nullptr;
int (*errorCode)(void*) = nullptr;
void* (*getInitialize)(Message) = nullptr;
int (*initializeResult)(void*) = nullptr;
Request (*entitlement)() = nullptr;
Request (*loggedUser)() = nullptr;
Request (*accessToken)() = nullptr;
void* (*getUser)(Message) = nullptr;
uint64_t (*getUserId)(void*) = nullptr;
const char* (*getString)(Message) = nullptr;
int32_t (*avatarInitialize)(const AvatarInit*) = nullptr;
int32_t (*avatarShutdown)() = nullptr;
int32_t (*avatarUpdate)(float) = nullptr;
int32_t (*avatarToken)(const char*, int32_t) = nullptr;
int32_t (*avatarHas)(uint64_t, int32_t, int32_t*, void*) = nullptr;
int32_t (*avatarBool)(int32_t, bool*) = nullptr;
bool platformInitialized = false, avatarInitialized = false;
std::string corePath;
bool sampleMode = false;
Request pending = 0;
uint64_t userId = 0;
std::atomic<int32_t> avatarRequest{0};
std::atomic<int> state{0}, code{0};
std::chrono::steady_clock::time_point lastVideoCostLog{};

void status(int value, int error = 0) {
    code.store(error);
    state.store(value);
    __android_log_print(ANDROID_LOG_INFO, "AlterEgoHorizon", "Account stage=%d code=%d", value, error);
}
template<typename T> bool symbol(void* lib, T& fn, const char* name) {
    fn = reinterpret_cast<T>(dlsym(lib, name));
    return fn != nullptr;
}
bool platformSymbols() {
    if (!platformLib) platformLib = dlopen("libovrplatformloader.so", RTLD_NOW | RTLD_LOCAL);
    if (!platformLib) return false;
    return symbol(platformLib, initialize, "ovr_PlatformInitializeAndroidAsynchronous") &&
        symbol(platformLib, popMessage, "ovr_PopMessage") &&
        symbol(platformLib, freeMessage, "ovr_FreeMessage") &&
        symbol(platformLib, requestId, "ovr_Message_GetRequestID") &&
        symbol(platformLib, isError, "ovr_Message_IsError") &&
        symbol(platformLib, getError, "ovr_Message_GetError") &&
        symbol(platformLib, errorCode, "ovr_Error_GetCode") &&
        symbol(platformLib, getInitialize, "ovr_Message_GetPlatformInitialize") &&
        symbol(platformLib, initializeResult, "ovr_PlatformInitialize_GetResult") &&
        symbol(platformLib, entitlement, "ovr_Entitlement_GetIsViewerEntitled") &&
        symbol(platformLib, loggedUser, "ovr_User_GetLoggedInUser") &&
        symbol(platformLib, accessToken, "ovr_User_GetAccessToken") &&
        symbol(platformLib, getUser, "ovr_Message_GetUser") &&
        symbol(platformLib, getUserId, "ovr_User_GetID") &&
        symbol(platformLib, getString, "ovr_Message_GetString");
}
void avatarResponse(int32_t id, int32_t result, void*) {
    if (id != avatarRequest.load() || state.load() != 5) return;
    bool present = false;
    if (result != 0) status(-7, result);
    else if (const int r = avatarBool(id, &present); r != 0) status(-7, r);
    else status(present ? 6 : -8);
}
bool avatarSymbols() {
    if (!avatarLib) avatarLib = dlopen("libovravatar2.so", RTLD_NOW | RTLD_LOCAL);
    if (!avatarLib) return false;
    return symbol(avatarLib, avatarInitialize, "ovrAvatar2_Initialize") &&
        symbol(avatarLib, avatarShutdown, "ovrAvatar2_Shutdown") &&
        symbol(avatarLib, avatarUpdate, "ovrAvatar2_Update") &&
        symbol(avatarLib, avatarToken, "ovrAvatar2_UpdateAccessTokenForGraph") &&
        symbol(avatarLib, avatarHas, "ovrAvatar2_GraphHasAvatar") &&
        symbol(avatarLib, avatarBool, "ovrAvatar2_GetRequestBool") && horizon::bindRenderer(avatarLib);
}
bool initializeAvatar(bool pro) {
    if (!avatarSymbols()) { status(-5); return false; }
    if (!avatarInitialized) {
        AvatarInit info{};
        const uint32_t version[] = {40, 0, 0, 24, 4};
        for (int n = 0; n < 5; ++n) info.version[n] = version[n];
        info.clientVersion = "AlterEgo MR 1.0";
        info.platform = pro ? 4 : 5;
        // Match the pinned SDK 40 manager: its behavior system is required for
        // current avatar rigs. Keep the same excluded experimental/cache flags.
        constexpr int systemFlags = ((1 << 29) - 1) & ~((1 << 26) | (1 << 18) | (1 << 12));
        info.flags = (2 | 8 | (systemFlags & ~511)) &
                     ~((1 << 5) | (1 << 17) | (1 << 16) | (1 << 28));
        info.loggingLevel = 9; // SDK network logs must not expose account credentials.
        info.requestCallback = avatarResponse;
        info.resourceLoadCallback = reinterpret_cast<void*>(horizon::onResource);
        info.fallbackPath = corePath.c_str();
        info.shouldCreateWorkerThreads = 1;
        info.maxNetworkRequests = info.maxNetworkSendBytesPerSecond = info.maxNetworkReceiveBytesPerSecond = -1;
        info.defaultModelColor = {.12f, .62f, 1.f};
        info.clientSpaceRightAxis = {1.f, 0.f, 0.f};
        info.clientSpaceUpAxis = {0.f, 1.f, 0.f};
        // The renderer and OpenXR input both use native right-handed -Z forward.
        info.clientSpaceForwardAxis = {0.f, 0.f, -1.f};
        info.clientName = "AlterEgo MR";
        info.extraJsonPayload = "{}";
        info.networkWorkerUpdateFrequency = 256;
        const int r = avatarInitialize(&info);
        if (r != 0) { status(-5, r); return false; }
        avatarInitialized = true;
    }
    return true;
}
void startAvatar(const char* token, bool pro) {
    if (!initializeAvatar(pro)) return;
    // Platform SDK IDs/tokens use the Oculus graph (2), also the default in
    // Meta's current OvrAvatarEntitlement / OvrAvatarEntity wrappers.
    const int r = avatarToken(token, 2);
    if (r != 0) { status(-6, r); return; }
    int32_t id = 0;
    status(5);
    const int result = avatarHas(userId, 2, &id, nullptr);
    avatarRequest.store(id);
    if (result != 0 || id == 0) status(-7, result);
}
void resetAvatar() {
    avatarRequest.store(0);
    state.store(0);
    horizon::closeRenderer();
    if (avatarInitialized) avatarShutdown();
    avatarInitialized = false;
    userId = 0;
    pending = 0;
    corePath.clear();
    sampleMode = false;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_start(JNIEnv* env, jobject, jobject activity, jstring appId, jstring core) {
    resetAvatar();
    const char* path = env->GetStringUTFChars(core, nullptr);
    if (!path) { status(-5); return; }
    corePath = path; env->ReleaseStringUTFChars(core, path);
    if (!platformSymbols()) { status(-1); return; }
    if (platformInitialized) { status(2); pending = entitlement(); }
    else {
        const char* id = env->GetStringUTFChars(appId, nullptr);
        if (!id) { status(-2); return; }
        status(1);
        pending = initialize(id, activity, env);
        env->ReleaseStringUTFChars(appId, id);
    }
    if (pending == 0) status(-state.load());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_poll(JNIEnv* env, jobject, jboolean pro) {
    if (popMessage) for (int n = 0; n < 64; ++n) {
        Message msg = popMessage();
        if (!msg) break;
        if (pending != 0 && requestId(msg) == pending) {
            const int stage = state.load();
            pending = 0;
            if (isError(msg)) status(-stage, errorCode(getError(msg)));
            else if (stage == 1) {
                auto initResult = getInitialize(msg);
                const int result = initResult ? initializeResult(initResult) : -1;
                if (result != 0) status(-1, result);
                else { platformInitialized = true; status(2); pending = entitlement(); }
            } else if (stage == 2) { status(3); pending = loggedUser(); }
            else if (stage == 3) {
                auto user = getUser(msg);
                userId = user ? getUserId(user) : 0;
                if (!userId) status(-3);
                else { status(4); pending = accessToken(); }
            } else if (stage == 4) {
                const char* token = getString(msg);
                if (!token || !*token) status(-4);
                else startAvatar(token, pro == JNI_TRUE);
            }
            if (state.load() > 0 && state.load() < 5 && pending == 0) status(-state.load());
        }
        freeMessage(msg);
    }
    if (avatarInitialized) {
        avatarUpdate(.1f);
        if (state.load() == 6) {
            if (horizon::loadPersonal(userId)) status(7);
            else status(-10, horizon::renderError());
        }
        horizon::updateRenderer(.1f);
        if (state.load() == 7 || state.load() == 20) {
            if (horizon::renderStatus() == 2) status(sampleMode ? 21 : 8);
            else if (horizon::renderStatus() < 0) status(-10, horizon::renderError());
        }
    }
    const jint values[] = {state.load(), code.load()};
    jintArray result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_close(JNIEnv*, jobject) { resetAvatar(); }

extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_sample(JNIEnv* env, jobject, jstring core, jstring sample, jboolean pro) {
    resetAvatar();
    const char* path=env->GetStringUTFChars(core,nullptr);
    if(!path){status(-5);return;}
    corePath=path;env->ReleaseStringUTFChars(core,path);
    if(!initializeAvatar(pro==JNI_TRUE))return;
    const char* source=env->GetStringUTFChars(sample,nullptr);
    if(!source){status(-10);return;}
    sampleMode=true;
    const bool started=horizon::loadSample(source);
    env->ReleaseStringUTFChars(sample,source);
    status(started?20:-10,horizon::renderError());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_render(JNIEnv* env,jobject,jobject bitmap,jfloatArray tracking,jfloatArray gaze,jboolean videoInput,jfloat videoDeltaSeconds,jint requestedSamples) {
    if(!avatarInitialized || (state.load()!=8 && state.load()!=21))return JNI_FALSE;
    const auto started=std::chrono::steady_clock::now();
    // Feed this frame's input before animation, then draw the resulting pose.
    // All calls remain on the main thread, using the existing sensor session.
    if (!horizon::feedTracking(env,tracking,gaze,videoInput==JNI_TRUE)) return JNI_FALSE;
    const float delta = videoInput==JNI_TRUE && std::isfinite(videoDeltaSeconds)
        ? std::clamp(static_cast<float>(videoDeltaSeconds),1.f/120.f,.25f) : 1.f/15.f;
    // Camera observations can arrive more slowly than the preview poll. Advance
    // by actual elapsed time, in bounded simulation steps, and draw pixels once.
    // Raw immersive input retains its existing single-step behavior.
    float remaining=delta;
    while(remaining>0.f) {
        const float step=std::min(remaining,1.f/15.f);
        if(avatarUpdate(step)!=0)return JNI_FALSE;
        remaining-=step;
    }
    horizon::updateRenderer(delta);
    const auto animated=std::chrono::steady_clock::now();
    const bool drawn=horizon::renderBitmap(env,bitmap,requestedSamples);
    const auto finished=std::chrono::steady_clock::now();
    if(videoInput==JNI_TRUE && (lastVideoCostLog.time_since_epoch().count()==0 ||
        finished-lastVideoCostLog>=std::chrono::seconds(5))) {
        lastVideoCostLog=finished;
        const auto animationMs=std::chrono::duration_cast<std::chrono::milliseconds>(animated-started).count();
        const auto pixelsMs=std::chrono::duration_cast<std::chrono::milliseconds>(finished-animated).count();
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoSample2D",
            "SDK video cost: animationMs=%lld pixelsMs=%lld rendered=%d requestedSamples=%d",
            static_cast<long long>(animationMs),static_cast<long long>(pixelsMs),drawn,requestedSamples);
    }
    return drawn?JNI_TRUE:JNI_FALSE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_oculus_camerademo_mirror_NativeHorizonBridge_recenter(JNIEnv*,jobject) { horizon::recenterRenderer(); }
