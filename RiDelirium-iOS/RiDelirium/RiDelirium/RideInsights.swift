//
//  RideInsights.swift
//  RiDelirium
//

import Foundation
import Combine
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Facts about one ride worked out in code. The numbers always come from here, never from the language
/// model, which only puts them into words.
struct RideInsightFacts {
    /// Plain-language observations, shown on every device.
    let bullets: [String]
    /// Everything the model is allowed to talk about.
    let prompt: String
    /// False when there is nothing to write about, so the model isn't asked to invent something.
    let hasRuns: Bool
    /// True for a summary over many rides: the model then talks about progress, not one day.
    var isPeriod = false
    /// What the rider rides; decides how the model is briefed and which advice it gives.
    let sport: Sport
    /// Every sport used during the ride, in order. More than one when the rider changed sport on the way.
    let sports: [Sport]

    init(bullets: [String], prompt: String, hasRuns: Bool, isPeriod: Bool, sport: Sport = .board) {
        self.sports = [sport]
        self.bullets = bullets
        self.prompt = prompt
        self.hasRuns = hasRuns
        self.isPeriod = isPeriod
        self.sport = sport
    }

    init(analysis: RideAnalysis, points: [TrackPoint], placeName: String, units: Units, sport fallback: Sport = .board) {
        // The sport can change during a ride; the one ridden longest leads, and the rest are told to the model.
        let timeline = SportTimeline(points: points, fallback: fallback)
        let sport = timeline.dominant ?? fallback
        let sports = timeline.distinct.isEmpty ? [sport] : timeline.distinct
        let mixed = sports.count > 1
        self.sport = sport
        self.sports = sports
        let runs = analysis.runs, lifts = analysis.lifts, stills = analysis.stills
        let riding = analysis.duration(of: .run)
        let lifting = analysis.duration(of: .lift)
        let standing = analysis.duration(of: .still)
        let total = riding + lifting + standing
        var bullets: [String] = []

        // One neutral vocabulary when the sports differ; each run line names its own sport.
        let words = mixed ? Sport.bike.words : sport.words
        let showsSpeed = sports.contains(where: \.showsSpeed)
        if runs.isEmpty {
            bullets.append("No downhill \(words.runs) were detected in this recording.")
        } else {
            let numbered = Array(runs.enumerated())
            // Speed is no goal on foot, so a hike never singles out its fastest stretch.
            let speedRuns = numbered.filter { timeline.sport(at: $0.element.startIndex, fallback: sport).showsSpeed }
            if let fastest = speedRuns.max(by: { $0.element.maxSpeed < $1.element.maxSpeed }) {
                bullets.append("\(words.run.capitalized) \(fastest.offset + 1) was your fastest, topping out at \(units.speed(metersPerSecond: fastest.element.maxSpeed)).")
            }
            if let biggest = numbered.max(by: { $0.element.vertical < $1.element.vertical }), runs.count > 1 {
                bullets.append("\(words.run.capitalized) \(biggest.offset + 1) had the most descent: \(units.altitude(meters: biggest.element.vertical)) over \(units.distance(meters: biggest.element.distance)).")
            }
            if showsSpeed, !mixed, runs.count >= 4 {
                let half = runs.count / 2
                let first = runs.prefix(half).map(\.maxSpeed).reduce(0, +) / Double(half)
                let second = runs.dropFirst(half).map(\.maxSpeed).reduce(0, +) / Double(runs.count - half)
                if first > 0 {
                    let change = (second - first) / first * 100
                    if abs(change) >= 8 {
                        bullets.append("Your top speeds were \(Int(abs(change).rounded()))% \(change < 0 ? "lower" : "higher") in the second half of the day.")
                    }
                }
            }
        }
        if total > 0 {
            func share(_ value: TimeInterval) -> Int { Int((value / total * 100).rounded()) }
            bullets.append("You spent \(share(riding))% of the day \(words.downActivity), \(share(lifting))% \(words.upActivity) and \(share(standing))% \(words.stopActivity).")
        }
        if let longest = stills.max(by: { $0.duration < $1.duration }), longest.duration >= 300 {
            let time = points[longest.startIndex].timestamp.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
            bullets.append("Your longest \(words.stop) was \(units.compactDuration(longest.duration)), around \(time).")
        }
        if !lifts.isEmpty {
            let average = lifts.map(\.duration).reduce(0, +) / Double(lifts.count)
            bullets.append("\(lifts.count) \(lifts.count == 1 ? words.lift : words.lifts), about \(units.compactDuration(average)) each.")
        }
        if total >= 1800, analysis.totalVertical > 0 {
            let perHour = analysis.totalVertical / (total / 3600)
            bullets.append("That is \(units.altitude(meters: perHour)) of descent for every hour on the mountain.")
        }
        let recovery = HeartRateRecovery.compute(analysis: analysis, points: points)
        if let recovery { bullets.append(recovery.bullet) }
        self.bullets = bullets
        hasRuns = !runs.isEmpty

        let sportLine = mixed
            ? "Sports during this ride, in order: " + timeline.spans.map { "\($0.sport.activity.lowercased()) \(units.compactDuration($0.duration))" }.joined(separator: ", then ")
            : "Sport: \(sport.activity.lowercased())"
        var lines = [
            sportLine,
            "Place: \(placeName)",
            "Total time: \(units.compactDuration(total)); riding \(units.compactDuration(riding)), lifts \(units.compactDuration(lifting)), standing \(units.compactDuration(standing))",
            "\(words.runs.capitalized): \(runs.count); downhill distance \(units.distance(meters: analysis.downhillDistance)); total descent \(units.altitude(meters: analysis.totalVertical))",
            "\(words.lifts.capitalized): \(lifts.count); total climb \(units.altitude(meters: analysis.liftVertical))",
        ]
        for (index, run) in runs.prefix(20).enumerated() {
            let tag = mixed ? " (\(timeline.sport(at: run.startIndex, fallback: sport).activity.lowercased()))" : ""
            lines.append("\(words.run.capitalized) \(index + 1)\(tag): \(units.duration(run.duration)), \(units.distance(meters: run.distance)), descent \(units.altitude(meters: run.vertical)), top speed \(units.speed(metersPerSecond: run.maxSpeed))")
        }
        // Snow measurements only describe the parts of the ride spent on snow.
        let snow = RideAnalysis(segments: analysis.segments.filter { timeline.sport(at: $0.startIndex, fallback: sport).isSnowSport })
        if !snow.runs.isEmpty {
            lines.append(contentsOf: SnowMetrics.compute(analysis: snow, points: points).promptLines(analysis: snow, units: units))
        }
        // Hiking measurements only describe the parts of the ride on foot.
        let walk = RideAnalysis(segments: analysis.segments.filter { timeline.sport(at: $0.startIndex, fallback: sport) == .hike })
        if !walk.segments.isEmpty {
            lines.append(contentsOf: HikeMetrics.compute(analysis: walk, points: points).promptLines(units: units))
        }
        if let recovery { lines.append(recovery.promptLine) }
        lines.append("Observations already worked out:")
        lines.append(contentsOf: bullets.map { "- \($0)" })
        prompt = lines.joined(separator: "\n")
    }
}

extension RideInsightFacts {
    /// Who the model plays; a ride that changed sport gets a general coach.
    var coachRole: String { sports.count > 1 ? "outdoor sports coach" : sport.coachRole }

    /// The sport's brief, or for a ride that changed sport one brief per sport used, in order.
    var coachBrief: String {
        guard sports.count > 1 else { return sport.coachBrief }
        let names = sports.map { $0.activity.lowercased() }.joined(separator: ", then ")
        return "The rider changed sport during this ride: \(names). Each run line names its sport. Coach each part by its own sport, "
            + "use each sport's own words, and never apply one sport's advice to another. "
            + sports.map { "For \($0.activity.lowercased()): \($0.coachBrief)" }.joined(separator: " ")
    }
}

/// Writes a short summary of a ride with Apple's on-device model (iOS 26 and later, Apple Intelligence
/// devices). Nothing leaves the phone. On other devices `state` ends as `.unavailable` and the screen
/// shows only the numbers.
@MainActor
final class RideInsightsModel: ObservableObject {
    struct Summary: Equatable {
        let headline: String
        let observations: [String]
        let tip: String
    }

    enum State: Equatable {
        case idle
        case generating
        case unavailable(String)
        case failed(String)
        case ready(Summary)
    }

    @Published private(set) var state: State = .idle

    /// Generates once; reopening the sheet shows the same text. Use `regenerate` for a new take.
    func generate(_ facts: RideInsightFacts) async {
        guard state == .idle else { return }
        await run(facts)
    }

    func regenerate(_ facts: RideInsightFacts) async {
        guard state != .generating else { return }
        await run(facts)
    }

    private func run(_ facts: RideInsightFacts) async {
        guard facts.hasRuns else {
            state = .unavailable(facts.isPeriod ? "No downhill runs were found in this period, so there is nothing to summarise yet." : "No downhill runs were found in this ride, so there is nothing to summarise yet.")
            return
        }
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            await generateWithAppleModel(facts)
            return
        }
        #endif
        state = .unavailable("Written insights need iOS 26 or later. Update in Settings > General > Software Update.")
    }
}

/// The words a sport uses for the ride data's downhill segments ("runs") and climbs ("lifts").
struct SportWords {
    let run: String, runs: String, lift: String, lifts: String
    let downActivity: String, upActivity: String, stopActivity: String, stop: String
}

extension Sport {
    var isSnowSport: Bool { self == .ski || self == .board }

    /// False on foot: nobody hikes to go fast.
    var showsSpeed: Bool { self != .hike }

    var words: SportWords {
        switch self {
        case .ski, .board:
            SportWords(run: "run", runs: "runs", lift: "lift", lifts: "lifts",
                       downActivity: "riding", upActivity: "on lifts", stopActivity: "standing still", stop: "stop")
        case .bike:
            SportWords(run: "descent", runs: "descents", lift: "climb", lifts: "climbs",
                       downActivity: "descending", upActivity: "climbing", stopActivity: "stopped", stop: "stop")
        case .hike:
            SportWords(run: "descent", runs: "descents", lift: "climb", lifts: "climbs",
                       downActivity: "descending", upActivity: "climbing", stopActivity: "resting", stop: "rest")
        }
    }

    /// The measurements a ski or snowboard summary covers when the data has them.
    fileprivate static let snowMetricsBrief =
        " Cover these where the data gives them, each with its number: vertical descent, max speed, average speed while moving, "
        + "peak G-force, turns (how many and how hard), activity intensity, and recovery between runs. "
        + "Skip any the data lacks; never estimate air time or jump height."

    /// Who the model plays, so a hike is not coached like a snowboard day.
    var coachRole: String {
        switch self {
        case .ski: "ski coach"
        case .board: "snowboarding coach"
        case .bike: "mountain bike coach"
        case .hike: "hiking guide"
        }
    }

    /// What this sport's data means and what advice fits it. The ride data uses "run" for a downhill
    /// segment and "lift" for a climb, whatever the sport; the brief tells the model the right words.
    var coachBrief: String {
        switch self {
        case .ski:
            "The rider skis. Runs are ski runs and lifts are chairlifts or gondolas. Look at descent per run, top speeds, "
                + "and whether the legs tire in the second half. Tips are about skiing: edge control, speed control, pacing the day, resting." + Self.snowMetricsBrief
        case .board:
            "The rider snowboards. Runs are snowboard runs and lifts are chairlifts or gondolas. Look at descent per run, top speeds, "
                + "and whether energy drops in the second half. Tips are about snowboarding: carving, edge control, pacing the day, resting." + Self.snowMetricsBrief
        case .bike:
            "The rider cycles off-road. Call runs descents and lifts climbs; never mention snow or chairlifts. Look at descent, "
                + "top speeds and the balance between climbing and descending. Tips are about cycling: braking, pacing climbs, hydration."
        case .hike:
            "The rider hikes. Call runs descents and lifts climbs or ascents; never mention snow, skiing, snowboarding or chairlifts. "
                + "Speed is not the goal, so never praise or push top speed. Look at total ascent and descent, time moving against resting, "
                + "and steady effort. Pay special attention to heart rate and tempo when the data gives them: the average and highest pulse, "
                + "the pulse on climbs against descents, whether the pulse rose while the pace held or fell (tiredness), the pace on climbs against descents, "
                + "the climbing rate, and whether the pace faded or held in the second half. Judge effort by pulse and a steady tempo, never by speed; "
                + "a pulse that climbs at the same pace means ease off. If heart rate is not recorded, do not mention it. "
                + "Tips are about hiking: steady pace, rest breaks, descending carefully on the knees, water and food."
        }
    }
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
@Generable
private struct GeneratedInsights {
    @Guide(description: "One short sentence that sums up the day, under 14 words")
    var headline: String

    @Guide(description: "Three short observations about this ride. Each uses only numbers from the data.", .count(3))
    var observations: [String]

    @Guide(description: "One practical tip for the next ride, one sentence")
    var tip: String
}

@available(iOS 26.0, *)
extension RideInsightsModel {
    fileprivate func generateWithAppleModel(_ facts: RideInsightFacts) async {
        let model = SystemLanguageModel.default
        switch model.availability {
        case .available:
            break
        case .unavailable(let reason):
            switch reason {
            case .appleIntelligenceNotEnabled:
                state = .unavailable("Turn on Apple Intelligence in Settings > Apple Intelligence & Siri to get written insights.")
            case .modelNotReady:
                state = .unavailable("Apple Intelligence is still getting ready. Keep the iPhone connected to Wi-Fi and charging, then try again in a few minutes. Progress is under Settings > Apple Intelligence & Siri.")
            default:
                state = .unavailable("This iPhone doesn't support Apple Intelligence, so written insights aren't available. The numbers below still work.")
            }
            return
        }

        state = .generating
        let scope = facts.isPeriod
            ? "several days of ride data and the changes between earlier and later rides. Say whether the rider is improving, holding steady or slipping, and what changed"
            : "one day of ride data"
        let session = LanguageModelSession(instructions: """
            You are a concise \(facts.coachRole) reading \(scope). Use only the numbers you are given. \
            Never invent speeds, places or events. Plain, friendly language. No emojis. \
            If heart rate recovery is given, say how quickly the pulse falls during rests. \
            \(facts.coachBrief)
            """)
        do {
            let response = try await session.respond(to: facts.prompt, generating: GeneratedInsights.self)
            let result = response.content
            state = .ready(Summary(headline: result.headline, observations: result.observations, tip: result.tip))
        } catch {
            state = .failed("Couldn't write insights this time. Try again.")
        }
    }
}
#endif
