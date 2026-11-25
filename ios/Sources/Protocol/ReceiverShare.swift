import Foundation
import Network

/// The phone gives the receiver to the box over the local Wi-Fi: it gets receiver.apk from GitHub, then serves it on
/// a short address such as 192.168.1.23:8080. On the box, the user types that address into Downloader or a browser.
/// The short address is much easier to type with a TV remote than the GitHub link. Android does the same
/// (ReceiverShare.kt).
enum ReceiverShare {
    /// The ports to try, in order. 8080 is the usual one; the others are for a phone where 8080 is in use.
    static let ports: [UInt16] = [8080, 8000, 8888] + Array(8081...8099)

    /// Downloads the newest receiver to the caches folder. If the download fails, it uses the copy from an earlier
    /// download. Throws if there is neither.
    static func fetch() async throws -> Data {
        let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let file = caches.appendingPathComponent("receiver.apk")
        do {
            guard let url = URL(string: ReceiverDownload.url) else { throw URLError(.badURL) }
            var request = URLRequest(url: url)
            request.timeoutInterval = 30
            // GitHub sends "latest/download" to the file of the newest release. URLSession follows the redirect.
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200, isApk(data) else {
                throw URLError(.badServerResponse)
            }
            try data.write(to: file, options: .atomic)
            return data
        } catch {
            guard let data = try? Data(contentsOf: file), isApk(data) else { throw error }
            return data
        }
    }

    /// An APK is a ZIP file: it starts with "PK". The receiver is much larger than 10 KB.
    static func isApk(_ data: Data) -> Bool {
        data.count >= 10_000 && data.prefix(2) == Data([0x50, 0x4B])
    }

    /// The IPv4 address of the phone on the local network, or nil if the phone is not on Wi-Fi.
    /// Wi-Fi (en0) comes first, then the phone hotspot (bridge), then other local networks. Mobile data (pdp_ip)
    /// does not count: the box cannot reach it.
    static func localAddress() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var best: (rank: Int, address: String)?
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_RUNNING != 0, flags & IFF_LOOPBACK == 0,
                  let address = entry.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: entry.ifa_name)
            let rank: Int
            if name == "en0" {
                rank = 0
            } else if name.hasPrefix("bridge") {
                rank = 1
            } else if name.hasPrefix("en") {
                rank = 2
            } else {
                continue
            }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: host)
            if ip.hasPrefix("169.254.") { continue } // No DHCP address: not a usable network.
            if best == nil || rank < best!.rank { best = (rank, ip) }
        }
        return best?.address
    }
}

/// A small HTTP server for receiver.apk. Every path except /favicon.ico gets the APK, so "192.168.1.23:8080" alone
/// works. The file goes out as an attachment named receiver.apk, with the APK media type, so that Downloader offers
/// to install it and a browser saves it.
final class ReceiverShareServer {
    /// Called on the main thread after the box got the whole file.
    var onDownloaded: (() -> Void)?

    private let body: Data
    private let queue = DispatchQueue(label: "receiver.share")
    private var listener: NWListener?

    init(body: Data) {
        self.body = body
    }

    /// Starts on the first free port of `ReceiverShare.ports`. Returns nil if no port is free.
    func start() async -> UInt16? {
        for port in ReceiverShare.ports {
            if let listener = await listen(on: port) {
                self.listener = listener
                return port
            }
        }
        return nil
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    private func listen(on port: UInt16) async -> NWListener? {
        guard let nwPort = NWEndpoint.Port(rawValue: port),
              let listener = try? NWListener(using: .tcp, on: nwPort) else { return nil }
        listener.newConnectionHandler = { [weak self] connection in self?.serve(connection) }
        return await withCheckedContinuation { continuation in
            let once = Once()
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    once.run { continuation.resume(returning: listener) }
                case .failed, .cancelled:
                    // For example the port is in use.
                    once.run {
                        listener.cancel()
                        continuation.resume(returning: nil)
                    }
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
    }

    private func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        receive(connection, buffer: Data())
    }

    /// Reads the request up to the empty line after the headers.
    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, complete, error in
            guard let self else {
                connection.cancel()
                return
            }
            var request = buffer
            if let data { request.append(data) }
            if let end = request.range(of: Data("\r\n\r\n".utf8)) {
                self.respond(connection, head: request[request.startIndex..<end.lowerBound])
            } else if error != nil || complete || request.count > 16_384 {
                connection.cancel()
            } else {
                self.receive(connection, buffer: request)
            }
        }
    }

    private func respond(_ connection: NWConnection, head: Data) {
        let requestLine = String(decoding: head, as: UTF8.self).components(separatedBy: "\r\n").first ?? ""
        let parts = requestLine.split(separator: " ")
        let method = parts.first.map(String.init) ?? ""
        let path = parts.count > 1 ? String(parts[1]) : ""
        var response: Data
        var isDownload = false
        if method != "GET" && method != "HEAD" {
            response = Data("HTTP/1.1 405 Method Not Allowed\r\nAllow: GET, HEAD\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
        } else if path.hasPrefix("/favicon") {
            response = Data("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8)
        } else {
            let header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/vnd.android.package-archive\r\n"
                + "Content-Length: \(body.count)\r\n"
                + "Content-Disposition: attachment; filename=\"receiver.apk\"\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n"
            response = Data(header.utf8)
            if method == "GET" {
                response.append(body)
                isDownload = true
            }
        }
        connection.send(content: response, completion: .contentProcessed { [weak self] error in
            connection.cancel()
            guard error == nil, isDownload else { return }
            DispatchQueue.main.async { self?.onDownloaded?() }
        })
    }
}

/// Runs a block only the first time. The listener reports more than one end state.
private final class Once {
    private var done = false
    private let lock = NSLock()

    func run(_ block: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { block() }
    }
}
