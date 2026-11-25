import Foundation
import Security

/// The control session on port 6466.
/// See docs/ANDROID_TV_REMOTE_PROTOCOL.md, "Remote control".
/// All methods and callbacks run on the main queue.
final class AtvRemoteSession {
    enum State: Equatable {
        case connecting
        case connected
        case closed(String?)
    }

    static let defaultPort: UInt16 = 6466
    private static let pingTimeout: TimeInterval = 16
    private static let connectTimeout: TimeInterval = 10

    var onStateChange: ((State) -> Void)?
    var onVolume: ((VolumeInfo) -> Void)?
    var onPower: ((Bool) -> Void)?
    var onImeShow: ((String) -> Void)?

    private(set) var state: State = .closed(nil)
    private(set) var certificateMismatch = false
    /// True after the box sent set-active (or start). Keys sent before it can be lost.
    private(set) var isActive = false

    private let host: String
    private let port: UInt16
    private let identity: SecIdentity
    private let pinnedFingerprint: String?
    private let model: String
    private var connection: FramedConnection?
    private var watchdog: Timer?
    private var lastActivity = Date()
    private var connectStarted = Date()
    private var imeCounter = 0
    private var fieldCounter = 0

    init(host: String, port: UInt16, identity: SecIdentity, pinnedFingerprint: String?, model: String) {
        self.host = host
        self.port = port
        self.identity = identity
        self.pinnedFingerprint = pinnedFingerprint
        self.model = model
    }

    deinit {
        watchdog?.invalidate()
    }

    func connect() {
        connection?.close()
        let connection = FramedConnection(host: host, port: port, identity: identity, pinnedFingerprint: pinnedFingerprint)
        connection.onEvent = { [weak self] event in
            self?.handle(event)
        }
        self.connection = connection
        connectStarted = Date()
        lastActivity = Date()
        isActive = false
        setState(.connecting)
        connection.start()
        startWatchdog()
    }

    /// Closes the session. No state callback follows.
    func disconnect() {
        watchdog?.invalidate()
        watchdog = nil
        onStateChange = nil
        connection?.close()
        connection = nil
        isActive = false
        state = .closed(nil)
    }

    /// How long ago the box last sent something. The box pings every 5 s.
    var secondsSinceActivity: TimeInterval { Date().timeIntervalSince(lastActivity) }

    /// Sends a key and waits until the network stack took it. Returns false if it did not go out.
    func sendKeyConfirmed(_ code: Int, direction: Int = 3) async -> Bool {
        guard state == .connected, let connection else { return false }
        return await connection.sendConfirmed(RemoteMessages.key(code, direction: direction))
    }

    /// Direction: 1 start long press, 2 end long press, 3 short press.
    func sendKey(_ code: Int, direction: Int = 3) {
        guard state == .connected else { return }
        connection?.send(RemoteMessages.key(code, direction: direction))
    }

    func sendText(_ text: String) {
        guard state == .connected else { return }
        connection?.send(RemoteMessages.text(text, imeCounter: imeCounter, fieldCounter: fieldCounter))
    }

    func launch(appLink: String) {
        guard state == .connected else { return }
        connection?.send(RemoteMessages.appLink(appLink))
    }

    // MARK: - Private

    private func setState(_ newState: State) {
        guard newState != state else { return }
        state = newState
        onStateChange?(newState)
    }

    private func handle(_ event: FramedConnection.Event) {
        switch event {
        case .ready:
            lastActivity = Date()
        case .frame(let data):
            lastActivity = Date()
            handleFrame(data)
        case .closed(let message):
            certificateMismatch = connection?.capture.fingerprintMismatch ?? certificateMismatch
            close(message ?? L10n.t("error.session.closed"))
        }
    }

    private func handleFrame(_ data: Data) {
        guard let event = try? RemoteEvent(data) else { return }
        switch event {
        case .configure:
            connection?.send(RemoteMessages.configure(model: model, vendor: "Apple"))
            setState(.connected)
        case .setActive:
            connection?.send(RemoteMessages.setActive())
            isActive = true
            setState(.connected)
        case .ping(let value):
            connection?.send(RemoteMessages.pingResponse(value))
        case .start(let started):
            isActive = true
            onPower?(started)
        case .volume(let level, let max, let muted):
            onVolume?(VolumeInfo(level: level, max: max, muted: muted))
        case .imeBatchEdit(let ime, let field):
            imeCounter = ime
            fieldCounter = field
        case .imeShow(let text):
            onImeShow?(text)
        case .imeKeyInject, .error, .other:
            break
        }
    }

    private func startWatchdog() {
        watchdog?.invalidate()
        watchdog = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            self?.checkAlive()
        }
    }

    private func checkAlive() {
        let now = Date()
        switch state {
        case .connecting where now.timeIntervalSince(connectStarted) > Self.connectTimeout:
            close(L10n.t("error.session.noAnswer"))
        case .connected where now.timeIntervalSince(lastActivity) > Self.pingTimeout:
            close(L10n.t("error.session.stopped"))
        default:
            break
        }
    }

    private func close(_ message: String) {
        watchdog?.invalidate()
        watchdog = nil
        connection?.close()
        connection = nil
        isActive = false
        setState(.closed(message))
    }
}

struct VolumeInfo: Equatable {
    let level: Int
    let max: Int
    let muted: Bool
}
