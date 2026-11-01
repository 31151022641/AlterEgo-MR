import XCTest
import Security
@testable import AlterEgoMac

final class TransportCertificateTests: XCTestCase {
    func testSelfSignedTransportCertificateParsesAndVerifiesWithoutSavingKeys() throws {
        var error: Unmanaged<CFError>?
        let key = try XCTUnwrap(SecKeyCreateRandomKey([
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048] as CFDictionary, &error))
        let der = try CertificateDER.selfSigned(key: key)
        let certificate = try XCTUnwrap(SecCertificateCreateWithData(nil, der as CFData))
        XCTAssertEqual(SecCertificateCopySubjectSummary(certificate) as String?, "AlterEgo MR Mac Webcam")
        var trust: SecTrust?
        XCTAssertEqual(SecTrustCreateWithCertificates(certificate, SecPolicyCreateBasicX509(), &trust), errSecSuccess)
        let verifier = try XCTUnwrap(trust)
        XCTAssertEqual(SecTrustSetAnchorCertificates(verifier, [certificate] as CFArray), errSecSuccess)
        XCTAssertEqual(SecTrustSetAnchorCertificatesOnly(verifier, true), errSecSuccess)
        var trustError: CFError?
        XCTAssertTrue(SecTrustEvaluateWithError(verifier, &trustError), "Generated RSA certificate must have a valid self-signature")
        // No isPermanent attribute: this fixture never persists a private key.
    }
}
