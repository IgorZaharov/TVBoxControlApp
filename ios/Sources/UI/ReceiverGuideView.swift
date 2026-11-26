import SwiftUI
import UIKit

/// The phone serving receiver.apk on the local Wi-Fi: the state, the number of downloads, and a retry.
/// Used by the receiver install guide.
@MainActor
final class ReceiverHost: ObservableObject {
    enum State: Equatable {
        case loading, ready(String), noWifi, failed
    }

    @Published private(set) var state: State = .loading
    @Published private(set) var downloads = 0
    private var server: ReceiverShareServer?
    private var task: Task<Void, Never>?

    func start() {
        task?.cancel()
        task = Task { [weak self] in await self?.prepare() }
    }

    func stop() {
        task?.cancel()
        task = nil
        server?.stop()
        server = nil
    }

    private func prepare() async {
        state = .loading
        server?.stop()
        server = nil
        guard let ip = ReceiverShare.localAddress() else {
            state = .noWifi
            return
        }
        guard let data = try? await ReceiverShare.fetch(), !Task.isCancelled else {
            state = .failed
            return
        }
        let newServer = ReceiverShareServer(body: data)
        newServer.onDownloaded = { [weak self] in
            Task { @MainActor in self?.downloads += 1 }
        }
        guard let port = await newServer.start() else {
            state = .failed
            return
        }
        server = newServer
        state = .ready("\(ip):\(port)")
    }
}

/// The receiver install guide (docs/UI_SPEC.md, "Receiver install guide"): six cards with a drawing of the TV
/// screen and a hand-drawn arrow at the thing to select. Two steps move on by themselves: when the box downloads
/// the file, and when the phone finds the receiver. The same steps as on Android (ReceiverGuide.kt).
@MainActor
struct ReceiverGuideView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var host = ReceiverHost()
    @State private var step = 0
    @State private var forward = true
    @State private var idleTimerWasDisabled = false

    private static let steps = 6
    private static let firstAutoStep = 3
    private static let codeStep = 5

    var body: some View {
        ZStack {
            Color(red: 4 / 255, green: 5 / 255, blue: 8 / 255).opacity(0.66).ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text(L10n.f("guide.label %d %d", step + 1, Self.steps).uppercased())
                            .font(.system(size: 12, weight: .semibold))
                            .tracking(0.7)
                            .foregroundColor(Theme.muted)
                        Spacer()
                        Button { dismiss() } label: {
                            Image(systemName: "xmark")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundColor(Theme.muted)
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(L10n.t("guide.close"))
                    }
                    GuideStepView(step: step, host: host, mouseState: model.mouseState) { model.startTypeLink() }
                        .id(step)
                        .transition(stepTransition)
                    navigation
                }
                .padding(EdgeInsets(top: 6, leading: 18, bottom: 8, trailing: 8))
                .coachCard(cornerRadius: 24)
                .frame(maxWidth: 420)
                .padding(16)
            }
        }
        .modifier(ClearCoverBackground())
        .onAppear {
            host.start()
            idleTimerWasDisabled = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            host.stop()
            UIApplication.shared.isIdleTimerDisabled = idleTimerWasDisabled
        }
        // The box downloaded the file: on to Install.
        .onChange(of: host.downloads) { count in
            if count > 0 && step < Self.firstAutoStep { go(to: Self.firstAutoStep) }
        }
        // While the receiver gets installed and turned on, look for it every 3 seconds.
        .task(id: step) {
            while step >= Self.firstAutoStep && step < Self.codeStep && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                model.lookForReceiver()
            }
        }
        // The receiver answers: it asks for its code, or the mouse is ready.
        .onChange(of: model.mouseState) { state in
            switch state {
            case .needsPairing: go(to: Self.codeStep)
            case .ready: dismiss()
            default: break
            }
        }
    }

    private var stepTransition: AnyTransition {
        if reduceMotion { return .opacity }
        let shift: CGFloat = forward ? 24 : -24
        return .asymmetric(
            insertion: .offset(x: shift).combined(with: .opacity)
                .animation(.timingCurve(0, 0, 0.58, 1, duration: 0.3).delay(0.15)),
            removal: .offset(x: -shift).combined(with: .opacity)
                .animation(.timingCurve(0, 0, 0.58, 1, duration: 0.15))
        )
    }

    private func go(to newStep: Int) {
        forward = newStep > step
        withAnimation { step = newStep }
    }

    private var navigation: some View {
        HStack {
            Group {
                if step > 0 {
                    Button(L10n.t("guide.back")) { go(to: step - 1) }
                        .foregroundColor(accent.accent)
                        .frame(minHeight: 44)
                } else {
                    Color.clear.frame(height: 44)
                }
            }
            .frame(width: 80, alignment: .leading)
            HStack(spacing: 6) {
                ForEach(0..<Self.steps, id: \.self) { i in
                    Capsule()
                        .fill(i == step ? accent.accent : Theme.muted.opacity(0.4))
                        .frame(width: i == step ? 18 : 6, height: 6)
                        .animation(.spring(response: 0.25, dampingFraction: 1), value: step)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
            Group {
                if step < Self.codeStep {
                    Button { go(to: step + 1) } label: {
                        Text(L10n.t("guide.next"))
                            .font(.system(size: 15, weight: .semibold))
                            .padding(.horizontal, 18)
                            .frame(minHeight: 44)
                            .background(Capsule().fill(accent.accent))
                            .foregroundColor(accent.onAccent)
                    }
                } else {
                    Color.clear.frame(height: 44)
                }
            }
            .frame(width: 80, alignment: .trailing)
        }
        .padding(.trailing, 10)
    }
}

private struct GuideStepView: View {
    let step: Int
    @ObservedObject var host: ReceiverHost
    let mouseState: AppModel.MouseState
    let onTypeLink: () -> Void
    @Environment(\.accent) private var accent

    private static let titles = ["guide1", "guide2", "guide3", "guide4", "guide5", "guide6"]

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            TVDrawing(step: step, address: address)
            Text(L10n.t("\(Self.titles[step]).title"))
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(Theme.onSurface)
                .accessibilityAddTraits(.isHeader)
            Text(L10n.t("\(Self.titles[step]).text"))
                .font(.system(size: 15))
                .lineSpacing(4)
                .foregroundColor(Theme.bodyText)
                .fixedSize(horizontal: false, vertical: true)
            extras
        }
        .padding(.trailing, 10)
    }

    private var address: String? {
        if case .ready(let a) = host.state { return a }
        return nil
    }

    @ViewBuilder
    private var extras: some View {
        switch step {
        case 2:
            switch host.state {
            case .ready(let a):
                Text(a)
                    .font(.system(size: 24, design: .monospaced))
                    .foregroundColor(Theme.onSurface)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.background))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(accent.accent, lineWidth: 1.5))
                    .minimumScaleFactor(0.5)
                status(L10n.t("guide3.waiting"))
            case .loading:
                status(L10n.t("receiver.share.loading"))
            case .noWifi:
                Text(L10n.t("receiver.share.noWifi")).font(.callout).foregroundColor(Theme.power)
            case .failed:
                Text(L10n.t("receiver.share.failed")).font(.callout).foregroundColor(Theme.power)
                Button(L10n.t("action.tryAgain")) { host.start() }.foregroundColor(accent.accent)
            }
            Button(L10n.t("guide3.type"), action: onTypeLink)
                .foregroundColor(accent.accent)
                .frame(minHeight: 44)
        case 3:
            Text(L10n.t("guide4.blocked"))
                .font(.footnote)
                .foregroundColor(Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
            if mouseState == .connecting { status(L10n.t("guide5.looking")) }
        case 4:
            Text(L10n.t("guide5.gray"))
                .font(.footnote)
                .foregroundColor(Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
            status(L10n.t("guide5.looking"))
        default:
            EmptyView()
        }
    }

    private func status(_ text: String) -> some View {
        HStack(spacing: 10) {
            ProgressView().controlSize(.small)
            Text(text)
                .font(.footnote)
                .foregroundColor(Theme.muted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// A drawing of the TV screen for a step, with the thing to select lit and an arrow at it. The layout uses the
/// 322 by 181 space of the mockup, scaled to the card width. A TV screen looks dark whatever the phone theme is.
private struct TVDrawing: View {
    let step: Int
    let address: String?
    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drawn: CGFloat = 0

    private static let width: CGFloat = 322
    private static let back = Color(red: 11 / 255, green: 12 / 255, blue: 16 / 255)
    private static let tile = Color(red: 28 / 255, green: 30 / 255, blue: 36 / 255)
    private static let raised = Color(red: 35 / 255, green: 38 / 255, blue: 45 / 255)
    private static let bodyText = Color(red: 201 / 255, green: 204 / 255, blue: 212 / 255)
    private static let strong = Color(red: 232 / 255, green: 234 / 255, blue: 240 / 255)
    private static let muted = Color(red: 138 / 255, green: 143 / 255, blue: 156 / 255)
    private static let blue = Color(red: 58 / 255, green: 116 / 255, blue: 232 / 255)
    private static let orange = Color(red: 240 / 255, green: 127 / 255, blue: 26 / 255)

    /// The arrow of each drawing in the 322 by 181 space: from, then to. Step 6 has none.
    private static let arrows: [(CGPoint, CGPoint)] = [
        (CGPoint(x: 196, y: 162), CGPoint(x: 148, y: 122)),
        (CGPoint(x: 190, y: 166), CGPoint(x: 262, y: 118)),
        (CGPoint(x: 226, y: 162), CGPoint(x: 282, y: 112)),
        (CGPoint(x: 306, y: 172), CGPoint(x: 260, y: 140)),
        (CGPoint(x: 214, y: 164), CGPoint(x: 282, y: 100)),
    ]

    var body: some View {
        GeometryReader { geo in
            let k = geo.size.width / Self.width
            ZStack(alignment: .topLeading) {
                Self.back
                content(k)
                if step < Self.arrows.count {
                    let a = Self.arrows[step]
                    let arrow = CoachLayout.curve(
                        from: CGPoint(x: a.0.x * k, y: a.0.y * k), to: CGPoint(x: a.1.x * k, y: a.1.y * k)
                    )
                    GuideArrow(arrow: arrow, progress: drawn, color: accent.accent, scale: k)
                }
            }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 42 / 255, green: 45 / 255, blue: 53 / 255), lineWidth: 1))
        .accessibilityHidden(true)
        .onAppear {
            if reduceMotion {
                drawn = 1
            } else {
                withAnimation(.timingCurve(0, 0, 0.58, 1, duration: 0.65).delay(0.45)) { drawn = 1 }
            }
        }
    }

    @ViewBuilder
    private func content(_ k: CGFloat) -> some View {
        switch step {
        case 0:
            label(L10n.t("tv.apps"), 16, 46, k, color: Self.muted, bold: true)
            box(16, 70, 62, 44, k, Self.tile) { small(L10n.t("tv.video"), k) }
            box(92, 70, 62, 44, k, Self.raised, lit: true) { small(L10n.t("tv.play"), k, color: .white) }
            box(168, 70, 62, 44, k, Self.tile) { small(L10n.t("tv.music"), k) }
            box(244, 70, 62, 44, k, Self.tile) { small(L10n.t("tv.settings"), k) }
        case 1:
            box(16, 18, 290, 28, k, Self.tile, radius: 14, leading: true) { small(L10n.t("tv.search"), k, color: Self.strong).padding(.leading, 12 * k) }
            box(16, 70, 290, 60, k, Color(red: 22 / 255, green: 24 / 255, blue: 29 / 255), radius: 10) { EmptyView() }
            box(26, 80, 40, 40, k, Self.orange, radius: 9) {
                Image(systemName: "arrow.down").font(.system(size: 18 * k, weight: .bold)).foregroundColor(.white)
            }
            label("Downloader", 76, 82, k, color: Self.strong, bold: true, size: 12)
            label("AFTVnews", 76, 100, k, color: Self.muted)
            box(236, 88, 62, 24, k, Self.blue, radius: 12, lit: true) { small(L10n.t("tv.install"), k, color: .white, bold: true) }
        case 2:
            label("Downloader", 16, 20, k, color: Color(red: 245 / 255, green: 165 / 255, blue: 36 / 255), bold: true, size: 13)
            box(16, 74, 234, 32, k, Self.strong, radius: 6, leading: true) {
                Text(address ?? "…")
                    .font(.system(size: 12 * k, design: .monospaced))
                    .foregroundColor(Color(red: 20 / 255, green: 22 / 255, blue: 27 / 255))
                    .lineLimit(1)
                    .padding(.leading, 10 * k)
            }
            box(258, 74, 48, 32, k, Self.orange, radius: 6, lit: true) { small(L10n.t("tv.go"), k, color: .white, bold: true) }
        case 3:
            box(56, 26, 210, 124, k, Self.raised, radius: 10) { EmptyView() }
            label("TVBox Remote Receiver", 68, 36, k, color: Self.strong, bold: true, size: 12)
            label(L10n.t("tv.installQuestion"), 68, 56, k)
            box(112, 112, 66, 26, k, .clear, radius: 13) { small(L10n.t("tv.cancel"), k, color: Self.muted) }
            box(186, 112, 68, 26, k, Self.blue, radius: 13, lit: true) { small(L10n.t("tv.install"), k, color: .white, bold: true) }
        case 4:
            label(L10n.t("tv.accessibility"), 16, 20, k, color: Self.strong, bold: true, size: 13)
            box(16, 60, 290, 44, k, Self.tile, radius: 10, leading: true) {
                small(L10n.t("tv.mouseService"), k, color: Self.strong).padding(.leading, 12 * k)
            }
            box(262, 72, 36, 20, k, Self.blue, radius: 10, lit: true, leading: true) {
                Circle().fill(.white).frame(width: 14 * k, height: 14 * k).padding(.leading, 19 * k)
            }
            box(16, 112, 290, 36, k, Color(red: 22 / 255, green: 24 / 255, blue: 29 / 255), radius: 10, leading: true) {
                small(L10n.t("tv.captions"), k, color: Self.muted).padding(.leading, 12 * k)
            }
        default:
            VStack(spacing: 8 * k) {
                Text("TVBox Remote Receiver · " + L10n.t("tv.pairCode"))
                    .font(.system(size: 12 * k))
                    .foregroundColor(Self.muted)
                // Dots, not digits: the real code is on the TV.
                Text("● ● ● ●")
                    .font(.system(size: 24 * k))
                    .foregroundColor(.white)
                    .padding(.horizontal, 16 * k)
                    .padding(.vertical, 6 * k)
                    .modifier(TVLit(radius: 10 * k))
            }
            .frame(width: Self.width * k, height: Self.width * k * 9 / 16)
        }
    }

    private func box<C: View>(
        _ x: CGFloat, _ y: CGFloat, _ w: CGFloat, _ h: CGFloat, _ k: CGFloat, _ color: Color,
        radius: CGFloat = 8, lit: Bool = false, leading: Bool = false, @ViewBuilder content: () -> C
    ) -> some View {
        ZStack(alignment: leading ? .leading : .center) {
            RoundedRectangle(cornerRadius: radius * k).fill(color)
            content()
        }
        .frame(width: w * k, height: h * k)
        .modifier(TVLit(radius: radius * k, on: lit))
        .offset(x: x * k, y: y * k)
    }

    private func label(_ text: String, _ x: CGFloat, _ y: CGFloat, _ k: CGFloat, color: Color = TVDrawing.bodyText, bold: Bool = false, size: CGFloat = 11) -> some View {
        Text(text)
            .font(.system(size: size * k, weight: bold ? .semibold : .regular))
            .foregroundColor(color)
            .lineLimit(1)
            .offset(x: x * k, y: y * k)
    }

    private func small(_ text: String, _ k: CGFloat, color: Color = TVDrawing.bodyText, bold: Bool = false) -> some View {
        Text(text)
            .font(.system(size: 10 * k, weight: bold ? .semibold : .regular))
            .foregroundColor(color)
            .multilineTextAlignment(.center)
            .lineLimit(2)
    }
}

/// The light on the thing to select: an accent ring and glow.
private struct TVLit: ViewModifier {
    let radius: CGFloat
    var on = true
    @Environment(\.accent) private var accent

    func body(content: Content) -> some View {
        if on {
            content
                .overlay(RoundedRectangle(cornerRadius: radius).stroke(accent.accent, lineWidth: 1.5))
                .shadow(color: accent.accent.opacity(0.6), radius: 6)
        } else {
            content
        }
    }
}

private struct GuideArrow: View {
    let arrow: CoachArrow
    let progress: CGFloat
    let color: Color
    let scale: CGFloat

    var body: some View {
        ZStack {
            GuideCurve(arrow: arrow)
                .trim(from: 0, to: progress)
                .stroke(color, style: StrokeStyle(lineWidth: 2.5 * scale, lineCap: .round, lineJoin: .round))
                .shadow(color: color.opacity(0.6), radius: 4)
        }
        .allowsHitTesting(false)
    }
}

/// The curve and its head as one path, so one trim draws the line and then the head.
private struct GuideCurve: Shape {
    let arrow: CoachArrow
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: arrow.from)
        path.addCurve(to: arrow.to, control1: arrow.c1, control2: arrow.c2)
        let tx = arrow.to.x - arrow.c2.x
        let ty = arrow.to.y - arrow.c2.y
        let tl = max(hypot(tx, ty), 1)
        let bx = -tx / tl
        let by = -ty / tl
        let angle = 28.0 * .pi / 180
        let head: CGFloat = 10
        let w1 = CGPoint(x: arrow.to.x + head * (bx * cos(angle) - by * sin(angle)), y: arrow.to.y + head * (bx * sin(angle) + by * cos(angle)))
        let w2 = CGPoint(x: arrow.to.x + head * (bx * cos(angle) + by * sin(angle)), y: arrow.to.y + head * (-bx * sin(angle) + by * cos(angle)))
        path.move(to: w1)
        path.addLine(to: arrow.to)
        path.addLine(to: w2)
        return path
    }
}

/// The guide is a full screen cover over the mouse screen. From iOS 16.4 the mouse screen stays visible under the dim.
private struct ClearCoverBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(iOS 16.4, *) {
            content.presentationBackground(.clear)
        } else {
            content.background(RemoteBackground().ignoresSafeArea())
        }
    }
}
