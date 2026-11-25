import CoreGraphics
import Foundation

/// One control of the game controller screen. See docs/UI_SPEC.md, "Controller mode".
enum ControllerControl: String, CaseIterable, Codable {
    case a, b, x, y
    case l1, r1, l2, r2
    case select, exit, start
    case leftStick, rightStick
    case dpad

    enum Kind {
        case face, shoulder, pill, stick, dpad
    }

    var kind: Kind {
        switch self {
        case .a, .b, .x, .y: return .face
        case .l1, .r1, .l2, .r2: return .shoulder
        case .select, .exit, .start: return .pill
        case .leftStick, .rightStick: return .stick
        case .dpad: return .dpad
        }
    }

    /// The size at scale 1, in points.
    var baseSize: CGSize {
        switch kind {
        case .face: return CGSize(width: 64, height: 64)
        case .shoulder: return CGSize(width: 96, height: 44)
        case .pill: return CGSize(width: 76, height: 36)
        case .stick: return CGSize(width: 132, height: 132)
        case .dpad: return CGSize(width: 132, height: 132)
        }
    }

    /// The Android key code that the button sends over Wi-Fi. Nil for the sticks, the D-pad, and Exit.
    var keyCode: Int? {
        switch self {
        case .a: return 96
        case .b: return 97
        case .x: return 99
        case .y: return 100
        case .l1: return 102
        case .r1: return 103
        case .l2: return 104
        case .r2: return 105
        case .start: return 108
        case .select: return 109
        case .leftStick, .rightStick, .dpad, .exit: return nil
        }
    }

    /// The key code of a click on the stick (L3, R3).
    var stickClickCode: Int? {
        switch self {
        case .leftStick: return 106
        case .rightStick: return 107
        default: return nil
        }
    }

    /// The text on the control (letters and shoulder names are the same in all languages).
    var caption: String {
        switch self {
        case .a: return "A"
        case .b: return "B"
        case .x: return "X"
        case .y: return "Y"
        case .l1: return "L1"
        case .r1: return "R1"
        case .l2: return "L2"
        case .r2: return "R2"
        case .select: return L10n.t("controller.select")
        case .exit: return L10n.t("controller.exit")
        case .start: return L10n.t("controller.start")
        case .leftStick: return L10n.t("controller.leftStick")
        case .rightStick: return L10n.t("controller.rightStick")
        case .dpad: return L10n.t("controller.dpad")
        }
    }

    /// The control under a finger: the smaller controls win over the large ones that they overlap.
    static let hitOrder: [ControllerControl] = [
        .a, .b, .x, .y, .l1, .r1, .l2, .r2, .select, .exit, .start, .dpad, .leftStick, .rightStick,
    ]
}

/// Where one control is: its center as a fraction (0...1) of the controller area,
/// and its size factor.
struct ControlPlacement: Codable, Equatable {
    var x: Double
    var y: Double
    var scale: Double
}

/// The positions of all controls. Stored as JSON in the shared UserDefaults.
struct ControllerLayout: Codable, Equatable {
    static let scaleRange: ClosedRange<Double> = 0.6...1.6

    var placements: [String: ControlPlacement]

    /// The default layout for a phone in landscape (about 844 x 390 points).
    static let standard = ControllerLayout(placements: [
        ControllerControl.l2.rawValue: ControlPlacement(x: 0.10, y: 0.10, scale: 1),
        ControllerControl.l1.rawValue: ControlPlacement(x: 0.10, y: 0.25, scale: 1),
        ControllerControl.r2.rawValue: ControlPlacement(x: 0.90, y: 0.10, scale: 1),
        ControllerControl.r1.rawValue: ControlPlacement(x: 0.90, y: 0.25, scale: 1),
        ControllerControl.select.rawValue: ControlPlacement(x: 0.40, y: 0.09, scale: 1),
        ControllerControl.exit.rawValue: ControlPlacement(x: 0.50, y: 0.09, scale: 1),
        ControllerControl.start.rawValue: ControlPlacement(x: 0.60, y: 0.09, scale: 1),
        ControllerControl.leftStick.rawValue: ControlPlacement(x: 0.13, y: 0.56, scale: 1),
        ControllerControl.dpad.rawValue: ControlPlacement(x: 0.31, y: 0.80, scale: 1),
        ControllerControl.y.rawValue: ControlPlacement(x: 0.87, y: 0.42, scale: 1),
        ControllerControl.x.rawValue: ControlPlacement(x: 0.81, y: 0.55, scale: 1),
        ControllerControl.b.rawValue: ControlPlacement(x: 0.93, y: 0.55, scale: 1),
        ControllerControl.a.rawValue: ControlPlacement(x: 0.87, y: 0.68, scale: 1),
        ControllerControl.rightStick.rawValue: ControlPlacement(x: 0.68, y: 0.80, scale: 1),
    ])

    func placement(_ control: ControllerControl) -> ControlPlacement {
        placements[control.rawValue]
            ?? Self.standard.placements[control.rawValue]
            ?? ControlPlacement(x: 0.5, y: 0.5, scale: 1)
    }

    mutating func set(_ placement: ControlPlacement, for control: ControllerControl) {
        placements[control.rawValue] = placement
    }

    /// The size of a control in points.
    func size(of control: ControllerControl) -> CGSize {
        let scale = Self.clampScale(placement(control).scale)
        return CGSize(width: control.baseSize.width * scale, height: control.baseSize.height * scale)
    }

    /// The frame of a control in an area of `areaSize` points.
    func frame(of control: ControllerControl, in areaSize: CGSize) -> CGRect {
        let placement = placement(control)
        let size = size(of: control)
        let center = Self.clamp(
            center: CGPoint(x: placement.x, y: placement.y),
            controlSize: size,
            areaSize: areaSize
        )
        return CGRect(
            x: center.x * areaSize.width - size.width / 2,
            y: center.y * areaSize.height - size.height / 2,
            width: size.width,
            height: size.height
        )
    }

    static func clampScale(_ scale: Double) -> Double {
        min(scaleRange.upperBound, max(scaleRange.lowerBound, scale))
    }

    /// Keeps a control inside the area: its center (as fractions) moves so that no
    /// edge of the control goes past the edge of the area. A control larger than the
    /// area is centered on that axis.
    static func clamp(center: CGPoint, controlSize: CGSize, areaSize: CGSize) -> CGPoint {
        func axis(_ value: CGFloat, _ control: CGFloat, _ area: CGFloat) -> CGFloat {
            guard area > 0 else { return 0.5 }
            let half = control / 2 / area
            guard half < 0.5 else { return 0.5 }
            return min(1 - half, max(half, value))
        }
        return CGPoint(
            x: axis(center.x, controlSize.width, areaSize.width),
            y: axis(center.y, controlSize.height, areaSize.height)
        )
    }
}

/// Loads and saves the controller layout. The layout is the same for all devices.
enum ControllerLayoutStore {
    private static let key = "controller.layout.v1"

    static func load(defaults: UserDefaults = SharedConfig.defaults) -> ControllerLayout {
        guard let data = defaults.data(forKey: key),
              let layout = try? JSONDecoder().decode(ControllerLayout.self, from: data)
        else { return .standard }
        return layout
    }

    static func save(_ layout: ControllerLayout, defaults: UserDefaults = SharedConfig.defaults) {
        if let data = try? JSONEncoder().encode(layout) {
            defaults.set(data, forKey: key)
        }
    }
}

/// Turns a stick or D-pad deflection into arrow keys.
enum StickMapper {
    /// The left stick holds arrows past 50% of its travel.
    static let stickThreshold: CGFloat = 0.5
    /// The D-pad reacts closer to its center.
    static let dpadThreshold: CGFloat = 0.25

    /// The arrows for a deflection. `x` grows to the right, `y` grows down, both -1...1.
    /// 8 directions of 45 degrees each: a diagonal holds two arrows.
    static func arrows(x: CGFloat, y: CGFloat, threshold: CGFloat = stickThreshold) -> Set<RemoteKey> {
        let magnitude = (x * x + y * y).squareRoot()
        guard magnitude >= threshold, magnitude > 0 else { return [] }
        // Angle in degrees, 0 = right, 90 = up (screen y grows down).
        var degrees = atan2(-y, x) * 180 / .pi
        if degrees < 0 { degrees += 360 }
        let sector = Int(((degrees + 22.5) / 45).rounded(.down)) % 8
        switch sector {
        case 0: return [.right]
        case 1: return [.up, .right]
        case 2: return [.up]
        case 3: return [.up, .left]
        case 4: return [.left]
        case 5: return [.down, .left]
        case 6: return [.down]
        default: return [.down, .right]
        }
    }

    /// A deflection of a finger at `point` from `center`, scaled by `travel` and limited to length 1.
    static func deflection(point: CGPoint, center: CGPoint, travel: CGFloat) -> CGPoint {
        guard travel > 0 else { return .zero }
        var dx = (point.x - center.x) / travel
        var dy = (point.y - center.y) / travel
        let length = (dx * dx + dy * dy).squareRoot()
        if length > 1 {
            dx /= length
            dy /= length
        }
        return CGPoint(x: dx, y: dy)
    }
}
