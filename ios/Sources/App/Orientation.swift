import SwiftUI
import UIKit

/// Gives the window its allowed orientations. The app is portrait, except while
/// the controller screen or the controller layout editor is open (landscape only).
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.mask
    }
}

enum OrientationLock {
    /// Read by AppDelegate on the main thread. Info.plist lists portrait and both
    /// landscape orientations. Write it only through `set(_:)`.
    static var mask: UIInterfaceOrientationMask = .portrait

    /// Allows `mask` only and turns the screen to it.
    @MainActor
    static func set(_ newMask: UIInterfaceOrientationMask) {
        mask = newMask
        for scene in UIApplication.shared.connectedScenes {
            guard let windowScene = scene as? UIWindowScene else { continue }
            for window in windowScene.windows {
                var controller = window.rootViewController
                while let current = controller {
                    current.setNeedsUpdateOfSupportedInterfaceOrientations()
                    controller = current.presentedViewController
                }
            }
            windowScene.requestGeometryUpdate(.iOS(interfaceOrientations: newMask)) { _ in
                // The system refuses while another rotation runs. The next set() tries again.
            }
        }
    }
}

extension View {
    /// Landscape only, no status bar or home indicator, and the screen stays on,
    /// while this view is on screen. Restores portrait and the "Keep screen on" preference after.
    @MainActor
    func landscapeFullScreen(prefs: Preferences) -> some View {
        modifier(LandscapeFullScreen(prefs: prefs))
    }
}

@MainActor
private struct LandscapeFullScreen: ViewModifier {
    let prefs: Preferences

    func body(content: Content) -> some View {
        content
            .statusBarHidden(true)
            .persistentSystemOverlays(.hidden)
            .onAppear {
                OrientationLock.set(.landscape)
                UIApplication.shared.isIdleTimerDisabled = true
            }
            .onDisappear {
                OrientationLock.set(.portrait)
                UIApplication.shared.isIdleTimerDisabled = prefs.keepScreenOn
            }
    }
}
