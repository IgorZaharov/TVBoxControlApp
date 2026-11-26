import SwiftUI

/// Android TV pairing: the user types the 6-character code that the TV shows.
@MainActor
struct PairingSheet: View {
    @EnvironmentObject private var model: AppModel
    @State private var code = ""
    @FocusState private var fieldFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                switch model.pairingStage {
                case .waitingForTV:
                    ProgressView()
                        .controlSize(.large)
                    Text(L10n.t("pair.asking"))
                        .foregroundColor(Theme.onSurface)
                    Text(L10n.t("pair.askingHint"))
                        .font(.footnote)
                        .foregroundColor(Theme.muted)
                        .multilineTextAlignment(.center)
                case .enterCode, .checking:
                    Text(L10n.t("pair.enterCode"))
                        .font(.title3.weight(.semibold))
                        .foregroundColor(Theme.onSurface)
                        .multilineTextAlignment(.center)
                    TextField("", text: $code)
                        .font(.system(size: 36, weight: .semibold, design: .monospaced))
                        .multilineTextAlignment(.center)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .keyboardType(.asciiCapable)
                        .submitLabel(.go)
                        .focused($fieldFocused)
                        .padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surface))
                        .onChange(of: code) { newValue in
                            let filtered = String(newValue.uppercased().filter { $0.isHexDigit }.prefix(6))
                            if filtered != newValue { code = filtered }
                        }
                        .onSubmit { submit() }
                    if model.pairingStage == .checking {
                        ProgressView()
                    } else {
                        // The TV can miss the request, for example while it sleeps. A new request shows a new code.
                        Text(L10n.t("pair.noCode"))
                            .font(.footnote)
                            .foregroundColor(Theme.muted)
                            .multilineTextAlignment(.center)
                        Button(L10n.t("pair.askAgain")) {
                            code = ""
                            model.retryPairing()
                        }
                    }
                case .failed:
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 40))
                        .foregroundColor(Theme.warn)
                    Button(L10n.t("action.tryAgain")) {
                        code = ""
                        model.retryPairing()
                    }
                    .buttonStyle(.borderedProminent)
                }

                if let error = model.pairingError {
                    Text(error)
                        .font(.callout)
                        .foregroundColor(Theme.power)
                        .multilineTextAlignment(.center)
                }
                Spacer(minLength: 0)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(L10n.t("pair.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.t("action.cancel")) { model.cancelPairing() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.pair")) { submit() }
                        .disabled(code.count != 6 || model.pairingStage != .enterCode)
                }
            }
            .onChange(of: model.pairingStage) { stage in
                if stage == .enterCode { fieldFocused = true }
            }
        }
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled()
    }

    private func submit() {
        guard code.count == 6, model.pairingStage == .enterCode else { return }
        model.submitPairingCode(code)
    }
}

/// Receiver pairing: the user types the 4-digit code that the TV shows.
@MainActor
struct ReceiverPairingSheet: View {
    @EnvironmentObject private var model: AppModel
    @State private var code = ""
    @FocusState private var fieldFocused: Bool

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text(L10n.t("pair.receiverEnterCode"))
                    .font(.title3.weight(.semibold))
                    .foregroundColor(Theme.onSurface)
                    .multilineTextAlignment(.center)
                TextField("", text: $code)
                    .font(.system(size: 36, weight: .semibold, design: .monospaced))
                    .multilineTextAlignment(.center)
                    .keyboardType(.numberPad)
                    .focused($fieldFocused)
                    .padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surface))
                    .onChange(of: code) { newValue in
                        let filtered = String(newValue.filter { $0.isASCII && $0.isNumber }.prefix(4))
                        if filtered != newValue { code = filtered }
                    }
                if let error = model.receiverPairingError {
                    Text(error)
                        .font(.callout)
                        .foregroundColor(Theme.power)
                        .multilineTextAlignment(.center)
                }
                Spacer(minLength: 0)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background.ignoresSafeArea())
            .navigationTitle(L10n.t("pair.receiverTitle"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.t("action.cancel")) { model.cancelReceiverPairing() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.pair")) {
                        model.submitReceiverCode(code)
                        code = ""
                    }
                    .disabled(code.count != 4)
                }
            }
            .onAppear { fieldFocused = true }
        }
        .presentationDetents([.medium])
        .interactiveDismissDisabled()
    }
}
