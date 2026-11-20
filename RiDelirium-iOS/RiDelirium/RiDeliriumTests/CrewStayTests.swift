//
//  CrewStayTests.swift
//  RiDeliriumTests
//

import XCTest
@testable import RiDelirium

final class CrewStayTests: XCTestCase {
    private var calendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Europe/Sofia")!
        return calendar
    }()

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    func testPresetsAreExactEndTimes() {
        let now = date(16, 10, 12)
        XCTAssertEqual(Stay.Preset.twoHours.until(from: now, calendar: calendar), date(16, 12, 12))
        XCTAssertEqual(Stay.Preset.fourHours.until(from: now, calendar: calendar), date(16, 14, 12))
        XCTAssertEqual(Stay.Preset.tonight.until(from: now, calendar: calendar), date(16, 23, 59))
    }

    func testClampDropsSecondsAndStaysWithinThirtyDays() {
        let now = date(16, 10, 12)
        XCTAssertEqual(Stay.clamp(now.addingTimeInterval(-600), now: now, calendar: calendar), date(16, 10, 13))
        XCTAssertEqual(Stay.clamp(date(16, 13, 45).addingTimeInterval(30), now: now, calendar: calendar), date(16, 13, 45))
        XCTAssertEqual(Stay.clamp(now.addingTimeInterval(40 * 86_400), now: now, calendar: calendar), now.addingTimeInterval(30 * 86_400))
    }

    func testLabels() {
        let now = date(16, 10, 12)
        XCTAssertNil(Stay.timeLeft(until: nil, now: now, calendar: calendar))
        XCTAssertEqual(Stay.timeLeft(until: date(16, 13, 45), now: now, calendar: calendar), "UNTIL 13:45")
        XCTAssertEqual(Stay.timeLeft(until: date(17, 9), now: now, calendar: calendar), "UNTIL TOMORROW 09:00")
        XCTAssertEqual(Stay.timeLeft(until: date(21, 18), now: now, calendar: calendar), "UNTIL SAT 21 18:00")
        XCTAssertEqual(Stay.timeLeft(until: now.addingTimeInterval(-1), now: now, calendar: calendar), "ENDED")
        XCTAssertEqual(Stay.duration(from: now, to: date(16, 13, 45)), "3 H 33 MIN")
        XCTAssertEqual(Stay.duration(from: now, to: date(16, 10, 57)), "45 MIN")
        XCTAssertEqual(Stay.duration(from: now, to: date(18, 14, 12)), "2 D 4 H")
        XCTAssertEqual(Stay.sharing(until: date(16, 13, 45), now: now, calendar: calendar), "SHARING UNTIL 13:45 · 3 H 33 MIN LEFT")
    }
}
