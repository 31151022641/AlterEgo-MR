import AppKit
import Foundation
import UniformTypeIdentifiers
import Combine

private final class PreviewMailbox {
    private let lock = NSLock()
    private var revision: UInt64 = 0
    private var frame: ProcessedFrame?
    func update(_ value: ProcessedFrame?) { lock.lock(); defer { lock.unlock() }; frame = value; revision &+= 1 }
    func snapshot() -> (UInt64, ProcessedFrame?) { lock.lock(); defer { lock.unlock() }; return (revision, frame) }
}

/// App actions and published state run on main; service work and camera setup
/// run off main. The mailbox bounds UI work to the latest processed frame.
final class WebcamModel: ObservableObject {
    @Published var networks: [NetworkChoice] = []
    @Published var selectedAddress = ""
    @Published var status = "Preparing AlterEgo…"
    @Published var running = false
    @Published var busy = true
    @Published var ready = false
    @Published var preview: NSImage?
    @Published var resolution = "No video yet"
    @Published var errorMessage: String?
    @Published var pairingKey = ""
    @Published var pairing: PairingPrompt?
    @Published var backgroundSettings = AvatarBackground.Settings()
    @Published var backgroundNotice = "Original avatar background"
    @Published var showBackground = false
    let camera = CameraManager()
    private let worker = DispatchQueue(label: "com.misterslee.alterego.mac.control", qos: .userInitiated)
    private let mailbox = PreviewMailbox()
    private var previewRevision: UInt64 = .max
    private var timer: Timer?
    private var receiver: AvatarReceiver?
    private var identity: TransportIdentity?
    private var publisher: SharedFrameStore?
    private var background: AvatarBackground?
    private var pairingReply: ((Bool) -> Void)?
    private var pairingReplyID: UUID?
    private var generation = UUID() // Main-thread UI ownership; callbacks carry a snapshot.
    private var workspaceObservers: [NSObjectProtocol] = []

    struct PairingPrompt: Identifiable { let id: UUID; let code: String }

    init() {
        // Native unit tests must not read/write a real pairing identity or
        // background choice merely because XCTest launches its app host.
        if ProcessInfo.processInfo.environment["ALTEREGO_TESTING"] == "1" {
            status = "Test mode"; busy = false; return
        }
        refreshNetworks()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0/15.0, repeats: true) { [weak self] _ in self?.refreshPreview() }
        if let timer = timer { RunLoop.main.add(timer, forMode: .common) }
        for name in [NSWorkspace.willSleepNotification, NSWorkspace.sessionDidResignActiveNotification] {
            workspaceObservers.append(NSWorkspace.shared.notificationCenter.addObserver(forName: name,
                object: nil, queue: .main) { [weak self] _ in self?.suspendForPrivacy() })
        }
        camera.onChanged = { [weak self] in self?.checkCamera() }
        worker.async { [weak self] in
            guard let self = self else { return }
            do {
                let folder = try self.stateDirectory()
                self.background = AvatarBackground(stateDirectory: folder)
                self.identity = try TransportIdentity.loadOrCreate()
                self.publisher = try SharedFrameStore(role: .writer)
                let settings = self.background!.settings; let key = self.identity!.pairingKey
                DispatchQueue.main.async {
                    self.backgroundSettings = settings; self.pairingKey = key
                    self.status = "Receiver off"; self.busy = false; self.ready = true
                }
            } catch { self.report(error, prefix: "Setup needed") }
        }
    }

    func refreshNetworks() {
        networks = NetworkChoice.available()
        if !networks.contains(where: { $0.address == selectedAddress }) { selectedAddress = networks.first?.address ?? "" }
    }

    func toggleReceiver() { if running { stop() } else { start() } }
    func start() {
        guard ready, !busy, !running, !selectedAddress.isEmpty else { return }
        busy = true; status = "Starting receiver…"
        generation = UUID(); let startGeneration = generation
        let address = selectedAddress
        worker.async { [weak self] in
            guard let self = self else { return }
            guard let identity = self.identity, let publisher = self.publisher, let background = self.background else {
                self.report(MacAppError.message("Complete Mac camera and pairing setup first."), prefix: "Setup needed")
                return
            }
            do {
                // A receipt must represent camera publication, not just a UI preview.
                // Enabling the extension is a separate user-visible macOS operation.
                try publisher.connect()
                guard publisher.isReady else { throw MacAppError.message("Enable the Mac camera extension first, then start the receiver.") }
                weak var service: AvatarReceiver?
                let receiver = AvatarReceiver(identity: identity, publish: { [weak self] jpeg, width, height, commitIfFresh in
                    guard let self = self else { return false }
                    do {
                        guard let frame = try background.process(jpeg: jpeg, width: width, height: height) else { return false }
                        return commitIfFresh { stillFresh in
                            guard publisher.publish(bgra: frame.bgra, width: frame.width, height: frame.height,
                                                    bytesPerRow: frame.width*4, stillFresh: stillFresh) else { return false }
                            self.mailbox.update(frame)
                            return true
                        }
                    } catch { return false }
                }, clear: { [weak self] in
                    publisher.clear(); self?.mailbox.update(nil)
                }, onStatus: { [weak self] message in
                    DispatchQueue.main.async {
                        guard let self = self, self.generation == startGeneration else { return }
                        self.status = message; self.running = service?.isRunning == true
                    }
                }, confirmPairing: { [weak self] attempt, code, completion in
                    DispatchQueue.main.async {
                        guard let self = self, self.generation == startGeneration, self.running || self.busy else { completion(false); return }
                        self.respondToPairing(false)
                        self.pairingReply = completion; self.pairingReplyID = attempt
                        self.pairing = PairingPrompt(id: attempt, code: code)
                    }
                }, cancelPairing: { [weak self] attempt in
                    DispatchQueue.main.async {
                        if self?.generation == startGeneration { self?.respondToPairing(false, id: attempt) }
                    }
                })
                service = receiver
                try receiver.start(address: address)
                self.receiver = receiver
                DispatchQueue.main.async {
                    guard self.generation == startGeneration else { return }
                    self.running = receiver.isRunning; self.busy = false
                    if self.running { self.status = "Waiting for Quest" }
                    self.camera.status = "Mac camera connected"
                }
            } catch { publisher.disconnect(); self.report(error, prefix: "Receiver off") }
        }
    }

    func stop() {
        guard !busy else { return }
        generation = UUID()
        busy = true; status = "Stopping receiver…"; respondToPairing(false)
        worker.async { [weak self] in
            guard let self = self else { return }
            self.receiver?.stop(); self.receiver = nil
            self.publisher?.clear(); self.publisher?.disconnect(); self.mailbox.update(nil)
            DispatchQueue.main.async { self.running = false; self.busy = false; self.status = "Receiver off" }
        }
    }

    func shutdown(completion: @escaping () -> Void) {
        generation = UUID()
        for observer in workspaceObservers { NSWorkspace.shared.notificationCenter.removeObserver(observer) }
        workspaceObservers.removeAll()
        timer?.invalidate(); timer = nil; respondToPairing(false)
        // Let native camera callbacks finish while the main run loop stays live.
        worker.async {
            self.receiver?.stop(); self.receiver = nil
            self.publisher?.clear(); self.publisher?.disconnect(); self.mailbox.update(nil)
            DispatchQueue.main.async(execute: completion)
        }
    }

    private func suspendForPrivacy() {
        generation = UUID(); respondToPairing(false)
        busy = true; status = "Receiver off · start again when ready"
        worker.async {
            self.receiver?.stop(); self.receiver = nil
            self.publisher?.clear(); self.publisher?.disconnect(); self.mailbox.update(nil)
            DispatchQueue.main.async { self.running = false; self.busy = false }
        }
    }

    func checkCamera() {
        guard !running, !busy else { return }
        busy = true
        worker.async { [weak self] in
            guard let self = self else { return }
            do {
                try self.publisher?.connect()
                let available = self.publisher?.isReady == true
                self.publisher?.disconnect()
                DispatchQueue.main.async { self.busy = false; self.camera.status = available ? "Mac camera ready" : "Enable the camera, then check again." }
            } catch {
                DispatchQueue.main.async { self.busy = false; self.camera.status = error.localizedDescription }
            }
        }
    }

    func respondToPairing(_ approved: Bool, id: UUID? = nil) {
        if let id = id, pairingReplyID != id { return }
        let reply = pairingReply; pairingReply = nil; pairingReplyID = nil; pairing = nil; reply?(approved)
    }
    func copyAddress() { copy(selectedAddress + ":38991") }
    func copyKey() { copy(pairingKey) }
    private func copy(_ text: String) { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string) }

    func resetPairing() {
        guard !running, !busy else { return }
        busy = true
        worker.async { [weak self] in
            guard let self = self else { return }
            do {
                if self.background == nil { self.background = AvatarBackground(stateDirectory: try self.stateDirectory()) }
                self.identity = try TransportIdentity.loadOrCreate(reset: true)
                if self.publisher == nil { self.publisher = try SharedFrameStore(role: .writer) }
                let key = self.identity!.pairingKey
                DispatchQueue.main.async { self.pairingKey = key; self.ready = true; self.busy = false; self.status = "Pairing reset · pair your Quest again" }
            } catch { self.report(error, prefix: "Pairing reset failed") }
        }
    }

    func updateBackground(_ settings: AvatarBackground.Settings, pictureURL: URL? = nil) {
        worker.async { [weak self] in
            guard let self = self, let background = self.background else { return }
            do {
                if let url = pictureURL {
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    try background.choosePicture(url)
                }
                try background.updateSettings(settings)
                let saved = background.settings; let notice = background.notice
                DispatchQueue.main.async { self.backgroundSettings = saved; self.backgroundNotice = notice }
            } catch { DispatchQueue.main.async { self.errorMessage = error.localizedDescription } }
        }
    }
    func choosePicture(completion: (URL) -> Void) {
        let panel = NSOpenPanel(); panel.allowedContentTypes = [.png, .jpeg]
        panel.allowsMultipleSelection = false; panel.canChooseDirectories = false
        panel.message = "Choose a background picture for your avatar webcam."
        guard panel.runModal() == .OK, let url = panel.url else { return }
        completion(url)
    }

    private func refreshPreview() {
        let (revision, frame) = mailbox.snapshot()
        if revision != previewRevision {
            previewRevision = revision; preview = frame?.makeNSImage()
            resolution = frame.map { "\($0.width) × \($0.height)" } ?? "No video yet"
            if let background = background { backgroundNotice = background.notice }
        }
    }
    private func report(_ error: Error, prefix: String) {
        DispatchQueue.main.async { self.busy = false; self.running = false; self.status = prefix; self.errorMessage = error.localizedDescription }
    }
    private func stateDirectory() throws -> URL {
        let folder = try FileManager.default.url(for: .applicationSupportDirectory,
            in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("AlterEgo MR", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        return folder
    }
}
