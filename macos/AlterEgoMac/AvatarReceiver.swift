import Foundation
import Network
import Security

enum AvatarReceiverError: Error, LocalizedError {
    case invalidAddress, alreadyRunning, identityUnavailable, timeout, disconnected, stopped, randomUnavailable
    var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Choose the private IPv4 network shared with your Quest."
        case .alreadyRunning: return "The receiver is already running."
        case .identityUnavailable: return "The Mac's secure pairing identity is unavailable."
        case .timeout: return "The headset connection timed out."
        case .disconnected: return "The headset disconnected."
        case .stopped: return "The receiver stopped."
        case .randomUnavailable: return "Secure pairing could not create a fresh challenge."
        }
    }
}

/// Bounded, blocking byte operations used only on a background client worker.
/// Network.framework completions run on a different queue, never a blocked worker.
private final class AvatarTLSChannel {
    let id = UUID()
    let peer: String
    let connection: NWConnection
    let authenticationDeadline = DispatchTime.now() + .seconds(3)
    private let ioQueue = DispatchQueue(label: "com.misterslee.alterego.tls-io")
    private let readySignal = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var readyResult: Result<Void, Error>?

    init(connection: NWConnection, peer: String) {
        self.connection = connection; self.peer = peer
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready: self?.completeReady(.success(()))
            case .failed(let error): self?.completeReady(.failure(error))
            case .cancelled: self?.completeReady(.failure(AvatarReceiverError.stopped))
            default: break
            }
        }
        connection.start(queue: ioQueue)
    }

    private func completeReady(_ result: Result<Void, Error>) {
        lock.lock()
        guard readyResult == nil else { lock.unlock(); return }
        readyResult = result; lock.unlock(); readySignal.signal()
    }

    func waitForTLS() throws {
        guard readySignal.wait(timeout: authenticationDeadline) == .success else {
            cancel(); throw AvatarReceiverError.timeout
        }
        lock.lock(); let result = readyResult; lock.unlock()
        guard let result else { throw AvatarReceiverError.disconnected }
        try result.get()
    }

    func read(_ count: Int, deadline: DispatchTime) throws -> Data {
        guard count > 0 && count <= 8 * 1024 * 1024 else { throw AvatarProtocolError.invalidHeader }
        var accumulated = Data(capacity: count)
        while accumulated.count < count {
            let signal = DispatchSemaphore(value: 0), resultLock = NSLock()
            var result: Result<Data, Error>?
            let remaining = count - accumulated.count
            connection.receive(minimumIncompleteLength: remaining, maximumLength: remaining) { data, _, complete, error in
                let value: Result<Data, Error>
                if let error { value = .failure(error) }
                else if let data, !data.isEmpty { value = .success(data) }
                else { value = .failure(complete ? AvatarReceiverError.disconnected : AvatarReceiverError.timeout) }
                resultLock.lock(); result = value; resultLock.unlock(); signal.signal()
            }
            guard signal.wait(timeout: deadline) == .success else { cancel(); throw AvatarReceiverError.timeout }
            resultLock.lock(); let delivered = result; resultLock.unlock()
            guard let delivered else { throw AvatarReceiverError.disconnected }
            accumulated.append(try delivered.get())
        }
        return accumulated
    }

    func write(_ data: Data, deadline: DispatchTime = .now() + .seconds(3)) throws {
        let signal = DispatchSemaphore(value: 0), resultLock = NSLock()
        var result: Result<Void, Error>?
        connection.send(content: data, completion: .contentProcessed { error in
            resultLock.lock()
            if let error { result = .failure(error) } else { result = .success(()) }
            resultLock.unlock(); signal.signal()
        })
        guard signal.wait(timeout: deadline) == .success else { cancel(); throw AvatarReceiverError.timeout }
        resultLock.lock(); let delivered = result; resultLock.unlock()
        guard let delivered else { throw AvatarReceiverError.disconnected }
        try delivered.get()
    }

    func cancel() { connection.cancel() }
}

private final class AvatarPairingDecision {
    let signal = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var decision: Bool?
    func finish(_ approved: Bool) {
        lock.lock()
        guard decision == nil else { lock.unlock(); return }
        decision = approved; lock.unlock(); signal.signal()
    }
    var approved: Bool { lock.lock(); defer { lock.unlock() }; return decision == true }
}

private final class AvatarPublicationPermit {
    // Accessed only under AvatarReceiver.stateLock.
    var open = true
    var consumed = false
    var committed = false
}

/// Native Mac receiver for the same pinned-TLS protocol as the Windows EXE.
///
/// Public publication contract:
/// `publish(jpeg, width, height, commitIfFresh) -> Bool`
/// Decode/composite on the calling background worker, then invoke exactly once:
/// `commitIfFresh { stillFresh in writeNativeCameraFrame(stillFresh); updatePreviewMailbox(); return success }`.
/// The inner freshness closure is a cheap non-locking check: the state lock is
/// already held. Native scaling must call it immediately before enqueueing.
/// The inner commit must be short/synchronous and must not synchronously call main.
/// Do not retain either closure. Stop/expiry may clear video while decode runs;
/// the transactional gate prevents an old decode from resurrecting cleared video.
final class AvatarReceiver {
    typealias CommitIfFresh = (_ commit: (_ stillFresh: () -> Bool) -> Bool) -> Bool
    typealias Publish = (Data, Int, Int, CommitIfFresh) -> Bool

    private let identity: TransportIdentity
    private let publish: Publish
    private let clear: () -> Void
    private let onStatus: (String) -> Void
    private let confirmPairing: (UUID, String, @escaping (Bool) -> Void) -> Void
    private let cancelPairing: (UUID) -> Void
    private let stateLock = NSLock()
    private let networkQueue = DispatchQueue(label: "com.misterslee.alterego.tls-listener")
    private let discovery = LanDiscovery()
    private var listener: NWListener?
    private var expiry: DispatchSourceTimer?
    private var running = false
    private var clients: [UUID: AvatarTLSChannel] = [:]
    private var owner: UUID?
    private var pairingOwner: (channel: UUID, attempt: UUID)?
    private var lastPublished: Int64 = 0
    private var blank = true
    private var rejected: [String: [Int64]] = [:]
    private var allRejected: [Int64] = []

    init(identity: TransportIdentity, publish: @escaping Publish, clear: @escaping () -> Void,
         onStatus: @escaping (String) -> Void,
         confirmPairing: @escaping (UUID, String, @escaping (Bool) -> Void) -> Void,
         cancelPairing: @escaping (UUID) -> Void) {
        self.identity = identity; self.publish = publish; self.clear = clear
        self.onStatus = onStatus; self.confirmPairing = confirmPairing; self.cancelPairing = cancelPairing
    }

    /// Refresh this in the status callback: a listener can fail asynchronously
    /// after Network.framework accepts its initial configuration.
    var isRunning: Bool { withState { running } }

    func start(address: String) throws {
        guard AvatarProtocol.privateIPv4(address) else { throw AvatarReceiverError.invalidAddress }
        guard let tlsIdentity = sec_identity_create(identity.identity) else { throw AvatarReceiverError.identityUnavailable }
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_local_identity(tls.securityProtocolOptions, tlsIdentity)
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv12)
        // Quest authenticates this server's pinned certificate; the application
        // protocol authenticates the sender's PIN after the TLS handshake.
        sec_protocol_options_set_peer_authentication_required(tls.securityProtocolOptions, false)
        let tcp = NWProtocolTCP.Options(); tcp.noDelay = true
        let parameters = NWParameters(tls: tls, tcp: tcp)
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(address), port: NWEndpoint.Port(rawValue: AvatarProtocol.videoPort)!)
        let created = try NWListener(using: parameters, on: NWEndpoint.Port(rawValue: AvatarProtocol.videoPort)!)
        stateLock.lock()
        guard !running else { stateLock.unlock(); throw AvatarReceiverError.alreadyRunning }
        do { try discovery.start(address: address, fingerprint: identity.fingerprint) }
        catch { stateLock.unlock(); throw error }
        running = true; listener = created; owner = nil; pairingOwner = nil
        blank = true; lastPublished = 0; rejected.removeAll(); allRejected.removeAll()
        clear()
        created.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.accept(connection)
        }
        created.stateUpdateHandler = { [weak self, weak created] state in
            guard let self, let created else { return }
            let isCurrent = self.withState { self.running && self.listener === created }
            guard isCurrent else { return }
            switch state {
            case .ready: self.onStatus("Waiting for Quest · choose Find PC on your headset")
            case .failed:
                self.stop()
                self.onStatus("The Mac receiver could not listen on this network. Check Local Network permission or choose another network.")
            default: break
            }
        }
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
        timer.setEventHandler { [weak self] in self?.expireImage() }
        expiry = timer
        created.start(queue: networkQueue); timer.resume()
        stateLock.unlock()
    }

    func stop() {
        let stopped: (NWListener?, DispatchSourceTimer?, [AvatarTLSChannel], UUID?) = withState {
            running = false
            let result = (listener, expiry, Array(clients.values), pairingOwner?.attempt)
            listener = nil; expiry = nil; clients.removeAll(); owner = nil; pairingOwner = nil
            blank = true; lastPublished = 0
            clear()
            return result
        }
        stopped.0?.cancel(); stopped.1?.cancel()
        stopped.2.forEach { $0.cancel() }
        discovery.stop()
        if let attempt = stopped.3 { cancelPairing(attempt) }
    }

    private func withState<T>(_ work: () throws -> T) rethrows -> T {
        stateLock.lock(); defer { stateLock.unlock() }; return try work()
    }

    private static func nowMs() -> Int64 { Int64(clamping: DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    private func isActive(_ client: AvatarTLSChannel) -> Bool {
        withState { running && clients[client.id] === client }
    }

    private func isLimited(_ peer: String, now: Int64) -> Bool {
        // Called with stateLock. Stored addresses are ephemeral admission keys,
        // never written to logs or included in UI diagnostics.
        allRejected.removeAll { now - $0 > 60_000 }
        if allRejected.count >= 30 { return true }
        rejected[peer]?.removeAll { now - $0 > 60_000 }
        return (rejected[peer]?.count ?? 0) >= 5
    }

    private func reject(_ peer: String) {
        withState {
            let now = Self.nowMs()
            allRejected.removeAll { now - $0 > 60_000 }
            if rejected[peer] == nil && rejected.count >= 256 { rejected.removeAll() }
            rejected[peer, default: []].removeAll { now - $0 > 60_000 }
            // Once limited, additional failures need not grow either array.
            if (rejected[peer]?.count ?? 0) < 5 { rejected[peer, default: []].append(now) }
            if allRejected.count < 30 { allRejected.append(now) }
        }
    }

    private func accept(_ connection: NWConnection) {
        guard case .hostPort(let host, _) = connection.endpoint else { connection.cancel(); return }
        let peer: String
        switch host {
        case .ipv4(let address): peer = address.debugDescription
        default: connection.cancel(); return
        }
        guard AvatarProtocol.privateIPv4(peer) else { connection.cancel(); return }
        let channel = AvatarTLSChannel(connection: connection, peer: peer)
        let admitted = withState { () -> Bool in
            guard running && clients.count < 8 && !isLimited(peer, now: Self.nowMs()) else { return false }
            clients[channel.id] = channel; return true
        }
        guard admitted else { connection.cancel(); return }
        channel.start()
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in self?.handle(channel) }
    }

    private func handle(_ channel: AvatarTLSChannel) {
        var ownsVideo = false
        defer {
            let cleanup = withState { () -> (Bool, UUID?) in
                let wasOwner = owner == channel.id
                let cancelledAttempt = pairingOwner?.channel == channel.id ? pairingOwner?.attempt : nil
                if wasOwner { clear(); owner = nil; blank = true; lastPublished = 0 }
                if cancelledAttempt != nil { pairingOwner = nil }
                clients.removeValue(forKey: channel.id)
                return (wasOwner && running, cancelledAttempt)
            }
            channel.cancel()
            if let attempt = cleanup.1 { cancelPairing(attempt) }
            if cleanup.0 { onStatus("Headset disconnected · avatar cleared · waiting to reconnect") }
        }
        do {
            try channel.waitForTLS()
            let magic = try channel.read(8, deadline: channel.authenticationDeadline)
            if magic == Data("AEPAIR01".utf8) { try pair(channel); return }
            let suppliedPIN = try channel.read(6, deadline: channel.authenticationDeadline)
            let version: AvatarProtocolVersion
            do { version = try AvatarProtocol.authenticate(magic + suppliedPIN, pin: identity.pin) }
            catch { try? channel.write(Data([0])); reject(channel.peer); return }
            let claimed = withState { () -> Bool in
                guard running && clients[channel.id] === channel && owner == nil else { return false }
                owner = channel.id; blank = true; lastPublished = 0; rejected.removeValue(forKey: channel.peer)
                clear(); return true
            }
            guard claimed else { try channel.write(Data([2])); return }
            ownsVideo = true
            try channel.write(Data([1]))
            onStatus("Quest connected securely · waiting for a fresh avatar image")
            var lastSequence: Int64 = -1, timeline = AvatarFrameTimeline()
            var lastSize: AvatarVideoSize?
            while isActive(channel) {
                let bytes = try channel.read(20, deadline: .now() + .seconds(5))
                let header = try AvatarProtocol.frameHeader(bytes, version: version, lastSequence: lastSequence)
                lastSequence = header.sequence
                if header.payloadLength == 0 {
                    pauseImage(ownerID: channel.id)
                    try receipt(channel, version: version, sequence: header.sequence, state: 0)
                    continue
                }
                let jpeg = try channel.read(header.payloadLength, deadline: .now() + .seconds(5))
                let size = try AvatarProtocol.jpegSize(jpeg, version: version)
                let decision = try timeline.observe(capture: header.captureMilliseconds, arrival: Self.nowMs())
                guard decision == .accept else {
                    try receipt(channel, version: version, sequence: header.sequence, state: 2); continue
                }
                let frameTimeline = timeline, permit = AvatarPublicationPermit()
                var reportLive = false
                // Decode/background work happens before the short atomic commit.
                _ = publish(jpeg, size.width, size.height) { [weak self] commit in
                    guard let self else { return false }
                    return self.withState {
                        guard permit.open && !permit.consumed, self.running,
                              self.owner == channel.id, self.clients[channel.id] === channel,
                              frameTimeline.freshAt(capture: header.captureMilliseconds, arrival: Self.nowMs()) else { return false }
                        permit.consumed = true
                        guard commit({
                            // Synchronous inside commit; stateLock is already held.
                            permit.open && self.running && self.owner == channel.id &&
                            self.clients[channel.id] === channel &&
                            frameTimeline.freshAt(capture: header.captureMilliseconds, arrival: Self.nowMs())
                        }) else { return false }
                        guard frameTimeline.freshAt(capture: header.captureMilliseconds, arrival: Self.nowMs()) else {
                            self.clear(); self.blank = true; self.lastPublished = 0; return false
                        }
                        reportLive = self.blank || lastSize != size
                        permit.committed = true; self.blank = false; self.lastPublished = Self.nowMs()
                        return true
                    }
                }
                let committed = withState { permit.open = false; return permit.committed }
                try receipt(channel, version: version, sequence: header.sequence, state: committed ? 1 : 2)
                if committed {
                    lastSize = size
                    if reportLive { onStatus("Avatar video live · \(size.width) × \(size.height) source") }
                }
            }
        } catch {
            if !ownsVideo && isActive(channel) { reject(channel.peer) }
            // The public message is intentionally coarse; no address, certificate,
            // PIN, compressed image or native handle is logged.
        }
    }

    private func pair(_ channel: AvatarTLSChannel) throws {
        let attemptID = UUID()
        let reserved = withState { () -> Bool in
            guard running && pairingOwner == nil && clients[channel.id] === channel else { return false }
            pairingOwner = (channel.id, attemptID); return true
        }
        guard reserved else { try channel.write(Data([0])); reject(channel.peer); return }
        defer {
            let shouldCancel = withState { () -> Bool in
                guard pairingOwner?.channel == channel.id && pairingOwner?.attempt == attemptID else { return false }
                pairingOwner = nil; return true
            }
            if shouldCancel { cancelPairing(attemptID) }
        }
        let clientNonce = try channel.read(32, deadline: channel.authenticationDeadline)
        var serverNonce = Data(count: 32)
        let randomResult = serverNonce.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, 32, buffer.baseAddress!)
        }
        guard randomResult == errSecSuccess else { throw AvatarReceiverError.randomUnavailable }
        let code = try AvatarProtocol.pairingCode(fingerprint: identity.fingerprint,
            clientNonce: clientNonce, serverNonce: serverNonce)
        let decision = AvatarPairingDecision(), deadline = DispatchTime.now() + .seconds(90)
        confirmPairing(attemptID, code) { decision.finish($0) }
        try channel.write(Data([1]) + serverNonce)
        let remoteApproval = try channel.read(1, deadline: deadline)
        guard remoteApproval.first == 1 else { try? channel.write(Data([0])); reject(channel.peer); return }
        while decision.signal.wait(timeout: .now() + .milliseconds(100)) != .success {
            guard isActive(channel), DispatchTime.now() < deadline else {
                try? channel.write(Data([0])); reject(channel.peer); return
            }
        }
        guard decision.approved && isActive(channel) && DispatchTime.now() <= deadline else {
            try? channel.write(Data([0])); reject(channel.peer); return
        }
        try channel.write(Data([1]) + Data(identity.pin.utf8))
        onStatus("Codes confirmed on both devices · waiting for Quest video")
    }

    private func receipt(_ channel: AvatarTLSChannel, version: AvatarProtocolVersion,
                         sequence: Int64, state: UInt8) throws {
        if version.hasReceipts { try channel.write(AvatarProtocol.receipt(sequence: sequence, state: state)) }
    }

    private func pauseImage(ownerID: UUID) {
        let changed = withState { () -> Bool in
            guard running && owner == ownerID && !blank else { return false }
            clear(); blank = true; lastPublished = 0; return true
        }
        if changed { onStatus("Quest connected · avatar paused · waiting for a fresh mirror image") }
    }

    private func expireImage() {
        let changed = withState { () -> Bool in
            guard running && owner != nil && !blank && Self.nowMs() - lastPublished > 2000 else { return false }
            clear(); blank = true; lastPublished = 0; return true
        }
        if changed { onStatus("Avatar paused · old image cleared · waiting for Quest") }
    }

    deinit { stop() }
}
