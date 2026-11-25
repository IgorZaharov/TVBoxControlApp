import Foundation
import UIKit

/// User preferences, stored in the shared UserDefaults (App Group), so that they survive a restart.
/// The controller layout has its own store (ControllerLayoutStore).
final class Preferences: ObservableObject {
    static let speedRange: ClosedRange<Double> = 0.5...3.0

    @Published var haptics: Bool {
        didSet { defaults.set(haptics, forKey: Keys.haptics) }
    }

    @Published var gyroSpeed: Double {
        didSet { defaults.set(gyroSpeed, forKey: Keys.gyroSpeed) }
    }

    @Published var touchpadSpeed: Double {
        didSet { defaults.set(touchpadSpeed, forKey: Keys.touchpadSpeed) }
    }

    @Published var keepScreenOn: Bool {
        didSet { defaults.set(keepScreenOn, forKey: Keys.keepScreenOn) }
    }

    /// The Gyro switch on the mouse screen. Default on.
    @Published var gyroEnabled: Bool {
        didSet { defaults.set(gyroEnabled, forKey: Keys.gyroEnabled) }
    }

    /// Default dark.
    @Published var theme: ThemeChoice {
        didSet { defaults.set(theme.rawValue, forKey: Keys.theme) }
    }

    /// Default blue.
    @Published var accent: AccentChoice {
        didSet { defaults.set(accent.rawValue, forKey: Keys.accent) }
    }

    private let defaults: UserDefaults

    private enum Keys {
        static let haptics = "pref.haptics"
        static let gyroSpeed = "pref.gyroSpeed"
        static let touchpadSpeed = "pref.touchpadSpeed"
        static let keepScreenOn = "pref.keepScreenOn"
        static let gyroEnabled = "pref.gyroEnabled"
        static let theme = "pref.theme"
        static let accent = "pref.accent"
    }

    init(defaults: UserDefaults = SharedConfig.defaults) {
        self.defaults = defaults
        haptics = defaults.object(forKey: Keys.haptics) as? Bool ?? true
        gyroSpeed = defaults.object(forKey: Keys.gyroSpeed) as? Double ?? 1.0
        touchpadSpeed = defaults.object(forKey: Keys.touchpadSpeed) as? Double ?? 1.0
        keepScreenOn = defaults.object(forKey: Keys.keepScreenOn) as? Bool ?? false
        gyroEnabled = defaults.object(forKey: Keys.gyroEnabled) as? Bool ?? true
        theme = defaults.string(forKey: Keys.theme).flatMap(ThemeChoice.init(rawValue:)) ?? .dark
        accent = defaults.string(forKey: Keys.accent).flatMap(AccentChoice.init(rawValue:)) ?? .blue
    }
}

@MainActor
enum Haptics {
    /// A short, light tick.
    static func tick(enabled: Bool) {
        guard enabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
    }
}
