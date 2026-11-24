//
//  HeartRateRecovery.swift
//  RiDelirium
//

import Foundation

/// How fast the pulse falls once the rider stops: the drop over the first minute of each rest, from the
/// heart rate stored with the points. Needs an Apple Watch; without one there are no heart rates and no result.
struct HeartRateRecovery: Equatable {
    struct Rest: Equatable {
        let startBPM: Double
        let endBPM: Double
        /// Seconds between the two readings, at most `window`.
        let seconds: TimeInterval

        var drop: Double { startBPM - endBPM }
        /// Fall scaled to a minute, so rests of different lengths compare.
        var dropPerMinute: Double { drop / seconds * 60 }
    }

    let rests: [Rest]

    /// A rest shorter than this is a pause for breath, too short to judge.
    static let minRest: TimeInterval = 45
    /// The fall is read this long after the rest began (or at its end, if shorter).
    static let window: TimeInterval = 60
    /// Heart rate at the start is the highest reading this close to the stop.
    static let peakSpan: TimeInterval = 10
    /// A reading counts as "at" a time if it is within this many seconds of it.
    static let tolerance: TimeInterval = 8

    static func compute(analysis: RideAnalysis, points: [TrackPoint]) -> HeartRateRecovery? {
        guard points.contains(where: { $0.heartRate != nil }) else { return nil }
        var rests: [Rest] = []
        for still in analysis.stills where still.duration >= minRest && still.endIndex < points.count {
            let begin = points[still.startIndex].timestamp
            let peaks = points.compactMap { point -> Double? in
                guard let bpm = point.heartRate, abs(point.timestamp.timeIntervalSince(begin)) <= peakSpan else { return nil }
                return bpm
            }
            guard let start = peaks.max() else { continue }
            let seconds = min(window, still.duration)
            let target = begin.addingTimeInterval(seconds)
            let near = points[still.startIndex...still.endIndex].compactMap { point -> (Double, TimeInterval)? in
                guard let bpm = point.heartRate else { return nil }
                let gap = abs(point.timestamp.timeIntervalSince(target))
                return gap <= tolerance ? (bpm, gap) : nil
            }
            guard let end = near.min(by: { $0.1 < $1.1 })?.0 else { continue }
            rests.append(Rest(startBPM: start, endBPM: end, seconds: seconds))
        }
        return rests.isEmpty ? nil : HeartRateRecovery(rests: rests)
    }

    /// Average fall per minute across the rests that measured one.
    var averageDropPerMinute: Double { rests.map(\.dropPerMinute).reduce(0, +) / Double(rests.count) }
    var best: Rest? { rests.max { $0.dropPerMinute < $1.dropPerMinute } }

    var bullet: String {
        "After stopping, your pulse fell about \(Int(averageDropPerMinute.rounded())) bpm in the first minute (\(rests.count) \(rests.count == 1 ? "rest" : "rests") measured)."
    }

    var promptLine: String {
        var line = "- Heart rate recovery: over \(rests.count) \(rests.count == 1 ? "rest" : "rests") the pulse fell on average \(Int(averageDropPerMinute.rounded())) bpm in the first minute after stopping"
        if let best { line += "; best \(Int(best.startBPM.rounded())) to \(Int(best.endBPM.rounded())) bpm in \(Int(best.seconds.rounded())) s" }
        return line + ". A bigger drop means the body recovers faster"
    }
}
