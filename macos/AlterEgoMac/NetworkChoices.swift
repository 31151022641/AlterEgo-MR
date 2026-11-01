import Foundation
import Darwin
import SystemConfiguration

struct NetworkChoice: Identifiable, Hashable {
    var id: String { address }
    let name: String
    let address: String
    var title: String { "\(name) · \(address)" }
    static func available() -> [NetworkChoice] {
        var names: [String: String] = [:]
        if let interfaces = SCNetworkInterfaceCopyAll() as? [SCNetworkInterface] {
            for interface in interfaces {
                if let bsd = SCNetworkInterfaceGetBSDName(interface) as String?,
                   let display = SCNetworkInterfaceGetLocalizedDisplayName(interface) as String? { names[bsd] = display }
            }
        }
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0 else { return [] }
        defer { freeifaddrs(head) }
        var cursor = head; var result: [NetworkChoice] = []
        while let current = cursor {
            defer { cursor = current.pointee.ifa_next }
            guard let addr = current.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  current.pointee.ifa_flags & UInt32(IFF_UP) != 0,
                  current.pointee.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host); let name = String(cString: current.pointee.ifa_name)
            let parts = ip.split(separator: ".").compactMap { Int($0) }
            guard parts.count == 4, parts[0] == 10 || parts[0] == 192 && parts[1] == 168 ||
                    parts[0] == 172 && (16...31).contains(parts[1]) else { continue }
            if !result.contains(where: { $0.address == ip }) { result.append(NetworkChoice(name: names[name] ?? name, address: ip)) }
        }
        return result.sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
    }
}
