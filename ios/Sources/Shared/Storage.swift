import Foundation
#if canImport(WidgetKit)
import WidgetKit
#endif

/// A box that the user paired with or added by IP address.
struct SavedBox: Codable, Equatable {
    var id: String
    var name: String
    var host: String
    var port: UInt16
    /// SHA-256 fingerprint of the box certificate. Nil until pairing completes.
    var fingerprint: String?
    /// Token from the receiver app on this box.
    var receiverToken: String?
    var isManual: Bool
}

/// Stores saved boxes in the shared UserDefaults (App Group), so that the widget
/// can read them. The app writes. The widget reads.
final class BoxStore {
    private let defaults: UserDefaults
    private let notifyWidgets: Bool
    private let boxesKey = "savedBoxes.v1"
    private let lastBoxKey = "lastBoxId"
    private let recentKey = "recentBoxIds.v1"
    private var boxes: [String: SavedBox]

    init(defaults: UserDefaults = SharedConfig.defaults, notifyWidgets: Bool = true) {
        self.defaults = defaults
        self.notifyWidgets = notifyWidgets
        if let data = defaults.data(forKey: boxesKey),
           let decoded = try? JSONDecoder().decode([String: SavedBox].self, from: data) {
            boxes = decoded
        } else {
            boxes = [:]
        }
    }

    var all: [SavedBox] {
        boxes.values.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    func box(_ id: String) -> SavedBox? {
        boxes[id]
    }

    func save(_ box: SavedBox) {
        let old = boxes[box.id]
        boxes[box.id] = box
        persist()
        if old?.name != box.name || old?.host != box.host || old?.port != box.port || old?.fingerprint != box.fingerprint {
            reloadWidgets()
        }
    }

    func update(_ id: String, _ change: (inout SavedBox) -> Void) {
        guard let old = boxes[id] else { return }
        var box = old
        change(&box)
        guard box != old else { return }
        boxes[id] = box
        persist()
        if old.name != box.name || old.host != box.host || old.port != box.port || old.fingerprint != box.fingerprint {
            reloadWidgets()
        }
    }

    func remove(_ id: String) {
        boxes[id] = nil
        if lastBoxId == id { lastBoxId = nil }
        recentIds = recentIds.filter { $0 != id }
        persist()
        reloadWidgets()
    }

    var lastBoxId: String? {
        get { defaults.string(forKey: lastBoxKey) }
        set { defaults.set(newValue, forKey: lastBoxKey) }
    }

    // MARK: - Saved devices (widget)

    /// The ids of the boxes that the app connected to, the most recent first.
    private(set) var recentIds: [String] {
        get { defaults.stringArray(forKey: recentKey) ?? [] }
        set { defaults.set(newValue, forKey: recentKey) }
    }

    /// Call after a successful connection. Moves the box to the front of the saved devices.
    func markConnected(_ id: String) {
        var ids = recentIds
        guard ids.first != id else { return }
        ids.removeAll { $0 == id }
        ids.insert(id, at: 0)
        recentIds = ids
        reloadWidgets()
    }

    /// The saved devices for the widget: paired boxes that the app connected to, the most recent first.
    var savedDevices: [SavedBox] {
        recentIds.compactMap { id in
            guard let box = boxes[id], box.fingerprint != nil else { return nil }
            return box
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(boxes) {
            defaults.set(data, forKey: boxesKey)
        }
    }

    private func reloadWidgets() {
        guard notifyWidgets else { return }
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }
}
