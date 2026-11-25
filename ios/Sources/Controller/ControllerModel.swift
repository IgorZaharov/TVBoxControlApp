import SwiftUI
import UIKit

/// The touch engine of the controller screen and of the layout editor.
/// Each finger is tracked on its own, so several controls work at the same time.
/// It knows nothing about the network: `Output` sends the keys and the pointer moves.
@MainActor
final class ControllerModel: ObservableObject {
    struct Output {
        /// A key code goes down (true) or up (false).
        var key: @MainActor (Int, Bool) -> Void
        /// A pointer move in TV pixels.
        var pointer: @MainActor (Double, Double) -> Void
        var haptic: @MainActor () -> Void
        var exit: @MainActor () -> Void
    }

    @Published var layout: ControllerLayout
    @Published private(set) var pressed: Set<ControllerControl> = []
    @Published private(set) var deflections: [ControllerControl: CGPoint] = [:]
    @Published private(set) var dpadArrows: Set<RemoteKey> = []
    @Published var selected: ControllerControl?

    let editing: Bool
    var areaSize: CGSize = .zero
    var pointerAvailable = false
    var pointerSpeed = 1.0
    var output: Output?

    private struct Track {
        let control: ControllerControl
        let start: CGPoint
        let startTime: Date
        var moved = false
        /// Editor: from the finger to the center of the dragged control.
        var dragOffset: CGSize = .zero
    }

    private var tracks: [ObjectIdentifier: Track] = [:]
    /// The arrows that each source (left stick, D-pad) holds now.
    private var arrowSources: [ControllerControl: Set<RemoteKey>] = [:]
    /// The arrows that are down on the box.
    private var arrowsDown: Set<RemoteKey> = []
    private var pointerTask: Task<Void, Never>?

    /// A finger that moves less than this is a tap (stick click).
    private static let tapSlop: CGFloat = 10
    private static let tapTime: TimeInterval = 0.35
    /// TV pixels per pointer tick at full deflection and speed 1 (about 1000 px/s).
    private static let pointerPixelsPerTick = 16.0

    init(editing: Bool, layout: ControllerLayout = ControllerLayoutStore.load()) {
        self.editing = editing
        self.layout = layout
    }

    // MARK: - Geometry

    func frame(of control: ControllerControl) -> CGRect {
        layout.frame(of: control, in: areaSize)
    }

    /// Travel of a stick knob from the center, in points.
    func stickTravel(_ control: ControllerControl) -> CGFloat {
        let size = layout.size(of: control).width
        return (size - size * 60 / 132) / 2
    }

    func control(at point: CGPoint) -> ControllerControl? {
        for control in ControllerControl.hitOrder {
            let frame = frame(of: control)
            switch control.kind {
            case .stick, .dpad:
                let radius = frame.width / 2 * 1.1
                let dx = point.x - frame.midX
                let dy = point.y - frame.midY
                if dx * dx + dy * dy <= radius * radius { return control }
            default:
                if frame.insetBy(dx: -8, dy: -8).contains(point) { return control }
            }
        }
        return nil
    }

    // MARK: - Touches

    func touchBegan(_ id: ObjectIdentifier, at point: CGPoint) {
        let hit = control(at: point)
        if editing {
            selected = hit
            guard let hit else { return }
            let frame = frame(of: hit)
            var track = Track(control: hit, start: point, startTime: Date())
            track.dragOffset = CGSize(width: frame.midX - point.x, height: frame.midY - point.y)
            tracks[id] = track
            return
        }
        guard let hit else { return }
        tracks[id] = Track(control: hit, start: point, startTime: Date())
        let wasPressed = pressed.contains(hit)
        pressed.insert(hit)
        switch hit.kind {
        case .face, .shoulder, .pill:
            guard !wasPressed else { return }
            output?.haptic()
            if let code = hit.keyCode { output?.key(code, true) }
        case .stick:
            updateStick(hit, point: point)
        case .dpad:
            updateDpad(point: point)
        }
    }

    func touchMoved(_ id: ObjectIdentifier, to point: CGPoint) {
        guard var track = tracks[id] else { return }
        let dx = point.x - track.start.x
        let dy = point.y - track.start.y
        if dx * dx + dy * dy > Self.tapSlop * Self.tapSlop { track.moved = true }
        tracks[id] = track
        if editing {
            move(track.control, center: CGPoint(x: point.x + track.dragOffset.width, y: point.y + track.dragOffset.height))
            return
        }
        switch track.control.kind {
        case .stick: updateStick(track.control, point: point)
        case .dpad: updateDpad(point: point)
        default: break
        }
    }

    func touchEnded(_ id: ObjectIdentifier, cancelled: Bool) {
        guard let track = tracks.removeValue(forKey: id) else { return }
        guard !editing else { return }
        let control = track.control
        // Another finger can still hold the same control.
        guard !tracks.values.contains(where: { $0.control == control }) else { return }
        pressed.remove(control)
        switch control.kind {
        case .face, .shoulder, .pill:
            if let code = control.keyCode { output?.key(code, false) }
            if control == .exit, !cancelled { output?.exit() }
        case .stick:
            deflections[control] = .zero
            if control == .leftStick { setArrows([], from: .leftStick) }
            if control == .rightStick { stopPointer() }
            let quick = Date().timeIntervalSince(track.startTime) < Self.tapTime
            if !cancelled, !track.moved, quick, let code = control.stickClickCode {
                output?.haptic()
                output?.key(code, true)
                output?.key(code, false)
            }
        case .dpad:
            dpadArrows = []
            setArrows([], from: .dpad)
        }
    }

    /// Releases every held key. Call when the screen closes.
    func releaseAll() {
        for (id, _) in tracks {
            touchEnded(id, cancelled: true)
        }
        tracks = [:]
        stopPointer()
    }

    /// A D-pad direction from VoiceOver: key down and up at once.
    func accessibilityArrow(_ key: RemoteKey) {
        guard !editing else { return }
        output?.key(key.rawValue, true)
        output?.key(key.rawValue, false)
    }

    /// A press from VoiceOver: down and up at once.
    func accessibilityPress(_ control: ControllerControl) {
        guard !editing else { return }
        if control == .exit {
            output?.exit()
            return
        }
        guard let code = control.keyCode ?? control.stickClickCode else { return }
        output?.key(code, true)
        output?.key(code, false)
    }

    // MARK: - Sticks and D-pad

    private func updateStick(_ control: ControllerControl, point: CGPoint) {
        let frame = frame(of: control)
        let deflection = StickMapper.deflection(
            point: point,
            center: CGPoint(x: frame.midX, y: frame.midY),
            travel: stickTravel(control)
        )
        deflections[control] = deflection
        if control == .leftStick {
            setArrows(StickMapper.arrows(x: deflection.x, y: deflection.y), from: .leftStick)
        } else if control == .rightStick {
            startPointerIfNeeded()
        }
    }

    private func updateDpad(point: CGPoint) {
        let frame = frame(of: .dpad)
        let deflection = StickMapper.deflection(
            point: point,
            center: CGPoint(x: frame.midX, y: frame.midY),
            travel: frame.width / 2
        )
        let arrows = StickMapper.arrows(x: deflection.x, y: deflection.y, threshold: StickMapper.dpadThreshold)
        dpadArrows = arrows
        setArrows(arrows, from: .dpad)
    }

    /// Holds `arrows` for one source and sends the key changes for the union of all sources.
    private func setArrows(_ arrows: Set<RemoteKey>, from source: ControllerControl) {
        let before = arrowSources[source] ?? []
        guard arrows != before else { return }
        arrowSources[source] = arrows
        let target = arrowSources.values.reduce(into: Set<RemoteKey>()) { $0.formUnion($1) }
        let released = arrowsDown.subtracting(target)
        let added = target.subtracting(arrowsDown)
        arrowsDown = target
        for key in released { output?.key(key.rawValue, false) }
        for key in added { output?.key(key.rawValue, true) }
        if !arrows.subtracting(before).isEmpty { output?.haptic() }
    }

    private func startPointerIfNeeded() {
        guard pointerAvailable, pointerTask == nil else { return }
        pointerTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                let deflection = self.deflections[.rightStick] ?? .zero
                let length = Double((deflection.x * deflection.x + deflection.y * deflection.y).squareRoot())
                if length > 0.08 {
                    // Quadratic response: slow near the center, fast at the edge.
                    let factor = Self.pointerPixelsPerTick * self.pointerSpeed * length
                    self.output?.pointer(Double(deflection.x) * factor, Double(deflection.y) * factor)
                }
                try? await Task.sleep(nanoseconds: 16_000_000)
            }
        }
    }

    private func stopPointer() {
        pointerTask?.cancel()
        pointerTask = nil
    }

    // MARK: - Editor

    /// Moves a control so that its center is at `center` (points), inside the area.
    func move(_ control: ControllerControl, center: CGPoint) {
        guard areaSize.width > 0, areaSize.height > 0 else { return }
        var placement = layout.placement(control)
        let clamped = ControllerLayout.clamp(
            center: CGPoint(x: center.x / areaSize.width, y: center.y / areaSize.height),
            controlSize: layout.size(of: control),
            areaSize: areaSize
        )
        placement.x = Double(clamped.x)
        placement.y = Double(clamped.y)
        layout.set(placement, for: control)
    }

    func scale(of control: ControllerControl) -> Double {
        ControllerLayout.clampScale(layout.placement(control).scale)
    }

    func setScale(_ scale: Double, for control: ControllerControl) {
        var placement = layout.placement(control)
        placement.scale = ControllerLayout.clampScale(scale)
        layout.set(placement, for: control)
        // A larger control can now cross the edge: move it back inside.
        let frame = frame(of: control)
        move(control, center: CGPoint(x: frame.midX, y: frame.midY))
    }

    func resetLayout() {
        layout = .standard
        selected = nil
    }

    func saveLayout() {
        ControllerLayoutStore.save(layout)
    }
}

/// A transparent UIKit view that reports every finger on its own to the model.
/// SwiftUI gestures cannot track several fingers on separate controls reliably.
struct MultiTouchSurface: UIViewRepresentable {
    let model: ControllerModel

    func makeUIView(context: Context) -> TouchView {
        let view = TouchView()
        view.model = model
        return view
    }

    func updateUIView(_ view: TouchView, context: Context) {
        view.model = model
    }

    final class TouchView: UIView {
        weak var model: ControllerModel?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isMultipleTouchEnabled = true
            backgroundColor = .clear
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) {
            fatalError("init(coder:) is not used")
        }

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            for touch in touches {
                model?.touchBegan(ObjectIdentifier(touch), at: touch.location(in: self))
            }
        }

        override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
            for touch in touches {
                model?.touchMoved(ObjectIdentifier(touch), to: touch.location(in: self))
            }
        }

        override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
            for touch in touches {
                model?.touchEnded(ObjectIdentifier(touch), cancelled: false)
            }
        }

        override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
            for touch in touches {
                model?.touchEnded(ObjectIdentifier(touch), cancelled: true)
            }
        }
    }
}
