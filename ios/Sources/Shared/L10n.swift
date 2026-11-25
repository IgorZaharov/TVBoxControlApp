import Foundation

/// Looks up user-visible text in Localizable.strings (Sources/Shared/Localization).
/// The app and the widget extension both compile the strings files, so
/// `Bundle.main` is the right bundle in both processes.
///
/// NSLocalizedString is used instead of `String(localized:)` because the keys
/// are often built at run time (for example the key titles), and it behaves
/// the same on iOS 16 and 17.
enum L10n {
    /// The text for `key`. A missing key shows the key itself, so it is easy to find.
    static func t(_ key: String) -> String {
        NSLocalizedString(key, tableName: nil, bundle: .main, value: key, comment: "")
    }

    /// The text for `key` with `%@` and `%lld` placeholders filled in.
    static func f(_ key: String, _ arguments: CVarArg...) -> String {
        String(format: t(key), locale: Locale.current, arguments: arguments)
    }
}
