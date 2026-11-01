import Foundation
import Darwin

enum LanDiscoveryError: Error, LocalizedError {
    case invalidAddress, socket(String), alreadyRunning
    var errorDescription: String? {
        switch self {
        case .invalidAddress: return "Choose the private IPv4 network shared with your Quest."
        case .socket(let reason): return "Quest discovery could not start: \(reason)"
        case .alreadyRunning: return "Quest discovery is already running."
        }
    }
}

/// The existing Quest UDP discovery format. This is an untrusted endpoint hint;
/// only certificate pinning and the independent comparison-code approvals grant trust.
final class LanDiscovery {
    private let queue = DispatchQueue(label: "com.misterslee.alterego.discovery")
    private let queueKey = DispatchSpecificKey<Void>()
    private var source: DispatchSourceRead?
    private var descriptor: Int32 = -1
    private var advertisedAddress = ""
    private var fingerprint = ""
    private var windowStarted: UInt64 = 0
    private var requests = 0
    private let displayName = ProcessInfo.processInfo.hostName

    init() { queue.setSpecific(key: queueKey, value: ()) }

    func start(address: String, fingerprint: String) throws {
        guard AvatarProtocol.privateIPv4(address), AvatarProtocol.hexBytes(fingerprint)?.count == 32 else {
            throw LanDiscoveryError.invalidAddress
        }
        try synchronized {
            guard source == nil else { throw LanDiscoveryError.alreadyRunning }
            let fd = Darwin.socket(AF_INET, SOCK_DGRAM, 0)
            guard fd >= 0 else { throw socketError() }
            do {
                let flags = fcntl(fd, F_GETFL)
                guard flags >= 0, fcntl(fd, F_SETFL, flags | O_NONBLOCK) >= 0 else { throw socketError() }
                var local = sockaddr_in()
                local.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
                local.sin_family = sa_family_t(AF_INET)
                local.sin_port = AvatarProtocol.discoveryPort.bigEndian
                local.sin_addr.s_addr = INADDR_ANY
                let result = withUnsafePointer(to: &local) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
                guard result == 0 else { throw socketError() }
            } catch { Darwin.close(fd); throw error }
            descriptor = fd; advertisedAddress = address; self.fingerprint = fingerprint
            windowStarted = DispatchTime.now().uptimeNanoseconds; requests = 0
            let reader = DispatchSource.makeReadSource(fileDescriptor: fd, queue: queue)
            reader.setEventHandler { [weak self] in self?.receiveAvailable(fd: fd) }
            reader.setCancelHandler { Darwin.close(fd) }
            source = reader
            reader.resume()
        }
    }

    func stop() {
        // Closing in the cancel handler prevents descriptor reuse underneath a
        // dispatched read. Wait only for that tiny queue operation, never network IO.
        let isOnQueue = DispatchQueue.getSpecific(key: queueKey) != nil
        let completion: DispatchSemaphore? = synchronized {
            guard let reader = source else { return nil }
            let fd = descriptor, finished = DispatchSemaphore(value: 0)
            source = nil; descriptor = -1
            reader.setCancelHandler { Darwin.close(fd); finished.signal() }
            reader.cancel()
            return finished
        }
        if !isOnQueue { completion?.wait() }
    }

    private func synchronized<T>(_ body: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil { return try body() }
        return try queue.sync(execute: body)
    }

    private func socketError() -> LanDiscoveryError { .socket(String(cString: strerror(errno))) }

    private func receiveAvailable(fd: Int32) {
        guard descriptor == fd else { return }
        // Bound work per dispatch, as well as replies per minute.
        for _ in 0..<32 {
            var packet = [UInt8](repeating: 0, count: 256)
            var peer = sockaddr_in(), peerLength = socklen_t(MemoryLayout<sockaddr_in>.size)
            let count = packet.withUnsafeMutableBytes { bytes in
                withUnsafeMutablePointer(to: &peer) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        Darwin.recvfrom(fd, bytes.baseAddress, bytes.count, 0, $0, &peerLength)
                    }
                }
            }
            if count < 0 { return } // EAGAIN is normal; a stopped socket needs no reply.
            let now = DispatchTime.now().uptimeNanoseconds
            if now - windowStarted > 60_000_000_000 { windowStarted = now; requests = 0 }
            requests += 1
            guard requests <= 120, count == 47, peer.sin_family == sa_family_t(AF_INET) else { continue }
            var ip = peer.sin_addr
            var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            let converted = withUnsafePointer(to: &ip) { pointer in
                text.withUnsafeMutableBufferPointer { buffer in
                    inet_ntop(AF_INET, pointer, buffer.baseAddress, socklen_t(buffer.count)) != nil
                }
            }
            guard converted else { continue }
            let peerAddress = String(cString: text)
            guard let reply = AvatarProtocol.discoveryReply(request: Data(packet.prefix(count)),
                peerAddress: peerAddress, address: advertisedAddress, fingerprint: fingerprint, name: displayName) else { continue }
            reply.withUnsafeBytes { bytes in
                withUnsafePointer(to: &peer) { pointer in
                    pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        _ = Darwin.sendto(fd, bytes.baseAddress, bytes.count, 0, $0, peerLength)
                    }
                }
            }
        }
    }

    deinit { stop() }
}
