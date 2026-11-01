// AlterEgo MR: Windows 11 software-camera source. No kernel driver or external broadcast app.
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mferror.h>
#include <mfvirtualcamera.h>
#include <mfreadwrite.h>
#include <ks.h>
#include <ksmedia.h>
#include <ksproxy.h>
#include <sddl.h>
#include <shlobj.h>
#include <wrl.h>
#include <atomic>
#include <array>
#include <mutex>
#include <condition_variable>
#include <thread>
#include <deque>
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>
#include <cstring>
using namespace Microsoft::WRL;
#define CHECK(x) do { HRESULT taskResult=(x); if (FAILED(taskResult)) return taskResult; } while(0)
constexpr DWORD WIDTH=1920, HEIGHT=1080, BYTES=WIDTH*HEIGHT*4, HEADER=64;
constexpr LONGLONG FRAME_TIME=10000000/30;
const CLSID CameraCLSID={0x0984a37e,0x84cf,0x4f9f,{0x83,0x41,0x37,0x6b,0x12,0x8e,0x68,0xbc}};
const wchar_t* CameraID=L"{0984A37E-84CF-4F9F-8341-376B128E68BC}";
// Set on IMFVirtualCamera before Start; FrameServer copies it into IMFActivate.
const GUID AvatarFramePathAttribute={0x95bd8717,0x78fd,0x4b27,{0xb8,0x0b,0xb4,0x53,0xbd,0xa7,0x46,0x61}};
const GUID AvatarFrameTokenAttribute={0x95bd8718,0x78fd,0x4b27,{0xb8,0x0b,0xb4,0x53,0xbd,0xa7,0x46,0x61}};
using FrameToken=std::array<BYTE,32>;
HMODULE module{};
ComPtr<IMFVirtualCamera> virtualCamera;
std::mutex cameraLock;
bool cameraMfStarted=false;
HMODULE cameraLibrary{};
std::wstring activeFramePath;

std::wstring moduleFolder() {
    wchar_t path[32768]{}; GetModuleFileNameW(module,path,32768);
    std::wstring p(path); auto n=p.find_last_of(L"\\/"); return p.substr(0,n+1);
}
std::wstring programDataFolder() {
    // Known-folder lookup must not trust a process environment override in the service.
    auto library=LoadLibraryExW(L"shell32.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!library) return L"";
    auto getFolder=reinterpret_cast<decltype(&SHGetKnownFolderPath)>(GetProcAddress(library,"SHGetKnownFolderPath"));
    constexpr GUID programData={0x62ab5d82,0xfdc1,0x4dc3,{0xa9,0xdd,0x07,0x0d,0x1d,0x49,0x5d,0x97}};
    PWSTR folder{}; std::wstring result;
    if(getFolder && SUCCEEDED(getFolder(programData,0,nullptr,&folder))) result=folder;
    CoTaskMemFree(folder); FreeLibrary(library); return result;
}
std::wstring currentUserSid() {
    HANDLE token{};
    if(!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)) return L"";
    DWORD size{}; GetTokenInformation(token,TokenUser,nullptr,0,&size);
    std::vector<BYTE> bytes(size); std::wstring result;
    if(size && GetTokenInformation(token,TokenUser,bytes.data(),size,&size)) {
        PWSTR text{};
        if(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(bytes.data())->User.Sid,&text)) { result=text; LocalFree(text); }
    }
    CloseHandle(token); return result;
}
bool privateFramePath(const std::wstring& path,bool requireCurrentUser) {
    auto root=programDataFolder(); if(root.empty()) return false;
    auto prefix=root+L"\\AlterEgoMR\\";
    if(path.size()<=prefix.size() || _wcsnicmp(path.c_str(),prefix.c_str(),prefix.size())!=0) return false;
    auto sidEnd=path.find(L'\\',prefix.size());
    if(sidEnd==std::wstring::npos || _wcsicmp(path.c_str()+sidEnd,L"\\frames.dat")!=0) return false;
    auto sidText=path.substr(prefix.size(),sidEnd-prefix.size());
    PSID sid{}; if(!ConvertStringSidToSidW(sidText.c_str(),&sid)) return false;
    PWSTR canonical{}; bool valid=IsValidSid(sid) && ConvertSidToStringSidW(sid,&canonical);
    valid=valid && _wcsicmp(canonical,sidText.c_str())==0;
    LocalFree(canonical); LocalFree(sid);
    if(!valid || (requireCurrentUser && _wcsicmp(sidText.c_str(),currentUserSid().c_str())!=0)) return false;
    // User-controlled junctions cannot redirect the service outside the private channel.
    for(const auto& directory:{root+L"\\AlterEgoMR",path.substr(0,sidEnd)}) {
        auto attrs=GetFileAttributesW(directory.c_str());
        if(attrs!=INVALID_FILE_ATTRIBUTES && (attrs&FILE_ATTRIBUTE_REPARSE_POINT)) return false;
    }
    return true;
}
HRESULT createFrameToken(const std::wstring& path,FrameToken& token) {
    auto library=LoadLibraryExW(L"advapi32.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!library) return HRESULT_FROM_WIN32(GetLastError());
    using RandomFunction=BOOLEAN(WINAPI*)(PVOID,ULONG);
    auto random=reinterpret_cast<RandomFunction>(GetProcAddress(library,"SystemFunction036"));
    bool generated=random && random(token.data(),static_cast<ULONG>(token.size()));
    FreeLibrary(library); if(!generated) return E_FAIL;
    // This open happens as the receiving user, never as the LocalService source.
    // A camera activation cannot obtain a victim's channel token by naming their SID.
    auto file=CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
        nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
    if(file==INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
    BY_HANDLE_FILE_INFORMATION info{}; LARGE_INTEGER size{},offset{}; offset.QuadPart=24; DWORD written{};
    HRESULT result=E_ACCESSDENIED;
    if(GetFileInformationByHandle(file,&info) && !(info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) &&
        GetFileSizeEx(file,&size) && size.QuadPart==HEADER+BYTES) {
        if(!SetFilePointerEx(file,offset,nullptr,FILE_BEGIN) ||
            !WriteFile(file,token.data(),static_cast<DWORD>(token.size()),&written,nullptr)) result=HRESULT_FROM_WIN32(GetLastError());
        else result=written==token.size()?S_OK:E_FAIL;
    }
    CloseHandle(file); return result;
}

class FrameReader {
    HANDLE file=INVALID_HANDLE_VALUE, mapping{};
    const BYTE* data{};
    std::vector<BYTE> cached=std::vector<BYTE>(BYTES);
    ULONGLONG cachedTime=0;
    DWORD cachedWidth=0,cachedHeight=0;
    LONG cachedSequence=0;
    std::vector<BYTE> scaled;
    ULONGLONG scaledTime=0;
    LONG scaledSequence=0;
    DWORD scaledWidth=0,scaledHeight=0;
    std::wstring path;
    bool testPath=false;
    FrameToken token{};
public:
    ~FrameReader() { if(data) UnmapViewOfFile(data); if(mapping) CloseHandle(mapping); if(file!=INVALID_HANDLE_VALUE) CloseHandle(file); }
    void Initialize(std::wstring framePath,const FrameToken& frameToken,bool inProcessTest) { path=std::move(framePath); token=frameToken; testPath=inProcessTest; }
    void read(BYTE* destination,DWORD width=WIDTH,DWORD height=HEIGHT) {
        if (!data && !path.empty() && (testPath || privateFramePath(path,false))) {
            file=CreateFileW(path.c_str(),GENERIC_READ,
                FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL|FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
            if(file!=INVALID_HANDLE_VALUE) {
                LARGE_INTEGER size{};
                BY_HANDLE_FILE_INFORMATION info{};
                if(GetFileInformationByHandle(file,&info) && !(info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) &&
                    GetFileSizeEx(file,&size) && size.QuadPart==HEADER+BYTES) {
                    mapping=CreateFileMappingW(file,nullptr,PAGE_READONLY,0,0,nullptr);
                    if(mapping) data=static_cast<const BYTE*>(MapViewOfFile(mapping,FILE_MAP_READ,0,0,HEADER+BYTES));
                }
                if(!data) { if(mapping) { CloseHandle(mapping); mapping=nullptr; } CloseHandle(file); file=INVALID_HANDLE_VALUE; }
            }
        }
        bool copied=false;
        BYTE difference=0;
        bool tokenRequired=!testPath || std::any_of(token.begin(),token.end(),[](BYTE value){return value!=0;});
        if(data && tokenRequired) for(size_t index=0;index<token.size();index++) difference|=data[24+index]^token[index];
        bool validHeader=data && !difference && *reinterpret_cast<const DWORD*>(data)==2;
        if(!validHeader) cachedTime=0;
        if(validHeader) {
            for(int attempt=0;attempt<3;attempt++) {
                auto sequence=*reinterpret_cast<const volatile LONG*>(data+12);
                MemoryBarrier();
                auto timestamp=*reinterpret_cast<const volatile ULONGLONG*>(data+16);
                // A privacy clear is an epoch boundary: old pixels cannot reappear
                // during a later publisher's seqlock window.
                if(timestamp==0) { cachedTime=0; break; }
                auto now=GetTickCount64();
                if((sequence&1) || timestamp>now || now-timestamp>2000) break;
                auto w=*reinterpret_cast<const DWORD*>(data+4),h=*reinterpret_cast<const DWORD*>(data+8);
                if(w<320 || h<180 || w>WIDTH || h>HEIGHT || (w&1) || (h&1) || w*9!=h*16) { cachedTime=0; break; }
                if(cachedTime==timestamp && cachedSequence==sequence) { copied=true; break; }
                // Preserve native source pixels. Scale only to the format this consumer selected.
                std::vector<BYTE> next(w*h*4); memcpy(next.data(),data+HEADER,next.size()); MemoryBarrier();
                if(sequence==*reinterpret_cast<const volatile LONG*>(data+12)) {
                    copied=true; cached.swap(next); cachedWidth=w; cachedHeight=h;
                    cachedTime=timestamp; cachedSequence=sequence; break;
                }
            }
            // Reuse a fresh sample during the writer's short seqlock window, avoiding black flashes.
            if(*reinterpret_cast<const volatile ULONGLONG*>(data+16)==0) { cachedTime=0; copied=false; }
            if(!copied && cachedTime && GetTickCount64()-cachedTime<=2000) {
                copied=true;
            }
        }
        if(copied) {
            if(width==cachedWidth && height==cachedHeight) memcpy(destination,cached.data(),width*height*4);
            else if(scaledTime==cachedTime && scaledSequence==cachedSequence && scaledWidth==width && scaledHeight==height)
                memcpy(destination,scaled.data(),scaled.size());
            else {
                // Bilinear resampling with pixel-centre alignment; no second JPEG or fake detail.
                std::vector<DWORD> x0(width),x1(width),weights(width);
                for(DWORD x=0;x<width;x++) {
                    const auto coordinate=std::clamp((double(x)+.5)*cachedWidth/width-.5,0.,double(cachedWidth-1));
                    x0[x]=DWORD(coordinate); x1[x]=std::min(x0[x]+1,cachedWidth-1); weights[x]=DWORD((coordinate-x0[x])*256);
                }
                for(DWORD y=0;y<height;y++) {
                    const auto coordinate=std::clamp((double(y)+.5)*cachedHeight/height-.5,0.,double(cachedHeight-1));
                    const auto y0=DWORD(coordinate),y1=std::min(y0+1,cachedHeight-1),wy=DWORD((coordinate-y0)*256);
                    for(DWORD x=0;x<width;x++) for(DWORD channel=0;channel<4;channel++) {
                        auto top=cached[(y0*cachedWidth+x0[x])*4+channel]*(256-weights[x])+cached[(y0*cachedWidth+x1[x])*4+channel]*weights[x];
                        auto bottom=cached[(y1*cachedWidth+x0[x])*4+channel]*(256-weights[x])+cached[(y1*cachedWidth+x1[x])*4+channel]*weights[x];
                        destination[(y*width+x)*4+channel]=BYTE((top*(256-wy)+bottom*wy+32768)>>16);
                    }
                }
                scaled.assign(destination,destination+width*height*4);
                scaledTime=cachedTime;scaledSequence=cachedSequence;scaledWidth=width;scaledHeight=height;
            }
        }
        // Never keep the last person's picture live after disconnection or a crashed receiver.
        if(!copied) {
            cachedTime=0;scaledTime=0;
            for(DWORD i=0;i<width*height*4;i+=4) { destination[i]=35; destination[i+1]=23; destination[i+2]=15; destination[i+3]=255; }
        }
    }
};

class AvatarStream : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaStream2, IMFMediaStream, IMFMediaEventGenerator>> {
    ComPtr<IMFMediaSource> parent;
    ComPtr<IMFStreamDescriptor> descriptor;
    ComPtr<IMFMediaEventQueue> events;
    std::mutex lock;
    std::condition_variable signal;
    std::thread worker;
    bool ended=false, running=false;
    MF_STREAM_STATE state=MF_STREAM_STATE_STOPPED;
    std::deque<ComPtr<IUnknown>> requests;
    LONGLONG sampleTime=0;
    ULONGLONG generation=0;
    FrameReader reader;
    GUID subtype=MFVideoFormat_RGB32;
    DWORD outputWidth=WIDTH,outputHeight=HEIGHT;
    std::vector<BYTE> rgb=std::vector<BYTE>(BYTES);
    static BYTE channel(int value) { return static_cast<BYTE>(std::clamp(value,0,255)); }
    void convert(BYTE* output,REFGUID format,DWORD width,DWORD height) {
        reader.read(rgb.data(),width,height);
        if(format==MFVideoFormat_RGB32) { memcpy(output,rgb.data(),width*height*4); return; }
        const bool hd=height>=720;
        auto luma=[hd](const BYTE* p) { return channel((((hd?47:66)*p[2]+(hd?157:129)*p[1]+(hd?16:25)*p[0]+128)>>8)+16); };
        auto u=[hd](int r,int g,int b) { return channel(((-(hd?26:38)*r-(hd?87:74)*g+112*b+128)>>8)+128); };
        auto v=[hd](int r,int g,int b) { return channel(((112*r-(hd?102:94)*g-(hd?10:18)*b+128)>>8)+128); };
        if(format==MFVideoFormat_NV12) {
            for(DWORD i=0;i<width*height;i++) output[i]=luma(rgb.data()+i*4);
            auto uv=output+width*height;
            for(DWORD y=0;y<height;y+=2) for(DWORD x=0;x<width;x+=2) {
                int r=0,g=0,b=0;
                for(DWORD row=0;row<2;row++) for(DWORD col=0;col<2;col++) {
                    auto p=rgb.data()+((y+row)*width+x+col)*4; r+=p[2]; g+=p[1]; b+=p[0];
                }
                *uv++=u(r/4,g/4,b/4); *uv++=v(r/4,g/4,b/4);
            }
        } else {
            for(DWORD i=0;i<width*height;i+=2) {
                auto a=rgb.data()+i*4; auto b= a+4;
                *output++=luma(a); *output++=u((a[2]+b[2])/2,(a[1]+b[1])/2,(a[0]+b[0])/2);
                *output++=luma(b); *output++=v((a[2]+b[2])/2,(a[1]+b[1])/2,(a[0]+b[0])/2);
            }
        }
    }
    void pump() {
        auto initialized=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
        auto next=std::chrono::steady_clock::now();
        std::unique_lock<std::mutex> guard(lock);
        while(!ended) {
            signal.wait(guard,[&]{return ended || (running && !requests.empty());});
            if(ended) break;
            if(signal.wait_until(guard,next,[&]{return ended || !running;})) continue;
            auto token=requests.front(); requests.pop_front();
            auto ticket=generation;
            auto queue=events;
            auto format=subtype;
            auto width=outputWidth,height=outputHeight;
            DWORD size=width*height*(format==MFVideoFormat_RGB32?4:2);
            if(format==MFVideoFormat_NV12) size=width*height*3/2;
            guard.unlock();
            ComPtr<IMFSample> sample; ComPtr<IMFMediaBuffer> buffer;
            HRESULT result=MFCreateSample(&sample);
            if(SUCCEEDED(result)) result=MFCreateMemoryBuffer(size,&buffer);
            if(SUCCEEDED(result)) {
                BYTE* pixels{}; result=buffer->Lock(&pixels,nullptr,nullptr);
                if(SUCCEEDED(result)) { convert(pixels,format,width,height); result=buffer->Unlock(); if(SUCCEEDED(result)) result=buffer->SetCurrentLength(size); }
            }
            guard.lock();
            if(ended || !running || ticket!=generation) { next=std::chrono::steady_clock::now(); continue; }
            if(SUCCEEDED(result)) {
                // FrameServer timestamps are QPC-correlated 100ns ticks, never a zero-based timeline.
                sampleTime=std::max(sampleTime+1,MFGetSystemTime());
                result=sample->AddBuffer(buffer.Get());
                if(SUCCEEDED(result)) result=sample->SetSampleTime(sampleTime);
                if(SUCCEEDED(result)) result=sample->SetSampleDuration(FRAME_TIME);
                if(SUCCEEDED(result) && token) result=sample->SetUnknown(MFSampleExtension_Token,token.Get());
                if(SUCCEEDED(result)) result=queue->QueueEventParamUnk(MEMediaSample,GUID_NULL,S_OK,sample.Get());
            }
            if(FAILED(result)) queue->QueueEventParamVar(MEError,GUID_NULL,result,nullptr);
            next=std::max(next+std::chrono::microseconds(33333),std::chrono::steady_clock::now());
        }
        guard.unlock(); if(SUCCEEDED(initialized)) CoUninitialize();
    }
public:
    ~AvatarStream() { Shutdown(); }
    HRESULT Initialize(IMFMediaSource* source,const std::wstring& framePath,const FrameToken& token,bool inProcessTest) {
        parent=source; CHECK(MFCreateEventQueue(&events));
        reader.Initialize(framePath,token,inProcessTest);
        std::array<ComPtr<IMFMediaType>,9> formats;
        std::array<IMFMediaType*,9> types{};
        const DWORD widths[]={1920,1280,960},heights[]={1080,720,540};
        const GUID subtypes[]={MFVideoFormat_RGB32,MFVideoFormat_NV12,MFVideoFormat_YUY2};
        for(DWORD resolution=0;resolution<3;resolution++) for(DWORD color=0;color<3;color++) {
            const auto index=resolution*3+color,w=widths[resolution],h=heights[resolution];
            auto& type=formats[index]; CHECK(MFCreateMediaType(&type)); types[index]=type.Get();
            CHECK(type->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Video)); CHECK(type->SetGUID(MF_MT_SUBTYPE,subtypes[color]));
            CHECK(MFSetAttributeSize(type.Get(),MF_MT_FRAME_SIZE,w,h));
            CHECK(MFSetAttributeRatio(type.Get(),MF_MT_FRAME_RATE,30,1));
            CHECK(MFSetAttributeRatio(type.Get(),MF_MT_PIXEL_ASPECT_RATIO,1,1));
            CHECK(type->SetUINT32(MF_MT_INTERLACE_MODE,MFVideoInterlace_Progressive));
            CHECK(type->SetUINT32(MF_MT_DEFAULT_STRIDE,w*(color==0?4:color==1?1:2)));
            CHECK(type->SetUINT32(MF_MT_FIXED_SIZE_SAMPLES,TRUE));
            CHECK(type->SetUINT32(MF_MT_SAMPLE_SIZE,color==0?w*h*4:color==1?w*h*3/2:w*h*2));
            CHECK(type->SetUINT32(MF_MT_VIDEO_PRIMARIES,MFVideoPrimaries_BT709));
            CHECK(type->SetUINT32(MF_MT_TRANSFER_FUNCTION,MFVideoTransFunc_709));
            CHECK(type->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE,color==0?MFNominalRange_0_255:MFNominalRange_16_235));
            if(color) CHECK(type->SetUINT32(MF_MT_YUV_MATRIX,h>=720?MFVideoTransferMatrix_BT709:MFVideoTransferMatrix_BT601));
        }
        CHECK(MFCreateStreamDescriptor(0,static_cast<DWORD>(types.size()),types.data(),&descriptor));
        ComPtr<IMFMediaTypeHandler> handler; CHECK(descriptor->GetMediaTypeHandler(&handler));
        CHECK(handler->SetCurrentMediaType(formats[0].Get()));
        try { worker=std::thread([this]{pump();}); } catch(...) { return E_OUTOFMEMORY; } return S_OK;
    }
    HRESULT Started(const PROPVARIANT* position,IMFMediaType* type) {
        std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN;
        ComPtr<IMFMediaTypeHandler> handler; CHECK(descriptor->GetMediaTypeHandler(&handler));
        CHECK(handler->IsMediaTypeSupported(type,nullptr)); CHECK(handler->SetCurrentMediaType(type));
        GUID requested{}; CHECK(type->GetGUID(MF_MT_SUBTYPE,&requested));
        UINT32 width{},height{}; CHECK(MFGetAttributeSize(type,MF_MT_FRAME_SIZE,&width,&height));
        if(!running || subtype!=requested || width!=outputWidth || height!=outputHeight) { ++generation; sampleTime=0; requests.clear(); }
        subtype=requested; outputWidth=width; outputHeight=height; running=true; state=MF_STREAM_STATE_RUNNING;
        auto result=events->QueueEventParamVar(MEStreamStarted,GUID_NULL,S_OK,position);
        if(FAILED(result)) { running=false; state=MF_STREAM_STATE_STOPPED; ++generation; return result; }
        signal.notify_all(); return S_OK;
    }
    HRESULT Halt(bool pause) {
        std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN;
        running=false; ++generation; requests.clear(); state=pause?MF_STREAM_STATE_PAUSED:MF_STREAM_STATE_STOPPED;
        signal.notify_all(); return events->QueueEventParamVar(pause?MEStreamPaused:MEStreamStopped,GUID_NULL,S_OK,nullptr);
    }
    HRESULT Shutdown() {
        { std::lock_guard<std::mutex> guard(lock); ended=true; running=false; ++generation; requests.clear(); signal.notify_all(); }
        if(worker.joinable()) worker.join();
        parent.Reset(); if(events) events->Shutdown(); return S_OK;
    }
    STDMETHODIMP GetEvent(DWORD flags,IMFMediaEvent** event) override { return events->GetEvent(flags,event); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* cb,IUnknown* s) override { return events->BeginGetEvent(cb,s); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r,IMFMediaEvent** e) override { return events->EndGetEvent(r,e); }
    STDMETHODIMP QueueEvent(MediaEventType t,REFGUID g,HRESULT hr,const PROPVARIANT* v) override { return events->QueueEventParamVar(t,g,hr,v); }
    STDMETHODIMP GetMediaSource(IMFMediaSource** p) override {
        if(!p) return E_POINTER; std::lock_guard<std::mutex> guard(lock); return ended?MF_E_SHUTDOWN:parent.CopyTo(p);
    }
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** p) override { if(!p) return E_POINTER; std::lock_guard<std::mutex> guard(lock); return ended?MF_E_SHUTDOWN:descriptor.CopyTo(p); }
    STDMETHODIMP RequestSample(IUnknown* token) override {
        std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN; if(!running) return MF_E_INVALIDREQUEST;
        if(requests.size()>=8) return MF_E_NOTACCEPTING; requests.emplace_back(token); signal.notify_one(); return S_OK;
    }
    STDMETHODIMP SetStreamState(MF_STREAM_STATE s) override {
        std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN;
        if(s!=MF_STREAM_STATE_RUNNING && s!=MF_STREAM_STATE_PAUSED && s!=MF_STREAM_STATE_STOPPED) return E_INVALIDARG;
        if(state==s) return S_OK;
        ++generation;
        state=s; running=s==MF_STREAM_STATE_RUNNING; if(!running) requests.clear(); signal.notify_all(); return S_OK;
    }
    STDMETHODIMP GetStreamState(MF_STREAM_STATE* s) override { if(!s) return E_POINTER; std::lock_guard<std::mutex> g(lock); *s=state; return ended?MF_E_SHUTDOWN:S_OK; }
};

class AvatarSource : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFMediaSourceEx, IMFMediaSource, IMFMediaEventGenerator>, IMFGetService, IKsControl> {
    ComPtr<IMFMediaEventQueue> events;
    ComPtr<IMFPresentationDescriptor> descriptor;
    ComPtr<IMFAttributes> attributes, streamAttributes;
    ComPtr<AvatarStream> stream;
    std::mutex lock;
    bool ended=false, announced=false, running=false;
public:
    HRESULT Initialize(IMFAttributes* activationAttributes,bool inProcessTest=false) {
        if(!activationAttributes) return E_POINTER;
        CHECK(MFCreateEventQueue(&events)); CHECK(MFCreateAttributes(&attributes,12)); CHECK(MFCreateAttributes(&streamAttributes,4));
        CHECK(attributes->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
        // Symbolic links, pipeline settings, and the private frame path must survive activation.
        CHECK(activationAttributes->CopyAllItems(attributes.Get()));
        PWSTR path{}; CHECK(attributes->GetAllocatedString(AvatarFramePathAttribute,&path,nullptr));
        std::wstring framePath(path); CoTaskMemFree(path);
        if(!inProcessTest && !privateFramePath(framePath,false)) return E_ACCESSDENIED;
        FrameToken token{};
        if(!inProcessTest) {
            UINT32 size{},copied{}; CHECK(attributes->GetBlobSize(AvatarFrameTokenAttribute,&size));
            if(size!=token.size()) return E_ACCESSDENIED;
            CHECK(attributes->GetBlob(AvatarFrameTokenAttribute,token.data(),static_cast<UINT32>(token.size()),&copied));
            if(copied!=token.size() || std::all_of(token.begin(),token.end(),[](BYTE value){return value==0;})) return E_ACCESSDENIED;
        }
        CHECK(streamAttributes->SetGUID(MF_DEVICESTREAM_STREAM_CATEGORY,PINNAME_VIDEO_CAPTURE));
        CHECK(streamAttributes->SetUINT32(MF_DEVICESTREAM_STREAM_ID,0));
        CHECK(streamAttributes->SetUINT32(MF_DEVICESTREAM_FRAMESERVER_SHARED,1));
        CHECK(streamAttributes->SetUINT32(MF_DEVICESTREAM_ATTRIBUTE_FRAMESOURCE_TYPES,MFFrameSourceTypes_Color));
        stream=Make<AvatarStream>(); if(!stream) return E_OUTOFMEMORY;
        CHECK(stream->Initialize(this,framePath,token,inProcessTest));
        ComPtr<IMFStreamDescriptor> sd; CHECK(stream->GetStreamDescriptor(&sd));
        CHECK(streamAttributes->CopyAllItems(sd.Get()));
        IMFStreamDescriptor* list[]={sd.Get()}; CHECK(MFCreatePresentationDescriptor(1,list,&descriptor));
        CHECK(descriptor->SelectStream(0)); return S_OK;
    }
    STDMETHODIMP GetEvent(DWORD f,IMFMediaEvent** e) override { return events->GetEvent(f,e); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* c,IUnknown* s) override { return events->BeginGetEvent(c,s); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* r,IMFMediaEvent** e) override { return events->EndGetEvent(r,e); }
    STDMETHODIMP QueueEvent(MediaEventType t,REFGUID g,HRESULT hr,const PROPVARIANT* v) override { return events->QueueEventParamVar(t,g,hr,v); }
    STDMETHODIMP GetCharacteristics(DWORD* c) override { if(!c) return E_POINTER; std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN; *c=MFMEDIASOURCE_IS_LIVE; return S_OK; }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** d) override {
        std::lock_guard<std::mutex> guard(lock); return ended?MF_E_SHUTDOWN:descriptor->Clone(d);
    }
    STDMETHODIMP Start(IMFPresentationDescriptor* pd,const GUID* format,const PROPVARIANT* position) override {
        if(!pd || !position) return E_POINTER;
        if(format && *format!=GUID_NULL) return MF_E_UNSUPPORTED_TIME_FORMAT;
        if(position->vt!=VT_EMPTY && !(position->vt==VT_I8 && position->hVal.QuadPart==0)) return MF_E_UNSUPPORTED_TIME_FORMAT;
        std::lock_guard<std::mutex> guard(lock); if(ended) return MF_E_SHUTDOWN;
        // FrameServer can restart a live preview when another client joins or
        // its capture graph changes. A selected live stream may start again.
        DWORD count{}; CHECK(pd->GetStreamDescriptorCount(&count)); if(count!=1) return MF_E_INVALIDREQUEST;
        BOOL selected{}; ComPtr<IMFStreamDescriptor> sd; CHECK(pd->GetStreamDescriptorByIndex(0,&selected,&sd));
        if(!selected) return MF_E_INVALIDREQUEST;
        DWORD streamId{}; CHECK(sd->GetStreamIdentifier(&streamId)); if(streamId!=0) return MF_E_INVALIDSTREAMNUMBER;
        ComPtr<IMFMediaTypeHandler> handler; ComPtr<IMFMediaType> type;
        CHECK(sd->GetMediaTypeHandler(&handler)); CHECK(handler->GetCurrentMediaType(&type));
        ComPtr<IMFStreamDescriptor> localDescriptor; ComPtr<IMFMediaTypeHandler> localHandler;
        CHECK(stream->GetStreamDescriptor(&localDescriptor)); CHECK(localDescriptor->GetMediaTypeHandler(&localHandler));
        CHECK(localHandler->IsMediaTypeSupported(type.Get(),nullptr));
        PROPVARIANT startTime{}; startTime.vt=VT_I8; startTime.hVal.QuadPart=MFGetSystemTime();
        CHECK(events->QueueEventParamUnk(announced?MEUpdatedStream:MENewStream,GUID_NULL,S_OK,stream.Get())); announced=true;
        CHECK(stream->Started(&startTime,type.Get()));
        auto result=events->QueueEventParamVar(MESourceStarted,GUID_NULL,S_OK,&startTime);
        if(FAILED(result)) { stream->Halt(false); return result; }
        running=true; return S_OK;
    }
    STDMETHODIMP Stop() override { std::lock_guard<std::mutex> g(lock); if(ended) return MF_E_SHUTDOWN; CHECK(stream->Halt(false)); running=false; return events->QueueEventParamVar(MESourceStopped,GUID_NULL,S_OK,nullptr); }
    STDMETHODIMP Pause() override { std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        ComPtr<AvatarStream> s;
        { std::lock_guard<std::mutex> g(lock); if(ended) return MF_E_SHUTDOWN; ended=true; running=false; s=stream; stream.Reset(); }
        if(s) s->Shutdown(); if(events) events->Shutdown(); return S_OK;
    }
    bool IsShutdown() { std::lock_guard<std::mutex> g(lock); return ended; }
    STDMETHODIMP GetSourceAttributes(IMFAttributes** a) override { if(!a) return E_POINTER; std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:attributes.CopyTo(a); }
    STDMETHODIMP GetStreamAttributes(DWORD id,IMFAttributes** a) override { if(!a) return E_POINTER; std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:(id==0?streamAttributes.CopyTo(a):MF_E_INVALIDSTREAMNUMBER); }
    STDMETHODIMP SetD3DManager(IUnknown*) override { std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:S_OK; }
    STDMETHODIMP GetService(REFGUID,REFIID,LPVOID* object) override {
        if(!object) return E_POINTER; *object=nullptr;
        std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:MF_E_UNSUPPORTED_SERVICE;
    }
    STDMETHODIMP KsProperty(PKSPROPERTY,ULONG,LPVOID,ULONG,ULONG* returned) override {
        if(returned) *returned=0; std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
    }
    STDMETHODIMP KsMethod(PKSMETHOD,ULONG,LPVOID,ULONG,ULONG* returned) override {
        if(returned) *returned=0; std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
    }
    STDMETHODIMP KsEvent(PKSEVENT,ULONG,LPVOID,ULONG,ULONG* returned) override {
        if(returned) *returned=0; std::lock_guard<std::mutex> g(lock); return ended?MF_E_SHUTDOWN:HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND);
    }
};

// FrameServer activates a COM IMFActivate, which creates the actual media source.
class AvatarActivate : public RuntimeClass<RuntimeClassFlags<ClassicCom>, ChainInterfaces<IMFActivate, IMFAttributes>> {
    ComPtr<IMFAttributes> attributes;
    ComPtr<AvatarSource> source;
    std::mutex lock;
    bool inProcessTest=false;
public:
    HRESULT Initialize(bool test=false) { inProcessTest=test; return MFCreateAttributes(&attributes,8); }
    STDMETHODIMP ActivateObject(REFIID iid,void** object) override {
        std::lock_guard<std::mutex> g(lock);
        if(!object) return E_POINTER; *object=nullptr;
        if(source && source->IsShutdown()) source.Reset();
        if(!source) { source=Make<AvatarSource>(); if(!source) return E_OUTOFMEMORY; auto hr=source->Initialize(attributes.Get(),inProcessTest); if(FAILED(hr)) { if(source) source->Shutdown(); source.Reset(); return hr; } }
        return source->QueryInterface(iid,object);
    }
    // Required for a FrameServer IMFActivate: source lifetime belongs to IMFMediaSource::Shutdown.
    STDMETHODIMP ShutdownObject() override { return E_NOTIMPL; }
    STDMETHODIMP DetachObject() override { return E_NOTIMPL; }
    STDMETHODIMP GetItem(REFGUID k,PROPVARIANT* v) override { return attributes->GetItem(k,v); }
    STDMETHODIMP GetItemType(REFGUID k,MF_ATTRIBUTE_TYPE* t) override { return attributes->GetItemType(k,t); }
    STDMETHODIMP CompareItem(REFGUID k,REFPROPVARIANT v,BOOL* r) override { return attributes->CompareItem(k,v,r); }
    STDMETHODIMP Compare(IMFAttributes* a,MF_ATTRIBUTES_MATCH_TYPE t,BOOL* r) override { return attributes->Compare(a,t,r); }
    STDMETHODIMP GetUINT32(REFGUID k,UINT32* v) override { return attributes->GetUINT32(k,v); }
    STDMETHODIMP GetUINT64(REFGUID k,UINT64* v) override { return attributes->GetUINT64(k,v); }
    STDMETHODIMP GetDouble(REFGUID k,double* v) override { return attributes->GetDouble(k,v); }
    STDMETHODIMP GetGUID(REFGUID k,GUID* v) override { return attributes->GetGUID(k,v); }
    STDMETHODIMP GetStringLength(REFGUID k,UINT32* v) override { return attributes->GetStringLength(k,v); }
    STDMETHODIMP GetString(REFGUID k,LPWSTR v,UINT32 n,UINT32* l) override { return attributes->GetString(k,v,n,l); }
    STDMETHODIMP GetAllocatedString(REFGUID k,LPWSTR* v,UINT32* n) override { return attributes->GetAllocatedString(k,v,n); }
    STDMETHODIMP GetBlobSize(REFGUID k,UINT32* n) override { return attributes->GetBlobSize(k,n); }
    STDMETHODIMP GetBlob(REFGUID k,UINT8* v,UINT32 n,UINT32* l) override { return attributes->GetBlob(k,v,n,l); }
    STDMETHODIMP GetAllocatedBlob(REFGUID k,UINT8** v,UINT32* n) override { return attributes->GetAllocatedBlob(k,v,n); }
    STDMETHODIMP GetUnknown(REFGUID k,REFIID i,LPVOID* v) override { return attributes->GetUnknown(k,i,v); }
    STDMETHODIMP SetItem(REFGUID k,REFPROPVARIANT v) override { return attributes->SetItem(k,v); }
    STDMETHODIMP DeleteItem(REFGUID k) override { return attributes->DeleteItem(k); }
    STDMETHODIMP DeleteAllItems() override { return attributes->DeleteAllItems(); }
    STDMETHODIMP SetUINT32(REFGUID k,UINT32 v) override { return attributes->SetUINT32(k,v); }
    STDMETHODIMP SetUINT64(REFGUID k,UINT64 v) override { return attributes->SetUINT64(k,v); }
    STDMETHODIMP SetDouble(REFGUID k,double v) override { return attributes->SetDouble(k,v); }
    STDMETHODIMP SetGUID(REFGUID k,REFGUID v) override { return attributes->SetGUID(k,v); }
    STDMETHODIMP SetString(REFGUID k,LPCWSTR v) override { return attributes->SetString(k,v); }
    STDMETHODIMP SetBlob(REFGUID k,const UINT8* v,UINT32 n) override { return attributes->SetBlob(k,v,n); }
    STDMETHODIMP SetUnknown(REFGUID k,IUnknown* v) override { return attributes->SetUnknown(k,v); }
    STDMETHODIMP LockStore() override { return attributes->LockStore(); }
    STDMETHODIMP UnlockStore() override { return attributes->UnlockStore(); }
    STDMETHODIMP GetCount(UINT32* n) override { return attributes->GetCount(n); }
    STDMETHODIMP GetItemByIndex(UINT32 n,GUID* k,PROPVARIANT* v) override { return attributes->GetItemByIndex(n,k,v); }
    STDMETHODIMP CopyAllItems(IMFAttributes* a) override { return attributes->CopyAllItems(a); }
};

class Factory : public RuntimeClass<RuntimeClassFlags<ClassicCom>,IClassFactory> {
public:
    STDMETHODIMP CreateInstance(IUnknown* outer,REFIID iid,void** p) override {
        if(outer) return CLASS_E_NOAGGREGATION; auto activate=Make<AvatarActivate>(); CHECK(activate->Initialize()); return activate->QueryInterface(iid,p);
    }
    STDMETHODIMP LockServer(BOOL) override { return S_OK; }
};

STDAPI DllGetClassObject(REFCLSID cls,REFIID iid,void** p) {
    if(cls!=CameraCLSID) return CLASS_E_CLASSNOTAVAILABLE; auto factory=Make<Factory>(); return factory->QueryInterface(iid,p);
}
STDAPI DllCanUnloadNow() { return S_FALSE; }
extern "C" HRESULT __stdcall DllRegisterServer() {
    wchar_t path[32768]{}; GetModuleFileNameW(module,path,32768);
    std::wstring key=std::wstring(L"Software\\Classes\\CLSID\\")+CameraID+L"\\InprocServer32";
    HKEY registry{}; auto result=RegCreateKeyExW(HKEY_LOCAL_MACHINE,key.c_str(),0,nullptr,0,KEY_WRITE,nullptr,&registry,nullptr);
    if(result) return HRESULT_FROM_WIN32(result);
    result=RegSetValueExW(registry,nullptr,0,REG_SZ,reinterpret_cast<const BYTE*>(path),(static_cast<DWORD>(wcslen(path))+1)*sizeof(wchar_t));
    if(!result) result=RegSetValueExW(registry,L"ThreadingModel",0,REG_SZ,reinterpret_cast<const BYTE*>(L"Both"),10);
    RegCloseKey(registry); return HRESULT_FROM_WIN32(result);
}
extern "C" HRESULT __stdcall DllUnregisterServer() {
    auto result=RegDeleteTreeW(HKEY_LOCAL_MACHINE,(std::wstring(L"Software\\Classes\\CLSID\\")+CameraID).c_str());
    return result==ERROR_FILE_NOT_FOUND?S_OK:HRESULT_FROM_WIN32(result);
}
class ComApartment {
    HRESULT result;
public:
    ComApartment():result(CoInitializeEx(nullptr,COINIT_MULTITHREADED)) {}
    ~ComApartment() { if(SUCCEEDED(result)) CoUninitialize(); }
    HRESULT Status() const { return result==RPC_E_CHANGED_MODE?S_OK:result; }
};
void stopCameraLocked() {
    if(virtualCamera) { virtualCamera->Stop(); virtualCamera->Remove(); virtualCamera->Shutdown(); virtualCamera.Reset(); }
    activeFramePath.clear();
    if(cameraMfStarted) { MFShutdown(); cameraMfStarted=false; }
    if(cameraLibrary) { FreeLibrary(cameraLibrary); cameraLibrary=nullptr; }
}
extern "C" __declspec(dllexport) HRESULT __stdcall BeginAvatarCamera(LPCWSTR framePath) {
    if(!framePath) return E_POINTER;
    std::wstring path(framePath); if(!privateFramePath(path,true)) return E_ACCESSDENIED;
    ComApartment apartment; CHECK(apartment.Status());
    std::lock_guard<std::mutex> guard(cameraLock);
    if(virtualCamera) return _wcsicmp(activeFramePath.c_str(),path.c_str())==0?S_OK:HRESULT_FROM_WIN32(ERROR_BUSY);
    FrameToken token{}; CHECK(createFrameToken(path,token));
    CHECK(MFStartup(MF_VERSION)); cameraMfStarted=true;
    cameraLibrary=LoadLibraryExW(L"mfsensorgroup.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
    if(!cameraLibrary) { stopCameraLocked(); return HRESULT_FROM_WIN32(ERROR_OLD_WIN_VERSION); }
    auto create=reinterpret_cast<decltype(&MFCreateVirtualCamera)>(GetProcAddress(cameraLibrary,"MFCreateVirtualCamera"));
    if(!create) { stopCameraLocked(); return HRESULT_FROM_WIN32(ERROR_OLD_WIN_VERSION); }
    auto result=create(MFVirtualCameraType_SoftwareCameraSource,MFVirtualCameraLifetime_Session,MFVirtualCameraAccess_CurrentUser,
        L"AlterEgo MR Avatar",CameraID,nullptr,0,&virtualCamera);
    if(SUCCEEDED(result)) result=virtualCamera->SetString(AvatarFramePathAttribute,path.c_str());
    if(SUCCEEDED(result)) result=virtualCamera->SetBlob(AvatarFrameTokenAttribute,token.data(),static_cast<UINT32>(token.size()));
    if(SUCCEEDED(result)) result=virtualCamera->Start(nullptr);
    if(FAILED(result)) { stopCameraLocked(); return result; }
    activeFramePath=path; return S_OK;
}
extern "C" __declspec(dllexport) void __stdcall EndAvatarCamera() {
    ComApartment apartment;
    std::lock_guard<std::mutex> guard(cameraLock);
    stopCameraLocked();
}
extern "C" __declspec(dllexport) HRESULT __stdcall TestAvatarSource() {
    ComApartment apartment; CHECK(apartment.Status()); CHECK(MFStartup(MF_VERSION));
    ComPtr<AvatarActivate> activate; ComPtr<IMFMediaSource> source;
    auto result=[&]() -> HRESULT {
        wchar_t path[32768]{};
        if(!GetEnvironmentVariableW(L"ALTEREGO_MR_FRAME_FILE",path,32768)) return E_INVALIDARG;
        // Only this explicit in-process diagnostic can use a project-local fixture.
        activate=Make<AvatarActivate>(); CHECK(activate->Initialize(true));
        CHECK(activate->SetString(AvatarFramePathAttribute,path));
        CHECK(activate->SetString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK,L"alterego-test-link"));
        CHECK(activate->ActivateObject(IID_PPV_ARGS(&source)));
        ComPtr<IMFMediaSourceEx> sourceEx; CHECK(source.As(&sourceEx));
        ComPtr<IMFAttributes> copied; CHECK(sourceEx->GetSourceAttributes(&copied));
        wchar_t copiedPath[32768]{}; CHECK(copied->GetString(AvatarFramePathAttribute,copiedPath,32768,nullptr));
        wchar_t link[64]{}; CHECK(copied->GetString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK,link,64,nullptr));
        if(wcscmp(path,copiedPath) || wcscmp(link,L"alterego-test-link")) return E_FAIL;
        // Normal COM activation must ignore the test environment and reject a fixture path.
        auto normal=Make<AvatarActivate>(); CHECK(normal->Initialize()); CHECK(normal->SetString(AvatarFramePathAttribute,path));
        ComPtr<IMFMediaSource> denied; if(normal->ActivateObject(IID_PPV_ARGS(&denied))!=E_ACCESSDENIED) return E_FAIL;
        ComPtr<IMFGetService> service; CHECK(source.As(&service)); void* unsupported{};
        if(service->GetService(GUID_NULL,IID_IUnknown,&unsupported)!=MF_E_UNSUPPORTED_SERVICE || unsupported) return E_FAIL;
        ComPtr<IKsControl> controls; CHECK(source.As(&controls)); ULONG returned=99;
        if(controls->KsProperty(nullptr,0,nullptr,0,&returned)!=HRESULT_FROM_WIN32(ERROR_SET_NOT_FOUND) || returned) return E_FAIL;
        if(activate->ShutdownObject()!=E_NOTIMPL || activate->DetachObject()!=E_NOTIMPL) return E_FAIL;
        ComPtr<IMFPresentationDescriptor> pd; CHECK(source->CreatePresentationDescriptor(&pd));
        PROPVARIANT position{}; auto before=MFGetSystemTime(); CHECK(source->Start(pd.Get(),nullptr,&position));
        ComPtr<IMFMediaEvent> event; CHECK(source->GetEvent(0,&event));
        PROPVARIANT value{}; CHECK(event->GetValue(&value));
        ComPtr<IMFMediaStream> stream; auto hr=value.punkVal->QueryInterface(IID_PPV_ARGS(&stream)); PropVariantClear(&value); CHECK(hr);
        ComPtr<IMFStreamDescriptor> streamDescriptor; CHECK(stream->GetStreamDescriptor(&streamDescriptor));
        ComPtr<IMFMediaTypeHandler> mediaHandler; CHECK(streamDescriptor->GetMediaTypeHandler(&mediaHandler));
        ComPtr<IMFMediaType> mediaType; CHECK(mediaHandler->GetCurrentMediaType(&mediaType));
        UINT32 stride{}; CHECK(mediaType->GetUINT32(MF_MT_DEFAULT_STRIDE,&stride));
        if(static_cast<LONG>(stride)!=static_cast<LONG>(WIDTH*4)) return E_FAIL;
        CHECK(source->GetEvent(0,&event)); MediaEventType type{}; CHECK(event->GetType(&type));
        CHECK(event->GetValue(&value)); bool sourceStartOk=type==MESourceStarted && value.vt==VT_I8 && value.hVal.QuadPart>=before;
        PropVariantClear(&value); if(!sourceStartOk) return E_FAIL;
        CHECK(stream->GetEvent(0,&event)); CHECK(event->GetType(&type)); CHECK(event->GetValue(&value));
        bool streamStartOk=type==MEStreamStarted && value.vt==VT_I8 && value.hVal.QuadPart>=before;
        PropVariantClear(&value); if(!streamStartOk) return E_FAIL;
        LONGLONG lastTime=0;
        for(int frame=0;frame<2;frame++) {
            CHECK(stream->RequestSample(nullptr)); CHECK(stream->GetEvent(0,&event)); CHECK(event->GetType(&type));
            if(type!=MEMediaSample) return E_UNEXPECTED;
            CHECK(event->GetValue(&value)); ComPtr<IMFSample> sample;
            hr=value.punkVal->QueryInterface(IID_PPV_ARGS(&sample)); PropVariantClear(&value); CHECK(hr);
            DWORD length{}; CHECK(sample->GetTotalLength(&length));
            ComPtr<IMFMediaBuffer> buffer; CHECK(sample->ConvertToContiguousBuffer(&buffer));
            BYTE* pixels{}; CHECK(buffer->Lock(&pixels,nullptr,nullptr));
            bool colorOk=pixels[0]==237 && pixels[1]==149 && pixels[2]==100;
            auto bottom=pixels+(HEIGHT-1)*WIDTH*4;
            bool orientationOk=bottom[0]==17 && bottom[1]==31 && bottom[2]==53; buffer->Unlock();
            LONGLONG time{},duration{}; CHECK(sample->GetSampleTime(&time)); CHECK(sample->GetSampleDuration(&duration));
            if(length!=BYTES || !colorOk || !orientationOk || duration!=FRAME_TIME || time<before || time<=lastTime ||
                llabs(MFGetSystemTime()-time)>20000000) return E_FAIL;
            lastTime=time;
        }
        // MediaCapture may call Start again while changing or sharing its
        // preview graph. A live source must acknowledge that valid transition.
        CHECK(source->Start(pd.Get(),nullptr,&position));
        CHECK(source->GetEvent(0,&event)); CHECK(event->GetType(&type));
        if(type!=MEUpdatedStream) return E_FAIL;
        CHECK(source->GetEvent(0,&event)); CHECK(event->GetType(&type));
        if(type!=MESourceStarted) return E_FAIL;
        CHECK(stream->GetEvent(0,&event)); CHECK(event->GetType(&type));
        if(type!=MEStreamStarted) return E_FAIL;
        CHECK(stream->RequestSample(nullptr)); CHECK(stream->GetEvent(0,&event));
        CHECK(event->GetType(&type)); if(type!=MEMediaSample) return E_FAIL;
        if(source->Pause()!=MF_E_INVALID_STATE_TRANSITION) return E_FAIL;
        CHECK(source->Stop()); if(stream->RequestSample(nullptr)!=MF_E_INVALIDREQUEST) return E_FAIL;
        CHECK(source->GetEvent(0,&event)); CHECK(stream->GetEvent(0,&event));
        DWORD formatCount{}; CHECK(mediaHandler->GetMediaTypeCount(&formatCount)); if(formatCount!=9) return E_FAIL;
        for(DWORD index=1;index<formatCount;index++) {
            ComPtr<IMFMediaType> format; CHECK(mediaHandler->GetMediaTypeByIndex(index,&format));
            CHECK(mediaHandler->SetCurrentMediaType(format.Get())); CHECK(source->Start(pd.Get(),nullptr,&position));
            CHECK(source->GetEvent(0,&event)); CHECK(source->GetEvent(0,&event)); CHECK(stream->GetEvent(0,&event));
            CHECK(stream->RequestSample(nullptr)); CHECK(stream->GetEvent(0,&event)); CHECK(event->GetType(&type));
            if(type!=MEMediaSample) return E_FAIL;
            CHECK(event->GetValue(&value)); ComPtr<IMFSample> sample;
            CHECK(value.punkVal->QueryInterface(IID_PPV_ARGS(&sample))); PropVariantClear(&value);
            DWORD size{}; CHECK(sample->GetTotalLength(&size));
            UINT32 w{},h{}; CHECK(MFGetAttributeSize(format.Get(),MF_MT_FRAME_SIZE,&w,&h));
            const auto color=index%3;
            if(size!=(color==0?w*h*4:color==1?w*h*3/2:w*h*2)) return E_FAIL;
            ComPtr<IMFMediaBuffer> buffer; CHECK(sample->ConvertToContiguousBuffer(&buffer));
            BYTE* pixels{}; CHECK(buffer->Lock(&pixels,nullptr,nullptr));
            bool correct=color==0?pixels[0]==237:pixels[0]==(h>=720?141:140) && (color==1?pixels[w*h]==(h>=720?171:174):pixels[1]==(h>=720?171:174));
            // Resizing may blend the source's single bottom scanline. Verify the
            // full-size edge exactly and smaller formats by orientation.
            if(h==HEIGHT && color) correct=correct && pixels[(h-1)*w*(color==1?1:2)]==46;
            if(h<HEIGHT) correct=correct && pixels[(h-1)*w*(color==0?4:color==1?1:2)]<pixels[0];
            buffer->Unlock(); if(!correct) return E_FAIL;
            CHECK(source->Stop()); CHECK(source->GetEvent(0,&event)); CHECK(stream->GetEvent(0,&event));
        }
        FrameToken token{}; CHECK(createFrameToken(path,token));
        FrameReader authenticated; authenticated.Initialize(path,token,true);
        std::vector<BYTE> pixels(BYTES); authenticated.read(pixels.data());
        if(pixels[0]!=237 || pixels[1]!=149 || pixels[2]!=100) return E_FAIL;
        auto writeHeader=[&](LONGLONG offset,const void* bytes,DWORD count) -> HRESULT {
            auto fixture=CreateFileW(path,GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
                nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
            if(fixture==INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());
            LARGE_INTEGER position{}; position.QuadPart=offset; DWORD written{};
            bool success=SetFilePointerEx(fixture,position,nullptr,FILE_BEGIN) && WriteFile(fixture,bytes,count,&written,nullptr);
            auto result=!success?HRESULT_FROM_WIN32(GetLastError()):(written==count?S_OK:E_FAIL); CloseHandle(fixture); return result;
        };
        // Seqlock contention keeps an already-live frame, but a clear invalidates it.
        LONG odd=101,even=102; ULONGLONG zero=0,fresh=GetTickCount64();
        CHECK(writeHeader(12,&odd,sizeof(odd))); authenticated.read(pixels.data());
        if(pixels[0]!=237 || pixels[1]!=149 || pixels[2]!=100) return E_FAIL;
        CHECK(writeHeader(16,&zero,sizeof(zero))); authenticated.read(pixels.data());
        if(pixels[0]!=35 || pixels[1]!=23 || pixels[2]!=15) return E_FAIL;
        CHECK(writeHeader(16,&fresh,sizeof(fresh))); authenticated.read(pixels.data());
        if(pixels[0]!=35 || pixels[1]!=23 || pixels[2]!=15) return E_FAIL;
        CHECK(writeHeader(12,&even,sizeof(even))); authenticated.read(pixels.data());
        if(pixels[0]!=237 || pixels[1]!=149 || pixels[2]!=100) return E_FAIL;
        token[0]^=1; authenticated.Initialize(path,token,true); authenticated.read(pixels.data());
        if(pixels[0]!=35 || pixels[1]!=23 || pixels[2]!=15) return E_FAIL;
        // Restoring the header/token during an unfinished publish cannot revive
        // the cache that an invalid header cleared.
        token[0]^=1; authenticated.Initialize(path,token,true);
        CHECK(writeHeader(12,&odd,sizeof(odd))); authenticated.read(pixels.data());
        if(pixels[0]!=35 || pixels[1]!=23 || pixels[2]!=15) return E_FAIL;
        CHECK(writeHeader(12,&even,sizeof(even)));
        CHECK(source->Shutdown());
        ComPtr<IMFMediaSource> reopened; CHECK(activate->ActivateObject(IID_PPV_ARGS(&reopened)));
        ComPtr<IMFPresentationDescriptor> reopenedDescriptor;
        auto reopenResult=reopened->CreatePresentationDescriptor(&reopenedDescriptor);
        reopened->Shutdown(); CHECK(reopenResult);
        return S_OK;
    }();
    if(source) source->Shutdown(); source.Reset(); activate.Reset(); MFShutdown(); return result;
}
class CameraReadCallback : public RuntimeClass<RuntimeClassFlags<ClassicCom>,IMFSourceReaderCallback> {
    HANDLE ready=CreateEventW(nullptr,FALSE,FALSE,nullptr);
    std::mutex lock;
    HRESULT status=E_PENDING;
    ComPtr<IMFSample> received;
public:
    ~CameraReadCallback() { if(ready) CloseHandle(ready); }
    STDMETHODIMP OnReadSample(HRESULT result,DWORD,DWORD flags,LONGLONG,IMFSample* sample) override {
        { std::lock_guard<std::mutex> guard(lock); status=(flags&MF_SOURCE_READERF_ERROR)?E_FAIL:result; received=sample; }
        if(ready) SetEvent(ready); return S_OK;
    }
    STDMETHODIMP OnFlush(DWORD) override { return S_OK; }
    STDMETHODIMP OnEvent(DWORD,IMFMediaEvent*) override { return S_OK; }
    HRESULT Wait(IMFSample** sample,DWORD timeout) {
        if(!ready) return HRESULT_FROM_WIN32(ERROR_NOT_ENOUGH_MEMORY);
        auto wait=WaitForSingleObject(ready,timeout);
        if(wait!=WAIT_OBJECT_0) return HRESULT_FROM_WIN32(wait==WAIT_TIMEOUT?ERROR_TIMEOUT:GetLastError());
        std::lock_guard<std::mutex> guard(lock); CHECK(status); return received?received.CopyTo(sample):S_FALSE;
    }
};
// Explicit installer QA only: consumes the camera through Windows' registered service path.
// Does not register the DLL, read a physical camera, or save any camera content.
extern "C" __declspec(dllexport) HRESULT __stdcall TestRegisteredAvatarCamera(LPCWSTR framePath) {
    ComApartment apartment; CHECK(apartment.Status()); CHECK(BeginAvatarCamera(framePath));
    ComPtr<IMFMediaSource> source; ComPtr<IMFSourceReader> reader;
    HMODULE readerLibrary{};
    auto result=[&]() -> HRESULT {
        ComPtr<IMFAttributes> enumeration; CHECK(MFCreateAttributes(&enumeration,1));
        CHECK(enumeration->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID));
        IMFActivate** cameras{}; UINT32 count{}; CHECK(MFEnumDeviceSources(enumeration.Get(),&cameras,&count));
        ComPtr<IMFActivate> selected;
        for(UINT32 index=0;index<count;index++) {
            PWSTR name{};
            if(SUCCEEDED(cameras[index]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME,&name,nullptr)) &&
                wcsstr(name,L"AlterEgo MR Avatar")) selected=cameras[index];
            CoTaskMemFree(name); cameras[index]->Release();
        }
        CoTaskMemFree(cameras); if(!selected) return HRESULT_FROM_WIN32(ERROR_NOT_FOUND);
        CHECK(selected->ActivateObject(IID_PPV_ARGS(&source)));
        readerLibrary=LoadLibraryExW(L"mfreadwrite.dll",nullptr,LOAD_LIBRARY_SEARCH_SYSTEM32);
        if(!readerLibrary) return HRESULT_FROM_WIN32(GetLastError());
        auto createReader=reinterpret_cast<decltype(&MFCreateSourceReaderFromMediaSource)>(GetProcAddress(readerLibrary,"MFCreateSourceReaderFromMediaSource"));
        if(!createReader) return HRESULT_FROM_WIN32(ERROR_PROC_NOT_FOUND);
        auto callback=Make<CameraReadCallback>(); if(!callback) return E_OUTOFMEMORY;
        ComPtr<IMFAttributes> settings; CHECK(MFCreateAttributes(&settings,2));
        CHECK(settings->SetUnknown(MF_SOURCE_READER_ASYNC_CALLBACK,callback.Get()));
        CHECK(createReader(source.Get(),settings.Get(),&reader));
        ComPtr<IMFMediaType> requested; CHECK(MFCreateMediaType(&requested));
        CHECK(requested->SetGUID(MF_MT_MAJOR_TYPE,MFMediaType_Video));
        CHECK(requested->SetGUID(MF_MT_SUBTYPE,MFVideoFormat_RGB32));
        CHECK(MFSetAttributeSize(requested.Get(),MF_MT_FRAME_SIZE,WIDTH,HEIGHT));
        CHECK(reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM,nullptr,requested.Get()));
        CHECK(reader->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM,TRUE));
        auto deadline=GetTickCount64()+8000;
        while(GetTickCount64()<deadline) {
            CHECK(reader->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM,0,nullptr,nullptr,nullptr,nullptr));
            auto now=GetTickCount64(); if(now>=deadline) return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
            ComPtr<IMFSample> sample; auto read=callback->Wait(&sample,static_cast<DWORD>(deadline-now)); CHECK(read);
            if(!sample) continue;
            ComPtr<IMFMediaBuffer> buffer; CHECK(sample->ConvertToContiguousBuffer(&buffer));
            BYTE* pixels{}; DWORD length{}; CHECK(buffer->Lock(&pixels,nullptr,&length));
            bool color=length==BYTES && pixels[0]==237 && pixels[1]==149 && pixels[2]==100;
            buffer->Unlock(); if(color) return S_OK;
        }
        return HRESULT_FROM_WIN32(ERROR_TIMEOUT);
    }();
    if(reader) reader->Flush(MF_SOURCE_READER_ALL_STREAMS);
    if(source) source->Shutdown(); reader.Reset(); source.Reset();
    if(readerLibrary) FreeLibrary(readerLibrary);
    EndAvatarCamera(); return result;
}
BOOL WINAPI DllMain(HINSTANCE h,DWORD reason,LPVOID) { if(reason==DLL_PROCESS_ATTACH) { module=h; DisableThreadLibraryCalls(h); } return TRUE; }
