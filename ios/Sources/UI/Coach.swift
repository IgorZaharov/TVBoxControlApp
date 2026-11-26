import SwiftUI

// The coach overlay of the tours (docs/UI_SPEC.md, "First-run tutorial"): the screen dims, a spotlight keeps the
// target bright and tappable, a hand-drawn arrow draws itself toward it, and a card with a glowing accent outline
// rises in beside it. The same rules and values as on Android (Coach.kt).

/// The places of the controls that a coach card can point at.
struct CoachTargetKey: PreferenceKey {
    static var defaultValue: [String: Anchor<CGRect>] = [:]
    static func reduce(value: inout [String: Anchor<CGRect>], nextValue: () -> [String: Anchor<CGRect>]) {
        value.merge(nextValue()) { $1 }
    }
}

private struct CoachCardSizeKey: PreferenceKey {
    static var defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) { value = nextValue() }
}

extension View {
    /// Marks this control as the coach target `id`. Several controls named "id/…" form one target.
    func coachTarget(_ id: String) -> some View {
        anchorPreference(key: CoachTargetKey.self, value: .bounds) { [id: $0] }
    }

    /// Shows the coach card of `step` over this view. The targets are the ones marked inside this view.
    func coachOverlay<Card: View>(step: TourStep?, @ViewBuilder card: @escaping (TourStep) -> Card) -> some View {
        overlayPreferenceValue(CoachTargetKey.self) { anchors in
            GeometryReader { proxy in
                CoachHost(step: step, anchors: anchors, proxy: proxy, card: card)
            }
            .ignoresSafeArea()
        }
    }

    /// The card of a coach step: the surface under a blur, a 1.5 pt accent outline and a soft accent glow.
    func coachCard(cornerRadius: CGFloat = 20) -> some View {
        modifier(CoachCardStyle(cornerRadius: cornerRadius))
    }
}

/// Finds the target of `step` and shows its overlay. A target that is not laid out yet shows nothing.
private struct CoachHost<Card: View>: View {
    let step: TourStep?
    let anchors: [String: Anchor<CGRect>]
    let proxy: GeometryProxy
    let card: (TourStep) -> Card

    var body: some View {
        if let step {
            if let id = step.target {
                if let rect = Self.rect(for: id, anchors: anchors, proxy: proxy) {
                    CoachOverlay(target: rect, stepKey: step, size: proxy.size, safe: proxy.safeAreaInsets) { card(step) }
                }
            } else {
                CoachOverlay(target: nil, stepKey: step, size: proxy.size, safe: proxy.safeAreaInsets) { card(step) }
            }
        }
    }

    static func rect(for id: String, anchors: [String: Anchor<CGRect>], proxy: GeometryProxy) -> CGRect? {
        var result: CGRect?
        for (key, anchor) in anchors where key == id || key.hasPrefix(id + "/") {
            let r = proxy[anchor]
            result = result.map { $0.union(r) } ?? r
        }
        return result
    }
}

/// The dim with a rounded hole. Filled and hit-tested even-odd, so a tap in the hole reaches the control under it.
private struct ScrimShape: Shape {
    var hole: CGRect?
    var radius: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path(rect)
        if let hole {
            path.addRoundedRect(in: hole, cornerSize: CGSize(width: radius, height: radius))
        }
        return path
    }
}

struct CoachOverlay<Card: View>: View {
    let target: CGRect?
    let stepKey: TourStep
    let size: CGSize
    let safe: EdgeInsets
    @ViewBuilder let card: () -> Card

    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var scrim = 0.0
    @State private var line: CGFloat = 0
    @State private var sketch: CGFloat = 0
    @State private var head: CGFloat = 0
    @State private var cardIn = false
    @State private var pulse = false
    @State private var nudge = false
    @State private var cardHeight: CGFloat = 160

    /// The dim color: black at 66 %.
    private static var scrimColor: Color { Color(red: 4 / 255, green: 5 / 255, blue: 8 / 255) }

    var body: some View {
        let hole = target?.insetBy(dx: -8, dy: -8)
        let radius = hole.map { min($0.height / 2, 22) } ?? 0
        let width = min(size.width - 32, 340)
        let placed = CoachLayout.place(
            target: hole, screen: size, card: CGSize(width: width, height: cardHeight),
            edge: 16, gap: 64, safeTop: safe.top, safeBottom: safe.bottom
        )
        let below = hole.map { placed.card.minY >= $0.maxY } ?? true

        ZStack(alignment: .topLeading) {
            ScrimShape(hole: hole, radius: radius)
                .fill(Self.scrimColor.opacity(0.66 * scrim), style: FillStyle(eoFill: true))
                .contentShape(ScrimShape(hole: hole, radius: radius), eoFill: true)
                // Taps outside the spotlight do nothing during a step.
                .onTapGesture {}
                .accessibilityHidden(true)

            if let hole {
                RoundedRectangle(cornerRadius: radius)
                    .stroke(accent.accent.opacity(0.85 * scrim), lineWidth: 1.5)
                    .shadow(color: accent.accent.opacity(0.6 * scrim), radius: 8)
                    .frame(width: hole.width, height: hole.height)
                    .position(x: hole.midX, y: hole.midY)
                    .allowsHitTesting(false)
                if !reduceMotion {
                    RoundedRectangle(cornerRadius: radius + 6)
                        .stroke(accent.accent, lineWidth: 2)
                        .frame(width: hole.width + 12, height: hole.height + 12)
                        .scaleEffect(pulse ? 1.08 : 1)
                        .opacity(pulse ? 0 : 0.8 * scrim)
                        .position(x: hole.midX, y: hole.midY)
                        .allowsHitTesting(false)
                }
            }

            if let arrow = placed.arrow {
                CoachArrowView(arrow: arrow, line: line, sketch: sketch, head: head, color: accent.accent)
                    .offset(nudgeOffset(arrow))
                    .allowsHitTesting(false)
            }

            VStack(alignment: .leading, spacing: 6) { card() }
                .padding(EdgeInsets(top: 16, leading: 18, bottom: 6, trailing: 10))
                .frame(width: width, alignment: .leading)
                .coachCard()
                .background(GeometryReader { g in Color.clear.preference(key: CoachCardSizeKey.self, value: g.size) })
                .onPreferenceChange(CoachCardSizeKey.self) { if $0.height > 0 { cardHeight = $0.height } }
                .opacity(cardIn ? 1 : 0)
                .offset(x: placed.card.minX, y: placed.card.minY + (cardIn || reduceMotion ? 0 : (below ? 12 : -12)))
                .accessibilityElement(children: .contain)
                .accessibilityAddTraits(.isModal)
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
        .onAppear {
            withAnimation(.timingCurve(0.215, 0.61, 0.355, 1, duration: reduceMotion ? 0.2 : 0.4)) { scrim = 1 }
            start()
        }
        .onChange(of: stepKey) { _ in start() }
    }

    /// The arrow nudges toward its target, 4 pt, every 2.4 s.
    private func nudgeOffset(_ arrow: CoachArrow) -> CGSize {
        guard nudge, !reduceMotion else { return .zero }
        let dx = arrow.to.x - arrow.c2.x
        let dy = arrow.to.y - arrow.c2.y
        let length = max(hypot(dx, dy), 1)
        return CGSize(width: dx / length * 4, height: dy / length * 4)
    }

    /// The step comes in: the arrow draws itself (line, sketch, head), then the card rises in on a spring.
    private func start() {
        cardIn = false
        pulse = false
        nudge = false
        if reduceMotion {
            line = 1
            sketch = 1
            head = 1
            withAnimation(.easeOut(duration: 0.2)) { cardIn = true }
            return
        }
        line = 0
        sketch = 0
        head = 0
        let draw = { (duration: Double, delay: Double) in Animation.timingCurve(0, 0, 0.58, 1, duration: duration).delay(delay) }
        withAnimation(draw(0.6, 0.2)) { line = 1 }
        withAnimation(draw(0.6, 0.32)) { sketch = 1 }
        withAnimation(draw(0.18, 0.78)) { head = 1 }
        withAnimation(.spring(response: 0.35, dampingFraction: 0.72).delay(0.55)) { cardIn = true }
        withAnimation(.easeOut(duration: 1.8).repeatForever(autoreverses: false).delay(1.1)) { pulse = true }
        withAnimation(.timingCurve(0.37, 0, 0.63, 1, duration: 1.2).repeatForever(autoreverses: true).delay(1.4)) { nudge = true }
    }
}

/// The hand-drawn arrow: the line, a fainter second line beside it, then the head. A wide faint stroke is the glow.
private struct CoachArrowView: View {
    let arrow: CoachArrow
    let line: CGFloat
    let sketch: CGFloat
    let head: CGFloat
    let color: Color

    var body: some View {
        ZStack {
            CurveShape(arrow: arrow)
                .trim(from: 0, to: line)
                .stroke(color.opacity(0.3), style: StrokeStyle(lineWidth: 9, lineCap: .round))
            CurveShape(arrow: arrow)
                .trim(from: 0, to: line)
                .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            CurveShape(arrow: arrow)
                .trim(from: 0, to: sketch)
                .stroke(color.opacity(0.4), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                .offset(x: 2.7, y: -2.7)
            HeadShape(arrow: arrow)
                .trim(from: 0, to: head)
                .stroke(color, style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
    }
}

private struct CurveShape: Shape {
    let arrow: CoachArrow
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: arrow.from)
        path.addCurve(to: arrow.to, control1: arrow.c1, control2: arrow.c2)
        return path
    }
}

/// The head follows the tangent at the end of the curve: two 12 pt wings at 28 degrees.
private struct HeadShape: Shape {
    let arrow: CoachArrow
    func path(in rect: CGRect) -> Path {
        let tx = arrow.to.x - arrow.c2.x
        let ty = arrow.to.y - arrow.c2.y
        let tl = max(hypot(tx, ty), 1)
        let bx = -tx / tl
        let by = -ty / tl
        let angle = 28.0 * .pi / 180
        func wing(_ sign: CGFloat) -> CGPoint {
            CGPoint(
                x: arrow.to.x + 12 * (bx * cos(angle) - by * sin(angle) * sign),
                y: arrow.to.y + 12 * (bx * sin(angle) * sign + by * cos(angle))
            )
        }
        var path = Path()
        path.move(to: wing(1))
        path.addLine(to: arrow.to)
        path.addLine(to: wing(-1))
        return path
    }
}

private struct CoachCardStyle: ViewModifier {
    let cornerRadius: CGFloat
    @Environment(\.accent) private var accent

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        return content
            .background(
                ZStack {
                    shape.fill(Color.black.opacity(0.4)).blur(radius: 18).offset(y: 10)
                    shape.stroke(accent.accent.opacity(0.5), lineWidth: 4).blur(radius: 8)
                    shape.fill(.ultraThinMaterial)
                    shape.fill(Theme.surface.opacity(0.6))
                }
            )
            .overlay(shape.stroke(accent.accent.opacity(0.7), lineWidth: 1.5))
    }
}

/// The text of a coach card: a small step label, the title, the body, and one quiet action (Skip, Not now).
struct CoachText: View {
    let label: String
    let title: String
    let text: String
    let action: String?
    let onAction: () -> Void
    @Environment(\.accent) private var accent

    var body: some View {
        Text(label.uppercased())
            .font(.system(size: 12, weight: .semibold))
            .tracking(0.7)
            .foregroundColor(Theme.muted)
        Text(title)
            .font(.system(size: 17, weight: .semibold))
            .foregroundColor(Theme.onSurface)
            .fixedSize(horizontal: false, vertical: true)
        Text(text)
            .font(.system(size: 15))
            .lineSpacing(4)
            .foregroundColor(Theme.bodyText)
            .fixedSize(horizontal: false, vertical: true)
        if let action {
            HStack {
                Spacer()
                Button(action, action: onAction)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundColor(accent.accent)
                    .frame(minHeight: 44)
                    .padding(.horizontal, 8)
            }
        } else {
            Spacer().frame(height: 8)
        }
    }
}

/// The check of the last card: the circle pops in, then the check draws itself.
struct CoachCheck: View {
    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false
    @State private var drawn: CGFloat = 0

    var body: some View {
        ZStack {
            Circle().fill(accent.accent.opacity(0.15))
            Circle().stroke(accent.accent, lineWidth: 1.5)
            CheckShape()
                .trim(from: 0, to: drawn)
                .stroke(accent.accent, style: StrokeStyle(lineWidth: 3.4, lineCap: .round, lineJoin: .round))
                .frame(width: 34, height: 34)
        }
        .frame(width: 64, height: 64)
        .scaleEffect(shown || reduceMotion ? 1 : 0.5)
        .opacity(shown ? 1 : 0)
        .accessibilityHidden(true)
        .onAppear {
            if reduceMotion {
                drawn = 1
                withAnimation(.easeOut(duration: 0.2)) { shown = true }
            } else {
                withAnimation(.spring(response: 0.3, dampingFraction: 0.54)) { shown = true }
                withAnimation(.timingCurve(0, 0, 0.58, 1, duration: 0.3).delay(0.35)) { drawn = 1 }
            }
        }
    }
}

private struct CheckShape: Shape {
    func path(in rect: CGRect) -> Path {
        let s = rect.width / 24
        var path = Path()
        path.move(to: CGPoint(x: 5 * s, y: 12.5 * s))
        path.addLine(to: CGPoint(x: 10 * s, y: 17.5 * s))
        path.addLine(to: CGPoint(x: 19 * s, y: 7 * s))
        return path
    }
}

/// The card of each tour step.
struct TourCard: View {
    let step: TourStep
    @EnvironmentObject private var model: AppModel
    @Environment(\.accent) private var accent

    var body: some View {
        switch step {
        case .openDevices:
            CoachText(label: L10n.f("tour.step %d %d", 1, 3), title: L10n.t("tour.devices.title"),
                      text: L10n.t("tour.devices.text"), action: L10n.t("tour.skip")) { model.finishTour() }
        case .pickBox:
            CoachText(label: L10n.f("tour.step %d %d", 2, 3), title: L10n.t("tour.pick.title"),
                      text: L10n.t("tour.pick.text"), action: L10n.t("tour.skip")) { model.finishTour() }
        case .addByIp:
            CoachText(label: L10n.f("tour.step %d %d", 2, 3), title: L10n.t("tour.none.title"),
                      text: L10n.t("tour.none.text"), action: L10n.t("tour.skip")) { model.finishTour() }
        case .mouseChoose:
            CoachText(label: L10n.t("mouseTour.label"), title: L10n.t("mouseTour.choose.title"),
                      text: L10n.t("mouseTour.choose.text"), action: L10n.t("tour.notNow")) { model.finishMouseTour() }
        case .allSet:
            VStack(spacing: 8) {
                CoachCheck()
                Text(L10n.t("tour.done.title"))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(Theme.onSurface)
                    .padding(.top, 8)
                Text(L10n.f("tour.done.text %@", model.currentBox?.name ?? ""))
                    .font(.system(size: 15))
                    .lineSpacing(4)
                    .multilineTextAlignment(.center)
                    .foregroundColor(Theme.bodyText)
                    .fixedSize(horizontal: false, vertical: true)
                Button { model.finishTour() } label: {
                    Text(L10n.t("action.done"))
                        .font(.system(size: 16, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(Capsule().fill(accent.accent))
                        .foregroundColor(accent.onAccent)
                }
                .padding(.top, 8)
            }
            .frame(maxWidth: .infinity)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
        }
    }
}
