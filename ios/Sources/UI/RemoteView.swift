import SwiftUI
import UIKit

/// The main screen: a remote control with large buttons.
@MainActor
struct RemoteView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accent) private var accent
    @StateObject private var tilt = TiltModel()

    var body: some View {
        // One-hand layout (docs/UI_SPEC.md, "Remote screen"): the top bar and the top row stay
        // at the top. The D-pad, the Back/Home/Settings row and the volume pill form one group
        // at the bottom, so that the D-pad is in thumb reach. All free height goes above the D-pad.
        VStack(spacing: 0) {
            topBar

            HStack(spacing: 14) {
                CircleButton(systemImage: "power", label: RemoteKey.power.title, tint: Theme.power, glow: Theme.power) {
                    model.press(.power)
                }
                Spacer()
                CircleButton(systemImage: "keyboard", label: L10n.t("remote.keyboard")) {
                    model.openKeyboard()
                }
                CircleButton(systemImage: "cursorarrow.motionlines", label: L10n.t("remote.mouse")) {
                    model.openMouse()
                }
                CircleButton(systemImage: "gamecontroller", label: L10n.t("remote.controller")) {
                    model.openController()
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)

            // All free height goes here, above the D-pad (at least 16 pt). The signal line lives in
            // this area, so it never pushes the D-pad down. It sits in the middle of the area, and on a
            // short screen it gets shorter to keep 16 pt clear above and below it.
            GeometryReader { area in
                SignalLine(signals: model.signals, height: Self.signalLineHeight(freeHeight: area.size.height))
                    .frame(width: area.size.width, height: area.size.height, alignment: .center)
            }
            .frame(minHeight: 16)

            // The bottom group.
            DPadView { key in model.press(key) }
                .frame(maxWidth: .infinity)

            HStack(spacing: 32) {
                CircleButton(systemImage: RemoteKey.back.symbol, label: RemoteKey.back.title) { model.press(.back) }
                CircleButton(systemImage: RemoteKey.home.symbol, label: RemoteKey.home.title) { model.press(.home) }
                CircleButton(systemImage: RemoteKey.menu.symbol, label: RemoteKey.menu.title) { model.press(.menu) }
            }
            .padding(.top, 20)

            VolumePill(volume: model.volume) { key in model.press(key) }
                .padding(.top, 16)
                .padding(.bottom, 16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(RemoteBackground())
        .environment(\.tiltModel, tilt)
        .sheet(item: $model.sheet) { sheet in
            sheetContent(sheet)
                .themed(prefs: prefs, accent: accent)
                .environmentObject(model)
        }
        .fullScreenCover(isPresented: $model.showMouse) {
            MouseView()
                .themed(prefs: prefs, accent: accent)
                .environmentObject(model)
        }
        .background(
            // A second presenter, so that the mouse and the controller covers do not share one view.
            Color.clear
                .fullScreenCover(isPresented: $model.showController) {
                    ControllerView()
                        .themed(prefs: prefs, accent: accent)
                        .environmentObject(model)
                }
        )
        .modelAlert(model)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = prefs.keepScreenOn
            updateTilt()
        }
        .onDisappear { tilt.stop() }
        .onChange(of: prefs.keepScreenOn) { keepOn in
            UIApplication.shared.isIdleTimerDisabled = keepOn
        }
        .onChange(of: scenePhase) { _ in updateTilt() }
        .onChange(of: model.showMouse) { _ in updateTilt() }
        .onChange(of: model.showController) { _ in updateTilt() }
        .onChange(of: model.sheet) { _ in updateTilt() }
        .onChange(of: reduceMotion) { _ in updateTilt() }
    }

    /// Space the signal line keeps clear above and below it.
    private static let signalLineClearance: CGFloat = 16

    /// The signal line is 64 pt high. With less free height it gets shorter, but never shorter than 24 pt.
    static func signalLineHeight(freeHeight: CGFloat) -> CGFloat {
        let room = freeHeight - 2 * signalLineClearance
        return min(max(room, SignalLine.minHeight), SignalLine.maxHeight)
    }

    /// The tilt sensor runs only while the remote is visible and motion is allowed.
    /// A sheet (Preferences, the layout editor over it, Devices, ...) covers the remote too.
    private func updateTilt() {
        let visible = scenePhase == .active && !model.showMouse && !model.showController && model.sheet == nil
        if visible && !reduceMotion {
            tilt.start()
        } else {
            tilt.stop()
        }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Button {
                model.sheet = .devices
            } label: {
                HStack(spacing: 10) {
                    Circle()
                        .fill(statusColor)
                        .frame(width: 10, height: 10)
                        .shadow(color: statusColor.opacity(0.7), radius: 4)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(model.statusTitle)
                            .font(.headline)
                            .foregroundColor(Theme.onSurface)
                            .lineLimit(1)
                        if let detail = model.statusDetail {
                            Text(detail)
                                .font(.caption)
                                .foregroundColor(Theme.muted)
                                .lineLimit(1)
                        }
                    }
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(Theme.muted)
                }
                .padding(.vertical, 6)
            }
            .accessibilityLabel(L10n.f("remote.devicesLabel %@", model.statusTitle))

            Spacer()

            Menu {
                Button { model.sheet = .devices } label: { Label(L10n.t("menu.devices"), systemImage: "tv") }
                Button { model.sheet = .mediaKeys } label: { Label(L10n.t("menu.media"), systemImage: "playpause") }
                Button { model.sheet = .preferences } label: { Label(L10n.t("menu.preferences"), systemImage: "slider.horizontal.3") }
                Button { model.sheet = .help } label: { Label(L10n.t("menu.help"), systemImage: "questionmark.circle") }
                Button { model.sheet = .about } label: { Label(L10n.t("menu.about"), systemImage: "info.circle") }
            } label: {
                Image(systemName: "ellipsis")
                    .rotationEffect(.degrees(90))
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                    .frame(width: 48, height: 48)
            }
            .accessibilityLabel(L10n.t("menu.open"))
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
    }

    private var statusColor: Color {
        switch model.connectionState {
        case .connected: return Theme.ok
        case .connecting: return Theme.warn
        case .idle, .failed: return Theme.muted
        }
    }

    @ViewBuilder
    private func sheetContent(_ sheet: AppModel.Sheet) -> some View {
        switch sheet {
        case .devices: DevicesView()
        case .mediaKeys: MediaKeysSheet()
        case .keyboard: KeyboardSheet()
        case .preferences: PreferencesView()
        case .help: HelpView()
        case .about: AboutView()
        case .atvPairing: PairingSheet()
        }
    }
}

extension View {
    /// The theme, the accent and the preferences for a sheet or a cover. Presentations get their
    /// own environment, so they get the same appearance again here.
    @MainActor
    func themed(prefs: Preferences, accent: AccentPalette) -> some View {
        self
            .environment(\.accent, accent)
            .environment(\.backgroundTexture, prefs.background)
            .tint(accent.accent)
            .environmentObject(prefs)
            .preferredColorScheme(prefs.theme.colorScheme)
    }

    /// Shows the general alert message of the app model.
    @MainActor
    func modelAlert(_ model: AppModel) -> some View {
        modifier(ModelAlertModifier(model: model))
    }
}

@MainActor
private struct ModelAlertModifier: ViewModifier {
    @ObservedObject var model: AppModel

    func body(content: Content) -> some View {
        content.alert(
            L10n.t("app.name"),
            isPresented: Binding(
                get: { model.alertMessage != nil },
                set: { if !$0 { model.alertMessage = nil } }
            )
        ) {
            Button(L10n.t("action.ok"), role: .cancel) {}
        } message: {
            Text(model.alertMessage ?? "")
        }
    }
}
