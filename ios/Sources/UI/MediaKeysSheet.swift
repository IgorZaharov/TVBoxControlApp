import SwiftUI

/// Extra keys that the main screen does not show.
@MainActor
struct MediaKeysSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var prefs: Preferences
    @Environment(\.dismiss) private var dismiss

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 12), count: 4)

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: columns, spacing: 20) {
                    ForEach(RemoteKey.mediaKeys, id: \.self) { key in
                        VStack(spacing: 6) {
                            mediaButton(key)
                            Text(key.title)
                                .font(.caption)
                                .foregroundColor(Theme.muted)
                                .lineLimit(1)
                                .minimumScaleFactor(0.8)
                        }
                    }
                }
                .padding(20)
            }
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(L10n.t("media.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    /// Rewind and fast forward repeat while held, without blocking the scroll. The other keys
    /// are plain buttons: they fire on release, so a scroll that starts on a key does not press it.
    @ViewBuilder
    private func mediaButton(_ key: RemoteKey) -> some View {
        if key == .rewind || key == .fastForward {
            CircleButton(systemImage: key.symbol, label: key.title, size: 64, repeats: true, inScrollView: true) {
                model.press(key)
            }
        } else {
            Button {
                Haptics.tick(enabled: prefs.haptics)
                model.press(key)
            } label: {
                Image(systemName: key.symbol)
                    .font(.system(size: Theme.iconSize * 64 / Theme.buttonSize, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
                    .frame(width: 64, height: 64)
            }
            .buttonStyle(RaisedButtonStyle())
            .accessibilityLabel(key.title)
        }
    }
}
