import CryptoKit
import Security
import XCTest
@testable import TVBoxRemote

final class SecurityTests: XCTestCase {
    private func makeKey() throws -> SecKey {
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecAttrIsPermanent as String: false,
        ]
        var error: Unmanaged<CFError>?
        return try XCTUnwrap(SecKeyCreateRandomKey(attributes as CFDictionary, &error))
    }

    private func pkcs1(_ privateKey: SecKey) throws -> [UInt8] {
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(privateKey))
        var error: Unmanaged<CFError>?
        let data = try XCTUnwrap(SecKeyCopyExternalRepresentation(publicKey, &error) as Data?)
        return [UInt8](data)
    }

    // MARK: - DER

    func testDERLengthEncoding() {
        XCTAssertEqual(DER.length(0), [0x00])
        XCTAssertEqual(DER.length(0x7F), [0x7F])
        XCTAssertEqual(DER.length(0x80), [0x81, 0x80])
        XCTAssertEqual(DER.length(0xFF), [0x81, 0xFF])
        XCTAssertEqual(DER.length(0x100), [0x82, 0x01, 0x00])
        XCTAssertEqual(DER.length(70_000), [0x83, 0x01, 0x11, 0x70])
    }

    func testDERIntegerAddsSignByte() {
        XCTAssertEqual(DER.integer(2), [0x02, 0x01, 0x02])
        XCTAssertEqual(DER.integer(unsigned: [0x00, 0x80]), [0x02, 0x02, 0x00, 0x80])
        XCTAssertEqual(DER.integer(unsigned: [0x00, 0x00, 0x7F]), [0x02, 0x01, 0x7F])
    }

    func testObjectIdentifierEncoding() {
        // sha256WithRSAEncryption
        XCTAssertEqual(
            DER.objectIdentifier([1, 2, 840, 113_549, 1, 1, 11]),
            [0x06, 0x09, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B]
        )
    }

    func testDERReaderReadsLongLength() throws {
        let content = [UInt8](repeating: 7, count: 300)
        var reader = DERReader(DER.tlv(0x04, content))
        let item = try reader.read()
        XCTAssertEqual(item.tag, 0x04)
        XCTAssertEqual(item.content, content)
        XCTAssertTrue(reader.isAtEnd)
    }

    func testSelfSignedCertificateIsAcceptedBySecurity() throws {
        let privateKey = try makeKey()
        let der = try CertificateManager.selfSignedCertificate(
            publicKeyPKCS1: try pkcs1(privateKey),
            commonName: "atvremote"
        ) { tbs in
            var error: Unmanaged<CFError>?
            let signature = SecKeyCreateSignature(privateKey, .rsaSignatureMessagePKCS1v15SHA256, Data(tbs) as CFData, &error)
            return [UInt8](try XCTUnwrap(signature as Data?))
        }
        let certificate = try XCTUnwrap(SecCertificateCreateWithData(nil, Data(der) as CFData))
        XCTAssertEqual(SecCertificateCopySubjectSummary(certificate) as String?, "atvremote")

        // The key in the certificate is the key that we generated.
        let fromCertificate = try Certificates.publicNumbers(certificate)
        let publicKey = try XCTUnwrap(SecKeyCopyPublicKey(privateKey))
        XCTAssertEqual(fromCertificate, try Certificates.publicNumbers(publicKey))
        XCTAssertEqual(Certificates.fingerprint(certificate).count, 64)
    }

    func testRSAPublicNumbersFromSPKI() throws {
        let pkcs1 = DER.sequence(DER.integer(unsigned: [0x00, 0xC1, 0x02]), DER.integer(65_537))
        let spki = DER.sequence(
            DER.sequence(DER.objectIdentifier([1, 2, 840, 113_549, 1, 1, 1]), DER.null),
            DER.bitString(pkcs1)
        )
        let numbers = try RSAPublicNumbers(der: Data(spki))
        XCTAssertEqual(numbers.modulus, [0xC1, 0x02])
        XCTAssertEqual(numbers.exponent, [0x01, 0x00, 0x01])
        XCTAssertEqual(try RSAPublicNumbers(der: Data(pkcs1)), numbers)
    }

    // MARK: - Pairing secret

    /// Independent extraction: a 2048-bit PKCS#1 key with exponent 65537 is
    /// 30 82 01 0A | 02 82 01 01 00 <256-byte modulus> | 02 03 01 00 01.
    private func manualNumbers(_ bytes: [UInt8]) -> (modulus: [UInt8], exponent: [UInt8]) {
        XCTAssertEqual(bytes.count, 270)
        XCTAssertEqual(Array(bytes[4..<9]), [0x02, 0x82, 0x01, 0x01, 0x00])
        XCTAssertEqual(Array(bytes[265..<267]), [0x02, 0x03])
        return (Array(bytes[9..<265]), Array(bytes[267..<270]))
    }

    func testPairingSecretMatchesManualConcatenation() throws {
        let clientBytes = try pkcs1(try makeKey())
        let serverBytes = try pkcs1(try makeKey())
        let client = manualNumbers(clientBytes)
        let server = manualNumbers(serverBytes)
        XCTAssertEqual(client.exponent, [0x01, 0x00, 0x01])

        var input: [UInt8] = []
        input += client.modulus
        input += client.exponent
        input += server.modulus
        input += server.exponent
        input += [0xA1, 0xB2]
        let expected = Data(SHA256.hash(data: input))
        let code = String(format: "%02X", expected[0]) + "a1b2"

        let clientNumbers = try RSAPublicNumbers(der: Data(clientBytes))
        let serverNumbers = try RSAPublicNumbers(der: Data(serverBytes))
        XCTAssertEqual(clientNumbers.modulus, client.modulus)
        XCTAssertEqual(
            PairingSecret.compute(code: code, client: clientNumbers, server: serverNumbers),
            .secret(expected)
        )

        let wrongFirstByte = String(format: "%02X", expected[0] ^ 0xFF) + "A1B2"
        XCTAssertEqual(
            PairingSecret.compute(code: wrongFirstByte, client: clientNumbers, server: serverNumbers),
            .mismatch
        )
    }

    func testPairingSecretRejectsBadFormat() {
        let numbers = RSAPublicNumbers(modulus: [0x01], exponent: [0x03])
        XCTAssertEqual(PairingSecret.compute(code: "12345", client: numbers, server: numbers), .invalidFormat)
        XCTAssertEqual(PairingSecret.compute(code: "12345G", client: numbers, server: numbers), .invalidFormat)
    }
}
