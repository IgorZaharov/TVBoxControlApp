import SwiftUI
import UIKit

/// Install the receiver from this phone: the phone serves receiver.apk on the local Wi-Fi and shows the short
/// address in big text, with the steps for the box (docs/UI_SPEC.md, "Receiver install"). The server runs while
/// this screen is open, and the screen stays on.
@MainActor
struct ReceiverShareView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accent) private var accent
    @StateObject private var host = ReceiverHost()
    @State private var idleTimerWasDisabled = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    addressCard
                    stepsCard
                    Text(L10n.t("receiver.share.keep"))
                        .font(.footnote)
                        .foregroundColor(Theme.muted)
                }
                .padding(16)
            }
            .background(RemoteBackground())
            .navigationTitle(L10n.t("receiver.share.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
        }
        .onAppear {
            host.start()
            idleTimerWasDisabled = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            host.stop()
            UIApplication.shared.isIdleTimerDisabled = idleTimerWasDisabled
        }
    }

    private var addressCard: some View {
        VStack(spacing: 12) {
            switch host.state {
            case .loading:
                ProgressView()
                Text(L10n.t("receiver.share.loading"))
                    .foregroundColor(Theme.muted)
            case .noWifi, .failed:
                Text(L10n.t(host.state == .noWifi ? "receiver.share.noWifi" : "receiver.share.failed"))
                    .foregroundColor(Theme.onSurface)
                    .multilineTextAlignment(.center)
                Button(L10n.t("action.tryAgain")) { host.start() }
                    .buttonStyle(.bordered)
            case .ready(let address):
                Text(L10n.t("receiver.share.type"))
                    .foregroundColor(Theme.muted)
                Text(address)
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                    .foregroundColor(accent.accent)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.5)
                    .lineLimit(2)
                    .textSelection(.enabled)
                Text(L10n.f("receiver.share.fullAddress", "http://\(address)"))
                    .font(.footnote)
                    .foregroundColor(Theme.muted)
                    .multilineTextAlignment(.center)
                if host.downloads > 0 {
                    Label(L10n.t("receiver.share.done"), systemImage: "checkmark.circle.fill")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Theme.ok)
                } else {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(L10n.t("receiver.share.waiting"))
                            .foregroundColor(Theme.muted)
                    }
                }
            }
        }
        .frame(maxWidth: .infinity)
        .padding(20)
        .background(GlassBackground(cornerRadius: 20))
    }

    private var stepsCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(["receiver.share.step1", "receiver.share.step2", "receiver.share.step3", "receiver.share.step4"].enumerated()), id: \.offset) { index, key in
                HStack(alignment: .top, spacing: 12) {
                    Text("\(index + 1)")
                        .font(.subheadline.weight(.bold))
                        .foregroundColor(accent.onAccent)
                        .frame(width: 26, height: 26)
                        .background(Circle().fill(accent.accent))
                    Text(L10n.t(key))
                        .foregroundColor(Theme.onSurface)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(GlassBackground(cornerRadius: 20))
    }
}

/// "Type link on the box": what the phone waits for, and the fallback when the box does not take the text.
@MainActor
struct LinkTypingSheet: View {
    @EnvironmentObject private var model: AppModel
    let onShare: () -> Void

    init(onShare: @escaping () -> Void) {
        self.onShare = onShare
    }

    var body: some View {
        VStack(spacing: 16) {
            Text(L10n.t("link.typing.title"))
                .font(.headline)
                .foregroundColor(Theme.onSurface)
            if model.linkTyping == .waiting || model.linkTyping == .sending {
                ProgressView()
            }
            Text(message)
                .foregroundColor(Theme.onSurface)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Button(L10n.t("receiver.share.open"), action: onShare)
                    .buttonStyle(.bordered)
                Button(L10n.t(model.linkTyping == .typed || model.linkTyping == .noKeyboard ? "action.done" : "action.cancel")) {
                    model.closeTypeLink()
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
        .presentationDetents([.medium])
    }

    private var message: String {
        switch model.linkTyping {
        case .typed: return L10n.t("link.typing.done")
        case .noKeyboard: return L10n.t("link.typing.noKeyboard")
        default: return L10n.t("link.typing.wait")
        }
    }
}
