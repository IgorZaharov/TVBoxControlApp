import SwiftUI
import UIKit

// MARK: - Controller screen

/// The full-screen game controller, landscape only. See docs/UI_SPEC.md, "Controller mode".
/// iOS uses Wi-Fi only: buttons send gamepad key codes as long presses, the left stick
/// holds arrow keys, and the right stick moves the pointer through the receiver app.
@MainActor
struct ControllerView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var controller = ControllerModel(editing: false)

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                ControllerCanvas(
                    controller: controller,
                    size: geometry.size,
                    pointerReady: model.controllerPointerReady
                )
                MultiTouchSurface(model: controller)
                    .accessibilityHidden(true)
                Text(L10n.t("controller.wifiNote"))
                    .font(.caption)
                    .foregroundColor(Theme.muted)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Theme.surface.opacity(0.85)))
                    .position(x: geometry.size.width / 2, y: geometry.size.height * 0.22)
                    .allowsHitTesting(false)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .onAppear {
                controller.areaSize = geometry.size
                connectOutput()
            }
            .onChange(of: geometry.size) { size in
                controller.releaseAll()
                controller.areaSize = size
            }
        }
        .background(RemoteBackground())
        .landscapeFullScreen(prefs: prefs)
        .onChange(of: model.controllerPointerReady) { ready in
            controller.pointerAvailable = ready
        }
        .onChange(of: scenePhase) { phase in
            if phase != .active { controller.releaseAll() }
        }
        .onDisappear {
            controller.releaseAll()
        }
    }

    private func connectOutput() {
        controller.pointerAvailable = model.controllerPointerReady
        controller.pointerSpeed = prefs.touchpadSpeed
        let model = self.model
        let prefs = self.prefs
        controller.output = ControllerModel.Output(
            key: { code, down in model.controllerKey(code, down: down) },
            pointer: { dx, dy in model.controllerPointer(dx: dx, dy: dy) },
            haptic: { Haptics.tick(enabled: prefs.haptics) },
            exit: { model.closeController() }
        )
    }
}

// MARK: - Layout editor

/// Moves and resizes the controls of the controller screen. The controls send nothing here.
@MainActor
struct ControllerLayoutEditor: View {
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = ControllerModel(editing: true)

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                ControllerCanvas(controller: controller, size: geometry.size, pointerReady: true)
                MultiTouchSurface(model: controller)
                    .accessibilityHidden(true)
                // In the free middle area of the default layout: between the sticks and below
                // Select and Start. Touches outside the panel go to the controls below it.
                panel
                    .position(x: geometry.size.width / 2, y: geometry.size.height * 0.37)
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .onAppear { controller.areaSize = geometry.size }
            .onChange(of: geometry.size) { size in controller.areaSize = size }
        }
        .background(RemoteBackground())
        .landscapeFullScreen(prefs: prefs)
    }

    private var panel: some View {
        VStack(spacing: 10) {
            Text(L10n.t("controller.editor.title"))
                .font(.headline)
                .foregroundColor(Theme.onSurface)
            if let selected = controller.selected {
                Text(L10n.f("controller.editor.size %@", selected.caption))
                    .font(.footnote)
                    .foregroundColor(Theme.muted)
                HStack(spacing: 10) {
                    Slider(
                        value: Binding(
                            get: { controller.scale(of: selected) },
                            set: { controller.setScale($0, for: selected) }
                        ),
                        in: ControllerLayout.scaleRange,
                        step: 0.05
                    )
                    .accessibilityLabel(L10n.f("controller.editor.size %@", selected.caption))
                    Text("\(Int((controller.scale(of: selected) * 100).rounded())) %")
                        .font(.footnote.monospacedDigit())
                        .foregroundColor(Theme.onSurface)
                        .frame(width: 48, alignment: .trailing)
                }
            } else {
                Text(L10n.t("controller.editor.hint"))
                    .font(.footnote)
                    .foregroundColor(Theme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            // The labels never wrap: in a long language the buttons go under each other.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { editorButtons }
                VStack(spacing: 8) { editorButtons }
            }
            .controlSize(.small)
        }
        .padding(14)
        .frame(width: 340)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(.ultraThinMaterial))
    }

    @ViewBuilder private var editorButtons: some View {
        // Closes without saving the changes.
        Button(L10n.t("action.cancel")) { dismiss() }
            .buttonStyle(.bordered)
            .lineLimit(1)
            .fixedSize()
        Button(L10n.t("action.reset")) { controller.resetLayout() }
            .buttonStyle(.bordered)
            .lineLimit(1)
            .fixedSize()
        Button(L10n.t("action.done")) {
            controller.saveLayout()
            dismiss()
        }
        .buttonStyle(.borderedProminent)
        .lineLimit(1)
        .fixedSize()
    }
}

// MARK: - Drawing

/// Draws every control at its place in the layout. It does no touch handling:
/// MultiTouchSurface on top of it sends all touches to the model.
@MainActor
struct ControllerCanvas: View {
    @ObservedObject var controller: ControllerModel
    let size: CGSize
    var pointerReady: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            ForEach(ControllerControl.allCases, id: \.self) { control in
                let frame = controller.layout.frame(of: control, in: size)
                ControlView(
                    control: control,
                    controller: controller,
                    scale: frame.width / control.baseSize.width,
                    pointerReady: pointerReady
                )
                .frame(width: frame.width, height: frame.height)
                .position(x: frame.midX, y: frame.midY)
            }
        }
        .frame(width: size.width, height: size.height)
    }
}

/// One control, drawn with the raised surfaces of the remote screen.
@MainActor
private struct ControlView: View {
    let control: ControllerControl
    @ObservedObject var controller: ControllerModel
    let scale: CGFloat
    let pointerReady: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accent) private var accent

    private var pressed: Bool { controller.pressed.contains(control) }
    private var selected: Bool { controller.editing && controller.selected == control }

    var body: some View {
        content
            .overlay(selectionOutline)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(control.caption)
            .accessibilityAddTraits(.isButton)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityAction {
                if controller.editing {
                    controller.selected = control
                } else {
                    controller.accessibilityPress(control)
                }
            }
            .modifier(DpadAccessibilityActions(control: control, controller: controller))
    }

    @ViewBuilder
    private var content: some View {
        switch control.kind {
        case .face:
            ZStack {
                RaisedSurface(shape: Circle(), pressed: pressed, glow: faceColor)
                Text(control.caption)
                    .font(.system(size: 26 * scale, weight: .bold, design: .rounded))
                    .foregroundColor(faceColor)
            }
            .scaleEffect(pressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: pressed)
        case .shoulder:
            ZStack {
                RaisedSurface(shape: RoundedRectangle(cornerRadius: 14 * scale, style: .continuous), pressed: pressed)
                Text(control.caption)
                    .font(.system(size: 17 * scale, weight: .bold, design: .rounded))
                    .foregroundColor(Theme.onSurface)
            }
            .scaleEffect(pressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: pressed)
        case .pill:
            ZStack {
                RaisedSurface(shape: Capsule(), pressed: pressed)
                if control == .exit {
                    Image(systemName: "xmark")
                        .font(.system(size: 15 * scale, weight: .bold))
                        .foregroundColor(Theme.onSurface)
                } else {
                    Text(control.caption)
                        .font(.system(size: 12 * scale, weight: .semibold))
                        .foregroundColor(Theme.onSurface)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                        .padding(.horizontal, 6)
                }
            }
            .scaleEffect(pressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: pressed)
        case .stick:
            stick
        case .dpad:
            dpad
        }
    }

    private var faceColor: Color {
        switch control {
        case .a: return Theme.ok
        case .b: return Theme.power
        case .x: return Theme.faceBlue
        default: return Theme.warn
        }
    }

    private var stick: some View {
        let deflection = controller.deflections[control] ?? .zero
        let travel = controller.stickTravel(control)
        let knob = 60 * scale
        let unavailable = control == .rightStick && !pointerReady && !controller.editing
        return ZStack {
            Circle()
                .fill(RadialGradient(colors: [Theme.wellCenter, Theme.wellEdge], center: .center, startRadius: 0, endRadius: 66 * scale))
            Circle()
                .strokeBorder(
                    LinearGradient(
                        colors: [Color.white.opacity(0), Theme.rim],
                        startPoint: .top,
                        endPoint: .bottom
                    ),
                    lineWidth: 1
                )
            RaisedSurface(shape: Circle(), pressed: pressed)
                .frame(width: knob, height: knob)
                .offset(x: deflection.x * travel, y: deflection.y * travel)
                // Follow the finger without delay. Spring back to the center on release.
                .animation(deflection == .zero ? Motion.press : nil, value: deflection)
            if unavailable {
                Text(L10n.t("controller.unavailable"))
                    .font(.system(size: 11 * scale, weight: .semibold))
                    .foregroundColor(Theme.muted)
                    .multilineTextAlignment(.center)
                    .offset(y: 132 * scale / 2 + 12)
                    .fixedSize()
            }
        }
        .opacity(unavailable ? 0.45 : 1)
    }

    private var dpad: some View {
        let arrows = controller.dpadArrows
        let side = 132 * scale
        return ZStack {
            RaisedSurface(shape: Circle(), pressed: false, glowing: !arrows.isEmpty)
            ForEach([RemoteKey.right, .down, .left, .up], id: \.self) { key in
                arc(lit: arrows.contains(key), angle: angle(key), side: side)
                ArcFlash(pressed: arrows.contains(key), angle: angle(key), diameter: side, color: accent.accent)
                Image(systemName: key.symbol)
                    .font(.system(size: 20 * scale, weight: .bold))
                    .foregroundColor(arrows.contains(key) ? accent.accent : Theme.onSurface)
                    .offset(offset(key, distance: side * 0.33))
            }
        }
    }

    private func angle(_ key: RemoteKey) -> Double {
        switch key {
        case .right: return 0
        case .down: return 90
        case .left: return 180
        default: return 270
        }
    }

    private func offset(_ key: RemoteKey, distance: CGFloat) -> CGSize {
        switch key {
        case .right: return CGSize(width: distance, height: 0)
        case .down: return CGSize(width: 0, height: distance)
        case .left: return CGSize(width: -distance, height: 0)
        default: return CGSize(width: 0, height: -distance)
        }
    }

    /// The light on the inner edge of the ring, on the side of a held arrow.
    private func arc(lit: Bool, angle: Double, side: CGFloat) -> some View {
        Circle()
            .trim(from: 0.02, to: 0.23)
            .stroke(accent.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
            .shadow(color: accent.accent, radius: 6)
            .frame(width: side - 10, height: side - 10)
            .rotationEffect(.degrees(angle - 45))
            .opacity(lit ? 1 : 0)
            .animation(lit ? Motion.pop : Motion.fadeOut, value: lit)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private var selectionOutline: some View {
        if selected {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .strokeBorder(accent.accent, style: StrokeStyle(lineWidth: 2, dash: [6, 4]))
                .padding(-6)
                .allowsHitTesting(false)
        }
    }
}

/// VoiceOver: the D-pad has one action per direction ("Up", "Down", "Left", "Right").
@MainActor
private struct DpadAccessibilityActions: ViewModifier {
    let control: ControllerControl
    @ObservedObject var controller: ControllerModel

    @ViewBuilder
    func body(content: Content) -> some View {
        if control == .dpad && !controller.editing {
            content
                .accessibilityAction(named: Text(RemoteKey.up.title)) { controller.accessibilityArrow(.up) }
                .accessibilityAction(named: Text(RemoteKey.down.title)) { controller.accessibilityArrow(.down) }
                .accessibilityAction(named: Text(RemoteKey.left.title)) { controller.accessibilityArrow(.left) }
                .accessibilityAction(named: Text(RemoteKey.right.title)) { controller.accessibilityArrow(.right) }
        } else {
            content
        }
    }
}
