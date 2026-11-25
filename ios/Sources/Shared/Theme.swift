import SwiftUI
import UIKit

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }

    /// A color that SwiftUI resolves from the color scheme of the view: `dark` or `light`.
    /// It follows the Theme preference, because the app sets `preferredColorScheme` on its root.
    static func themed(dark: UInt32, light: UInt32, darkOpacity: CGFloat = 1, lightOpacity: CGFloat = 1) -> Color {
        Color(UIColor { traits in
            traits.userInterfaceStyle == .light
                ? UIColor(hex: light, alpha: lightOpacity)
                : UIColor(hex: dark, alpha: darkOpacity)
        })
    }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }
}

/// Colors and sizes from docs/UI_SPEC.md ("Colors and shapes", "Themes and accents").
/// The colors resolve to the dark or the light theme by themselves. The accent colors are not
/// here: they come from the `\.accent` environment value (see AccentPalette).
enum Theme {
    static let background = Color.themed(dark: 0x0E0F12, light: 0xF2F3F5)
    static let surface = Color.themed(dark: 0x1C1E24, light: 0xFFFFFF)
    static let surfacePressed = Color.themed(dark: 0x2A2D35, light: 0xE4E6EA)
    static let onSurface = Color.themed(dark: 0xE8EAF0, light: 0x14161B)
    static let bodyText = Color.themed(dark: 0xC9CCD4, light: 0x2A2E36)
    static let muted = Color.themed(dark: 0x8A8F9C, light: 0x565C68)
    static let power = Color.themed(dark: 0xE5484D, light: 0xD9363C)
    static let ok = Color.themed(dark: 0x30A46C, light: 0x218A57)
    static let warn = Color.themed(dark: 0xF5A524, light: 0xB7730A)
    /// The X face button of the controller: blue in both themes, darker in light.
    static let faceBlue = Color.themed(dark: 0x4F8CFF, light: 0x2563EB)

    // Premium look: textured background and raised buttons.
    static let backgroundTop = Color.themed(dark: 0x16181D, light: 0xF8F9FA)
    static let backgroundBottom = Color.themed(dark: 0x0A0B0E, light: 0xE6E8EC)
    static let bodyTop = Color.themed(dark: 0x272A33, light: 0xFFFFFF)
    static let bodyBottom = Color.themed(dark: 0x17191E, light: 0xE9EBEF)
    static let bodyPressedTop = Color.themed(dark: 0x17191E, light: 0xE1E3E8)
    static let bodyPressedBottom = Color.themed(dark: 0x22252D, light: 0xF1F2F4)
    static let shadow = Color.themed(dark: 0x000000, light: 0x000000, darkOpacity: 0.5, lightOpacity: 0.16)
    /// The top of the rim stroke. It fades to clear at the bottom.
    static let rim = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.12, lightOpacity: 0.9)
    /// The sheen at the center of its radial light.
    static let sheen = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.08, lightOpacity: 0.45)
    static let wellCenter = Color.themed(dark: 0x07080A, light: 0xD5D8DE)
    static let wellEdge = Color.themed(dark: 0x1B1D23, light: 0xEEF0F3)
    /// The signal line at rest.
    static let track = Color.themed(dark: 0xFFFFFF, light: 0x000000, darkOpacity: 0.06, lightOpacity: 0.08)
    /// A pressed volume segment and other darkened areas.
    static let pressShade = Color.themed(dark: 0x000000, light: 0x000000, darkOpacity: 0.28, lightOpacity: 0.08)

    // Matte glass cards of the Preferences screen (docs/UI_SPEC.md, "Glass card").
    static let glassTop = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.07, lightOpacity: 0.78)
    static let glassBottom = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.03, lightOpacity: 0.64)
    static let glassBorderTop = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.12, lightOpacity: 1)
    static let glassBorderBottom = Color.themed(dark: 0xFFFFFF, light: 0xFFFFFF, darkOpacity: 0.04, lightOpacity: 1)
    /// The hairline outside the light theme's white border. None in the dark theme.
    static let glassHairline = Color.themed(dark: 0x000000, light: 0x000000, darkOpacity: 0, lightOpacity: 0.06)
    static let glassShadow = Color.themed(dark: 0x000000, light: 0x000000, darkOpacity: 0.45, lightOpacity: 0.10)
    /// The line between rows of one card: onSurface at 8%.
    static let divider = Color.themed(dark: 0xE8EAF0, light: 0x14161B, darkOpacity: 0.08, lightOpacity: 0.08)

    static let buttonSize: CGFloat = 72
    static let iconSize: CGFloat = 30
    static let dpadSize: CGFloat = 264
    static let okSize: CGFloat = 104

    /// Hold-to-repeat timing.
    static let repeatDelayNanos: UInt64 = 400_000_000
    static let repeatIntervalNanos: UInt64 = 110_000_000
}

/// Motion tokens from docs/UI_SPEC.md, "Motion" (Animate UI values).
enum Motion {
    /// Button scale and body change. Stiffness 550, damping 30.
    static let press = Animation.interpolatingSpring(stiffness: 550, damping: 30)
    /// Press glow coming in. Stiffness 400, damping 29.
    static let pop = Animation.interpolatingSpring(stiffness: 400, damping: 29)
    /// Press glow going out.
    static let fadeOut = Animation.timingCurve(0.23, 1, 0.32, 1, duration: 0.26)
    /// The signal line pulse.
    static let pulse = Animation.timingCurve(0.23, 1, 0.32, 1, duration: 0.45)
    static let pulseSeconds: Double = 0.45
    /// The sheen following the tilt. Stiffness 500, damping 40.
    static let highlight = Animation.interpolatingSpring(stiffness: 500, damping: 40)

    /// The scale of a pressed button.
    static let pressScale: CGFloat = 0.97
}
