import SwiftUI

@main
struct TVBoxRemoteApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            AppearanceRoot(prefs: model.prefs) {
                RemoteView()
            }
            .environmentObject(model)
            .environmentObject(model.prefs)
            .onOpenURL { _ in
                // The widget opens the app with SharedConfig.openAppURL. Nothing else to do.
            }
        }
        .onChange(of: scenePhase) { phase in
            model.scenePhaseChanged(phase)
        }
    }
}
