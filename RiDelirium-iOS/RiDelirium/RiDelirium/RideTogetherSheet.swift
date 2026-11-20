//
//  RideTogetherSheet.swift
//  RiDelirium
//

import SwiftUI
import UIKit
import VisionKit
import Vision
import CoreImage.CIFilterBuiltins

/// Ride Together: your crews first; each crew opens its riders, its invite code and how long you stay.
struct RideTogetherSheet: View {
    @EnvironmentObject private var friends: FriendsStore
    @EnvironmentObject private var tracker: LocationTracker
    @Environment(\.dismiss) private var dismiss

    private enum Page: Hashable { case crews, detail, invite, newCrew, join }
    @State private var page: Page = .crews
    /// The crew the detail and invite pages show.
    @State private var crewID: String?
    @State private var crewNameDraft = ""
    @State private var stayEnd = Stay.defaultEnd()
    @State private var changingTime = false
    @State private var renaming = false
    @State private var typedCode = ""
    @FocusState private var isTypingCode: Bool
    @State private var nameDraft = ""
    @State private var confirmingLeave = false
    @State private var isSharingInvite = false
    @FocusState private var isTypingName: Bool

    private static let offlineAvatar = Color(hex: 0x8C939D)
    private static let statusGreen = Color(hex: 0x30D158)
    private static let statusAmber = Color(hex: 0xFFB020)
    private static let statusGrey = Color(hex: 0xC9CDD3)

    private var focused: Crew? { crewID.flatMap { id in friends.crews.first { $0.id == id } } }

    private var isOwner: Bool { focused?.ownerID == friends.me?.id }

    /// The big code is up: on the invite page, or on a crew with nobody else in it yet.
    private var isShowingCode: Bool { page == .invite || (page == .detail && focused != nil && members.isEmpty) }

    private var crewName: String { focused?.name.uppercased() ?? "YOUR CREW" }

    /// Members of the open crew, without you.
    private var members: [Friend] { focused?.others(than: friends.me?.id) ?? [] }

    private func open(_ page: Page, crew: Crew? = nil) {
        if let crew { crewID = crew.id }
        friends.joinError = nil
        self.page = page
    }

    var body: some View {
        Group {
            if let crew = friends.justJoined {
                joined(crew).padding(.horizontal, 20)
            } else if friends.me == nil {
                nameStep.padding(.horizontal, 20).padding(.top, 22)
            } else if friends.isOffline && friends.crews.isEmpty {
                offlineStep.padding(.horizontal, 20).padding(.top, 22)
            } else if let preview = friends.joinPreview {
                previewStep(preview).padding(.horizontal, 20).padding(.top, 12)
            } else {
                switch page {
                case .detail where focused != nil: crewPage.transition(.move(edge: .trailing).combined(with: .opacity))
                case .invite where focused != nil: invitePage.transition(.move(edge: .trailing).combined(with: .opacity))
                case .newCrew: newCrewPage.transition(.move(edge: .trailing).combined(with: .opacity))
                case .join: joinPage.transition(.move(edge: .trailing).combined(with: .opacity))
                default: crewsHome.transition(.move(edge: .leading).combined(with: .opacity))
                }
            }
        }
        .padding(.top, 10)
        .padding(.bottom, 28)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(Color.white)
        .animation(.easeOut(duration: 0.25), value: page)
        .animation(.easeOut(duration: 0.25), value: isTypingCode)
        // Typing collapses the camera box instead, so the field and JOIN stay above the keyboard.
        .ignoresSafeArea(.keyboard)
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .presentationCornerRadius(32)
        .task(id: friends.me) {
            await friends.refresh()
            await friends.loadRecentRiders()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .sheet(isPresented: $isSharingInvite) {
            if let crew = focused {
                ActivityView(items: ["Ride with me on RiDelirium. Tap the link to join \(crew.name), or enter the code \(Self.formatted(crew.code)).\n\(crew.invite.url.absoluteString)"]) { completed in
                    // Sent through Mail, Messages, Viber...: the invite is out, so the screen has done its job.
                    if completed { friends.isShowingRideTogether = false }
                }
                .presentationDetents([.medium, .large])
            }
        }
        .onChange(of: friends.joinBanner) { _, banner in
            if banner != nil { UINotificationFeedbackGenerator().notificationOccurred(.success) }
        }
        // The screen stays awake while the code is up, so it does not lock before the friend has scanned it.
        .onChange(of: isShowingCode) { _, showing in UIApplication.shared.isIdleTimerDisabled = showing }
        .sheet(isPresented: $changingTime) {
            ChangeTimeSheet(crewName: focused?.name ?? "Your crew", current: focused?.myExpiresAt) { end in
                if let id = crewID { Task { await friends.setStay(until: end, in: id) } }
            }
            .presentationDetents([.height(560)])
        }
        .alert("Crew name", isPresented: $renaming) {
            TextField("Crew name", text: $crewNameDraft)
            Button("Cancel", role: .cancel) {}
            Button("Save") { if let id = crewID { Task { await friends.renameCrew(id, to: crewNameDraft) } } }
        }
    }

    // MARK: - Crews home

    private var crewsHome: some View {
        TimelineView(.periodic(from: .now, by: 20)) { context in
            VStack(alignment: .leading, spacing: 12) {
                Text("RIDE TOGETHER")
                    .font(.sbFixed(32))
                    .foregroundStyle(.sbCharcoal)
                    .cssLineHeight(1, fontSize: 32)
                    .accessibilityAddTraits(.isHeader)
                    .padding(.top, 12)
                if friends.crews.isEmpty {
                    Text("Make a crew for today's ride and invite your friends, or join a friend's crew with their code.")
                        .font(.sb(17, .semibold))
                        .foregroundStyle(.sbSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                } else {
                    Text("YOUR CREWS")
                        .font(.sb(13))
                        .tracking(13 * 0.08)
                        .foregroundStyle(.sbSecondary)
                        .padding(.top, 4)
                    ScrollView {
                        VStack(spacing: 10) {
                            ForEach(friends.crews) { crew in crewCard(crew, now: context.date) }
                        }
                    }
                    .scrollBounceBehavior(.basedOnSize)
                }
                Spacer(minLength: 0)
                HStack(spacing: 10) {
                    pillButton("NEW CREW", fill: .sbRed) {
                        crewNameDraft = friends.me.map { "\($0.displayName.uppercased())'S CREW" } ?? "MY CREW"
                        open(.newCrew)
                    }
                    pillButton("JOIN CREW", fill: .sbCharcoal) { open(.join) }
                }
            }
            .padding(.horizontal, 20)
        }
    }

    private func crewCard(_ crew: Crew, now: Date) -> some View {
        let others = crew.others(than: friends.me?.id)
        let live = others.filter { isOnMap($0, now: now) }.count
        let selected = crew.id == friends.selectedCrew?.id
        return Button { open(.detail, crew: crew) } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(crew.name.uppercased())
                        .font(.sbFixed(20))
                        .foregroundStyle(.sbCharcoal)
                        .lineLimit(1)
                    HStack(spacing: 8) {
                        if !others.isEmpty {
                            HStack(spacing: -8) {
                                ForEach(Array(others.prefix(4))) { friend in
                                    Text(friend.initials)
                                        .font(.sbFixed(10))
                                        .foregroundStyle(.white)
                                        .frame(width: 26, height: 26)
                                        .background(isOnMap(friend, now: now) ? Color(hex: friend.avatarColor) : Self.offlineAvatar, in: Circle())
                                        .overlay(Circle().strokeBorder(Color.sbLightGrey, lineWidth: 2))
                                }
                            }
                        }
                        Text(others.isEmpty ? "JUST YOU · INVITE SOMEONE" : "\(live) ON THE MAP · \(others.count) \(others.count == 1 ? "RIDER" : "RIDERS")")
                            .font(.sb(12))
                            .tracking(12 * 0.04)
                            .foregroundStyle(.sbSecondary)
                            .lineLimit(1)
                    }
                    if let left = Stay.timeLeft(until: crew.myExpiresAt, now: now) {
                        Text(left).font(.sb(12)).tracking(12 * 0.04).foregroundStyle(.sbRed)
                    }
                }
                Spacer(minLength: 8)
                if selected {
                    Text("ON MAP").font(.sb(12)).tracking(12 * 0.04).foregroundStyle(.sbRed)
                }
                Chevron(color: .sbChevron, lineWidth: 2.5, size: 10)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay {
                if selected { RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.sbRed, lineWidth: 2) }
            }
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel("\(crew.name), \(others.count) riders\(selected ? ", on the map" : "")")
    }

    private func pillButton(_ title: String, fill: Color, foreground: Color = .white, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.sb(19))
                .foregroundStyle(foreground)
                .frame(maxWidth: .infinity)
                .frame(height: 58)
                .background(fill, in: Capsule())
        }
        .buttonStyle(PressableStyle())
    }

    /// Back button with a centred title, for the pages inside the sheet.
    private func backTitle(_ title: String, back: @escaping () -> Void) -> some View {
        ZStack {
            Text(title)
                .font(.sbFixed(20))
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .padding(.horizontal, 56)
            HStack {
                Button(action: back) {
                    Chevron(color: .sbCharcoal, lineWidth: 3, size: 11, pointsLeft: true)
                        .frame(width: 40, height: 40)
                        .background(Color.sbLightGrey, in: Circle())
                }
                .buttonStyle(PressableStyle())
                .accessibilityLabel("Back")
                Spacer()
            }
        }
        .padding(.top, 12)
    }

    // MARK: - Crew detail

    private var crewPage: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            if members.isEmpty {
                justYou
            } else {
                let live = members.filter { isOnMap($0, now: Date()) }.count
                Text("ON THE MAP · \(live) OF \(members.count)")
                    .font(.sb(13))
                    .tracking(13 * 0.08)
                    .foregroundStyle(.sbSecondary)
                    .padding(.horizontal, 20)
                    .padding(.bottom, -6)
                crewList
                recentRiders
                codeCard
                showOnMapButton
            }
        }
    }

    private var header: some View {
        VStack(spacing: 6) {
            backTitle(crewName) { page = .crews }
                .overlay(alignment: .bottomTrailing) { crewMenu }
            TimelineView(.everyMinute) { context in
                let left = Stay.sharing(until: focused?.myExpiresAt, now: context.date) ?? "YOU STAY UNTIL YOU LEAVE"
                Text(members.isEmpty ? "JUST YOU SO FAR · \(left)" : left)
                    .font(.sb(12))
                    .tracking(12 * 0.04)
                    .foregroundStyle(.sbSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
        .padding(.horizontal, 20)
        .alert("Leave \(focused?.name ?? "this crew")?", isPresented: $confirmingLeave) {
            Button("Leave", role: .destructive) {
                if let id = crewID { friends.leave(id) }
                page = .crews
            }
            Button("Stay", role: .cancel) {}
        } message: {
            Text(members.isEmpty
                 ? "Nobody else is in it, so the crew goes away."
                 : "You'll stop riding with \(members.map(\.displayName).joined(separator: ", ")). You can join again with its code.")
        }
    }

    /// The rarely used crew actions, kept out of the way behind "⋯".
    private var crewMenu: some View {
        Menu {
            Button("Change time", systemImage: "clock") { changingTime = true }
            if isOwner {
                Button("Rename crew", systemImage: "pencil") {
                    crewNameDraft = focused?.name ?? ""
                    renaming = true
                }
            }
            Button("Leave crew", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { confirmingLeave = true }
        } label: {
            HStack(spacing: 3) {
                ForEach(0..<3, id: \.self) { _ in Circle().fill(Color.sbCharcoal).frame(width: 5, height: 5) }
            }
            .frame(width: 40, height: 40)
            .background(Color.sbLightGrey, in: Circle())
        }
        .accessibilityLabel("Crew options")
    }

    /// Nobody else is in the crew yet, so inviting is the only thing to do: the code leads.
    private var justYou: some View {
        VStack(spacing: 16) {
            VStack(spacing: 14) {
                Group {
                    if let invite = focused?.invite, let image = QRCode.image(for: invite.url.absoluteString) {
                        Image(uiImage: image)
                            .interpolation(.none)
                            .resizable()
                            .scaledToFit()
                            .accessibilityLabel("Invite QR code")
                    } else {
                        ProgressView()
                    }
                }
                .frame(width: 210, height: 210)
                .padding(20)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Color(hex: 0xEAEAEA), lineWidth: 1.5))

                if let crew = focused {
                    Text(Self.formatted(crew.code))
                        .font(.sbFixed(36))
                        .tracking(36 * 0.1)
                        .monospacedDigit()
                        .foregroundStyle(.sbCharcoal)
                        .textSelection(.enabled)
                }

                Text("Let friends scan this, or add someone you rode with.")
                    .font(.sb(15, .semibold))
                    .foregroundStyle(.sbSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 300)
            }
            .frame(maxWidth: .infinity)

            recentRiders
            Spacer(minLength: 0)

            Button { isSharingInvite = true } label: {
                Text("SHARE CREW LINK")
                    .font(.sb(19))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 58)
                    .background(Color.sbRed, in: Capsule())
                    .sbShadow(y: 6, blur: 16, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
            .disabled(focused == nil)
            .padding(.horizontal, 20)
        }
    }

    // MARK: Crew rows

    private enum Look { case riding, stopped, offline }

    private func look(_ friend: Friend, now: Date) -> Look {
        guard friend.coordinate != nil else { return .offline }
        if now.timeIntervalSince(friend.updatedAt ?? .distantPast) > RiderTag.liveWindow { return .offline }
        return (friend.speedKmh ?? 0) > 2 ? .riding : .stopped
    }

    private func isOnMap(_ friend: Friend, now: Date) -> Bool { look(friend, now: now) != .offline }

    private struct Row {
        let friend: Friend
        let look: Look
        let distance: Double?
        let bearing: Double?
    }

    /// Riding by distance, then stopped, then offline.
    private func rows(now: Date) -> [Row] {
        let user = tracker.currentLocation
        let heading = tracker.heading
        let rows = members.map { friend -> Row in
            var distance: Double?, bearing: Double?
            if let user, let target = friend.coordinate {
                distance = user.distance(from: CLLocation(latitude: target.latitude, longitude: target.longitude))
                let absolute = FriendsStore.bearing(from: user.coordinate, to: target)
                bearing = (absolute - (heading ?? 0) + 360).truncatingRemainder(dividingBy: 360)
            }
            return Row(friend: friend, look: look(friend, now: now), distance: distance, bearing: bearing)
        }
        func rank(_ look: Look) -> Int { look == .riding ? 0 : look == .stopped ? 1 : 2 }
        return rows.sorted {
            rank($0.look) != rank($1.look) ? rank($0.look) < rank($1.look) : ($0.distance ?? .infinity) < ($1.distance ?? .infinity)
        }
    }

    private var crewList: some View {
        TimelineView(.periodic(from: .now, by: 5)) { context in
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(rows(now: context.date), id: \.friend.id) { row in
                        crewRow(row, now: context.date)
                    }
                }
                .padding(.horizontal, 20)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .frame(maxHeight: .infinity)
    }

    private func crewRow(_ row: Row, now: Date) -> some View {
        let friend = row.friend
        let offline = row.look == .offline
        let sent = friends.isWaitSent(to: friend.id, at: now)
        return HStack(spacing: 12) {
            ZStack(alignment: .bottomTrailing) {
                Text(friend.initials)
                    .font(.sbFixed(17))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(offline ? Self.offlineAvatar : Color(hex: friend.avatarColor), in: Circle())
                Circle()
                    .fill(statusColor(row.look))
                    .frame(width: 16, height: 16)
                    .overlay(Circle().strokeBorder(.white, lineWidth: 3))
                    .offset(x: 2, y: 2)
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(friend.displayName.uppercased())
                    .font(.sbFixed(19))
                    .foregroundStyle(.sbCharcoal)
                    .lineLimit(1)
                detail(row, now: now)
            }
            Spacer(minLength: 8)
            // Offline riders still get the push, so WAIT stays; only the distance is unknown.
            Button {
                friends.askToWait(friend, distanceMeters: row.distance)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            } label: {
                Text(sent ? "SENT ✓" : "WAIT")
                    .font(.sb(15))
                    .foregroundStyle(sent ? Color.sbRed : .white)
                    .padding(.horizontal, 14)
                    .frame(height: 40)
                    .background(sent ? Color.white : Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        if sent { RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(Color.sbRed, lineWidth: 1.5) }
                    }
            }
            .buttonStyle(PressableStyle())
            .disabled(sent)
            .accessibilityLabel(sent ? "Wait request sent" : "Ask \(friend.displayName) to wait")
            Button {
                if let id = crewID { friends.selectCrew(id) }
                friends.focusFriendID = friend.id
                friends.isShowingRideTogether = false
            } label: {
                Image(systemName: "mappin.and.ellipse")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.sbCharcoal)
                    .frame(width: 40, height: 40)
                    .background(Color.sbLightGrey, in: Circle())
            }
            .buttonStyle(PressableStyle())
            .disabled(friend.coordinate == nil)
            .accessibilityLabel("Show \(friend.displayName) on the map")
        }
        .padding(.vertical, 10)
        .opacity(offline ? 0.6 : 1)
        .overlay(alignment: .bottom) { Rectangle().fill(Color.sbDivider).frame(height: 1) }
    }

    private func statusColor(_ look: Look) -> Color {
        switch look {
        case .riding: Self.statusGreen
        case .stopped: Self.statusAmber
        case .offline: Self.statusGrey
        }
    }

    @ViewBuilder
    private func detail(_ row: Row, now: Date) -> some View {
        let friend = row.friend
        switch row.look {
        case .offline:
            Text(lastSeen(friend, now: now))
                .font(.sb(14))
                .foregroundStyle(.sbSecondary)
                .lineLimit(1)
        case .riding, .stopped:
            let color = row.look == .riding ? Color(hex: friend.avatarColor) : Color.sbSecondary
            HStack(spacing: 4) {
                if let distance = row.distance {
                    Text(Units(metric: UserDefaults.standard.object(forKey: "unitsMetric") as? Bool ?? true).shortDistance(meters: distance))
                    if let bearing = row.bearing {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 12, weight: .bold))
                            .rotationEffect(.degrees(bearing))
                    }
                    Text("·")
                }
                Text(row.look == .riding ? "RIDING \(speedText(friend.speedKmh ?? 0))" : "STOPPED")
            }
            .font(.sb(14))
            .foregroundStyle(color)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
    }

    private func speedText(_ kmh: Double) -> String {
        let metric = UserDefaults.standard.object(forKey: "unitsMetric") as? Bool ?? true
        return "\(Int((metric ? kmh : kmh * 0.621).rounded())) \(metric ? "KM/H" : "MPH")"
    }

    private func lastSeen(_ friend: Friend, now: Date) -> String {
        guard let updated = friend.updatedAt else { return "OFFLINE" }
        let minutes = max(Int(now.timeIntervalSince(updated) / 60), 0)
        return minutes < 60 ? "LAST SEEN \(minutes) MIN AGO" : "LAST SEEN \(minutes / 60) H AGO"
    }

    // MARK: Recent riders

    @ViewBuilder
    private var recentRiders: some View {
        if !friends.recentRiders.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Text("RECENT RIDERS")
                    .font(.sb(13))
                    .tracking(13 * 0.08)
                    .foregroundStyle(.sbSecondary)
                    .padding(.horizontal, 20)
                // Each chip is as wide as its name; more scroll.
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(friends.recentRiders) { rider in chip(rider) }
                    }
                    .padding(.horizontal, 20)
                }
                .frame(height: 52)
            }
        }
    }

    private func chip(_ rider: Friend) -> some View {
        let invited = friends.invitedIDs.contains(rider.id)
        return Button { if let id = crewID { friends.invite(rider, to: id) } } label: {
            HStack(spacing: 6) {
                Text(rider.initials)
                    .font(.sbFixed(12))
                    .foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(Color(hex: rider.avatarColor), in: Circle())
                VStack(alignment: .leading, spacing: 0) {
                    Text(rider.displayName.uppercased())
                        .font(.sbFixed(15))
                        .foregroundStyle(.sbCharcoal)
                        .lineLimit(1)
                        .fixedSize()
                    if invited {
                        Text("INVITED").font(.sb(10)).foregroundStyle(.sbRed)
                    }
                }
                if !invited {
                    Text("+").font(.sbFixed(20)).foregroundStyle(.sbRed)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 10)
            .frame(minWidth: 100)
            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
        .buttonStyle(PressableStyle())
        .disabled(invited)
        .accessibilityLabel(invited ? "\(rider.displayName) invited" : "Invite \(rider.displayName)")
    }

    // MARK: Code card and buttons

    /// The invite: tap to show the big code, or the share icon to send the link.
    private var codeCard: some View {
        HStack(spacing: 14) {
            Button { page = .invite } label: {
                HStack(spacing: 14) {
                    Group {
                        if let invite = focused?.invite, let image = QRCode.image(for: invite.url.absoluteString) {
                            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                        } else {
                            ProgressView()
                        }
                    }
                    .frame(width: 60, height: 60)
                    .padding(8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    VStack(alignment: .leading, spacing: 4) {
                        Text("INVITE · TAP TO SHOW OR SHARE")
                            .font(.sb(12))
                            .foregroundStyle(.sbSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        Text(focused.map { Self.formatted($0.code) } ?? "—")
                            .font(.sbFixed(26))
                            .tracking(26 * 0.08)
                            .monospacedDigit()
                            .foregroundStyle(.sbCharcoal)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Invite code, tap to enlarge")

            Button { isSharingInvite = true } label: {
                Image(systemName: "square.and.arrow.up")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(.sbCharcoal)
                    .frame(width: 44, height: 44)
                    .background(Color.white, in: Circle())
            }
            .buttonStyle(PressableStyle())
            .accessibilityLabel("Share crew link")
        }
        .padding(14)
        .frame(maxWidth: .infinity)
        .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 20)
    }

    private var showOnMapButton: some View {
        let selected = focused?.id == friends.selectedCrew?.id
        return pillButton(selected ? "ON THE MAP" : "SHOW ON MAP", fill: selected ? .sbCharcoal : .sbRed) {
            if let id = crewID { friends.selectCrew(id) }
            friends.isShowingRideTogether = false
        }
        .padding(.horizontal, 20)
    }

    // MARK: - Invite, new crew, join

    private var invitePage: some View {
        VStack(alignment: .leading, spacing: 18) {
            backTitle(crewName) { page = .detail }
            shareTab
        }
        .padding(.horizontal, 20)
    }

    private var joinPage: some View {
        VStack(alignment: .leading, spacing: 18) {
            backTitle("JOIN A CREW") { page = .crews }
            joinTab
            errorText
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
    }

    @ViewBuilder
    private var errorText: some View {
        if let error = friends.joinError {
            Text(error)
                .font(.sb(14, .semibold))
                .foregroundStyle(.sbRed)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var newCrewPage: some View {
        VStack(alignment: .leading, spacing: 14) {
            backTitle("NEW CREW") { page = .crews }
            Text("Give it a name your friends will recognise. You can change it later.")
                .font(.sb(17, .semibold))
                .foregroundStyle(.sbSecondary)
                .fixedSize(horizontal: false, vertical: true)
            TextField("Crew name", text: $crewNameDraft)
                .font(.sb(22))
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit(createCrew)
                .padding(.horizontal, 16)
                .frame(height: 56)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            Button(action: createCrew) {
                Text("CREATE")
                    .font(.sb(20))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            .disabled(crewNameDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            errorText
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
    }

    private func createCrew() {
        Task {
            if let crew = await friends.createCrew(name: crewNameDraft) { open(.invite, crew: crew) }
        }
    }

    /// What the code opens, and how long to stay.
    private func previewStep(_ preview: CrewPreview) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            backTitle("JOIN A CREW") { friends.joinPreview = nil; friends.joinError = nil }
            Text(preview.name.uppercased())
                .font(.sbFixed(32))
                .foregroundStyle(.sbCharcoal)
                .lineLimit(2)
                .minimumScaleFactor(0.6)
            Text("\(preview.ownerName) · \(preview.memberCount) \(preview.memberCount == 1 ? "rider" : "riders")")
                .font(.sb(17, .semibold))
                .foregroundStyle(.sbSecondary)
            if preview.isMember {
                Text("You are already in this crew. Joining again changes how long you stay.")
                    .font(.sb(15, .semibold))
                    .foregroundStyle(.sbSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            StayPicker(end: $stayEnd)
                .padding(.top, 4)
            Button { Task { await friends.confirmJoin(until: stayEnd) } } label: {
                Text(friends.isJoining ? "JOINING…" : "JOIN · UNTIL \(Stay.clock(stayEnd))")
                    .font(.sb(20))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            .disabled(friends.isJoining)
            errorText
            Spacer(minLength: 0)
        }
        .onAppear { stayEnd = Stay.defaultEnd() }
    }

    private var shareTab: some View {
        VStack(spacing: 16) {
            Group {
                if let invite = focused?.invite, let image = QRCode.image(for: invite.url.absoluteString) {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .accessibilityLabel("Invite QR code")
                } else {
                    ProgressView()
                }
            }
            .frame(width: 228, height: 228)
            .padding(22)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(Color(hex: 0xEAEAEA), lineWidth: 1.5))

            if let invite = focused?.invite {
                Text(Self.formatted(invite.code))
                    .font(.sbFixed(38))
                    .tracking(38 * 0.1)
                    .monospacedDigit()
                    .foregroundStyle(.sbCharcoal)
                    .textSelection(.enabled)
                    .accessibilityLabel("Code \(invite.code.map(String.init).joined(separator: " "))")
            }

            Text("Friends scan with their camera or in RiDelirium. They appear in the crew as soon as they join.")
                .font(.sb(16, .semibold))
                .foregroundStyle(.sbSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 300)
                .fixedSize(horizontal: false, vertical: true)

            if let name = friends.joinBanner {
                HStack(spacing: 8) {
                    Circle().fill(Self.statusGreen).frame(width: 10, height: 10)
                    Text("\(name.uppercased()) JUST JOINED")
                        .font(.sb(15))
                        .foregroundStyle(Color(hex: 0x1F7A3A))
                }
                .padding(.horizontal, 16)
                .frame(height: 40)
                .background(Color(hex: 0xEAF6EE), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .transition(.scale.combined(with: .opacity))
            }

            Spacer(minLength: 0)

            Button { isSharingInvite = true } label: {
                Text("SHARE CREW LINK")
                    .font(.sb(19))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 58)
                    .background(Color.sbRed, in: Capsule())
                    .sbShadow(y: 6, blur: 16, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
            .disabled(focused == nil)
        }
        .frame(maxWidth: .infinity)
        .animation(.easeOut(duration: 0.25), value: friends.joinBanner)
    }

    // MARK: Join tab

    @ViewBuilder
    private var joinTab: some View {
        VStack(alignment: .leading, spacing: 16) {
            if !isTypingCode {
                Group {
                    if QRScannerView.isAvailable {
                        QRScannerView { scanned in
                            Task { await friends.preview(scanned) }
                        }
                    } else {
                        VStack(spacing: 10) {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 28, weight: .semibold))
                            Text("CAMERA SCANNING ISN'T AVAILABLE HERE")
                                .font(.sb(14))
                                .multilineTextAlignment(.center)
                        }
                        .foregroundStyle(Color.white.opacity(0.8))
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.sbMapBackground)
                    }
                }
                .frame(height: 300)
                .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay {
                    if friends.isJoining { ProgressView().tint(.white) }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
            }

            Text("ENTER CODE")
                .font(.sb(13))
                .tracking(13 * 0.08)
                .foregroundStyle(.sbSecondary)

            HStack(spacing: 10) {
                TextField("XXXX-XXXX", text: $typedCode)
                    .font(.sb(22))
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .submitLabel(.join)
                    .focused($isTypingCode)
                    .onSubmit(joinTypedCode)
                    .onChange(of: typedCode) { _, text in
                        let formatted = Self.autoDash(text)
                        if formatted != text { typedCode = formatted }
                    }
                    .padding(.horizontal, 16)
                    .frame(height: 56)
                    .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

                Button(action: joinTypedCode) {
                    Text("JOIN")
                        .font(.sb(20))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 26)
                        .frame(height: 56)
                        .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                }
                .buttonStyle(PressableStyle())
                .disabled(friends.isJoining)
            }
        }
    }

    private func joinTypedCode() {
        isTypingCode = false
        Task { await friends.preview(typedCode) }
    }

    /// "w858b7ba" -> "W858-B7BA": letters and digits only, a dash after the fourth, eight at most.
    static func autoDash(_ text: String) -> String {
        let characters = text.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(8)
        let head = characters.prefix(4), tail = characters.dropFirst(4)
        return tail.isEmpty ? String(head) : "\(head)-\(tail)"
    }

    // MARK: - Offline

    /// No server configured, or it can't be reached: nothing here works without it.
    private var offlineStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("RIDE TOGETHER")
                .font(.sbFixed(32))
                .foregroundStyle(.sbCharcoal)
                .cssLineHeight(1, fontSize: 32)
                .accessibilityAddTraits(.isHeader)

            HStack(spacing: 10) {
                Image(systemName: "wifi.slash").font(.system(size: 18, weight: .bold))
                Text("WE ARE OFFLINE").font(.sb(20))
            }
            .foregroundStyle(.sbRed)
            .padding(.top, 24)

            Text("Check your signal. Your crews come back as soon as you are connected.")
                .font(.sb(17, .semibold))
                .foregroundStyle(.sbSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)

            Button { Task { await friends.refresh() } } label: {
                Text("TRY AGAIN")
                    .font(.sb(20))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            .padding(.top, 24)
        }
    }

    // MARK: - Name

    /// Nearby riders need something to call you by. There are no accounts; the name stays on this phone.
    private var nameStep: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("YOUR NAME")
                .font(.sbFixed(32))
                .foregroundStyle(.sbCharcoal)
                .cssLineHeight(1, fontSize: 32)
                .accessibilityAddTraits(.isHeader)

            Text("This is what riders see when they join your code. It isn't stored anywhere but on your phone.")
                .font(.sb(17, .semibold))
                .foregroundStyle(.sbSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 10)

            TextField("Your name", text: $nameDraft)
                .font(.sb(22))
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.continue)
                .focused($isTypingName)
                .onSubmit(saveName)
                .padding(.horizontal, 16)
                .frame(height: 56)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .padding(.top, 24)

            Button(action: saveName) {
                Text("CONTINUE")
                    .font(.sb(20))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            .disabled(nameDraft.trimmingCharacters(in: .whitespaces).isEmpty)
            .padding(.top, 12)
        }
        .onAppear { isTypingName = true }
    }

    private func saveName() {
        friends.setName(nameDraft)
    }

    // MARK: - Joined

    private func joined(_ crew: Crew) -> some View {
        VStack(spacing: 18) {
            Text("YOU'RE RIDING WITH")
                .font(.sb(16))
                .tracking(16 * 0.08)
                .foregroundStyle(.sbSecondary)
                .padding(.top, 40)
            Text(crew.name.uppercased())
                .font(.sbFixed(32))
                .foregroundStyle(.sbCharcoal)
                .multilineTextAlignment(.center)
            Text(([("\(crew.members.count) riders")] + [Stay.timeLeft(until: crew.myExpiresAt)?.lowercased()].compactMap { $0 }).joined(separator: " · "))
                .font(.sb(16, .semibold))
                .foregroundStyle(.sbSecondary)

            Button {
                dismiss()
            } label: {
                Text("LET'S RIDE")
                    .font(.sb(22))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 64)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
            }
            .buttonStyle(PressableStyle())
            .padding(.top, 20)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .contain)
    }

    private static func formatted(_ code: String) -> String {
        code.count == 8 ? "\(code.prefix(4))-\(code.suffix(4))" : code
    }
}

private enum QRCode {
    static func image(for string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cgImage = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

/// Live QR scanning with VisionKit. Needs a device camera, so it's unavailable in the simulator.
private struct QRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    static var isAvailable: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        DispatchQueue.main.async { try? scanner.startScanning() }
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {}

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(onScan: onScan) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void

        init(onScan: @escaping (String) -> Void) {
            self.onScan = onScan
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for case .barcode(let barcode) in addedItems {
                if let payload = barcode.payloadStringValue {
                    onScan(payload)
                    return
                }
            }
        }
    }
}


/// The system share sheet, with a callback that says whether the invite was actually sent (SwiftUI's ShareLink has none).
private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    let onFinish: (Bool) -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: items, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, completed, _, _ in onFinish(completed) }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Picks the exact minute sharing stops: day, hour and minute wheels, with 2 H, 4 H and TONIGHT as quick picks.
private struct StayPicker: View {
    @Binding var end: Date

    var body: some View {
        TimelineView(.everyMinute) { context in
            let now = context.date
            VStack(alignment: .leading, spacing: 12) {
                Text("SHARE MY LOCATION UNTIL")
                    .font(.sb(13))
                    .tracking(13 * 0.08)
                    .foregroundStyle(.sbSecondary)
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(Stay.clock(end))
                        .font(.sbFixed(54))
                        .monospacedDigit()
                        .foregroundStyle(.sbCharcoal)
                    Text("\(Stay.day(end, now: now)) · \(Stay.duration(from: now, to: end))")
                        .font(.sb(15))
                        .tracking(15 * 0.04)
                        .foregroundStyle(.sbSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .accessibilityElement(children: .combine)
                DatePicker("Share until", selection: $end, in: Stay.range(from: now), displayedComponents: [.date, .hourAndMinute])
                    .datePickerStyle(.wheel)
                    .labelsHidden()
                    .environment(\.locale, Locale(identifier: "en_GB"))
                    .frame(maxWidth: .infinity)
                    .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                HStack(spacing: 8) {
                    ForEach(Stay.Preset.allCases, id: \.self) { preset in
                        Button { end = preset.until(from: Date()) } label: {
                            Text(preset.title)
                                .font(.sbFixed(15))
                                .foregroundStyle(.sbCharcoal)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .overlay(Capsule().strokeBorder(Color(hex: 0xE4E4E4), lineWidth: 1.5))
                                .contentShape(Capsule())
                        }
                        .buttonStyle(PressableStyle())
                        .accessibilityLabel(preset == .tonight ? "Until tonight" : "For \(preset.title.lowercased())")
                    }
                }
            }
        }
    }
}

/// "Change time" from the crew menu: the same picker, opening on the current end time.
private struct ChangeTimeSheet: View {
    let crewName: String
    let save: (Date) -> Void
    @State private var end: Date
    @Environment(\.dismiss) private var dismiss

    init(crewName: String, current: Date?, save: @escaping (Date) -> Void) {
        self.crewName = crewName
        self.save = save
        let start = current.flatMap { $0 > Date() ? $0 : nil } ?? Stay.defaultEnd()
        _end = State(initialValue: Stay.clamp(start))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(crewName.uppercased())
                .font(.sbFixed(26))
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            StayPicker(end: $end)
            Button {
                save(end)
                dismiss()
            } label: {
                Text("SAVE · UNTIL \(Stay.clock(end))")
                    .font(.sb(20))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(PressableStyle())
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 28)
        .presentationDragIndicator(.visible)
    }
}
