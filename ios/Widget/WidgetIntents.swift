import AppIntents
import Foundation
import WidgetKit

// The widget intents live in the widget extension only. With the same intent in the
// app target too, a widget button can open the app instead of running perform().
// Titles are Localizable.strings keys: App Intents resolves LocalizedStringResource at run time.

/// A saved TV box, for the widget configuration.
struct DeviceEntity: AppEntity {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "widget.entity.device")
    static var defaultQuery = DeviceQuery()

    let id: String
    let name: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }
}

struct DeviceQuery: EntityQuery {
    private static func all() -> [DeviceEntity] {
        BoxStore(notifyWidgets: false).savedDevices.map { DeviceEntity(id: $0.id, name: $0.name) }
    }

    func entities(for identifiers: [String]) async throws -> [DeviceEntity] {
        Self.all().filter { identifiers.contains($0.id) }
    }

    func suggestedEntities() async throws -> [DeviceEntity] {
        Self.all()
    }

    func defaultResult() async -> DeviceEntity? {
        Self.all().first
    }
}

/// "Edit widget": which box this widget controls.
struct RemoteWidgetIntent: WidgetConfigurationIntent {
    static var title: LocalizedStringResource = "widget.config.title"
    static var description = IntentDescription("widget.config.description")

    @Parameter(title: "widget.config.device")
    var device: DeviceEntity?

    init() {}
}

/// A widget button: sends one key to the box of the widget. The app does not open.
struct PressKeyIntent: AppIntent {
    static var title: LocalizedStringResource = "widget.intent.press"
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.intent.key")
    var keyCode: Int

    @Parameter(title: "widget.config.device")
    var deviceId: String

    init() {}

    init(key: RemoteKey, deviceId: String) {
        self.keyCode = key.rawValue
        self.deviceId = deviceId
    }

    func perform() async throws -> some IntentResult {
        let sent = await WidgetRemote.shared.send(key: keyCode, to: deviceId)
        if sent {
            if WidgetStatus.recentFailure(deviceId) != nil {
                WidgetStatus.clearFailure()
                WidgetCenter.shared.reloadTimelines(ofKind: SharedConfig.widgetKind)
            }
        } else {
            WidgetStatus.recordFailure(deviceId)
            WidgetCenter.shared.reloadTimelines(ofKind: SharedConfig.widgetKind)
        }
        return .result()
    }
}

/// The small button in the widget header: shows the next saved device.
/// The choice is kept per configured device, so Edit Widget still decides what a widget shows.
struct NextDeviceIntent: AppIntent {
    static var title: LocalizedStringResource = "widget.intent.next"
    static var isDiscoverable: Bool = false

    @Parameter(title: "widget.config.device")
    var configuredId: String?

    @Parameter(title: "widget.config.device")
    var shownId: String

    init() {}

    init(configuredId: String?, shownId: String) {
        self.configuredId = configuredId
        self.shownId = shownId
    }

    func perform() async throws -> some IntentResult {
        WidgetStatus.showNext(after: shownId, configuredId: configuredId)
        WidgetStatus.clearFailure()
        WidgetCenter.shared.reloadTimelines(ofKind: SharedConfig.widgetKind)
        return .result()
    }
}
