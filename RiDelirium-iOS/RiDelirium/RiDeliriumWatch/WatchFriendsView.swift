//
//  WatchFriendsView.swift
//  RiDeliriumWatch
//

import SwiftUI
import WatchKit

/// Tab 4. The crew, nearest riders first, each with a WAIT button that sends "wait for me".
struct WatchFriendsView: View {
    @EnvironmentObject private var link: WatchLink
    @EnvironmentObject private var compass: WatchCompass
    @Environment(\.watchScale) private var scale
    let highlighted: String?
    let onShowMap: () -> Void

    private var state: WatchState { link.state }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            let rows = sorted(at: context.date)
            ZStack(alignment: .topLeading) {
                ScrollView {
                    VStack(spacing: scale.pt(8)) {
                        Color.clear.frame(height: scale.pt(60))
                        Button(action: onShowMap) {
                            HStack(spacing: scale.pt(10)) {
                                Image(systemName: "map.fill").font(.system(size: scale.pt(26)))
                                Text("SHOW ON MAP").font(WatchFont.extraBold(scale.pt(22)))
                            }
                            .foregroundStyle(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: scale.pt(60))
                            .background(WatchPalette.surface2, in: RoundedRectangle(cornerRadius: scale.pt(30)))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        if rows.isEmpty {
                            Text("NO FRIENDS YET")
                                .font(WatchFont.extraBold(scale.pt(18)))
                                .foregroundStyle(WatchPalette.secondary)
                                .padding(.top, scale.pt(60))
                        }
                        ForEach(rows, id: \.friend.id) { row in
                            friendRow(row, now: context.date)
                        }
                        Color.clear.frame(height: scale.pt(100))
                    }
                    .padding(.horizontal, scale.pt(18))
                }
                Text("FRIENDS · \(rows.count)")
                    .font(WatchFont.extraBold(scale.pt(18)))
                    .foregroundStyle(WatchPalette.secondary)
                    .padding(.top, scale.pt(22))
                    .padding(.leading, scale.pt(24))
            }
        }
    }

    private struct Row {
        let friend: WatchFriend
        let look: WatchFriendState
        let distance: Double?
        let bearing: Double?
    }

    /// Riding by distance, then stopped, then offline.
    private func sorted(at date: Date) -> [Row] {
        let rows = link.extras.friends.map { friend -> Row in
            var distance: Double?
            var bearing: Double?
            if let lat = state.latitude, let lon = state.longitude, let fLat = friend.latitude, let fLon = friend.longitude {
                distance = Bearing.meters(fromLat: lat, fromLon: lon, toLat: fLat, toLon: fLon)
                bearing = Bearing.degrees(fromLat: lat, fromLon: lon, toLat: fLat, toLon: fLon)
            }
            return Row(friend: friend, look: WatchFriendState.of(friend, at: date), distance: distance, bearing: bearing)
        }
        func rank(_ look: WatchFriendState) -> Int { look == .riding ? 0 : look == .stopped ? 1 : 2 }
        return rows.sorted {
            if rank($0.look) != rank($1.look) { return rank($0.look) < rank($1.look) }
            return ($0.distance ?? .infinity) < ($1.distance ?? .infinity)
        }
    }

    private func friendRow(_ row: Row, now: Date) -> some View {
        let friend = row.friend
        let offline = row.look == .offline
        let sent = link.isWaitActive(for: friend.id, at: now)
        return HStack(spacing: scale.pt(10)) {
            FriendBubble(friend: friend, size: 54)
            VStack(alignment: .leading, spacing: scale.pt(2)) {
                Text(friend.name.uppercased())
                    .font(WatchFont.extraBold(scale.pt(23)))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                detail(row, now: now)
            }
            Spacer(minLength: 0)
            if !offline {
                Button {
                    link.sendWait(to: friend, distanceMeters: row.distance)
                    WKInterfaceDevice.current().play(.success)
                } label: {
                    Text(sent ? "SENT ✓" : "WAIT")
                        .font(WatchFont.extraBold(scale.pt(22)))
                        .foregroundStyle(sent ? WatchPalette.red : .white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(width: scale.pt(104), height: scale.pt(66))
                        .background(sent ? Color.white : WatchPalette.red, in: RoundedRectangle(cornerRadius: scale.pt(22)))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(sent || !link.isReachable)
                .accessibilityLabel("Ask \(friend.name) to wait")
            }
        }
        .padding(.leading, scale.pt(12))
        .padding(.trailing, scale.pt(8))
        .frame(height: scale.pt(84))
        .background(WatchPalette.surface, in: RoundedRectangle(cornerRadius: scale.pt(28), style: .continuous))
        .overlay {
            if highlighted == friend.id {
                RoundedRectangle(cornerRadius: scale.pt(28), style: .continuous).strokeBorder(Color(hex: friend.colorHex), lineWidth: scale.pt(3))
            }
        }
        .opacity(offline ? 0.6 : 1)
    }

    @ViewBuilder
    private func detail(_ row: Row, now: Date) -> some View {
        let friend = row.friend
        switch row.look {
        case .offline:
            Text(lastSeen(friend, now: now))
                .font(WatchFont.bold(scale.pt(15)))
                .foregroundStyle(WatchPalette.secondary)
                .lineLimit(1)
        case .riding, .stopped:
            let color = row.look == .riding ? Color(hex: friend.colorHex) : WatchPalette.secondary
            HStack(spacing: scale.pt(4)) {
                if let distance = row.distance {
                    Text(WatchFormat.short(distance, metric: state.metric))
                    if let bearing = row.bearing {
                        Image(systemName: "arrow.up")
                            .font(.system(size: scale.pt(14), weight: .bold))
                            .rotationEffect(.degrees(bearing - (compass.heading ?? 0)))
                    }
                    Text("·")
                }
                Text(row.look == .riding ? "\(WatchFormat.speed((friend.speedKmh ?? 0) / 3.6, metric: state.metric)) \(state.metric ? "KM/H" : "MPH")" : "STOPPED")
            }
            .font(WatchFont.bold(scale.pt(15)))
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
        }
    }

    private func lastSeen(_ friend: WatchFriend, now: Date) -> String {
        guard let updated = friend.updatedAt else { return "OFFLINE" }
        let minutes = max(Int(now.timeIntervalSince(updated) / 60), 0)
        return minutes < 60 ? "LAST SEEN \(minutes) MIN" : "LAST SEEN \(minutes / 60) H"
    }
}
