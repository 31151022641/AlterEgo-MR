import AppKit
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// An owned, tightly packed, opaque BGRA image. Preview and camera publication
/// consume the same processed pixels; the compositor never uploads images.
struct ProcessedFrame {
    let bgra: Data
    let width: Int
    let height: Int

    func makeCGImage() -> CGImage? {
        guard width > 0, height > 0, width <= 1920, height <= 1080,
              bgra.count == width * height * 4,
              let provider = CGDataProvider(data: bgra as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8,
            bitsPerPixel: 32, bytesPerRow: width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGBitmapInfo(rawValue: CGBitmapInfo.byteOrder32Little.rawValue |
                CGImageAlphaInfo.premultipliedFirst.rawValue), provider: provider,
            decode: nil, shouldInterpolate: false, intent: .defaultIntent)
    }

    func makeNSImage() -> NSImage? {
        guard let image = makeCGImage() else { return nil }
        return NSImage(cgImage: image, size: NSSize(width: CGFloat(width), height: CGFloat(height)))
    }
}

/// Border-connected plain-backdrop replacement, matching the Windows receiver.
/// This is deliberately not person segmentation: uncertain corners keep the
/// original video, and enclosed details matching the backdrop remain foreground.
final class AvatarBackground {
    enum Style: String, Codable, CaseIterable, Identifiable {
        case original, darkStudio, lightStudio, colour, picture, greenScreen
        var id: String { rawValue }
        var title: String {
            switch self {
            case .original: return "Original"
            case .darkStudio: return "Dark studio"
            case .lightStudio: return "Light studio"
            case .colour: return "Custom colour"
            case .picture: return "My picture"
            case .greenScreen: return "Green screen"
            }
        }
    }

    struct Settings: Codable, Equatable {
        var style: Style = .original
        var colourHex: String = "#26405C"
        var strength: Int = 26
    }

    enum ImageError: LocalizedError, Equatable {
        case invalidSettings, pictureNotChosen, fileTooLarge, unsupportedImage
        case imageTooLarge, invalidVideoSize, decodeFailed, saveFailed
        var errorDescription: String? {
            switch self {
            case .invalidSettings: return "Choose a valid colour and an edge strength from 12 to 60."
            case .pictureNotChosen: return "Choose a PNG or JPG picture first."
            case .fileTooLarge: return "Choose a PNG or JPG picture up to 16 MB."
            case .unsupportedImage: return "Choose a valid, single-image PNG or JPG picture."
            case .imageTooLarge: return "Use a picture up to 8192 pixels per side and 32 million pixels."
            case .invalidVideoSize: return "The avatar image dimensions are invalid."
            case .decodeFailed: return "The image could not be decoded."
            case .saveFailed: return "The background choice could not be saved on this Mac."
            }
        }
    }

    private let lock = NSLock()
    private let statusLock = NSLock()
    private let stateDirectory: URL
    private var selected = Settings()
    private var statusValue = "Original avatar background"
    private var status: String {
        get { statusLock.lock(); defer { statusLock.unlock() }; return statusValue }
        set { statusLock.lock(); statusValue = newValue; statusLock.unlock() }
    }
    private var photo: CGImage?
    private var revision = 0
    private var cacheRevision = -1
    private var cacheWidth = 0
    private var cacheHeight = 0
    private var backdrop = Data()
    private var distance = [UInt8]()
    private var connected = [UInt8]()
    private var queue = [Int32]()

    var settings: Settings { lock.lock(); defer { lock.unlock() }; return selected }
    // The main preview timer must not wait behind a full-resolution matte pass.
    var notice: String { status }
    var hasPicture: Bool { lock.lock(); defer { lock.unlock() }; return photo != nil }

    init(stateDirectory: URL) {
        self.stateDirectory = stateDirectory
        do {
            let settingsURL = stateDirectory.appendingPathComponent("background.json")
            if FileManager.default.fileExists(atPath: settingsURL.path) {
                let bytes = try Self.readBounded(settingsURL, limit: 4096)
                selected = try Self.validate(JSONDecoder().decode(Settings.self, from: bytes))
            }
            let pictureURL = stateDirectory.appendingPathComponent("background.png")
            if FileManager.default.fileExists(atPath: pictureURL.path) {
                photo = try Self.decodePicture(Self.readBounded(pictureURL, limit: Self.pictureLimit))
            }
            if selected.style == .picture && photo == nil { selected.style = .original }
            status = selected.style == .original ? "Original avatar background" : "Saved background restored"
        } catch {
            selected = Settings()
            status = "Original background restored; choose a background again if needed"
        }
    }

    func updateSettings(_ value: Settings) throws {
        let normalized = try Self.validate(value)
        lock.lock(); defer { lock.unlock() }
        if normalized.style == .picture && photo == nil { throw ImageError.pictureNotChosen }
        try save(normalized)
        selected = normalized; revision += 1
        status = selected.style == .original ? "Original avatar background" : "Background applies to preview and webcam"
    }

    /// The selected file is read once, bounded before decode, orientation-corrected,
    /// normalized and copied privately. Its original location need not remain available.
    func choosePicture(_ url: URL) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let loaded = try Self.decodePicture(Self.readBounded(url, limit: Self.pictureLimit))
        let normalized = try Self.rasterize(loaded, width: 1920, height: 1080, cover: true)
        guard let image = normalized.makeCGImage() else { throw ImageError.decodeFailed }
        let encoded = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(encoded, UTType.png.identifier as CFString, 1, nil) else {
            throw ImageError.saveFailed
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw ImageError.saveFailed }
        lock.lock(); defer { lock.unlock() }
        try ensureDirectory()
        let pictureURL = stateDirectory.appendingPathComponent("background.png")
        try (encoded as Data).write(to: pictureURL, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: pictureURL.path)
        var next = selected; next.style = .picture
        try save(next)
        selected = next; photo = image; revision += 1
        status = "Picture background saved on this Mac"
    }

    /// Called on the receiver's processing queue. A nil result means the source
    /// expired while processing, and must not receive an accepted-frame receipt.
    func process(jpeg: Data, width: Int, height: Int, fresh: () -> Bool = { true }) throws -> ProcessedFrame? {
        guard Self.validVideoSize(width, height), jpeg.count <= 8 * 1024 * 1024,
              let size = Self.jpegSize(jpeg), size.width == width, size.height == height,
              jpeg.suffix(2).elementsEqual([0xff, 0xd9]) else {
            throw ImageError.invalidVideoSize
        }
        guard fresh() else { return nil }
        let source = try Self.imageSource(jpeg, expected: size, png: false)
        guard let image = CGImageSourceCreateImageAtIndex(source, 0,
            [kCGImageSourceShouldCacheImmediately: true] as CFDictionary),
              image.width == width, image.height == height else { throw ImageError.decodeFailed }
        guard fresh() else { return nil }
        let decoded = try Self.rasterize(image, width: width, height: height, cover: false)
        return try processBGRA(decoded, fresh: fresh)
    }

    /// Also used by synthetic pixel regressions without relying on JPEG noise.
    func processBGRA(_ source: ProcessedFrame, fresh: () -> Bool = { true }) throws -> ProcessedFrame? {
        guard Self.validVideoSize(source.width, source.height),
              source.bgra.count == source.width * source.height * 4 else { throw ImageError.invalidVideoSize }
        guard fresh() else { return nil }
        lock.lock(); defer { lock.unlock() }
        if selected.style == .original {
            status = "Original avatar background"
            return fresh() ? source : nil
        }
        try ensureBackdrop(width: source.width, height: source.height)
        let result = applyMatte(source)
        return fresh() ? result : nil
    }

    private func applyMatte(_ source: ProcessedFrame) -> ProcessedFrame {
        let width = source.width, height = source.height, size = width * height
        var output = Data(count: size * 4)
        let replaced: Bool = source.bgra.withUnsafeBytes { inputBytes in
            let input = inputBytes.bindMemory(to: UInt8.self)
            var corners = [Self.corner(input, width, height, false, false),
                Self.corner(input, width, height, true, false),
                Self.corner(input, width, height, false, true),
                Self.corner(input, width, height, true, true)]
            guard Self.repairCorners(&corners, maximumSpread: max(24, selected.strength)) else { return false }
            let tl = corners[0].colour, tr = corners[1].colour, bl = corners[2].colour, br = corners[3].colour
            let inner = max(4, selected.strength - 12), outer = selected.strength + 12
            connected.withUnsafeMutableBufferPointer { $0.initialize(repeating: 0) }
            for y in 0..<height {
                let t = Float(y) / Float(height - 1)
                var blue = Float(tl[0]) + Float(bl[0] - tl[0]) * t
                var green = Float(tl[1]) + Float(bl[1] - tl[1]) * t
                var red = Float(tl[2]) + Float(bl[2] - tl[2]) * t
                let blueStep = (Float(tr[0]) + Float(br[0] - tr[0]) * t - blue) / Float(width - 1)
                let greenStep = (Float(tr[1]) + Float(br[1] - tr[1]) * t - green) / Float(width - 1)
                let redStep = (Float(tr[2]) + Float(br[2] - tr[2]) * t - red) / Float(width - 1)
                for x in 0..<width {
                    let pixel = (y * width + x) * 4
                    let db = abs(Int(input[pixel]) - Int(blue))
                    let dg = abs(Int(input[pixel + 1]) - Int(green))
                    let dr = abs(Int(input[pixel + 2]) - Int(red))
                    distance[y * width + x] = UInt8(min(255, max(db, max(dg, dr))))
                    blue += blueStep; green += greenStep; red += redStep
                }
            }
            var tail = 0
            func seed(_ index: Int) {
                if connected[index] == 0 && Int(distance[index]) < outer {
                    connected[index] = 1; queue[tail] = Int32(index); tail += 1
                }
            }
            for x in 0..<width { seed(x); seed((height - 1) * width + x) }
            for y in 1..<(height - 1) { seed(y * width); seed(y * width + width - 1) }
            var head = 0
            while head < tail {
                let index = Int(queue[head]), x = index % width
                if x > 0 { seed(index - 1) }; if x < width - 1 { seed(index + 1) }
                if index >= width { seed(index - width) }; if index < size - width { seed(index + width) }
                head += 1
            }
            var alpha = [Int](repeating: 0, count: 256)
            for value in 0..<256 {
                let t = max(0, min(1, Float(value - inner) / Float(outer - inner)))
                alpha[value] = Int(255 * t * t * (3 - 2 * t) + 0.5)
            }
            backdrop.withUnsafeBytes { backgroundBytes in
                let background = backgroundBytes.bindMemory(to: UInt8.self)
                output.withUnsafeMutableBytes { outputBytes in
                    let result = outputBytes.bindMemory(to: UInt8.self)
                    for index in 0..<size {
                        let foreground = connected[index] != 0 ? alpha[Int(distance[index])] : 255
                        let pixel = index * 4
                        for channel in 0..<3 {
                            result[pixel + channel] = UInt8((Int(input[pixel + channel]) * foreground +
                                Int(background[pixel + channel]) * (255 - foreground) + 127) / 255)
                        }
                        result[pixel + 3] = 255
                    }
                }
            }
            return true
        }
        if !replaced {
            status = "Backdrop is not plain at the corners. Original video retained; try a plainer backdrop."
            return source
        }
        status = "Plain backdrop removed locally · preview and webcam match"
        return ProcessedFrame(bgra: output, width: width, height: height)
    }

    private struct Corner { var colour: [Int]; let spread: Int }
    private static func corner(_ data: UnsafeBufferPointer<UInt8>, _ width: Int, _ height: Int,
                               _ right: Bool, _ bottom: Bool) -> Corner {
        var channels = [[Int]](repeating: [], count: 3)
        for y in 0..<9 { for x in 0..<9 {
            var px = min(width - 1, 1 + x * max(1, width / 220))
            var py = min(height - 1, 1 + y * max(1, height / 220))
            if right { px = width - 1 - px }; if bottom { py = height - 1 - py }
            for c in 0..<3 { channels[c].append(Int(data[(py * width + px) * 4 + c])) }
        } }
        for c in 0..<3 { channels[c].sort() }
        return Corner(colour: channels.map { $0[40] }, spread: channels.map { $0[72] - $0[8] }.max()!)
    }

    private static func colourDistance(_ a: [Int], _ b: [Int]) -> Int {
        max(abs(a[0] - b[0]), max(abs(a[1] - b[1]), abs(a[2] - b[2])))
    }

    private static func repairCorners(_ corners: inout [Corner], maximumSpread: Int) -> Bool {
        var best = -1, agreeing = [Int]()
        for candidate in 0..<4 where corners[candidate].spread <= maximumSpread {
            let matches = (0..<4).filter { corners[$0].spread <= maximumSpread &&
                colourDistance(corners[candidate].colour, corners[$0].colour) <= 72 }
            if matches.count > agreeing.count { best = candidate; agreeing = matches }
        }
        guard best >= 0, agreeing.count >= 2 else { return false }
        let anchor = corners[best].colour
        let reference = (0..<3).map { channel -> Int in
            let values = agreeing.map { corners[$0].colour[channel] }.sorted()
            return values[values.count / 2]
        }
        for index in 0..<4 where corners[index].spread > maximumSpread || colourDistance(anchor, corners[index].colour) > 72 {
            corners[index].colour = reference
        }
        return true
    }

    private func ensureBackdrop(width: Int, height: Int) throws {
        if cacheWidth == width && cacheHeight == height && cacheRevision == revision { return }
        if selected.style == .picture {
            guard let photo else { throw ImageError.pictureNotChosen }
            backdrop = try Self.rasterize(photo, width: width, height: height, cover: true).bgra
        } else {
            let top: [UInt8], bottom: [UInt8]
            switch selected.style {
            case .darkStudio: top = [87, 65, 42]; bottom = [34, 20, 11]
            case .lightStudio: top = [249, 244, 239]; bottom = [215, 199, 181]
            case .greenScreen: top = [0, 255, 0]; bottom = top
            default:
                let rgb = UInt32(selected.colourHex.dropFirst(), radix: 16)!
                top = [UInt8(rgb & 255), UInt8((rgb >> 8) & 255), UInt8((rgb >> 16) & 255)]
                bottom = top
            }
            backdrop = Data(count: width * height * 4)
            backdrop.withUnsafeMutableBytes { bytes in
                let pixels = bytes.bindMemory(to: UInt8.self)
                for y in 0..<height {
                    let t = Float(y) / Float(max(1, height - 1))
                    let colour = (0..<3).map { UInt8(max(0, min(255, Int(Float(top[$0]) + Float(Int(bottom[$0]) - Int(top[$0])) * t + 0.5)))) }
                    for x in 0..<width {
                        let offset = (y * width + x) * 4
                        pixels[offset] = colour[0]; pixels[offset + 1] = colour[1]
                        pixels[offset + 2] = colour[2]; pixels[offset + 3] = 255
                    }
                }
            }
        }
        cacheWidth = width; cacheHeight = height; cacheRevision = revision
        if distance.count != width * height {
            distance = [UInt8](repeating: 0, count: width * height)
            connected = [UInt8](repeating: 0, count: width * height)
            queue = [Int32](repeating: 0, count: width * height)
        }
    }

    private static func validate(_ value: Settings) throws -> Settings {
        guard (12...60).contains(value.strength), value.colourHex.count == 7,
              value.colourHex.first == "#", UInt32(value.colourHex.dropFirst(), radix: 16) != nil else {
            throw ImageError.invalidSettings
        }
        var normalized = value; normalized.colourHex = value.colourHex.uppercased()
        return normalized
    }

    private func ensureDirectory() throws {
        try FileManager.default.createDirectory(at: stateDirectory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
    }

    private func save(_ value: Settings) throws {
        try ensureDirectory()
        let url = stateDirectory.appendingPathComponent("background.json")
        try JSONEncoder().encode(value).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private static let pictureLimit = 16 * 1024 * 1024
    private static func readBounded(_ url: URL, limit: Int) throws -> Data {
        guard try url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile == true else {
            throw ImageError.unsupportedImage
        }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        var bytes = Data()
        while bytes.count <= limit {
            let chunk = try file.read(upToCount: min(65_536, limit + 1 - bytes.count)) ?? Data()
            if chunk.isEmpty { break }
            bytes.append(chunk)
        }
        guard !bytes.isEmpty, bytes.count <= limit else { throw ImageError.fileTooLarge }
        return bytes
    }

    struct Dimensions: Equatable { let width: Int; let height: Int }
    static func validVideoSize(_ width: Int, _ height: Int) -> Bool {
        width >= 320 && height >= 180 && width <= 1920 && height <= 1080 &&
            width % 2 == 0 && height % 2 == 0 && width * 9 == height * 16
    }

    /// Parse dimensions before ImageIO is allowed to allocate decoded pixels.
    static func pictureDimensions(_ data: Data) throws -> Dimensions {
        guard !data.isEmpty, data.count <= pictureLimit else { throw ImageError.fileTooLarge }
        let bytes = [UInt8](data)
        let size: Dimensions
        if bytes.count >= 33 && Array(bytes[0..<8]) == [137, 80, 78, 71, 13, 10, 26, 10] &&
            Array(bytes[8..<16]) == [0, 0, 0, 13, 73, 72, 68, 82] {
            func be32(_ at: Int) -> Int {
                Int(bytes[at]) << 24 | Int(bytes[at + 1]) << 16 | Int(bytes[at + 2]) << 8 | Int(bytes[at + 3])
            }
            size = Dimensions(width: be32(16), height: be32(20))
        } else if let jpeg = jpegSize(data) { size = jpeg }
        else { throw ImageError.unsupportedImage }
        guard size.width > 0 && size.height > 0, size.width <= 8192, size.height <= 8192,
              Int64(size.width) * Int64(size.height) <= 32_000_000 else { throw ImageError.imageTooLarge }
        return size
    }

    static func jpegSize(_ data: Data) -> Dimensions? {
        let bytes = [UInt8](data)
        guard bytes.count >= 4, bytes[0] == 255, bytes[1] == 216 else { return nil }
        var offset = 2
        while offset < bytes.count {
            guard bytes[offset] == 255 else { return nil }; offset += 1
            while offset < bytes.count && bytes[offset] == 255 { offset += 1 }
            guard offset < bytes.count else { return nil }
            let marker = Int(bytes[offset]); offset += 1
            if marker == 0 || marker == 217 || marker == 218 { return nil }
            if marker == 1 || (208...215).contains(marker) { continue }
            guard offset + 2 <= bytes.count else { return nil }
            let length = Int(bytes[offset]) << 8 | Int(bytes[offset + 1])
            guard length >= 2, length <= bytes.count - offset else { return nil }
            if (192...207).contains(marker) && marker != 196 && marker != 200 && marker != 204 {
                guard length >= 8, bytes[offset + 2] == 8 else { return nil }
                return Dimensions(width: Int(bytes[offset + 5]) << 8 | Int(bytes[offset + 6]),
                    height: Int(bytes[offset + 3]) << 8 | Int(bytes[offset + 4]))
            }
            offset += length
        }
        return nil
    }

    private static func imageSource(_ data: Data, expected: Dimensions, png: Bool) throws -> CGImageSource {
        guard let source = CGImageSourceCreateWithData(data as CFData,
            [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetStatus(source) == .statusComplete, CGImageSourceGetCount(source) == 1,
              let type = CGImageSourceGetType(source),
              type as String == (png ? UTType.png.identifier : UTType.jpeg.identifier),
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int,
              width == expected.width, height == expected.height else { throw ImageError.unsupportedImage }
        return source
    }

    private static func decodePicture(_ data: Data) throws -> CGImage {
        let size = try pictureDimensions(data)
        let png = data.first == 137
        let source = try imageSource(data, expected: size, png: png)
        // ImageIO applies EXIF orientation before the bounded, normalized copy.
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0,
            [kCGImageSourceCreateThumbnailFromImageAlways: true,
             kCGImageSourceCreateThumbnailWithTransform: true,
             kCGImageSourceThumbnailMaxPixelSize: 8192,
             kCGImageSourceShouldCacheImmediately: true] as CFDictionary) else { throw ImageError.decodeFailed }
        return image
    }

    private static func rasterize(_ image: CGImage, width: Int, height: Int, cover: Bool) throws -> ProcessedFrame {
        var data = Data(count: width * height * 4)
        let success: Bool = data.withUnsafeMutableBytes { bytes in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height,
                bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.premultipliedFirst.rawValue) else { return false }
            context.setFillColor(CGColor(red: 38.0 / 255, green: 64.0 / 255, blue: 92.0 / 255, alpha: 1))
            context.fill(CGRect(x: 0, y: 0, width: CGFloat(width), height: CGFloat(height)))
            context.interpolationQuality = .high
            let ratio = cover ? max(CGFloat(width) / CGFloat(image.width), CGFloat(height) / CGFloat(image.height)) : 1
            let drawnWidth = cover ? CGFloat(image.width) * ratio : CGFloat(width)
            let drawnHeight = cover ? CGFloat(image.height) * ratio : CGFloat(height)
            context.draw(image, in: CGRect(x: (CGFloat(width) - drawnWidth) / 2,
                y: (CGFloat(height) - drawnHeight) / 2, width: drawnWidth, height: drawnHeight))
            return true
        }
        guard success else { throw ImageError.decodeFailed }
        return ProcessedFrame(bgra: data, width: width, height: height)
    }
}
