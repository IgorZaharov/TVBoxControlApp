import Foundation

/// The fixed download link of the newest box receiver. The Release workflow attaches receiver.apk to each GitHub
/// release, and GitHub sends "latest/download" to the newest release. Android uses the same link (ReceiverDownload.kt).
enum ReceiverDownload {
    static let url = "https://github.com/IgorZaharov/TVBoxControlApp/releases/latest/download/receiver.apk"
}
