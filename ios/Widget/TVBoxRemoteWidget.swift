import AppIntents
import SwiftUI
import WidgetKit

@main
struct TVBoxRemoteWidgetBundle: WidgetBundle {
    var body: some Widget {
        TVBoxRemoteWidget()
    }
}

/// A small remote on the home screen (iOS 17 and later). See docs/UI_SPEC.md, "Home screen widget".
struct TVBoxRemoteWidget: Widget {
    var body: some WidgetConfiguration {
        AppIntentConfiguration(
            kind: SharedConfig.widgetKind,
            intent: RemoteWidgetIntent.self,
            provider: RemoteProvider()
        ) { entry in
            // The widget keeps the default look: dark theme, blue accent.
            RemoteWidgetView(entry: entry)
                .environment(\.colorScheme, .dark)
                .containerBackground(for: .widget) { WidgetBackground().environment(\.colorScheme, .dark) }
        }
        .configurationDisplayName(Text(L10n.t("widget.name")))
        .description(Text(L10n.t("widget.description")))
        .supportedFamilies([.systemMedium, .systemLarge])
    }
}

/// The accent colors of the widget: always the default, blue on dark.
let widgetAccent = AccentPalette.standard

// MARK: - Timeline

struct RemoteEntry: TimelineEntry {
    let date: Date
    /// Nil: no saved devices.
    let deviceId: String?
    let deviceName: String
    let deviceCount: Int
    /// "Cannot reach <name>" after a failed press.
    let message: String?
    var isPlaceholder = false
    /// The device chosen in Edit Widget. "Next device" keeps its choice per configured device.
    var configuredId: String?

    static func placeholder(_ date: Date = Date()) -> RemoteEntry {
        RemoteEntry(
            date: date,
            deviceId: nil,
            deviceName: L10n.t("widget.placeholderName"),
            deviceCount: 1,
            message: nil,
            isPlaceholder: true
        )
    }
}

struct RemoteProvider: AppIntentTimelineProvider {
    func placeholder(in context: Context) -> RemoteEntry {
        .placeholder()
    }

    func snapshot(for configuration: RemoteWidgetIntent, in context: Context) async -> RemoteEntry {
        entries(for: configuration, now: Date()).first ?? .placeholder()
    }

    func timeline(for configuration: RemoteWidgetIntent, in context: Context) async -> Timeline<RemoteEntry> {
        // The app and the intents reload the timeline when something changes.
        Timeline(entries: entries(for: configuration, now: Date()), policy: .never)
    }

    /// One entry, and a second one without the failure line when it should go away.
    private func entries(for configuration: RemoteWidgetIntent, now: Date) -> [RemoteEntry] {
        let configuredId = configuration.device?.id
        guard let found = WidgetStatus.device(configuredId: configuredId) else {
            return [RemoteEntry(date: now, deviceId: nil, deviceName: "", deviceCount: 0, message: nil)]
        }
        let box = found.box
        let count = found.count
        let base = RemoteEntry(
            date: now, deviceId: box.id, deviceName: box.name, deviceCount: count, message: nil, configuredId: configuredId
        )
        guard let failure = WidgetStatus.recentFailure(box.id, now: now) else { return [base] }
        let failed = RemoteEntry(
            date: now,
            deviceId: box.id,
            deviceName: box.name,
            deviceCount: count,
            message: L10n.f("widget.cannotReach %@", box.name),
            configuredId: configuredId
        )
        let cleared = RemoteEntry(
            date: failure.addingTimeInterval(WidgetStatus.failureSeconds),
            deviceId: box.id,
            deviceName: box.name,
            deviceCount: count,
            message: nil,
            configuredId: configuredId
        )
        return [failed, cleared]
    }
}

// MARK: - Views

struct RemoteWidgetView: View {
    let entry: RemoteEntry
    @Environment(\.widgetFamily) private var family

    var body: some View {
        if entry.deviceId == nil && !entry.isPlaceholder {
            emptyState
        } else if family == .systemLarge {
            large
        } else {
            medium
        }
    }

    /// The id that the buttons send to. A placeholder sends nowhere.
    private var deviceId: String { entry.deviceId ?? "" }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tv.and.mediabox")
                .font(.system(size: 30))
                .foregroundColor(widgetAccent.accent)
            Text(L10n.t("widget.empty"))
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Theme.onSurface)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .widgetURL(SharedConfig.openAppURL)
    }

    /// The medium widget lays out from the space it really has: the content area is about
    /// 116 to 138 pt high, depending on the phone.
    private var medium: some View {
        GeometryReader { geometry in
            let height = geometry.size.height
            let dpad = min(height, geometry.size.width * 0.46)
            // Header about 24 pt, message line about 16 pt, spacing 8 between rows.
            let used: CGFloat = 24 + 8 + (entry.message == nil ? 0 : 16 + 8) + 8
            let button = min(42, max(28, (height - used - 8) / 2))
            HStack(spacing: 14) {
                WidgetDPad(deviceId: deviceId, side: dpad)
                VStack(alignment: .leading, spacing: 8) {
                    header
                    messageLine
                    Spacer(minLength: 0)
                    HStack(spacing: 10) {
                        WidgetKeyButton(key: .power, deviceId: deviceId, side: button, tint: Theme.power)
                        WidgetOpenAppButton(side: button)
                    }
                    HStack(spacing: 10) {
                        WidgetKeyButton(key: .back, deviceId: deviceId, side: button)
                        WidgetKeyButton(key: .home, deviceId: deviceId, side: button)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(width: geometry.size.width, height: height)
        }
    }

    private var large: some View {
        VStack(spacing: 10) {
            header
            messageLine
            Spacer(minLength: 0)
            WidgetDPad(deviceId: deviceId, side: 196)
            Spacer(minLength: 0)
            HStack(spacing: 18) {
                WidgetKeyButton(key: .back, deviceId: deviceId, side: 52)
                WidgetKeyButton(key: .home, deviceId: deviceId, side: 52)
                WidgetKeyButton(key: .power, deviceId: deviceId, side: 52, tint: Theme.power)
                WidgetOpenAppButton(side: 52)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image(systemName: "tv")
                .font(.caption.weight(.semibold))
                .foregroundColor(widgetAccent.accent)
            Text(entry.deviceName)
                .font(.footnote.weight(.semibold))
                .foregroundColor(Theme.onSurface)
                .lineLimit(1)
            Spacer(minLength: 4)
            if entry.deviceCount > 1 {
                Button(intent: NextDeviceIntent(configuredId: entry.configuredId, shownId: deviceId)) {
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.caption.weight(.bold))
                        .foregroundColor(Theme.muted)
                        .frame(width: 28, height: 24)
                        .background(Capsule().fill(Color.white.opacity(0.08)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.t("widget.nextDevice"))
            }
        }
    }

    @ViewBuilder
    private var messageLine: some View {
        if let message = entry.message {
            Text(message)
                .font(.caption2.weight(.semibold))
                .foregroundColor(Theme.warn)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
    }
}

/// The widget background: the gradient and the accent light of the remote screen.
struct WidgetBackground: View {
    var body: some View {
        ZStack {
            LinearGradient(colors: [Theme.backgroundTop, Theme.backgroundBottom], startPoint: .top, endPoint: .bottom)
            RadialGradient(
                colors: [widgetAccent.accent.opacity(0.1), widgetAccent.accent.opacity(0)],
                center: .top,
                startRadius: 0,
                endRadius: 220
            )
        }
    }
}

/// A raised body for widget buttons. Widgets do not animate presses, so it has no press state.
private struct WidgetRaised<S: InsettableShape>: View {
    let shape: S

    var body: some View {
        shape
            .fill(LinearGradient(colors: [Theme.bodyTop, Theme.bodyBottom], startPoint: .top, endPoint: .bottom))
            .overlay(
                shape.strokeBorder(
                    LinearGradient(colors: [Color.white.opacity(0.12), Color.white.opacity(0)], startPoint: .top, endPoint: .bottom),
                    lineWidth: 1
                )
            )
            .shadow(color: Color.black.opacity(0.45), radius: 4, x: 0, y: 3)
    }
}

struct WidgetKeyButton: View {
    let key: RemoteKey
    let deviceId: String
    let side: CGFloat
    var tint: Color = Theme.onSurface

    var body: some View {
        Button(intent: PressKeyIntent(key: key, deviceId: deviceId)) {
            Image(systemName: key.symbol)
                .font(.system(size: side * 0.4, weight: .semibold))
                .foregroundColor(tint)
                .frame(width: side, height: side)
                .background(WidgetRaised(shape: Circle()))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(key.title)
    }
}

/// Opens the app.
struct WidgetOpenAppButton: View {
    let side: CGFloat

    var body: some View {
        Link(destination: SharedConfig.openAppURL) {
            Image(systemName: "arrow.up.forward.app")
                .font(.system(size: side * 0.4, weight: .semibold))
                .foregroundColor(widgetAccent.accent)
                .frame(width: side, height: side)
                .background(WidgetRaised(shape: Circle()))
        }
        .accessibilityLabel(L10n.t("widget.openApp"))
    }
}

/// The D-pad ring with OK in the center.
struct WidgetDPad: View {
    let deviceId: String
    let side: CGFloat

    var body: some View {
        ZStack {
            WidgetRaised(shape: Circle())
            arrow(.up, dx: 0, dy: -1)
            arrow(.down, dx: 0, dy: 1)
            arrow(.left, dx: -1, dy: 0)
            arrow(.right, dx: 1, dy: 0)
            Button(intent: PressKeyIntent(key: .center, deviceId: deviceId)) {
                Text(L10n.t("remote.ok"))
                    .font(.system(size: side * 0.12, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: side * 0.4, height: side * 0.4)
                    .background(
                        Circle()
                            .fill(LinearGradient(colors: [widgetAccent.okTop, widgetAccent.okBottom], startPoint: .top, endPoint: .bottom))
                            .shadow(color: widgetAccent.accent.opacity(0.3), radius: 8)
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(RemoteKey.center.title)
        }
        .frame(width: side, height: side)
    }

    private func arrow(_ key: RemoteKey, dx: CGFloat, dy: CGFloat) -> some View {
        Button(intent: PressKeyIntent(key: key, deviceId: deviceId)) {
            Image(systemName: key.symbol)
                .font(.system(size: side * 0.12, weight: .bold))
                .foregroundColor(Theme.onSurface)
                .frame(width: side * 0.3, height: side * 0.3)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(key.title)
        .offset(x: dx * side * 0.35, y: dy * side * 0.35)
    }
}
