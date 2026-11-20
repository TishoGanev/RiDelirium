import XCTest
@testable import RiDelirium

final class RideTogetherTests: XCTestCase {
    func testAutoDashFormatsTypedCodes() {
        XCTAssertEqual(RideTogetherSheet.autoDash("w858b7ba"), "W858-B7BA")
        XCTAssertEqual(RideTogetherSheet.autoDash("W858"), "W858")
        XCTAssertEqual(RideTogetherSheet.autoDash("w858-b7"), "W858-B7")
        XCTAssertEqual(RideTogetherSheet.autoDash("w858 b7ba extra"), "W858-B7BA")
        XCTAssertEqual(RideTogetherSheet.autoDash(""), "")
    }

    func testShortDistance() {
        XCTAssertEqual(Units(metric: true).shortDistance(meters: 340), "340 M")
        XCTAssertEqual(Units(metric: true).shortDistance(meters: 1240), "1.2 KM")
        XCTAssertEqual(Units(metric: false).shortDistance(meters: 100), "328 FT")
    }
}
