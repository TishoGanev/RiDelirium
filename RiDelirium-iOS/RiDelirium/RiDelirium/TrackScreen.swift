//
//  TrackScreen.swift
//  RiDelirium
//

import SwiftUI
import MapKit

struct TrackScreen: View {
    /// Opens Settings > Apple Health, for riders whose pulse isn't coming from a watch.
    var onOpenHealth: () -> Void = {}
    @EnvironmentObject private var tracker: LocationTracker
    @EnvironmentObject private var watch: PhoneWatchBridge
    @EnvironmentObject private var friends: FriendsStore
    @AppStorage("unitsMetric") private var unitsMetric = true
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    @State private var cameraPosition: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var lastCamera: MapCamera?
    @State private var follow = false
    @State private var showingEveryone = false
    /// How big the recording card is (mini: only pause + stop). Remembered across launches.
    @AppStorage("trackCardSize") private var cardSizeRaw = CardSize.compact.rawValue
    /// The rider picked on the map; a card offers "wait for me" to them.
    @State private var selectedFriendID: String?
    @State private var ignoreUserCameraUntil: Date = .distantPast
    /// Where a long press on the map wants to drop the crew's meet point.
    @State private var meetDraft: MeetDraft?
    @State private var confirmingClearMeet = false
    /// The meet point whose banner was closed on this phone (by its time); the pin stays.
    @State private var hiddenMeetAt: Date?

    private enum CardSize: String { case mini, compact, full }
    private var cardSize: CardSize { CardSize(rawValue: cardSizeRaw) ?? .compact }
    private var cardExpanded: Bool { cardSize == .full }

    private struct MeetDraft: Identifiable {
        let id = UUID()
        let coordinate: CLLocationCoordinate2D
    }

    private var units: Units { Units(metric: unitsMetric) }
    private static let followCamera: MapCameraPosition = .userLocation(followsHeading: false, fallback: .automatic)

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                map

                if verticalSizeClass == .compact {
                    landscapeOverlay(proxy)
                } else {
                VStack(spacing: 0) {
                    topChips
                        .padding(.top, proxy.safeAreaInsets.top + 4)
                        .padding(.horizontal, 14)
                    meetBanner
                        .padding(.top, 10)
                        .padding(.horizontal, 14)
                    Spacer(minLength: 0)
                    bottomStack
                        .padding(.horizontal, 12)
                        .padding(.bottom, 104)
                }
                }
            }
            .ignoresSafeArea()
        }
        .onChange(of: friends.focusFriendID) { _, id in
            guard let id else { return }
            if let friend = friends.friends.first(where: { $0.id == id }) { centerOn(friend) }
            friends.focusFriendID = nil
        }
        .sheet(item: $meetDraft) { draft in
            if let crew = friends.selectedCrew {
                MeetPointSheet(crewName: crew.name) { kind, minutes in
                    await friends.setMeet(kind, inMinutes: minutes, at: draft.coordinate, crewID: crew.id)
                }
            }
        }
        .confirmationDialog("Remove the meet point?", isPresented: $confirmingClearMeet, titleVisibility: .visible) {
            Button("Remove for everyone", role: .destructive) {
                if let id = friends.selectedCrew?.id { Task { await friends.clearMeet(crewID: id) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It disappears from everyone's map in the crew.")
        }
    }

    // MARK: - Map

    private var map: some View {
        MapReader { proxy in
        Map(position: $cameraPosition) {
            // The crew's meet point, under the riders.
            if let meet = friends.selectedCrew?.meet {
                Annotation("", coordinate: meet.coordinate, anchor: .bottom) {
                    MeetPin(meet: meet)
                        .onTapGesture { confirmingClearMeet = true }
                }
                .annotationTitles(.hidden)
            }
            if tracker.trackCoordinates.count > 1 {
                MapPolyline(coordinates: tracker.trackCoordinates)
                    .stroke(Color.sbRed, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
            }
            // Riders sit above your track and, being drawn first, below your own dot; the bottom tip of each tag is their position.
            ForEach(sharedFriends) { friend in
                if let coordinate = friend.coordinate {
                    Annotation("", coordinate: coordinate, anchor: .bottom) {
                        RiderTag(friend: friend, units: units, compact: compactMarkers)
                            .onTapGesture {
                                centerOn(friend)
                                selectedFriendID = friend.id
                            }
                    }
                    .annotationTitles(.hidden)
                }
            }
            // You, drawn like a friend sees you: your sport, your speed, "ME", in red. The tag's tip is your position.
            if let location = tracker.currentLocation {
                Annotation("", coordinate: location.coordinate, anchor: compactMarkers ? .center : .bottom) {
                    RiderTag(friend: meTag(at: location), units: units, compact: compactMarkers, sport: FriendsStore.currentSport, tint: .sbRed)
                        .accessibilityLabel("You")
                }
                .annotationTitles(.hidden)
            }
        }
        .mapControls {}
        .environment(\.colorScheme, .dark)
        .background(Color.sbMapBackground)
        .onMapCameraChange(frequency: .onEnd) { context in
            lastCamera = context.camera
        }
        .onChange(of: cameraPosition) { _, position in
            // A tap on an overlaid button also reaches MapKit, which drops user tracking a moment
            // after we asked for it. Re-assert our own camera move instead of treating it as the user's.
            if position.positionedByUser, Date() < ignoreUserCameraUntil {
                cameraPosition = Self.followCamera
                return
            }
            // Programmatic follow keeps `followsUserLocation`; MapKit replaces it when the user moves the map.
            if !position.followsUserLocation { follow = false }
        }
        // Gestures are the reliable signal that the user took over, so follow never resumes by itself.
        .simultaneousGesture(DragGesture(minimumDistance: 8).onChanged { _ in userMovedMap() })
        .simultaneousGesture(MagnifyGesture().onChanged { _ in userMovedMap() })
        // Long press while in a crew: drop a meet point there.
        .simultaneousGesture(
            LongPressGesture(minimumDuration: 0.5)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .onEnded { value in
                    guard case .second(true, let drag?) = value, friends.selectedCrew != nil,
                          let coordinate = proxy.convert(drag.location, from: .local) else { return }
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    meetDraft = MeetDraft(coordinate: coordinate)
                }
        )
        }
    }

    private func userMovedMap() {
        showingEveryone = false
        guard follow else { return }
        stopFollowing()
    }

    // MARK: - Landscape

    /// Sideways: crew pill and map buttons along the top, one low bar along the bottom (Landscape.swift).
    private func landscapeOverlay(_ proxy: GeometryProxy) -> some View {
        let insets = proxy.safeAreaInsets
        return VStack(spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                topChips
                meetBanner
                    .frame(maxWidth: 380)
                Spacer(minLength: 0)
                if !sharedFriends.isEmpty {
                    showEveryoneButton
                }
                mapButton
            }
            // Leaves the corner to the settings button that ContentView puts there.
            .padding(.trailing, LandscapeMetrics.gearSize + 10)

            Spacer(minLength: 0)

            HStack {
                Spacer(minLength: 0)
                selectedFriendCard
                    .frame(maxWidth: 460)
            }

            LandscapeBar {
                if tracker.isTracking {
                    landscapeLiveStats
                } else {
                    HStack(spacing: 28) {
                        LandscapeStat(label: "YOUR ALTITUDE", value: altitudeValue)
                        Button { friends.isShowingRideTogether = true } label: {
                            CrewStat(friends: friends.friends, compact: true)
                        }
                        .buttonStyle(PressableStyle())
                    }
                }
            } trailing: {
                landscapeButtons
            }
        }
        .padding(.top, insets.top + 12)
        .padding(.leading, insets.leading + 8)
        .padding(.trailing, insets.trailing + 8)
        .padding(.bottom, max(insets.bottom, 8) + 4)
    }

    private var landscapeLiveStats: some View {
        HStack(alignment: .center, spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    PulsingDot(size: 7, pulses: !tracker.isPaused)
                    Text(tracker.isPaused ? "PAUSED" : units.duration(tracker.elapsed))
                        .font(.sb(11))
                        .tracking(11 * 0.04)
                        .monospacedDigit()
                }
                .foregroundStyle(.sbRed)
                speedPulseRow(sizes: [40], lineHeight: 0.95)
            }
            .fixedSize()
            LandscapeStat(label: "DISTANCE", value: units.distance(meters: tracker.traveledDistance), size: 20)
            LandscapeStat(label: "MAX SPEED", value: maxSpeedValue, size: 20)
            LandscapeStat(label: "ALTITUDE", value: altitudeValue, size: 20)
        }
    }

    @ViewBuilder
    private var landscapeButtons: some View {
        if tracker.isTracking {
            HStack(spacing: 10) {
                Button(action: togglePause) {
                    Image(systemName: tracker.isPaused ? "play.fill" : "pause.fill")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.sbGreyButtonText)
                        .frame(width: 60, height: 60)
                        .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                .buttonStyle(PressableStyle(pressedBrightness: -0.05))
                .accessibilityLabel(tracker.isPaused ? "Resume" : "Pause")

                Button(action: stopTracking) {
                    HStack(spacing: 10) {
                        RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 14, height: 14)
                        Text("STOP").font(.sb(20))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .frame(height: 60)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
                }
                .buttonStyle(PressableStyle())
            }
        } else {
            Button(action: startTracking) {
                HStack(spacing: 10) {
                    Circle().fill(.white).frame(width: 14, height: 14)
                    (Text("START TRACKING").font(.sb(20))
                        + Text(" · \(gpsStatus)").font(.sb(12)))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 22)
                .frame(height: 60)
                .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
        }
    }

    // MARK: - Top chips

    private var topChips: some View {
        HStack(spacing: 10) {
            Button { friends.isShowingRideTogether = true } label: {
                CrewPill(friends: friends.friends, crew: friends.selectedCrew, crewCount: friends.crews.count)
            }
            .buttonStyle(PressableStyle())
        }
    }

    // MARK: - Meet point

    /// Where and when the crew meets; tap to see it on the map, ✕ to hide the banner on this phone.
    @ViewBuilder
    private var meetBanner: some View {
        if let meet = friends.selectedCrew?.meet, hiddenMeetAt != meet.at {
            TimelineView(.periodic(from: .now, by: 20)) { context in
                let distance = tracker.currentLocation.map { $0.distance(from: CLLocation(latitude: meet.latitude, longitude: meet.longitude)) }
                let by = meet.byID == friends.me?.id ? "YOU" : (meet.byName?.uppercased() ?? "YOUR CREW")
                let detail = ([distance.map { units.shortDistance(meters: $0) }] + ["SET BY \(by)"]).compactMap { $0 }.joined(separator: " · ")
                HStack(spacing: 12) {
                    Image(systemName: "mappin.and.ellipse")
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 40, height: 40)
                        .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    VStack(alignment: .leading, spacing: 3) {
                        Text("MEET \(meet.kind.place) · \(meet.when(now: context.date))")
                            .font(.sbFixed(17))
                            .foregroundStyle(.sbCharcoal)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(detail)
                            .font(.sb(13))
                            .foregroundStyle(.sbSecondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Button { hiddenMeetAt = meet.at } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.sbSecondary)
                            .frame(width: 32, height: 32)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Hide")
                }
                .padding(.vertical, 10)
                .padding(.leading, 12)
                .padding(.trailing, 6)
                .background(Color.white.opacity(0.96), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                .sbShadow(y: 8, blur: 24, opacity: 0.35)
                .contentShape(Rectangle())
                .onTapGesture { centerOn(meet.coordinate) }
            }
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }

    // MARK: - Bottom stack

    private var bottomStack: some View {
        VStack(alignment: .trailing, spacing: 12) {
            if !sharedFriends.isEmpty {
                showEveryoneButton
            }
            mapButton
            selectedFriendCard
            card
        }
    }

    /// Tap a rider on the map, then WAIT: the same "wait for me" as the crew list, the watch and the lock screen.
    @ViewBuilder
    private var selectedFriendCard: some View {
        if let id = selectedFriendID, let friend = friends.friends.first(where: { $0.id == id }) {
            TimelineView(.periodic(from: .now, by: 5)) { context in
                let distance = tracker.currentLocation.flatMap { user in
                    friend.coordinate.map { user.distance(from: CLLocation(latitude: $0.latitude, longitude: $0.longitude)) }
                }
                let sent = friends.isWaitSent(to: friend.id, at: context.date)
                HStack(spacing: 12) {
                    Text(friend.initials)
                        .font(.sbFixed(16))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .background(Color(hex: friend.avatarColor), in: Circle())
                    VStack(alignment: .leading, spacing: 2) {
                        Text(friend.displayName.uppercased())
                            .font(.sbFixed(19))
                            .foregroundStyle(.sbCharcoal)
                            .lineLimit(1)
                        if let distance {
                            Text(units.shortDistance(meters: distance))
                                .font(.sb(14))
                                .foregroundStyle(.sbSecondary)
                        }
                    }
                    Spacer(minLength: 8)
                    Button {
                        friends.askToWait(friend, distanceMeters: distance)
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                    } label: {
                        Text(sent ? "SENT ✓" : "WAIT")
                            .font(.sb(16))
                            .foregroundStyle(sent ? Color.sbRed : .white)
                            .padding(.horizontal, 18)
                            .frame(height: 44)
                            .background(sent ? Color.white : Color.sbRed, in: Capsule())
                            .overlay { if sent { Capsule().strokeBorder(Color.sbRed, lineWidth: 1.5) } }
                    }
                    .buttonStyle(PressableStyle())
                    .disabled(sent)
                    .accessibilityLabel(sent ? "Wait request sent" : "Ask \(friend.displayName) to wait")

                    Button { selectedFriendID = nil } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundStyle(.sbCharcoal)
                            .frame(width: 36, height: 36)
                            .background(Color.sbLightGrey, in: Circle())
                    }
                    .buttonStyle(PressableStyle())
                    .accessibilityLabel("Close")
                }
                .padding(.vertical, 10)
                .padding(.leading, 12)
                .padding(.trailing, 10)
                .frame(maxWidth: .infinity)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .sbShadow(y: 8, blur: 24, opacity: 0.35)
            }
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    @ViewBuilder
    private var mapButton: some View {
        if !tracker.isTracking {
            Button(action: recenter) {
                LocationPin()
                    .frame(width: 48, height: 48)
                    .background(Color.white, in: Circle())
                    .sbShadow(y: 4, blur: 14, opacity: 0.35)
            }
            .buttonStyle(PressableStyle())
            .accessibilityLabel("Re-centre map")
        } else {
            Button { follow ? stopFollowing() : startFollowing() } label: {
                NavigationArrow(filled: follow)
                    .frame(width: 20, height: 20)
                    .frame(width: 48, height: 48)
                    .background(follow ? Color.sbRed : Color.white, in: Circle())
                    .sbShadow(y: 4, blur: 14, opacity: 0.35)
            }
            .buttonStyle(PressableStyle())
            .accessibilityLabel(follow ? "Stop following" : "Follow me")
            .accessibilityAddTraits(follow ? .isSelected : [])
        }
    }

    /// While recording, the compact card keeps the map visible; the grabber opens the full one, the chevron shrinks it to pause + stop.
    @ViewBuilder
    private var card: some View {
        if tracker.isTracking && cardSize == .mini {
            miniCard
        } else if tracker.isTracking && !cardExpanded {
            compactCard
        } else {
            statsCard
        }
    }

    private var grabber: some View {
        Capsule()
            .fill(Color(hex: 0xD6D6D6))
            .frame(width: 36, height: 4)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle().inset(by: -20))
            .onTapGesture { setCardSize(cardExpanded ? .compact : .full) }
            .gesture(
                DragGesture(minimumDistance: 8).onEnded { value in
                    if value.translation.height < -16 { setCardSize(.full) }
                    if value.translation.height > 16 { setCardSize(cardExpanded ? .compact : .mini) }
                }
            )
            .accessibilityLabel(cardExpanded ? "Show less" : "Show more")
            .accessibilityAddTraits(.isButton)
    }

    private func setCardSize(_ size: CardSize) {
        withAnimation(.easeOut(duration: 0.25)) { cardSizeRaw = size.rawValue }
    }

    /// The smallest card: only pause and stop, so the map shows. The handle brings the compact card back.
    private var miniCard: some View {
        HStack(spacing: 10) {
            Button { setCardSize(.compact) } label: {
                VStack(spacing: 6) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.sbSecondary)
                    PulsingDot(size: 7, pulses: !tracker.isPaused)
                }
                .frame(width: 32, height: 52)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Show ride stats")

            Button(action: togglePause) {
                Image(systemName: tracker.isPaused ? "play.fill" : "pause.fill")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(.sbGreyButtonText)
                    .frame(width: 52, height: 52)
                    .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressableStyle(pressedBrightness: -0.05))
            .accessibilityLabel(tracker.isPaused ? "Resume" : "Pause")

            Button(action: stopTracking) {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 13, height: 13)
                    Text("STOP").font(.sb(18))
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .frame(height: 52)
                .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .sbShadow(y: 6, blur: 16, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
        }
        .padding(.vertical, 10)
        .padding(.leading, 6)
        .padding(.trailing, 10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .sbShadow(y: 14, blur: 40, opacity: 0.45)
        .simultaneousGesture(
            DragGesture(minimumDistance: 12).onEnded { value in
                if value.translation.height < -16 { setCardSize(.compact) }
            }
        )
        .transition(.scale(scale: 0.9, anchor: .bottomTrailing).combined(with: .opacity))
    }

    private var compactCard: some View {
        VStack(spacing: 10) {
            grabber

            HStack(spacing: 12) {
                Button { setCardSize(.mini) } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(.sbSecondary)
                        .frame(width: 28, height: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .padding(.trailing, -6)
                .accessibilityLabel("Shrink to pause and stop")

                HStack(spacing: 6) {
                    PulsingDot(size: 7, pulses: !tracker.isPaused)
                    Text(tracker.isPaused ? "PAUSED" : "RECORDING · \(units.duration(tracker.elapsed))")
                        .font(.sb(12))
                        .tracking(12 * 0.04)
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .cssLineHeight(1, fontSize: 12)
                }
                .foregroundStyle(.sbRed)
                Spacer(minLength: 0)

                Button(action: togglePause) {
                    Image(systemName: tracker.isPaused ? "play.fill" : "pause.fill")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(.sbGreyButtonText)
                        .frame(width: 52, height: 52)
                        .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(PressableStyle(pressedBrightness: -0.05))
                .accessibilityLabel(tracker.isPaused ? "Resume" : "Pause")

                Button(action: stopTracking) {
                    HStack(spacing: 8) {
                        RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 13, height: 13)
                        Text("STOP").font(.sb(18))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .frame(height: 52)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .sbShadow(y: 6, blur: 16, opacity: 0.3, color: .sbRed)
                }
                .buttonStyle(PressableStyle())
            }

            // Speed and pulse get the whole width, so both can be as big as the phone allows.
            speedPulseRow(sizes: [56, 52, 48, 44, 40, 36, 32, 28], lineHeight: 1)

            VStack(spacing: 10) {
                Rectangle().fill(Color(hex: 0xEFEFEF)).frame(height: 1)
                HStack(alignment: .top, spacing: 12) {
                    compactStat("DISTANCE", units.distance(meters: tracker.traveledDistance))
                    compactStat("MAX SPEED", maxSpeedValue)
                    compactStat("ALTITUDE", altitudeValue)
                }
            }
        }
        .padding(.top, 8)
        .padding(.horizontal, 14)
        .padding(.bottom, 14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .sbShadow(y: 14, blur: 40, opacity: 0.45)
    }

    private func compactStat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.sb(11))
                .foregroundStyle(.sbSecondary)
                .cssLineHeight(1, fontSize: 11)
            Text(value)
                .font(.sbFixed(20))
                .monospacedDigit()
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .cssLineHeight(1.05, fontSize: 20)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var statsCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            if tracker.isTracking {
                grabber
                    .padding(.bottom, 12)
                liveHeader
                    .padding(.bottom, 14)
            }

            // Before a ride, distance and max speed would only read zero.
            HStack(alignment: .top, spacing: 12) {
                if tracker.isTracking {
                    statColumn("DISTANCE", units.distance(meters: tracker.traveledDistance))
                    statColumn("MAX SPEED", maxSpeedValue)
                }
                if tracker.isTracking {
                    statColumn("ALTITUDE", altitudeValue)
                } else {
                    statColumn("YOUR ALTITUDE", altitudeValue)
                    Button { friends.isShowingRideTogether = true } label: {
                        CrewStat(friends: friends.friends)
                    }
                    .buttonStyle(PressableStyle())
                }
            }
            .padding(.bottom, tracker.isTracking && tracker.currentG != nil ? 14 : 18)

            // Only on phones with an accelerometer.
            if tracker.isTracking, let g = tracker.currentG {
                HStack(alignment: .top, spacing: 12) {
                    statColumn("G-FORCE", String(format: "%.2f G", g))
                    statColumn("MAX G", tracker.maxG.map { String(format: "%.2f G", $0) } ?? "--")
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 0)
                }
                .padding(.bottom, 18)
            }

            // No watch, so no pulse to show: a way into what the app does with Apple Health.
            if !tracker.isTracking, !watch.hasWatch {
                pulseBadge(size: 22, linkLabel: "APPLE HEALTH")
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 14)
            }

            buttons
        }
        .padding(.top, tracker.isTracking ? 8 : 20)
        .padding(.horizontal, 18)
        .padding(.bottom, 16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        .sbShadow(y: 14, blur: 40, opacity: 0.45)
    }

    private var liveHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                PulsingDot()
                Text("\(tracker.isPaused ? "PAUSED" : "RECORDING") · \(units.duration(tracker.elapsed))\(tracker.isPaused ? "" : " · " + activityLabel)")
                    .font(.sb(14))
                    .tracking(14 * 0.04)
                    .monospacedDigit()
                    .cssLineHeight(1, fontSize: 14)
            }
            .foregroundStyle(.sbRed)

            speedPulseRow(sizes: [80, 74, 68, 62, 56, 50, 44, 38], tracking: 0.02, lineHeight: 0.9)
        }
    }

    private var speedText: String {
        String(format: "%.0f", tracker.isPaused ? 0 : units.speedValue(metersPerSecond: tracker.currentSpeed))
    }

    /// Speed and heart rate side by side at the same size, the largest that fits.
    private func speedPulseRow(sizes: [CGFloat], tracking: CGFloat = 0, lineHeight: CGFloat) -> some View {
        SpeedPulseRow(speed: speedText, unit: units.speedUnit, sizes: sizes, tracking: tracking, lineHeight: lineHeight) { size, template in
            pulseBadge(size: size, lineHeight: lineHeight, template: template)
        }
    }

    /// Live heart rate from the Apple Watch beside the speed. Without a watch it points to Settings > Apple Health.
    @ViewBuilder
    private func pulseBadge(size: CGFloat, lineHeight: CGFloat = 1, template: Bool = false, linkLabel: String = "HEALTH") -> some View {
        if let bpm = watch.currentHeartRate {
            PulseBadge(text: template ? "188" : String(Int(bpm.rounded())), unit: "BPM", size: size, lineHeight: lineHeight)
        } else if watch.hasWatch {
            PulseBadge(text: template ? "188" : "--", unit: "BPM", size: size, lineHeight: lineHeight)
        } else {
            Button(action: onOpenHealth) {
                PulseBadge(text: linkLabel, unit: nil, size: size, isLink: true)
            }
            .buttonStyle(PressableStyle())
            .accessibilityLabel("Apple Health. Open Apple Health settings")
        }
    }

    private var activityLabel: String {
        switch tracker.activity {
        case .run: "RIDING"
        case .lift: "LIFT"
        case .still: "STOPPED"
        }
    }

    private func statColumn(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.sb(13))
                .tracking(13 * 0.04)
                .foregroundStyle(.sbSecondary)
                .cssLineHeight(1, fontSize: 13)
            Text(value)
                .font(.sbFixed(28))
                .monospacedDigit()
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .cssLineHeight(1.05, fontSize: 28)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var buttons: some View {
        if tracker.isTracking {
            GeometryReader { proxy in
                let available = proxy.size.width - 10
                HStack(spacing: 10) {
                    Button(action: togglePause) {
                        Text(tracker.isPaused ? "RESUME" : "PAUSE")
                            .font(.sb(22))
                            .foregroundStyle(.sbGreyButtonText)
                            .frame(width: available / 2.4, height: 68)
                            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    }
                    .buttonStyle(PressableStyle(pressedBrightness: -0.05))

                    Button(action: stopTracking) {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 3).fill(.white).frame(width: 16, height: 16)
                            Text("STOP").font(.sb(22))
                        }
                        .foregroundStyle(.white)
                        .frame(width: available * 1.4 / 2.4, height: 68)
                        .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
                    }
                    .buttonStyle(PressableStyle())
                }
            }
            .frame(height: 68)
        } else {
            Button(action: startTracking) {
                HStack(spacing: 12) {
                    Circle().fill(.white).frame(width: 16, height: 16)
                    (Text("START TRACKING").font(.sb(22))
                        + Text(" · \(gpsStatus)").font(.sb(13)))
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 68)
                .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
        }
    }

    // MARK: - Values

    private var maxSpeedValue: String {
        units.speed(metersPerSecond: tracker.maxSpeed)
    }

    private var altitudeValue: String {
        guard let altitude = tracker.currentLocation?.altitude else { return "--" }
        return units.altitude(meters: altitude)
    }

    private var gpsStatus: String {
        guard let location = tracker.currentLocation, location.horizontalAccuracy >= 0 else {
            return "SEARCHING GPS…"
        }
        let accuracy = Int(units.altitudeValue(meters: location.horizontalAccuracy).rounded())
        let unit = units.altitudeUnit.uppercased()
        return location.horizontalAccuracy <= 20
            ? "GPS ±\(accuracy) \(unit)"
            : "WEAK GPS ±\(accuracy) \(unit)"
    }

    // MARK: - Actions

    private func startTracking() {
        tracker.startTracking()
        startFollowing()
    }

    private func stopTracking() {
        tracker.stopTracking()
        follow = false
    }

    private func togglePause() {
        if tracker.isPaused {
            tracker.resumeTracking()
        } else {
            tracker.pauseTracking()
        }
    }

    /// Friends who are riding and sharing a position, so they can be drawn on the map.
    private var sharedFriends: [Friend] {
        friends.friends.filter { $0.coordinate != nil }
    }

    /// Zoomed far out, tags would pile up, so each rider becomes a small coloured dot.
    private var compactMarkers: Bool {
        (lastCamera?.distance ?? 0) > 8000
    }

    /// Centres the map on a rider without turning on Follow.
    private func centerOn(_ friend: Friend) {
        guard let coordinate = friend.coordinate else { return }
        centerOn(coordinate)
    }

    private func centerOn(_ coordinate: CLLocationCoordinate2D) {
        follow = false
        showingEveryone = false
        ignoreUserCameraUntil = Date().addingTimeInterval(0.6)
        withAnimation(.easeOut(duration: 0.45)) {
            cameraPosition = .camera(MapCamera(
                centerCoordinate: coordinate,
                distance: lastCamera?.distance ?? 1500,
                heading: lastCamera?.heading ?? 0,
                pitch: lastCamera?.pitch ?? 0
            ))
        }
    }

    private var showEveryoneButton: some View {
        Button(action: showEveryone) {
            Image(systemName: "person.2.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(showingEveryone ? Color.white : Color.sbCharcoal)
                .frame(width: 48, height: 48)
                .background(showingEveryone ? Color.sbCharcoal : Color.white, in: Circle())
                .sbShadow(y: 4, blur: 14, opacity: 0.35)
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel("Show everyone on the map")
        .accessibilityAddTraits(showingEveryone ? .isSelected : [])
    }

    /// Your own tag: moving while a ride records and you are not paused, otherwise stopped.
    private func meTag(at location: CLLocation) -> Friend {
        let moving = tracker.isTracking && !tracker.isPaused
        return Friend(id: friends.me?.id ?? "me", displayName: "ME", initials: "ME",
                      speedKmh: moving ? max(0, location.speed) * 3.6 : 0, updatedAt: Date())
    }

    /// Zooms out to fit you and every friend who is sharing a position, keeping them clear of the stats card.
    private func showEveryone() {
        var points = sharedFriends.compactMap(\.coordinate)
        if let here = tracker.currentLocation?.coordinate { points.append(here) }
        guard let first = points.first else { return }
        var minLat = first.latitude, maxLat = first.latitude, minLon = first.longitude, maxLon = first.longitude
        for point in points {
            minLat = min(minLat, point.latitude); maxLat = max(maxLat, point.latitude)
            minLon = min(minLon, point.longitude); maxLon = max(maxLon, point.longitude)
        }
        // Room around the riders (at least about 400 m), and extra below so the stats card doesn't cover them.
        let latSpan = max((maxLat - minLat) * 1.5, 0.004)
        let lonSpan = max((maxLon - minLon) * 1.5, 0.004)
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2 - latSpan * 0.2,
            longitude: (minLon + maxLon) / 2
        )
        follow = false
        showingEveryone = true
        ignoreUserCameraUntil = Date().addingTimeInterval(0.6)
        withAnimation(.easeOut(duration: 0.45)) {
            cameraPosition = .region(MKCoordinateRegion(
                center: center,
                span: MKCoordinateSpan(latitudeDelta: latSpan * 1.4, longitudeDelta: lonSpan)
            ))
        }
    }

    private func startFollowing() {
        follow = true
        showingEveryone = false
        moveCameraToUser()
    }

    private func stopFollowing() {
        follow = false
        ignoreUserCameraUntil = .distantPast
        if let lastCamera {
            cameraPosition = .camera(lastCamera)
        }
    }

    private func recenter() {
        showingEveryone = false
        moveCameraToUser()
    }

    private func moveCameraToUser() {
        ignoreUserCameraUntil = Date().addingTimeInterval(0.6)
        withAnimation(.easeOut(duration: 0.45)) {
            cameraPosition = Self.followCamera
        }
    }
}

// MARK: - Components

/// Slim bar along the top: invites you to ride with someone, or shows your crew and who is on the map.
/// A small heart with the rider's pulse, or a "PULSE ›" link when there is no watch to read it from.
/// Unit beside a hero number (KM/H, MPH, BPM): one size for speed and pulse so they read as a pair.
private func heroUnitSize(_ numberSize: CGFloat) -> CGFloat {
    max(13, numberSize * 0.3)
}

/// Speed and pulse at one size: the largest of `sizes` that fits the row. The size is measured
/// against at least two speed digits and a three-digit pulse, so it doesn't jump as the numbers change.
/// Units stay on one line.
private struct SpeedPulseRow<Pulse: View>: View {
    let speed: String
    let unit: String
    let sizes: [CGFloat]
    var tracking: CGFloat = 0
    var lineHeight: CGFloat = 1
    @ViewBuilder let pulse: (_ size: CGFloat, _ template: Bool) -> Pulse

    var body: some View {
        ViewThatFits(in: .horizontal) {
            ForEach(sizes, id: \.self) { size in
                row(size, template: true)
                    .hidden()
                    .accessibilityHidden(true)
                    .overlay { row(size, template: false) }
            }
        }
    }

    private func row(_ size: CGFloat, template: Bool) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 0) {
            Text(template ? String(repeating: "8", count: max(2, speed.count)) : speed)
                .font(.sbFixed(size))
                .tracking(-size * tracking)
                .monospacedDigit()
                .foregroundStyle(.sbCharcoal)
                .cssLineHeight(lineHeight, fontSize: size)
            Text(unit)
                .font(.sb(heroUnitSize(size)))
                .foregroundStyle(.sbSecondary)
                .padding(.leading, size * 0.1)
            Spacer(minLength: size * 0.3)
            pulse(size, template)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.5)
    }
}

private struct PulseBadge: View {
    let text: String
    let unit: String?
    /// Point size of the pulse number, the same as the speed beside it.
    let size: CGFloat
    var lineHeight: CGFloat = 1
    var isLink = false

    var body: some View {
        let linkSize = min(14, size * 0.5)
        HStack(alignment: .lastTextBaseline, spacing: isLink ? 4 : size * 0.08) {
            Image(systemName: "heart.fill")
                .font(.system(size: isLink ? linkSize * 1.15 : size * 0.5))
                .foregroundStyle(.sbRed)
                .alignmentGuide(.lastTextBaseline) { $0[.bottom] - 1 }
            if isLink {
                Text(text)
                    .font(.sb(linkSize))
                    .tracking(linkSize * 0.04)
                    .foregroundStyle(.sbSecondary)
            } else {
                Text(text)
                    .font(.sbFixed(size))
                    .monospacedDigit()
                    .foregroundStyle(.sbCharcoal)
                    .cssLineHeight(lineHeight, fontSize: size)
            }
            if let unit {
                Text(unit)
                    .font(.sb(heroUnitSize(size)))
                    .foregroundStyle(.sbSecondary)
            }
            if isLink {
                Chevron(color: .sbSecondary, lineWidth: 2, size: linkSize * 0.7)
            }
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, isLink ? 10 : 0)
        .padding(.vertical, isLink ? 6 : 0)
        .background(isLink ? Color.sbLightGrey : .clear, in: Capsule())
        .accessibilityElement(children: .combine)
    }
}

private struct CrewPill: View {
    let friends: [Friend]
    let crew: Crew?
    let crewCount: Int

    private static let offlineGrey = Color(hex: 0x8C939D)

    var body: some View {
        // Who counts as online ages on its own, so re-read the clock now and then.
        TimelineView(.periodic(from: .now, by: 20)) { context in
            content(now: context.date)
        }
        .padding(.leading, 8)
        .padding(.trailing, 14)
        .frame(height: 48)
        .frame(maxWidth: .infinity)
        .background(Color.white, in: Capsule())
        .sbShadow(y: 6, blur: 18, opacity: 0.35)
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isButton)
        .accessibilityLabel(crew.map { "Crew \($0.name)" } ?? "Ride with someone")
    }

    /// On the map: has a position and was heard from within the live window.
    static func isLive(_ friend: Friend, now: Date) -> Bool {
        friend.coordinate != nil && now.timeIntervalSince(friend.updatedAt ?? .distantPast) <= RiderTag.liveWindow
    }

    private func content(now: Date) -> some View {
        let live = friends.filter { Self.isLive($0, now: now) }
        let ordered = live + friends.filter { !Self.isLive($0, now: now) }
        let offline = friends.count - live.count

        return HStack(spacing: 12) {
            if crew == nil {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.sbRed)
                    .padding(.leading, 8)
                Text("RIDE WITH SOMEONE")
                    .font(.sb(16, .extraBold))
                    .foregroundStyle(.sbCharcoal)
                    .lineLimit(1)
            } else {
                if friends.isEmpty {
                    Image(systemName: "person.2.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.sbRed)
                        .padding(.leading, 8)
                }
                HStack(spacing: -10) {
                    ForEach(Array(ordered.prefix(3))) { friend in
                        SportGlyph(sport: friend.rideSport, size: 18)
                            .foregroundStyle(.white)
                            .frame(width: 32, height: 32)
                            .background(Self.isLive(friend, now: now) ? Color(hex: friend.avatarColor) : Self.offlineGrey, in: Circle())
                            .overlay(Circle().strokeBorder(.white, lineWidth: 2))
                    }
                }
                VStack(alignment: .leading, spacing: 3) {
                    Text(title(ordered))
                        .font(.sb(16, .extraBold))
                        .foregroundStyle(.sbCharcoal)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .cssLineHeight(1, fontSize: 16)
                    Text(subtitle(online: live.count, offline: offline))
                        .font(.sb(11))
                        .tracking(11 * 0.04)
                        .foregroundStyle(.sbSecondary)
                        .lineLimit(1)
                        .cssLineHeight(1, fontSize: 11)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.right")
                .font(.system(size: 9, weight: .heavy))
                .foregroundStyle(.sbChevron)
        }
    }

    private func title(_ ordered: [Friend]) -> String {
        let name = crew?.name.uppercased() ?? "YOUR CREW"
        return crewCount > 1 ? "\(name) · \(crewCount) CREWS" : name
    }

    private func subtitle(online: Int, offline: Int) -> String {
        if friends.isEmpty { return "JUST YOU · INVITE SOMEONE" }
        return offline > 0 ? "\(online) ON THE MAP · \(offline) OFFLINE" : "\(online) ON THE MAP"
    }
}

/// Idle stats card: how many of your crew are on the map (same rule as the crew pill).
private struct CrewStat: View {
    let friends: [Friend]
    /// Landscape bar: left-aligned and a line shorter.
    var compact = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 20)) { context in
            let online = friends.filter { CrewPill.isLive($0, now: context.date) }.count
            let offline = friends.count - online
            Group {
                if compact {
                    LandscapeStat(label: "YOUR CREW", value: friends.isEmpty ? "INVITE" : "\(online) ONLINE")
                } else {
                    content(online: online, offline: offline)
                }
            }
                .accessibilityElement(children: .ignore)
                .accessibilityAddTraits(.isButton)
                .accessibilityLabel(friends.isEmpty
                    ? "Your crew, no crew, invite a friend"
                    : "Your crew, \(online) online, \(offline) offline")
        }
        .frame(maxWidth: compact ? nil : .infinity, alignment: .trailing)
        .contentShape(Rectangle())
    }

    private func content(online: Int, offline: Int) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text("YOUR CREW")
                .font(.sb(13))
                .tracking(13 * 0.04)
                .foregroundStyle(.sbSecondary)
                .cssLineHeight(1, fontSize: 13)
                .padding(.bottom, 6)
            HStack(spacing: 6) {
                if online > 0 {
                    Circle()
                        .fill(Color(hex: 0x30D158))
                        .frame(width: 9, height: 9)
                }
                Text(friends.isEmpty ? "NO CREW" : "\(online) ONLINE")
                    .font(.sbFixed(28))
                    .monospacedDigit()
                    .foregroundStyle(friends.isEmpty ? Color.sbSecondary : Color.sbCharcoal)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .cssLineHeight(1.05, fontSize: 28)
            }
            Text(friends.isEmpty ? "INVITE A FRIEND" : "\(offline) OFFLINE")
                .font(.sb(12))
                .tracking(12 * 0.04)
                .foregroundStyle(friends.isEmpty ? Color(hex: 0xED213D) : Color.sbSecondary)
                .lineLimit(1)
                .cssLineHeight(1, fontSize: 12)
                .padding(.top, 5)
        }
    }
}

struct UserDot: View {
    var body: some View {
        Circle()
            .fill(Color.sbUserDot)
            .overlay(Circle().strokeBorder(.white, lineWidth: 3))
            .frame(width: 22, height: 22)
            .background(
                Circle()
                    .fill(Color(red: 46 / 255, green: 110 / 255, blue: 200 / 255).opacity(0.22))
                    .padding(-14)
            )
    }
}

private struct PulsingDot: View {
    var size: CGFloat = 9
    var pulses = true
    @State private var dimmed = false

    var body: some View {
        Circle()
            .fill(Color.sbRed)
            .frame(width: size, height: size)
            .opacity(dimmed && pulses ? 0.25 : 1)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.7).repeatForever(autoreverses: true)) {
                    dimmed = true
                }
            }
    }
}

/// Teardrop pin from the design: an 18 pt square with three round corners, rotated −45°, with a white centre.
private struct LocationPin: View {
    var body: some View {
        UnevenRoundedRectangle(topLeadingRadius: 9, bottomLeadingRadius: 0, bottomTrailingRadius: 9, topTrailingRadius: 9)
            .fill(Color.sbCharcoal)
            .frame(width: 18, height: 18)
            .overlay(Circle().fill(.white).frame(width: 6, height: 6))
            .rotationEffect(.degrees(-45))
    }
}

private struct NavigationArrow: View {
    let filled: Bool

    var body: some View {
        let shape = ArrowShape()
        if filled {
            shape.fill(.white)
        } else {
            shape.stroke(Color.sbRed, style: StrokeStyle(lineWidth: 2, lineJoin: .round))
        }
    }

    private struct ArrowShape: Shape {
        func path(in rect: CGRect) -> Path {
            let sx = rect.width / 20, sy = rect.height / 20
            var path = Path()
            path.move(to: CGPoint(x: 10 * sx, y: 2 * sy))
            path.addLine(to: CGPoint(x: 17 * sx, y: 17 * sy))
            path.addLine(to: CGPoint(x: 10 * sx, y: 13.5 * sy))
            path.addLine(to: CGPoint(x: 3 * sx, y: 17 * sy))
            path.closeSubpath()
            return path
        }
    }
}

// MARK: - Meet point

/// The crew's meet point on the map: what it is and when, above a red dot on the spot.
private struct MeetPin: View {
    let meet: MeetPoint

    var body: some View {
        TimelineView(.periodic(from: .now, by: 20)) { context in
            VStack(spacing: 0) {
                VStack(spacing: 2) {
                    Text("MEET HERE")
                        .font(.sb(10))
                        .tracking(10 * 0.08)
                        .foregroundStyle(.white.opacity(0.85))
                    Text("\(meet.kind.title) · \(meet.when(now: context.date))")
                        .font(.sbFixed(15))
                        .foregroundStyle(.white)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                MeetPinTip()
                    .fill(Color.sbRed)
                    .frame(width: 16, height: 9)
                Circle()
                    .fill(Color.sbRed)
                    .frame(width: 14, height: 14)
                    .overlay(Circle().strokeBorder(.white, lineWidth: 3))
                    .padding(.top, 2)
            }
            .sbShadow(y: 6, blur: 16, opacity: 0.4)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Meet point, \(meet.kind.place.lowercased())")
    }
}

private struct MeetPinTip: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: rect.minX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
            path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
            path.closeSubpath()
        }
    }
}

/// After a long press on the map: what is at the spot and when to meet, then send it to the crew.
private struct MeetPointSheet: View {
    let crewName: String
    let onSend: (MeetPoint.Kind, Int) async -> Bool
    @Environment(\.dismiss) private var dismiss
    @State private var kind: MeetPoint.Kind = .lift
    @State private var minutes = 10
    @State private var sending = false
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("MEET POINT")
                .font(.sbFixed(28))
                .foregroundStyle(.sbCharcoal)
                .padding(.top, 24)
            Text("Everyone in \(crewName) sees it on their map and gets a notification.")
                .font(.sb(15, .semibold))
                .foregroundStyle(.sbSecondary)
                .fixedSize(horizontal: false, vertical: true)
            label("WHAT'S HERE")
            SBSegmentedControl(options: MeetPoint.Kind.allCases.map { ($0.title, $0) }, selection: $kind, fillsWidth: true)
            label("WHEN")
            SBSegmentedControl(options: [("NOW", 0), ("10 MIN", 10), ("30 MIN", 30)], selection: $minutes, fillsWidth: true)
            if failed {
                Text("Couldn't reach the server. Try again.")
                    .font(.sb(14, .semibold))
                    .foregroundStyle(.sbRed)
            }
            Spacer(minLength: 0)
            Button {
                sending = true
                Task {
                    let ok = await onSend(kind, minutes)
                    sending = false
                    if ok {
                        UINotificationFeedbackGenerator().notificationOccurred(.success)
                        dismiss()
                    } else {
                        failed = true
                    }
                }
            } label: {
                Text(sending ? "SENDING…" : "SEND TO \(crewName.uppercased())")
                    .font(.sb(19))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .padding(.horizontal, 16)
                    .frame(maxWidth: .infinity)
                    .frame(height: 58)
                    .background(Color.sbRed, in: Capsule())
            }
            .buttonStyle(PressableStyle())
            .disabled(sending)
        }
        .padding(.horizontal, 20)
        .padding(.bottom, 20)
        .presentationDetents([.height(450)])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(32)
    }

    private func label(_ text: String) -> some View {
        Text(text)
            .font(.sb(13))
            .tracking(13 * 0.08)
            .foregroundStyle(.sbSecondary)
            .padding(.top, 4)
    }
}
