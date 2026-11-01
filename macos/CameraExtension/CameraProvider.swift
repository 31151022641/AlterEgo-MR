import Foundation
import CoreMediaIO
import CoreMedia
import CoreVideo
import CoreImage
import CoreGraphics
import CoreAudio
import Security

/// A native source/sink camera pair. The signed host supplies frames through
/// CMIO's bounded sink queue; AVFoundation clients see only the source camera.
final class AvatarCameraProviderSource: NSObject, CMIOExtensionProviderSource {
    private(set) var provider: CMIOExtensionProvider!
    private var camera: AvatarCameraDeviceSource!

    override init() {
        super.init()
        provider = CMIOExtensionProvider(source: self, clientQueue: DispatchQueue(label: "com.misterslee.alterego.camera.clients"))
        do {
            camera = try AvatarCameraDeviceSource(name: ProductConfiguration.cameraName)
            try provider.addDevice(camera.device)
        } catch { fatalError("Unable to initialize the AlterEgo camera: \(error)") }
    }

    var availableProperties: Set<CMIOExtensionProperty> { [.providerName, .providerManufacturer] }
    func providerProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionProviderProperties {
        let result = CMIOExtensionProviderProperties(dictionary: [:])
        if properties.contains(.providerName) { result.name = "AlterEgo MR Webcam" }
        if properties.contains(.providerManufacturer) { result.manufacturer = "MistersLee" }
        return result
    }
    func setProviderProperties(_ properties: CMIOExtensionProviderProperties) throws { }
    func connect(to client: CMIOExtensionClient) throws { }
    func disconnect(from client: CMIOExtensionClient) { camera.disconnect(client) }
}

private final class AvatarCameraDeviceSource: NSObject, CMIOExtensionDeviceSource {
    private(set) var device: CMIOExtensionDevice!
    private let hub = AvatarFrameHub()
    private var source: AvatarCameraStreamSource!
    private var sink: AvatarCameraStreamSource!

    init(name: String) throws {
        super.init()
        device = CMIOExtensionDevice(localizedName: name,
            deviceID: ProductConfiguration.deviceIdentifier, legacyDeviceID: nil, source: self)
        source = try AvatarCameraStreamSource(direction: .source, hub: hub)
        sink = try AvatarCameraStreamSource(direction: .sink, hub: hub)
        hub.source = source; hub.sink = sink
        try device.addStream(source.stream)
        try device.addStream(sink.stream)
    }

    var availableProperties: Set<CMIOExtensionProperty> { [.deviceModel, .deviceTransportType, .deviceIsSuspended] }
    func deviceProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionDeviceProperties {
        let result = CMIOExtensionDeviceProperties(dictionary: [:])
        if properties.contains(.deviceModel) { result.model = "AlterEgo MR Avatar Camera" }
        if properties.contains(.deviceTransportType) { result.transportType = Int(kAudioDeviceTransportTypeVirtual) }
        if properties.contains(.deviceIsSuspended) { result.suspended = false }
        return result
    }
    func setDeviceProperties(_ properties: CMIOExtensionDeviceProperties) throws { }
    func disconnect(_ client: CMIOExtensionClient) { hub.disconnect(client) }
}

private final class AvatarCameraStreamSource: NSObject, CMIOExtensionStreamSource {
    private(set) var stream: CMIOExtensionStream!
    let formats: [CMIOExtensionStreamFormat]
    let pools: [CVPixelBufferPool]
    let dimensions: [(width: Int, height: Int)]
    private let direction: CMIOExtensionStream.Direction
    private let hub: AvatarFrameHub
    private let stateLock = NSLock()
    private var formatIndex = 0
    private var approvedClient: CMIOExtensionClient?

    init(direction: CMIOExtensionStream.Direction, hub: AvatarFrameHub) throws {
        self.direction = direction; self.hub = hub
        dimensions = direction == .source ? ProductConfiguration.videoSizes : [(width: 1920, height: 1080)]
        var descriptions = [CMIOExtensionStreamFormat](), buffers = [CVPixelBufferPool]()
        for size in dimensions {
            var format: CMVideoFormatDescription?
            let result = CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault,
                codecType: kCVPixelFormatType_32BGRA, width: Int32(size.width), height: Int32(size.height),
                extensions: nil, formatDescriptionOut: &format)
            guard result == noErr, let format else { throw Self.error("Unable to create camera format", result) }
            descriptions.append(CMIOExtensionStreamFormat(formatDescription: format,
                maxFrameDuration: ProductConfiguration.frameDuration, minFrameDuration: ProductConfiguration.frameDuration,
                validFrameDurations: nil))
            var pool: CVPixelBufferPool?
            let attributes: [CFString: Any] = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: size.width, kCVPixelBufferHeightKey: size.height,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary]
            let status = CVPixelBufferPoolCreate(kCFAllocatorDefault,
                [kCVPixelBufferPoolMinimumBufferCountKey: 3] as CFDictionary, attributes as CFDictionary, &pool)
            guard status == kCVReturnSuccess, let pool else { throw Self.error("Unable to create camera buffers", status) }
            buffers.append(pool)
        }
        formats = descriptions; pools = buffers
        super.init()
        stream = CMIOExtensionStream(localizedName: direction == .source ? ProductConfiguration.cameraName : "AlterEgo Host Video Input",
            streamID: direction == .source ? ProductConfiguration.sourceStreamIdentifier : ProductConfiguration.sinkStreamIdentifier,
            direction: direction, clockType: .hostTime, source: self)
    }

    var activeFormatIndex: Int { stateLock.lock(); defer { stateLock.unlock() }; return formatIndex }
    var availableProperties: Set<CMIOExtensionProperty> {
        var result: Set<CMIOExtensionProperty> = [.streamActiveFormatIndex, .streamFrameDuration, .streamMaxFrameDuration]
        if direction == .sink { result.formUnion([.streamSinkBufferQueueSize, .streamSinkBuffersRequiredForStartup,
            .streamSinkBufferUnderrunCount, .streamSinkEndOfData]) }
        return result
    }

    func streamProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionStreamProperties {
        let result = CMIOExtensionStreamProperties(dictionary: [:])
        if properties.contains(.streamActiveFormatIndex) { result.activeFormatIndex = activeFormatIndex }
        if properties.contains(.streamFrameDuration) { result.frameDuration = ProductConfiguration.frameDuration }
        if properties.contains(.streamMaxFrameDuration) { result.maxFrameDuration = ProductConfiguration.frameDuration }
        if direction == .sink {
            if properties.contains(.streamSinkBufferQueueSize) { result.sinkBufferQueueSize = 2 }
            if properties.contains(.streamSinkBuffersRequiredForStartup) { result.sinkBuffersRequiredForStartup = 1 }
            if properties.contains(.streamSinkBufferUnderrunCount) { result.sinkBufferUnderrunCount = 0 }
            if properties.contains(.streamSinkEndOfData) { result.sinkEndOfData = 0 }
        }
        return result
    }

    func setStreamProperties(_ properties: CMIOExtensionStreamProperties) throws {
        if let index = properties.activeFormatIndex {
            guard formats.indices.contains(index) else { throw Self.error("Unsupported camera size", -1) }
            stateLock.lock(); formatIndex = index; stateLock.unlock()
        }
        for duration in [properties.frameDuration, properties.maxFrameDuration].compactMap({ $0 }) {
            guard CMTimeCompare(duration, ProductConfiguration.frameDuration) == 0 else {
                throw Self.error("The camera uses 30 sample frames per second", -1)
            }
        }
    }

    func authorizedToStartStream(for client: CMIOExtensionClient) -> Bool {
        if direction == .source { return true } // macOS enforces each camera client's consent.
        guard client.signingID == ProductConfiguration.applicationBundleIdentifier,
              Self.matchesHostTeam(client) else { return false }
        stateLock.lock(); defer { stateLock.unlock() }
        if let approvedClient, approvedClient.clientID != client.clientID { return false }
        approvedClient = client
        return true
    }

    func startStream() throws {
        if direction == .source { hub.startSource() }
        else {
            stateLock.lock(); let client = approvedClient; stateLock.unlock()
            guard let client else { throw Self.error("Only the signed AlterEgo app may supply video", -1) }
            hub.startSink(client)
        }
    }

    func stopStream() throws {
        if direction == .source { hub.stopSource() }
        else {
            stateLock.lock(); approvedClient = nil; stateLock.unlock()
            hub.stopSink()
        }
    }

    private static func matchesHostTeam(_ client: CMIOExtensionClient) -> Bool {
        guard let expectedTeam = ProductConfiguration.teamIdentifier,
              expectedTeam.range(of: "^[A-Z0-9]{10}$", options: .regularExpression) != nil else { return false }
        var code: SecCode?
        let attributes = [kSecGuestAttributePid as String: NSNumber(value: client.pid)] as CFDictionary
        guard SecCodeCopyGuestWithAttributes(nil, attributes, [], &code) == errSecSuccess, let code else { return false }
        // Validate the running code directly. CopySigningInformation expects a
        // SecStaticCode and would inspect a potentially replaced on-disk image.
        let expression = "anchor apple generic and identifier \"\(ProductConfiguration.applicationBundleIdentifier)\" and certificate leaf[subject.OU] = \"\(expectedTeam)\""
        var requirement: SecRequirement?
        guard SecRequirementCreateWithString(expression as CFString, [], &requirement) == errSecSuccess,
              let requirement else { return false }
        return SecCodeCheckValidity(code, [], requirement) == errSecSuccess
    }

    private static func error(_ message: String, _ code: OSStatus) -> NSError {
        NSError(domain: "com.misterslee.alterego.camera", code: Int(code), userInfo: [NSLocalizedDescriptionKey: message])
    }
}

private final class AvatarFrameHub {
    weak var source: AvatarCameraStreamSource?
    weak var sink: AvatarCameraStreamSource?
    private let work = DispatchQueue(label: "com.misterslee.alterego.camera.frames", qos: .userInteractive)
    private let context = CIContext(options: [.cacheIntermediates: false])
    private let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    private var outputTimer: DispatchSourceTimer?
    private var inputTimer: DispatchSourceTimer?
    private var sourceUsers = 0
    private var client: CMIOExtensionClient?
    private var generation: UInt64 = 0
    private var consuming = false
    private var latest: CVPixelBuffer?
    private var receivedAtNs: UInt64 = 0
    private var previousContinuousNs = ProductConfiguration.continuousNanoseconds()
    private var previousHostNs = ProductConfiguration.hostTime().nanoseconds
    private var minimumInputHostNs: UInt64 = 0

    func startSource() { work.async { [self] in
        sourceUsers += 1
        guard outputTimer == nil else { return }
        outputTimer = timer { [weak self] in self?.output() }
    } }

    func stopSource() { work.async { [self] in
        sourceUsers = max(0, sourceUsers - 1)
        if sourceUsers == 0 { outputTimer?.cancel(); outputTimer = nil }
    } }

    func startSink(_ value: CMIOExtensionClient) { work.async { [self] in
        guard client == nil else { return }
        generation &+= 1; client = value; consuming = false; clearFrame()
        minimumInputHostNs = ProductConfiguration.hostTime().nanoseconds
        inputTimer = timer { [weak self] in self?.consume(remaining: 2) }
    } }

    func stopSink() { work.async { [self] in stopSinkOnQueue() } }
    func disconnect(_ value: CMIOExtensionClient) { work.async { [self] in
        if client?.clientID == value.clientID { stopSinkOnQueue() }
    } }

    private func stopSinkOnQueue() {
        generation &+= 1; client = nil; consuming = false
        inputTimer?.cancel(); inputTimer = nil
        clearFrame()
    }

    private func timer(_ action: @escaping () -> Void) -> DispatchSourceTimer {
        let result = DispatchSource.makeTimerSource(queue: work)
        result.schedule(deadline: .now(), repeating: .nanoseconds(33_333_333), leeway: .milliseconds(1))
        result.setEventHandler(handler: action); result.resume()
        return result
    }

    private func observeSleep() {
        let continuous = ProductConfiguration.continuousNanoseconds(), host = ProductConfiguration.hostTime().nanoseconds
        if continuous >= previousContinuousNs, host >= previousHostNs,
           continuous - previousContinuousNs > host - previousHostNs + 1_000_000_000 {
            // Reject queued pre-sleep samples as well as cached output.
            clearFrame(); minimumInputHostNs = host
        }
        previousContinuousNs = continuous; previousHostNs = host
    }

    private func consume(remaining: Int) {
        observeSleep()
        guard !consuming, let client, let sink else { return }
        consuming = true
        let currentGeneration = generation
        sink.stream.consumeSampleBuffer(from: client) { [weak self] sample, sequence, _, hasMore, _ in
            guard let self else { return }
            self.work.async { [self] in
                guard generation == currentGeneration else { return }
                consuming = false
                if let sample, let image = CMSampleBufferGetImageBuffer(sample) {
                    let presentation = CMTimeConvertScale(CMSampleBufferGetPresentationTimeStamp(sample),
                        timescale: 1_000_000_000, method: .default)
                    let now = ProductConfiguration.hostTime().nanoseconds
                    if presentation.isValid, presentation.value > 0,
                       UInt64(presentation.value) >= minimumInputHostNs, UInt64(presentation.value) <= now,
                       now - UInt64(presentation.value) <= ProductConfiguration.maximumFrameAgeNs,
                       CVPixelBufferGetWidth(image) == 1920, CVPixelBufferGetHeight(image) == 1080,
                       CVPixelBufferGetPixelFormatType(image) == kCVPixelFormatType_32BGRA {
                        latest = image
                        // Include queue age; receiving an almost-expired image
                        // must not grant it another two seconds of visibility.
                        let continuous = ProductConfiguration.continuousNanoseconds()
                        let age = now - UInt64(presentation.value)
                        receivedAtNs = continuous >= age ? continuous - age : 0
                    }
                    sink.stream.notifyScheduledOutputChanged(CMIOExtensionScheduledOutput(sequenceNumber: sequence,
                        hostTimeInNanoseconds: now))
                }
                if hasMore && remaining > 1 { consume(remaining: remaining - 1) }
            }
        }
    }

    private func output() {
        observeSleep()
        guard let source else { return }
        let now = ProductConfiguration.continuousNanoseconds()
        if receivedAtNs == 0 || now < receivedAtNs || now - receivedAtNs > ProductConfiguration.maximumFrameAgeNs { clearFrame() }
        let index = source.activeFormatIndex, size = source.dimensions[index]
        var output: CVPixelBuffer?
        let auxiliary = [kCVPixelBufferPoolAllocationThresholdKey: 8] as CFDictionary
        guard CVPixelBufferPoolCreatePixelBufferWithAuxAttributes(kCFAllocatorDefault, source.pools[index], auxiliary, &output) == kCVReturnSuccess,
              let output else { return }
        let bounds = CGRect(x: 0, y: 0, width: CGFloat(size.width), height: CGFloat(size.height))
        let image: CIImage
        if let latest {
            image = CIImage(cvPixelBuffer: latest).transformed(by: CGAffineTransform(
                scaleX: CGFloat(size.width) / 1920, y: CGFloat(size.height) / 1080))
        } else {
            image = CIImage(color: CIColor(red: 0.055, green: 0.075, blue: 0.10)).cropped(to: bounds)
        }
        context.render(image, to: output, bounds: bounds, colorSpace: colorSpace)
        let host = ProductConfiguration.hostTime()
        var timing = CMSampleTimingInfo(duration: ProductConfiguration.frameDuration,
            presentationTimeStamp: host.time, decodeTimeStamp: .invalid)
        var sample: CMSampleBuffer?
        guard CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: output,
            formatDescription: source.formats[index].formatDescription, sampleTiming: &timing, sampleBufferOut: &sample) == noErr,
              let sample else { return }
        source.stream.send(sample, discontinuity: [], hostTimeInNanoseconds: host.nanoseconds)
    }

    private func clearFrame() { latest = nil; receivedAtNs = 0 }
}
