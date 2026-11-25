import CoreMotion
import SwiftUI

/// The tilt of the phone from the gravity sensor, for the sheen on the buttons
/// ("dynamic reflection" in docs/UI_SPEC.md). Values are -1...1 on each axis:
/// x < 0 puts the light to the left, y < 0 puts it to the top.
///
/// Updates arrive on the main queue at about 30 Hz, only between `start()` and `stop()`.
final class TiltModel: ObservableObject {
    /// A model that never starts. The sheen of buttons outside the remote screen uses it.
    static let idle = TiltModel()

    @Published private(set) var point: CGPoint = .zero

    private lazy var manager = CMMotionManager()
    private var running = false

    func start() {
        guard !running, manager.isDeviceMotionAvailable else { return }
        running = true
        manager.deviceMotionUpdateInterval = 1.0 / 30.0
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self, self.running, let gravity = motion?.gravity else { return }
            let next = CGPoint(x: Self.clamp(-gravity.x), y: Self.clamp(gravity.y))
            // Skip tiny changes, so the buttons do not redraw for sensor noise.
            guard abs(next.x - self.point.x) > 0.01 || abs(next.y - self.point.y) > 0.01 else { return }
            withAnimation(Motion.highlight) {
                self.point = next
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopDeviceMotionUpdates()
    }

    private static func clamp(_ value: Double) -> CGFloat {
        CGFloat(min(1, max(-1, value)))
    }
}

private struct TiltModelKey: EnvironmentKey {
    static let defaultValue = TiltModel.idle
}

extension EnvironmentValues {
    /// The tilt source for the sheen. The remote screen sets a running model.
    var tiltModel: TiltModel {
        get { self[TiltModelKey.self] }
        set { self[TiltModelKey.self] = newValue }
    }
}
