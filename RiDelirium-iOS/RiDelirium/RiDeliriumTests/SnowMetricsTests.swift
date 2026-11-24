import XCTest
import CoreLocation
@testable import RiDelirium

final class SnowMetricsTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_792_000_000)

    /// A straight run with the heading swinging 40 degrees either way every few seconds.
    private func carvingRun(swings: Int) -> (RideAnalysis, [TrackPoint]) {
        var points: [TrackPoint] = []
        var course = 180.0
        var second = 0.0
        for swing in 0..<swings {
            for _ in 0..<4 {
                course += swing % 2 == 0 ? 10 : -10
                let point = TrackPoint(lat: 42, lon: 23, altitude: 2000 - second, speed: 10, timestamp: base.addingTimeInterval(second), session: 1)
                point.course = course
                point.gForce = second == 6 ? 2.8 : 1.1
                points.append(point)
                second += 1
            }
        }
        let run = Segment(activity: .run, startIndex: 0, endIndex: points.count - 1, distance: 400, vertical: second, maxSpeed: 10, duration: second)
        return (RideAnalysis(segments: [run]), points)
    }

    func testCountsOneTurnPerHeadingSwing() {
        let (analysis, points) = carvingRun(swings: 6)
        XCTAssertEqual(SnowMetrics.compute(analysis: analysis, points: points).turns, 6)
    }

    func testTurnRateIsAverageHeadingChangePerSecond() {
        let (analysis, points) = carvingRun(swings: 4)
        XCTAssertEqual(SnowMetrics.compute(analysis: analysis, points: points).turnRate ?? 0, 10, accuracy: 0.01)
    }

    func testPeakGAndHardImpacts() {
        let (analysis, points) = carvingRun(swings: 4)
        let metrics = SnowMetrics.compute(analysis: analysis, points: points)
        XCTAssertEqual(metrics.peakG ?? 0, 2.8, accuracy: 0.001)
        XCTAssertEqual(metrics.hardImpacts, 1)
    }

    func testNoHeadingMeansNoTurnLine() {
        let (analysis, points) = carvingRun(swings: 4)
        points.forEach { $0.course = nil }
        let metrics = SnowMetrics.compute(analysis: analysis, points: points)
        XCTAssertNil(metrics.turnRate)
        XCTAssertFalse(metrics.promptLines(analysis: analysis, units: Units(metric: true)).contains { $0.contains("Turns") })
    }

    func testHikeBriefNeverPushesSpeed() {
        XCTAssertFalse(Sport.hike.showsSpeed)
        XCTAssertTrue(Sport.hike.coachBrief.contains("never praise or push top speed"))
        XCTAssertFalse(Sport.hike.coachBrief.contains("G-force"))
        XCTAssertTrue(Sport.ski.coachBrief.contains("peak G-force"))
        XCTAssertTrue(Sport.board.coachBrief.contains("peak G-force"))
    }
}
