//
//  WatchTrackView.swift
//  RiDeliriumWatch
//
//  Root of the watch app: one screen (heart rate, distance, vertical, START / STOP). No tabs.
//

import SwiftUI

struct WatchTrackView: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var workout: WorkoutManager
    @EnvironmentObject private var gForce: GForceMonitor
    @EnvironmentObject private var compass: WatchCompass

    @State private var confirmingStop = false

    private var state: WatchState { link.state }

    var body: some View {
        GeometryReader { proxy in
            let scale = WatchScale(k: proxy.size.width / WatchScale.designWidth)
            ZStack(alignment: .topLeading) {
                Color.black.ignoresSafeArea()

                WatchRecordView(confirmingStop: $confirmingStop)

                if confirmingStop {
                    WatchStopConfirm(
                        onEnd: { link.send(.stop); confirmingStop = false },
                        onResume: { confirmingStop = false }
                    )
                }
            }
            .environment(\.watchScale, scale)
        }
        // The design is drawn on the whole screen, time included.
        .ignoresSafeArea()
        .onAppear {
            workout.startMonitoring()
            workout.sync(tracking: state.isTracking, paused: state.isPaused, sport: state.sport)
        }
        .onChange(of: state.isTracking) { _, tracking in
            workout.sync(tracking: tracking, paused: state.isPaused, sport: state.sport)
            if tracking { gForce.start() } else { gForce.stop(); confirmingStop = false }
        }
        .onChange(of: state.isPaused) { workout.sync(tracking: state.isTracking, paused: state.isPaused, sport: state.sport) }
        .onChange(of: workout.heartRate) { _, bpm in
            if let bpm, state.isTracking { link.sendHeartRate(bpm) }
        }
    }
}
