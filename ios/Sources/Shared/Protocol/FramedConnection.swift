import Foundation
import Network
import Security

/// Holds what the TLS verify block learns about the box certificate.
/// It is only touched on the main queue.
final class TLSPeerCapture {
    var certificate: SecCertificate?
    var fingerprintMismatch = false
}

enum TLSFactory {
    /// TLS options with the client identity. The verify block accepts the
    /// self-signed box certificate. If a pinned fingerprint is given, it
    /// refuses any other certificate.
    static func options(
        identity: SecIdentity,
        pinnedFingerprint: String?,
        capture: TLSPeerCapture
    ) -> NWProtocolTLS.Options {
        let tls = NWProtocolTLS.Options()
        let security = tls.securityProtocolOptions
        sec_protocol_options_set_min_tls_protocol_version(security, .TLSv12)
        if let localIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(security, localIdentity)
        }
        sec_protocol_options_set_verify_block(security, { _, trust, complete in
            let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
            let chain = SecTrustCopyCertificateChain(secTrust) as? [SecCertificate]
            let leaf = chain?.first
            capture.certificate = leaf
            if let pinnedFingerprint {
                guard let leaf, Certificates.fingerprint(leaf) == pinnedFingerprint else {
                    capture.fingerprintMismatch = true
                    complete(false)
                    return
                }
            }
            complete(true)
        }, DispatchQueue.main)
        return tls
    }
}

/// A TLS connection that sends and receives varint length-prefixed
/// protobuf messages. All callbacks run on the main queue.
final class FramedConnection {
    enum Event {
        case ready
        case frame(Data)
        case closed(String?)
    }

    let capture: TLSPeerCapture
    var onEvent: ((Event) -> Void)?

    private let connection: NWConnection
    private var buffer = FrameBuffer()
    private var finished = false
    /// `.waiting` is not a failure: the Local Network prompt or a short Wi-Fi change can cause it.
    /// The connection fails only if it is still not ready after this time.
    private static let waitingTimeout: TimeInterval = 10
    private var waitingTimer: Timer?
    private var lastWaitingError: NWError?

    init(host: String, port: UInt16, identity: SecIdentity, pinnedFingerprint: String?) {
        let capture = TLSPeerCapture()
        self.capture = capture
        let tls = TLSFactory.options(identity: identity, pinnedFingerprint: pinnedFingerprint, capture: capture)
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        tcp.connectionTimeout = 8
        let parameters = NWParameters(tls: tls, tcp: tcp)
        connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(rawValue: port) ?? 6466,
            using: parameters
        )
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.waitingTimer?.invalidate()
                self.waitingTimer = nil
                self.onEvent?(.ready)
                self.receive()
            case .waiting(let error):
                self.lastWaitingError = error
                self.startWaitingTimer()
            case .failed(let error):
                self.finish(self.describe(error))
            case .cancelled:
                self.finish(nil)
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    func send(_ message: Data) {
        guard !finished else { return }
        connection.send(content: ProtoFraming.frame(message), completion: .contentProcessed { [weak self] error in
            if let error, let self {
                self.finish(self.describe(error))
            }
        })
    }

    /// Sends and waits until the network stack took the bytes. Returns false on an error
    /// or if the connection is closed.
    func sendConfirmed(_ message: Data) async -> Bool {
        guard !finished else { return false }
        return await withCheckedContinuation { continuation in
            connection.send(content: ProtoFraming.frame(message), completion: .contentProcessed { [weak self] error in
                if let error {
                    self?.finish(self?.describe(error))
                    continuation.resume(returning: false)
                } else {
                    continuation.resume(returning: true)
                }
            })
        }
    }

    private func startWaitingTimer() {
        guard waitingTimer == nil else { return }
        waitingTimer = Timer.scheduledTimer(withTimeInterval: Self.waitingTimeout, repeats: false) { [weak self] _ in
            guard let self, !self.finished else { return }
            self.finish(self.lastWaitingError.map { self.describe($0) } ?? L10n.t("error.conn.timeout"))
        }
    }

    /// Closes the connection without an event.
    func close() {
        onEvent = nil
        finished = true
        waitingTimer?.invalidate()
        waitingTimer = nil
        connection.cancel()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.finished else { return }
            if let data, !data.isEmpty {
                self.buffer.append(data)
                do {
                    while let frame = try self.buffer.nextFrame() {
                        self.onEvent?(.frame(frame))
                        if self.finished { return }
                    }
                } catch {
                    self.finish(L10n.t("error.conn.badData"))
                    return
                }
            }
            if let error {
                self.finish(self.describe(error))
            } else if isComplete {
                self.finish(L10n.t("error.conn.closed"))
            } else {
                self.receive()
            }
        }
    }

    private func finish(_ message: String?) {
        guard !finished else { return }
        finished = true
        waitingTimer?.invalidate()
        waitingTimer = nil
        connection.cancel()
        let handler = onEvent
        onEvent = nil
        handler?(.closed(message))
    }

    private func describe(_ error: NWError) -> String {
        if capture.fingerprintMismatch {
            return L10n.t("error.conn.certChanged")
        }
        switch error {
        case .posix(let code) where code == .ECONNREFUSED:
            return L10n.t("error.conn.refused")
        case .posix(let code) where code == .ETIMEDOUT:
            return L10n.t("error.conn.timeout")
        case .tls:
            return L10n.t("error.conn.tls")
        default:
            return error.localizedDescription
        }
    }
}
