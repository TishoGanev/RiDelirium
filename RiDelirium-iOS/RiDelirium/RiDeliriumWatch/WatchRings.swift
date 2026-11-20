//
//  WatchRings.swift
//  RiDeliriumWatch
//

import SwiftUI

/// One arc of a ring, starting at 12 o'clock, with round caps.
struct RingArc: View {
    let progress: Double
    let color: Color
    let track: Color
    let diameter: CGFloat
    let stroke: CGFloat

    var body: some View {
        ZStack {
            Circle().stroke(track, lineWidth: stroke)
            Circle()
                .trim(from: 0, to: min(max(progress, 0.001), 1))
                .stroke(color, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .opacity(progress > 0 ? 1 : 0)
        }
        .frame(width: diameter - stroke, height: diameter - stroke)
        .frame(width: diameter, height: diameter)
        .animation(.easeOut(duration: 0.4), value: progress)
    }
}

/// Distance (outer 154), speed (middle 124) and pulse (inner 94): three 12 pt rings, 3 pt apart.
struct ActivityRings: View {
    let distance: Double
    let speed: Double
    let pulse: Double
    @Environment(\.watchScale) private var scale

    var body: some View {
        let stroke = scale.pt(12)
        let outer = scale.pt(154), middle = scale.pt(124), inner = scale.pt(94)
        ZStack {
            RingArc(progress: distance, color: WatchPalette.red, track: WatchPalette.redTrack, diameter: outer, stroke: stroke)
            RingArc(progress: speed, color: WatchPalette.amber, track: WatchPalette.amberTrack, diameter: middle, stroke: stroke)
            RingArc(progress: pulse, color: WatchPalette.pink, track: WatchPalette.pinkTrack, diameter: inner, stroke: stroke)
        }
        .frame(width: outer, height: outer)
    }
}

/// The 26 pt ring in the corner of a Stats tile.
struct MiniRing: View {
    let progress: Double
    let color: Color
    @Environment(\.watchScale) private var scale

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.22), lineWidth: scale.pt(6))
            Circle()
                .trim(from: 0, to: min(max(progress, 0.001), 1))
                .stroke(color, style: StrokeStyle(lineWidth: scale.pt(6), lineCap: .round))
                .rotationEffect(.degrees(-90))
                .opacity(progress > 0 ? 1 : 0)
        }
        .padding(scale.pt(3))
        .frame(width: scale.pt(26), height: scale.pt(26))
    }
}
