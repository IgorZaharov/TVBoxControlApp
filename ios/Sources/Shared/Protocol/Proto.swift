import Foundation

/// Minimal protobuf encoder. It writes only the wire types that the
/// Android TV Remote protocol uses: varint (0) and length-delimited (2).
struct ProtoWriter {
    private(set) var data = Data()

    mutating func writeVarint(_ value: UInt64) {
        var v = value
        while v >= 0x80 {
            data.append(UInt8(truncatingIfNeeded: v & 0x7F) | 0x80)
            v >>= 7
        }
        data.append(UInt8(truncatingIfNeeded: v))
    }

    mutating func writeTag(_ field: Int, wireType: Int) {
        writeVarint(UInt64((field << 3) | wireType))
    }

    /// Writes an int32/int64/uint32/enum field. Proto3 omits zero values.
    /// Negative values use the 10-byte sign-extended form, as protobuf does.
    mutating func int(_ field: Int, _ value: Int) {
        guard value != 0 else { return }
        writeTag(field, wireType: 0)
        writeVarint(UInt64(bitPattern: Int64(value)))
    }

    mutating func bool(_ field: Int, _ value: Bool) {
        guard value else { return }
        writeTag(field, wireType: 0)
        writeVarint(1)
    }

    mutating func bytes(_ field: Int, _ value: Data) {
        writeTag(field, wireType: 2)
        writeVarint(UInt64(value.count))
        data.append(value)
    }

    /// Writes a string field. Proto3 omits empty strings.
    mutating func string(_ field: Int, _ value: String) {
        guard !value.isEmpty else { return }
        bytes(field, Data(value.utf8))
    }

    /// Writes a nested message. An empty nested message is still written,
    /// because its presence carries meaning.
    mutating func message(_ field: Int, _ build: (inout ProtoWriter) -> Void) {
        var inner = ProtoWriter()
        build(&inner)
        bytes(field, inner.data)
    }
}

enum ProtoError: Error, Equatable {
    case truncated
    case malformed
}

struct ProtoField {
    let number: Int
    let wireType: Int
    /// Value for wire type 0.
    let varint: UInt64
    /// Value for wire type 2 (and the raw bytes of wire types 1 and 5).
    let bytes: Data

    var intValue: Int { Int(Int32(truncatingIfNeeded: varint)) }
    var uintValue: Int { Int(UInt32(truncatingIfNeeded: varint)) }
    var boolValue: Bool { varint != 0 }
    var stringValue: String { String(decoding: bytes, as: UTF8.self) }
}

/// Minimal protobuf decoder.
struct ProtoReader {
    private let buffer: [UInt8]
    private var position = 0

    init(_ data: Data) {
        buffer = [UInt8](data)
    }

    var isAtEnd: Bool { position >= buffer.count }

    mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard position < buffer.count else { throw ProtoError.truncated }
            let byte = buffer[position]
            position += 1
            if shift < 64 {
                result |= UInt64(byte & 0x7F) << shift
            }
            if byte & 0x80 == 0 { return result }
            shift += 7
            if shift >= 70 { throw ProtoError.malformed }
        }
    }

    private mutating func readBytes(_ count: Int) throws -> Data {
        guard count >= 0, count <= buffer.count - position else { throw ProtoError.truncated }
        let slice = Data(buffer[position..<(position + count)])
        position += count
        return slice
    }

    mutating func next() throws -> ProtoField? {
        guard !isAtEnd else { return nil }
        let key = try readVarint()
        let number = Int(truncatingIfNeeded: key >> 3)
        let wireType = Int(key & 0x7)
        switch wireType {
        case 0:
            return ProtoField(number: number, wireType: 0, varint: try readVarint(), bytes: Data())
        case 1:
            return ProtoField(number: number, wireType: 1, varint: 0, bytes: try readBytes(8))
        case 2:
            let length = try readVarint()
            guard length <= UInt64(buffer.count - position) else { throw ProtoError.truncated }
            return ProtoField(number: number, wireType: 2, varint: 0, bytes: try readBytes(Int(length)))
        case 5:
            return ProtoField(number: number, wireType: 5, varint: 0, bytes: try readBytes(4))
        default:
            throw ProtoError.malformed
        }
    }

    /// Decodes all fields of one message.
    static func fields(_ data: Data) throws -> [ProtoField] {
        var reader = ProtoReader(data)
        var result: [ProtoField] = []
        while let field = try reader.next() {
            result.append(field)
        }
        return result
    }
}

extension Array where Element == ProtoField {
    func first(_ number: Int) -> ProtoField? {
        first(where: { $0.number == number })
    }

    func has(_ number: Int) -> Bool {
        contains(where: { $0.number == number })
    }
}

/// Varint length-prefix framing: each message is preceded by its length.
enum ProtoFraming {
    static func frame(_ message: Data) -> Data {
        var writer = ProtoWriter()
        writer.writeVarint(UInt64(message.count))
        var out = writer.data
        out.append(message)
        return out
    }
}

/// Collects bytes from the network and cuts them into complete frames.
struct FrameBuffer {
    private var pending = [UInt8]()
    static let maxFrameSize = 1 << 20

    mutating func append(_ data: Data) {
        pending.append(contentsOf: data)
    }

    /// Returns the next complete frame, or nil if more bytes are necessary.
    mutating func nextFrame() throws -> Data? {
        var length: UInt64 = 0
        var shift: UInt64 = 0
        var index = 0
        while true {
            guard index < pending.count else { return nil }
            let byte = pending[index]
            index += 1
            length |= UInt64(byte & 0x7F) << shift
            if byte & 0x80 == 0 { break }
            shift += 7
            if shift >= 35 { throw ProtoError.malformed }
        }
        guard length <= UInt64(Self.maxFrameSize) else { throw ProtoError.malformed }
        let total = index + Int(length)
        guard pending.count >= total else { return nil }
        let frame = Data(pending[index..<total])
        pending.removeFirst(total)
        return frame
    }
}
