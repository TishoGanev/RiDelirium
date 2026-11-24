//
//  RideSports.swift
//  RiDelirium
//

import Foundation

/// Which sport the rider had chosen along a recording. It can change mid-ride: a snowboard day that ends
/// with a walk down. Points saved before the sport was stored fall back to `fallback`.
/// Keep in sync with `RideSports.kt` on Android.
struct SportTimeline {
    struct Span: Equatable {
        let sport: Sport
        var duration: TimeInterval
    }

    private let labels: [Sport]
    /// Contiguous stretches in ride order, with the time spent in each.
    let spans: [Span]

    init(points: [TrackPoint], fallback: Sport) {
        labels = points.map { $0.sport.flatMap(Sport.init(rawValue:)) ?? fallback }
        var spans: [Span] = []
        for (index, sport) in labels.enumerated() {
            let step = index + 1 < points.count ? max(0, points[index + 1].timestamp.timeIntervalSince(points[index].timestamp)) : 0
            if let last = spans.last, last.sport == sport {
                spans[spans.count - 1].duration += step
            } else {
                spans.append(Span(sport: sport, duration: step))
            }
        }
        self.spans = spans
    }

    /// Sports in the order they were first used.
    var distinct: [Sport] {
        var seen: [Sport] = []
        for span in spans where !seen.contains(span.sport) { seen.append(span.sport) }
        return seen
    }

    /// The sport with the most time, or nil for an empty recording.
    var dominant: Sport? {
        Dictionary(grouping: spans, by: \.sport)
            .mapValues { $0.reduce(0) { $0 + $1.duration } }
            .max { $0.value < $1.value }?.key
    }

    func sport(at index: Int, fallback: Sport) -> Sport {
        labels.indices.contains(index) ? labels[index] : fallback
    }
}
