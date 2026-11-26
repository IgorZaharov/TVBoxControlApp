import CoreGraphics
import Foundation

/// The first-run tour (docs/UI_SPEC.md, "First-run tutorial"): pair a box, then "You are all set".
enum Tour: Equatable {
    case pair, allSet
}

/// The mouse walk-through, the first time the mouse screen opens. An iPhone has only the receiver way.
enum MouseTour: Equatable {
    case choose
}

/// The screen that the tour looks at.
enum TourScreen: Equatable {
    case remote, devices, mouse, other
}

/// The coach step that an overlay shows. Each step has its own target.
enum TourStep: Equatable, Hashable {
    case openDevices, pickBox, addByIp, allSet, mouseChoose

    var target: String? {
        switch self {
        case .openDevices: return TourTargets.devices
        case .pickBox: return TourTargets.wifiRows
        case .addByIp: return TourTargets.addByIp
        case .allSet: return nil
        case .mouseChoose: return TourTargets.mouseChoices
        }
    }
}

/// The names of the coach targets that the screens mark with `.coachTarget(_:)`.
enum TourTargets {
    static let devices = "devices"
    /// Each Wi-Fi row is "wifi_rows/<id>". The spotlight covers all of them.
    static let wifiRows = "wifi_rows"
    static let addByIp = "add_by_ip"
    static let mouseChoices = "mouse_choices"
}

/// The rules of the tours, the same as on Android (TourFlow.kt).
enum TourFlow {
    /// With no box found for this long, the Devices step points at "Add by IP address".
    static let emptyListWait: TimeInterval = 3

    /// The tour starts on the first launch only. A phone that paired with a box before (an update) skips it.
    static func initial(tourDone: Bool, hasPairedBox: Bool) -> Tour? {
        tourDone || hasPairedBox ? nil : .pair
    }

    /// The link to a box works: the pairing part of the tour ends with "You are all set".
    static func onConnected(_ tour: Tour?) -> Tour? {
        tour == .pair ? .allSet : tour
    }

    /// The step to show now, or nil. No card shows while a dialog is open (the pairing sheet guides by itself).
    static func step(
        tour: Tour?,
        mouseTour: MouseTour?,
        screen: TourScreen,
        wifiBoxes: Int,
        emptyForLong: Bool,
        dialogOpen: Bool
    ) -> TourStep? {
        if dialogOpen { return nil }
        switch tour {
        case .pair:
            if screen == .remote { return .openDevices }
            if screen == .devices {
                if wifiBoxes > 0 { return .pickBox }
                return emptyForLong ? .addByIp : nil
            }
        case .allSet:
            if screen == .remote { return .allSet }
        case nil:
            break
        }
        if mouseTour == .choose && screen == .mouse { return .mouseChoose }
        return nil
    }
}

/// The arrow of a coach card: a hand-drawn cubic curve from the card edge to the target edge.
struct CoachArrow: Equatable {
    var from: CGPoint
    var c1: CGPoint
    var c2: CGPoint
    var to: CGPoint
}

/// The placement rule of the coach cards, the same as on Android (CoachLayout.kt): the card goes on the side of the
/// target with more free space, at least `gap` away, so it never covers the target.
enum CoachLayout {
    static func place(
        target: CGRect?,
        screen: CGSize,
        card: CGSize,
        edge: CGFloat,
        gap: CGFloat,
        safeTop: CGFloat = 0,
        safeBottom: CGFloat = 0
    ) -> (card: CGRect, arrow: CoachArrow?) {
        let minTop = safeTop + edge
        let maxTop = max(minTop, screen.height - safeBottom - edge - card.height)
        guard let target else {
            let left = max(edge, (screen.width - card.width) / 2)
            let top = min(max((screen.height - card.height) / 2, minTop), maxTop)
            return (CGRect(x: left, y: top, width: card.width, height: card.height), nil)
        }
        let spaceBelow = screen.height - safeBottom - target.maxY
        let spaceAbove = target.minY - safeTop
        let below = spaceBelow >= card.height + gap + edge || spaceBelow >= spaceAbove
        var top = below ? target.maxY + gap : target.minY - gap - card.height
        top = min(max(top, minTop), maxTop)
        if below && top < target.maxY + edge { top = target.maxY + edge }
        if !below && top + card.height > target.minY - edge { top = target.minY - edge - card.height }
        let left = min(max(target.midX - card.width / 2, edge), max(edge, screen.width - edge - card.width))
        let cardRect = CGRect(x: left, y: top, width: card.width, height: card.height)

        let inset = min(28, cardRect.width / 4)
        let fromX = min(max((target.midX + cardRect.midX) / 2, cardRect.minX + inset), cardRect.maxX - inset)
        let fromY = below ? cardRect.minY - 6 : cardRect.maxY + 6
        let toX = min(max(target.midX + (fromX - target.midX) * 0.3, target.minX + 12), target.maxX - 12)
        let toY = below ? target.maxY + 6 : target.minY - 6
        return (cardRect, curve(from: CGPoint(x: fromX, y: fromY), to: CGPoint(x: toX, y: toY)))
    }

    /// A gentle hand-drawn bend: control points pushed to one side of the straight line.
    static func curve(from: CGPoint, to: CGPoint) -> CoachArrow {
        let dx = to.x - from.x
        let dy = to.y - from.y
        let length = max(hypot(dx, dy), 1)
        let side: CGFloat = dx >= 0 ? -1 : 1
        let bend = min(0.3 * length, 60) * side
        let nx = -dy / length * bend
        let ny = dx / length * bend
        let c1 = CGPoint(x: from.x + dx * 0.25 + nx, y: from.y + dy * 0.25 + ny)
        let c2 = CGPoint(x: from.x + dx * 0.8 + nx * 0.5, y: from.y + dy * 0.8 + ny * 0.5)
        return CoachArrow(from: from, c1: c1, c2: c2, to: to)
    }
}
