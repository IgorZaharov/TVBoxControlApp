import Foundation
import os

/// Identifiers that the app and the widget extension share.
/// The values must match Config/App.entitlements and Config/Widget.entitlements.
enum SharedConfig {
    /// The App Group that holds the shared UserDefaults.
    static let appGroup = "group.com.tvboxremote.shared"

    /// The keychain access group for the client key and certificate.
    /// Info.plist key `TVBRKeychainGroup` holds `$(AppIdentifierPrefix)com.tvboxremote.shared`,
    /// so the team prefix comes from the build. Nil in unit tests and in builds without the key.
    static var keychainGroup: String? {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "TVBRKeychainGroup") as? String,
              !value.isEmpty, !value.hasPrefix("$(")
        else { return nil }
        return value
    }

    /// The URL that the widget uses to open the app.
    static let openAppURL = URL(string: "tvboxremote://open")!

    /// The kinds of the widgets, for WidgetCenter reloads.
    static let widgetKind = "TVBoxRemoteWidget"

    private static let migratedKey = "shared.migrated.v1"

    /// Keys that older builds kept in the standard UserDefaults.
    private static let migratedKeys = [
        "savedBoxes.v1", "lastBoxId", "recentBoxIds.v1",
        "pref.haptics", "pref.gyroSpeed", "pref.touchpadSpeed", "pref.keepScreenOn",
    ]

    /// The shared UserDefaults. Falls back to the standard defaults if the App Group
    /// is not set up (for example in unit tests). The first call copies the values
    /// of older builds from the standard defaults.
    static let defaults: UserDefaults = {
        // UserDefaults(suiteName:) returns an object even without the App Group entitlement.
        // The container URL tells whether the group really exists for this build.
        guard FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) != nil,
              let shared = UserDefaults(suiteName: appGroup)
        else {
            Logger(subsystem: "com.tvboxremote.ios", category: "config").error(
                "App Group \(appGroup, privacy: .public) is not available. Check the signing team and the entitlements. The app and the widget do not share data."
            )
            return .standard
        }
        // Only the app has the old values. The widget (an .appex) must not mark the
        // migration as done before the app ran it.
        let isApp = Bundle.main.bundleURL.pathExtension == "app"
        if isApp, !shared.bool(forKey: migratedKey) {
            let standard = UserDefaults.standard
            for key in migratedKeys where shared.object(forKey: key) == nil {
                if let value = standard.object(forKey: key) {
                    shared.set(value, forKey: key)
                }
            }
            seedRecentBoxes(in: shared)
            shared.set(true, forKey: migratedKey)
        }
        return shared
    }()

    /// Older builds did not keep the list of boxes that the widget shows. Start it with the
    /// paired boxes, the last box first, so that the widget is not empty after an update.
    private static func seedRecentBoxes(in shared: UserDefaults) {
        let recentKey = "recentBoxIds.v1"
        guard (shared.stringArray(forKey: recentKey) ?? []).isEmpty,
              let data = shared.data(forKey: "savedBoxes.v1"),
              let boxes = try? JSONDecoder().decode([String: SavedBox].self, from: data)
        else { return }
        var ids = boxes.values
            .filter { $0.fingerprint != nil }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
            .map(\.id)
        if let last = shared.string(forKey: "lastBoxId"), let index = ids.firstIndex(of: last) {
            ids.remove(at: index)
            ids.insert(last, at: 0)
        }
        if !ids.isEmpty { shared.set(ids, forKey: recentKey) }
    }
}
