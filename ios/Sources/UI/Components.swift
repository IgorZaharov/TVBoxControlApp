import Combine
import SwiftUI
import UIKit

// MARK: - Press handling

/// A press target that fires on touch down, gives a haptic tick, and can
/// repeat while held (first repeat after 400 ms, then every 110 ms).
/// With `scales`, the content scales to 0.97 while pressed (not with Reduce Motion).
/// With `inScrollView`, the touch does not block scrolling of a parent ScrollView.
@MainActor
struct HoldButton<Content: View>: View {
    private let label: String
    private let repeats: Bool
    private let scales: Bool
    private let inScrollView: Bool
    private let onPressedChange: ((Bool) -> Void)?
    private let action: () -> Void
    private let content: (Bool) -> Content

    @EnvironmentObject private var prefs: Preferences
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    /// True while a finger is on the button. A GestureState also resets when the system
    /// cancels the gesture (Control Center, a call, a sheet that opens), so a press never sticks.
    @GestureState private var touching = false
    @State private var pressed = false
    @State private var repeatTask: Task<Void, Never>?

    init(
        _ label: String,
        repeats: Bool = false,
        scales: Bool = false,
        inScrollView: Bool = false,
        onPressedChange: ((Bool) -> Void)? = nil,
        action: @escaping () -> Void,
        @ViewBuilder content: @escaping (Bool) -> Content
    ) {
        self.label = label
        self.repeats = repeats
        self.scales = scales
        self.inScrollView = inScrollView
        self.onPressedChange = onPressedChange
        self.action = action
        self.content = content
    }

    private var touchGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .updating($touching) { _, state, _ in state = true }
    }

    var body: some View {
        content(pressed)
            .scaleEffect(scales && pressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: pressed)
            .contentShape(Rectangle())
            .modifier(TouchGestureModifier(gesture: touchGesture, simultaneous: inScrollView))
            .onChange(of: touching) { isTouching in
                if isTouching {
                    guard !pressed else { return }
                    setPressed(true)
                    begin()
                } else {
                    release()
                }
            }
            .onChange(of: scenePhase) { phase in
                if phase != .active { release() }
            }
            .onDisappear { release() }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(label)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { action() }
    }

    private func setPressed(_ value: Bool) {
        pressed = value
        onPressedChange?(value)
    }

    private func release() {
        if pressed { setPressed(false) }
        end()
    }

    private func begin() {
        Haptics.press(prefs)
        action()
        guard repeats else { return }
        let action = self.action
        repeatTask?.cancel()
        repeatTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: Theme.repeatDelayNanos)
                while !Task.isCancelled {
                    action()
                    try await Task.sleep(nanoseconds: Theme.repeatIntervalNanos)
                }
            } catch {
                // Cancelled: the finger left the button.
            }
        }
    }

    private func end() {
        repeatTask?.cancel()
        repeatTask = nil
    }
}

/// Attaches the touch gesture: exclusive on the remote, simultaneous inside a ScrollView.
private struct TouchGestureModifier<G: Gesture>: ViewModifier {
    let gesture: G
    let simultaneous: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
        if simultaneous {
            content.simultaneousGesture(gesture)
        } else {
            content.gesture(gesture)
        }
    }
}

/// A plain button with the raised look, for buttons inside a ScrollView: it fires on release,
/// and a scroll cancels it. The pressed state comes from the button style.
struct RaisedButtonStyle: ButtonStyle {
    var shape: AnyInsettableShape = AnyInsettableShape(Circle())

    func makeBody(configuration: Configuration) -> some View {
        RaisedButtonBody(configuration: configuration, shape: shape)
    }
}

private struct RaisedButtonBody: View {
    let configuration: ButtonStyleConfiguration
    let shape: AnyInsettableShape
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        configuration.label
            .background(RaisedSurface(shape: shape, pressed: configuration.isPressed))
            .scaleEffect(configuration.isPressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}

/// A type-erased insettable shape.
struct AnyInsettableShape: InsettableShape {
    private let pathBuilder: (CGRect) -> Path
    private let insetBuilder: (CGFloat) -> AnyInsettableShape

    init<S: InsettableShape>(_ shape: S) {
        pathBuilder = { shape.path(in: $0) }
        insetBuilder = { AnyInsettableShape(shape.inset(by: $0)) }
    }

    func path(in rect: CGRect) -> Path { pathBuilder(rect) }

    func inset(by amount: CGFloat) -> AnyInsettableShape { insetBuilder(amount) }
}

// MARK: - Raised surfaces

/// The radial highlight on a raised button. It follows the tilt of the phone
/// (at most 30% of the radius from the center), or stays at the top left with Reduce Motion.
struct SheenLayer<S: Shape>: View {
    let shape: S
    /// Nil: the themed sheen (white 8% dark, 45% light).
    var intensity: Double?
    @Environment(\.tiltModel) private var tilt

    var body: some View {
        SheenContent(shape: shape, intensity: intensity, tilt: tilt)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

private struct SheenContent<S: Shape>: View {
    let shape: S
    let intensity: Double?
    @ObservedObject var tilt: TiltModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height)
            let point = reduceMotion ? CGPoint(x: -1, y: -1) : tilt.point
            // 30% of the radius is 0.15 in unit coordinates.
            let center = UnitPoint(x: 0.5 + point.x * 0.15, y: 0.5 + point.y * 0.15)
            shape.fill(
                RadialGradient(
                    colors: [intensity.map { Color.white.opacity($0) } ?? Theme.sheen, Color.white.opacity(0)],
                    center: center,
                    startRadius: 0,
                    endRadius: side * 0.6
                )
            )
        }
    }
}

/// The ring of light outside a button while it is pressed: in on `pop`, out on `fadeOut`.
struct PressGlow<S: Shape>: View {
    let shape: S
    let color: Color
    let lit: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        shape
            .stroke(color.opacity(0.85), lineWidth: 6)
            .blur(radius: 8)
            .scaleEffect(lit || reduceMotion ? 1 : 0.96)
            .opacity(lit ? 1 : 0)
            .animation(lit ? Motion.pop : Motion.fadeOut, value: lit)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// One moment of a press flash (docs/UI_SPEC.md, "Press glow"). `light` is the opacity, from 0 to 1.
/// `spread` says how far the flash reaches past the edge, from 0 (3 pt) to 1 (12 pt).
struct PressFlashValue: Equatable {
    var light: Double
    var spread: Double

    static let none = PressFlashValue(light: 0, spread: 0)

    /// 0.52 s, or 0.3 s under Reduce Motion.
    static func duration(reduceMotion: Bool) -> Double { reduceMotion ? 0.3 : 0.52 }

    /// The flash at `t` (0 to 1) of its run: up to full light in the first 15%, then a slow fade out.
    /// Without `expands` (Reduce Motion) it does not grow.
    static func at(_ t: Double, expands: Bool) -> PressFlashValue {
        guard t > 0, t < 1 else { return .none }
        let light = t < 0.15 ? t / 0.15 : pow(1 - (t - 0.15) / 0.85, 2)
        let spread = expands ? 1 - (1 - t) * (1 - t) : 0
        return PressFlashValue(light: light, spread: spread)
    }

    /// How far past the edge the flash reaches now, in points.
    var grow: CGFloat { 3 + 9 * spread }
}

/// Plays one press flash each time `pressed` turns true and gives each frame to `content`.
/// The flash plays to its end, also after a short tap. The timeline runs only during a flash.
struct FlashTimeline<Content: View>: View {
    let pressed: Bool
    @ViewBuilder let content: (PressFlashValue) -> Content
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var start: Date?
    @State private var run = 0

    var body: some View {
        let duration = PressFlashValue.duration(reduceMotion: reduceMotion)
        TimelineView(.animation(minimumInterval: nil, paused: start == nil)) { context in
            let t = start.map { context.date.timeIntervalSince($0) / duration } ?? 1
            content(PressFlashValue.at(t, expands: !reduceMotion))
        }
        .onChange(of: pressed) { isPressed in
            guard isPressed else { return }
            start = Date()
            run += 1
            let current = run
            DispatchQueue.main.asyncAfter(deadline: .now() + duration + 0.05) {
                if run == current { start = nil }
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The momentary glow around a pressed button: a soft halo and a thin bright edge that grow
/// outward and fade (docs/UI_SPEC.md, "Press glow").
struct PressFlash<S: Shape>: View {
    let shape: S
    let color: Color
    let pressed: Bool

    var body: some View {
        FlashTimeline(pressed: pressed) { flash in
            ZStack {
                // Blur 10 pt; SwiftUI's radius is about half of the blur size.
                shape.fill(color.opacity(0.65 * flash.light)).blur(radius: 5)
                shape.stroke(color.opacity(0.5 * flash.light), lineWidth: 1.5)
            }
            .padding(-flash.grow)
            .opacity(flash.light > 0 ? 1 : 0)
        }
    }
}

/// The press flash of one D-pad arrow: a band of light outside the rim on that side.
/// `angle` 0 is the right side, clockwise. `diameter` is the ring diameter.
struct ArcFlash: View {
    let pressed: Bool
    let angle: Double
    let diameter: CGFloat
    let color: Color

    var body: some View {
        FlashTimeline(pressed: pressed) { flash in
            // The band reaches from 2 pt inside the rim to `grow` outside it.
            let width = flash.grow + 2
            Circle()
                .trim(from: 0.04, to: 0.21)
                .stroke(color.opacity(0.7 * flash.light), style: StrokeStyle(lineWidth: width, lineCap: .round))
                .blur(radius: 5)
                .frame(width: diameter + flash.grow - 2, height: diameter + flash.grow - 2)
                .rotationEffect(.degrees(angle - 45))
                .opacity(flash.light > 0 ? 1 : 0)
        }
    }
}

/// A raised body: drop shadow, gradient that flips while pressed, top rim,
/// tilt sheen, the press glow and the press flash. See docs/UI_SPEC.md, "Raised buttons".
struct RaisedSurface<S: InsettableShape>: View {
    let shape: S
    var pressed: Bool
    /// Nil: the accent.
    var glow: Color? = nil
    /// Lights the glow without the pressed body (for the volume pill). Nil: follows `pressed`.
    var glowing: Bool?
    @Environment(\.accent) private var accent

    var body: some View {
        ZStack {
            shape.fill(
                LinearGradient(
                    colors: pressed
                        ? [Theme.bodyPressedTop, Theme.bodyPressedBottom]
                        : [Theme.bodyTop, Theme.bodyBottom],
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            SheenLayer(shape: shape)
            shape.strokeBorder(
                LinearGradient(
                    colors: [Theme.rim, Color.white.opacity(0)],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                lineWidth: 1
            )
        }
        .shadow(color: Theme.shadow, radius: pressed ? 3 : 7, x: 0, y: pressed ? 2 : 6)
        .animation(Motion.press, value: pressed)
        .background(PressGlow(shape: shape, color: glow ?? accent.accent, lit: glowing ?? pressed))
        .background(PressFlash(shape: shape, color: glow ?? accent.accent, pressed: glowing ?? pressed))
        .accessibilityHidden(true)
    }
}

/// The OK button body: accent gradient, rim, sheen, and a halo that grows while pressed.
struct AccentSurface<S: InsettableShape>: View {
    let shape: S
    var pressed: Bool
    @Environment(\.accent) private var accent

    var body: some View {
        ZStack {
            shape.fill(LinearGradient(colors: [accent.okTop, accent.okBottom], startPoint: .top, endPoint: .bottom))
            SheenLayer(shape: shape, intensity: 0.18)
            shape.strokeBorder(
                LinearGradient(
                    colors: [Color.white.opacity(0.3), Color.white.opacity(0)],
                    startPoint: .top,
                    endPoint: .bottom
                ),
                lineWidth: 1
            )
        }
        .shadow(color: accent.accent.opacity(pressed ? 0.55 : 0.25), radius: 12)
        .animation(pressed ? Motion.pop : Motion.fadeOut, value: pressed)
        .background(PressFlash(shape: shape, color: accent.accent, pressed: pressed))
        .accessibilityHidden(true)
    }
}

// MARK: - Buttons

/// A round raised remote button with an SF Symbol.
@MainActor
struct CircleButton: View {
    let systemImage: String
    let label: String
    var tint: Color = Theme.onSurface
    /// Nil: the accent.
    var glow: Color? = nil
    var size: CGFloat = Theme.buttonSize
    var repeats = false
    var inScrollView = false
    let action: () -> Void

    var body: some View {
        HoldButton(label, repeats: repeats, scales: true, inScrollView: inScrollView, action: action) { pressed in
            Image(systemName: systemImage)
                .font(.system(size: Theme.iconSize * size / Theme.buttonSize, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: size, height: size)
                .background(RaisedSurface(shape: Circle(), pressed: pressed, glow: glow))
        }
    }
}

/// The power button. Like the power key of an appliance, it shows the state of the box:
/// on, a green symbol with a green halo and a lit LED dot; off (standby), a red symbol with a red LED dot;
/// unknown (nil), a red symbol with no LED. The change cross-fades in 300 ms.
struct PowerButton: View {
    let isOn: Bool?
    let action: () -> Void

    private var color: Color { isOn == true ? Theme.ok : Theme.power }
    private var ledLight: Double {
        switch isOn {
        case true?: return 1
        case false?: return 0.75
        case nil: return 0
        }
    }

    var body: some View {
        HoldButton(RemoteKey.power.title, scales: true, action: action) { pressed in
            ZStack {
                Image(systemName: "power")
                    .font(.system(size: Theme.iconSize, weight: .semibold))
                    .foregroundColor(color.opacity(isOn == false ? 0.8 : 1))
                // The LED: a 5 pt dot under the symbol with a soft light around it.
                Circle()
                    .fill(color)
                    .frame(width: 5, height: 5)
                    .shadow(color: color.opacity(0.9), radius: 4)
                    .opacity(ledLight)
                    .offset(y: Theme.buttonSize * 0.32)
            }
            .frame(width: Theme.buttonSize, height: Theme.buttonSize)
            .background(RaisedSurface(shape: Circle(), pressed: pressed, glow: color))
            .background(
                Circle()
                    .fill(color)
                    .blur(radius: 12)
                    .opacity(isOn == true ? 0.34 : 0)
                    .accessibilityHidden(true)
            )
            .animation(.easeOut(duration: 0.3), value: isOn)
        }
        .accessibilityValue(isOn.map { L10n.t($0 ? "power.on" : "power.off") } ?? "")
    }
}

/// The D-pad ring with OK in the center. A press on an arrow lights an arc
/// of the ring's inner edge on that side. The ring itself does not move.
@MainActor
struct DPadView: View {
    let onKey: (RemoteKey) -> Void
    @State private var pressedArrow: RemoteKey?
    @Environment(\.accent) private var accent

    var body: some View {
        ZStack {
            DPadGlow()
            RaisedSurface(shape: Circle(), pressed: false, glowing: false)
                .frame(width: Theme.dpadSize, height: Theme.dpadSize)
            // The thin accent rim on the outer edge of the ring (docs/UI_SPEC.md, "D-pad glow").
            Circle()
                .strokeBorder(accent.accent.opacity(0.55), lineWidth: 1.5)
                .frame(width: Theme.dpadSize, height: Theme.dpadSize)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            arc(.right, angle: 0)
            arc(.down, angle: 90)
            arc(.left, angle: 180)
            arc(.up, angle: 270)
            ArcFlash(pressed: pressedArrow == .right, angle: 0, diameter: Theme.dpadSize, color: accent.accent)
            ArcFlash(pressed: pressedArrow == .down, angle: 90, diameter: Theme.dpadSize, color: accent.accent)
            ArcFlash(pressed: pressedArrow == .left, angle: 180, diameter: Theme.dpadSize, color: accent.accent)
            ArcFlash(pressed: pressedArrow == .up, angle: 270, diameter: Theme.dpadSize, color: accent.accent)
            arrow(.up, dx: 0, dy: -1)
            arrow(.down, dx: 0, dy: 1)
            arrow(.left, dx: -1, dy: 0)
            arrow(.right, dx: 1, dy: 0)
            HoldButton(RemoteKey.center.title, scales: true, action: { onKey(.center) }) { pressed in
                Text(L10n.t("remote.ok"))
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(accent.onAccent)
                    .frame(width: Theme.okSize, height: Theme.okSize)
                    .background(AccentSurface(shape: Circle(), pressed: pressed))
            }
        }
        .frame(width: Theme.dpadSize, height: Theme.dpadSize)
    }

    /// The arc light on one side. Angle 0 is the right side, clockwise.
    private func arc(_ key: RemoteKey, angle: Double) -> some View {
        let lit = pressedArrow == key
        let diameter = Theme.dpadSize - 14
        return ZStack {
            Circle()
                .trim(from: 0, to: 0.25)
                .stroke(accent.accent, style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .blur(radius: 7)
            Circle()
                .trim(from: 0.02, to: 0.23)
                .stroke(accent.accent.opacity(0.9), style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        }
        .frame(width: diameter, height: diameter)
        .rotationEffect(.degrees(angle - 45))
        .opacity(lit ? 1 : 0)
        .animation(lit ? Motion.pop : Motion.fadeOut, value: lit)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func arrow(_ key: RemoteKey, dx: CGFloat, dy: CGFloat) -> some View {
        HoldButton(
            key.title,
            repeats: true,
            onPressedChange: { isPressed in
                if isPressed {
                    pressedArrow = key
                } else if pressedArrow == key {
                    pressedArrow = nil
                }
            },
            action: { onKey(key) }
        ) { pressed in
            Image(systemName: key.symbol)
                .font(.system(size: Theme.iconSize, weight: .bold))
                .foregroundColor(pressed ? accent.accent : Theme.onSurface)
                .animation(Motion.press, value: pressed)
                .frame(width: 76, height: 76)
        }
        .offset(x: dx * 92, y: dy * 92)
    }
}

/// The soft light that breathes around the D-pad ring, in the accent color (docs/UI_SPEC.md,
/// "D-pad glow"): a blurred halo whose opacity goes from 18% to 42% and back in 5 s.
/// With the RGB accent it follows the hue. Reduce Motion: a steady 30%.
private struct DPadGlow: View {
    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bright = false

    var body: some View {
        let opacity = reduceMotion ? 0.30 : (bright ? 0.42 : 0.18)
        Circle()
            .stroke(accent.accent, lineWidth: 10)
            .frame(width: Theme.dpadSize + 4, height: Theme.dpadSize + 4)
            // Blur 22 pt; SwiftUI's radius is about half of the blur size, as for the shadows.
            .blur(radius: 11)
            .opacity(opacity)
            // Half a cycle each way, with a sine-like ease, so one full breath takes 5 s.
            .animation(reduceMotion ? nil : .easeInOut(duration: 2.5).repeatForever(autoreverses: true), value: bright)
            .onAppear { bright = !reduceMotion }
            .onChange(of: reduceMotion) { reduce in bright = !reduce }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// The wide volume bar: volume down, mute, volume up. The segments do not scale:
/// a pressed segment darkens, and the whole pill glows.
@MainActor
struct VolumePill: View {
    let volume: VolumeInfo?
    let onKey: (RemoteKey) -> Void
    @State private var pressedKeys: Set<RemoteKey> = []

    var body: some View {
        HStack(spacing: 0) {
            segment(.volumeDown, repeats: true) {
                Image(systemName: RemoteKey.volumeDown.symbol)
                    .font(.system(size: Theme.iconSize, weight: .bold))
            }
            segment(.mute, repeats: false) {
                VStack(spacing: 2) {
                    Image(systemName: volume?.muted == true ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 24, weight: .semibold))
                    if let volume, volume.max > 0 {
                        Text("\(volume.level)")
                            .font(.caption2.monospacedDigit())
                            .foregroundColor(Theme.muted)
                    }
                }
            }
            segment(.volumeUp, repeats: true) {
                Image(systemName: RemoteKey.volumeUp.symbol)
                    .font(.system(size: Theme.iconSize, weight: .bold))
            }
        }
        .frame(width: 296, height: Theme.buttonSize)
        .background(RaisedSurface(shape: Capsule(), pressed: false, glowing: !pressedKeys.isEmpty))
    }

    private func segment<Label: View>(
        _ key: RemoteKey,
        repeats: Bool,
        @ViewBuilder label: @escaping () -> Label
    ) -> some View {
        let title = key == .mute && volume?.muted == true ? L10n.t("remote.unmute") : key.title
        return HoldButton(
            title,
            repeats: repeats,
            onPressedChange: { isPressed in
                if isPressed { pressedKeys.insert(key) } else { pressedKeys.remove(key) }
            },
            action: { onKey(key) }
        ) { pressed in
            label()
                .foregroundColor(Theme.onSurface)
                .frame(width: key == .mute ? 88 : 104, height: Theme.buttonSize)
                .background(
                    Capsule()
                        .fill(pressed ? Theme.pressShade : Color.clear)
                        .padding(5)
                        .animation(Motion.press, value: pressed)
                )
        }
    }
}

// MARK: - Background texture

/// The remote screen background: vertical gradient, a soft accent light at the top center, the texture of the
/// Background preference (none, pebble leather or satin), and a fixed grain. See docs/UI_SPEC.md, "Background texture".
struct RemoteBackground: View {
    /// A fixed texture (the Background picker). Nil uses the preference.
    var texture: BackgroundTexture?

    @Environment(\.displayScale) private var displayScale
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accent) private var accent
    @Environment(\.backgroundTexture) private var preferredTexture

    init(texture: BackgroundTexture? = nil) {
        self.texture = texture
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LinearGradient(
                    colors: [Theme.backgroundTop, Theme.backgroundBottom],
                    startPoint: .top,
                    endPoint: .bottom
                )
                RadialGradient(
                    colors: [accent.accent.opacity(0.07), accent.accent.opacity(0)],
                    center: .top,
                    startRadius: 0,
                    endRadius: geometry.size.width * 0.7
                )
                switch texture ?? preferredTexture {
                case .gradient:
                    EmptyView()
                case .leather:
                    // The leather tile (Assets: LeatherGrain, made by tools/make_leather.py) is 128 pt.
                    Image("LeatherGrain")
                        .resizable(resizingMode: .tile)
                        .opacity(colorScheme == .light ? 0.55 : 1)
                case .satin:
                    // The satin image (Assets: SatinFolds, made by tools/make_satin.py) is not a tile: it fills the
                    // screen, and the rest is cropped.
                    Image("SatinFolds")
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
                        .opacity(colorScheme == .light ? 0.7 : 1)
                }
                Image(uiImage: GrainTexture.image(scale: displayScale, light: colorScheme == .light))
                    .resizable(resizingMode: .tile)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// A 128 x 128 px noise tile, made once per screen scale and theme. Dark theme: each pixel is
/// white or black at 0 to 4% opacity. Light theme: black only, at 0 to 2%. A fixed seed keeps
/// the grain the same on every start. Call it from the main thread (SwiftUI view bodies).
enum GrainTexture {
    static let side = 128
    private struct CacheKey: Hashable {
        let scale: CGFloat
        let light: Bool
    }
    private static var cache: [CacheKey: UIImage] = [:]

    static func image(scale: CGFloat, light: Bool = false) -> UIImage {
        let key = CacheKey(scale: scale, light: light)
        if let cached = cache[key] { return cached }
        let image = make(scale: scale, light: light)
        cache[key] = image
        return image
    }

    /// Premultiplied RGBA bytes of the tile.
    static func pixels(seed: UInt64 = 0x5EED_7AB1E, light: Bool = false) -> [UInt8] {
        var generator = SplitMix64(state: seed)
        var bytes = [UInt8](repeating: 0, count: side * side * 4)
        let maxOpacity = light ? 0.02 : 0.04
        for index in 0..<(side * side) {
            let random = generator.next()
            let alpha = UInt8((Double(random & 0xFF) / 255 * maxOpacity * 255).rounded())
            let white = !light && (random >> 8) & 1 == 1
            let color: UInt8 = white ? alpha : 0
            bytes[index * 4] = color
            bytes[index * 4 + 1] = color
            bytes[index * 4 + 2] = color
            bytes[index * 4 + 3] = alpha
        }
        return bytes
    }

    private static func make(scale: CGFloat, light: Bool) -> UIImage {
        let bytes = pixels(light: light)
        guard let provider = CGDataProvider(data: Data(bytes) as CFData),
              let cgImage = CGImage(
                  width: side,
                  height: side,
                  bitsPerComponent: 8,
                  bitsPerPixel: 32,
                  bytesPerRow: side * 4,
                  space: CGColorSpaceCreateDeviceRGB(),
                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                  provider: provider,
                  decode: nil,
                  shouldInterpolate: false,
                  intent: .defaultIntent
              )
        else { return UIImage() }
        return UIImage(cgImage: cgImage, scale: max(scale, 1), orientation: .up)
    }
}

/// A small, fast random generator with a fixed seed.
struct SplitMix64 {
    var state: UInt64

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

// MARK: - Signal line

/// A thin vertical line above the D-pad. Each command that goes to the box sends a pulse of
/// light up the line. With Reduce Motion, the whole line fades in and out instead.
/// See docs/UI_SPEC.md, "Signal line". Decorative: hidden from VoiceOver.
@MainActor
struct SignalLine: View {
    let signals: PassthroughSubject<Void, Never>
    /// The line height. The remote screen fits it into the free height above the D-pad.
    let height: CGFloat

    init(signals: PassthroughSubject<Void, Never>, height: CGFloat = SignalLine.maxHeight) {
        self.signals = signals
        self.height = height
    }

    static let width: CGFloat = 4
    static let maxHeight: CGFloat = 64
    static let minHeight: CGFloat = 24
    /// Room for the soft glow left and right of the line. The frame is this much wider on each side.
    static let glowRoom: CGFloat = 6
    static let maxPulses = 3

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accent) private var accent
    @State private var pulses: [Pulse] = []
    @State private var trackLit = false
    @State private var glowTask: Task<Void, Never>?

    struct Pulse: Identifiable, Equatable {
        let id = UUID()
        var risen = false
    }

    var body: some View {
        let height = self.height
        ZStack {
            // Soft glow of the whole track and of the pulses, outside the line.
            ZStack {
                Capsule()
                    .fill(accent.accent)
                    .frame(width: Self.width + 4)
                    .opacity(trackLit ? (reduceMotion ? 0.6 : 0.35) : 0)
                ForEach(pulses) { pulse in
                    pulseGradient
                        .frame(width: Self.width + 6, height: height * 0.4)
                        .offset(y: pulse.risen ? -height * 0.7 : height * 0.7)
                }
            }
            .frame(width: Self.width + Self.glowRoom * 2, height: height)
            .blur(radius: 5)
            .clipped()

            // The line itself.
            ZStack {
                Capsule().fill(Theme.track)
                Capsule()
                    .fill(accent.accent)
                    .opacity(trackLit ? (reduceMotion ? 0.9 : 0.25) : 0)
                ForEach(pulses) { pulse in
                    pulseGradient
                        .frame(height: height * 0.4)
                        .offset(y: pulse.risen ? -height * 0.7 : height * 0.7)
                }
            }
            .frame(width: Self.width, height: height)
            .clipShape(Capsule())
        }
        .frame(width: Self.width + Self.glowRoom * 2, height: height)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onReceive(signals) { _ in fire() }
    }

    /// The pulse moves up, so its top end is white and the accent fades out below it.
    private var pulseGradient: LinearGradient {
        LinearGradient(
            colors: [Color.white.opacity(0.95), accent.accent, accent.accent.opacity(0)],
            startPoint: .top,
            endPoint: .bottom
        )
    }

    private func fire() {
        if reduceMotion {
            // No movement: the line fades in and out in 300 ms.
            withAnimation(.easeOut(duration: 0.15)) { trackLit = true }
            scheduleTrackOff(after: 150_000_000, animation: .easeOut(duration: 0.15))
            return
        }
        withAnimation(Motion.pop) { trackLit = true }
        scheduleTrackOff(after: 180_000_000, animation: Motion.fadeOut)

        // At most three pulses at the same time. A command past that only lights the track.
        guard pulses.count < Self.maxPulses else { return }
        let pulse = Pulse()
        pulses.append(pulse)
        let id = pulse.id
        // Start the movement on the next run loop pass, after the pulse is on screen.
        DispatchQueue.main.async {
            withAnimation(Motion.pulse) {
                if let index = pulses.firstIndex(where: { $0.id == id }) {
                    pulses[index].risen = true
                }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Motion.pulseSeconds + 0.05) {
            pulses.removeAll { $0.id == id }
        }
    }

    private func scheduleTrackOff(after nanos: UInt64, animation: Animation) {
        glowTask?.cancel()
        glowTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: nanos)
            guard !Task.isCancelled else { return }
            withAnimation(animation) { trackLit = false }
        }
    }
}

// MARK: - Text

/// A section of plain text for Help and About.
@MainActor
struct TextSection: View {
    let title: String
    let paragraphs: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
                .foregroundColor(Theme.onSurface)
            ForEach(paragraphs, id: \.self) { paragraph in
                Text(paragraph)
                    .font(.body)
                    .foregroundColor(Theme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
