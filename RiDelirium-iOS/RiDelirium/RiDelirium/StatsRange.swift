//
//  StatsRange.swift
//  RiDelirium
//

import Foundation

/// How far back the Stats list reaches.
enum StatsRange: String, CaseIterable, Identifiable {
    case today, days3, days7, month, season, all

    static let storageKey = "statsRange"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .today: "TODAY"
        case .days3: "3 DAYS"
        case .days7: "7 DAYS"
        case .month: "1 MONTH"
        case .season: "SEASON"
        case .all: "ALL"
        }
    }

    /// Calendar days count, today included: 3 DAYS is today and the two days before it.
    func cutoff(now: Date = Date(), calendar: Calendar = .current) -> Date? {
        let today = calendar.startOfDay(for: now)
        func back(_ days: Int) -> Date? { calendar.date(byAdding: .day, value: -days, to: today) }
        switch self {
        case .today: return today
        case .days3: return back(2)
        case .days7: return back(6)
        case .month: return back(29)
        case .season:
            let year = Season(containing: now, calendar: calendar).startYear
            return calendar.date(from: DateComponents(year: year, month: 7, day: 1))
        case .all: return nil
        }
    }

    func contains(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> Bool {
        guard let cutoff = cutoff(now: now, calendar: calendar) else { return true }
        return date >= cutoff
    }
}

/// What one ride day amounts to, reduced to the numbers progress is judged by.
struct DayMetrics: Equatable {
    var date: Date
    var runs: Int
    var downhillDistance: Double
    var vertical: Double
    var topSpeed: Double
    var ridingTime: TimeInterval
    var totalTime: TimeInterval

    init(date: Date, runs: Int, downhillDistance: Double, vertical: Double, topSpeed: Double, ridingTime: TimeInterval, totalTime: TimeInterval) {
        self.date = date
        self.runs = runs
        self.downhillDistance = downhillDistance
        self.vertical = vertical
        self.topSpeed = topSpeed
        self.ridingTime = ridingTime
        self.totalTime = totalTime
    }

    init(_ session: SessionSummary) {
        let analysis = session.analysis
        self.init(
            date: session.startDate,
            runs: analysis.runs.count,
            downhillDistance: analysis.downhillDistance,
            vertical: analysis.totalVertical,
            topSpeed: analysis.runs.map(\.maxSpeed).max() ?? 0,
            ridingTime: analysis.duration(of: .run),
            totalTime: analysis.duration(of: .run) + analysis.duration(of: .lift) + analysis.duration(of: .still)
        )
    }
}

extension RideInsightFacts {
    /// Facts for many rides at once, aimed at how the rider is progressing. Trends compare the earlier
    /// rides with the later ones; the model only words what is worked out here.
    init(period metrics: [DayMetrics], rangeTitle: String, units: Units, sport: Sport = .board) {
        let days = metrics.filter { $0.runs > 0 }.sorted { $0.date < $1.date }
        var bullets: [String] = []

        let runs = days.reduce(0) { $0 + $1.runs }
        let vertical = days.reduce(0) { $0 + $1.vertical }
        let distance = days.reduce(0) { $0 + $1.downhillDistance }

        if days.isEmpty {
            bullets.append("No downhill runs were found in this period.")
        } else {
            bullets.append("\(days.count) ride \(days.count == 1 ? "day" : "days"), \(runs) runs, \(units.altitude(meters: vertical)) of descent over \(units.distance(meters: distance)).")
            if days.count > 1 {
                if let best = days.max(by: { $0.vertical < $1.vertical }) {
                    bullets.append("Most descent in a day: \(units.altitude(meters: best.vertical)) on \(Self.day(best.date)).")
                }
                if let fastest = days.max(by: { $0.topSpeed < $1.topSpeed }) {
                    bullets.append("Fastest day: \(units.speed(metersPerSecond: fastest.topSpeed)) on \(Self.day(fastest.date)).")
                }
                bullets.append(contentsOf: Self.trends(days, units: units))
            } else {
                bullets.append("Only one ride day in this period, so there is no trend to show yet.")
            }
        }
        self.init(bullets: bullets, prompt: Self.prompt(rangeTitle: rangeTitle, sport: sport, days: days, runs: runs, vertical: vertical, distance: distance, bullets: bullets, units: units), hasRuns: !days.isEmpty, isPeriod: true, sport: sport)
    }

    private static func day(_ date: Date) -> String {
        date.formatted(.dateTime.day().month(.abbreviated))
    }

    private static func mean(_ values: [Double]) -> Double {
        values.isEmpty ? 0 : values.reduce(0, +) / Double(values.count)
    }

    /// Earlier rides against later ones. With 2 or 3 days it is the first against the last; with more,
    /// the first half against the second. Changes under 8% are noise and are not reported.
    static func trends(_ days: [DayMetrics], units: Units) -> [String] {
        guard days.count >= 2 else { return [] }
        let early: [DayMetrics], late: [DayMetrics]
        if days.count >= 4 {
            let half = days.count / 2
            early = Array(days.prefix(half))
            late = Array(days.suffix(days.count - half))
        } else {
            early = [days[0]]
            late = [days[days.count - 1]]
        }
        var out: [String] = []

        func compare(_ label: String, _ before: Double, _ after: Double, format: (Double) -> String) {
            guard before > 0 else { return }
            let change = (after - before) / before * 100
            guard abs(change) >= 8 else { return }
            out.append("\(label) is \(Int(abs(change).rounded()))% \(change > 0 ? "higher" : "lower") in your later rides (\(format(after)) against \(format(before))).")
        }
        compare("Top speed", mean(early.map(\.topSpeed)), mean(late.map(\.topSpeed))) { units.speed(metersPerSecond: $0) }
        compare("Descent per day", mean(early.map(\.vertical)), mean(late.map(\.vertical))) { units.altitude(meters: $0) }
        compare("Runs per day", mean(early.map { Double($0.runs) }), mean(late.map { Double($0.runs) })) { String(Int($0.rounded())) }

        func perHour(_ group: [DayMetrics]) -> Double {
            let time = group.reduce(0) { $0 + $1.totalTime }
            return time > 0 ? group.reduce(0) { $0 + $1.vertical } / (time / 3600) : 0
        }
        compare("Descent per hour on the mountain", perHour(early), perHour(late)) { units.altitude(meters: $0) }

        if out.isEmpty { out.append("Your numbers are steady across this period: no big change either way.") }
        return out
    }

    private static func prompt(rangeTitle: String, sport: Sport, days: [DayMetrics], runs: Int, vertical: Double, distance: Double, bullets: [String], units: Units) -> String {
        var lines = [
            "Sport: \(sport.activity.lowercased())",
            "Period: \(rangeTitle)",
            "Ride days with runs: \(days.count); runs \(runs); total descent \(units.altitude(meters: vertical)); downhill distance \(units.distance(meters: distance))",
        ]
        for metrics in days.suffix(31) {
            lines.append("\(day(metrics.date)): \(metrics.runs) runs, descent \(units.altitude(meters: metrics.vertical)), top speed \(units.speed(metersPerSecond: metrics.topSpeed)), riding \(units.compactDuration(metrics.ridingTime)) of \(units.compactDuration(metrics.totalTime))")
        }
        lines.append("Changes already worked out (earlier rides against later rides):")
        lines.append(contentsOf: bullets.map { "- \($0)" })
        return lines.joined(separator: "\n")
    }
}
