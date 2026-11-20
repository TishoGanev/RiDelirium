//
//  WatchMapView.swift
//  RiDeliriumWatch
//
//  Friends -> Map. A live `Map` made the watch unresponsive, so this is a still picture from
//  `MKMapSnapshotter`, redrawn every 12 seconds or after the Crown moves, with the markers drawn on top in
//  SwiftUI. It exists only while the screen is open.
//

import SwiftUI
import MapKit

struct WatchMapScreen: View {
    @EnvironmentObject private var link: WatchLink
    @Environment(\.watchScale) private var scale
    let onBack: () -> Void

    @State private var snapshot: MKMapSnapshotter.Snapshot?
    @State private var snapshotter: MKMapSnapshotter?
    @State private var task: Task<Void, Never>?
    /// Camera height in metres, moved by the Crown on a log scale.
    @State private var zoom: Double = log(1200)
    @FocusState private var focused: Bool

    private static let refresh: TimeInterval = 12

    private var state: WatchState { link.state }
    private var user: CLLocationCoordinate2D? {
        guard let lat = state.latitude, let lon = state.longitude else { return nil }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .topLeading) {
                Color(hex: 0x1B1F26)
                if let snapshot {
                    Image(uiImage: snapshot.image)
                        .resizable()
                        .frame(width: proxy.size.width, height: proxy.size.height)
                    overlay(snapshot: snapshot, size: proxy.size)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }

                Button(action: onBack) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: scale.pt(26), weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: scale.pt(60), height: scale.pt(60))
                        .background(Color.black.opacity(0.75), in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Back")
                .placed(x: 18, y: 18, scale: scale)

                Text("TAP A FRIEND TO SEND WAIT")
                    .font(WatchFont.extraBold(scale.pt(18)))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .frame(width: scale.pt(362), height: scale.pt(64))
                    .background(Color.black.opacity(0.78), in: Capsule())
                    .placed(x: 24, y: WatchScale.designHeight - 22 - 64, scale: scale)
            }
            .frame(width: proxy.size.width, height: proxy.size.height, alignment: .topLeading)
            .focusable()
            .focused($focused)
            .digitalCrownRotation($zoom, from: log(60), through: log(30000), by: 0.05, sensitivity: .medium, isContinuous: false, isHapticFeedbackEnabled: true)
            .onChange(of: zoom) { _, _ in restart(size: proxy.size) }
            .onAppear { focused = true; restart(size: proxy.size) }
            .onDisappear { stop() }
        }
        .ignoresSafeArea()
    }

    // MARK: Markers

    private func overlay(snapshot: MKMapSnapshotter.Snapshot, size: CGSize) -> some View {
        ZStack(alignment: .topLeading) {
            if link.extras.route.count > 1 {
                Path { path in
                    for (index, point) in link.extras.route.enumerated() {
                        let p = snapshot.point(for: CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon))
                        index == 0 ? path.move(to: p) : path.addLine(to: p)
                    }
                }
                .stroke(WatchPalette.red, style: StrokeStyle(lineWidth: scale.pt(7), lineCap: .round, lineJoin: .round))
            }
            if let user {
                let p = snapshot.point(for: user)
                ZStack {
                    Circle().fill(WatchPalette.userBlue.opacity(0.25)).frame(width: scale.pt(36), height: scale.pt(36))
                    Circle().fill(WatchPalette.userBlue).frame(width: scale.pt(24), height: scale.pt(24))
                        .overlay(Circle().strokeBorder(.white, lineWidth: scale.pt(3.5)))
                }
                .position(p)
            }
            TimelineView(.periodic(from: .now, by: 1)) { context in
                ZStack(alignment: .topLeading) {
                    ForEach(link.extras.friends) { friend in
                        if let lat = friend.latitude, let lon = friend.longitude {
                            let p = snapshot.point(for: CLLocationCoordinate2D(latitude: lat, longitude: lon))
                            if CGRect(origin: .zero, size: size).insetBy(dx: -40, dy: -40).contains(p) {
                                marker(friend, now: context.date).position(p)
                            }
                        }
                    }
                }
            }
        }
        .frame(width: size.width, height: size.height, alignment: .topLeading)
    }

    /// Tapping a friend sends "wait for me", the same as WAIT in the list; the marker then carries a check for 30 seconds.
    private func marker(_ friend: WatchFriend, now: Date) -> some View {
        let sent = link.isWaitActive(for: friend.id, at: now)
        return Button {
            guard !sent else { return }
            link.sendWait(to: friend, distanceMeters: distance(to: friend))
            WKInterfaceDevice.current().play(.success)
        } label: {
            FriendBubble(friend: friend, size: 58)
                .overlay(alignment: .topTrailing) {
                    if sent {
                        Image(systemName: "checkmark")
                            .font(.system(size: scale.pt(14), weight: .heavy))
                            .foregroundStyle(WatchPalette.red)
                            .frame(width: scale.pt(26), height: scale.pt(26))
                            .background(.white, in: Circle())
                            .offset(x: scale.pt(6), y: scale.pt(-6))
                    }
                }
        }
        .buttonStyle(.plain)
        .disabled(!link.isReachable || WatchFriendState.of(friend, at: now) == .offline)
    }

    private func distance(to friend: WatchFriend) -> Double? {
        guard let lat = state.latitude, let lon = state.longitude, let fLat = friend.latitude, let fLon = friend.longitude else { return nil }
        return Bearing.meters(fromLat: lat, fromLon: lon, toLat: fLat, toLon: fLon)
    }

    // MARK: Snapshots

    /// Draws one picture now and another every few seconds, until the screen closes.
    private func restart(size: CGSize) {
        task?.cancel()
        task = Task {
            while !Task.isCancelled {
                await render(size: size)
                try? await Task.sleep(for: .seconds(Self.refresh))
            }
        }
    }

    private func stop() {
        task?.cancel()
        task = nil
        snapshotter?.cancel()
        snapshotter = nil
        snapshot = nil
    }

    @MainActor
    private func render(size: CGSize) async {
        guard size.width > 0, let center = user ?? link.extras.friends.compactMap({ f in f.latitude.flatMap { lat in f.longitude.map { CLLocationCoordinate2D(latitude: lat, longitude: $0) } } }).first else { return }
        let options = MKMapSnapshotter.Options()
        options.region = MKCoordinateRegion(center: center, latitudinalMeters: exp(zoom), longitudinalMeters: exp(zoom))
        options.size = size
        options.scale = WKInterfaceDevice.current().screenScale
        let snapshotter = MKMapSnapshotter(options: options)
        self.snapshotter = snapshotter
        if let result = try? await snapshotter.start() { snapshot = result }
    }
}

/// A friend as a round marker with initials, in their colour; grey when they have gone quiet.
struct FriendBubble: View {
    let friend: WatchFriend
    /// Diameter in design points.
    let size: CGFloat
    @Environment(\.watchScale) private var scale

    var body: some View {
        TimelineView(.periodic(from: .now, by: 20)) { context in
            let offline = WatchFriendState.of(friend, at: context.date) == .offline
            Text(friend.initials)
                .font(WatchFont.extraBold(scale.pt(size * 20 / 54)))
                .foregroundStyle(offline ? WatchPalette.offlineText : .white)
                .frame(width: scale.pt(size), height: scale.pt(size))
                .background(offline ? WatchPalette.offlineFill : Color(hex: friend.colorHex), in: Circle())
                .overlay(Circle().strokeBorder(offline ? WatchPalette.offlineBorder : .white, lineWidth: scale.pt(3)))
                .shadow(color: .black.opacity(0.45), radius: 4, y: 2)
        }
    }
}

enum WatchFriendState {
    case riding, stopped, offline

    /// Same rules as the phone's rider markers: two minutes without a position is offline.
    static let liveWindow: TimeInterval = 120
    static let movingKmh = 2.0

    static func of(_ friend: WatchFriend, at date: Date) -> WatchFriendState {
        guard friend.latitude != nil, let updated = friend.updatedAt,
              date.timeIntervalSince(updated) <= liveWindow else { return .offline }
        return (friend.speedKmh ?? 0) > movingKmh ? .riding : .stopped
    }
}
