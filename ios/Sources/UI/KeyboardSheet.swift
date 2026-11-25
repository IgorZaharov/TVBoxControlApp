import SwiftUI

/// Sends text to the focused text field on the box as the user types.
@MainActor
struct KeyboardSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @FocusState private var fieldFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                TextField(L10n.t("keyboard.placeholder"), text: $model.keyboardText)
                    .font(.title3)
                    .foregroundColor(Theme.onSurface)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .submitLabel(.send)
                    .focused($fieldFocused)
                    .onSubmit {
                        model.press(.enter)
                        fieldFocused = true
                    }
                    .padding(14)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surface))

                HStack(spacing: 12) {
                    Button {
                        if model.keyboardText.isEmpty {
                            model.press(.delete)
                        } else {
                            model.keyboardText.removeLast()
                        }
                    } label: {
                        Label(L10n.t("keyboard.delete"), systemImage: "delete.left")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)

                    Button {
                        model.keyboardText = ""
                    } label: {
                        Label(L10n.t("keyboard.clear"), systemImage: "xmark")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                }

                Text(L10n.t("keyboard.hint"))
                    .font(.footnote)
                    .foregroundColor(Theme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(L10n.t("keyboard.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
            .onChange(of: model.keyboardText) { text in
                model.sendText(text)
            }
            .task {
                // The sheet must finish its animation before the field can take focus.
                try? await Task.sleep(nanoseconds: 350_000_000)
                fieldFocused = true
            }
        }
        .presentationDetents([.medium, .large])
    }
}
