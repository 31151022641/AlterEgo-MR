#pragma once
#include "HorizonAvatarTypes.h"
#include <jni.h>

namespace horizon {
bool bindRenderer(void* library);
void onResource(const Resource*, void*);
bool loadPersonal(uint64_t userId);
bool loadSample(const char* path);
void updateRenderer(float deltaSeconds);
// 0 off, 1 loading, 2 ready, negative: SDK / mesh / GL failure.
int renderStatus();
int renderError();
void closeRenderer();
bool feedTracking(JNIEnv*, jfloatArray tracking, jfloatArray gaze, bool videoInput = false);
bool renderBitmap(JNIEnv*, jobject bitmap, int requestedSamples = 4);
void recenterRenderer();
}
