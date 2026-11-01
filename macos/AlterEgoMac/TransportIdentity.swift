import Foundation
import Security
import CryptoKit

enum MacAppError: LocalizedError {
    case message(String)
    var errorDescription: String? { if case let .message(text) = self { return text }; return nil }
}

/// Self-signed LAN transport identity. Its private RSA key and pairing manifest
/// stay in the user's login Keychain, independently of application upgrades.
/// This certificate is unrelated to Apple's application signing certificate.
final class TransportIdentity {
    let identity: SecIdentity
    let fingerprint: String
    let pin: String
    var pairingKey: String { "\(pin)-\(fingerprint)" }
    private static let service = "com.misterslee.alterego.mac.transport"
    private static let account = "identity-v1"

    private struct Manifest: Codable { let keyTag: String; let certificate: Data; let pin: String }

    private init(identity: SecIdentity, certificate: SecCertificate, pin: String) {
        self.identity = identity; self.pin = pin
        fingerprint = SHA256.hash(data: SecCertificateCopyData(certificate) as Data)
            .map { String(format: "%02X", $0) }.joined()
    }

    static func loadOrCreate(reset: Bool = false) throws -> TransportIdentity {
        let old: Manifest?
        if reset { old = try? readManifest() } else { old = try readManifest() }
        if !reset, let saved = old { return try restore(saved) }
        let tag = service + "." + UUID().uuidString
        var error: Unmanaged<CFError>?
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data(tag.utf8), kSecAttrLabel as String: tag]]
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let failure = error?.takeRetainedValue() { throw failure }
            throw MacAppError.message("Could not create the Mac pairing identity.")
        }
        do {
            let der = try CertificateDER.selfSigned(key: key)
            guard let cert = SecCertificateCreateWithData(nil, der as CFData) else {
                throw MacAppError.message("Could not create the transport certificate.")
            }
            let added = SecItemAdd([kSecClass as String: kSecClassCertificate,
                kSecValueRef as String: cert, kSecAttrLabel as String: tag] as CFDictionary, nil)
            guard added == errSecSuccess else { throw keychainError(added) }
            let pin = String(100000 + arc4random_uniform(900000))
            let manifest = Manifest(keyTag: tag, certificate: der, pin: pin)
            let result = try restore(manifest)
            try saveManifest(manifest)
            if let previous = old { remove(previous) }
            return result
        } catch {
            SecItemDelete([kSecClass as String: kSecClassKey,
                kSecAttrApplicationTag as String: Data(tag.utf8)] as CFDictionary)
            SecItemDelete([kSecClass as String: kSecClassCertificate,
                kSecAttrLabel as String: tag] as CFDictionary)
            throw error
        }
    }

    private static func restore(_ saved: Manifest) throws -> TransportIdentity {
        guard saved.pin.count == 6, saved.pin.allSatisfy({ $0.isASCII && $0.isNumber }),
              let certificate = SecCertificateCreateWithData(nil, saved.certificate as CFData) else {
            throw MacAppError.message("Pairing identity is damaged. Choose Reset pairing and pair again.")
        }
        var trust: SecTrust?
        guard SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust) == errSecSuccess,
              let verifier = trust,
              SecTrustSetAnchorCertificates(verifier, [certificate] as CFArray) == errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(verifier, true) == errSecSuccess,
              SecTrustSetNetworkFetchAllowed(verifier, false) == errSecSuccess,
              SecTrustEvaluateWithError(verifier, nil) else {
            throw MacAppError.message("Pairing identity expired or is invalid. Choose Reset pairing and pair again.")
        }
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        guard status == errSecSuccess, let identity = identity else { throw keychainError(status) }
        return TransportIdentity(identity: identity, certificate: certificate, pin: saved.pin)
    }

    private static var manifestQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account]
    }
    private static func readManifest() throws -> Manifest? {
        var query = manifestQuery; query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw keychainError(status) }
        do { return try JSONDecoder().decode(Manifest.self, from: data) }
        catch { throw MacAppError.message("Pairing identity is damaged. Choose Reset pairing.") }
    }
    private static func saveManifest(_ manifest: Manifest) throws {
        let data = try JSONEncoder().encode(manifest)
        let status = SecItemUpdate(manifestQuery as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var query = manifestQuery; query[kSecValueData as String] = data
            let added = SecItemAdd(query as CFDictionary, nil)
            guard added == errSecSuccess else { throw keychainError(added) }
        } else if status != errSecSuccess { throw keychainError(status) }
    }
    private static func remove(_ manifest: Manifest) {
        SecItemDelete([kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: manifest.keyTag] as CFDictionary)
        SecItemDelete([kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: Data(manifest.keyTag.utf8)] as CFDictionary)
    }
    private static func keychainError(_ status: OSStatus) -> Error {
        MacAppError.message((SecCopyErrorMessageString(status, nil) as String?) ?? "Keychain error \(status)")
    }
}

/// Minimal DER X.509 certificate generation using Security's RSA signer; no
/// OpenSSL subprocess, temporary private-key file, network CA or shell command.
enum CertificateDER {
    private static func value(_ tag: UInt8, _ body: Data) -> Data {
        var result = Data([tag])
        if body.count < 128 { result.append(UInt8(body.count)) }
        else {
            var n = body.count; var bytes: [UInt8] = []
            while n > 0 { bytes.insert(UInt8(n & 255), at: 0); n >>= 8 }
            result.append(0x80 | UInt8(bytes.count)); result.append(contentsOf: bytes)
        }
        result.append(body); return result
    }
    private static func sequence(_ parts: Data...) -> Data { value(0x30, parts.reduce(Data(), +)) }
    private static func oid(_ bytes: [UInt8]) -> Data { value(0x06, Data(bytes)) }
    private static let rsaSHA256 = sequence(oid([0x2A,0x86,0x48,0x86,0xF7,0x0D,0x01,0x01,0x0B]), Data([0x05,0x00]))
    private static func time(_ date: Date) -> Data {
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US_POSIX")
        format.timeZone = TimeZone(secondsFromGMT: 0)
        let year = Calendar(identifier: .gregorian).component(.year, from: date)
        format.dateFormat = year < 2050 ? "yyMMddHHmmss'Z'" : "yyyyMMddHHmmss'Z'"
        return value(year < 2050 ? 0x17 : 0x18, Data(format.string(from: date).utf8))
    }
    static func selfSigned(key: SecKey) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(key) else { throw MacAppError.message("RSA public key is unavailable.") }
        var failure: Unmanaged<CFError>?
        guard let publicDER = SecKeyCopyExternalRepresentation(publicKey, &failure) as Data? else {
            if let error = failure?.takeRetainedValue() { throw error }
            throw MacAppError.message("Could not encode the RSA public key.")
        }
        var serial = [UInt8](repeating: 0, count: 20)
        guard SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial) == errSecSuccess else {
            throw MacAppError.message("Secure random generation failed.")
        }
        serial[0] &= 0x7F; serial[0] |= 1
        let name = sequence(value(0x31, sequence(oid([0x55,0x04,0x03]),
            value(0x0C, Data("AlterEgo MR Mac Webcam".utf8)))))
        let spki = sequence(sequence(oid([0x2A,0x86,0x48,0x86,0xF7,0x0D,0x01,0x01,0x01]), Data([0x05,0x00])),
            value(0x03, Data([0]) + publicDER))
        let basic = sequence(oid([0x55,0x1D,0x13]), Data([0x01,0x01,0xFF]), value(0x04, sequence()))
        let usage = sequence(oid([0x55,0x1D,0x0F]), Data([0x01,0x01,0xFF]), value(0x04, value(0x03, Data([5,0xA0]))))
        let server = sequence(oid([0x55,0x1D,0x25]), value(0x04, sequence(oid([0x2B,0x06,0x01,0x05,0x05,0x07,0x03,0x01]))))
        let now = Date()
        let tbs = sequence(value(0xA0, value(0x02, Data([2]))), value(0x02, Data(serial)), rsaSHA256,
            name, sequence(time(now.addingTimeInterval(-86400)), time(now.addingTimeInterval(5*365*86400))),
            name, spki, value(0xA3, sequence(basic, usage, server)))
        guard let signature = SecKeyCreateSignature(key, .rsaSignatureMessagePKCS1v15SHA256, tbs as CFData, &failure) as Data? else {
            if let error = failure?.takeRetainedValue() { throw error }
            throw MacAppError.message("Could not sign the transport certificate.")
        }
        return sequence(tbs, rsaSHA256, value(0x03, Data([0]) + signature))
    }
}
