import Foundation
import CryptoKit

enum AvatarProtocolError: Error, LocalizedError {
    case invalidHeader, unsupportedProtocol, invalidImage, invalidTranscript, clockReversed
    var errorDescription: String? {
        switch self {
        case .invalidHeader: return "The headset sent an invalid video header."
        case .unsupportedProtocol: return "Update AlterEgo on the headset to use encrypted video sharing."
        case .invalidImage: return "The headset sent an unsupported avatar image."
        case .invalidTranscript: return "The pairing request was invalid."
        case .clockReversed: return "The headset video clock restarted. Reconnect sharing."
        }
    }
}

enum AvatarProtocolVersion: String {
    case v3 = "AEMR0003", v4 = "AEMR0004", v5 = "AEMR0005"
    var hasReceipts: Bool { self != .v3 }
    var maximumJPEGBytes: Int { (self == .v5 ? 8 : 3) * 1024 * 1024 }
}

struct AvatarVideoSize: Equatable {
    let width: Int
    let height: Int
}

struct AvatarFrameHeader: Equatable {
    let sequence: Int64
    let captureMilliseconds: Int64
    let payloadLength: Int
}

/// The Quest and Windows receiver's existing wire format; no platform camera APIs.
enum AvatarProtocol {
    static let videoPort: UInt16 = 38991
    static let discoveryPort: UInt16 = 38992

    static func validVideoSize(width: Int, height: Int) -> Bool {
        width >= 320 && height >= 180 && width <= 1920 && height <= 1080 &&
        width % 2 == 0 && height % 2 == 0 && width * 9 == height * 16
    }

    static func authenticate(_ header: Data, pin: String) throws -> AvatarProtocolVersion {
        guard header.count == 14, let magic = String(data: header.prefix(8), encoding: .ascii),
              let version = AvatarProtocolVersion(rawValue: magic),
              pin.utf8.count == 6, pin.utf8.allSatisfy({ (48...57).contains($0) }) else {
            throw AvatarProtocolError.unsupportedProtocol
        }
        let expected = Array(pin.utf8)
        let supplied = Array(header.suffix(6))
        var difference: UInt8 = 0
        for index in 0..<6 { difference |= expected[index] ^ supplied[index] }
        guard difference == 0 else { throw AvatarProtocolError.invalidHeader }
        return version
    }

    static func frameHeader(_ bytes: Data, version: AvatarProtocolVersion,
                            lastSequence: Int64) throws -> AvatarFrameHeader {
        guard bytes.count == 20 else { throw AvatarProtocolError.invalidHeader }
        let value = Array(bytes)
        func long(_ start: Int) -> Int64 {
            var result: UInt64 = 0
            for byte in value[start..<(start + 8)] { result = (result << 8) | UInt64(byte) }
            return Int64(bitPattern: result)
        }
        var length: UInt32 = 0
        for byte in value[16..<20] { length = (length << 8) | UInt32(byte) }
        let sequence = long(0), capture = long(8), count = Int(length)
        guard sequence > lastSequence, capture > 0, count == 0 || count >= 4,
              count <= version.maximumJPEGBytes else { throw AvatarProtocolError.invalidHeader }
        return AvatarFrameHeader(sequence: sequence, captureMilliseconds: capture, payloadLength: count)
    }

    /// Inspect dimensions before giving compressed data to ImageIO/CoreGraphics.
    static func jpegSize(_ data: Data, version: AvatarProtocolVersion) throws -> AvatarVideoSize {
        guard data.count >= 4 && data.count <= version.maximumJPEGBytes else {
            throw AvatarProtocolError.invalidImage
        }
        return try data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
            guard bytes[0] == 0xff && bytes[1] == 0xd8 else { throw AvatarProtocolError.invalidImage }
            var offset = 2
            while offset < bytes.count {
                guard bytes[offset] == 0xff else { throw AvatarProtocolError.invalidImage }
                offset += 1
                while offset < bytes.count && bytes[offset] == 0xff { offset += 1 }
                guard offset < bytes.count else { throw AvatarProtocolError.invalidImage }
                let marker = bytes[offset]; offset += 1
                guard marker != 0 && marker != 0xd9 && marker != 0xda else {
                    throw AvatarProtocolError.invalidImage
                }
                if marker == 1 || (0xd0...0xd7).contains(marker) { continue }
                guard offset + 2 <= bytes.count else { throw AvatarProtocolError.invalidImage }
                let length = (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
                guard length >= 2 && length <= bytes.count - offset else { throw AvatarProtocolError.invalidImage }
                let isSOF = (0xc0...0xcf).contains(marker) && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
                if isSOF {
                    guard length >= 8 && bytes[offset + 2] == 8 else { throw AvatarProtocolError.invalidImage }
                    let height = (Int(bytes[offset + 3]) << 8) | Int(bytes[offset + 4])
                    let width = (Int(bytes[offset + 5]) << 8) | Int(bytes[offset + 6])
                    guard validVideoSize(width: width, height: height),
                          version == .v5 || (width == 960 && height == 540) else {
                        throw AvatarProtocolError.invalidImage
                    }
                    return AvatarVideoSize(width: width, height: height)
                }
                offset += length
            }
            throw AvatarProtocolError.invalidImage
        }
    }

    static func receipt(sequence: Int64, state: UInt8) -> Data {
        let sequenceBE = UInt64(bitPattern: sequence).bigEndian
        var result = withUnsafeBytes(of: sequenceBE) { Data($0) }
        result.append(state)
        return result
    }

    static func pairingCode(fingerprint: String, clientNonce: Data, serverNonce: Data) throws -> String {
        guard let certificateHash = hexBytes(fingerprint), certificateHash.count == 32,
              clientNonce.count == 32, serverNonce.count == 32 else { throw AvatarProtocolError.invalidTranscript }
        let digest = SHA256.hash(data: certificateHash + clientNonce + serverNonce)
        let hex = digest.prefix(6).map { String(format: "%02X", $0) }.joined()
        let characters = Array(hex)
        return stride(from: 0, to: 12, by: 4).map { String(characters[$0..<($0 + 4)]) }.joined(separator: "-")
    }

    static func hexBytes(_ value: String) -> Data? {
        let characters = Array(value.utf8)
        guard characters.count % 2 == 0 else { return nil }
        func nibble(_ c: UInt8) -> UInt8? {
            switch c { case 48...57: return c - 48; case 65...70: return c - 55; case 97...102: return c - 87; default: return nil }
        }
        var result = Data(capacity: characters.count / 2)
        for index in stride(from: 0, to: characters.count, by: 2) {
            guard let high = nibble(characters[index]), let low = nibble(characters[index + 1]) else { return nil }
            result.append((high << 4) | low)
        }
        return result
    }

    static func privateIPv4(_ address: String) -> Bool {
        let parts = address.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 4 else { return false }
        let numbers = parts.compactMap { part -> Int? in
            guard !part.isEmpty, part.count <= 3, part.utf8.allSatisfy({ (48...57).contains($0) }),
                  (part.count == 1 || part.first != "0"), let value = Int(part), value <= 255 else { return nil }
            return value
        }
        guard numbers.count == 4 else { return false }
        return numbers[0] == 10 || numbers[0] == 127 || (numbers[0] == 172 && (16...31).contains(numbers[1])) ||
               (numbers[0] == 192 && numbers[1] == 168)
    }

    static func discoveryReply(request: Data, peerAddress: String, address: String,
                               fingerprint: String, name: String) -> Data? {
        let prefix = Data("AEGO_DISCOVER1|".utf8)
        guard privateIPv4(peerAddress), privateIPv4(address), request.count == 47,
              request.starts(with: prefix), hexBytes(fingerprint)?.count == 32,
              let nonce = String(data: request.suffix(32), encoding: .ascii), hexBytes(nonce)?.count == 16 else { return nil }
        let safeName = String(name.unicodeScalars.filter { (32...126).contains($0.value) && $0.value != 124 }
            .prefix(60).map(Character.init))
        return Data("AEGO_REPLY1|\(nonce)|\(address)|38991|\(fingerprint.uppercased())|\(safeName.isEmpty ? "Mac" : safeName)".utf8)
    }
}

enum AvatarFrameDecision: Equatable { case accept, delayed, clockJump }

/// Different clock origins are expected. Compare relative transit age without
/// arithmetic overflow even when a malicious peer supplies extreme timestamps.
struct AvatarFrameTimeline {
    private var anchored = false
    private var minimumOffset: Int64 = 0
    private var lastCapture: Int64 = -1
    private var lastArrival: Int64 = 0

    mutating func observe(capture: Int64, arrival: Int64) throws -> AvatarFrameDecision {
        guard capture > 0, arrival >= 0, capture >= lastCapture,
              !anchored || arrival >= lastArrival else { throw AvatarProtocolError.clockReversed }
        if anchored {
            let captureDelta = capture - lastCapture, arrivalDelta = arrival - lastArrival
            if captureDelta > arrivalDelta && captureDelta - arrivalDelta > 2000 { return .clockJump }
        }
        let offset = arrival - capture
        let proposed = anchored ? min(minimumOffset, offset) : offset
        let difference = offset.subtractingReportingOverflow(proposed)
        if difference.overflow || difference.partialValue > 1500 { return .delayed }
        minimumOffset = proposed; lastCapture = capture; lastArrival = arrival; anchored = true
        return .accept
    }

    func freshAt(capture: Int64, arrival: Int64) -> Bool {
        guard anchored, capture > 0, arrival >= lastArrival else { return false }
        let age = (arrival - capture).subtractingReportingOverflow(minimumOffset)
        return !age.overflow && age.partialValue <= 1500
    }
}
