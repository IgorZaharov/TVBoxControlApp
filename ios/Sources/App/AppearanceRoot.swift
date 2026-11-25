import Combine
import SwiftUI

/// Applies the Theme and Accent preferences to the whole app.
/// The theme goes to `preferredColorScheme`, so the themed Theme colors and the sheets follow it.
/// The accent goes to the `\.accent` environment value and to the tint.
@MainActor
struct AppearanceRoot<Content: View>: View {
    @ObservedObject var prefs: Preferences
    @ViewBuilder let content: () -> Content

    var body: some View {
        AccentProvider(choice: prefs.accent, content: content)
            .preferredColorScheme(prefs.theme.colorScheme)
    }
}

/// Reads the effective color scheme (after preferredColorScheme) and builds the accent palette.
@MainActor
private struct AccentProvider<Content: View>: View {
    let choice: AccentChoice
    let content: () -> Content

    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var clock = RainbowClock()

    var body: some View {
        let hue = reduceMotion ? AccentPalette.reducedMotionHue : clock.hue
        let palette = AccentPalette.make(choice, dark: colorScheme != .light, hue: hue)
        content()
            .environment(\.accent, palette)
            .tint(palette.accent)
            .onAppear(perform: updateClock)
            .onChange(of: choice) { _ in updateClock() }
            .onChange(of: reduceMotion) { _ in updateClock() }
            .onChange(of: scenePhase) { _ in updateClock() }
    }

    /// The RGB accent moves only while it is chosen, the app is active, and motion is allowed.
    private func updateClock() {
        clock.setRunning(choice == .rgb && !reduceMotion && scenePhase == .active)
    }
}

/// The hue of the RGB accent: one full cycle in 8 s, about 20 updates per second.
@MainActor
final class RainbowClock: ObservableObject {
    static let cycleSeconds: Double = 8
    static let interval: TimeInterval = 0.05

    @Published private(set) var hue: Double = AccentPalette.reducedMotionHue

    private var timer: Timer?
    /// Where the cycle starts, so that a restart of the timer continues the hue without a jump.
    private var start = Date()

    func setRunning(_ running: Bool) {
        if running {
            guard timer == nil else { return }
            start = Date().addingTimeInterval(-hue / 360 * Self.cycleSeconds)
            let timer = Timer(timeInterval: Self.interval, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.tick() }
            }
            RunLoop.main.add(timer, forMode: .common)
            self.timer = timer
        } else {
            timer?.invalidate()
            timer = nil
        }
    }

    private func tick() {
        let elapsed = Date().timeIntervalSince(start)
        hue = (elapsed / Self.cycleSeconds * 360).truncatingRemainder(dividingBy: 360)
    }
}
