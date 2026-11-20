//
//  WatchRecordView.swift
//  RiDeliriumWatch
//

import SwiftUI

/// The whole watch app: heart rate, distance and vertical as three big rows, and one full-width
/// START (green) / STOP (red) button, sized for gloves. No tabs.
struct WatchRecordView: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var workout: WorkoutManager
    @Environment(\.watchScale) private var scale
    @Binding var confirmingStop: Bool

    private var state: WatchState { link.state }
    private var tracking: Bool { state.isTracking }

    var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 0) {
                metricRow(label: "HEART", color: WatchPalette.pink,
                          value: workout.heartRate.map { String(Int($0.rounded())) } ?? "--",
                          unit: "BPM", active: workout.heartRate != nil)
                metricRow(label: "DISTANCE", color: WatchPalette.amber,
                          value: WatchFormat.km(state.distance, metric: state.metric),
                          unit: state.metric ? "KM" : "MI", active: tracking)
                metricRow(label: "VERTICAL", color: WatchPalette.green,
                          value: Int((state.metric ? state.descent : state.descent * 3.281).rounded()).formatted(),
                          unit: state.metric ? "M" : "FT", active: tracking)
            }

            Spacer(minLength: 0)

            if !link.isReachable {
                Text("OPEN APP ON IPHONE")
                    .font(WatchFont.bold(scale.pt(18)))
                    .foregroundStyle(WatchPalette.secondary)
                    .padding(.bottom, scale.pt(8))
            }

            Button {
                if tracking { confirmingStop = true } else { link.send(.start) }
            } label: {
                Text(tracking ? "STOP" : "START")
                    .font(WatchFont.extraBold(scale.pt(48)))
                    .tracking(scale.pt(2))
                    .foregroundStyle(tracking ? Color.white : Color.black)
                    .frame(maxWidth: .infinity)
                    .frame(height: scale.pt(100))
                    .background(tracking ? WatchPalette.red : WatchPalette.green, in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(!link.isReachable)
            .opacity(link.isReachable ? 1 : 0.4)
            .accessibilityLabel(tracking ? "Stop ride" : "Start ride")
        }
        .padding(.top, scale.pt(76))
        .padding(.horizontal, scale.pt(30))
        .padding(.bottom, scale.pt(22))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// Label on the left, big value and unit on the right. Distance and vertical are grey until a ride starts.
    private func metricRow(label: String, color: Color, value: String, unit: String, active: Bool) -> some View {
        HStack(alignment: .center, spacing: 0) {
            Text(label)
                .font(WatchFont.extraBold(scale.pt(30)))
                .tracking(scale.pt(1))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(color)
            Spacer(minLength: scale.pt(8))
            HStack(alignment: .lastTextBaseline, spacing: scale.pt(6)) {
                Text(value)
                    .font(WatchFont.extraBold(scale.pt(60)))
                    .monospacedDigit()
                    .foregroundStyle(active ? Color.white : Color(hex: 0x6E6E73))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Text(unit)
                    .font(WatchFont.extraBold(scale.pt(26)))
                    .foregroundStyle(Color(hex: 0xC7C7CC))
                    .frame(width: scale.pt(52), alignment: .leading)
            }
        }
        .frame(height: scale.pt(86))
        .accessibilityElement(children: .combine)
    }
}

/// Full-screen "end the ride?" with two large buttons, for gloves.
struct WatchStopConfirm: View {
    let onEnd: () -> Void
    let onResume: () -> Void
    @Environment(\.watchScale) private var scale

    var body: some View {
        VStack(spacing: scale.pt(14)) {
            Button(action: onEnd) {
                Text("END").font(WatchFont.extraBold(scale.pt(34))).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: scale.pt(110))
                    .background(WatchPalette.red, in: RoundedRectangle(cornerRadius: scale.pt(40)))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Button(action: onResume) {
                Text("RESUME").font(WatchFont.extraBold(scale.pt(34))).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).frame(height: scale.pt(110))
                    .background(WatchPalette.surface2, in: RoundedRectangle(cornerRadius: scale.pt(40)))
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, scale.pt(24))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black)
    }
}
