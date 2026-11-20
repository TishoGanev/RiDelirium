//
//  RideLiveActivity.swift
//  RideWidget
//
//  Lock Screen card and Dynamic Island for a ride in progress, and for a friend's "wait for me".
//

import ActivityKit
import SwiftUI
import WidgetKit

struct RideLiveActivity: Widget {
    /// Tapping the card or the island opens the app on the ride, not wherever it was last left.
    private static let rideURL = URL(string: "ridelirium://ride")

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: RideActivityAttributes.self) { context in
            LockScreenView(state: context.state, format: LiveFormat(metric: context.attributes.metric))
                .activityBackgroundTint(context.state.face == .request ? LivePalette.red : LivePalette.card)
                .activitySystemActionForegroundColor(.white)
                .widgetURL(Self.rideURL)
        } dynamicIsland: { context in
            let state = context.state
            let format = LiveFormat(metric: context.attributes.metric)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    if let request = state.request {
                        LiveAvatar(initials: request.initials, colorIndex: request.colorIndex, size: 48, border: LivePalette.red, borderWidth: 3, fontSize: 18)
                            .padding(.leading, 4)
                    }
                }
                DynamicIslandExpandedRegion(.center) {
                    if let request = state.request {
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(spacing: 4) {
                                Text(request.fromName.uppercased())
                                if let distance = request.distanceM { Text("· \(format.short(distance))") }
                                LiveArrow(degrees: request.bearingDeg, size: 11, color: LivePalette.secondary)
                            }
                            .font(.live(12, extraBold: false)).foregroundStyle(LivePalette.secondary)
                            Text("WAIT FOR ME").font(.live(26)).foregroundStyle(LivePalette.red)
                        }
                    }
                }
                DynamicIslandExpandedRegion(.bottom) {
                    switch state.face {
                    case .request:
                        if let request = state.request {
                            WaitButtons(request: request, buttonHeight: 46, mapWidth: 90, mapFill: LivePalette.surface2, okFill: LivePalette.red, okText: .white)
                        }
                    case .waiting:
                        if let waiting = state.waiting { WaitingCard(waiting: waiting, format: format) }
                    case .stopped, .lift:
                        StoppedCard(state: state, format: format)
                    case .riding, .finished:
                        RidingCard(state: state, format: format, showGoal: false)
                    }
                }
            } compactLeading: {
                if let request = state.request {
                    LiveAvatar(initials: request.initials, colorIndex: request.colorIndex, size: 27, border: LivePalette.red, borderWidth: 2, fontSize: 10)
                } else {
                    HStack(spacing: 5) {
                        LiveDot(color: LivePalette.red)
                        Text("\(format.distance(state.distance)) \(format.distanceUnit)")
                            .font(.live(15)).monospacedDigit().foregroundStyle(.white)
                    }
                }
            } compactTrailing: {
                if state.request != nil {
                    HStack(spacing: 2) {
                        Text("WAIT").font(.live(14))
                        Image(systemName: "arrow.up.right").font(.system(size: 11, weight: .heavy))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).frame(height: 27)
                    .background(LivePalette.red, in: Capsule())
                } else {
                    Text(format.speed(metersPerSecond: state.speed))
                        .font(.live(15)).monospacedDigit().foregroundStyle(LivePalette.amber)
                }
            } minimal: {
                if let request = state.request {
                    LiveAvatar(initials: request.initials, colorIndex: request.colorIndex, size: 24, border: LivePalette.red, borderWidth: 2, fontSize: 9)
                } else {
                    LiveDot(color: LivePalette.red, size: 12)
                }
            }
            .keylineTint(LivePalette.red)
            .widgetURL(Self.rideURL)
        }
    }
}
