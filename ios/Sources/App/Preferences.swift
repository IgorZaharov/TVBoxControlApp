import AVFoundation
import Foundation
import UIKit

/// User preferences, stored in the shared UserDefaults (App Group), so that they survive a restart.
/// The controller layout has its own store (ControllerLayoutStore).
final class Preferences: ObservableObject {
    static let speedRange: ClosedRange<Double> = 0.5...3.0

    @Published var haptics: Bool {
        didSet { defaults.set(haptics, forKey: Keys.haptics) }
    }

    /// A soft tap sound on each button press. Default off.
    @Published var pressSound: Bool {
        didSet { defaults.set(pressSound, forKey: Keys.pressSound) }
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

    /// Default: the gradient.
    @Published var background: BackgroundTexture {
        didSet { defaults.set(background.rawValue, forKey: Keys.background) }
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
        static let pressSound = "pref.pressSound"
        static let background = "pref.background"
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
        pressSound = defaults.object(forKey: Keys.pressSound) as? Bool ?? false
        background = defaults.string(forKey: Keys.background).flatMap(BackgroundTexture.init(rawValue:)) ?? .gradient
    }
}

@MainActor
enum Haptics {
    /// The feedback of one button press: the haptic tick and the tap sound, as the preferences say.
    static func press(_ prefs: Preferences) {
        tick(enabled: prefs.haptics)
        if prefs.pressSound { PressSound.play() }
    }

    /// A short, light tick.
    static func tick(enabled: Bool) {
        guard enabled else { return }
        let generator = UIImpactFeedbackGenerator(style: .light)
        generator.impactOccurred()
    }
}

/// The button press sound: tap.wav (made by tools/make_tap.py), a soft 50 ms tap. It plays on the media volume.
/// The user turned the sound on in the app, so the ringer volume and the silent switch do not mute it.
/// The audio session mixes with other apps, so music keeps playing.
@MainActor
enum PressSound {
    /// Three players, so that fast presses do not cut each other off.
    private static let players: [AVAudioPlayer] = {
        guard let url = Bundle.main.url(forResource: "tap", withExtension: "wav") else { return [] }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try? session.setActive(true)
        return (0..<3).compactMap { _ in
            let player = try? AVAudioPlayer(contentsOf: url)
            player?.prepareToPlay()
            return player
        }
    }()
    private static var next = 0

    static func play() {
        guard !players.isEmpty else { return }
        let player = players[next]
        next = (next + 1) % players.count
        player.currentTime = 0
        player.play()
    }
}
