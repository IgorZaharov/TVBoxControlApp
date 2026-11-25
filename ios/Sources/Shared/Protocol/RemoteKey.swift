import Foundation

/// Android KeyEvent codes that the Android TV Remote protocol accepts.
enum RemoteKey: Int, CaseIterable {
    case home = 3
    case back = 4
    case up = 19
    case down = 20
    case left = 21
    case right = 22
    case center = 23
    case volumeUp = 24
    case volumeDown = 25
    case power = 26
    case enter = 66
    case delete = 67
    case menu = 82
    case search = 84
    case playPause = 85
    case next = 87
    case previous = 88
    case rewind = 89
    case fastForward = 90
    case mute = 164
    case info = 165
    case channelUp = 166
    case channelDown = 167
    case guide = 172
    case captions = 175
    case settings = 176
    case tvInput = 178
    case assistant = 219
    case allApps = 284

    /// The localized name of the key (Localizable.strings, "key.<case>").
    var title: String { L10n.t("key.\(name)") }

    /// The case name, used to build the strings key. Written out, so that it
    /// does not depend on reflection metadata.
    var name: String {
        switch self {
        case .home: return "home"
        case .back: return "back"
        case .up: return "up"
        case .down: return "down"
        case .left: return "left"
        case .right: return "right"
        case .center: return "center"
        case .volumeUp: return "volumeUp"
        case .volumeDown: return "volumeDown"
        case .power: return "power"
        case .enter: return "enter"
        case .delete: return "delete"
        case .menu: return "menu"
        case .search: return "search"
        case .playPause: return "playPause"
        case .next: return "next"
        case .previous: return "previous"
        case .rewind: return "rewind"
        case .fastForward: return "fastForward"
        case .mute: return "mute"
        case .info: return "info"
        case .channelUp: return "channelUp"
        case .channelDown: return "channelDown"
        case .guide: return "guide"
        case .captions: return "captions"
        case .settings: return "settings"
        case .tvInput: return "tvInput"
        case .assistant: return "assistant"
        case .allApps: return "allApps"
        }
    }

    /// SF Symbol name.
    var symbol: String {
        switch self {
        case .home: return "house.fill"
        case .back: return "arrow.uturn.backward"
        case .up: return "chevron.up"
        case .down: return "chevron.down"
        case .left: return "chevron.left"
        case .right: return "chevron.right"
        case .center: return "circle.fill"
        case .volumeUp: return "plus"
        case .volumeDown: return "minus"
        case .power: return "power"
        case .enter: return "return"
        case .delete: return "delete.left"
        case .menu: return "line.3.horizontal"
        case .search: return "magnifyingglass"
        case .playPause: return "playpause.fill"
        case .next: return "forward.end.fill"
        case .previous: return "backward.end.fill"
        case .rewind: return "backward.fill"
        case .fastForward: return "forward.fill"
        case .mute: return "speaker.slash.fill"
        case .info: return "info.circle"
        case .channelUp: return "chevron.up.square"
        case .channelDown: return "chevron.down.square"
        case .guide: return "list.bullet.rectangle"
        case .captions: return "captions.bubble"
        case .settings: return "gearshape.fill"
        case .tvInput: return "rectangle.on.rectangle"
        case .assistant: return "mic.fill"
        case .allApps: return "square.grid.3x3.fill"
        }
    }

    /// Keys in the Media keys sheet, in the order of docs/UI_SPEC.md.
    static let mediaKeys: [RemoteKey] = [
        .previous, .rewind, .playPause, .fastForward, .next,
        .channelUp, .channelDown, .tvInput, .guide, .search, .allApps, .settings,
    ]
}
