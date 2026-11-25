import SwiftUI

/// Lists the TV boxes on the Wi-Fi network and the saved boxes.
@MainActor
struct DevicesView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var showManualEntry = false
    @State private var manualAddress = ""
    @State private var showInvalidAddress = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if model.boxes.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text(L10n.t("devices.searching"))
                                .foregroundColor(Theme.muted)
                        }
                        .listRowBackground(Theme.surface)
                    }
                    ForEach(model.boxes) { box in
                        Button {
                            model.connect(box)
                        } label: {
                            DeviceRow(
                                box: box,
                                isCurrent: model.currentBox?.id == box.id,
                                state: model.connectionState
                            )
                        }
                        .listRowBackground(Theme.surface)
                        .swipeActions {
                            Button(role: .destructive) {
                                model.forget(box)
                            } label: {
                                Label(L10n.t("action.forget"), systemImage: "trash")
                            }
                        }
                        .contextMenu {
                            Button(role: .destructive) {
                                model.forget(box)
                            } label: {
                                Label(L10n.t("action.forget"), systemImage: "trash")
                            }
                        }
                    }
                } header: {
                    Text(L10n.t("devices.wifi"))
                } footer: {
                    Text(L10n.t("devices.wifiFooter"))
                }

                Section {
                    Button {
                        manualAddress = ""
                        showManualEntry = true
                    } label: {
                        Label(L10n.t("devices.addByIp"), systemImage: "plus.circle")
                    }
                    .listRowBackground(Theme.surface)
                    if model.currentBox != nil {
                        Button(role: .destructive) {
                            model.disconnect()
                        } label: {
                            Label(L10n.t("devices.disconnect"), systemImage: "xmark.circle")
                        }
                        .listRowBackground(Theme.surface)
                    }
                } footer: {
                    Text(L10n.t("devices.noBluetooth"))
                }
            }
            .scrollContentBackground(.hidden)
            .background(RemoteBackground())
            .navigationTitle(L10n.t("devices.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
            .alert(L10n.t("devices.addByIp"), isPresented: $showManualEntry) {
                TextField("192.168.1.20", text: $manualAddress)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                Button(L10n.t("action.cancel"), role: .cancel) {}
                Button(L10n.t("action.add")) {
                    if !model.addManualBox(ip: manualAddress) {
                        showInvalidAddress = true
                    }
                }
            } message: {
                Text(L10n.t("devices.addByIpHelp"))
            }
            .alert(L10n.t("devices.invalidTitle"), isPresented: $showInvalidAddress) {
                Button(L10n.t("action.ok"), role: .cancel) {}
            } message: {
                Text(L10n.t("devices.invalidText"))
            }
            .modelAlert(model)
        }
    }
}

@MainActor
private struct DeviceRow: View {
    let box: TVBox
    let isCurrent: Bool
    @Environment(\.accent) private var accent
    let state: AppModel.ConnectionState

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: "tv")
                .font(.system(size: 22))
                .foregroundColor(isCurrent ? accent.accent : Theme.onSurface)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 3) {
                Text(box.name)
                    .font(.body.weight(.semibold))
                    .foregroundColor(Theme.onSurface)
                Text(detail)
                    .font(.caption)
                    .foregroundColor(Theme.muted)
                if box.mouseReady {
                    Text(L10n.t("devices.mouseReady"))
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Theme.ok)
                }
            }
            Spacer()
            if isCurrent {
                switch state {
                case .connected:
                    Image(systemName: "checkmark.circle.fill").foregroundColor(Theme.ok)
                case .connecting:
                    ProgressView()
                default:
                    EmptyView()
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var detail: String {
        var parts: [String] = [box.host ?? L10n.t("devices.findingAddress")]
        if !box.isPaired {
            parts.append(L10n.t("devices.notPaired"))
        } else if !box.isDiscovered {
            parts.append(L10n.t("devices.notSeen"))
        }
        return parts.joined(separator: " · ")
    }
}
