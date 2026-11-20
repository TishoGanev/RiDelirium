//
//  WatchStatsView.swift
//  RiDeliriumWatch
//

import SwiftUI

/// Tab 3. Six tiles for this ride; each ring shows the value against a goal or a personal best.
struct WatchStatsView: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var workout: WorkoutManager
    @EnvironmentObject private var gForce: GForceMonitor
    @Environment(\.watchScale) private var scale

    private var state: WatchState { link.state }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let elapsed = link.elapsed(at: context.date)
            ZStack(alignment: .topLeading) {
                Text("THIS RIDE")
                    .font(WatchFont.extraBold(scale.pt(18)))
                    .foregroundStyle(WatchPalette.secondary)
                    .placed(x: 24, y: 24, scale: scale)

                VStack(spacing: scale.pt(8)) {
                    HStack(spacing: scale.pt(8)) {
                        tile("DISTANCE", WatchFormat.km(state.distance, metric: state.metric), state.metric ? "KM" : "MI",
                             WatchPalette.red, ratio(state.distance, state.goalDistance))
                        tile("MAX SPEED", "\(WatchFormat.speed(state.maxSpeed, metric: state.metric))", state.metric ? "KM/H" : "MPH",
                             WatchPalette.amber, ratio(state.maxSpeed, WatchBests.value(.maxSpeed, current: state.maxSpeed)))
                    }
                    HStack(spacing: scale.pt(8)) {
                        tile("AVG PULSE", workout.averageHeartRate.map { String(Int($0.rounded())) } ?? "--", "BPM",
                             WatchPalette.pink, ratio(workout.averageHeartRate ?? 0, state.maxHeartRate))
                        tile("MAX G", String(format: "%.1f", gForce.maxG), "G",
                             WatchPalette.cyan, ratio(gForce.maxG, WatchBests.value(.maxG, current: gForce.maxG)))
                    }
                    HStack(spacing: scale.pt(8)) {
                        tile("TIME", WatchFormat.elapsed(elapsed), "", .white, ratio(elapsed, WatchBests.value(.duration, current: elapsed)))
                        tile("DESCENT", "\(Int((state.metric ? state.descent : state.descent * 3.281).rounded()))", state.metric ? "M" : "FT",
                             WatchPalette.green, ratio(state.descent, WatchBests.value(.descent, current: state.descent)))
                    }
                }
                .placed(x: 24, y: 62, width: 362, scale: scale)
            }
        }
        .onChange(of: state.isTracking) { _, tracking in
            // Personal bests are kept when a ride ends.
            if !tracking {
                WatchBests.record(.maxSpeed, state.maxSpeed)
                WatchBests.record(.maxG, gForce.maxG)
                WatchBests.record(.duration, link.state.elapsed)
                WatchBests.record(.descent, state.descent)
            }
        }
    }

    private func ratio(_ value: Double, _ reference: Double) -> Double { reference > 0 ? value / reference : 0 }

    private func tile(_ label: String, _ value: String, _ unit: String, _ color: Color, _ progress: Double) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text(label).font(WatchFont.extraBold(scale.pt(13))).foregroundStyle(WatchPalette.secondary).lineLimit(1)
                Spacer(minLength: 0)
                MiniRing(progress: progress, color: color)
            }
            Spacer(minLength: 0)
            HStack(alignment: .lastTextBaseline, spacing: scale.pt(3)) {
                Text(value).font(WatchFont.extraBold(scale.pt(32))).monospacedDigit().foregroundStyle(.white)
                    .lineLimit(1).minimumScaleFactor(0.6)
                if !unit.isEmpty { Text(unit).font(WatchFont.bold(scale.pt(13))).foregroundStyle(WatchPalette.secondary) }
            }
        }
        .padding(.horizontal, scale.pt(14))
        .padding(.vertical, scale.pt(10))
        .frame(maxWidth: .infinity)
        .frame(height: scale.pt(108))
        .background(WatchPalette.surface, in: RoundedRectangle(cornerRadius: scale.pt(22), style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// Personal bests on this watch, the reference for the Stats rings.
enum WatchBests {
    enum Kind: String { case maxSpeed, maxG, duration, descent }

    static func stored(_ kind: Kind) -> Double { UserDefaults.standard.double(forKey: "best.\(kind.rawValue)") }

    /// The larger of the best so far and this ride, so a record fills the ring instead of overflowing it.
    static func value(_ kind: Kind, current: Double) -> Double { max(stored(kind), current) }

    static func record(_ kind: Kind, _ value: Double) {
        if value > stored(kind) { UserDefaults.standard.set(value, forKey: "best.\(kind.rawValue)") }
    }
}
