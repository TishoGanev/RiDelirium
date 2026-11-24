//
//  HikeMetrics.swift
//  RiDelirium
//

import Foundation

/// Heart rate and tempo of a hike, worked out in code and handed to the language model as facts. Pace is time per
/// distance while moving; heart rate needs an Apple Watch. Keep in sync with `HikeMetrics.kt` on Android.
struct HikeMetrics: Equatable {
    struct Heart: Equatable {
        var average: Double
        var maximum: Double
        var climbs: Double?
        var descents: Double?
        var firstHalf: Double?
        var secondHalf: Double?
    }

    /// Seconds per metre while not standing; nil without distance.
    var averagePace: Double?
    var climbPace: Double?
    var descentPace: Double?
    /// Metres climbed per hour of climbing.
    var climbRate: Double?
    /// Second half's pace against the first, in percent; positive means slower.
    var paceChange: Double?
    var heart: Heart?

    /// A pace change smaller than this is noise.
    static let steadyPercent = 8.0

    static func compute(analysis: RideAnalysis, points: [TrackPoint]) -> HikeMetrics {
        var metrics = HikeMetrics()
        let moving = analysis.segments.filter { $0.activity != .still && $0.endIndex < points.count }

        func pace(_ group: [Segment]) -> Double? {
            let distance = group.reduce(0) { $0 + $1.distance }, time = group.reduce(0) { $0 + $1.duration }
            return distance > 0 && time > 0 ? time / distance : nil
        }
        metrics.averagePace = pace(moving)
        metrics.climbPace = pace(moving.filter { $0.activity == .lift })
        metrics.descentPace = pace(moving.filter { $0.activity == .run })
        let climbTime = analysis.duration(of: .lift)
        if climbTime > 0, analysis.liftVertical > 0 { metrics.climbRate = analysis.liftVertical / (climbTime / 3600) }

        // First and second half of the time spent moving.
        let total = moving.reduce(0) { $0 + $1.duration }
        var elapsed = 0.0
        var first: [Segment] = [], second: [Segment] = []
        for segment in moving {
            if elapsed + segment.duration / 2 < total / 2 { first.append(segment) } else { second.append(segment) }
            elapsed += segment.duration
        }
        if total >= 600, let early = pace(first), let late = pace(second) {
            metrics.paceChange = (late - early) / early * 100
        }

        func heartRate(_ group: [Segment]) -> Double? {
            let values = group.flatMap { points[$0.startIndex...$0.endIndex].compactMap(\.heartRate) }
            return values.isEmpty ? nil : values.reduce(0, +) / Double(values.count)
        }
        let all = moving.flatMap { points[$0.startIndex...$0.endIndex].compactMap(\.heartRate) }
        if let maximum = all.max() {
            metrics.heart = Heart(
                average: all.reduce(0, +) / Double(all.count), maximum: maximum,
                climbs: heartRate(moving.filter { $0.activity == .lift }),
                descents: heartRate(moving.filter { $0.activity == .run }),
                firstHalf: heartRate(first), secondHalf: heartRate(second)
            )
        }
        return metrics
    }

    /// "12:30 /km" from seconds per metre.
    static func paceText(_ secondsPerMeter: Double, units: Units) -> String {
        let perUnit = secondsPerMeter * (units.metric ? 1000 : 1609.344)
        let whole = Int(perUnit.rounded())
        return String(format: "%d:%02d min/%@", whole / 60, whole % 60, units.metric ? "km" : "mi")
    }

    func promptLines(units: Units) -> [String] {
        var lines = ["Hiking measurements, worked out in code:"]
        if let averagePace {
            lines.append("- Tempo: average pace while moving \(Self.paceText(averagePace, units: units))")
        }
        var split: [String] = []
        if let climbPace { split.append("climbs \(Self.paceText(climbPace, units: units))") }
        if let descentPace { split.append("descents \(Self.paceText(descentPace, units: units))") }
        if !split.isEmpty { lines.append("- Pace on " + split.joined(separator: ", ")) }
        if let climbRate { lines.append("- Climbing rate: \(units.altitude(meters: climbRate)) per hour of climbing") }
        if let paceChange {
            let size = Int(abs(paceChange).rounded())
            lines.append(abs(paceChange) < Self.steadyPercent
                ? "- Tempo was steady: the second half's pace matched the first half"
                : "- Pace in the second half was \(size)% \(paceChange > 0 ? "slower" : "faster") than in the first half")
        }
        if let heart {
            var line = "- Heart rate: average \(Int(heart.average.rounded())) bpm, highest \(Int(heart.maximum.rounded())) bpm"
            if let climbs = heart.climbs { line += "; on climbs \(Int(climbs.rounded())) bpm" }
            if let descents = heart.descents { line += "; on descents \(Int(descents.rounded())) bpm" }
            lines.append(line)
            if let early = heart.firstHalf, let late = heart.secondHalf {
                lines.append("- Heart rate in the second half averaged \(Int(late.rounded())) bpm against \(Int(early.rounded())) bpm in the first half")
            }
        } else {
            lines.append("- Heart rate: not recorded (no Apple Watch); do not mention it")
        }
        return lines
    }
}
