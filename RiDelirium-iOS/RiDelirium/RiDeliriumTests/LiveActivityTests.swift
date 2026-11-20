import XCTest
@testable import RiDelirium

final class LiveActivityTests: XCTestCase {
    /// What the server sends for "wait for me": only the request, no ride fields.
    func testPushedStateWithOnlyARequestDecodes() throws {
        let json = """
        {"request":{"id":"ab12","fromName":"Maya","initials":"MA","colorIndex":1,"sentAt":1792000000,"count":2,"distanceM":340}}
        """.data(using: .utf8)!
        let state = try JSONDecoder().decode(RideActivityAttributes.ContentState.self, from: json)
        XCTAssertEqual(state.request?.fromName, "Maya")
        XCTAssertEqual(state.request?.count, 2)
        XCTAssertEqual(state.request?.distanceM, 340)
        XCTAssertEqual(state.distance, 0)
        XCTAssertTrue(state.friends.isEmpty)
        XCTAssertNil(state.waiting)
    }

    func testStateRoundTrips() throws {
        var state = RideActivityAttributes.ContentState()
        state.distance = 12_400
        state.heartRate = 142
        state.friends = [LiveFriend(initials: "TO", colorIndex: 0, distanceM: 340, bearingDeg: 40, status: .riding, speed: 34, name: "Todor")]
        state.waiting = LiveWaiting(id: "x", name: "Maya", initials: "MA", colorIndex: 1, distanceM: 120, bearingDeg: nil, speed: 34, etaSec: 40, progress: 0.6, arrived: false)
        let decoded = try JSONDecoder().decode(RideActivityAttributes.ContentState.self, from: JSONEncoder().encode(state))
        XCTAssertEqual(decoded, state)
    }

    /// The server computes the colour of a friend from the id; the phone must agree.
    func testColorIndexIsStableAndInRange() {
        let friend = Friend(id: "3F2504E0-4F89-41D3-9A0C-0305E82C3301", displayName: "A", initials: "A")
        XCTAssertEqual(friend.colorIndex, 0)
        XCTAssertTrue((0..<5).contains(Friend(id: "ZZZ", displayName: "B", initials: "B").colorIndex))
    }
}
