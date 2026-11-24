import XCTest
@testable import RiDelirium

final class RideSportsTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_792_000_000)

    private func point(_ second: Double, sport: Sport?, bpm: Double? = nil) -> TrackPoint {
        let p = TrackPoint(lat: 42, lon: 23, altitude: 2000, speed: 1, timestamp: base.addingTimeInterval(second), session: 1)
        p.sport = sport?.rawValue
        p.heartRate = bpm
        return p
    }

    func testTimelineKeepsOrderAndDominantSport() {
        let points = (0..<10).map { point(Double($0) * 10, sport: $0 < 6 ? .board : .hike) }
        let timeline = SportTimeline(points: points, fallback: .ski)
        XCTAssertEqual(timeline.distinct, [.board, .hike])
        XCTAssertEqual(timeline.dominant, .board)
        XCTAssertEqual(timeline.sport(at: 8, fallback: .ski), .hike)
    }

    func testPointsWithoutSportUseTheFallback() {
        let points = (0..<4).map { point(Double($0), sport: nil) }
        XCTAssertEqual(SportTimeline(points: points, fallback: .bike).distinct, [.bike])
    }

    func testMixedRideTellsTheModelAboutBothSports() {
        let points = (0..<20).map { point(Double($0) * 10, sport: $0 < 10 ? .board : .hike) }
        let run = Segment(activity: .run, startIndex: 0, endIndex: 9, distance: 500, vertical: 100, maxSpeed: 12, duration: 90)
        let walk = Segment(activity: .run, startIndex: 10, endIndex: 19, distance: 500, vertical: 80, maxSpeed: 2, duration: 90)
        let facts = RideInsightFacts(analysis: RideAnalysis(segments: [run, walk]), points: points, placeName: "Test", units: Units(metric: true), sport: .board)
        XCTAssertEqual(facts.sports, [.board, .hike])
        XCTAssertTrue(facts.prompt.contains("Sports during this ride, in order: snowboarding"))
        XCTAssertTrue(facts.prompt.contains("then hiking"))
        XCTAssertTrue(facts.prompt.contains("(hiking)"))
        XCTAssertTrue(facts.coachBrief.contains("never apply one sport's advice to another"))
        XCTAssertTrue(facts.coachBrief.contains("peak G-force"))
        XCTAssertEqual(facts.coachRole, "outdoor sports coach")
        // Only the snowboard part feeds the snow measurements, and a walk is never "fastest".
        XCTAssertFalse(facts.bullets.contains { $0.contains("Descent 2 was your fastest") })
    }

    func testSingleSportRideKeepsItsOwnCoach() {
        let points = (0..<10).map { point(Double($0), sport: .hike) }
        let run = Segment(activity: .run, startIndex: 0, endIndex: 9, distance: 500, vertical: 100, maxSpeed: 2, duration: 9)
        let facts = RideInsightFacts(analysis: RideAnalysis(segments: [run]), points: points, placeName: "Test", units: Units(metric: true), sport: .board)
        XCTAssertEqual(facts.sports, [.hike])
        XCTAssertEqual(facts.coachRole, "hiking guide")
        XCTAssertFalse(facts.prompt.contains("Snow sport measurements"))
    }

    // MARK: Heart rate recovery

    private func restRide(startBPM: Double, afterMinute: Double) -> (RideAnalysis, [TrackPoint]) {
        var points: [TrackPoint] = []
        for second in stride(from: 0.0, through: 90, by: 5) {
            let bpm = second <= 10 ? startBPM : (second >= 55 && second <= 65 ? afterMinute : (startBPM + afterMinute) / 2)
            points.append(point(second, sport: .board, bpm: bpm))
        }
        let still = Segment(activity: .still, startIndex: 0, endIndex: points.count - 1, distance: 0, vertical: 0, maxSpeed: 0, duration: 90)
        return (RideAnalysis(segments: [still]), points)
    }

    func testRecoveryIsTheFallOverTheFirstMinute() {
        let (analysis, points) = restRide(startBPM: 170, afterMinute: 130)
        let recovery = HeartRateRecovery.compute(analysis: analysis, points: points)
        XCTAssertEqual(recovery?.rests.count, 1)
        XCTAssertEqual(recovery?.averageDropPerMinute ?? 0, 40, accuracy: 1)
        XCTAssertTrue(recovery?.bullet.contains("40 bpm") ?? false)
    }

    func testNoHeartRateMeansNoRecovery() {
        let (analysis, points) = restRide(startBPM: 170, afterMinute: 130)
        points.forEach { $0.heartRate = nil }
        XCTAssertNil(HeartRateRecovery.compute(analysis: analysis, points: points))
    }

    func testShortStopsAreNotJudged() {
        let (_, points) = restRide(startBPM: 170, afterMinute: 130)
        let short = Segment(activity: .still, startIndex: 0, endIndex: 5, distance: 0, vertical: 0, maxSpeed: 0, duration: 25)
        XCTAssertNil(HeartRateRecovery.compute(analysis: RideAnalysis(segments: [short]), points: points))
    }
}

final class HikeMetricsTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_792_000_000)

    /// 20 minutes of climbing (2 km, 300 m) then 20 minutes descending (2 km), slower in the second half.
    private func hike(withHeartRate: Bool) -> (RideAnalysis, [TrackPoint]) {
        let points = (0..<41).map { i -> TrackPoint in
            let p = TrackPoint(lat: 42, lon: 23, altitude: 1000, speed: 1, timestamp: base.addingTimeInterval(Double(i) * 60), session: 1)
            p.sport = Sport.hike.rawValue
            if withHeartRate { p.heartRate = i < 20 ? 140 : 120 }
            return p
        }
        let climb = Segment(activity: .lift, startIndex: 0, endIndex: 19, distance: 2000, vertical: 300, maxSpeed: 1.5, duration: 1200)
        let descent = Segment(activity: .run, startIndex: 20, endIndex: 40, distance: 1500, vertical: 300, maxSpeed: 1.5, duration: 1200)
        return (RideAnalysis(segments: [climb, descent]), points)
    }

    func testTempoIsPaceClimbRateAndFade() {
        let (analysis, points) = hike(withHeartRate: false)
        let metrics = HikeMetrics.compute(analysis: analysis, points: points)
        XCTAssertEqual(metrics.averagePace ?? 0, 2400.0 / 3500, accuracy: 0.001)
        XCTAssertEqual(metrics.climbRate ?? 0, 900, accuracy: 0.1)
        XCTAssertEqual(metrics.paceChange ?? 0, 33.3, accuracy: 0.5, "the second half covers less ground in the same time")
        XCTAssertNil(metrics.heart)
    }

    func testHeartRateAveragesAndHalves() {
        let (analysis, points) = hike(withHeartRate: true)
        let heart = HikeMetrics.compute(analysis: analysis, points: points).heart
        XCTAssertEqual(heart?.maximum, 140)
        XCTAssertEqual(heart?.climbs ?? 0, 140, accuracy: 0.1)
        XCTAssertEqual(heart?.descents ?? 0, 120, accuracy: 0.1)
        XCTAssertEqual(heart?.secondHalf ?? 0, 120, accuracy: 0.1)
    }

    func testPaceText() {
        XCTAssertEqual(HikeMetrics.paceText(0.75, units: Units(metric: true)), "12:30 min/km")
        XCTAssertTrue(HikeMetrics.paceText(0.75, units: Units(metric: false)).hasSuffix("min/mi"))
    }

    func testHikePromptCarriesTempoAndHeartRate() {
        let (analysis, points) = hike(withHeartRate: true)
        let facts = RideInsightFacts(analysis: analysis, points: points, placeName: "Test", units: Units(metric: true), sport: .hike)
        XCTAssertTrue(facts.prompt.contains("Hiking measurements"))
        XCTAssertTrue(facts.prompt.contains("Heart rate: average"))
        XCTAssertTrue(facts.prompt.contains("average pace while moving"))
        XCTAssertTrue(Sport.hike.coachBrief.contains("Pay special attention to heart rate and tempo"))
        XCTAssertFalse(Sport.ski.coachBrief.contains("Pay special attention to heart rate and tempo"))
    }

    func testSnowRideGetsNoHikingBlock() {
        let (analysis, points) = hike(withHeartRate: true)
        points.forEach { $0.sport = Sport.ski.rawValue }
        let facts = RideInsightFacts(analysis: analysis, points: points, placeName: "Test", units: Units(metric: true), sport: .ski)
        XCTAssertFalse(facts.prompt.contains("Hiking measurements"))
    }
}
