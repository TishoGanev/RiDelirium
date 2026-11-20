import XCTest
@testable import RiDelirium

final class StatsRangeTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        return calendar
    }
    private func date(_ y: Int, _ m: Int, _ d: Int, _ h: Int = 12) -> Date {
        calendar.date(from: DateComponents(year: y, month: m, day: d, hour: h))!
    }
    private let units = Units(metric: true)

    func testCutoffsCountCalendarDaysIncludingToday() {
        let now = date(2026, 11, 2, 15)
        XCTAssertEqual(StatsRange.today.cutoff(now: now, calendar: calendar), date(2026, 11, 2, 0))
        XCTAssertEqual(StatsRange.days3.cutoff(now: now, calendar: calendar), date(2026, 10, 31, 0))
        XCTAssertEqual(StatsRange.days7.cutoff(now: now, calendar: calendar), date(2026, 10, 27, 0))
        XCTAssertEqual(StatsRange.month.cutoff(now: now, calendar: calendar), date(2026, 10, 4, 0))
        XCTAssertEqual(StatsRange.season.cutoff(now: now, calendar: calendar), date(2026, 7, 1, 0))
        XCTAssertNil(StatsRange.all.cutoff(now: now, calendar: calendar))
    }

    func testContains() {
        let now = date(2026, 11, 2, 15)
        XCTAssertTrue(StatsRange.today.contains(date(2026, 11, 2, 0), now: now, calendar: calendar))
        XCTAssertFalse(StatsRange.today.contains(date(2026, 11, 1, 23), now: now, calendar: calendar))
        XCTAssertTrue(StatsRange.days3.contains(date(2026, 10, 31, 8), now: now, calendar: calendar))
        XCTAssertFalse(StatsRange.days3.contains(date(2026, 10, 30, 8), now: now, calendar: calendar))
        XCTAssertTrue(StatsRange.all.contains(date(2001, 1, 1), now: now, calendar: calendar))
    }

    func testSeasonStartsInJulyOfThePreviousYearBeforeJuly() {
        XCTAssertEqual(StatsRange.season.cutoff(now: date(2027, 3, 1), calendar: calendar), date(2026, 7, 1, 0))
    }

    private func day(_ d: Int, runs: Int = 8, vertical: Double = 4000, top: Double = 20) -> DayMetrics {
        DayMetrics(date: date(2026, 1, d), runs: runs, downhillDistance: 20_000, vertical: vertical, topSpeed: top, ridingTime: 7200, totalTime: 18_000)
    }

    func testTrendComparesFirstAndLastWithTwoDays() {
        let facts = RideInsightFacts(period: [day(1, top: 20), day(2, top: 25)], rangeTitle: "7 DAYS", units: units)
        XCTAssertTrue(facts.isPeriod)
        XCTAssertTrue(facts.bullets.contains { $0.contains("Top speed is 25% higher") }, "\(facts.bullets)")
    }

    func testTrendUsesHalvesWithFourDays() {
        let days = [day(1, vertical: 4000), day(2, vertical: 4000), day(3, vertical: 2000), day(4, vertical: 2000)]
        let facts = RideInsightFacts(period: days, rangeTitle: "1 MONTH", units: units)
        XCTAssertTrue(facts.bullets.contains { $0.contains("Descent per day is 50% lower") }, "\(facts.bullets)")
    }

    func testSteadyNumbersSaySo() {
        let facts = RideInsightFacts(period: [day(1), day(2)], rangeTitle: "7 DAYS", units: units)
        XCTAssertTrue(facts.bullets.contains { $0.contains("steady") }, "\(facts.bullets)")
    }

    func testSingleDayHasNoTrend() {
        let facts = RideInsightFacts(period: [day(1)], rangeTitle: "TODAY", units: units)
        XCTAssertTrue(facts.hasRuns)
        XCTAssertTrue(facts.bullets.contains { $0.contains("no trend") })
    }

    func testNoRunsMeansNothingToWrite() {
        XCTAssertFalse(RideInsightFacts(period: [day(1, runs: 0)], rangeTitle: "TODAY", units: units).hasRuns)
        XCTAssertFalse(RideInsightFacts(period: [], rangeTitle: "TODAY", units: units).hasRuns)
    }
}
