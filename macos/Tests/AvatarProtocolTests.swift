import XCTest
@testable import AlterEgoMac

final class AvatarProtocolTests: XCTestCase {
    private func header(sequence: Int64 = 1, capture: Int64 = 10_000, length: UInt32 = 100) -> Data {
        let sequenceBE = UInt64(bitPattern: sequence).bigEndian
        let captureBE = UInt64(bitPattern: capture).bigEndian
        let lengthBE = length.bigEndian
        return withUnsafeBytes(of: sequenceBE) { Data($0) } +
            withUnsafeBytes(of: captureBE) { Data($0) } + withUnsafeBytes(of: lengthBE) { Data($0) }
    }

    /// Minimal SOF metadata for parser tests; not a complete decodable image.
    private func jpeg(width: Int, height: Int, precision: UInt8 = 8) -> Data {
        Data([0xff, 0xd8, 0xff, 0xc0, 0, 11, precision,
            UInt8((height >> 8) & 255), UInt8(height & 255),
            UInt8((width >> 8) & 255), UInt8(width & 255), 1, 1, 0x11, 0])
    }

    func testAllEncryptedVersionsAuthenticateButPlaintextAndShortPINCannot() throws {
        for version in [AvatarProtocolVersion.v3, .v4, .v5] {
            XCTAssertEqual(try AvatarProtocol.authenticate(Data((version.rawValue + "123456").utf8), pin: "123456"), version)
            XCTAssertThrowsError(try AvatarProtocol.authenticate(Data((version.rawValue + "123455").utf8), pin: "123456"))
        }
        XCTAssertThrowsError(try AvatarProtocol.authenticate(Data("AEMR0001123456".utf8), pin: "123456"))
        XCTAssertThrowsError(try AvatarProtocol.authenticate(Data("AEMR0005123".utf8), pin: "123"))
    }

    func testSupportedEvenSixteenByNineDimensions() {
        for size in [(1920, 1080), (1600, 900), (1280, 720), (960, 540), (320, 180)] {
            XCTAssertTrue(AvatarProtocol.validVideoSize(width: size.0, height: size.1))
        }
        for size in [(1921, 1080), (1920, 1200), (640, 481), (0, 0), (16_384, 9216), (319, 180)] {
            XCTAssertFalse(AvatarProtocol.validVideoSize(width: size.0, height: size.1))
        }
    }

    func testFrameHeaderUsesBigEndianAndHasExplicitPause() throws {
        let frame = try AvatarProtocol.frameHeader(header(sequence: 0x0102030405060708, capture: 42, length: 1000),
            version: .v5, lastSequence: -1)
        XCTAssertEqual(frame.sequence, 0x0102030405060708)
        XCTAssertEqual(frame.captureMilliseconds, 42)
        XCTAssertEqual(frame.payloadLength, 1000)
        XCTAssertEqual(try AvatarProtocol.frameHeader(header(length: 0), version: .v5, lastSequence: 0).payloadLength, 0)
        XCTAssertEqual(AvatarProtocol.receipt(sequence: 0x0102030405060708, state: 2), Data([1, 2, 3, 4, 5, 6, 7, 8, 2]))
    }

    func testReplayedNegativeAndOversizedHeadersAreRejectedBeforePayloadAllocation() {
        for bytes in [header(sequence: 1), header(sequence: -1), header(sequence: 2, capture: 0), header(sequence: 2, length: 3),
                      header(sequence: 2, length: 8 * 1024 * 1024 + 1), header(sequence: 2, length: UInt32.max), Data(repeating: 0, count: 19)] {
            XCTAssertThrowsError(try AvatarProtocol.frameHeader(bytes, version: .v5, lastSequence: 1))
        }
        XCTAssertThrowsError(try AvatarProtocol.frameHeader(header(sequence: 2, length: 3 * 1024 * 1024 + 1),
            version: .v4, lastSequence: 1))
        XCTAssertNoThrow(try AvatarProtocol.frameHeader(header(sequence: 2, length: 8 * 1024 * 1024),
            version: .v5, lastSequence: 1))
    }

    func testJPEGMetadataBoundsPrecedeDecoderAndOlderVersionsStayAt540p() throws {
        XCTAssertEqual(try AvatarProtocol.jpegSize(jpeg(width: 1920, height: 1080), version: .v5),
                       AvatarVideoSize(width: 1920, height: 1080))
        XCTAssertEqual(try AvatarProtocol.jpegSize(jpeg(width: 960, height: 540), version: .v3),
                       AvatarVideoSize(width: 960, height: 540))
        XCTAssertThrowsError(try AvatarProtocol.jpegSize(jpeg(width: 1920, height: 1080), version: .v4))
        XCTAssertThrowsError(try AvatarProtocol.jpegSize(jpeg(width: 16_384, height: 9216), version: .v5))
        XCTAssertThrowsError(try AvatarProtocol.jpegSize(jpeg(width: 960, height: 540, precision: 12), version: .v5))
        XCTAssertThrowsError(try AvatarProtocol.jpegSize(Data([0xff, 0xd8, 0xff, 0xe0, 0xff, 0xff]), version: .v5))
        XCTAssertThrowsError(try AvatarProtocol.jpegSize(Data([0xff, 0xd8, 0xff, 0xda, 0, 8, 0, 0]), version: .v5))
    }

    func testPairingCodeMatchesExistingWindowsAndQuestTranscript() throws {
        let fingerprint = String(repeating: "00", count: 32)
        let client = Data((0..<32).map { UInt8($0) }), server = Data((32..<64).map { UInt8($0) })
        XCTAssertEqual(try AvatarProtocol.pairingCode(fingerprint: fingerprint, clientNonce: client, serverNonce: server),
                       "DC7A-4801-4FC1")
        XCTAssertNotEqual(try AvatarProtocol.pairingCode(fingerprint: fingerprint, clientNonce: server, serverNonce: client),
                          "DC7A-4801-4FC1")
        XCTAssertThrowsError(try AvatarProtocol.pairingCode(fingerprint: String(repeating: "GG", count: 32),
            clientNonce: client, serverNonce: server))
        XCTAssertThrowsError(try AvatarProtocol.pairingCode(fingerprint: fingerprint, clientNonce: Data(), serverNonce: server))
    }

    func testDiscoveryPreservesNonceAndSanitizesUntrustedNameWithoutAdvertisingPIN() throws {
        let nonce = String(repeating: "ab", count: 16), fingerprint = String(repeating: "01", count: 32)
        let query = Data("AEGO_DISCOVER1|\(nonce)".utf8)
        let reply = try XCTUnwrap(AvatarProtocol.discoveryReply(request: query, peerAddress: "192.168.1.10",
            address: "192.168.1.20", fingerprint: fingerprint, name: "My|Mac\n"))
        XCTAssertEqual(String(data: reply, encoding: .utf8), "AEGO_REPLY1|\(nonce)|192.168.1.20|38991|\(fingerprint)|MyMac")
        XCTAssertNil(AvatarProtocol.discoveryReply(request: query, peerAddress: "8.8.8.8", address: "192.168.1.20", fingerprint: fingerprint, name: "Mac"))
        XCTAssertNil(AvatarProtocol.discoveryReply(request: query + Data([0]), peerAddress: "192.168.1.10", address: "192.168.1.20", fingerprint: fingerprint, name: "Mac"))
        XCTAssertNil(AvatarProtocol.discoveryReply(request: Data("AEGO_DISCOVER1|xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".utf8),
            peerAddress: "192.168.1.10", address: "192.168.1.20", fingerprint: fingerprint, name: "Mac"))
    }

    func testPrivateIPv4ValidationDoesNotAcceptWildcardPublicOrAmbiguousAddresses() {
        for ip in ["10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.2", "127.0.0.1"] {
            XCTAssertTrue(AvatarProtocol.privateIPv4(ip))
        }
        for ip in ["0.0.0.0", "8.8.8.8", "172.32.0.1", "192.168.1.256", "192.168.01.1", "192.168.1", "::1", "192.168.1.1|x"] {
            XCTAssertFalse(AvatarProtocol.privateIPv4(ip))
        }
    }

    func testDifferentClockOriginsAndSmallTransitVariationRemainFresh() throws {
        var timeline = AvatarFrameTimeline()
        XCTAssertEqual(try timeline.observe(capture: 10_000, arrival: 1_000_000), .accept)
        XCTAssertEqual(try timeline.observe(capture: 10_033, arrival: 1_000_045), .accept)
        XCTAssertTrue(timeline.freshAt(capture: 10_033, arrival: 1_000_050))
        XCTAssertFalse(timeline.freshAt(capture: 10_033, arrival: 1_001_534))
    }

    func testDelayedAndForwardClockJumpCannotResetTheFreshnessAnchor() throws {
        var timeline = AvatarFrameTimeline()
        XCTAssertEqual(try timeline.observe(capture: 10_000, arrival: 1_000_000), .accept)
        XCTAssertEqual(try timeline.observe(capture: 10_033, arrival: 1_002_000), .delayed)
        XCTAssertEqual(try timeline.observe(capture: 900_000, arrival: 1_002_010), .clockJump)
        XCTAssertEqual(try timeline.observe(capture: 12_020, arrival: 1_002_025), .accept)
        XCTAssertThrowsError(try timeline.observe(capture: 12_019, arrival: 1_002_026))
    }

    func testExtremePeerTimestampsNeverOverflow() throws {
        var timeline = AvatarFrameTimeline()
        XCTAssertEqual(try timeline.observe(capture: Int64.max, arrival: 0), .accept)
        XCTAssertEqual(try timeline.observe(capture: Int64.max, arrival: Int64.max), .delayed)
        XCTAssertFalse(timeline.freshAt(capture: 1, arrival: Int64.max))
        XCTAssertThrowsError(try timeline.observe(capture: 1, arrival: Int64.max))
    }
}
