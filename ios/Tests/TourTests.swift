import CoreGraphics
import XCTest
@testable import TVBoxRemote

final class TourFlowTests: XCTestCase {
    private func step(
        _ tour: Tour?, _ screen: TourScreen, boxes: Int = 0, emptyForLong: Bool = false,
        dialog: Bool = false, mouse: MouseTour? = nil
    ) -> TourStep? {
        TourFlow.step(tour: tour, mouseTour: mouse, screen: screen, wifiBoxes: boxes, emptyForLong: emptyForLong, dialogOpen: dialog)
    }

    func testStartsOnlyOnTheFirstLaunch() {
        XCTAssertEqual(TourFlow.initial(tourDone: false, hasPairedBox: false), .pair)
        XCTAssertNil(TourFlow.initial(tourDone: true, hasPairedBox: false))
        XCTAssertNil(TourFlow.initial(tourDone: false, hasPairedBox: true))
    }

    func testPairStepsFollowTheScreens() {
        XCTAssertEqual(step(.pair, .remote), .openDevices)
        XCTAssertEqual(step(.pair, .devices, boxes: 2), .pickBox)
        XCTAssertNil(step(.pair, .devices, boxes: 0, emptyForLong: false))
        XCTAssertEqual(step(.pair, .devices, boxes: 0, emptyForLong: true), .addByIp)
        XCTAssertEqual(step(.pair, .devices, boxes: 1, emptyForLong: true), .pickBox)
    }

    func testNoCardWhileADialogIsOpen() {
        XCTAssertNil(step(.pair, .devices, boxes: 1, dialog: true))
        XCTAssertNil(step(.allSet, .remote, dialog: true))
    }

    func testConnectingEndsWithAllSet() {
        XCTAssertEqual(TourFlow.onConnected(.pair), .allSet)
        XCTAssertNil(TourFlow.onConnected(nil))
        XCTAssertEqual(step(.allSet, .remote), .allSet)
        XCTAssertNil(step(.allSet, .devices))
    }

    func testMouseWalkThrough() {
        XCTAssertEqual(step(nil, .mouse, mouse: .choose), .mouseChoose)
        XCTAssertNil(step(nil, .remote, mouse: .choose))
        XCTAssertEqual(step(.pair, .remote, mouse: .choose), .openDevices)
    }
}

final class CoachLayoutTests: XCTestCase {
    private let screen = CGSize(width: 390, height: 844)

    private func inside(_ r: CGRect) -> Bool {
        r.minX >= 0 && r.minY >= 0 && r.maxX <= screen.width && r.maxY <= screen.height
    }

    func testCardGoesBelowATargetAtTheTop() {
        let target = CGRect(x: 20, y: 20, width: 176, height: 48)
        let p = CoachLayout.place(target: target, screen: screen, card: CGSize(width: 340, height: 150), edge: 16, gap: 64)
        XCTAssertGreaterThanOrEqual(p.card.minY, target.maxY + 64 - 0.5)
        XCTAssertFalse(p.card.intersects(target))
        XCTAssertTrue(inside(p.card))
        guard let arrow = p.arrow else { return XCTFail("The card has no arrow") }
        XCTAssertLessThan(arrow.from.y, p.card.minY)
        XCTAssertGreaterThan(arrow.to.y, target.maxY)
    }

    func testCardGoesAboveATargetAtTheBottom() {
        let target = CGRect(x: 26, y: 634, width: 338, height: 88)
        let p = CoachLayout.place(target: target, screen: screen, card: CGSize(width: 340, height: 160), edge: 16, gap: 64)
        XCTAssertLessThanOrEqual(p.card.maxY, target.minY)
        XCTAssertFalse(p.card.intersects(target))
    }

    func testCardNeverCoversATallTarget() {
        let target = CGRect(x: 10, y: 250, width: 370, height: 380)
        let p = CoachLayout.place(target: target, screen: screen, card: CGSize(width: 340, height: 220), edge: 16, gap: 64)
        XCTAssertFalse(p.card.intersects(target))
        XCTAssertTrue(inside(p.card))
    }

    func testCenteredCardHasNoArrow() {
        let p = CoachLayout.place(target: nil, screen: screen, card: CGSize(width: 340, height: 300), edge: 16, gap: 64)
        XCTAssertNil(p.arrow)
        XCTAssertEqual(p.card.midY, screen.height / 2, accuracy: 1)
    }
}
