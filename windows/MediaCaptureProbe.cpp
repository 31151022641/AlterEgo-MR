// Read-only compatibility test for the same WinRT capture route used by Camera.
// Opens only AlterEgo's existing session camera. Saves no images or recordings.
#include <windows.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Devices.Enumeration.h>
#include <winrt/Windows.Media.Capture.h>
#include <winrt/Windows.Media.Capture.Frames.h>
#include <winrt/Windows.Media.MediaProperties.h>
#include <winrt/Windows.Graphics.Imaging.h>
#include <cstdio>
#include <atomic>
#include <memory>
#include <mutex>
using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Devices::Enumeration;
using namespace Windows::Media::Capture;
using namespace Windows::Media::Capture::Frames;

template<class Operation> void waitFor(Operation const& operation) {
    const auto deadline=GetTickCount64()+10000;
    while(operation.Status()==AsyncStatus::Started && GetTickCount64()<deadline) Sleep(20);
    if(operation.Status()==AsyncStatus::Started) { operation.Cancel(); throw hresult_error(HRESULT_FROM_WIN32(ERROR_TIMEOUT)); }
}

int main() {
    const char* stage="enumerate";
    try {
        init_apartment(apartment_type::multi_threaded);
        auto enumeration=DeviceInformation::FindAllAsync(DeviceClass::VideoCapture);
        waitFor(enumeration);
        DeviceInformation selected{nullptr};
        for(auto const& device:enumeration.GetResults())
            if(wcsstr(device.Name().c_str(),L"AlterEgo MR Avatar")) {
                if(selected) throw hresult_error(E_UNEXPECTED,L"More than one AlterEgo camera");
                selected=device;
            }
        if(!selected) throw hresult_error(HRESULT_FROM_WIN32(ERROR_NOT_FOUND));
        MediaCapture capture;
        capture.Failed([](auto const&,MediaCaptureFailedEventArgs const& error) {
            printf("CAPTURE_FAILED code=0x%08X\n",error.Code()); fflush(stdout);
        });
        MediaCaptureInitializationSettings settings;
        settings.VideoDeviceId(selected.Id());
        settings.StreamingCaptureMode(StreamingCaptureMode::Video);
        settings.SharingMode(MediaCaptureSharingMode::SharedReadOnly);
        settings.MemoryPreference(MediaCaptureMemoryPreference::Cpu);
        stage="initialize"; auto initialization=capture.InitializeAsync(settings);
        waitFor(initialization); initialization.GetResults();
        printf("MEDIACAPTURE_INITIALIZED\n"); fflush(stdout);
        MediaFrameSource color{nullptr};
        for(auto const& entry:capture.FrameSources()) {
            auto source=entry.Value(); auto info=source.Info();
            printf("SOURCE kind=%d stream=%d formats=%u\n",int(info.SourceKind()),int(info.MediaStreamType()),source.SupportedFormats().Size());
            if(info.SourceKind()==MediaFrameSourceKind::Color) color=source;
        }
        if(!color) throw hresult_error(HRESULT_FROM_WIN32(ERROR_NOT_FOUND));
        for(auto const& format:color.SupportedFormats()) {
            printf("FORMAT %ls %ux%u %u/%u\n",format.Subtype().c_str(),format.VideoFormat().Width(),format.VideoFormat().Height(),
                   format.FrameRate().Numerator(),format.FrameRate().Denominator());
        }
        fflush(stdout);
        stage="create_reader";
        auto creation=capture.CreateFrameReaderAsync(color);
        waitFor(creation); auto reader=creation.GetResults();
        struct Observations { std::atomic<int> count{0},monotonic{0},fullHd{0}; int64_t last=0; std::mutex gate; };
        auto report=std::make_shared<Observations>();
        auto subscription=reader.FrameArrived([report](MediaFrameReader const& sender,auto const&) {
            std::lock_guard<std::mutex> lock(report->gate);
            auto frame=sender.TryAcquireLatestFrame(); if(!frame) return;
            auto timestamp=frame.SystemRelativeTime();
            if(timestamp) { auto now=timestamp.Value().count(); if(report->last && now>report->last) ++report->monotonic; report->last=now; }
            auto video=frame.VideoMediaFrame();
            if(video && video.SoftwareBitmap()) {
                auto bitmap=video.SoftwareBitmap();
                if(bitmap.PixelWidth()==1920 && bitmap.PixelHeight()==1080) ++report->fullHd;
                if(report->count==0) printf("FIRST_FRAME %dx%d pixel_format=%d\n",bitmap.PixelWidth(),bitmap.PixelHeight(),int(bitmap.BitmapPixelFormat()));
                ++report->count;
            }
            frame.Close();
        });
        stage="start_reader"; auto starting=reader.StartAsync(); waitFor(starting);
        auto started=starting.GetResults(); printf("READER_START status=%d\n",int(started)); fflush(stdout);
        if(started!=MediaFrameReaderStartStatus::Success) throw hresult_error(E_FAIL);
        stage="frames";
        auto deadline=GetTickCount64()+8000;
        while(report->count<8 && GetTickCount64()<deadline) Sleep(25);
        auto stopping=reader.StopAsync(); waitFor(stopping); stopping.GetResults();
        reader.FrameArrived(subscription);
        reader.Close(); capture.Close();
        printf("MEDIACAPTURE_FRAMES count=%d monotonic=%d fullHd=%d\n",report->count.load(),report->monotonic.load(),report->fullHd.load());
        return report->count>=8 && report->fullHd>=8 && report->monotonic>=7?0:2;
    } catch(hresult_error const& error) {
        printf("MEDIACAPTURE_ERROR stage=%s HRESULT=0x%08X\n",stage,unsigned(error.code()));
        return 1;
    }
}
