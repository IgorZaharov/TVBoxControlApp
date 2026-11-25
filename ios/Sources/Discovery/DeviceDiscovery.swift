import Foundation
import Network

struct DiscoveredService: Equatable {
    let name: String
    var host: String?
    var port: UInt16?
}

/// Browses one Bonjour service type and resolves each service to an IPv4
/// address and port. All callbacks run on the main queue.
final class BonjourBrowser {
    var onChange: (() -> Void)?
    private(set) var services: [String: DiscoveredService] = [:]

    private let type: String
    private var browser: NWBrowser?
    private var resolvers: [String: NWConnection] = [:]
    private var endpoints: [String: NWEndpoint] = [:]
    private var retries: [String: Int] = [:]

    init(type: String) {
        self.type = type
    }

    func start() {
        guard browser == nil else { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = false
        let browser = NWBrowser(for: .bonjour(type: type, domain: nil), using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.update(results)
        }
        browser.stateUpdateHandler = { [weak self] state in
            if case .failed = state {
                self?.restart()
            }
        }
        self.browser = browser
        browser.start(queue: .main)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        resolvers.values.forEach { $0.cancel() }
        resolvers.removeAll()
    }

    private func restart() {
        stop()
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
            self?.start()
        }
    }

    private func update(_ results: Set<NWBrowser.Result>) {
        var seen = Set<String>()
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            seen.insert(name)
            endpoints[name] = result.endpoint
            if services[name] == nil {
                services[name] = DiscoveredService(name: name)
                retries[name] = 0
                resolve(name)
            } else if services[name]?.host == nil, resolvers[name] == nil {
                // stop() cancelled an earlier resolve, or all retries failed.
                retries[name] = 0
                resolve(name)
            }
        }
        for name in services.keys where !seen.contains(name) {
            services[name] = nil
            endpoints[name] = nil
            retries[name] = nil
            resolvers[name]?.cancel()
            resolvers[name] = nil
        }
        onChange?()
    }

    /// Opens a short TCP connection to learn the IPv4 address and port.
    private func resolve(_ name: String) {
        guard let endpoint = endpoints[name] else { return }
        let parameters = NWParameters(tls: nil, tcp: NWProtocolTCP.Options())
        if let ip = parameters.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let connection = NWConnection(to: endpoint, using: parameters)
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                if case let .hostPort(host, port)? = connection.currentPath?.remoteEndpoint {
                    self.services[name]?.host = Self.string(for: host)
                    self.services[name]?.port = port.rawValue
                }
                connection.cancel()
                self.resolvers[name] = nil
                self.onChange?()
            case .failed, .waiting:
                connection.cancel()
                self.resolvers[name] = nil
                self.retryLater(name)
            default:
                break
            }
        }
        resolvers[name] = connection
        connection.start(queue: .main)
    }

    private func retryLater(_ name: String) {
        guard let count = retries[name], count < 3 else { return }
        retries[name] = count + 1
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.services[name] != nil, self.services[name]?.host == nil else { return }
            self.resolve(name)
        }
    }

    static func string(for host: NWEndpoint.Host) -> String {
        switch host {
        case .ipv4(let address):
            // Drop an interface suffix such as "%en0".
            let text = "\(address)"
            return text.split(separator: "%").first.map(String.init) ?? text
        case .ipv6(let address):
            return "\(address)"
        case .name(let name, _):
            return name
        @unknown default:
            return "\(host)"
        }
    }
}

/// Finds Android TV boxes and receiver apps on the local network.
final class DeviceDiscovery {
    static let tvServiceType = "_androidtvremote2._tcp"
    static let receiverServiceType = "_tvboxremote._tcp"

    var onChange: (() -> Void)?

    private let tvBrowser = BonjourBrowser(type: DeviceDiscovery.tvServiceType)
    private let receiverBrowser = BonjourBrowser(type: DeviceDiscovery.receiverServiceType)

    var tvBoxes: [DiscoveredService] {
        tvBrowser.services.values.sorted { $0.name < $1.name }
    }

    var receivers: [DiscoveredService] {
        Array(receiverBrowser.services.values)
    }

    func start() {
        tvBrowser.onChange = { [weak self] in self?.onChange?() }
        receiverBrowser.onChange = { [weak self] in self?.onChange?() }
        tvBrowser.start()
        receiverBrowser.start()
    }

    func stop() {
        tvBrowser.stop()
        receiverBrowser.stop()
    }
}
