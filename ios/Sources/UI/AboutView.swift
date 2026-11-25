import SwiftUI

@MainActor
struct AboutView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accent) private var accent

    private var version: String {
        let info = Bundle.main.infoDictionary
        let short = info?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        let build = info?["CFBundleVersion"] as? String ?? "1"
        return L10n.f("about.version %@ %@", short, build)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    VStack(spacing: 10) {
                        Image(systemName: "tv.and.mediabox")
                            .font(.system(size: 56))
                            .foregroundColor(accent.accent)
                        Text(L10n.t("app.storeName"))
                            .font(.title2.weight(.bold))
                            .foregroundColor(Theme.onSurface)
                        Text(version)
                            .font(.subheadline)
                            .foregroundColor(Theme.muted)
                        Text(L10n.t("about.description"))
                            .font(.body)
                            .foregroundColor(Theme.onSurface)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)

                    TextSection(title: L10n.t("about.protocols.title"), paragraphs: [
                        L10n.t("about.protocols.1"),
                        L10n.t("about.protocols.2"),
                        L10n.t("about.protocols.3"),
                    ])

                    TextSection(title: L10n.t("about.privacy.title"), paragraphs: [
                        L10n.t("about.privacy.1"),
                        L10n.t("about.privacy.2"),
                    ])

                    TextSection(title: L10n.t("about.credits.title"), paragraphs: [
                        L10n.t("about.credits.1"),
                        L10n.t("about.credits.2"),
                    ])
                }
                .padding(20)
            }
            .background(RemoteBackground())
            .navigationTitle(L10n.t("about.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
        }
    }
}
