import SwiftUI

@MainActor
struct HelpView: View {
    @Environment(\.dismiss) private var dismiss

    /// The sections, as Localizable.strings keys: "help.<id>.title" and "help.<id>.1" ... "help.<id>.<count>".
    private struct HelpSection: Identifiable {
        let id: String
        let count: Int
    }

    private static let sections: [HelpSection] = [
        HelpSection(id: "start", count: 5),
        HelpSection(id: "pairing", count: 4),
        HelpSection(id: "buttons", count: 3),
        HelpSection(id: "mouse", count: 6),
        HelpSection(id: "controller", count: 4),
        HelpSection(id: "widget", count: 3),
        HelpSection(id: "keyboard", count: 3),
        HelpSection(id: "trouble", count: 6),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 28) {
                    ForEach(Self.sections) { section in
                        TextSection(
                            title: L10n.t("help.\(section.id).title"),
                            paragraphs: (1...section.count).map { L10n.t("help.\(section.id).\($0)") }
                        )
                    }
                }
                .padding(20)
            }
            .background(RemoteBackground())
            .navigationTitle(L10n.t("help.title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.t("action.done")) { dismiss() }
                }
            }
        }
    }
}
