import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate {
    var model: WebcamModel?
    private var closing = false
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard let model = model else { return .terminateNow }
        guard !closing else { return .terminateLater }
        closing = true
        model.shutdown { sender.reply(toApplicationShouldTerminate: true) }
        return .terminateLater
    }
}

@main
struct AlterEgoMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var model = WebcamModel()
    var body: some Scene {
        WindowGroup("AlterEgo MR Webcam") {
            WebcamWindow(model: model)
                .onAppear { delegate.model = model }
                .preferredColorScheme(.dark)
        }
        .defaultSize(width: 1160, height: 800)
        .windowResizability(.contentMinSize)
        .commands { CommandGroup(replacing: .newItem) {} }
    }
}
