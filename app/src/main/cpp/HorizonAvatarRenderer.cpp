#include "HorizonAvatarRenderer.h"
#include "HorizonAvatarInput.h"
#include "HorizonAvatarHands.h"
#include "HorizonMorphUploadCache.h"
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <deque>
#include <mutex>
#include <new>
#include <unordered_map>
#include <vector>

namespace horizon {
namespace {
constexpr int Width = 1920, Height = 1080, MaxBones = 256;
constexpr size_t MorphBudget = 192 * 1024 * 1024;
int32_t entity = 0, loadRequest = 0;
int phase = 0, error = 0;
std::chrono::steady_clock::time_point behaviorWait{};
int behaviorLastResult=-1;
uint32_t lastEntityPendingCount=0;
bool referenceSet = false, sample = false;
Vec3 reference{};
Quat referenceInverse{0, 0, 0, 1};
InputTracking input{};
FacePose face{};
EyesPose eyes{};
HandState hands{};
bool handSkeletonReady[2]{}, handOverrideActive[2]{};
bool packetKindKnown=false, packetIsVideo=false;
HandRetarget handRetarget[2];
int lastInputStatus=-1;
bool physicalFace = false, physicalEyes = false;
float cameraHeight = 1.6f;
std::mutex resourceMutex;
std::deque<Resource> resources;

int32_t (*createEntity)(const CreateInfo*, int32_t*) = nullptr;
int32_t (*destroyEntity)(int32_t) = nullptr;
LoadSettings (*defaultLoad)() = nullptr;
int32_t (*loadUser)(int32_t, uint64_t, int32_t, LoadSettings, int32_t*) = nullptr;
int32_t (*loadUri)(int32_t, const char*, LoadSettings, int32_t*) = nullptr;
int32_t (*getLoad)(int32_t, LoadInfo*) = nullptr;
int32_t (*setTracking)(int32_t, const Provider<InputTracking>*) = nullptr;
uint32_t (*getEntityPendingCount)(int32_t) = nullptr;
int32_t (*setFace)(int32_t, const Provider<FacePose>*) = nullptr;
int32_t (*setEyes)(int32_t, const Provider<EyesPose>*) = nullptr;
int32_t (*setHands)(int32_t, const Provider<HandState>*) = nullptr;
int32_t (*setHandSkeleton)(int32_t, int32_t, const TrackingSkeleton*) = nullptr;
int32_t (*setHandPose)(int32_t, int32_t, const TrackingPose*) = nullptr;
int32_t (*clearHandPose)(int32_t, int32_t) = nullptr;
int32_t (*queryRender)(int32_t, RenderState*) = nullptr;
int32_t (*queryPrimitive)(int32_t, uint32_t, PrimitiveState*) = nullptr;
int32_t (*getSkin)(int32_t, int32_t, Matrix*, uint32_t, bool) = nullptr;
int32_t (*getWeights)(int32_t, int32_t, float*, uint32_t) = nullptr;
int32_t (*enableBehavior)(int32_t, bool) = nullptr;
int32_t (*mainBehavior)(int32_t, const char*) = nullptr;
int32_t (*outputPose)(int32_t, int32_t, const char*) = nullptr;
int32_t (*primitiveCount)(int32_t, uint32_t*) = nullptr;
int32_t (*getPrimitive)(int32_t, uint32_t, Primitive*) = nullptr;
int32_t (*getLods)(int32_t, uint32_t*) = nullptr;
int32_t (*imageCount)(int32_t, uint32_t*) = nullptr;
int32_t (*getImage)(int32_t, uint32_t, Image*) = nullptr;
int32_t (*imageData)(int32_t, uint32_t, uint8_t*, uint32_t) = nullptr;
int32_t (*getTexture)(int32_t, uint32_t, MaterialTexture*) = nullptr;
int32_t (*vertexCount)(int32_t, uint32_t*) = nullptr;
using Attribute = int32_t (*)(int32_t, void*, uint32_t, uint32_t);
Attribute positions = nullptr, normals = nullptr, tangents = nullptr, colors = nullptr, ormt = nullptr,
    uv = nullptr, joints = nullptr, jointWeights = nullptr;
int32_t (*indexData)(int32_t, uint16_t*, uint32_t) = nullptr;
int32_t (*morphCount)(int32_t, uint32_t*) = nullptr;
using MorphAttribute = int32_t (*)(int32_t, uint32_t, Vec3*, uint32_t, uint32_t);
MorphAttribute morphPositions = nullptr, morphNormals = nullptr;
int32_t (*readyResource)(int32_t) = nullptr, (*releaseResource)(int32_t) = nullptr;

void fail(int state, int code = 0) {
    if (phase != state || error != code)
        __android_log_print(ANDROID_LOG_WARN, "AlterEgoAvatar", "Renderer stage=%d code=%d", state, code);
    phase = state; error = code;
}
Quat multiply(Quat a, Quat b) {
    return {a.w*b.x+a.x*b.w+a.y*b.z-a.z*b.y, a.w*b.y-a.x*b.z+a.y*b.w+a.z*b.x,
            a.w*b.z+a.x*b.y-a.y*b.x+a.z*b.w, a.w*b.w-a.x*b.x-a.y*b.y-a.z*b.z};
}
Vec3 rotate(Quat q, Vec3 v) {
    const auto r = multiply(multiply(q, {v.x,v.y,v.z,0}), {-q.x,-q.y,-q.z,q.w});
    return {r.x,r.y,r.z};
}
Transform identity() { return {{0,0,0},{0,0,0,1},{1,1,1}}; }
Matrix matrix(Transform t) {
    const auto q = t.orientation;
    const float x=q.x,y=q.y,z=q.z,w=q.w;
    return {{(1-2*(y*y+z*z))*t.scale.x, 2*(x*y+z*w)*t.scale.x, 2*(x*z-y*w)*t.scale.x, 0,
             2*(x*y-z*w)*t.scale.y, (1-2*(x*x+z*z))*t.scale.y, 2*(y*z+x*w)*t.scale.y, 0,
             2*(x*z+y*w)*t.scale.z, 2*(y*z-x*w)*t.scale.z, (1-2*(x*x+y*y))*t.scale.z, 0,
             t.position.x,t.position.y,t.position.z,1}};
}
bool trackingCallback(InputTracking* out, void*) { *out=input; return input.headActive; }
// The SDK can retain its last controls when a provider returns false. A video
// tracking gap must actively deliver neutral pose controls, without claiming
// that those controls were observed by any physical sensor.
bool faceCallback(FacePose* out, void*) { *out=face; return physicalFace || (packetIsVideo && input.headActive); }
bool eyeCallback(EyesPose* out, void*) { *out=eyes; return physicalEyes || (packetIsVideo && input.headActive); }
bool handCallback(HandState* out, void*) { *out=hands; return hands.leftTracked || hands.rightTracked; }

struct Vertex {
    Vec3 p, n;
    Vec4 color, ormt;
    Vec2 uv;
    Vec4 joints, weights;
    Vec4 tangent;
};
struct Mesh {
    Primitive source{};
    std::vector<Vertex> base, animated;
    std::vector<std::vector<Vec3>> morphP, morphN;
    std::vector<Matrix> skin;
    std::vector<float> weights;
    MorphUploadCache morphUpload;
    GLuint vao=0, vbo=0, indices=0;
    int32_t baseImage=0, ormImage=0, normalImage=0;
    Vec4 factor{1,1,1,1};
    bool hasTangents=false;
};
std::unordered_map<int32_t, Mesh> meshes;
std::unordered_map<int32_t, GLuint> textures;
size_t morphBytes = 0;
EGLDisplay display = EGL_NO_DISPLAY;
EGLContext context = EGL_NO_CONTEXT;
EGLSurface surface = EGL_NO_SURFACE;
GLuint program=0, frameBuffer=0, colorTarget=0, depthTarget=0, skinBuffer=0;
GLuint multisampleBuffer=0,multisampleColor=0,multisampleDepth=0;
int samples=1;
int renderWidth=0,renderHeight=0,renderSamplesRequest=0;
std::vector<uint8_t> pixels;

// Spatial SDK owns a different context. Restore its exact context and surfaces
// after every offscreen operation; never terminate its shared EGL display.
struct GLGuard {
    EGLDisplay oldDisplay = eglGetCurrentDisplay();
    EGLContext oldContext = eglGetCurrentContext();
    EGLSurface oldDraw = eglGetCurrentSurface(EGL_DRAW), oldRead = eglGetCurrentSurface(EGL_READ);
    bool active = false;
    GLGuard() { if (context != EGL_NO_CONTEXT) active=eglMakeCurrent(display,surface,surface,context); }
    ~GLGuard() {
        if (active) eglMakeCurrent(oldDisplay != EGL_NO_DISPLAY ? oldDisplay : display,
                                  oldDraw,oldRead,oldContext);
    }
};
const char* VertexShader = R"GLSL(#version 300 es
precision highp float;
layout(location=0) in vec3 p;
layout(location=1) in vec3 n;
layout(location=2) in vec4 color;
layout(location=3) in vec4 orm;
layout(location=4) in vec2 uv;
layout(location=5) in vec4 joint;
layout(location=6) in vec4 weight;
layout(location=7) in vec4 tangent;
layout(std140) uniform Skin { mat4 bone[256]; };
uniform mat4 model, projection;
uniform bool skinned;
out vec3 normal;
out vec4 worldTangent;
out vec4 vertexColor, vertexOrm;
out vec2 texcoord;
void main() {
    mat4 s=mat4(1.0);
    if (skinned) s=bone[int(joint.x)]*weight.x+bone[int(joint.y)]*weight.y+
                   bone[int(joint.z)]*weight.z+bone[int(joint.w)]*weight.w;
    vec4 world=model*s*vec4(p,1.0);
    gl_Position=projection*world;
    normal=normalize(mat3(model)*mat3(s)*n);
    worldTangent=vec4(normalize(mat3(model)*mat3(s)*tangent.xyz),tangent.w);
    vertexColor=color; vertexOrm=orm; texcoord=uv;
})GLSL";
const char* FragmentShader = R"GLSL(#version 300 es
precision highp float;
in vec3 normal;
in vec4 worldTangent;
in vec4 vertexColor, vertexOrm;
in vec2 texcoord;
uniform sampler2D baseMap, ormMap, normalMap;
uniform bool hasBase, hasOrm, hasNormal;
uniform vec4 baseFactor;
uniform int alphaMode;
out vec4 outputColor;
void main() {
    vec4 base=hasBase?texture(baseMap,texcoord):vertexColor;
    base*=baseFactor;
    float alpha=alphaMode==0?1.0:base.a;
    if(alphaMode==1 && alpha<0.5) discard;
    vec4 orm=hasOrm?texture(ormMap,texcoord):vertexOrm;
    vec3 c=pow(max(base.rgb,vec3(0)),vec3(2.2));
    vec3 N=normalize(normal), L=normalize(vec3(-0.35,0.65,-1.0)), V=vec3(0,0,-1);
    if(hasNormal) {
        vec3 T=worldTangent.xyz-N*dot(worldTangent.xyz,N);
        if(dot(T,T)>0.000001) {
            T=normalize(T);
            vec3 B=normalize(cross(N,T))*worldTangent.w;
            // Meta's Android/ASTC normal texture stores XY in G/A (rrrg),
            // with inverted Y. It is not an ordinary RGB normal texture.
            vec2 xy=texture(normalMap,texcoord).ga;
            xy=vec2(xy.x,1.0-xy.y)*2.0-1.0;
            vec3 detail=vec3(xy,sqrt(max(0.0,1.0-dot(xy,xy))));
            N=normalize(mat3(T,B,N)*detail);
        }
    }
    float rough=clamp(orm.g,0.18,1.0), metallic=clamp(orm.b,0.0,1.0);
    float diffuse=clamp((dot(N,L)+0.2)/1.2,0.0,1.0);
    float highlight=pow(max(dot(N,normalize(L+V)),0.0),mix(96.0,4.0,rough));
    vec3 f0=mix(vec3(0.04),c,metallic);
    vec3 lit=c*(0.46+0.58*diffuse)*mix(0.65,1.0,clamp(orm.r,0.0,1.0))+
             f0*highlight*(1.0-0.6*rough);
    outputColor=vec4(pow(clamp(lit,0.0,1.0),vec3(1.0/2.2)),alpha);
})GLSL";

GLuint shader(GLenum type, const char* source) {
    GLuint id=glCreateShader(type); glShaderSource(id,1,&source,nullptr); glCompileShader(id);
    GLint ok=0; glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if (!ok) { glDeleteShader(id); return 0; }
    return id;
}
bool configureRenderTargets(int width,int height,int requestedSamples) {
    const bool supportedSize=(width==Width && height==Height) ||
        (width==1280 && height==720) || (width==960 && height==540);
    if(!supportedSize || (requestedSamples!=1 && requestedSamples!=2 && requestedSamples!=4))return false;
    if(frameBuffer && renderWidth==width && renderHeight==height &&
        renderSamplesRequest==requestedSamples)return true;
    // Allocate a replacement first. A failed profile change leaves the existing
    // framebuffer and all SDK/model/context resources available for a retry.
    GLuint nextFrame=0,nextColor=0,nextDepth=0,nextMsaa=0,nextMsaaColor=0,nextMsaaDepth=0;
    auto discardNextMsaa=[&] {
        glDeleteFramebuffers(1,&nextMsaa);glDeleteRenderbuffers(1,&nextMsaaColor);glDeleteRenderbuffers(1,&nextMsaaDepth);
        nextMsaa=nextMsaaColor=nextMsaaDepth=0;
    };
    auto discardNext=[&] {
        discardNextMsaa();glDeleteFramebuffers(1,&nextFrame);glDeleteTextures(1,&nextColor);glDeleteRenderbuffers(1,&nextDepth);
        glBindFramebuffer(GL_FRAMEBUFFER,frameBuffer);
    };
    glGenFramebuffers(1,&nextFrame);glBindFramebuffer(GL_FRAMEBUFFER,nextFrame);
    glGenTextures(1,&nextColor);glBindTexture(GL_TEXTURE_2D,nextColor);
    glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);
    glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,nextColor,0);
    glGenRenderbuffers(1,&nextDepth);glBindRenderbuffer(GL_RENDERBUFFER,nextDepth);
    glRenderbufferStorage(GL_RENDERBUFFER,GL_DEPTH_COMPONENT24,width,height);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,nextDepth);
    const GLenum baseStatus=glCheckFramebufferStatus(GL_FRAMEBUFFER),baseError=glGetError();
    if(baseStatus!=GL_FRAMEBUFFER_COMPLETE || baseError!=GL_NO_ERROR) {
        discardNext();while(glGetError()!=GL_NO_ERROR) {}
        return false;
    }
    GLint maximum=0;glGetIntegerv(GL_MAX_SAMPLES,&maximum);
    const int candidate=requestedSamples>=4 && maximum>=4 ? 4 :
        requestedSamples>=2 && maximum>=2 ? 2 : 1;
    int nextSamples=1;
    if(candidate>1) {
        glGenFramebuffers(1,&nextMsaa);glBindFramebuffer(GL_FRAMEBUFFER,nextMsaa);
        glGenRenderbuffers(1,&nextMsaaColor);glBindRenderbuffer(GL_RENDERBUFFER,nextMsaaColor);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER,candidate,GL_RGBA8,width,height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_RENDERBUFFER,nextMsaaColor);
        glGenRenderbuffers(1,&nextMsaaDepth);glBindRenderbuffer(GL_RENDERBUFFER,nextMsaaDepth);
        glRenderbufferStorageMultisample(GL_RENDERBUFFER,candidate,GL_DEPTH_COMPONENT24,width,height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER,GL_DEPTH_ATTACHMENT,GL_RENDERBUFFER,nextMsaaDepth);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)==GL_FRAMEBUFFER_COMPLETE && glGetError()==GL_NO_ERROR)nextSamples=candidate;
        else {
            discardNextMsaa();while(glGetError()!=GL_NO_ERROR) {}
        }
    }
    try { pixels.resize(static_cast<size_t>(width)*height*4); }
    catch(const std::bad_alloc&) { discardNext();return false; }
    glDeleteFramebuffers(1,&frameBuffer);glDeleteTextures(1,&colorTarget);glDeleteRenderbuffers(1,&depthTarget);
    glDeleteFramebuffers(1,&multisampleBuffer);glDeleteRenderbuffers(1,&multisampleColor);glDeleteRenderbuffers(1,&multisampleDepth);
    frameBuffer=nextFrame;colorTarget=nextColor;depthTarget=nextDepth;
    multisampleBuffer=nextMsaa;multisampleColor=nextMsaaColor;multisampleDepth=nextMsaaDepth;
    renderWidth=width;renderHeight=height;renderSamplesRequest=requestedSamples;samples=nextSamples;
    glBindFramebuffer(GL_FRAMEBUFFER,frameBuffer);
    __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar",
        "SDK render quality: %dx%d, requestedSamples=%d, samples=%d, standard materials, LOD0",
        renderWidth,renderHeight,renderSamplesRequest,samples);
    return true;
}
bool ensureGL() {
    if (context != EGL_NO_CONTEXT) return program != 0;
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY || !eglInitialize(display,nullptr,nullptr)) return false;
    const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_NONE};
    EGLConfig config{}; EGLint count=0;
    if (!eglChooseConfig(display,attrs,&config,1,&count) || !count) return false;
    const EGLint ctx[]={EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
    context=eglCreateContext(display,config,EGL_NO_CONTEXT,ctx);
    const EGLint size[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    surface=eglCreatePbufferSurface(display,config,size);
    GLGuard guard;
    if (!guard.active) return false;
    const GLuint vs=shader(GL_VERTEX_SHADER,VertexShader), fs=shader(GL_FRAGMENT_SHADER,FragmentShader);
    if (!vs || !fs) { if(vs)glDeleteShader(vs); if(fs)glDeleteShader(fs); return false; }
    program=glCreateProgram(); glAttachShader(program,vs); glAttachShader(program,fs); glLinkProgram(program);
    glDeleteShader(vs); glDeleteShader(fs); GLint ok=0; glGetProgramiv(program,GL_LINK_STATUS,&ok);
    if (!ok) { glDeleteProgram(program); program=0; return false; }
    glUniformBlockBinding(program,glGetUniformBlockIndex(program,"Skin"),0);
    glGenBuffers(1,&skinBuffer); glBindBuffer(GL_UNIFORM_BUFFER,skinBuffer);
    glBufferData(GL_UNIFORM_BUFFER,MaxBones*sizeof(Matrix),nullptr,GL_DYNAMIC_DRAW);
    return configureRenderTargets(Width,Height,4);
}
void deleteMesh(Mesh& mesh) {
    mesh.morphUpload.reset();
    glDeleteVertexArrays(1,&mesh.vao); glDeleteBuffers(1,&mesh.vbo); glDeleteBuffers(1,&mesh.indices);
}
bool copyImage(int32_t resourceId, uint32_t index) {
    Image image{};
    if (getImage(resourceId,index,&image) || !image.id || !image.width || !image.height ||
        image.width>4096 || image.height>4096 || !image.mips || image.mips>13 ||
        image.bytes>64*1024*1024) return false;
    if (textures.count(image.id)) return true;
    GLenum format=0; int block=0;
    switch (image.format) {
        case 0xe3dd9a1e: format=GL_RGBA8; break;
        case 0xdc2b8f4c: format=0x93b0; block=4; break;
        case 0xbd4fed74: format=0x93b4; block=6; break;
        case 0xa75606e7: format=0x93b7; block=8; break;
        case 0x7dfcb3d0: format=0x93bd; block=12; break;
        default:
            __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Unsupported image format=%u",image.format);
            return false; // Never reinterpret an unsupported texture format.
    }
    std::vector<uint8_t> data(image.bytes);
    if (imageData(resourceId,index,data.data(),image.bytes)) return false;
    GLuint texture=0; glGenTextures(1,&texture); glBindTexture(GL_TEXTURE_2D,texture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,image.mips>1?GL_LINEAR_MIPMAP_LINEAR:GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_REPEAT); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_REPEAT);
    uint32_t w=image.width,h=image.height; size_t offset=0;
    for (uint32_t mip=0;mip<image.mips;++mip) {
        const size_t bytes=block?((w+block-1)/block)*((h+block-1)/block)*16:size_t(w)*h*4;
        if (offset>data.size() || bytes>data.size()-offset) { glDeleteTextures(1,&texture); return false; }
        if (block) glCompressedTexImage2D(GL_TEXTURE_2D,mip,format,w,h,0,bytes,data.data()+offset);
        else glTexImage2D(GL_TEXTURE_2D,mip,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,data.data()+offset);
        offset+=bytes; w=std::max(1u,w/2); h=std::max(1u,h/2);
    }
    if (glGetError()!=GL_NO_ERROR) { glDeleteTextures(1,&texture); return false; }
    textures.emplace(image.id,texture); return true;
}
template<class T> bool attribute(Attribute fn, int32_t id, std::vector<T>& target) {
    return !fn(id,target.data(),uint32_t(target.size()*sizeof(T)),sizeof(T));
}
bool copyMesh(const Primitive& p) {
    if (meshes.count(p.id)) return true;
    uint32_t count=0, morphs=0;
    const int countResult=vertexCount(p.vertices,&count);
    if (meshes.size()>=64 || countResult || !count || count>65536 ||
        !p.indexCount || p.indexCount>600000 || p.jointCount>MaxBones || p.textureCount>32) {
        __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh limits: count=%u result=%d indices=%u joints=%u textures=%u",count,countResult,p.indexCount,p.jointCount,p.textureCount);
        return false;
    }
    Mesh mesh; mesh.source=p; mesh.base.resize(count);
    std::vector<Vec3> pos(count), normal(count,{0,0,1});
    std::vector<Vec4> color(count,{1,1,1,1}), orm(count,{1,.65f,0,0}), weights(count,{1,0,0,0});
    std::vector<Vec4> tangent(count,{1,0,0,1});
    std::vector<Vec2> texcoord(count);
    std::vector<std::array<uint16_t,4>> joint(count);
    const bool hasPositions=attribute(positions,p.vertices,pos),hasNormals=attribute(normals,p.vertices,normal);
    if (!hasPositions || !hasNormals) {
        __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh attributes: positions=%d normals=%d",hasPositions,hasNormals);return false;
    }
    attribute(colors,p.vertices,color); attribute(ormt,p.vertices,orm); attribute(uv,p.vertices,texcoord);
    mesh.hasTangents=attribute(tangents,p.vertices,tangent);
    if (p.jointCount && (!attribute(joints,p.vertices,joint) || !attribute(jointWeights,p.vertices,weights))) {
        __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh joint attributes unavailable");return false;
    }
    for (uint32_t v=0;v<count;++v) {
        const float influences[]={weights[v].x,weights[v].y,weights[v].z,weights[v].w};
        for(int slot=0;slot<4;++slot) {
            if(!std::isfinite(influences[slot]) || influences[slot]<0.f){__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh influence invalid");return false;}
            // Unused influences may contain the SDK's invalid-joint sentinel.
            // Give them a safe index: GLSL still evaluates the zero-weight term.
            if(influences[slot]==0.f)joint[v][slot]=0;
            else if(p.jointCount && joint[v][slot]>=p.jointCount){
                __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Weighted joint index exceeds mesh palette");return false;
            }
        }
        mesh.base[v]={pos[v],normal[v],color[v],orm[v],texcoord[v],
            {float(joint[v][0]),float(joint[v][1]),float(joint[v][2]),float(joint[v][3])},weights[v],tangent[v]};
    }
    std::vector<uint16_t> indices(p.indexCount);
    const int indexResult=indexData(p.id,indices.data(),uint32_t(indices.size()*2));
    if (indexResult || std::any_of(indices.begin(),indices.end(),[count](uint16_t i){return i>=count;})) {
        __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh indices invalid; result=%d",indexResult);return false;
    }
    if (p.morphs && morphCount(p.morphs,&morphs)) {__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh morph count unavailable");return false;}
    const size_t bytes=size_t(morphs)*count*sizeof(Vec3)*2;
    if (morphs>512 || bytes>MorphBudget-morphBytes) {__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Morph budget exceeded; targets=%u vertices=%u",morphs,count);return false;}
    mesh.morphP.resize(morphs); mesh.morphN.resize(morphs); mesh.weights.resize(morphs);
    for (uint32_t m=0;m<morphs;++m) {
        mesh.morphP[m].resize(count); mesh.morphN[m].resize(count);
        const int result=morphPositions(p.morphs,m,mesh.morphP[m].data(),count*sizeof(Vec3),sizeof(Vec3));
        if (result){__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Morph positions unavailable; result=%d",result);return false;}
        morphNormals(p.morphs,m,mesh.morphN[m].data(),count*sizeof(Vec3),sizeof(Vec3));
    }
    for (uint32_t t=0;t<p.textureCount;++t) {
        MaterialTexture texture{}; if (getTexture(p.id,t,&texture)) {__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh material unavailable");return false;}
        if (texture.type==0) { mesh.baseImage=texture.image; mesh.factor=texture.factor; }
        if (texture.type==3) mesh.ormImage=texture.image;
        if (texture.type==1) mesh.normalImage=texture.image;
    }
    mesh.animated=mesh.base; mesh.skin.resize(std::max(1u,p.jointCount),matrix(identity()));
    glGenVertexArrays(1,&mesh.vao); glBindVertexArray(mesh.vao);
    glGenBuffers(1,&mesh.vbo); glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);
    glBufferData(GL_ARRAY_BUFFER,count*sizeof(Vertex),mesh.base.data(),GL_DYNAMIC_DRAW);
    const int sizes[]={3,3,4,4,2,4,4,4};
    const size_t offsets[]={offsetof(Vertex,p),offsetof(Vertex,n),offsetof(Vertex,color),offsetof(Vertex,ormt),
        offsetof(Vertex,uv),offsetof(Vertex,joints),offsetof(Vertex,weights),offsetof(Vertex,tangent)};
    for (GLuint i=0;i<8;++i) { glEnableVertexAttribArray(i); glVertexAttribPointer(i,sizes[i],GL_FLOAT,GL_FALSE,sizeof(Vertex),reinterpret_cast<void*>(offsets[i])); }
    glGenBuffers(1,&mesh.indices); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,mesh.indices);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices.size()*2,indices.data(),GL_STATIC_DRAW);
    const auto glError=glGetError();
    if (glError!=GL_NO_ERROR) {__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh GL error=%u",glError);deleteMesh(mesh); return false; }
    morphBytes+=bytes; meshes.emplace(p.id,std::move(mesh)); return true;
}
bool processResources() {
    if (!ensureGL()) { fail(-2); return false; }
    GLGuard guard; if(!guard.active){fail(-2);return false;}
    for (int processed=0;processed<2;++processed) {
        Resource r{};
        { std::lock_guard<std::mutex> lock(resourceMutex); if(resources.empty()) break; r=resources.front(); resources.pop_front(); }
        if (r.status!=1 && r.status!=3) continue;
        uint32_t images=0, prims=0;
        const int imageResult=imageCount(r.id,&images), primitiveResult=primitiveCount(r.id,&prims);
        bool ok=!imageResult && !primitiveResult && images<=256 && prims<=64;
        int reason=ok?0:100;
        for (uint32_t i=0;ok && i<images;++i) {ok=copyImage(r.id,i);if(!ok)reason=110;}
        for (uint32_t p=0;ok && p<prims;++p) { Primitive prim{};const int result=getPrimitive(r.id,p,&prim);
            uint32_t lods=0;const int lodResult=result?result:getLods(prim.id,&lods);
            // Core assets contain every detail level. Only retain the LOD the
            // entity requests; unused default meshes must not exhaust memory.
            if(!result && !lodResult && lods && !(lods&1u))continue;
            ok=!result && copyMesh(prim);if(!ok){reason=120;__android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Mesh query result=%d",result);} }
        if (ok) {const int result=readyResource(r.id);ok=result==0;if(!ok)reason=130+result;}
        releaseResource(r.id); // CPU buffers have been copied before releasing SDK resources.
        if (!ok) {
            __android_log_print(ANDROID_LOG_WARN,"AlterEgoAvatar","Asset copy stage=%d images=%u meshes=%u queryResults=%d,%d",reason,images,prims,imageResult,primitiveResult);
            fail(-3,reason); return false;
        }
    }
    return true;
}
Filters filters() { Filters f{}; f.lod=1; f.manifestation=1; f.view=2; f.inclusion=-1; f.quality=0; f.loadRigZip=true; return f; }
bool begin() {
    if (entity || !createEntity) return false;
    CreateInfo info{};
    // OvrAvatarHelperExtensions adds these required flags for SDK 40 rigs:
    // EventSystem, BehaviorSystem, UseRtRig, and ConstraintSolver.
    info.features=6|16|64|256|1024|(1<<12)|(1<<13)|(1<<14)|(1<<15);
    info.filters=filters();
    int r=createEntity(&info,&entity);
    if(r || !entity){fail(-1,r);return false;}
    const Provider<InputTracking> tracking{nullptr,trackingCallback};
    const Provider<FacePose> fp{nullptr,faceCallback}; const Provider<EyesPose> ep{nullptr,eyeCallback};
    const Provider<HandState> hp{nullptr,handCallback};
    if ((r=setTracking(entity,&tracking)) || (r=setFace(entity,&fp)) || (r=setEyes(entity,&ep)) || (r=setHands(entity,&hp))) {fail(-1,r);return false;}
    phase=1; error=0; return true;
}
LoadSettings settings() { auto s=defaultLoad(); s.filters=filters(); s.morphLods=1; s.textureBytes=32*1024*1024; return s; }
Transform tracked(const float* p) {
    Transform t=identity(); t.position=rotate(referenceInverse,{p[0]-reference.x,p[1],p[2]-reference.z});
    t.orientation=multiply(referenceInverse,{p[3],p[4],p[5],p[6]}); return t;
}
void readTracking(const std::vector<float>& p, const std::vector<float>& gaze, bool videoInput) {
    input={}; physicalFace=false; physicalEyes=false; face={}; eyes={}; hands={};
    for(auto& rotation:hands.rotations)rotation={0,0,0,1};
    hands.leftScale=hands.rightScale=1;
    if (p.size()!=434 && p.size()!=642 && p.size()!=WearerPacketSize) return;
    if (p[0]!=1 || std::any_of(p.begin(),p.end(),[](float v){return !std::isfinite(v);})) return;
    // Sensor and reconstructed video skeletons have different neutral axes.
    // Never retarget new input through the previous source's bind reference.
    if(!packetKindKnown || packetIsVideo!=videoInput) {
        for(int side=0;side<2;++side) {
            if(handOverrideActive[side])clearHandPose(entity,side);
            handOverrideActive[side]=false;handSkeletonReady[side]=false;
            handRetarget[side]=HandRetarget{};
        }
        packetKindKnown=true;packetIsVideo=videoInput;
    }
    // Video positions already belong to the fixed frontal camera. Recentring
    // on its first turned head would rotate hand positions and erase that turn.
    if(videoInput){reference={0,0,0};referenceInverse={0,0,0,1};referenceSet=true;}
    if(!referenceSet){reference={p[5],0,p[7]}; const auto q=Quat{p[8],p[9],p[10],p[11]};
        const float yaw=std::atan2(2*(q.w*q.y+q.x*q.z),1-2*(q.y*q.y+q.z*q.z));
        referenceInverse={0,-std::sin(yaw*.5f),0,std::cos(yaw*.5f)};referenceSet=true;}
    input.headActive=true; input.head=tracked(p.data()+5); cameraHeight=input.head.position.y;
    // Real optical wrist joints take priority over the body's wrist-twist bones.
    for(int side=0;side<2;++side){
        Transform& wrist=side?input.right:input.left; bool& active=side?input.rightActive:input.leftActive;
        const int body=side?17:12;
        const int at=156+side*104+4;
        if(p[3+side]==1 && p[at]==1){
            float pose[]={p[at+1],p[at+2],p[at+3],0,0,0,1};
            if(p.size()>=642)std::copy_n(p.data()+434+side*104+4,4,pose+3);
            Transform checked{};
            if(handTransform(pose,checked)){wrist=tracked(pose);active=true;}
        }
        if(!active && p[1]==1 && p[12+body*8]==1){wrist=tracked(p.data()+13+body*8);active=true;}
        CanonicalHandTransforms pose{};
        if(!handSkeletonReady[side] && handRetarget[side].initialize(p.data(),p.size(),side)) {
            std::array<TrackingBone,CanonicalHandCount> bones{};
            for(int n=0;n<CanonicalHandCount;++n)bones[n]={n?(n==1?(side?23:19):(side?44:24)+n-2):-1,static_cast<int16_t>(CanonicalParents[n])};
            const TrackingSkeleton skeleton{CanonicalHandCount,{0,0,-1},bones.data(),{CanonicalHandCount,0,CanonicalHands[side],1,1}};
            const int result=setHandSkeleton(entity,side,&skeleton);
            handSkeletonReady[side]=result==0;
            __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar","Hand %d retarget skeleton: result=%d",side,result);
        }
        const bool optical=active && handSkeletonReady[side] && handRetarget[side].pose(p.data(),p.size(),side,pose);
        if(optical) {
            wrist.orientation=handRetarget[side].wristOrientation(wrist.orientation);
            const TrackingPose handPose{CanonicalHandCount,0,pose.data(),1,1};
            handOverrideActive[side]=setHandPose(entity,side,&handPose)==0;
            for(int n=0;n<17;++n) {
                const int bone=ProviderBones[n];
                hands.rotations[side*17+n]=pose[bone].orientation;
                // The tracking provider omits index/middle/ring metacarpals.
                if(n==4 || n==7 || n==10)hands.rotations[side*17+n]=multiply(pose[bone-1].orientation,pose[bone].orientation);
            }
        } else if(handOverrideActive[side]) {clearHandPose(entity,side);handOverrideActive[side]=false;}
        // The custom skeleton/pose supplies retargeted finger rotations. The
        // provider uses the same canonical local rotations and corrected wrists.
        if(side){hands.right=wrist;hands.rightTracked=hands.rightConfident=optical && handOverrideActive[side];}
        else {hands.left=wrist;hands.leftTracked=hands.leftConfident=optical && handOverrideActive[side];}
    }
    input.leftVisible=input.leftActive; input.rightVisible=input.rightActive;
    physicalFace=p[2]==1;
    if(physicalFace || videoInput)face=mapFaceExpressions(p.data()+364,70);
    const bool observedEyes=gaze.size()==3 && gaze[0]==1 && std::isfinite(gaze[1]) && std::isfinite(gaze[2]);
    if(observedEyes || videoInput) {
        const float x=observedEyes?std::clamp(gaze[1],-.8f,.8f):0.f;
        const float y=observedEyes?std::clamp(gaze[2],-.8f,.8f):0.f;
        // Head-relative direction, forward is -Z in the native tracking space.
        const float yaw=-std::atan2(x,1.f),pitch=std::atan2(y,1.f);
        const Quat q=multiply({0,std::sin(yaw*.5f),0,std::cos(yaw*.5f)}, {std::sin(pitch*.5f),0,0,std::cos(pitch*.5f)});
        for(int side=0;side<2;++side){auto& eye=side?eyes.right:eyes.left; eye.valid=true;
            eye.orientation=multiply(input.head.orientation,q);
            const auto offset=rotate(input.head.orientation,{side?.032f:-.032f,0,0});
            eye.position={input.head.position.x+offset.x,input.head.position.y+offset.y,input.head.position.z+offset.z};}
        physicalEyes=observedEyes;
    }
    const int status=(physicalFace?1:0)|(physicalEyes?2:0)|(hands.leftTracked?4:0)|(hands.rightTracked?8:0);
    if(status!=lastInputStatus){lastInputStatus=status;
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar","Live avatar inputs: face=%d eyes=%d leftFingers=%d rightFingers=%d",physicalFace,physicalEyes,hands.leftTracked,hands.rightTracked);}
}
} // namespace

bool bindRenderer(void* library) {
#define BIND(target,name) target=reinterpret_cast<decltype(target)>(dlsym(library,name)); if(!target)return false
    BIND(createEntity,"ovrAvatar2Entity_Create"); BIND(destroyEntity,"ovrAvatar2Entity_Destroy");
    BIND(defaultLoad,"ovrAvatar2Entity_DefaultLoadSettings"); BIND(loadUser,"ovrAvatar2Entity_LoadUserFromGraph");
    BIND(loadUri,"ovrAvatar2Entity_LoadUri"); BIND(getLoad,"ovrAvatar2Asset_GetLoadRequestInfo");
    BIND(setTracking,"ovrAvatar2Input_SetInputTrackingContext"); BIND(setFace,"ovrAvatar2Input_SetFacePoseProvider");
    BIND(getEntityPendingCount,"ovrAvatar2Asset_GetEntityPendingCount");
    BIND(setEyes,"ovrAvatar2Input_SetEyePoseProvider"); BIND(queryRender,"ovrAvatar2Render_QueryRenderState");
    BIND(setHands,"ovrAvatar2Input_SetHandTrackingContext");
    BIND(setHandSkeleton,"ovrAvatar2_SetCustomHandSkeleton"); BIND(setHandPose,"ovrAvatar2_SetCustomHandPose");
    BIND(clearHandPose,"ovrAvatar2_ClearCustomHandPose");
    BIND(queryPrimitive,"ovrAvatar2Render_GetPrimitiveRenderStateByIndex"); BIND(getSkin,"ovrAvatar2Render_GetSkinTransforms");
    BIND(getWeights,"ovrAvatar2Render_GetMorphTargetWeights"); BIND(primitiveCount,"ovrAvatar2Asset_GetPrimitiveCount");
    BIND(enableBehavior,"ovrAvatar2Behavior_SetBehaviorSystemEnabled");
    BIND(mainBehavior,"ovrAvatar2Behavior_SetMainBehavior"); BIND(outputPose,"ovrAvatar2Behavior_SetOutputPose");
    BIND(getPrimitive,"ovrAvatar2Asset_GetPrimitiveByIndex"); BIND(imageCount,"ovrAvatar2Asset_GetImageCount");
    BIND(getLods,"ovrAvatar2Asset_GetLodFlags");
    BIND(getImage,"ovrAvatar2Asset_GetImageByIndex"); BIND(imageData,"ovrAvatar2Asset_GetImageDataByIndex");
    BIND(getTexture,"ovrAvatar2Primitive_GetMaterialTextureByIndex"); BIND(vertexCount,"ovrAvatar2VertexBuffer_GetVertexCount");
    BIND(positions,"ovrAvatar2VertexBuffer_GetPositions"); BIND(normals,"ovrAvatar2VertexBuffer_GetNormals");
    BIND(tangents,"ovrAvatar2VertexBuffer_GetTangents");
    BIND(colors,"ovrAvatar2VertexBuffer_GetColors"); BIND(ormt,"ovrAvatar2VertexBuffer_GetColorsORMT");
    BIND(uv,"ovrAvatar2VertexBuffer_GetTexCoord0"); BIND(joints,"ovrAvatar2VertexBuffer_GetJointIndices");
    BIND(jointWeights,"ovrAvatar2VertexBuffer_GetJointWeights"); BIND(indexData,"ovrAvatar2Primitive_GetIndexData");
    BIND(morphCount,"ovrAvatar2VertexBuffer_GetMorphTargetCount"); BIND(morphPositions,"ovrAvatar2MorphTarget_GetVertexPositions");
    BIND(morphNormals,"ovrAvatar2MorphTarget_GetVertexNormals"); BIND(readyResource,"ovrAvatar2Asset_ResourceReadyToRender");
    BIND(releaseResource,"ovrAvatar2Asset_ReleaseResource");
#undef BIND
    return true;
}
void onResource(const Resource* resource, void*) {
    if(!resource)return;
    std::lock_guard<std::mutex> lock(resourceMutex); resources.push_back(*resource);
}
bool loadPersonal(uint64_t userId) {
    sample=false; if(!userId || !begin())return false;
    const int r=loadUser(entity,userId,2,settings(),&loadRequest);
    if(r || !loadRequest){fail(-1,r);return false;}return true;
}
bool loadSample(const char* path) {
    sample=true; if(!path || !begin())return false;
    input={}; input.headActive=true; input.head=identity(); input.head.position.y=1.6f;
    input.leftActive=input.rightActive=true;input.left=input.right=identity();
    input.left.position={-.25f,1.05f,-.25f};input.right.position={.25f,1.05f,-.25f};
    const int r=loadUri(entity,path,settings(),&loadRequest);
    if(r || !loadRequest){fail(-1,r);return false;}return true;
}
void updateRenderer(float) {
    if(phase<=0)return;
    if(!processResources())return;
    // Completed load requests are removed by the SDK. Do not query their IDs
    // again after the model has become ready.
    if(phase==2)return;
    if(phase==1) {
        LoadInfo info{}; const int r=getLoad(loadRequest,&info);
        if(r){fail(-1,r);return;}
        if(info.state==8 || info.state==9){fail(-1,info.failure);return;}
        if(info.state!=7)return;
        phase=3;behaviorWait=std::chrono::steady_clock::now();behaviorLastResult=-1;
    }
    // Match SDK 40 OvrAvatarEntity_Loading: RtRig dependencies can continue
    // loading after the main request and mesh have completed. Keep processing
    // resources on every poll; their ready/release acknowledgements let native
    // pending assets finish before enabling the behavior and its output pose.
    const uint32_t pending=getEntityPendingCount(entity);
    if(pending!=lastEntityPendingCount) {
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar",
            "Avatar rig asset readiness: pending=%u",pending);
        lastEntityPendingCount=pending;
    }
    if(pending>0) {
        if(std::chrono::steady_clock::now()-behaviorWait>std::chrono::seconds(10))fail(-4,13);
        return;
    }
    RenderState rs{};
    if(!queryRender(entity,&rs) && rs.count && !meshes.empty()) {
        // Mesh completion can precede registration of the asynchronously loaded
        // rig behavior. Never query the retired load-request ID during this wait.
        const char* operation="enable";
        int result=enableBehavior(entity,true);
        if(!result){operation="main";result=mainBehavior(entity,"ovrAvatar2/default_root");}
        if(!result){operation="output";result=outputPose(entity,3,"pose");}
        if(result) {
            if(behaviorLastResult!=result) __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar","Waiting for rig behavior: operation=%s result=%d",operation,result);
            behaviorLastResult=result;
            const auto elapsed=std::chrono::steady_clock::now()-behaviorWait;
            if((result!=7 && result!=13 && result!=17 && result!=22) || elapsed>std::chrono::seconds(10))fail(-4,result);
            return;
        }
        __android_log_print(ANDROID_LOG_INFO,"AlterEgoAvatar","Avatar mesh ready; source=%s, primitives=%u",sample?"sample":"account",rs.count);
        phase=2;
    } else if(std::chrono::steady_clock::now()-behaviorWait>std::chrono::seconds(10))fail(-4,13);
}
int renderStatus(){return phase;}
int renderError(){return error;}
void recenterRenderer(){referenceSet=false;}
void closeRenderer() {
    if(entity && destroyEntity)destroyEntity(entity);entity=0;loadRequest=0;
    {std::lock_guard<std::mutex> lock(resourceMutex);resources.clear();}
    if(context!=EGL_NO_CONTEXT){GLGuard guard;
        if(guard.active){for(auto& entry:meshes)deleteMesh(entry.second);for(auto& entry:textures)glDeleteTextures(1,&entry.second);
            glDeleteBuffers(1,&skinBuffer);glDeleteProgram(program);glDeleteFramebuffers(1,&frameBuffer);
            glDeleteTextures(1,&colorTarget);glDeleteRenderbuffers(1,&depthTarget);}}
    if(context!=EGL_NO_CONTEXT){GLGuard guard;if(guard.active){
        glDeleteFramebuffers(1,&multisampleBuffer);glDeleteRenderbuffers(1,&multisampleColor);glDeleteRenderbuffers(1,&multisampleDepth);}}
    if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
    if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
    surface=EGL_NO_SURFACE;context=EGL_NO_CONTEXT;
    program=frameBuffer=colorTarget=depthTarget=skinBuffer=0;
    multisampleBuffer=multisampleColor=multisampleDepth=0;samples=1;
    renderWidth=renderHeight=renderSamplesRequest=0;
    meshes.clear();textures.clear();pixels.clear();morphBytes=0;phase=error=0;
    input={};face={};eyes={};hands={};physicalFace=physicalEyes=referenceSet=false;cameraHeight=1.6f;
    handSkeletonReady[0]=handSkeletonReady[1]=handOverrideActive[0]=handOverrideActive[1]=false;lastInputStatus=-1;
    lastEntityPendingCount=0;
    packetKindKnown=packetIsVideo=false;
}
bool feedTracking(JNIEnv* env, jfloatArray tracking, jfloatArray gaze, bool videoInput) {
    if(phase!=2)return false;
    std::vector<float> packet, gazeData;
    if(tracking){const auto count=env->GetArrayLength(tracking);if(count!=434 && count!=642 && count!=WearerPacketSize)return false;
        packet.resize(count);env->GetFloatArrayRegion(tracking,0,count,packet.data());}
    if(gaze && env->GetArrayLength(gaze)==3){gazeData.resize(3);env->GetFloatArrayRegion(gaze,0,3,gazeData.data());}
    if(!packet.empty())readTracking(packet,gazeData,videoInput);
    else {
        physicalFace=physicalEyes=false;face={};eyes={};hands={};
        for(int side=0;side<2;++side)if(handOverrideActive[side]){clearHandPose(entity,side);handOverrideActive[side]=false;}
        if(!sample){input={};return false;}
    }
    return input.headActive;
}
// Called after the account owner has updated the SDK for this frame.
bool renderBitmap(JNIEnv* env, jobject bitmap,int requestedSamples) {
    if(phase!=2 || !bitmap)return false;
    AndroidBitmapInfo info{};
    if(AndroidBitmap_getInfo(env,bitmap,&info) || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        !((info.width==Width && info.height==Height) || (info.width==1280 && info.height==720) ||
          (info.width==960 && info.height==540)) || info.stride<info.width*4)return false;
    GLGuard guard;if(!guard.active)return false;
    if(!configureRenderTargets(static_cast<int>(info.width),static_cast<int>(info.height),requestedSamples))return false;
    glBindFramebuffer(GL_FRAMEBUFFER,samples>1?multisampleBuffer:frameBuffer);glViewport(0,0,renderWidth,renderHeight);
    glEnable(GL_DEPTH_TEST);glDepthMask(GL_TRUE);glDisable(GL_CULL_FACE);glDisable(GL_BLEND);
    glClearColor(.059f,.09f,.137f,1);glClear(GL_COLOR_BUFFER_BIT|GL_DEPTH_BUFFER_BIT);glUseProgram(program);
    // Frontal belly-up camera, framing follows wearer height after recentering.
    // Direct passthrough mirrors show head through waist, using the same metre
    // scale as the custom solid-body renderer. Keep video-driven sample framing
    // unchanged: its inference/diagnostic regions use that camera calibration.
    const float halfHeight=packetIsVideo?.43f:.55f,halfWidth=halfHeight*renderWidth/renderHeight;
    const float centerBelowHead=packetIsVideo?.18f:.27f;
    // Horizontal reflection makes left/right movement match a personal mirror.
    Matrix projection{{1/halfWidth,0,0,0,0,1/halfHeight,0,0,0,0,.2f,0,0,-(cameraHeight-centerBelowHead)/halfHeight,0,1}};
    glUniformMatrix4fv(glGetUniformLocation(program,"projection"),1,GL_FALSE,projection.v);
    glUniform1i(glGetUniformLocation(program,"baseMap"),0);glUniform1i(glGetUniformLocation(program,"ormMap"),1);
    glUniform1i(glGetUniformLocation(program,"normalMap"),2);
    glBindBufferBase(GL_UNIFORM_BUFFER,0,skinBuffer);
    RenderState state{};if(queryRender(entity,&state) || state.count>64)return false;
    uint32_t rendered=0;
    for(uint32_t p=0;p<state.count;++p){PrimitiveState ps{};if(queryPrimitive(entity,p,&ps))return false;
        const auto it=meshes.find(ps.primitive);if(it==meshes.end())continue;auto& mesh=it->second;
        if(mesh.source.jointCount && getSkin(entity,ps.instance,mesh.skin.data(),mesh.skin.size()*sizeof(Matrix),false))return false;
        if(ps.morphCount!=mesh.weights.size())return false;
        if(ps.morphCount){if(getWeights(entity,ps.instance,mesh.weights.data(),mesh.weights.size()*sizeof(float)))return false;
            const auto morphState=mesh.morphUpload.inspect(mesh.weights);
            if(morphState==MorphUploadState::Invalid)return false;
            if(morphState==MorphUploadState::Changed){
                mesh.animated=mesh.base;
                for(size_t m=0;m<mesh.weights.size();++m){float w=mesh.weights[m];if(std::abs(w)<.0001f)continue;
                    for(size_t v=0;v<mesh.animated.size();++v){auto& a=mesh.animated[v];const auto d=mesh.morphP[m][v],n=mesh.morphN[m][v];
                        a.p.x+=d.x*w;a.p.y+=d.y*w;a.p.z+=d.z*w;a.n.x+=n.x*w;a.n.y+=n.y*w;a.n.z+=n.z*w;}}
                // Preserve earlier GL failures, and never cache a failed upload.
                if(glGetError()!=GL_NO_ERROR){mesh.morphUpload.reset();return false;}
                glBindBuffer(GL_ARRAY_BUFFER,mesh.vbo);glBufferSubData(GL_ARRAY_BUFFER,0,mesh.animated.size()*sizeof(Vertex),mesh.animated.data());
                if(glGetError()!=GL_NO_ERROR){mesh.morphUpload.reset();return false;}
                if(!mesh.morphUpload.confirmUpload(mesh.weights))return false;
            }}
        glBindBuffer(GL_UNIFORM_BUFFER,skinBuffer);glBufferSubData(GL_UNIFORM_BUFFER,0,mesh.skin.size()*sizeof(Matrix),mesh.skin.data());
        const Matrix model=matrix(mesh.source.jointCount?ps.skinningOrigin:ps.world);
        glUniformMatrix4fv(glGetUniformLocation(program,"model"),1,GL_FALSE,model.v);
        glUniform1i(glGetUniformLocation(program,"skinned"),mesh.source.jointCount>0);
        glUniform4f(glGetUniformLocation(program,"baseFactor"),mesh.factor.x,mesh.factor.y,mesh.factor.z,mesh.factor.w);
        const auto base=textures.find(mesh.baseImage),orm=textures.find(mesh.ormImage);
        const auto normal=textures.find(mesh.normalImage);
        glUniform1i(glGetUniformLocation(program,"hasBase"),base!=textures.end());
        glUniform1i(glGetUniformLocation(program,"hasOrm"),orm!=textures.end());
        glUniform1i(glGetUniformLocation(program,"hasNormal"),mesh.hasTangents && normal!=textures.end());
        glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,base==textures.end()?0:base->second);
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,orm==textures.end()?0:orm->second);
        glActiveTexture(GL_TEXTURE2);glBindTexture(GL_TEXTURE_2D,normal==textures.end()?0:normal->second);
        glUniform1i(glGetUniformLocation(program,"alphaMode"),mesh.source.alpha);
        if(mesh.source.alpha==2){glEnable(GL_BLEND);glBlendFunc(GL_SRC_ALPHA,GL_ONE_MINUS_SRC_ALPHA);}else glDisable(GL_BLEND);
        glBindVertexArray(mesh.vao);glDrawElements(GL_TRIANGLES,mesh.source.indexCount,GL_UNSIGNED_SHORT,nullptr);++rendered;
    }
    if(!rendered || glGetError()!=GL_NO_ERROR)return false;
    if(samples>1) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER,multisampleBuffer);glBindFramebuffer(GL_DRAW_FRAMEBUFFER,frameBuffer);
        glBlitFramebuffer(0,0,renderWidth,renderHeight,0,0,renderWidth,renderHeight,GL_COLOR_BUFFER_BIT,GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER,frameBuffer);
    }
    glReadPixels(0,0,renderWidth,renderHeight,GL_RGBA,GL_UNSIGNED_BYTE,pixels.data());
    if(glGetError()!=GL_NO_ERROR)return false;
    void* destination=nullptr;if(AndroidBitmap_lockPixels(env,bitmap,&destination) || !destination)return false;
    for(int row=0;row<renderHeight;++row)std::memcpy(static_cast<uint8_t*>(destination)+row*info.stride,
        pixels.data()+(renderHeight-1-row)*renderWidth*4,renderWidth*4);
    AndroidBitmap_unlockPixels(env,bitmap);return true;
}
} // namespace horizon
