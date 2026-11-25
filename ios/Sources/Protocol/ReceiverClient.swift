import Foundation
import Network

/// Client for the box receiver app (mouse over Wi-Fi).
/// See docs/RECEIVER_PROTOCOL.md. All methods and callbacks run on the main queue.
final class ReceiverClient {
    enum Event {
        case pairRequired
        case paired(token: String)
        case welcome(name: String, width: Int, height: Int)
        case error(code: String, message: String)
        case closed(String?)
    }

    static let defaultPort: UInt16 = 6470
    private static let silenceTimeout: TimeInterval = 15

    var onEvent: ((Event) -> Void)?
    private(set) var isAuthorized = false

    private let connection: NWConnection
    private let phoneName: String
    private let token: String?
    private var buffer = Data()
    private var finished = false
    private var pingTimer: Timer?
    private var lastReceived = Date()
    /// `.waiting` (Local Network prompt, short Wi-Fi change) fails only after this time.
    private var waitingTimer: Timer?

    init(host: String, port: UInt16 = ReceiverClient.defaultPort, phoneName: String, token: String?) {
        self.phoneName = phoneName
        self.token = token
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 6
        let parameters = NWParameters(tls: nil, tcp: tcp)
        connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? 6470,
            using: parameters
        )
    }

    deinit {
        pingTimer?.invalidate()
    }

    func connect() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.waitingTimer?.invalidate()
                self.waitingTimer = nil
                self.lastReceived = Date()
                var hello: [String: Any] = ["t": "hello", "v": 1, "name": self.phoneName]
                if let token = self.token { hello["token"] = token }
                self.send(hello)
                self.receive()
                self.startPing()
            case .waiting:
                guard self.waitingTimer == nil else { break }
                self.waitingTimer = Timer.scheduledTimer(withTimeInterval: 10, repeats: false) { [weak self] _ in
                    self?.finish(L10n.t("error.receiver.noAnswer"))
                }
            case .failed(let error):
                self.finish(error.localizedDescription)
            case .cancelled:
                self.finish(nil)
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    /// Closes the connection without an event.
    func close() {
        onEvent = nil
        pingTimer?.invalidate()
        pingTimer = nil
        waitingTimer?.invalidate()
        waitingTimer = nil
        finished = true
        connection.cancel()
    }

    func pair(code: String) {
        send(["t": "pair", "code": code])
    }

    func move(dx: Double, dy: Double) {
        guard isAuthorized, dx != 0 || dy != 0 else { return }
        sendLine(String(format: "{\"t\":\"move\",\"dx\":%.1f,\"dy\":%.1f}", dx, dy))
    }

    func click() {
        guard isAuthorized else { return }
        sendLine("{\"t\":\"click\"}")
    }

    func longPress() {
        guard isAuthorized else { return }
        sendLine("{\"t\":\"longpress\"}")
    }

    /// Positive dy scrolls the content down (the finger moves up on the TV).
    func scroll(dx: Double, dy: Double) {
        guard isAuthorized else { return }
        sendLine(String(format: "{\"t\":\"scroll\",\"dx\":%.1f,\"dy\":%.1f}", dx, dy))
    }

    func setCursor(visible: Bool) {
        guard isAuthorized else { return }
        sendLine(visible ? "{\"t\":\"cursor\",\"show\":true}" : "{\"t\":\"cursor\",\"show\":false}")
    }

    // MARK: - Private

    private func send(_ object: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let line = String(data: data, encoding: .utf8) else { return }
        sendLine(line)
    }

    private func sendLine(_ line: String) {
        guard !finished else { return }
        connection.send(content: Data((line + "\n").utf8), completion: .contentProcessed { [weak self] error in
            if let error, let self {
                self.finish(error.localizedDescription)
            }
        })
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty {
                self.lastReceived = Date()
                self.buffer.append(data)
                self.processLines()
                if self.finished { return }
                if self.buffer.count > 65_536 {
                    self.finish(L10n.t("error.receiver.tooMuch"))
                    return
                }
            }
            if let error {
                self.finish(error.localizedDescription)
            } else if isComplete {
                self.finish(L10n.t("error.receiver.closed"))
            } else {
                self.receive()
            }
        }
    }

    private func processLines() {
        while let newline = buffer.firstIndex(of: 0x0A) {
            let lineData = buffer.subdata(in: buffer.startIndex..<newline)
            buffer.removeSubrange(buffer.startIndex...newline)
            guard let object = try? JSONSerialization.jsonObject(with: lineData) as? [String: Any],
                  let type = object["t"] as? String else { continue }
            handle(type: type, object: object)
            if finished { return }
        }
    }

    private func handle(type: String, object: [String: Any]) {
        switch type {
        case "welcome":
            isAuthorized = true
            let name = object["name"] as? String ?? ""
            let width = (object["w"] as? NSNumber)?.intValue ?? 0
            let height = (object["h"] as? NSNumber)?.intValue ?? 0
            onEvent?(.welcome(name: name, width: width, height: height))
        case "pair_required":
            isAuthorized = false
            onEvent?(.pairRequired)
        case "paired":
            if let token = object["token"] as? String {
                onEvent?(.paired(token: token))
            }
        case "error":
            let code = object["code"] as? String ?? ""
            let message = object["msg"] as? String ?? ""
            onEvent?(.error(code: code, message: message))
        default:
            break // "pong" and unknown types
        }
    }

    private func startPing() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            guard let self else { return }
            if Date().timeIntervalSince(self.lastReceived) > Self.silenceTimeout {
                self.finish(L10n.t("error.receiver.stopped"))
            } else {
                self.sendLine("{\"t\":\"ping\"}")
            }
        }
    }

    private func finish(_ message: String?) {
        guard !finished else { return }
        finished = true
        isAuthorized = false
        pingTimer?.invalidate()
        pingTimer = nil
        waitingTimer?.invalidate()
        waitingTimer = nil
        connection.cancel()
        let handler = onEvent
        onEvent = nil
        handler?(.closed(message))
    }
}
