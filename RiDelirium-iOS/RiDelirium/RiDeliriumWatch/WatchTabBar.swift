//
//  WatchTabBar.swift
//  RiDeliriumWatch
//

import SwiftUI

enum WatchTab: CaseIterable {
    case record, stats, friends

    var title: String {
        switch self {
        case .record: "Record"
        case .stats: "Stats"
        case .friends: "Friends"
        }
    }
}

/// A custom bar like the phone's, not `TabView` page dots: inset 18, 12 from the bottom, 80 tall, three equal tabs.
struct WatchTabBar: View {
    @Binding var selection: WatchTab
    @Environment(\.watchScale) private var scale

    var body: some View {
        HStack(spacing: 0) {
            ForEach(WatchTab.allCases, id: \.self) { tab in
                let active = selection == tab
                Button { selection = tab } label: {
                    glyph(tab, color: active ? .white : WatchPalette.secondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(active ? WatchPalette.red : Color.clear, in: Capsule())
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .padding(scale.pt(6))
        .frame(height: scale.pt(80))
        .background(WatchPalette.surface, in: Capsule())
    }

    @ViewBuilder
    private func glyph(_ tab: WatchTab, color: Color) -> some View {
        switch tab {
        case .record:
            // Ring 32 / 4 pt stroke with a 12 pt dot.
            ZStack {
                Circle().strokeBorder(color, lineWidth: scale.pt(4)).frame(width: scale.pt(32), height: scale.pt(32))
                Circle().fill(color).frame(width: scale.pt(12), height: scale.pt(12))
            }
        case .stats:
            // Three bars, 8 wide, up to 30 tall.
            HStack(alignment: .bottom, spacing: scale.pt(4)) {
                ForEach([14.0, 30.0, 22.0], id: \.self) { height in
                    RoundedRectangle(cornerRadius: scale.pt(2)).fill(color).frame(width: scale.pt(8), height: scale.pt(height))
                }
            }
            .frame(height: scale.pt(30), alignment: .bottom)
        case .friends:
            Image(systemName: "person.2.fill")
                .font(.system(size: scale.pt(32), weight: .semibold))
                .foregroundStyle(color)
        }
    }
}
