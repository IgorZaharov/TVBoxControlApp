import SwiftUI
import UIKit

/// Preferences: five matte glass cards on the remote background (docs/UI_SPEC.md, "Preferences").
@MainActor
struct PreferencesView: View {
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accent) private var accent
    @State private var showLayoutEditor = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    appearance
                    language
                    remote
                    pointer
                    controller
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 32)
            }
            .background(RemoteBackground())
            .navigationTitle(L10n.t("prefs.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
        }
        .fullScreenCover(isPresented: $showLayoutEditor) {
            ControllerLayoutEditor()
                .themed(prefs: prefs, accent: accent)
        }
    }

    // MARK: Cards

    private var appearance: some View {
        section(L10n.t("prefs.appearanceHeader")) {
            VStack(alignment: .leading, spacing: 12) {
                rowTitle(systemImage: "circle.lefthalf.filled", title: L10n.t("prefs.theme"))
                ThemeSegmentedControl(selection: $prefs.theme)
            }
            .padding(14)
            GlassDivider()
            VStack(alignment: .leading, spacing: 14) {
                rowTitle(systemImage: "paintpalette", title: L10n.t("prefs.accent"))
                AccentPicker(selection: $prefs.accent)
            }
            .padding(14)
        }
    }

    /// iOS keeps the per-app language in the Settings app, so the row opens the page of the app there.
    private var language: some View {
        section(L10n.t("prefs.section.language")) {
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                GlassRow(
                    systemImage: "globe",
                    title: L10n.t("prefs.language"),
                    detail: Self.currentLanguageName
                ) {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Theme.muted)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint(L10n.t("prefs.language.hint"))
        }
    }

    /// The language that the app uses now, named in that language.
    private static var currentLanguageName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "en"
        return code.hasPrefix("uk") ? "Українська" : "English"
    }

    private var remote: some View {
        section(L10n.t("prefs.section.remote")) {
            toggleRow(
                systemImage: "iphone.radiowaves.left.and.right",
                title: L10n.t("prefs.haptics"),
                detail: L10n.t("prefs.haptics.detail"),
                isOn: $prefs.haptics
            )
            GlassDivider()
            toggleRow(
                systemImage: "sun.max",
                title: L10n.t("prefs.keepScreenOn"),
                detail: L10n.t("prefs.keepScreenOn.detail"),
                isOn: $prefs.keepScreenOn
            )
        }
    }

    private var pointer: some View {
        VStack(spacing: 8) {
            section(L10n.t("prefs.section.pointer")) {
                speedRow(systemImage: "gyroscope", title: L10n.t("prefs.gyroSpeed"), value: $prefs.gyroSpeed)
                GlassDivider()
                speedRow(systemImage: "hand.point.up.left", title: L10n.t("prefs.touchpadSpeed"), value: $prefs.touchpadSpeed)
            }
            Text(L10n.t("prefs.mouseFooter"))
                .font(.system(size: 13))
                .foregroundColor(Theme.muted)
                .padding(.horizontal, 6)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var controller: some View {
        section(L10n.t("prefs.section.controller")) {
            Button {
                showLayoutEditor = true
            } label: {
                GlassRow(
                    systemImage: "gamecontroller",
                    title: L10n.t("prefs.controllerLayout"),
                    detail: L10n.t("prefs.controllerLayoutFooter")
                ) {
                    Image(systemName: "chevron.right")
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Theme.muted)
                        .accessibilityHidden(true)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    // MARK: Building blocks

    /// A section title and a glass card under it.
    private func section<Content: View>(_ title: String, @ViewBuilder content: @escaping () -> Content) -> some View {
        VStack(spacing: 8) {
            GlassSectionTitle(text: title)
                .padding(.top, 14)
            GlassCard(content: content)
        }
    }

    private func rowTitle(systemImage: String, title: String) -> some View {
        HStack(spacing: 14) {
            IconTile(systemImage: systemImage)
            Text(title)
                .font(.system(size: 16))
                .foregroundColor(Theme.onSurface)
        }
    }

    /// A row with a switch. A tap anywhere on the row toggles the switch.
    private func toggleRow(systemImage: String, title: String, detail: String, isOn: Binding<Bool>) -> some View {
        GlassRow(systemImage: systemImage, title: title, detail: detail) {
            Toggle(title, isOn: isOn)
                .labelsHidden()
        }
        .contentShape(Rectangle())
        .onTapGesture { isOn.wrappedValue.toggle() }
        // One element for VoiceOver: the title, the state, the detail as a hint, and a double tap toggles.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(isOn.wrappedValue ? L10n.t("a11y.on") : L10n.t("a11y.off"))
        .accessibilityHint(detail)
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { isOn.wrappedValue.toggle() }
    }

    private func speedRow(systemImage: String, title: String, value: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 14) {
                IconTile(systemImage: systemImage)
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(Theme.onSurface)
                Spacer()
                Text(L10n.f("prefs.speedValue %@", value.wrappedValue.formatted(.number.precision(.fractionLength(1)))))
                    .font(.system(size: 14).monospacedDigit())
                    .foregroundColor(Theme.muted)
            }
            Slider(value: value, in: Preferences.speedRange, step: 0.1)
                .accessibilityLabel(title)
                .padding(.leading, 50)
        }
        .padding(14)
    }
}
