//
//  FriendsStore.swift
//  RiDelirium
//

import CoreLocation
import Foundation
import SwiftUI
import Combine
import UserNotifications
import os

@MainActor
final class FriendsStore: ObservableObject {
    /// You, as crew-mates see you. Nil until the rider has entered a name.
    @Published private(set) var me: Friend?
    /// Every crew you are in, most recently joined first.
    @Published private(set) var crews: [Crew] = [] {
        // "Sharing ends in 10 minutes" follows every change of the crews or of how long I stay in them.
        didSet { if crews != oldValue { StayReminder.sync(crews) } }
    }
    /// The crew the map shows; you switch it in Ride Together.
    @Published private(set) var selectedCrewID: String? = UserDefaults.standard.string(forKey: "selectedCrew")
    /// Members of the selected crew, without you: what the map, the crew pill, WAIT and the watch work with.
    @Published private(set) var friends: [Friend] = []
    @Published private(set) var isJoining = false
    @Published var joinError: String?
    /// What a scanned or typed code opens, waiting for the rider to pick how long to stay.
    @Published var joinPreview: CrewPreview?
    /// The crew just joined, shown as a confirmation in the Ride Together sheet.
    @Published var justJoined: Crew?
    /// Any screen can ask for the Ride Together sheet; ContentView presents it.
    @Published var isShowingRideTogether = false
    /// A scanned link that arrived before the rider entered a name; joined right after.
    @Published var pendingInvite: String?
    /// Friends asking me to wait, newest first. Polled while riding; the lock screen shows the first.
    @Published private(set) var incomingWaits: [IncomingWait] = []
    /// After "OK, waiting": whom I am waiting for, until they arrive.
    @Published private(set) var activeWait: ActiveWait?
    /// Set when the lock screen's MAP button is tapped; the map centres on this friend.
    @Published var focusFriendID: String?
    /// "NAME JUST JOINED", shown for four seconds on the share screen.
    @Published private(set) var joinBanner: String?
    /// People I rode with lately who are not in the crew now; tapping one sends an invite.
    @Published private(set) var recentRiders: [Friend] = []
    @Published private(set) var invitedIDs: Set<String> = []
    /// When "wait for me" was last sent to each friend from the crew list; the row says SENT for 30 seconds.
    @Published private(set) var waitSent: [String: Date] = [:]
    /// True when there is no server configured or the last call to it failed; the sheet then says "WE ARE OFFLINE".
    @Published private(set) var isOffline = false
    private var hasLoadedFriends = false

    /// The Ride Together server; nil when no API host is configured, and the app is then offline.
    private var remote: FriendsService?
    private var ridingLoop: Task<Void, Never>?
    private var observers: [NSObjectProtocol] = []
    private var quickRefresh: Task<Void, Never>?
    /// The push token the server already has, so it is sent once per change rather than on every refresh.
    private var sentPushToken: String?
    private static let nameKey = "riderName"

    private static let log = Logger(subsystem: "com.bgdeveloper.thesnowboarder", category: "friends")

    /// The running store, for the lock screen's "OK, waiting" button, which runs in this process.
    nonisolated(unsafe) static weak var current: FriendsStore?

    /// Stable per install, so a rider you met today isn't added twice if they reopen the sheet.
    static var deviceIdentity: String {
        if let saved = UserDefaults.standard.string(forKey: "voiceIdentity") { return saved }
        let id = UUID().uuidString
        UserDefaults.standard.set(id, forKey: "voiceIdentity")
        return id
    }

    init(service: FriendsService? = nil) {
        self.remote = service ?? RemoteFriendsService.configured(identity: Self.deviceIdentity)
        self.isOffline = self.remote == nil
        Self.current = self
        if let name = UserDefaults.standard.string(forKey: Self.nameKey) {
            startAs(name: name)
        }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: .pushTokenChanged, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in await self?.syncPushToken() }
            },
            // A friend joined or left: show it right away rather than at the next refresh.
            center.addObserver(forName: .crewPushReceived, object: nil, queue: .main) { [weak self] note in
                let event = note.userInfo?["event"] as? String
                Task { @MainActor in
                    if event == "wait" { await self?.pollWaits() }
                    // Someone who joined while our code was on screen shows up as a banner there.
                    await self?.refresh()
                    self?.refreshQuickly()
                }
            },
            center.addObserver(forName: .invitePushOpened, object: nil, queue: .main) { [weak self] note in
                guard let code = note.userInfo?["code"] as? String else { return }
                Task { @MainActor in
                    self?.isShowingRideTogether = true
                    if self?.me == nil { self?.pendingInvite = code } else { await self?.preview(code) }
                }
            },
            center.addObserver(forName: .crewPushOpened, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in
                    await self?.refresh()
                    self?.refreshQuickly()
                }
            },
        ]
        // Already allowed earlier: pick up the (possibly new) device token.
        if me != nil { Task { await Push.enable() } }
    }

    deinit {
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    /// Tells the server where to reach this phone. Needs the rider to have a name, as registering does.
    private func syncPushToken() async {
        guard me != nil, let remote, let token = Push.token, token != sentPushToken else { return }
        do {
            try await remote.registerPush(token: token, sandbox: Push.isSandbox)
            sentPushToken = token
        } catch {
            // No signal: tried again with the next refresh.
        }
    }

    var ridingNow: [Friend] { friends.filter(\.isRiding) }

    var selectedCrew: Crew? { crews.first { $0.id == selectedCrewID } ?? crews.first }

    /// The selected crew's code, for the invite card.
    var invite: Invite? { selectedCrew?.invite }

    /// In any crew, even alone in it: the rider is waiting for people to join, so their position is already on the
    /// server when the first one does.
    var isInviting: Bool { !crews.isEmpty }

    /// Makes `id` the crew the map shows.
    func selectCrew(_ id: String) {
        selectedCrewID = id
        UserDefaults.standard.set(id, forKey: "selectedCrew")
        friends = selectedCrew?.others(than: me?.id) ?? []
    }

    /// "Wait for me" from the watch. False when there is no server or no signal.
    func sendWait(to friendID: String, distanceMeters: Double?) async -> Bool {
        guard let remote else {
            Self.log.error("wait not sent: no server configured (APIHost is empty)")
            return false
        }
        do {
            let id = try await remote.sendWait(to: friendID, distanceMeters: distanceMeters)
            Self.log.info("wait sent to \(friendID, privacy: .public), request \(id ?? "-", privacy: .public)")
            if let id { watchForReply(to: id, friend: friends.first { $0.id == friendID }?.displayName ?? "Your friend") }
            return true
        } catch {
            // 404: not friends on the server (a join that never reached it); 401: no token; 429: too many taps.
            Self.log.error("wait to \(friendID, privacy: .public) failed: \(String(describing: error), privacy: .public)")
            return false
        }
    }

    /// Five minutes on, an unanswered request is reported to the sender instead of waiting in silence.
    private func watchForReply(to waitID: String, friend: String) {
        guard let remote else { return }
        Task {
            try? await Task.sleep(for: .seconds(305))
            guard let status = try? await remote.waitStatus(id: waitID), status != "accepted" else { return }
            let content = UNMutableNotificationContent()
            content.title = "RiDelirium"
            content.body = "\(friend.uppercased()) DIDN'T REPLY"
            try? await UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "no-reply-\(waitID)", content: content, trigger: nil))
        }
    }

    // MARK: - Wait requests (Live Activity)

    struct ActiveWait: Equatable {
        let id: String
        let friendID: String
        let name: String
        let initials: String
        let colorIndex: Int
        var startDistance: Double?
        let acceptedAt: Date
        var arrivedAt: Date?
    }

    /// Asks the server for requests waiting for my answer. Cheap; called every few seconds while riding.
    func pollWaits() async {
        guard let remote, me != nil, let waits = try? await remote.incomingWaits() else { return }
        if waits != incomingWaits { incomingWaits = waits }
    }

    /// "OK, waiting": tells the sender and turns the lock screen into the waiting card.
    func acceptWait(id: String) async {
        let wait = incomingWaits.first { $0.id == id }
        incomingWaits.removeAll { $0.id == id }
        if let wait {
            let rider = Friend(id: wait.fromID, displayName: wait.fromName, initials: wait.initials)
            activeWait = ActiveWait(id: id, friendID: wait.fromID, name: wait.fromName, initials: wait.initials,
                                    colorIndex: rider.colorIndex, startDistance: nil, acceptedAt: Date())
        }
        // The rider has already decided to wait; the server is told as soon as there is signal.
        for attempt in 0..<3 {
            if (try? await remote?.acceptWait(id: id)) != nil || remote == nil { break }
            try? await Task.sleep(for: .seconds(Double(attempt + 1) * 3))
        }
    }

    /// What the lock screen shows about waits and the crew right now. Moves the wait along: it ends five seconds
    /// after the friend is within reach, or after fifteen minutes.
    func liveState(user: CLLocation?, heading: Double?, now: Date = Date()) -> (friends: [LiveFriend], request: LiveRequest?, waiting: LiveWaiting?) {
        func geometry(to friend: Friend?) -> (distance: Double?, bearing: Double?) {
            guard let user, let target = friend?.coordinate else { return (nil, nil) }
            let distance = user.distance(from: CLLocation(latitude: target.latitude, longitude: target.longitude))
            let absolute = Self.bearing(from: user.coordinate, to: target)
            return (distance, (absolute - (heading ?? 0) + 360).truncatingRemainder(dividingBy: 360))
        }

        var crew = friends.compactMap { friend -> (LiveFriend, Double)? in
            let age = now.timeIntervalSince(friend.updatedAt ?? .distantPast)
            guard friend.coordinate != nil || friend.isRiding else { return nil }
            let g = geometry(to: friend)
            let status: LiveFriend.Status = age > RiderTag.liveWindow ? .offline : (friend.speedKmh ?? 0) > 2 ? .riding : .stopped
            let live = LiveFriend(initials: friend.initials, colorIndex: friend.colorIndex, distanceM: g.distance, bearingDeg: g.bearing,
                                  status: status, speed: friend.speedKmh ?? 0, name: friend.displayName)
            return (live, g.distance ?? .infinity)
        }
        crew.sort { $0.1 < $1.1 }
        let liveFriends = crew.prefix(3).map(\.0)

        if var wait = activeWait {
            let friend = friends.first { $0.id == wait.friendID }
            let g = geometry(to: friend)
            if let distance = g.distance {
                if wait.startDistance == nil { wait.startDistance = max(distance, 1) }
                if distance <= 20, wait.arrivedAt == nil { wait.arrivedAt = now }
            }
            if let arrived = wait.arrivedAt, now.timeIntervalSince(arrived) > 5 || now.timeIntervalSince(wait.acceptedAt) > 900 {
                activeWait = nil
            } else {
                activeWait = wait
                let distance = g.distance ?? wait.startDistance ?? 0
                let speed = friend?.speedKmh ?? 0
                let waiting = LiveWaiting(
                    id: wait.id, name: wait.name, initials: wait.initials, colorIndex: wait.colorIndex, distanceM: distance,
                    bearingDeg: g.bearing, speed: speed,
                    etaSec: speed > 3 ? distance / (speed / 3.6) : nil,
                    progress: wait.startDistance.map { 1 - distance / $0 } ?? 0,
                    arrived: wait.arrivedAt != nil
                )
                return (liveFriends, nil, waiting)
            }
        }

        if let first = incomingWaits.first {
            let friend = friends.first { $0.id == first.fromID }
            let g = geometry(to: friend)
            let request = LiveRequest(
                id: first.id, fromName: first.fromName, initials: first.initials,
                colorIndex: Friend(id: first.fromID, displayName: first.fromName, initials: first.initials).colorIndex,
                distanceM: g.distance ?? first.distanceM, bearingDeg: g.bearing,
                sentAt: first.receivedAt.timeIntervalSince1970, count: incomingWaits.count, friendID: first.fromID
            )
            return (liveFriends, request, nil)
        }
        return (liveFriends, nil, nil)
    }

    /// Degrees clockwise from north.
    static func bearing(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let lat1 = a.latitude * .pi / 180, lat2 = b.latitude * .pi / 180
        let dLon = (b.longitude - a.longitude) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    /// Where the server pushes updates for the running Live Activity.
    func setActivityToken(_ token: String?) async {
        guard let remote else {
            Self.log.error("live activity token not sent: no server configured")
            return
        }
        do {
            try await remote.setActivityToken(token, sandbox: Push.isSandbox)
            Self.log.info("live activity token \(token == nil ? "removed" : "registered", privacy: .public) on the server")
        } catch {
            // 401 with no name set yet: open Ride Together once so the rider is registered.
            Self.log.error("live activity token not saved: \(String(describing: error), privacy: .public)")
        }
    }

    /// Saves the rider's name and presents them to friends under it. There are no accounts:
    /// the name lives on this phone and is shared only with riders who join your code.
    func setName(_ raw: String) {
        let name = String(raw.trimmingCharacters(in: .whitespacesAndNewlines).prefix(30))
        guard !name.isEmpty else { return }
        UserDefaults.standard.set(name, forKey: Self.nameKey)
        startAs(name: name)
        Task { try? await remote?.updateName(name) }
        Task { await refresh() }
        if let pending = pendingInvite {
            pendingInvite = nil
            Task { await preview(pending) }
        }
    }

    private func startAs(name: String) {
        justJoined = nil
        self.me = Friend(id: Self.deviceIdentity, displayName: name, initials: Self.initials(from: name))
        // The server may not know what this rider rides yet (or has the default); tell it, quietly. Its answer also
        // says who the server thinks we are.
        Task {
            if let id = try? await remote?.updateSport(Self.currentSport.rawValue) { adoptServerIdentity(id) }
        }
    }

    /// The login token lives in the Keychain and survives a reinstall; the id lives in UserDefaults and does not.
    /// After a reinstall the server still knows us by the old id, so take that one; otherwise we would count
    /// ourselves as one more rider in every crew.
    private func adoptServerIdentity(_ id: String) {
        guard let me, id.uppercased() != me.id.uppercased() else { return }
        Self.log.info("identity \(me.id, privacy: .public) -> \(id, privacy: .public) (server)")
        UserDefaults.standard.set(id, forKey: "voiceIdentity")
        self.me = Friend(id: id, displayName: me.displayName, initials: me.initials)
        friends = selectedCrew?.others(than: id) ?? []
    }

    /// What this rider chose in Settings.
    static var currentSport: Sport {
        Sport(rawValue: UserDefaults.standard.string(forKey: Sport.storageKey) ?? "") ?? .board
    }

    /// Saves nothing locally (Settings already did); tells the server so friends' tags change right away.
    func setSport(_ sport: Sport) {
        Task { try? await remote?.updateSport(sport.rawValue) }
    }

    nonisolated static func initials(from name: String) -> String {
        let letters = name.split(separator: " ").prefix(2).compactMap(\.first)
        return letters.isEmpty ? "ME" : String(letters).uppercased()
    }

    /// The server is the truth: a crew you left or whose time ran out is gone from its list, and so is anyone who
    /// left a crew. Offline, we keep what we have.
    func refresh() async {
        await syncPushToken()
        guard me != nil else { return }
        guard let crews = try? await remote?.crews() else {
            isOffline = true
            return
        }
        isOffline = false
        let before = selectedCrew.map { crew in (crew.id, Set(crew.members.map(\.id))) }
        // Riders glide to their new position rather than jumping.
        withAnimation(.easeOut(duration: 0.45)) {
            self.crews = crews
            if !crews.contains(where: { $0.id == selectedCrewID }) { selectedCrewID = crews.first?.id }
            friends = selectedCrew?.others(than: me?.id) ?? []
        }
        // Someone new in the selected crew: say who, once the first load is past (not for the whole crew at launch).
        if hasLoadedFriends, let before, let crew = selectedCrew, crew.id == before.0,
           let joined = crew.members.first(where: { !before.1.contains($0.id) && $0.id != me?.id }), justJoined == nil, !isJoining {
            showJoinBanner(joined.displayName)
        }
        hasLoadedFriends = true
        let inCrews = Set(crews.flatMap { $0.members.map(\.id) })
        invitedIDs.subtract(inCrews)
        recentRiders.removeAll { inCrews.contains($0.id) }
    }

    func showJoinBanner(_ name: String) {
        joinBanner = name
        Task {
            try? await Task.sleep(for: .seconds(4))
            if joinBanner == name { joinBanner = nil }
        }
    }

    /// Recent riders for the Ride Together sheet.
    func loadRecentRiders() async {
        guard let remote, let riders = try? await remote.recentRiders() else { return }
        let inCrews = Set(crews.flatMap { $0.members.map(\.id) })
        recentRiders = riders.filter { !inCrews.contains($0.id) }
    }

    /// Sends a crew's code to a recent rider; the chip says INVITED until they join.
    func invite(_ rider: Friend, to crewID: String) {
        guard !invitedIDs.contains(rider.id) else { return }
        invitedIDs.insert(rider.id)
        Task {
            do { try await remote?.invite(riderID: rider.id, crewID: crewID) } catch { invitedIDs.remove(rider.id) }
        }
    }

    /// "Wait for me" from the crew list, the same message as the watch and the lock screen send.
    func askToWait(_ friend: Friend, distanceMeters: Double?) {
        waitSent[friend.id] = Date()
        Task {
            if await sendWait(to: friend.id, distanceMeters: distanceMeters) == false { waitSent[friend.id] = nil }
        }
    }

    func isWaitSent(to id: String, at date: Date) -> Bool {
        guard let sent = waitSent[id] else { return false }
        return date.timeIntervalSince(sent) < 30
    }

    /// Right after someone joins, look again every couple of seconds for a short while, so the new rider shows up on
    /// the map as soon as their phone sends its first position rather than at the next slow poll.
    func refreshQuickly() {
        quickRefresh?.cancel()
        quickRefresh = Task { [weak self] in
            for _ in 0..<6 {
                try? await Task.sleep(for: .seconds(2))
                if Task.isCancelled { return }
                await self?.refresh()
            }
        }
    }

    /// Creates a crew and selects it.
    func createCrew(name: String) async -> Crew? {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(30))
        guard !clean.isEmpty, let remote else { return nil }
        do {
            let crew = try await remote.createCrew(name: clean)
            crews.insert(crew, at: 0)
            selectCrew(crew.id)
            joinError = nil
            // The moment a rider sets up a crew is when "your friend joined" is worth allowing.
            await Push.enable()
            return crew
        } catch {
            joinError = (error as? FriendsError ?? .offline).localizedDescription
            return nil
        }
    }

    func renameCrew(_ id: String, to name: String) async {
        let clean = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(30))
        guard !clean.isEmpty, let crew = try? await remote?.renameCrew(id: id, name: clean) else { return }
        replace(crew)
    }

    /// Step one of joining: what a scanned QR payload, an opened invite link or a typed code opens.
    func preview(_ text: String) async {
        guard !isJoining else { return }
        guard let (code, _) = InviteLink.parse(text) else {
            joinError = FriendsError.invalidCode.localizedDescription
            return
        }
        guard me != nil else {
            joinError = FriendsError.noName.localizedDescription
            return
        }
        guard let remote else {
            joinError = FriendsError.offline.localizedDescription
            return
        }
        if crews.contains(where: { $0.code == code && $0.ownerID == me?.id }) {
            joinError = FriendsError.ownInvite.localizedDescription
            return
        }
        isJoining = true
        defer { isJoining = false }
        do {
            joinPreview = try await remote.previewCrew(code: code)
            joinError = nil
        } catch {
            joinError = (error as? FriendsError ?? .offline).localizedDescription
        }
    }

    /// Step two: join the previewed crew, sharing my location until `until`.
    func confirmJoin(until: Date) async {
        guard let preview = joinPreview, !isJoining, let remote else { return }
        isJoining = true
        defer { isJoining = false }
        do {
            let crew = try await remote.joinCrew(code: preview.code, until: Stay.clamp(until))
            crews.removeAll { $0.id == crew.id }
            crews.insert(crew, at: 0)
            selectCrew(crew.id)
            joinPreview = nil
            joinError = nil
            justJoined = crew
            await Push.enable()
            refreshQuickly()
        } catch {
            joinError = (error as? FriendsError ?? .offline).localizedDescription
        }
    }

    /// Changes when you stop sharing your location with `crewID`.
    func setStay(until: Date, in crewID: String) async {
        guard let crew = try? await remote?.setStay(crewID: crewID, until: Stay.clamp(until)) else {
            joinError = FriendsError.offline.localizedDescription
            return
        }
        replace(crew)
    }

    /// Drops `crewID`'s meet point at `coordinate`; everyone else in the crew is notified. False when the server can't be reached.
    func setMeet(_ kind: MeetPoint.Kind, inMinutes: Int, at coordinate: CLLocationCoordinate2D, crewID: String) async -> Bool {
        guard let crew = try? await remote?.setMeet(crewID: crewID, latitude: coordinate.latitude, longitude: coordinate.longitude,
                                                     kind: kind, inMinutes: inMinutes) else { return false }
        replace(crew)
        return true
    }

    /// Takes the meet point away for the whole crew.
    func clearMeet(crewID: String) async {
        guard let crew = try? await remote?.clearMeet(crewID: crewID) else { return }
        replace(crew)
    }

    private func replace(_ crew: Crew) {
        crews = crews.map { $0.id == crew.id ? crew : $0 }
        friends = selectedCrew?.others(than: me?.id) ?? []
    }

    /// Whether this rider's position is being shared with their crew right now.
    private(set) var isSharing = false

    /// Shares this rider's position with their friends, and keeps theirs fresh, while `sharing` is true: that is
    /// whenever a ride is being recorded or the rider is in a crew. Only friends see it, only the latest position
    /// is kept, and it expires on the server a minute after the app stops sending.
    func setSharing(_ sharing: Bool, location: @escaping @MainActor () -> CLLocation?) {
        guard sharing != isSharing || (sharing && ridingLoop == nil) else { return }
        ridingLoop?.cancel()
        ridingLoop = nil
        isSharing = sharing
        guard let remote, me != nil else { isSharing = false; return }
        guard sharing else {
            Task { try? await remote.setRiding(false, at: nil) }
            return
        }
        ridingLoop = Task { [weak self] in
            while !Task.isCancelled {
                try? await remote.setRiding(true, at: location())
                await self?.refresh()
                try? await Task.sleep(for: .seconds(6))
            }
        }
    }

    /// "Delete my Ride Together data" in Settings: the server forgets this rider (name, crews, waits, devices), then
    /// this phone forgets the name, token, id and crews. Recorded rides and settings stay. False when the server
    /// couldn't be reached; nothing on the phone is cleared then, so the rider can try again.
    func deleteRideTogetherData() async -> Bool {
        let name = UserDefaults.standard.string(forKey: Self.nameKey)
        // Without a name no request can register this install again while the delete is on its way.
        UserDefaults.standard.removeObject(forKey: Self.nameKey)
        ridingLoop?.cancel()
        ridingLoop = nil
        isSharing = false
        quickRefresh?.cancel()
        quickRefresh = nil
        do {
            try await remote?.deleteMe()
        } catch {
            if let name { UserDefaults.standard.set(name, forKey: Self.nameKey) }
            Self.log.error("delete my data failed: \(String(describing: error), privacy: .public)")
            return false
        }
        Keychain.delete("apiToken")
        UserDefaults.standard.removeObject(forKey: "selectedCrew")
        UserDefaults.standard.removeObject(forKey: "voiceIdentity")
        // A new install id from now on, so a later name starts as a new rider.
        if remote is RemoteFriendsService { remote = RemoteFriendsService.configured(identity: Self.deviceIdentity) }
        me = nil
        crews = []
        selectedCrewID = nil
        friends = []
        joinPreview = nil
        joinError = nil
        justJoined = nil
        pendingInvite = nil
        incomingWaits = []
        activeWait = nil
        focusFriendID = nil
        joinBanner = nil
        recentRiders = []
        invitedIDs = []
        waitSent = [:]
        sentPushToken = nil
        hasLoadedFriends = false
        Self.log.info("ride together data deleted")
        return true
    }

    /// Leaves one crew; the others in it are told.
    func leave(_ crewID: String) {
        crews.removeAll { $0.id == crewID }
        if selectedCrewID == crewID { selectedCrewID = crews.first?.id }
        friends = selectedCrew?.others(than: me?.id) ?? []
        justJoined = nil
        Task {
            try? await remote?.leaveCrew(id: crewID)
            await refresh()
            // Everyone in that crew is now a recent rider.
            await loadRecentRiders()
        }
    }
}
