import XCTest
@testable import TVBoxRemote

final class GyroMapperTests: XCTestCase {
    func testTurnLeftMovesCursorLeft() {
        var mapper = GyroMapper()
        // Positive z: the phone turns left (counterclockwise seen from above).
        let delta = mapper.map(rateX: 0, rateZ: 1.0, dt: 0.0625, speed: 1.0)
        XCTAssertEqual(delta.dx, -56) // 900 * 0.0625 = 56.25
        XCTAssertEqual(delta.dy, 0)
    }

    func testTiltUpMovesCursorUp() {
        var mapper = GyroMapper()
        // Positive x: the top of the phone tilts up.
        let delta = mapper.map(rateX: 1.0, rateZ: 0, dt: 0.0625, speed: 2.0)
        XCTAssertEqual(delta.dx, 0)
        XCTAssertEqual(delta.dy, -112) // 2 * 56.25 = 112.5
    }

    func testDeadZoneIgnoresDrift() {
        var mapper = GyroMapper()
        for _ in 0..<1000 {
            let delta = mapper.map(rateX: 0.02, rateZ: -0.02, dt: 0.01, speed: 3.0)
            XCTAssertEqual(delta.dx, 0)
            XCTAssertEqual(delta.dy, 0)
        }
    }

    func testFractionsAccumulate() {
        var mapper = GyroMapper()
        // 0.5 pixel per sample to the right: one pixel every second sample.
        let rate = -0.5 / (GyroMapper.pixelsPerRadian * 0.01)
        var total = 0
        for _ in 0..<10 {
            total += mapper.map(rateX: 0, rateZ: rate, dt: 0.01, speed: 1.0).dx
        }
        // Without the remainder, every sample would give 0. Allow for rounding.
        XCTAssertTrue((4...5).contains(total), "total was \(total)")
    }

    func testIPv4Validation() {
        XCTAssertTrue(AppModel.isValidIPv4("192.168.1.20"))
        XCTAssertFalse(AppModel.isValidIPv4("192.168.1"))
        XCTAssertFalse(AppModel.isValidIPv4("192.168.1.256"))
        XCTAssertFalse(AppModel.isValidIPv4("a.b.c.d"))
    }
}
