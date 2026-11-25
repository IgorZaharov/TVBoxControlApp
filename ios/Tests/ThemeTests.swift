import XCTest
@testable import TVBoxRemote

/// The contrast rules of docs/UI_SPEC.md, "Themes and accents".
final class ThemeTests: XCTestCase {
    /// The fixed accents, as (dark accent, light accent).
    private let fixedAccents: [(String, UInt32, UInt32)] = [
        ("blue", 0x4F8CFF, 0x2563EB),
        ("green", 0x3DBE7E, 0x127A46),
        ("orange", 0xFF9A3D, 0xA84C0A),
        ("red", 0xFF5A5F, 0xC9272D),
    ]

    func testFixedAccentsReadAsTextOnTheBackground() {
        for (name, dark, light) in fixedAccents {
            XCTAssertGreaterThanOrEqual(RGB(hex: dark).contrast(with: AccentPalette.darkBackground), 4.5, "\(name) dark")
            XCTAssertGreaterThanOrEqual(RGB(hex: light).contrast(with: AccentPalette.lightBackground), 4.5, "\(name) light")
        }
    }

    func testRainbowReadsAsTextAtEveryHue() {
        for hue in stride(from: 0.0, to: 360.0, by: 2.0) {
            let dark = AccentPalette.rainbowColor(hue: hue, dark: true).color
            let light = AccentPalette.rainbowColor(hue: hue, dark: false).color
            XCTAssertGreaterThanOrEqual(dark.contrast(with: AccentPalette.darkBackground), 4.5, "dark, hue \(hue)")
            XCTAssertGreaterThanOrEqual(light.contrast(with: AccentPalette.lightBackground), 4.5, "light, hue \(hue)")
        }
    }

    /// The OK label is large bold text: it needs 3:1 on the middle of the OK gradient.
    func testOkLabelHasLargeTextContrast() {
        let pairs: [(String, UInt32, UInt32, Bool)] = [
            ("blue dark", 0x6FA2FF, 0x3A74E8, false),
            ("green dark", 0x5BD495, 0x24965F, true),
            ("orange dark", 0xFFB15E, 0xF07F1A, true),
            ("red dark", 0xFF7A7E, 0xE0383E, false),
            ("blue light", 0x4A7FF0, 0x1D4FC4, false),
            ("green light", 0x1E9A5A, 0x0F6B3D, false),
            ("orange light", 0xD9691A, 0xA64708, false),
            ("red light", 0xE0383E, 0xA81E23, false),
        ]
        for (name, top, bottom, darkText) in pairs {
            let a = RGB(hex: top)
            let b = RGB(hex: bottom)
            let middle = RGB(r: (a.r + b.r) / 2, g: (a.g + b.g) / 2, b: (a.b + b.b) / 2)
            let text = darkText ? AccentPalette.darkText : AccentPalette.white
            XCTAssertGreaterThanOrEqual(text.contrast(with: middle), 3, name)
        }
    }

    func testHslKnownValues() {
        let red = RGB.hsl(hue: 0, saturation: 1, lightness: 0.5)
        XCTAssertEqual(red.r, 1, accuracy: 0.001)
        XCTAssertEqual(red.g, 0, accuracy: 0.001)
        XCTAssertEqual(red.b, 0, accuracy: 0.001)
        let blue = RGB.hsl(hue: 240, saturation: 1, lightness: 0.5)
        XCTAssertEqual(blue.b, 1, accuracy: 0.001)
        XCTAssertEqual(blue.r, 0, accuracy: 0.001)
    }

    func testContrastOfBlackOnWhiteIs21() {
        XCTAssertEqual(RGB(hex: 0x000000).contrast(with: RGB(hex: 0xFFFFFF)), 21, accuracy: 0.01)
    }

    func testDefaultsAreDarkAndBlue() {
        let suite = "theme-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = Preferences(defaults: defaults)
        XCTAssertEqual(prefs.theme, .dark)
        XCTAssertEqual(prefs.accent, .blue)
        XCTAssertTrue(prefs.gyroEnabled)
    }

    func testSettingsSurviveARestart() {
        let suite = "theme-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let first = Preferences(defaults: defaults)
        first.theme = .light
        first.accent = .rgb
        first.gyroEnabled = false
        let second = Preferences(defaults: defaults)
        XCTAssertEqual(second.theme, .light)
        XCTAssertEqual(second.accent, .rgb)
        XCTAssertFalse(second.gyroEnabled)
    }

    func testControllerLayoutSurvivesARestart() {
        let suite = "layout-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var layout = ControllerLayout.standard
        layout.set(ControlPlacement(x: 0.3, y: 0.4, scale: 1.3), for: .dpad)
        ControllerLayoutStore.save(layout, defaults: defaults)
        XCTAssertEqual(ControllerLayoutStore.load(defaults: defaults), layout)
    }

    // MARK: - Press glow and signal line (docs/UI_SPEC.md, "Press glow", "Signal line")

    func testPressFlashIsOffBeforeAndAfterItsRun() {
        XCTAssertEqual(PressFlashValue.at(0, expands: true), .none)
        XCTAssertEqual(PressFlashValue.at(1, expands: true), .none)
    }

    func testPressFlashPeaksAt15PercentThenFades() {
        XCTAssertEqual(PressFlashValue.at(0.15, expands: true).light, 1, accuracy: 0.0001)
        XCTAssertEqual(PressFlashValue.at(0.075, expands: true).light, 0.5, accuracy: 0.0001)
        XCTAssertLessThan(PressFlashValue.at(0.9, expands: true).light, 0.1)
    }

    func testPressFlashGrowsOnlyWithMotion() {
        XCTAssertGreaterThan(PressFlashValue.at(0.5, expands: true).spread, 0.7)
        XCTAssertEqual(PressFlashValue.at(0.5, expands: false).spread, 0)
    }

    @MainActor
    func testSignalLineKeeps16PointsClearAndStaysBetween24And64() {
        XCTAssertEqual(RemoteView.signalLineHeight(freeHeight: 200), 64)
        XCTAssertEqual(RemoteView.signalLineHeight(freeHeight: 80), 48)
        XCTAssertEqual(RemoteView.signalLineHeight(freeHeight: 30), 24)
    }
}
