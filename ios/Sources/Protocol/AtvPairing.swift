import Foundation
import Security

/// Runs the pairing sequence on port 6467.
/// See docs/ANDROID_TV_REMOTE_PROTOCOL.md, "Pairing".
/// All methods and callbacks run on the main queue.
final class AtvPairing {
    enum Outcome {
        case paired(fingerprint: String)
        case failed(String)
    }

    enum CodeCheck {
        case sent
        case invalidFormat
        case wrongCode
        case notReady
    }

    private enum Step {
        case connecting, request, option, configuration, awaitingCode, secret, done
    }

    static let defaultPort: UInt16 = 6467

    var onAwaitingCode: (() -> Void)?
    var onFinished: ((Outcome) -> Void)?

    private let host: String
    private let port: UInt16
    private let identity: SecIdentity
    private let clientNumbers: RSAPublicNumbers
    private let clientName: String
    private var connection: FramedConnection?
    private var step = Step.connecting

    init(host: String, port: UInt16 = AtvPairing.defaultPort, identity: SecIdentity, clientNumbers: RSAPublicNumbers, clientName: String) {
        self.host = host
        self.port = port
        self.identity = identity
        self.clientNumbers = clientNumbers
        self.clientName = clientName
    }

    func start() {
        let connection = FramedConnection(host: host, port: port, identity: identity, pinnedFingerprint: nil)
        connection.onEvent = { [weak self] event in
            self?.handle(event)
        }
        self.connection = connection
        step = .connecting
        connection.start()
    }

    func cancel() {
        step = .done
        connection?.close()
        connection = nil
        onFinished = nil
        onAwaitingCode = nil
    }

    func submit(code: String) -> CodeCheck {
        guard step == .awaitingCode, let connection, let serverCertificate = connection.capture.certificate else {
            return .notReady
        }
        guard let serverNumbers = try? Certificates.publicNumbers(serverCertificate) else {
            finish(.failed(L10n.t("error.pair.noRsa")))
            return .notReady
        }
        switch PairingSecret.compute(code: code, client: clientNumbers, server: serverNumbers) {
        case .invalidFormat:
            return .invalidFormat
        case .mismatch:
            return .wrongCode
        case .secret(let secret):
            step = .secret
            connection.send(PairingMessages.secret(secret))
            return .sent
        }
    }

    private func handle(_ event: FramedConnection.Event) {
        switch event {
        case .ready:
            step = .request
            connection?.send(PairingMessages.request(clientName: clientName))
        case .frame(let data):
            handleFrame(data)
        case .closed(let message):
            if step != .done {
                finish(.failed(message ?? L10n.t("error.pair.closed")))
            }
        }
    }

    private func handleFrame(_ data: Data) {
        guard let response = try? PairingResponse(data) else {
            finish(.failed(L10n.t("error.pair.badMessage")))
            return
        }
        guard response.status == PairingMessages.statusOK else {
            if response.status == PairingMessages.statusBadSecret {
                finish(.failed(L10n.t("error.pair.codeRejected")))
            } else {
                finish(.failed(L10n.f("error.pair.refused %lld", Int64(response.status))))
            }
            return
        }
        switch (step, response.kind) {
        case (.request, .requestAck):
            step = .option
            connection?.send(PairingMessages.option())
        case (.option, .option):
            step = .configuration
            connection?.send(PairingMessages.configuration())
        case (.configuration, .configurationAck):
            step = .awaitingCode
            onAwaitingCode?()
        case (.secret, .secretAck):
            guard let certificate = connection?.capture.certificate else {
                finish(.failed(L10n.t("error.pair.noCert")))
                return
            }
            finish(.paired(fingerprint: Certificates.fingerprint(certificate)))
        default:
            break
        }
    }

    private func finish(_ outcome: Outcome) {
        guard step != .done else { return }
        step = .done
        connection?.close()
        connection = nil
        let handler = onFinished
        onFinished = nil
        onAwaitingCode = nil
        handler?(outcome)
    }
}
