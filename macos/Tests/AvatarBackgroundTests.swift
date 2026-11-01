import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import AlterEgoMac

final class AvatarBackgroundTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("AlterEgo-background-tests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws {
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    private func fixture(_ b: UInt8 = 180, _ g: UInt8 = 100, _ r: UInt8 = 40) -> ProcessedFrame {
        var pixels = [UInt8](repeating: 0, count: 320 * 180 * 4)
        for index in stride(from: 0, to: pixels.count, by: 4) {
            pixels[index] = b; pixels[index + 1] = g; pixels[index + 2] = r; pixels[index + 3] = 255
        }
        return ProcessedFrame(bgra: Data(pixels), width: 320, height: 180)
    }
    private func fill(_ image: ProcessedFrame, x: Int, y: Int, width: Int, height: Int,
                      colour: [UInt8]) -> ProcessedFrame {
        var bytes = [UInt8](image.bgra)
        for row in y..<(y + height) { for column in x..<(x + width) {
            let offset = (row * image.width + column) * 4
            for channel in 0..<4 { bytes[offset + channel] = colour[channel] }
        } }
        return ProcessedFrame(bgra: Data(bytes), width: image.width, height: image.height)
    }
    private func pixel(_ frame: ProcessedFrame, _ x: Int, _ y: Int) -> [UInt8] {
        let offset = (y * frame.width + x) * 4
        return Array(frame.bgra[offset..<(offset + 4)])
    }
    private func greenScreen() throws -> AvatarBackground {
        let background = AvatarBackground(stateDirectory: directory)
        try background.updateSettings(.init(style: .greenScreen, colourHex: "#26405C", strength: 26))
        return background
    }
    private func encoded(_ frame: ProcessedFrame, type: UTType) throws -> Data {
        let result = NSMutableData()
        let image = try XCTUnwrap(frame.makeCGImage())
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(result, type.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 1.0] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return result as Data
    }

    func testOriginalPixelsArePreservedWithoutAnEffect() throws {
        let source = fixture()
        let result = try XCTUnwrap(AvatarBackground(stateDirectory: directory).processBGRA(source))
        XCTAssertEqual(source.bgra, result.bgra)
        XCTAssertEqual(result.width, 320); XCTAssertEqual(result.height, 180)
    }

    func testPlainBackgroundChangesButEnclosedMatchingClothingDetailRemains() throws {
        let foreground: [UInt8] = [20, 40, 220, 255]
        let blue: [UInt8] = [180, 100, 40, 255]
        var source = fill(fixture(), x: 100, y: 40, width: 120, height: 100, colour: foreground)
        source = fill(source, x: 115, y: 55, width: 90, height: 70, colour: blue)
        let output = try XCTUnwrap(greenScreen().processBGRA(source))
        XCTAssertEqual(pixel(output, 20, 20), [0, 255, 0, 255])
        XCTAssertEqual(pixel(output, 105, 45), foreground)
        XCTAssertEqual(pixel(output, 160, 90), blue)
    }

    func testCoveredCornerUsesAgreeingBackgroundCornersWithoutDeletingTheHand() throws {
        let hand: [UInt8] = [20, 40, 220, 255]
        let source = fill(fixture(), x: 0, y: 0, width: 38, height: 38, colour: hand)
        let output = try XCTUnwrap(greenScreen().processBGRA(source))
        XCTAssertEqual(pixel(output, 3, 3), hand)
        XCTAssertEqual(pixel(output, 317, 177), [0, 255, 0, 255])
    }

    func testDetailedCornersKeepOriginalVideoAndExplainWhy() throws {
        var bytes = [UInt8](fixture().bgra)
        for y in 0..<180 { for x in 0..<320 where (x + y) % 2 == 0 {
            let offset = (y * 320 + x) * 4
            bytes[offset] = 20; bytes[offset + 1] = 230; bytes[offset + 2] = 210
        } }
        let source = ProcessedFrame(bgra: Data(bytes), width: 320, height: 180)
        let compositor = try greenScreen()
        let output = try XCTUnwrap(compositor.processBGRA(source))
        XCTAssertEqual(source.bgra, output.bgra)
        XCTAssertTrue(compositor.notice.contains("not plain"))
    }

    func testEdgeStrengthProducesAFeatheredEdgeRatherThanAHardCut() throws {
        let source = fill(fixture(), x: 20, y: 20, width: 1, height: 1, colour: [180, 126, 40, 255])
        let output = try XCTUnwrap(greenScreen().processBGRA(source))
        let value = pixel(output, 20, 20)
        XCTAssertGreaterThan(value[0], 0); XCTAssertLessThan(value[0], 180)
        XCTAssertGreaterThan(value[1], 126); XCTAssertLessThan(value[1], 255)
        XCTAssertEqual(value[3], 255)
    }

    func testExpiredFramesAreNotReturnedForPublication() throws {
        let compositor = try greenScreen()
        XCTAssertNil(try compositor.processBGRA(fixture(), fresh: { false }))
        var checks = 0
        XCTAssertNil(try compositor.processBGRA(fixture(), fresh: { checks += 1; return checks == 1 }))
        XCTAssertEqual(checks, 2)
    }

    func testMalformedPixelsAndNonVideoAspectRatiosAreRejected() throws {
        let compositor = AvatarBackground(stateDirectory: directory)
        XCTAssertThrowsError(try compositor.processBGRA(ProcessedFrame(bgra: Data(count: 4), width: 320, height: 180)))
        XCTAssertThrowsError(try compositor.processBGRA(ProcessedFrame(bgra: Data(count: 320 * 181 * 4), width: 320, height: 181)))
        XCTAssertThrowsError(try compositor.process(jpeg: Data([255, 216, 255, 217]), width: 320, height: 180))
    }

    func testJpegHeaderDimensionsMustMatchTheAdvertisedDimensions() throws {
        let jpeg = try encoded(fixture(), type: .jpeg)
        let compositor = AvatarBackground(stateDirectory: directory)
        XCTAssertThrowsError(try compositor.process(jpeg: jpeg, width: 640, height: 360)) { error in
            XCTAssertEqual(error as? AvatarBackground.ImageError, .invalidVideoSize)
        }
        XCTAssertThrowsError(try compositor.process(jpeg: jpeg.dropLast(2), width: 320, height: 180))
        let output = try XCTUnwrap(compositor.process(jpeg: jpeg, width: 320, height: 180))
        XCTAssertEqual(output.width, 320); XCTAssertEqual(output.height, 180)
        XCTAssertEqual(output.bgra.count, 320 * 180 * 4)
    }

    func testJpegDecodeAndPreviewKeepTopAndBottomOrientation() throws {
        var source = fixture(240, 240, 240)
        source = fill(source, x: 0, y: 0, width: 320, height: 24, colour: [0, 0, 230, 255])
        source = fill(source, x: 0, y: 156, width: 320, height: 24, colour: [230, 0, 0, 255])
        let jpeg = try encoded(source, type: .jpeg)
        let output = try XCTUnwrap(AvatarBackground(stateDirectory: directory).process(jpeg: jpeg, width: 320, height: 180))
        XCTAssertGreaterThan(pixel(output, 100, 5)[2], 180)
        XCTAssertLessThan(pixel(output, 100, 5)[0], 30)
        XCTAssertGreaterThan(pixel(output, 100, 174)[0], 180)
        XCTAssertLessThan(pixel(output, 100, 174)[2], 30)
        XCTAssertNotNil(output.makeNSImage())
    }

    func testSettingsSurviveRestartAndInvalidChoicesDoNotOverwriteThem() throws {
        let compositor = AvatarBackground(stateDirectory: directory)
        let choice = AvatarBackground.Settings(style: .darkStudio, colourHex: "#abcdef", strength: 41)
        try compositor.updateSettings(choice)
        XCTAssertThrowsError(try compositor.updateSettings(.init(style: .colour, colourHex: "not-a-colour", strength: 26)))
        XCTAssertThrowsError(try compositor.updateSettings(.init(style: .colour, colourHex: "#112233", strength: 61)))
        let restored = AvatarBackground(stateDirectory: directory).settings
        XCTAssertEqual(restored.style, .darkStudio)
        XCTAssertEqual(restored.colourHex, "#ABCDEF")
        XCTAssertEqual(restored.strength, 41)
    }

    func testPictureSelectionIsNormalizedAndCopiedIndependentlyOfItsOriginalFile() throws {
        let sourceURL = directory.appendingPathComponent("chosen-picture.png")
        try encoded(fixture(20, 30, 200), type: .png).write(to: sourceURL)
        let compositor = AvatarBackground(stateDirectory: directory)
        XCTAssertThrowsError(try compositor.updateSettings(.init(style: .picture)))
        try compositor.choosePicture(sourceURL)
        try FileManager.default.removeItem(at: sourceURL)
        let saved = try Data(contentsOf: directory.appendingPathComponent("background.png"))
        XCTAssertEqual(try AvatarBackground.pictureDimensions(saved), .init(width: 1920, height: 1080))
        let restored = AvatarBackground(stateDirectory: directory)
        XCTAssertTrue(restored.hasPicture); XCTAssertEqual(restored.settings.style, .picture)
        let output = try XCTUnwrap(restored.processBGRA(fixture()))
        XCTAssertGreaterThan(pixel(output, 20, 20)[2], 180)
        XCTAssertLessThan(pixel(output, 20, 20)[0], 40)
    }

    func testOversizedPngHeaderIsRejectedBeforePixelDecode() throws {
        var header = [UInt8](repeating: 0, count: 33)
        header.replaceSubrange(0..<16, with: [137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82])
        func set(_ value: UInt32, at: Int) {
            for index in 0..<4 { header[at + index] = UInt8(truncatingIfNeeded: value >> UInt32((3 - index) * 8)) }
        }
        set(8192, at: 16); set(8192, at: 20)
        XCTAssertThrowsError(try AvatarBackground.pictureDimensions(Data(header))) { error in
            XCTAssertEqual(error as? AvatarBackground.ImageError, .imageTooLarge)
        }
        set(8193, at: 16); set(1, at: 20)
        XCTAssertThrowsError(try AvatarBackground.pictureDimensions(Data(header)))
    }

    func testEncodedByteLimitAndUnsupportedTypesAreRejected() throws {
        XCTAssertThrowsError(try AvatarBackground.pictureDimensions(Data(count: 16 * 1024 * 1024 + 1))) { error in
            XCTAssertEqual(error as? AvatarBackground.ImageError, .fileTooLarge)
        }
        XCTAssertThrowsError(try AvatarBackground.pictureDimensions(Data("GIF89a".utf8))) { error in
            XCTAssertEqual(error as? AvatarBackground.ImageError, .unsupportedImage)
        }
    }
}
