import CoreMotion
import Foundation

/// Turns gyroscope rotation speed into cursor movement in whole pixels.
///
/// The phone points at the TV with the screen up:
/// - Turn left or right (rotation about axis z): dx = -z * speed * dt.
/// - Tilt up or down (rotation about axis x): dy = -x * speed * dt.
struct GyroMapper {
    /// Pixels per radian of rotation at speed 1.0.
    static let pixelsPerRadian = 900.0
    /// Rotation speeds below this value (radians per second) are ignored.
    static let deadZone = 0.03

    private var remainderX = 0.0
    private var remainderY = 0.0

    mutating func reset() {
        remainderX = 0
        remainderY = 0
    }

    mutating func map(rateX: Double, rateZ: Double, dt: Double, speed: Double) -> (dx: Int, dy: Int) {
        let x = abs(rateX) < Self.deadZone ? 0 : rateX
        let z = abs(rateZ) < Self.deadZone ? 0 : rateZ
        let scale = Self.pixelsPerRadian * speed * dt
        let fx = -z * scale + remainderX
        let fy = -x * scale + remainderY
        let dx = Int(fx.rounded(.towardZero))
        let dy = Int(fy.rounded(.towardZero))
        remainderX = fx - Double(dx)
        remainderY = fy - Double(dy)
        return (dx, dy)
    }
}

/// Reads the gyroscope at about 100 Hz while it runs. Callbacks run on the main queue.
final class GyroPointer {
    var speed = 1.0
    var onMove: ((Int, Int) -> Void)?

    private let manager = CMMotionManager()
    private var mapper = GyroMapper()
    private var lastTimestamp: TimeInterval?

    var isAvailable: Bool { manager.isDeviceMotionAvailable }
    var isRunning: Bool { manager.isDeviceMotionActive }

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        mapper.reset()
        lastTimestamp = nil
        manager.deviceMotionUpdateInterval = 1.0 / 100.0
        manager.startDeviceMotionUpdates(to: OperationQueue.main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            var dt = 0.0
            if let last = self.lastTimestamp {
                dt = min(max(motion.timestamp - last, 0), 0.05)
            }
            self.lastTimestamp = motion.timestamp
            let rate = motion.rotationRate
            let delta = self.mapper.map(rateX: rate.x, rateZ: rate.z, dt: dt, speed: self.speed)
            if delta.dx != 0 || delta.dy != 0 {
                self.onMove?(delta.dx, delta.dy)
            }
        }
    }

    func stop() {
        if manager.isDeviceMotionActive {
            manager.stopDeviceMotionUpdates()
        }
    }
}
