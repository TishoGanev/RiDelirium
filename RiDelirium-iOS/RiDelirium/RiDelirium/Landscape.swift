//
//  Landscape.swift
//  RiDelirium
//
//  Landscape layout (design "Landscape C" on the canvas): the map or content fills the screen and one low
//  white bar runs along the bottom with TRACK / STATS on the left, the screen's numbers in the middle and its
//  buttons on the right. Portrait layouts are untouched; screens switch on `verticalSizeClass == .compact`.
//

import SwiftUI

/// What the landscape bar needs to switch tabs. ContentView provides it.
struct LandscapeNav {
    var tab: AppTab = .track
    var onTrack: () -> Void = {}
    var onStats: () -> Void = {}
}

private struct LandscapeNavKey: EnvironmentKey {
    static let defaultValue = LandscapeNav()
}

extension EnvironmentValues {
    var landscapeNav: LandscapeNav {
        get { self[LandscapeNavKey.self] }
        set { self[LandscapeNavKey.self] = newValue }
    }
}

enum LandscapeMetrics {
    /// Bar height and its gap to the bottom edge (above the home indicator).
    static let barHeight: CGFloat = 80
    /// Room the round settings button takes in the top-right corner.
    static let gearSize: CGFloat = 46
}

/// TRACK / STATS on the left, then `middle`, then `trailing`.
struct LandscapeBar<Middle: View, Trailing: View>: View {
    @Environment(\.landscapeNav) private var nav
    @ViewBuilder var middle: Middle
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(spacing: 14) {
            VStack(spacing: 4) {
                tabButton("TRACK", active: nav.tab == .track, action: nav.onTrack)
                tabButton("STATS", active: nav.tab == .stats, action: nav.onStats)
            }
            Rectangle().fill(Color.sbDivider).frame(width: 1).padding(.vertical, 14)
            middle
                .frame(maxWidth: .infinity, alignment: .leading)
            trailing
        }
        .padding(.leading, 8)
        .padding(.trailing, 10)
        .frame(height: LandscapeMetrics.barHeight)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .sbShadow(y: 14, blur: 40, opacity: 0.35)
    }

    private func tabButton(_ title: String, active: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.sb(12))
                .foregroundStyle(active ? Color.white : Color.sbCharcoal)
                .padding(.horizontal, 12)
                .frame(minWidth: 64)
                .frame(height: 30)
                .background(active ? Color.sbRed : Color.sbLightGrey, in: Capsule())
        }
        .buttonStyle(PressableStyle())
        .accessibilityAddTraits(active ? .isSelected : [])
    }
}

extension LandscapeBar where Trailing == EmptyView {
    init(@ViewBuilder middle: () -> Middle) {
        self.middle = middle()
        self.trailing = EmptyView()
    }
}

/// Small label over a number, as used in the bar.
struct LandscapeStat: View {
    let label: String
    let value: String
    var size: CGFloat = 28
    var labelColor: Color = .sbSecondary

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(label)
                .font(.sb(11))
                .tracking(11 * 0.04)
                .foregroundStyle(labelColor)
                .lineLimit(1)
                .cssLineHeight(1, fontSize: 11)
            Text(value)
                .font(.sbFixed(size))
                .monospacedDigit()
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .cssLineHeight(1.05, fontSize: size)
        }
        .fixedSize(horizontal: true, vertical: false)
        .accessibilityElement(children: .combine)
    }
}

/// Round white button used on the landscape map (crew, re-centre, follow).
struct LandscapeRoundButton<Label: View>: View {
    var filled: Bool = false
    let action: () -> Void
    @ViewBuilder var label: Label

    var body: some View {
        Button(action: action) {
            label
                .frame(width: 44, height: 44)
                .background(filled ? Color.sbRed : Color.white, in: Circle())
                .sbShadow(y: 4, blur: 14, opacity: 0.3)
        }
        .buttonStyle(PressableStyle())
    }
}
