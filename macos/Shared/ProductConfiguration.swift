import Foundation
import CoreMedia
import Darwin

enum ProductConfiguration {
    static let applicationBundleIdentifier = "com.misterslee.alterego.mac"
    static let cameraExtensionBundleIdentifier = "com.misterslee.alterego.mac.camera-extension"
    static let cameraName = "AlterEgo MR Avatar"
    static let deviceIdentifier = UUID(uuidString: "21F01147-2C01-45A4-86CC-F75D17C22EAA")!
    static let sourceStreamIdentifier = UUID(uuidString: "215190F1-932C-4683-B1BC-F640F4D3DA80")!
    static let sinkStreamIdentifier = UUID(uuidString: "C72434AB-A79C-42B4-BB74-127D126FE382")!
    static let videoSizes = [(width: 1920, height: 1080), (width: 1280, height: 720), (width: 960, height: 540)]
    static let frameDuration = CMTime(value: 1, timescale: 30)
    static let maximumFrameAgeNs: UInt64 = 2_000_000_000

    static var appGroupIdentifier: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "AEAppGroup") as? String,
              !value.contains("$("), value.split(separator: ".").first?.count == 10 else { return nil }
        return value
    }

    static var teamIdentifier: String? { appGroupIdentifier?.split(separator: ".").first.map(String.init) }

    private static let timebase: mach_timebase_info_data_t = {
        var result = mach_timebase_info_data_t()
        mach_timebase_info(&result)
        return result
    }()

    /// Unlike process uptime, the continuous clock includes Mac sleep time.
    static func continuousNanoseconds() -> UInt64 {
        let ticks = mach_continuous_time()
        let numerator = UInt64(timebase.numer), denominator = UInt64(timebase.denom)
        return (ticks / denominator) * numerator + ((ticks % denominator) * numerator) / denominator
    }

    static func hostTime() -> (time: CMTime, nanoseconds: UInt64) {
        let time = CMClockGetTime(CMClockGetHostTimeClock())
        let nanoseconds = CMTimeConvertScale(time, timescale: 1_000_000_000, method: .default).value
        return (time, UInt64(max(0, nanoseconds)))
    }

    static func validVideoSize(width: Int, height: Int) -> Bool {
        width >= 320 && height >= 180 && width <= 1920 && height <= 1080 &&
            width.isMultiple(of: 2) && height.isMultiple(of: 2) && width * 9 == height * 16
    }
}
