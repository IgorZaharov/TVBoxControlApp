import SwiftUI
import UIKit

/// The mouse screen: gyro pointer, touch pad, click, back and home.
@MainActor
struct MouseView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var prefs: Preferences
    /// Shows "Download link copied" after a tap on Copy link.
    @State private var linkCopied = false
    /// The guide that serves the receiver from this phone.
    @State private var showShare = false
    @Environment(\.accent) private var accent

    /// The Gyro switch is a preference, so it stays as the user left it after a restart.
    private var gyroOn: Bool { prefs.gyroEnabled }

    var body: some View {
        VStack(spacing: 16) {
            HStack(spacing: 12) {
                Text(L10n.t("mouse.title"))
                    .font(.headline)
                    .foregroundColor(Theme.onSurface)
                Spacer()
                if model.gyroAvailable {
                    Toggle(L10n.t("mouse.gyro"), isOn: $prefs.gyroEnabled)
                        .toggleStyle(.switch)
                        .fixedSize()
                        .foregroundColor(Theme.onSurface)
                }
                Button(L10n.t("action.done")) { model.closeMouse() }
                    .font(.body.weight(.semibold))
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)

            ZStack {
                RoundedRectangle(cornerRadius: 28)
                    .fill(Theme.surface)
                TouchPadView(
                    speed: prefs.touchpadSpeed,
                    onMove: { dx, dy in model.mouseMove(dx: dx, dy: dy) },
                    onTap: { model.mouseClick() },
                    onLongPress: { model.mouseLongPress() },
                    onScroll: { dx, dy in model.mouseScroll(dx: dx, dy: dy) }
                )
                .clipShape(RoundedRectangle(cornerRadius: 28))
                statusOverlay
                    .allowsHitTesting(model.mouseState != .ready)
            }
            .padding(.horizontal, 16)

            HStack(spacing: 16) {
                CircleButton(systemImage: RemoteKey.back.symbol, label: RemoteKey.back.title) { model.press(.back) }
                HoldButton(L10n.t("mouse.click"), scales: true, action: { model.mouseClick() }) { pressed in
                    Text(L10n.t("mouse.click"))
                        .font(.title3.weight(.semibold))
                        .foregroundColor(accent.onAccent)
                        .frame(maxWidth: .infinity)
                        .frame(height: Theme.buttonSize)
                        .background(AccentSurface(shape: Capsule(), pressed: pressed))
                }
                CircleButton(systemImage: RemoteKey.home.symbol, label: RemoteKey.home.title) { model.press(.home) }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 12)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RemoteBackground())
        .coachOverlay(step: tourStep) { step in TourCard(step: step).environmentObject(model) }
        .background(
            // A second presenter for the receiver install guide, so it does not share one view with the share cover.
            Color.clear
                .fullScreenCover(isPresented: $model.mouseGuideOpen) {
                    ReceiverGuideView()
                        .environmentObject(model)
                        .themed(prefs: prefs, accent: accent)
                }
        )
        .sheet(isPresented: Binding(
            get: { model.linkTyping != .idle },
            set: { if !$0 { model.closeTypeLink() } }
        )) {
            LinkTypingSheet {
                model.closeTypeLink()
                // The sheet closes first, then the guide opens.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { showShare = true }
            }
            .environmentObject(model)
            .themed(prefs: prefs, accent: accent)
        }
        .fullScreenCover(isPresented: $showShare) {
            ReceiverShareView()
                .themed(prefs: prefs, accent: accent)
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            model.setGyro(gyroOn)
        }
        .onDisappear {
            model.setGyro(false)
            UIApplication.shared.isIdleTimerDisabled = prefs.keepScreenOn
        }
        .onChange(of: prefs.gyroEnabled) { enabled in
            model.setGyro(enabled)
        }
        .sheet(isPresented: $model.receiverPairingVisible) {
            ReceiverPairingSheet()
                .themed(prefs: prefs, accent: accent)
                .environmentObject(model)
        }
    }

    @ViewBuilder
    private var statusOverlay: some View {
        switch model.mouseState {
        case .ready:
            VStack(spacing: 6) {
                Image(systemName: "hand.point.up.left")
                    .font(.system(size: 30))
                Text(gyroOn ? L10n.t("mouse.hintGyro") : L10n.t("mouse.hintTouch"))
                    .font(.footnote)
                Text(L10n.t("mouse.hintScroll"))
                    .font(.footnote)
            }
            .foregroundColor(Theme.muted.opacity(0.7))
        case .connecting, .off:
            VStack(spacing: 10) {
                ProgressView()
                Text(L10n.t("mouse.connecting"))
                    .font(.footnote)
                    .foregroundColor(Theme.muted)
            }
        case .needsPairing:
            Text(L10n.t("mouse.enterCode"))
                .font(.footnote)
                .foregroundColor(Theme.muted)
        case .unavailable(let message):
            // No receiver yet: the mouse setup (docs/UI_SPEC.md, "Mouse setup"). An iPhone has only the receiver way.
            ScrollView {
                VStack(spacing: 14) {
                    Image(systemName: "computermouse")
                        .font(.system(size: 40))
                        .foregroundColor(Theme.muted)
                    Text(L10n.t("mouse.setup.title"))
                        .font(.title3.weight(.semibold))
                        .foregroundColor(Theme.onSurface)
                    Text(L10n.t("mouse.setup.textIos"))
                        .font(.callout)
                        .foregroundColor(Theme.muted)
                        .multilineTextAlignment(.center)
                    Text(message)
                        .font(.footnote)
                        .foregroundColor(Theme.muted)
                        .multilineTextAlignment(.center)
                    Text(L10n.t("mouse.setup.noBluetooth"))
                        .font(.footnote)
                        .foregroundColor(Theme.muted)
                        .multilineTextAlignment(.center)
                    Button {
                        if model.mouseTour == .choose { model.finishMouseTour() }
                        model.mouseGuideOpen = true
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "arrow.down.to.line")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundColor(accent.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L10n.t("mouse.choice.receiver"))
                                    .font(.body.weight(.semibold))
                                    .foregroundColor(Theme.onSurface)
                                Text(L10n.t("mouse.choice.receiverDetail"))
                                    .font(.footnote)
                                    .foregroundColor(Theme.muted)
                            }
                            Spacer(minLength: 0)
                        }
                        .multilineTextAlignment(.leading)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: .infinity, minHeight: 76)
                        .background(RoundedRectangle(cornerRadius: 20).fill(Theme.onSurface.opacity(0.06)))
                    }
                    .coachTarget("\(TourTargets.mouseChoices)/receiver")
                    // The other ways to get the receiver: copy the fixed link, or let the phone type it on the box.
                    HStack(spacing: 12) {
                        Button(L10n.t("receiver.link.copy")) {
                            UIPasteboard.general.string = ReceiverDownload.url
                            linkCopied = true
                        }
                        Button(L10n.t("receiver.link.type")) { model.startTypeLink() }
                    }
                    .font(.footnote)
                    if linkCopied {
                        Text(L10n.t("receiver.link.copied"))
                            .font(.footnote)
                            .foregroundColor(Theme.muted)
                    }
                    Button(L10n.t("action.tryAgain")) { model.retryMouse() }
                        .font(.footnote)
                }
                .padding(20)
            }
            .onAppear { model.startMouseTourIfFirst() }
        }
    }

    /// The walk-through card over the mouse screen, the first time the setup shows.
    private var tourStep: TourStep? {
        TourFlow.step(
            tour: model.tour, mouseTour: model.mouseTour, screen: .mouse, wifiBoxes: 0, emptyForLong: false,
            dialogOpen: model.receiverPairingVisible || model.linkTyping != .idle || model.mouseGuideOpen || showShare
        )
    }
}

/// Touch pad scale factors. A separate enum keeps them free of actor isolation.
enum TouchPadTuning {
    /// TV pixels per phone point at speed 1.0.
    static let movePixelsPerPoint = 1.6
    /// Scale from finger points to TV pixels for one receiver swipe.
    static let scrollPixelsPerPoint = 2.5
    /// The finger must move this far (points) before a swipe goes to the box.
    static let scrollStep: CGFloat = 60
    /// At most one swipe per this interval, so each swipe is long enough.
    static let scrollInterval: TimeInterval = 0.15
    /// A remainder smaller than this at the end of the gesture is dropped.
    static let scrollMinimumRemainder: CGFloat = 8
}

/// A UIKit touch pad: one-finger drag moves, tap clicks, long press holds,
/// two-finger drag scrolls.
struct TouchPadView: UIViewRepresentable {
    var speed: Double
    var onMove: (Double, Double) -> Void
    var onTap: () -> Void
    var onLongPress: () -> Void
    var onScroll: (Double, Double) -> Void

    func makeCoordinator() -> Coordinator {
        let coordinator = Coordinator()
        copy(to: coordinator)
        return coordinator
    }

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        view.isMultipleTouchEnabled = true

        let coordinator = context.coordinator
        let move = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleMove(_:)))
        move.minimumNumberOfTouches = 1
        move.maximumNumberOfTouches = 1
        let scroll = UIPanGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleScroll(_:)))
        scroll.minimumNumberOfTouches = 2
        scroll.maximumNumberOfTouches = 2
        let tap = UITapGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleTap(_:)))
        let longPress = UILongPressGestureRecognizer(target: coordinator, action: #selector(Coordinator.handleLongPress(_:)))
        longPress.minimumPressDuration = 0.5

        for recognizer in [move, scroll, tap, longPress] {
            view.addGestureRecognizer(recognizer)
        }
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        copy(to: context.coordinator)
    }

    private func copy(to coordinator: Coordinator) {
        coordinator.speed = speed
        coordinator.onMove = onMove
        coordinator.onTap = onTap
        coordinator.onLongPress = onLongPress
        coordinator.onScroll = onScroll
    }

    /// Receives the UIKit gesture callbacks on the main thread.
    final class Coordinator: NSObject {
        var speed = 1.0
        var onMove: (Double, Double) -> Void = { _, _ in }
        var onTap: () -> Void = {}
        var onLongPress: () -> Void = {}
        var onScroll: (Double, Double) -> Void = { _, _ in }
        private var scrollRemainder = CGPoint.zero
        private var lastScrollTime = Date.distantPast

        @objc func handleMove(_ recognizer: UIPanGestureRecognizer) {
            let translation = recognizer.translation(in: recognizer.view)
            recognizer.setTranslation(.zero, in: recognizer.view)
            let factor = TouchPadTuning.movePixelsPerPoint * speed
            onMove(Double(translation.x) * factor, Double(translation.y) * factor)
        }

        @objc func handleScroll(_ recognizer: UIPanGestureRecognizer) {
            switch recognizer.state {
            case .began:
                scrollRemainder = .zero
                lastScrollTime = .distantPast
            case .changed, .ended:
                // Accumulate the finger movement. Do not send one swipe per touch event.
                let translation = recognizer.translation(in: recognizer.view)
                recognizer.setTranslation(.zero, in: recognizer.view)
                scrollRemainder.x += translation.x
                scrollRemainder.y += translation.y
                let distance = max(abs(scrollRemainder.x), abs(scrollRemainder.y))
                let now = Date()
                let ended = recognizer.state == .ended
                let stepReached = distance >= TouchPadTuning.scrollStep
                    && now.timeIntervalSince(lastScrollTime) >= TouchPadTuning.scrollInterval
                let lastPiece = ended && distance >= TouchPadTuning.scrollMinimumRemainder
                if stepReached || lastPiece {
                    // Same sign rule on both axes: dx = -finger dx, dy = -finger dy.
                    // A finger that moves up (negative y) gives a positive dy.
                    let factor = TouchPadTuning.scrollPixelsPerPoint
                    onScroll(Double(-scrollRemainder.x) * factor, Double(-scrollRemainder.y) * factor)
                    scrollRemainder = .zero
                    lastScrollTime = now
                }
                if ended {
                    scrollRemainder = .zero
                }
            default:
                scrollRemainder = .zero
            }
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            onTap()
        }

        @objc func handleLongPress(_ recognizer: UILongPressGestureRecognizer) {
            if recognizer.state == .began {
                onLongPress()
            }
        }
    }
}
