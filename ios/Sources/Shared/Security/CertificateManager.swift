import CryptoKit
import Foundation
import Security

enum CertificateError: LocalizedError {
    case keyGeneration(String)
    case signing(String)
    case certificate
    case keychain(OSStatus)
    case publicKey

    var errorDescription: String? {
        switch self {
        case .keyGeneration(let detail): return L10n.f("error.cert.key %@", detail)
        case .signing(let detail): return L10n.f("error.cert.sign %@", detail)
        case .certificate: return L10n.t("error.cert.make")
        case .keychain(let status): return L10n.f("error.cert.keychain %lld", Int64(status))
        case .publicKey: return L10n.t("error.cert.publicKey")
        }
    }
}

/// Makes and keeps the self-signed client certificate that the box uses to
/// remember this phone. The private key and the certificate live in the keychain,
/// in the shared access group (SharedConfig.keychainGroup), so that the widget
/// extension can use the same identity.
final class CertificateManager {
    static let shared = CertificateManager()

    private let keyTag = Data("com.tvboxremote.ios.clientkey".utf8)
    private let certificateLabel = "TV Box Remote client"
    private let lock = NSLock()
    private var cachedIdentity: SecIdentity?
    private let accessGroup: String? = SharedConfig.keychainGroup

    /// Returns the client identity. Makes a new key and certificate on first use.
    func identity() throws -> SecIdentity {
        lock.lock()
        defer { lock.unlock() }
        if let cachedIdentity { return cachedIdentity }
        moveOldItemsToSharedGroup()
        if let found = findIdentity() {
            cachedIdentity = found
            return found
        }
        try createIdentity()
        guard let created = findIdentity() else { throw CertificateError.keychain(errSecItemNotFound) }
        cachedIdentity = created
        return created
    }

    /// Returns the client identity only if the app already made it. The widget uses
    /// this: a new key would not match the pairing that the box stored.
    func existingIdentity() -> SecIdentity? {
        lock.lock()
        defer { lock.unlock() }
        if let cachedIdentity { return cachedIdentity }
        guard let found = findIdentity() else { return nil }
        cachedIdentity = found
        return found
    }

    func clientCertificate() throws -> SecCertificate {
        let identity = try identity()
        var certificate: SecCertificate?
        let status = SecIdentityCopyCertificate(identity, &certificate)
        guard status == errSecSuccess, let certificate else { throw CertificateError.keychain(status) }
        return certificate
    }

    // MARK: - Keychain

    private func findIdentity() -> SecIdentity? {
        var query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let item, CFGetTypeID(item) == SecIdentityGetTypeID() else {
            return nil
        }
        return (item as! SecIdentity)
    }

    /// Builds of the app before the widget kept the key and the certificate in the
    /// default access group of the app. The widget cannot read that group, so move
    /// both items to the shared group. Does nothing when they are already there.
    private func moveOldItemsToSharedGroup() {
        guard let accessGroup else { return }
        // Older items have the default class (when unlocked). The widget also runs on a
        // locked phone (StandBy), so the moved items get the class of new items.
        let change = [
            kSecAttrAccessGroup as String: accessGroup,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ] as CFDictionary
        let keyQuery: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
        ]
        let certificateQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certificateLabel,
        ]
        for query in [keyQuery, certificateQuery] {
            var probe = query
            probe[kSecAttrAccessGroup as String] = accessGroup
            if SecItemCopyMatching(probe as CFDictionary, nil) == errSecSuccess { continue }
            _ = SecItemUpdate(query as CFDictionary, change)
        }
    }

    private func deleteOldItems() {
        var keyQuery: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
        ]
        var certificateQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrLabel as String: certificateLabel,
        ]
        if let accessGroup {
            keyQuery[kSecAttrAccessGroup as String] = accessGroup
            certificateQuery[kSecAttrAccessGroup as String] = accessGroup
        }
        _ = SecItemDelete(keyQuery as CFDictionary)
        _ = SecItemDelete(certificateQuery as CFDictionary)
    }

    private func createIdentity() throws {
        deleteOldItems()

        var privateAttributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: keyTag,
            // The widget sends keys while the phone is locked too.
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if let accessGroup { privateAttributes[kSecAttrAccessGroup as String] = accessGroup }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: privateAttributes,
        ]
        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let detail = error.map { String(describing: $0.takeRetainedValue()) } ?? ""
            throw CertificateError.keyGeneration(detail)
        }
        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let pkcs1 = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?
        else {
            throw CertificateError.publicKey
        }

        let der = try Self.selfSignedCertificate(
            publicKeyPKCS1: [UInt8](pkcs1),
            commonName: "atvremote",
            sign: { tbs in
                var signError: Unmanaged<CFError>?
                guard let signature = SecKeyCreateSignature(
                    privateKey,
                    .rsaSignatureMessagePKCS1v15SHA256,
                    Data(tbs) as CFData,
                    &signError
                ) as Data? else {
                    let detail = signError.map { String(describing: $0.takeRetainedValue()) } ?? ""
                    throw CertificateError.signing(detail)
                }
                return [UInt8](signature)
            }
        )

        guard let certificate = SecCertificateCreateWithData(nil, Data(der) as CFData) else {
            throw CertificateError.certificate
        }
        var add: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecValueRef as String: certificate,
            kSecAttrLabel as String: certificateLabel,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if let accessGroup { add[kSecAttrAccessGroup as String] = accessGroup }
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw CertificateError.keychain(status)
        }
    }

    // MARK: - Certificate building

    /// Builds a DER X.509 v3 certificate, signed with sha256WithRSAEncryption.
    static func selfSignedCertificate(
        publicKeyPKCS1: [UInt8],
        commonName: String,
        now: Date = Date(),
        sign: ([UInt8]) throws -> [UInt8]
    ) throws -> [UInt8] {
        let sha256WithRSA = DER.sequence(
            DER.objectIdentifier([1, 2, 840, 113_549, 1, 1, 11]),
            DER.null
        )
        let rsaEncryption = DER.sequence(
            DER.objectIdentifier([1, 2, 840, 113_549, 1, 1, 1]),
            DER.null
        )
        let name = DER.sequence(
            DER.set(
                DER.sequence(
                    DER.objectIdentifier([2, 5, 4, 3]),
                    DER.utf8String(commonName)
                )
            )
        )
        let notBefore = now.addingTimeInterval(-86_400)
        let notAfter = now.addingTimeInterval(20 * 365 * 86_400)
        let validity = DER.sequence(DER.utcTime(notBefore), DER.utcTime(notAfter))
        let subjectPublicKeyInfo = DER.sequence(rsaEncryption, DER.bitString(publicKeyPKCS1))

        var serial = [UInt8](repeating: 0, count: 16)
        for index in serial.indices {
            serial[index] = UInt8.random(in: 0...255)
        }
        serial[0] = (serial[0] & 0x7F) | 0x01

        let tbs = DER.sequence(
            DER.explicit(0, DER.integer(2)),
            DER.integer(unsigned: serial),
            sha256WithRSA,
            name,
            validity,
            name,
            subjectPublicKeyInfo
        )
        let signature = try sign(tbs)
        return DER.sequence(tbs, sha256WithRSA, DER.bitString(signature))
    }
}

// MARK: - Certificate helpers

enum Certificates {
    /// Lower-case hexadecimal SHA-256 of the DER certificate.
    static func fingerprint(_ certificate: SecCertificate) -> String {
        let der = SecCertificateCopyData(certificate) as Data
        return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    static func publicNumbers(_ certificate: SecCertificate) throws -> RSAPublicNumbers {
        guard let key = SecCertificateCopyKey(certificate) else { throw CertificateError.publicKey }
        return try publicNumbers(key)
    }

    static func publicNumbers(_ key: SecKey) throws -> RSAPublicNumbers {
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw CertificateError.publicKey
        }
        return try RSAPublicNumbers(der: data)
    }
}

// MARK: - Pairing secret

enum PairingSecret {
    enum Result: Equatable {
        case secret(Data)
        case invalidFormat
        case mismatch
    }

    /// Computes the pairing secret from the code that the TV shows.
    /// See docs/ANDROID_TV_REMOTE_PROTOCOL.md, "Pairing secret".
    static func compute(code: String, client: RSAPublicNumbers, server: RSAPublicNumbers) -> Result {
        let clean = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard clean.count == 6, let codeBytes = hexBytes(clean), codeBytes.count == 3 else {
            return .invalidFormat
        }
        var hasher = SHA256()
        hasher.update(data: client.modulus)
        hasher.update(data: client.exponent)
        hasher.update(data: server.modulus)
        hasher.update(data: server.exponent)
        hasher.update(data: Array(codeBytes[1...]))
        let digest = Array(hasher.finalize())
        guard digest[0] == codeBytes[0] else { return .mismatch }
        return .secret(Data(digest))
    }

    static func hexBytes(_ hex: String) -> [UInt8]? {
        let characters = Array(hex)
        guard characters.count % 2 == 0 else { return nil }
        var result: [UInt8] = []
        var index = 0
        while index < characters.count {
            guard let byte = UInt8(String(characters[index...index + 1]), radix: 16) else { return nil }
            result.append(byte)
            index += 2
        }
        return result
    }
}
