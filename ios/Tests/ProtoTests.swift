import XCTest
@testable import TVBoxRemote

final class ProtoTests: XCTestCase {
    func testVarintRoundTrip() throws {
        let values: [UInt64] = [0, 1, 127, 128, 300, 16_383, 16_384, 1 << 31, UInt64.max]
        for value in values {
            var writer = ProtoWriter()
            writer.writeVarint(value)
            var reader = ProtoReader(writer.data)
            XCTAssertEqual(try reader.readVarint(), value)
            XCTAssertTrue(reader.isAtEnd)
        }
    }

    func testVarintKnownBytes() {
        var writer = ProtoWriter()
        writer.writeVarint(300)
        XCTAssertEqual([UInt8](writer.data), [0xAC, 0x02])
    }

    func testNegativeIntUsesTenBytes() throws {
        var writer = ProtoWriter()
        writer.int(1, -1)
        XCTAssertEqual(writer.data.count, 11) // tag + 10 bytes
        let fields = try ProtoReader.fields(writer.data)
        XCTAssertEqual(fields.first(1)?.intValue, -1)
    }

    func testNestedMessageRoundTrip() throws {
        var writer = ProtoWriter()
        writer.int(1, 2)
        writer.message(10) { inner in
            inner.string(1, "atvremote")
            inner.string(2, "Phone")
        }
        let fields = try ProtoReader.fields(writer.data)
        XCTAssertEqual(fields.first(1)?.intValue, 2)
        let inner = try ProtoReader.fields(try XCTUnwrap(fields.first(10)).bytes)
        XCTAssertEqual(inner.first(1)?.stringValue, "atvremote")
        XCTAssertEqual(inner.first(2)?.stringValue, "Phone")
    }

    func testTruncatedInputThrows() {
        var reader = ProtoReader(Data([0x0A, 0x05, 0x01]))
        XCTAssertThrowsError(try reader.next())
    }

    func testFrameBufferHandlesPartialData() throws {
        let message = Data(repeating: 0x42, count: 200)
        let framed = ProtoFraming.frame(message)
        XCTAssertEqual([UInt8](framed.prefix(2)), [0xC8, 0x01])

        var buffer = FrameBuffer()
        buffer.append(framed.prefix(1))
        XCTAssertNil(try buffer.nextFrame())
        buffer.append(framed.dropFirst(1).prefix(50))
        XCTAssertNil(try buffer.nextFrame())
        buffer.append(framed.dropFirst(51))
        buffer.append(ProtoFraming.frame(Data([1, 2, 3])))
        XCTAssertEqual(try buffer.nextFrame(), message)
        XCTAssertEqual(try buffer.nextFrame(), Data([1, 2, 3]))
        XCTAssertNil(try buffer.nextFrame())
    }

    func testPairingResponseKinds() throws {
        var ack = ProtoWriter()
        ack.int(1, 2)
        ack.int(2, 200)
        ack.message(11) { m in m.string(1, "Living Room") }
        let response = try PairingResponse(ack.data)
        XCTAssertEqual(response.status, 200)
        XCTAssertEqual(response.kind, .requestAck)

        var configAck = ProtoWriter()
        configAck.int(2, 200)
        configAck.message(31) { _ in }
        XCTAssertEqual(try PairingResponse(configAck.data).kind, .configurationAck)
    }

    func testPairingRequestEncoding() throws {
        let fields = try ProtoReader.fields(PairingMessages.request(clientName: "iPhone"))
        XCTAssertEqual(fields.first(1)?.intValue, 2)
        XCTAssertEqual(fields.first(2)?.intValue, 200)
        let request = try ProtoReader.fields(try XCTUnwrap(fields.first(10)).bytes)
        XCTAssertEqual(request.first(1)?.stringValue, "atvremote")
        XCTAssertEqual(request.first(2)?.stringValue, "iPhone")
    }

    func testRemoteEventParsing() throws {
        var ping = ProtoWriter()
        ping.message(8) { p in p.int(1, 7) }
        XCTAssertEqual(try RemoteEvent(ping.data), .ping(7))

        var volume = ProtoWriter()
        volume.message(50) { v in
            v.int(6, 100)
            v.int(7, 35)
            v.bool(8, true)
        }
        XCTAssertEqual(try RemoteEvent(volume.data), .volume(level: 35, max: 100, muted: true))

        var configure = ProtoWriter()
        configure.message(1) { c in c.int(1, 622) }
        XCTAssertEqual(try RemoteEvent(configure.data), .configure)
    }

    func testTextMessageEncoding() throws {
        let data = RemoteMessages.text("héllo", imeCounter: 3, fieldCounter: 9)
        let top = try ProtoReader.fields(data)
        let batch = try ProtoReader.fields(try XCTUnwrap(top.first(21)).bytes)
        XCTAssertEqual(batch.first(1)?.intValue, 3)
        XCTAssertEqual(batch.first(2)?.intValue, 9)
        let edit = try ProtoReader.fields(try XCTUnwrap(batch.first(3)).bytes)
        XCTAssertEqual(edit.first(1)?.intValue, 1)
        let object = try ProtoReader.fields(try XCTUnwrap(edit.first(2)).bytes)
        XCTAssertEqual(object.first(1)?.intValue, 4)
        XCTAssertEqual(object.first(2)?.intValue, 4)
        XCTAssertEqual(object.first(3)?.stringValue, "héllo")
    }

    func testKeyMessageEncoding() throws {
        let top = try ProtoReader.fields(RemoteMessages.key(RemoteKey.center.rawValue, direction: 3))
        let key = try ProtoReader.fields(try XCTUnwrap(top.first(10)).bytes)
        XCTAssertEqual(key.first(1)?.intValue, 23)
        XCTAssertEqual(key.first(2)?.intValue, 3)
    }
}
