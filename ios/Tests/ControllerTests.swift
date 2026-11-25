import CoreGraphics
import XCTest
@testable import TVBoxRemote

final class StickMapperTests: XCTestCase {
    func testCenterHoldsNoArrow() {
        XCTAssertEqual(StickMapper.arrows(x: 0, y: 0), [])
    }

    func testBelowHalfTravelHoldsNoArrow() {
        XCTAssertEqual(StickMapper.arrows(x: 0.49, y: 0), [])
        XCTAssertEqual(StickMapper.arrows(x: 0.3, y: -0.3), []) // length 0.42
    }

    func testStraightDirections() {
        XCTAssertEqual(StickMapper.arrows(x: 1, y: 0), [.right])
        XCTAssertEqual(StickMapper.arrows(x: -1, y: 0), [.left])
        // Screen y grows down: negative y is up.
        XCTAssertEqual(StickMapper.arrows(x: 0, y: -1), [.up])
        XCTAssertEqual(StickMapper.arrows(x: 0, y: 1), [.down])
        XCTAssertEqual(StickMapper.arrows(x: 0.5, y: 0), [.right])
    }

    func testDiagonalsHoldTwoArrows() {
        let d: CGFloat = 0.7
        XCTAssertEqual(StickMapper.arrows(x: d, y: -d), [.up, .right])
        XCTAssertEqual(StickMapper.arrows(x: -d, y: -d), [.up, .left])
        XCTAssertEqual(StickMapper.arrows(x: -d, y: d), [.down, .left])
        XCTAssertEqual(StickMapper.arrows(x: d, y: d), [.down, .right])
    }

    func testSectorEdgesAt22Point5Degrees() {
        // 20 degrees above the right axis is still "right"; 25 degrees is "up and right".
        func point(_ degrees: Double) -> (CGFloat, CGFloat) {
            let radians = degrees * .pi / 180
            return (CGFloat(cos(radians)), CGFloat(-sin(radians)))
        }
        let (x20, y20) = point(20)
        let (x25, y25) = point(25)
        let (x340, y340) = point(340)
        XCTAssertEqual(StickMapper.arrows(x: x20, y: y20), [.right])
        XCTAssertEqual(StickMapper.arrows(x: x25, y: y25), [.up, .right])
        XCTAssertEqual(StickMapper.arrows(x: x340, y: y340), [.right])
    }

    func testDpadThresholdIsLower() {
        XCTAssertEqual(StickMapper.arrows(x: 0.3, y: 0, threshold: StickMapper.dpadThreshold), [.right])
        XCTAssertEqual(StickMapper.arrows(x: 0.2, y: 0, threshold: StickMapper.dpadThreshold), [])
    }

    func testDeflectionIsLimitedToLengthOne() {
        let inside = StickMapper.deflection(point: CGPoint(x: 120, y: 100), center: CGPoint(x: 100, y: 100), travel: 40)
        XCTAssertEqual(inside.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(inside.y, 0, accuracy: 0.0001)

        let outside = StickMapper.deflection(point: CGPoint(x: 100, y: 300), center: CGPoint(x: 100, y: 100), travel: 40)
        XCTAssertEqual(outside.x, 0, accuracy: 0.0001)
        XCTAssertEqual(outside.y, 1, accuracy: 0.0001)

        XCTAssertEqual(StickMapper.deflection(point: .zero, center: CGPoint(x: 5, y: 5), travel: 0), .zero)
    }
}

final class ControllerLayoutTests: XCTestCase {
    private let area = CGSize(width: 844, height: 390)

    func testClampKeepsCenterInside() {
        let center = ControllerLayout.clamp(center: CGPoint(x: 0.5, y: 0.5), controlSize: CGSize(width: 64, height: 64), areaSize: area)
        XCTAssertEqual(center.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(center.y, 0.5, accuracy: 0.0001)
    }

    func testClampMovesControlAwayFromEdges() {
        let size = CGSize(width: 100, height: 100)
        let low = ControllerLayout.clamp(center: CGPoint(x: 0, y: 0), controlSize: size, areaSize: area)
        XCTAssertEqual(low.x * area.width, 50, accuracy: 0.001)
        XCTAssertEqual(low.y * area.height, 50, accuracy: 0.001)

        let high = ControllerLayout.clamp(center: CGPoint(x: 1.2, y: 1), controlSize: size, areaSize: area)
        XCTAssertEqual(high.x * area.width, area.width - 50, accuracy: 0.001)
        XCTAssertEqual(high.y * area.height, area.height - 50, accuracy: 0.001)
    }

    func testControlLargerThanAreaIsCentered() {
        let center = ControllerLayout.clamp(center: CGPoint(x: 0.1, y: 0.9), controlSize: CGSize(width: 900, height: 400), areaSize: area)
        XCTAssertEqual(center.x, 0.5, accuracy: 0.0001)
        XCTAssertEqual(center.y, 0.5, accuracy: 0.0001)
    }

    func testEmptyAreaGivesCenter() {
        let center = ControllerLayout.clamp(center: CGPoint(x: 0.1, y: 0.9), controlSize: CGSize(width: 10, height: 10), areaSize: .zero)
        XCTAssertEqual(center, CGPoint(x: 0.5, y: 0.5))
    }

    func testScaleIsClamped() {
        XCTAssertEqual(ControllerLayout.clampScale(0.1), 0.6)
        XCTAssertEqual(ControllerLayout.clampScale(1.0), 1.0)
        XCTAssertEqual(ControllerLayout.clampScale(5), 1.6)
    }

    /// The default controls must not cover each other. Round controls are compared as circles,
    /// because the diamond of A, B, X, Y has overlapping frames but not overlapping buttons.
    func testDefaultControlsDoNotOverlap() {
        let layout = ControllerLayout.standard
        let controls = ControllerControl.allCases
        func isRound(_ control: ControllerControl) -> Bool {
            switch control.kind {
            case .face, .stick, .dpad: return true
            case .shoulder, .pill: return false
            }
        }
        for (i, first) in controls.enumerated() {
            for second in controls[(i + 1)...] {
                let a = layout.frame(of: first, in: area)
                let b = layout.frame(of: second, in: area)
                if isRound(first) && isRound(second) {
                    let distance = hypot(a.midX - b.midX, a.midY - b.midY)
                    XCTAssertGreaterThanOrEqual(distance, a.width / 2 + b.width / 2, "\(first) and \(second) overlap")
                } else {
                    XCTAssertFalse(a.intersects(b), "\(first) \(a) and \(second) \(b) overlap")
                }
            }
        }
    }

    func testFrameUsesClampedScale() {
        var layout = ControllerLayout.standard
        layout.set(ControlPlacement(x: 0.5, y: 0.5, scale: 9), for: .a)
        let frame = layout.frame(of: .a, in: area)
        XCTAssertEqual(frame.width, 64 * 1.6, accuracy: 0.001)
    }

    func testLayoutRoundTripsThroughJSON() throws {
        var layout = ControllerLayout.standard
        layout.set(ControlPlacement(x: 0.25, y: 0.75, scale: 1.2), for: .leftStick)
        let data = try JSONEncoder().encode(layout)
        let decoded = try JSONDecoder().decode(ControllerLayout.self, from: data)
        XCTAssertEqual(decoded, layout)
    }

    func testMissingPlacementFallsBackToDefault() {
        let layout = ControllerLayout(placements: [:])
        XCTAssertEqual(layout.placement(.y), ControllerLayout.standard.placement(.y))
    }

    @MainActor
    func testEditorMoveStaysInside() {
        let model = ControllerModel(editing: true, layout: .standard)
        model.areaSize = area
        model.move(.a, center: CGPoint(x: -500, y: 2000))
        let frame = model.frame(of: .a)
        XCTAssertEqual(frame.minX, 0, accuracy: 0.001)
        XCTAssertEqual(frame.maxY, area.height, accuracy: 0.001)
    }
}
