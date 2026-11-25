import Foundation

/// Features bit mask: ping 1, key 2, IME 4, power 32, volume 64, app link 512.
/// The session uses the part of these that the box also supports.
let atvFeatures = 615

/// Opens the system settings through an app link. Some boxes (NVIDIA SHIELD) ignore the Settings key code.
/// This link is not checked on a box yet.
let atvSettingsLink = "intent:#Intent;action=android.settings.SETTINGS;launchFlags=0x10000000;end"

// MARK: - Pairing messages (port 6467)

enum PairingMessages {
    static let statusOK = 200
    static let statusError = 400
    static let statusBadConfiguration = 401
    static let statusBadSecret = 402

    private static func envelope(_ build: (inout ProtoWriter) -> Void) -> Data {
        var writer = ProtoWriter()
        writer.int(1, 2)            // protocol_version
        writer.int(2, statusOK)     // status
        build(&writer)
        return writer.data
    }

    private static func hexEncoding(_ writer: inout ProtoWriter) {
        writer.int(1, 3)            // type: hexadecimal
        writer.int(2, 6)            // symbol_length
    }

    static func request(clientName: String) -> Data {
        envelope { w in
            w.message(10) { r in
                r.string(1, "atvremote")
                r.string(2, clientName)
            }
        }
    }

    static func option() -> Data {
        envelope { w in
            w.message(20) { o in
                o.message(1) { e in hexEncoding(&e) }
                o.int(3, 1)         // preferred_role: input
            }
        }
    }

    static func configuration() -> Data {
        envelope { w in
            w.message(30) { c in
                c.message(1) { e in hexEncoding(&e) }
                c.int(2, 1)         // client_role: input
            }
        }
    }

    static func secret(_ secret: Data) -> Data {
        envelope { w in
            w.message(40) { s in s.bytes(1, secret) }
        }
    }
}

struct PairingResponse {
    enum Kind {
        case requestAck, option, configurationAck, secretAck, other
    }

    let status: Int
    let kind: Kind

    init(_ data: Data) throws {
        let fields = try ProtoReader.fields(data)
        status = fields.first(2)?.intValue ?? 0
        if fields.has(11) {
            kind = .requestAck
        } else if fields.has(20) || fields.has(21) {
            kind = .option
        } else if fields.has(31) {
            kind = .configurationAck
        } else if fields.has(41) {
            kind = .secretAck
        } else {
            kind = .other
        }
    }
}

// MARK: - Remote messages (port 6466)

enum RemoteMessages {
    static func configure(model: String, vendor: String, features: Int = atvFeatures) -> Data {
        var w = ProtoWriter()
        w.message(1) { c in
            c.int(1, features)
            c.message(2) { d in
                d.string(1, model)
                d.string(2, vendor)
                d.int(3, 1)
                d.string(4, "1")
                d.string(5, "atvremote")
                d.string(6, "1.0.0")
            }
        }
        return w.data
    }

    static func setActive(features: Int = atvFeatures) -> Data {
        var w = ProtoWriter()
        w.message(2) { a in a.int(1, features) }
        return w.data
    }

    static func pingResponse(_ value: Int) -> Data {
        var w = ProtoWriter()
        w.message(9) { p in p.int(1, value) }
        return w.data
    }

    /// Direction: 1 start long press, 2 end long press, 3 short press.
    static func key(_ code: Int, direction: Int) -> Data {
        var w = ProtoWriter()
        w.message(10) { k in
            k.int(1, code)
            k.int(2, direction)
        }
        return w.data
    }

    /// Inserts `text` at the cursor of the focused field on the box. The box adds the text to what the field
    /// has: it does not replace the field. Send only the new characters.
    static func text(_ text: String, imeCounter: Int, fieldCounter: Int) -> Data {
        let position = text.unicodeScalars.count - 1
        var w = ProtoWriter()
        w.message(21) { b in
            b.int(1, imeCounter)
            b.int(2, fieldCounter)
            b.message(3) { e in
                e.int(1, 1)
                e.message(2) { o in
                    o.int(1, position)
                    o.int(2, position)
                    o.string(3, text)
                }
            }
        }
        return w.data
    }

    static func appLink(_ link: String) -> Data {
        var w = ProtoWriter()
        w.message(90) { a in a.string(1, link) }
        return w.data
    }
}

enum RemoteEvent: Equatable {
    /// `features`: the features that the box supports (0 if it did not say). `vendor`: the maker of the box.
    case configure(features: Int, vendor: String)
    case setActive
    case ping(Int)
    case start(Bool)
    case volume(level: Int, max: Int, muted: Bool)
    case imeBatchEdit(imeCounter: Int, fieldCounter: Int)
    case imeShow(text: String)
    case imeKeyInject(appPackage: String)
    case error
    case other

    init(_ data: Data) throws {
        let fields = try ProtoReader.fields(data)
        if let configure = fields.first(1) {
            let inner = try ProtoReader.fields(configure.bytes)
            let info = try inner.first(2).map { try ProtoReader.fields($0.bytes) }
            self = .configure(features: inner.first(1)?.intValue ?? 0, vendor: info?.first(2)?.stringValue ?? "")
        } else if fields.has(2) {
            self = .setActive
        } else if let ping = fields.first(8) {
            let inner = try ProtoReader.fields(ping.bytes)
            self = .ping(inner.first(1)?.intValue ?? 0)
        } else if let start = fields.first(40) {
            let inner = try ProtoReader.fields(start.bytes)
            self = .start(inner.first(1)?.boolValue ?? false)
        } else if let volume = fields.first(50) {
            let inner = try ProtoReader.fields(volume.bytes)
            self = .volume(
                level: inner.first(7)?.uintValue ?? 0,
                max: inner.first(6)?.uintValue ?? 0,
                muted: inner.first(8)?.boolValue ?? false
            )
        } else if let batch = fields.first(21) {
            let inner = try ProtoReader.fields(batch.bytes)
            self = .imeBatchEdit(
                imeCounter: inner.first(1)?.intValue ?? 0,
                fieldCounter: inner.first(2)?.intValue ?? 0
            )
        } else if let show = fields.first(22) {
            let inner = try ProtoReader.fields(show.bytes)
            var text = ""
            if let status = inner.first(2) {
                text = try ProtoReader.fields(status.bytes).first(2)?.stringValue ?? ""
            }
            self = .imeShow(text: text)
        } else if let inject = fields.first(20) {
            let inner = try ProtoReader.fields(inject.bytes)
            var package = ""
            if let app = inner.first(1) {
                package = try ProtoReader.fields(app.bytes).first(12)?.stringValue ?? ""
            }
            self = .imeKeyInject(appPackage: package)
        } else if fields.has(3) {
            self = .error
        } else {
            self = .other
        }
    }
}
