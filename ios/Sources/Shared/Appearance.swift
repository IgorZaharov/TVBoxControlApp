import SwiftUI

/// The Theme preference. See docs/UI_SPEC.md, "Themes and accents".
enum ThemeChoice: String, CaseIterable, Identifiable {
    case system, dark, light

    var id: String { rawValue }

    /// The value for `preferredColorScheme`. Nil follows the phone.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .dark: return .dark
        case .light: return .light
        }
    }

    var title: String {
        switch self {
        case .system: return L10n.t("prefs.theme.system")
        case .dark: return L10n.t("prefs.theme.dark")
        case .light: return L10n.t("prefs.theme.light")
        }
    }
}

/// The Accent preference.
enum AccentChoice: String, CaseIterable, Identifiable {
    case blue, green, orange, red, rgb

    var id: String { rawValue }

    var title: String {
        switch self {
        case .blue: return L10n.t("prefs.accent.blue")
        case .green: return L10n.t("prefs.accent.green")
        case .orange: return L10n.t("prefs.accent.orange")
        case .red: return L10n.t("prefs.accent.red")
        case .rgb: return L10n.t("prefs.accent.rgb")
        }
    }
}

/// An sRGB color as three 0...1 components, for the contrast math.
struct RGB: Equatable {
    var r: Double
    var g: Double
    var b: Double

    init(r: Double, g: Double, b: Double) {
        self.r = r
        self.g = g
        self.b = b
    }

    init(hex: UInt32) {
        r = Double((hex >> 16) & 0xFF) / 255
        g = Double((hex >> 8) & 0xFF) / 255
        b = Double(hex & 0xFF) / 255
    }

    var color: Color { Color(red: r, green: g, blue: b) }

    /// WCAG relative luminance.
    var luminance: Double {
        func channel(_ c: Double) -> Double {
            c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /// WCAG contrast ratio, 1...21.
    func contrast(with other: RGB) -> Double {
        let a = luminance
        let b = other.luminance
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)
    }

    /// `hue` in degrees, `saturation` and `lightness` 0...1.
    static func hsl(hue: Double, saturation: Double, lightness: Double) -> RGB {
        let h = (hue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 360
        let l = min(1, max(0, lightness))
        let s = min(1, max(0, saturation))
        if s == 0 { return RGB(r: l, g: l, b: l) }
        let q = l < 0.5 ? l * (1 + s) : l + s - l * s
        let p = 2 * l - q
        func component(_ t0: Double) -> Double {
            var t = t0
            if t < 0 { t += 1 }
            if t > 1 { t -= 1 }
            if t < 1.0 / 6 { return p + (q - p) * 6 * t }
            if t < 1.0 / 2 { return q }
            if t < 2.0 / 3 { return p + (q - p) * (2.0 / 3 - t) * 6 }
            return p
        }
        return RGB(r: component(h + 1.0 / 3), g: component(h), b: component(h - 1.0 / 3))
    }
}

/// The accent colors of one theme: the accent itself, the two ends of the OK button
/// gradient, and the text color on the OK button.
struct AccentPalette: Equatable {
    var accent: Color
    var okTop: Color
    var okBottom: Color
    var onAccent: Color

    /// The theme backgrounds that the accent must stand out from (docs/UI_SPEC.md).
    static let darkBackground = RGB(hex: 0x0E0F12)
    static let lightBackground = RGB(hex: 0xF2F3F5)
    static let darkText = RGB(hex: 0x14161B)
    static let white = RGB(r: 1, g: 1, b: 1)

    /// The hue that RGB shows with Reduce Motion.
    static let reducedMotionHue: Double = 220

    /// Dark theme, blue: the default, and the look of the widget.
    static let standard = AccentPalette.make(.blue, dark: true, hue: reducedMotionHue)

    static func make(_ choice: AccentChoice, dark: Bool, hue: Double) -> AccentPalette {
        func fixed(_ accent: UInt32, _ top: UInt32, _ bottom: UInt32, onDark: Bool) -> AccentPalette {
            AccentPalette(
                accent: Color(hex: accent),
                okTop: Color(hex: top),
                okBottom: Color(hex: bottom),
                onAccent: onDark ? darkText.color : .white
            )
        }
        switch (choice, dark) {
        case (.blue, true): return fixed(0x4F8CFF, 0x6FA2FF, 0x3A74E8, onDark: false)
        case (.blue, false): return fixed(0x2563EB, 0x4A7FF0, 0x1D4FC4, onDark: false)
        case (.green, true): return fixed(0x3DBE7E, 0x5BD495, 0x24965F, onDark: true)
        case (.green, false): return fixed(0x127A46, 0x1E9A5A, 0x0F6B3D, onDark: false)
        case (.orange, true): return fixed(0xFF9A3D, 0xFFB15E, 0xF07F1A, onDark: true)
        case (.orange, false): return fixed(0xA84C0A, 0xD9691A, 0xA64708, onDark: false)
        case (.red, true): return fixed(0xFF5A5F, 0xFF7A7E, 0xE0383E, onDark: false)
        case (.red, false): return fixed(0xC9272D, 0xE0383E, 0xA81E23, onDark: false)
        case (.rgb, _): return rainbow(hue: hue, dark: dark)
        }
    }

    /// The RGB accent at `hue`: HSL with saturation 85%. The lightness starts at 62% (dark) or
    /// 36% (light) and moves in 2% steps until the color has 4.5:1 against the background.
    static func rainbowColor(hue: Double, dark: Bool) -> (color: RGB, lightness: Double) {
        let background = dark ? darkBackground : lightBackground
        var lightness = dark ? 0.62 : 0.36
        var color = RGB.hsl(hue: hue, saturation: 0.85, lightness: lightness)
        var steps = 0
        while color.contrast(with: background) < 4.5 && steps < 30 {
            lightness += dark ? 0.02 : -0.02
            color = RGB.hsl(hue: hue, saturation: 0.85, lightness: lightness)
            steps += 1
        }
        return (color, lightness)
    }

    static func rainbow(hue: Double, dark: Bool) -> AccentPalette {
        let (color, lightness) = rainbowColor(hue: hue, dark: dark)
        let top = RGB.hsl(hue: hue, saturation: 0.85, lightness: lightness + 0.12)
        let bottom = RGB.hsl(hue: hue, saturation: 0.85, lightness: lightness - 0.12)
        let textIsDark = darkText.contrast(with: color) > white.contrast(with: color)
        return AccentPalette(
            accent: color.color,
            okTop: top.color,
            okBottom: bottom.color,
            onAccent: textIsDark ? darkText.color : .white
        )
    }
}

private struct AccentPaletteKey: EnvironmentKey {
    static let defaultValue = AccentPalette.standard
}

extension EnvironmentValues {
    /// The accent colors for the current theme and Accent preference. The app root sets it.
    var accent: AccentPalette {
        get { self[AccentPaletteKey.self] }
        set { self[AccentPaletteKey.self] = newValue }
    }
}
