import Foundation
import Security

/// Sends keys from the widget to a saved box over Wi-Fi.
///
/// It uses the identity that the app made (shared keychain group) and the pinned
/// certificate fingerprint of the box (shared UserDefaults). The widget never pairs
/// and never makes a new key. The connection stays open a short time after a press,
/// so quick repeat presses do not connect again.
@MainActor
final class WidgetRemote {
    static let shared = WidgetRemote()

    private var session: AtvRemoteSession?
    private var sessionBoxId: String?
    private var idleTask: Task<Void, Never>?

    /// How long a press waits for the box to be ready.
    private static let readyTimeout: TimeInterval = 5
    /// How long the connection stays open after the last press. iOS can suspend the extension,
    /// so a long keep-alive does not help, and the connection is checked before each use.
    private static let keepAliveNanos: UInt64 = 8_000_000_000
    /// A session that heard nothing from the box for longer is not trusted (the box pings every 5 s).
    private static let maxSilence: TimeInterval = 5
    /// Some boxes may skip set-active. After configure, a press waits at most this long for it.
    private static let activeGrace: TimeInterval = 1.5

    /// Sends `code` to the box `boxId`. Returns false if the box cannot be reached.
    func send(key code: Int, to boxId: String) async -> Bool {
        guard let box = BoxStore(notifyWidgets: false).box(boxId),
              let fingerprint = box.fingerprint,
              let identity = CertificateManager.shared.existingIdentity()
        else { return false }

        if sessionBoxId != boxId || !isUsable(session) {
            open(box: box, fingerprint: fingerprint, identity: identity)
        }
        guard await waitUntilReady(), let session else {
            close()
            return false
        }
        // Wait until the network stack took the key, before perform() returns and iOS
        // may suspend the extension.
        guard await session.sendKeyConfirmed(code) else {
            close()
            return false
        }
        scheduleIdleClose()
        return true
    }

    /// A kept session is used again only if it is open and the box was heard recently.
    /// After a suspension, the state can still read connected while the socket is dead.
    private func isUsable(_ session: AtvRemoteSession?) -> Bool {
        guard let session else { return false }
        switch session.state {
        case .closed: return false
        case .connecting: return true
        case .connected: return session.secondsSinceActivity < Self.maxSilence
        }
    }

    private func open(box: SavedBox, fingerprint: String, identity: SecIdentity) {
        close()
        let session = AtvRemoteSession(
            host: box.host,
            port: box.port,
            identity: identity,
            pinnedFingerprint: fingerprint,
            model: "iPhone"
        )
        session.onStateChange = { [weak self, weak session] state in
            guard let self, case .closed = state, self.session === session else { return }
            self.session = nil
            self.sessionBoxId = nil
        }
        self.session = session
        sessionBoxId = box.id
        session.connect()
    }

    /// Waits until the box accepted the session (configure, then set-active), up to 5 s.
    /// If set-active does not come within 1.5 s after configure, the press goes anyway.
    private func waitUntilReady() async -> Bool {
        let deadline = Date().addingTimeInterval(Self.readyTimeout)
        var configuredAt: Date?
        while Date() < deadline {
            guard let session else { return false }
            switch session.state {
            case .connected:
                if session.isActive { return true }
                let since = configuredAt ?? Date()
                configuredAt = since
                if Date().timeIntervalSince(since) > Self.activeGrace { return true }
            case .closed:
                return false
            case .connecting:
                break
            }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return false
    }

    private func scheduleIdleClose() {
        idleTask?.cancel()
        idleTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.keepAliveNanos)
            guard !Task.isCancelled else { return }
            self?.close()
        }
    }

    private func close() {
        idleTask?.cancel()
        idleTask = nil
        session?.disconnect()
        session = nil
        sessionBoxId = nil
    }
}

/// Small widget state in the shared UserDefaults: the "next device" choice and the last failure.
enum WidgetStatus {
    private static var defaults: UserDefaults { SharedConfig.defaults }
    private static let failureIdKey = "widget.failure.id"
    private static let failureTimeKey = "widget.failure.time"

    /// How long the "Cannot reach" line stays.
    static let failureSeconds: TimeInterval = 10

    /// The key of the "next device" choice of widgets that have `configuredId` (nil: no device chosen).
    private static func overrideKey(_ configuredId: String?) -> String {
        "widget.override.\(SharedConfig.widgetKind).\(configuredId ?? "none")"
    }

    /// "Next device": shows the saved device after `shownId`. Back at the configured device,
    /// the choice is removed, so the widget follows its configuration again.
    static func showNext(after shownId: String, configuredId: String?) {
        let devices = BoxStore(notifyWidgets: false).savedDevices
        guard devices.count > 1 else {
            defaults.removeObject(forKey: overrideKey(configuredId))
            return
        }
        let index = devices.firstIndex { $0.id == shownId } ?? -1
        let next = devices[(index + 1) % devices.count]
        let configured = configuredId ?? devices.first?.id
        if next.id == configured {
            defaults.removeObject(forKey: overrideKey(configuredId))
        } else {
            defaults.set(next.id, forKey: overrideKey(configuredId))
        }
    }

    static func recordFailure(_ boxId: String, at date: Date = Date()) {
        defaults.set(boxId, forKey: failureIdKey)
        defaults.set(date.timeIntervalSince1970, forKey: failureTimeKey)
    }

    static func clearFailure() {
        defaults.removeObject(forKey: failureIdKey)
        defaults.removeObject(forKey: failureTimeKey)
    }

    /// The time of a recent failure for `boxId`, or nil.
    static func recentFailure(_ boxId: String, now: Date = Date()) -> Date? {
        guard defaults.string(forKey: failureIdKey) == boxId else { return nil }
        let time = Date(timeIntervalSince1970: defaults.double(forKey: failureTimeKey))
        guard now.timeIntervalSince(time) < failureSeconds else { return nil }
        return time
    }

    /// The device that a widget shows: its "next device" choice if that device still exists,
    /// else the configured device, else the first saved device.
    static func device(configuredId: String?) -> (box: SavedBox, count: Int)? {
        let devices = BoxStore(notifyWidgets: false).savedDevices
        guard !devices.isEmpty else { return nil }
        let key = overrideKey(configuredId)
        if let chosen = defaults.string(forKey: key) {
            if let box = devices.first(where: { $0.id == chosen }) {
                return (box, devices.count)
            }
            // The chosen device was forgotten.
            defaults.removeObject(forKey: key)
        }
        let configured = configuredId.flatMap { id in devices.first { $0.id == id } }
        return (configured ?? devices[0], devices.count)
    }
}
