import Foundation
import CoreMediaIO
import CoreMedia
import CoreVideo
import CoreImage
import CoreGraphics

/// The host-facing name is retained for the receiver interface. Frames are not
/// stored in files: they cross macOS's camera sink queue into extension memory.
/// This avoids sharing foreground-user files with the camera's role account.
final class SharedFrameStore {
    enum Role { case writer }
    enum CameraError: LocalizedError {
        case unavailable
        case operation(String, OSStatus)
        var errorDescription: String? {
            switch self {
            case .unavailable: return "Enable the AlterEgo camera first, then restart the receiver."
            case .operation(let operation, let status): return "The Mac camera could not \(operation) (\(status)). Try enabling the camera again."
            }
        }
    }

    private let lock = NSRecursiveLock()
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private var device: CMIODeviceID = 0
    private var stream: CMIOStreamID = 0
    private var queue: CMSimpleQueue?
    private var pool: CVPixelBufferPool?
    private var format: CMVideoFormatDescription?
    private var nextAutomaticConnectNs: UInt64 = 0

    init(role: Role = .writer) throws {
        // Installation/activation may not have happened yet. connect() gives the
        // caller a useful error and is explicitly retried after enabling camera.
    }

    var isReady: Bool { lock.lock(); defer { lock.unlock() }; return queue != nil && stream != 0 }

    func connect() throws {
        lock.lock(); defer { lock.unlock() }
        if queue != nil { return }
        let found = try Self.findCameraSink()
        var copied: Unmanaged<CMSimpleQueue>?
        let copyStatus = CMIOStreamCopyBufferQueue(found.stream, { _, _, _ in }, nil, &copied)
        guard copyStatus == noErr, let copied else { throw CameraError.operation("open its video queue", copyStatus) }
        let newQueue = copied.takeRetainedValue()
        let startStatus = CMIODeviceStartStream(found.device, found.stream)
        guard startStatus == noErr else { throw CameraError.operation("start its video input", startStatus) }
        do {
            let prepared = try Self.makePool()
            pool = prepared.pool; format = prepared.format
            device = found.device; stream = found.stream; queue = newQueue
            nextAutomaticConnectNs = 0
        } catch {
            CMIODeviceStopStream(found.device, found.stream)
            throw error
        }
    }

    /// Stopping the sink invalidates its frame immediately in the extension.
    /// A later fresh publish reconnects. There are no on-disk personal pixels.
    func clear() { disconnect() }

    func disconnect() {
        lock.lock(); defer { lock.unlock() }
        let oldDevice = device, oldStream = stream
        device = 0; stream = 0; queue = nil; pool = nil; format = nil
        if oldDevice != 0 && oldStream != 0 { CMIODeviceStopStream(oldDevice, oldStream) }
    }

    @discardableResult
    func publish(bgra: Data, width: Int, height: Int, bytesPerRow: Int,
                 stillFresh: () -> Bool = { true }) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard ProductConfiguration.validVideoSize(width: width, height: height), bytesPerRow == width * 4,
              bgra.count == bytesPerRow * height, stillFresh() else { return false }
        if queue == nil {
            let now = ProductConfiguration.continuousNanoseconds()
            guard now >= nextAutomaticConnectNs else { return false }
            do { try connect() } catch { nextAutomaticConnectNs = now + 1_000_000_000; return false }
        }
        guard let queue, let pool, let format, CMSimpleQueueGetCount(queue) < CMSimpleQueueGetCapacity(queue) else { return false }
        var pixelBuffer: CVPixelBuffer?
        let auxiliary = [kCVPixelBufferPoolAllocationThresholdKey: 6] as CFDictionary
        guard CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, pool, auxiliary, &pixelBuffer) == kCVReturnSuccess,
              let pixelBuffer else { return false }
        let input = CIImage(bitmapData: bgra, bytesPerRow: bytesPerRow,
            size: CGSize(width: CGFloat(width), height: CGFloat(height)), format: .BGRA8, colorSpace: colorSpace)
        let normalized = input.transformed(by: CGAffineTransform(scaleX: 1920.0 / CGFloat(width), y: 1080.0 / CGFloat(height)))
        context.render(normalized, to: pixelBuffer, bounds: CGRect(x: 0, y: 0, width: 1920, height: 1080), colorSpace: colorSpace)
        guard stillFresh() else { return false }
        let host = ProductConfiguration.hostTime()
        var timing = CMSampleTimingInfo(duration: ProductConfiguration.frameDuration,
            presentationTimeStamp: host.time, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: pixelBuffer,
            formatDescription: format, sampleTiming: &timing, sampleBufferOut: &sample) == noErr, let sample else { return false }
        // CMIO owns a successful queue insertion. Release our transfer on failure.
        let retained = Unmanaged.passRetained(sample)
        let status = CMSimpleQueueEnqueue(queue, element: retained.toOpaque())
        if status != noErr { retained.release(); return false }
        return true
    }

    deinit { disconnect() }

    private static func makePool() throws -> (pool: CVPixelBufferPool, format: CMVideoFormatDescription) {
        var result: CVPixelBufferPool?
        let attributes: [CFString: Any] = [kCVPixelBufferWidthKey: 1920, kCVPixelBufferHeightKey: 1080,
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary]
        let status = CVPixelBufferPoolCreate(kCFAllocatorDefault,
            [kCVPixelBufferPoolMinimumBufferCountKey: 2] as CFDictionary, attributes as CFDictionary, &result)
        guard status == kCVReturnSuccess, let result else { throw CameraError.operation("allocate video buffers", status) }
        var description: CMVideoFormatDescription?
        let descriptionStatus = CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault,
            codecType: kCVPixelFormatType_32BGRA, width: 1920, height: 1080, extensions: nil, formatDescriptionOut: &description)
        guard descriptionStatus == noErr, let description else { throw CameraError.operation("create its video format", descriptionStatus) }
        return (result, description)
    }

    private static func findCameraSink() throws -> (device: CMIODeviceID, stream: CMIOStreamID) {
        let devices = try objectIDs(CMIOObjectID(kCMIOObjectSystemObject),
            selector: CMIOObjectPropertySelector(kCMIOHardwarePropertyDevices),
            scope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal))
        for device in devices {
            var address = CMIOObjectPropertyAddress(mSelector: CMIOObjectPropertySelector(kCMIODevicePropertyDeviceUID),
                mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
                mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
            var uid: CFString = "" as CFString
            var size = UInt32(MemoryLayout<CFString>.size)
            guard CMIOObjectGetPropertyData(device, &address, 0, nil, size, &size, &uid) == noErr,
                  (uid as String).caseInsensitiveCompare(ProductConfiguration.deviceIdentifier.uuidString) == .orderedSame else { continue }
            let streams = try objectIDs(device, selector: CMIOObjectPropertySelector(kCMIODevicePropertyStreams),
                scope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeOutput))
            if let stream = streams.first { return (device, stream) }
        }
        throw CameraError.unavailable
    }

    private static func objectIDs(_ object: CMIOObjectID, selector: CMIOObjectPropertySelector,
                                  scope: CMIOObjectPropertyScope) throws -> [CMIOObjectID] {
        var address = CMIOObjectPropertyAddress(mSelector: selector, mScope: scope,
            mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
        var size: UInt32 = 0
        let sizeStatus = CMIOObjectGetPropertyDataSize(object, &address, 0, nil, &size)
        guard sizeStatus == noErr else { throw CameraError.operation("find camera devices", sizeStatus) }
        guard size % UInt32(MemoryLayout<CMIOObjectID>.size) == 0, size <= 65536 else { throw CameraError.unavailable }
        var values = [CMIOObjectID](repeating: 0, count: Int(size) / MemoryLayout<CMIOObjectID>.size)
        if values.isEmpty { return [] }
        let status = values.withUnsafeMutableBytes { bytes in
            CMIOObjectGetPropertyData(object, &address, 0, nil, size, &size, bytes.baseAddress!)
        }
        guard status == noErr else { throw CameraError.operation("read camera devices", status) }
        return values
    }
}
