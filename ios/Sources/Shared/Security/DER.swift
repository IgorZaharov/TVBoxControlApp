import Foundation

/// Minimal DER writer, enough to build a self-signed X.509 certificate.
enum DER {
    static func length(_ count: Int) -> [UInt8] {
        if count < 0x80 {
            return [UInt8(count)]
        }
        var bytes: [UInt8] = []
        var value = count
        while value > 0 {
            bytes.insert(UInt8(value & 0xFF), at: 0)
            value >>= 8
        }
        return [0x80 | UInt8(bytes.count)] + bytes
    }

    static func tlv(_ tag: UInt8, _ content: [UInt8]) -> [UInt8] {
        [tag] + length(content.count) + content
    }

    static func sequence(_ items: [UInt8]...) -> [UInt8] {
        tlv(0x30, items.flatMap { $0 })
    }

    static func set(_ items: [UInt8]...) -> [UInt8] {
        tlv(0x31, items.flatMap { $0 })
    }

    /// Encodes an unsigned big-endian integer.
    static func integer(unsigned bytes: [UInt8]) -> [UInt8] {
        var value = Array(bytes.drop { $0 == 0 })
        if value.isEmpty { value = [0] }
        if value[0] & 0x80 != 0 { value.insert(0, at: 0) }
        return tlv(0x02, value)
    }

    static func integer(_ value: Int) -> [UInt8] {
        precondition(value >= 0)
        var bytes: [UInt8] = []
        var v = value
        repeat {
            bytes.insert(UInt8(v & 0xFF), at: 0)
            v >>= 8
        } while v > 0
        return integer(unsigned: bytes)
    }

    static func objectIdentifier(_ components: [UInt]) -> [UInt8] {
        precondition(components.count >= 2)
        var body: [UInt8] = [UInt8(components[0] * 40 + components[1])]
        for component in components.dropFirst(2) {
            var chunk: [UInt8] = [UInt8(component & 0x7F)]
            var v = component >> 7
            while v > 0 {
                chunk.insert(UInt8(v & 0x7F) | 0x80, at: 0)
                v >>= 7
            }
            body += chunk
        }
        return tlv(0x06, body)
    }

    static let null: [UInt8] = [0x05, 0x00]

    static func bitString(_ content: [UInt8]) -> [UInt8] {
        tlv(0x03, [0x00] + content)
    }

    static func utf8String(_ string: String) -> [UInt8] {
        tlv(0x0C, Array(string.utf8))
    }

    static func utcTime(_ date: Date) -> [UInt8] {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return tlv(0x17, Array(formatter.string(from: date).utf8))
    }

    static func explicit(_ number: UInt8, _ content: [UInt8]) -> [UInt8] {
        tlv(0xA0 | number, content)
    }
}

enum DERError: Error {
    case malformed
}

/// Minimal DER reader.
struct DERReader {
    private let bytes: [UInt8]
    private var position = 0

    init(_ bytes: [UInt8]) {
        self.bytes = bytes
    }

    var isAtEnd: Bool { position >= bytes.count }

    mutating func read() throws -> (tag: UInt8, content: [UInt8]) {
        guard position < bytes.count else { throw DERError.malformed }
        let tag = bytes[position]
        position += 1
        guard position < bytes.count else { throw DERError.malformed }
        var length = Int(bytes[position])
        position += 1
        if length & 0x80 != 0 {
            let count = length & 0x7F
            guard count > 0, count <= 4, position + count <= bytes.count else { throw DERError.malformed }
            length = 0
            for _ in 0..<count {
                length = (length << 8) | Int(bytes[position])
                position += 1
            }
        }
        guard length <= bytes.count - position else { throw DERError.malformed }
        let content = Array(bytes[position..<(position + length)])
        position += length
        return (tag, content)
    }
}

/// RSA public key numbers as unsigned big-endian bytes with no leading zero bytes.
struct RSAPublicNumbers: Equatable {
    let modulus: [UInt8]
    let exponent: [UInt8]

    init(modulus: [UInt8], exponent: [UInt8]) {
        self.modulus = Array(modulus.drop { $0 == 0 })
        self.exponent = Array(exponent.drop { $0 == 0 })
    }

    /// Parses a PKCS#1 RSAPublicKey, or a SubjectPublicKeyInfo that wraps one.
    init(der: Data) throws {
        var outer = DERReader([UInt8](der))
        let sequence = try outer.read()
        guard sequence.tag == 0x30 else { throw DERError.malformed }
        var inner = DERReader(sequence.content)
        let first = try inner.read()
        if first.tag == 0x30 {
            // SubjectPublicKeyInfo: AlgorithmIdentifier, then BIT STRING with PKCS#1.
            let bitString = try inner.read()
            guard bitString.tag == 0x03, bitString.content.first == 0 else { throw DERError.malformed }
            try self.init(der: Data(bitString.content.dropFirst()))
            return
        }
        guard first.tag == 0x02 else { throw DERError.malformed }
        let second = try inner.read()
        guard second.tag == 0x02 else { throw DERError.malformed }
        self.init(modulus: first.content, exponent: second.content)
    }
}
