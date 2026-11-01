#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdint>
#include <limits>

namespace {
bool fits(jlong capacity,int row,int pixel,int left,int top,uint32_t width,uint32_t height) {
    if(capacity<=0 || row<=0 || pixel<=0 || left<0 || top<0 || !width || !height) return false;
    const uint64_t last=uint64_t(top+height-1)*row+uint64_t(left+width-1)*pixel;
    return last<uint64_t(capacity) && uint64_t(left+width-1)*pixel<uint64_t(row);
}
uint8_t channel(int value) { return uint8_t(std::clamp(value,0,255)); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_oculus_camerademo_mirror_NativeAvatarPixels_convert(JNIEnv* env,jobject,jobject output,
    jobject yBuffer,jobject uBuffer,jobject vBuffer,jint yRow,jint yPixel,jint uRow,jint uPixel,
    jint vRow,jint vPixel,jint left,jint top,jint colorMode) {
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,output,&info) || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        info.width>2560 || info.height>1600 || left<0 || top<0 || left>4096 || top>4096 || colorMode<0 || colorMode>3) return JNI_FALSE;
    auto y=static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto u=static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto v=static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    const auto chromaWidth=(left+info.width-1)/2-left/2+1;
    const auto chromaHeight=(top+info.height-1)/2-top/2+1;
    if(!y || !u || !v || !fits(env->GetDirectBufferCapacity(yBuffer),yRow,yPixel,left,top,info.width,info.height) ||
        !fits(env->GetDirectBufferCapacity(uBuffer),uRow,uPixel,left/2,top/2,chromaWidth,chromaHeight) ||
        !fits(env->GetDirectBufferCapacity(vBuffer),vRow,vPixel,left/2,top/2,chromaWidth,chromaHeight)) return JNI_FALSE;
    void* pixels{}; if(AndroidBitmap_lockPixels(env,output,&pixels)) return JNI_FALSE;
    const bool full=(colorMode&1)!=0,hd=(colorMode&2)!=0;
    const int red=full?(hd?403:359):(hd?459:409);
    const int greenU=full?(hd?48:88):(hd?55:100),greenV=full?(hd?120:183):(hd?136:208);
    const int blue=full?(hd?475:454):(hd?541:516);
    for(uint32_t row=0;row<info.height;++row) {
        auto destination=static_cast<uint8_t*>(pixels)+row*info.stride;
        const auto ys=y+(top+row)*yRow+left*yPixel;
        const auto us=u+((top+row)/2)*uRow;
        const auto vs=v+((top+row)/2)*vRow;
        for(uint32_t col=0;col<info.width;++col) {
            const int luma=full?int(ys[col*yPixel])*256:std::max(0,int(ys[col*yPixel])-16)*298;
            const int cb=int(us[((left+col)/2)*uPixel])-128;
            const int cr=int(vs[((left+col)/2)*vPixel])-128;
            *destination++=channel((luma+red*cr+128)>>8);
            *destination++=channel((luma-greenU*cb-greenV*cr+128)>>8);
            *destination++=channel((luma+blue*cb+128)>>8);
            *destination++=255;
        }
    }
    AndroidBitmap_unlockPixels(env,output); return JNI_TRUE;
}
