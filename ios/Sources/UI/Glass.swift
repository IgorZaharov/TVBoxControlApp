import SwiftUI

// The matte glass look of the Preferences screen. See docs/UI_SPEC.md, "Preferences".

/// A matte glass card: the system ultra-thin material, a white gradient fill, a rim that fades
/// down, and a soft shadow. Only the background casts the shadow, not the text.
struct GlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 20
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(spacing: 0, content: content)
            .background(GlassBackground(cornerRadius: cornerRadius))
    }
}

/// The background layers of a glass card or tile.
struct GlassBackground: View {
    var cornerRadius: CGFloat = 20
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        let light = colorScheme == .light
        ZStack {
            shape.fill(.ultraThinMaterial)
            shape.fill(LinearGradient(colors: [Theme.glassTop, Theme.glassBottom], startPoint: .top, endPoint: .bottom))
            shape.stroke(Theme.glassHairline, lineWidth: 1)
            shape.strokeBorder(
                LinearGradient(colors: [Theme.glassBorderTop, Theme.glassBorderBottom], startPoint: .top, endPoint: .bottom),
                lineWidth: 1
            )
        }
        // Blur 24, offset 10 (dark) or blur 20, offset 8 (light). SwiftUI's radius is about half the blur.
        .shadow(color: Theme.glassShadow, radius: light ? 10 : 12, x: 0, y: light ? 8 : 10)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// The small title above a card: 13 pt, semibold, muted, upper case.
struct GlassSectionTitle: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 13, weight: .semibold))
            .textCase(.uppercase)
            .foregroundColor(Theme.muted)
            .padding(.leading, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

/// The leading icon of a row: a 36 pt rounded square with the accent at 14%, the icon in the accent.
struct IconTile: View {
    let systemImage: String
    @Environment(\.accent) private var accent

    var body: some View {
        RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(accent.accent.opacity(0.14))
            .frame(width: 36, height: 36)
            .overlay(
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(accent.accent)
            )
            .accessibilityHidden(true)
    }
}

/// The line between two rows of a card, inset 64 pt from the left (after the icon tile).
struct GlassDivider: View {
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Rectangle()
            .fill(Theme.divider)
            .frame(height: 1 / displayScale)
            .padding(.leading, 64)
            .accessibilityHidden(true)
    }
}

/// One row of a card: icon tile, title and detail, and a trailing view. At least 56 pt high.
struct GlassRow<Trailing: View>: View {
    let systemImage: String
    let title: String
    var detail: String?
    @ViewBuilder let trailing: () -> Trailing

    var body: some View {
        HStack(spacing: 14) {
            IconTile(systemImage: systemImage)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 16))
                    .foregroundColor(Theme.onSurface)
                if let detail {
                    Text(detail)
                        .font(.system(size: 13))
                        .foregroundColor(Theme.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            trailing()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
    }
}

// MARK: - Theme control

/// Theme: System, Dark or Light, as a glass pill with three segments. The selected segment is a
/// raised body with an accent border, and it slides to the new choice.
@MainActor
struct ThemeSegmentedControl: View {
    @Binding var selection: ThemeChoice
    @Environment(\.accent) private var accent
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var namespace

    var body: some View {
        HStack(spacing: 0) {
            ForEach(ThemeChoice.allCases) { choice in
                segment(choice)
            }
        }
        .padding(3)
        .frame(height: 44)
        .background(
            Capsule()
                .fill(LinearGradient(colors: [Theme.wellCenter.opacity(0.55), Theme.wellEdge.opacity(0.55)], startPoint: .top, endPoint: .bottom))
                .overlay(Capsule().strokeBorder(Theme.divider, lineWidth: 1))
        )
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L10n.t("prefs.theme"))
    }

    private func segment(_ choice: ThemeChoice) -> some View {
        let selected = choice == selection
        return Button {
            withAnimation(reduceMotion ? nil : Motion.press) { selection = choice }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: Self.symbol(choice))
                    .font(.system(size: 13, weight: .semibold))
                Text(choice.title)
                    .font(.system(size: 14, weight: selected ? .semibold : .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundColor(selected ? Theme.onSurface : Theme.muted)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background {
                if selected {
                    ZStack {
                        RaisedSurface(shape: Capsule(), pressed: false, glowing: false)
                        Capsule().strokeBorder(accent.accent, lineWidth: 1.5)
                    }
                    .matchedGeometryEffect(id: "selectedTheme", in: namespace)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(choice.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    static func symbol(_ choice: ThemeChoice) -> String {
        switch choice {
        case .system: return "circle.lefthalf.filled"
        case .dark: return "moon.fill"
        case .light: return "sun.max.fill"
        }
    }
}

// MARK: - Accent picker

/// Five glass tiles in one row: a jewel ring in the tile's accent color and the color name.
/// The selected tile has an accent border, an outer glow and a check badge, so the choice is
/// clear without color. See docs/UI_SPEC.md, "Accent picker".
@MainActor
struct AccentPicker: View {
    @Binding var selection: AccentChoice

    var body: some View {
        HStack(spacing: 8) {
            ForEach(AccentChoice.allCases) { choice in
                AccentTile(choice: choice, selected: choice == selection) {
                    selection = choice
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(L10n.t("prefs.accent"))
    }
}

@MainActor
private struct AccentTile: View {
    let choice: AccentChoice
    let selected: Bool
    let action: () -> Void

    @Environment(\.colorScheme) private var colorScheme
    /// The live accent: for the selected RGB tile it moves with the RGB cycle.
    @Environment(\.accent) private var liveAccent

    var body: some View {
        Button(action: action) {
            VStack(spacing: 8) {
                AccentJewel(choice: choice, color: tileColor)
                Text(choice.title)
                    .font(.system(size: 12, weight: selected ? .semibold : .regular))
                    .foregroundColor(selected ? Theme.onSurface : Theme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 84)
            .background(GlassBackground(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(selected ? tileColor : Color.clear, lineWidth: 1.5)
            )
            // The outer glow of the selected tile: accent 35%, blur 14.
            .shadow(color: selected ? tileColor.opacity(0.35) : .clear, radius: 7)
            .overlay(alignment: .topTrailing) {
                if selected {
                    Circle()
                        .fill(tileColor)
                        .frame(width: 20, height: 20)
                        .overlay(
                            Image(systemName: "checkmark")
                                .font(.system(size: 10, weight: .heavy))
                                .foregroundColor(onTileColor)
                        )
                        .offset(x: 6, y: -6)
                        .accessibilityHidden(true)
                }
            }
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(TilePressStyle())
        .frame(minWidth: 56)
        .accessibilityLabel(choice.title)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private var palette: AccentPalette {
        AccentPalette.make(choice, dark: colorScheme != .light, hue: AccentPalette.reducedMotionHue)
    }

    /// The tile's own accent in the current theme. The RGB tile uses the live RGB accent.
    private var tileColor: Color { choice == .rgb ? liveAccent.accent : palette.accent }

    private var onTileColor: Color { choice == .rgb ? liveAccent.onAccent : palette.onAccent }
}

/// The jewel: a 34 pt ring, stroke 3 pt, with a soft glow of the same color inside.
/// The RGB jewel ring is a color wheel.
private struct AccentJewel: View {
    let choice: AccentChoice
    let color: Color
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            Circle()
                .fill(RadialGradient(colors: [glowColor.opacity(0.35), glowColor.opacity(0)], center: .center, startRadius: 0, endRadius: 17))
            if choice == .rgb {
                Circle().stroke(
                    AngularGradient(
                        colors: stride(from: 0.0, through: 360.0, by: 45.0).map {
                            AccentPalette.rainbowColor(hue: $0, dark: colorScheme != .light).color.color
                        },
                        center: .center
                    ),
                    lineWidth: 3
                )
            } else {
                Circle().stroke(color, lineWidth: 3)
            }
        }
        .frame(width: 34, height: 34)
        .padding(1.5)
        .accessibilityHidden(true)
    }

    private var glowColor: Color { color }
}

/// A press scales the tile to 0.97 with the press spring. The layout around it does not move.
private struct TilePressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? Motion.pressScale : 1)
            .animation(Motion.press, value: configuration.isPressed)
    }
}
