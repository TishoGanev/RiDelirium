//
//  SnowMetrics.swift
//  RiDelirium
//

import Foundation

/// Extra measurements for skiing and snowboarding, worked out in code and handed to the language model as
/// facts. Turns come from the GPS heading, so they are approximate; air time and jump height are not measured.
/// Keep in sync with `SnowMetrics.kt` on Android.
struct SnowMetrics: Equatable {
    var turns = 0
    /// Average heading change in degrees per second while riding a run; nil without heading data.
    var turnRate: Double?
    var peakG: Double?
    /// Fixes with more than `impactG` of G-force: hard landings or bumps.
    var hardImpacts: Int?
    /// Distance over time while not standing.
    var averageMovingSpeed = 0.0
    /// Seconds between the end of one run and the start of the next.
    var restBetweenRuns: [TimeInterval] = []

    static let impactG = 2.5
    /// Heading swing in one direction that counts as a turn, in degrees.
    static let minTurnSweep = 25.0
    /// Slower than this (m/s) the heading is too noisy to read.
    static let minTurnSpeed = 3.0

    static func compute(analysis: RideAnalysis, points: [TrackPoint]) -> SnowMetrics {
        var metrics = SnowMetrics()
        let movingTime = analysis.duration(of: .run) + analysis.duration(of: .lift)
        if movingTime > 0 { metrics.averageMovingSpeed = analysis.movingDistance / movingTime }

        let gs = points.compactMap(\.gForce)
        if !gs.isEmpty {
            metrics.peakG = gs.max()
            metrics.hardImpacts = gs.filter { $0 > impactG }.count
        }

        var headingTime = 0.0, headingChange = 0.0
        for run in analysis.runs where run.endIndex < points.count {
            var direction = 0.0, sweep = 0.0
            func closeSweep() { if abs(sweep) >= minTurnSweep { metrics.turns += 1 } }
            var index = run.startIndex + 1
            while index <= run.endIndex {
                let previous = points[index - 1], point = points[index]
                index += 1
                guard let course = point.course, let before = previous.course, course >= 0, before >= 0,
                      point.speed >= minTurnSpeed, previous.speed >= minTurnSpeed else { continue }
                let dt = point.timestamp.timeIntervalSince(previous.timestamp)
                guard dt > 0, dt <= 3 else { continue }
                var delta = course - before
                if delta > 180 { delta -= 360 }
                if delta < -180 { delta += 360 }
                headingTime += dt
                headingChange += abs(delta)
                guard abs(delta) >= 2 else { continue }
                if direction == 0 || (delta > 0) == (direction > 0) {
                    direction = delta > 0 ? 1 : -1
                    sweep += delta
                } else {
                    closeSweep()
                    direction = delta > 0 ? 1 : -1
                    sweep = delta
                }
            }
            closeSweep()
        }
        if headingTime > 0 { metrics.turnRate = headingChange / headingTime }

        let runs = analysis.runs
        for (current, next) in zip(runs, runs.dropFirst()) where next.startIndex < points.count && current.endIndex < points.count {
            let gap = points[next.startIndex].timestamp.timeIntervalSince(points[current.endIndex].timestamp)
            if gap > 0 { metrics.restBetweenRuns.append(gap) }
        }
        return metrics
    }

    /// Lines for the model's data block.
    func promptLines(analysis: RideAnalysis, units: Units) -> [String] {
        let runs = analysis.runs
        let riding = analysis.duration(of: .run)
        let total = riding + analysis.duration(of: .lift) + analysis.duration(of: .still)
        var lines = ["Snow sport measurements, worked out in code:"]
        lines.append("- Vertical descent: \(units.altitude(meters: analysis.totalVertical)) over \(runs.count) \(runs.count == 1 ? "run" : "runs")")
        lines.append("- Max speed \(units.speed(metersPerSecond: analysis.movingMaxSpeed)); average speed while moving \(units.speed(metersPerSecond: averageMovingSpeed))")
        if let peakG {
            var line = "- Peak G-force: \(String(format: "%.2f", peakG)) G"
            if let hardImpacts { line += "; \(hardImpacts) hard \(hardImpacts == 1 ? "impact" : "impacts") above \(Self.impactG) G (landings or bumps)" }
            lines.append(line)
        } else {
            lines.append("- Peak G-force: not recorded on this phone")
        }
        if let turnRate {
            let perRun = runs.isEmpty ? 0 : Double(turns) / Double(runs.count)
            lines.append("- Turns: about \(turns) (about \(Int(perRun.rounded())) per run); average turning \(Int(turnRate.rounded())) degrees per second. Estimated from the GPS heading, so approximate")
        }
        lines.append("- Air time and jump height are not measured; never mention them")
        if total > 0 {
            lines.append("- Intensity: \(units.altitude(meters: total >= 600 ? analysis.totalVertical / (total / 3600) : analysis.totalVertical)) of descent per hour on the mountain; riding \(Int((riding / total * 100).rounded()))% of the time")
        }
        if !restBetweenRuns.isEmpty {
            let average = restBetweenRuns.reduce(0, +) / Double(restBetweenRuns.count)
            lines.append("- Recovery: on average \(units.compactDuration(average)) between runs, longest \(units.compactDuration(restBetweenRuns.max() ?? 0))")
        }
        return lines
    }
}
