//
//  Backend.swift
//  RiDelirium
//
//  Ride Together types. There are no accounts: the server keeps a random id, a name and who rides with whom.
//

import CoreLocation
import Foundation

nonisolated struct Friend: Identifiable, Equatable, Codable {
    let id: String
    let displayName: String
    let initials: String
    let isRiding: Bool
    /// Where they were last, and how fast they went; only set while a recent position is known.
    let latitude: Double?
    let longitude: Double?
    let speedKmh: Double?
    /// When that position was sent.
    let updatedAt: Date?
    /// What they ride; nil for riders saved before this was shared.
    let sport: Sport?

    /// The sport to draw: boards unless the rider says otherwise.
    var rideSport: Sport { sport ?? .board }

    /// Stable per rider, and the same everywhere the rider shows up (map tag, crew bar, avatars).
    /// The brand red is left out on purpose: it belongs to the app and to your own track.
    private static let riderColors: [UInt32] = [0x3D7FD9, 0x3A9E57, 0x8C6AD6, 0xC17F1E, 0xD2556B]

    /// Position in the palette; the server computes the same number for Live Activity pushes.
    var colorIndex: Int {
        let hash = id.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        return hash % Self.riderColors.count
    }

    var avatarColor: UInt32 { Self.riderColors[colorIndex] }

    var coordinate: CLLocationCoordinate2D? {
        guard let latitude, let longitude else { return nil }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    /// A rider as others see them.
    init(id: String, displayName: String, initials: String, isRiding: Bool = true,
         latitude: Double? = nil, longitude: Double? = nil, speedKmh: Double? = nil, updatedAt: Date? = nil,
         sport: Sport? = nil) {
        self.id = id
        self.displayName = displayName
        self.initials = initials
        self.isRiding = isRiding
        self.latitude = latitude
        self.longitude = longitude
        self.speedKmh = speedKmh
        self.updatedAt = updatedAt
        self.sport = sport
    }
}

/// An invite to ride together. The QR code encodes `url`; scanning it opens the app and joins.
struct Invite: Equatable {
    let code: String
    /// Only set for old ridelirium:// codes, which carried the inviter; the https link does not.
    let inviter: Friend?

    var url: URL { InviteLink.url(for: code) }
}

enum InviteLink {
    /// Legacy link, still accepted from older QR codes.
    static let scheme = "ridelirium"

    /// Invite links are https://<host>/join/<code>, tappable in Viber, WhatsApp and the like.
    static let webHost = "ridelirium.com"

    static func url(for code: String) -> URL {
        var components = URLComponents()
        components.scheme = "https"
        components.host = webHost
        components.path = "/join/\(code)"
        return components.url!
    }

    /// Whether `url` is an invite for this app: https://<web host>/join/<code> or the legacy ridelirium:// form.
    static func isInvite(_ url: URL) -> Bool {
        if url.scheme == scheme { return url.host == "join" }
        return url.scheme == "https" && url.host == webHost && url.path.hasPrefix("/join/")
    }

    /// Accepts a scanned or opened invite URL, or a typed code such as "ABCD-2345".
    static func parse(_ text: String) -> (code: String, inviter: Friend?)? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed), let url = components.url, isInvite(url) else {
            return normalize(trimmed).map { ($0, nil) }
        }
        guard let code = normalize(components.scheme == scheme ? components.path : String(components.path.dropFirst("/join/".count))) else { return nil }
        let items = Dictionary((components.queryItems ?? []).map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { a, _ in a })
        var inviter: Friend?
        if let id = items["id"], !id.isEmpty, let name = items["name"], !name.isEmpty {
            inviter = Friend(id: id, displayName: name, initials: items["initials"] ?? FriendsStore.initials(from: name))
        }
        return (code, inviter)
    }

    private static func normalize(_ raw: String) -> String? {
        let code = raw.uppercased().filter { $0.isLetter || $0.isNumber }
        return code.count == 8 ? code : nil
    }
}

/// A group of riders who see each other on the map. `members` includes you.
nonisolated struct Crew: Identifiable, Equatable {
    let id: String
    let name: String
    let code: String
    let ownerID: String
    /// When your membership ends; nil = until you leave.
    let myExpiresAt: Date?
    let members: [Friend]
    /// Where the crew meets next; nil when nobody dropped a meet point (or it is long past).
    var meet: MeetPoint? = nil

    var invite: Invite { Invite(code: code, inviter: nil) }

    func others(than meID: String?) -> [Friend] { members.filter { $0.id != meID } }
}

/// One meet point per crew: any member drops it with a long press on the map, everyone sees it.
nonisolated struct MeetPoint: Equatable {
    enum Kind: String, CaseIterable {
        case lift, hut, parking, here

        var title: String { rawValue.uppercased() }

        /// "MEET AT THE LIFT", "MEET HERE".
        var place: String { self == .here ? "HERE" : "AT THE \(title)" }
    }

    let latitude: Double
    let longitude: Double
    let kind: Kind
    /// When to meet.
    let at: Date
    let byID: String?
    let byName: String?

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }

    /// "NOW", "IN 8 MIN", "4 MIN AGO".
    func when(now: Date = Date()) -> String {
        let minutes = Int((at.timeIntervalSince(now) / 60).rounded())
        if minutes > 0 { return "IN \(minutes) MIN" }
        if minutes < 0 { return "\(-minutes) MIN AGO" }
        return "NOW"
    }
}

/// What a code opens, shown before joining.
nonisolated struct CrewPreview: Equatable {
    let code: String
    let name: String
    let ownerName: String
    let memberCount: Int
    let isMember: Bool
}

/// How long a rider shares their location with a crew: an exact end time, to the minute, at most 30 days ahead.
nonisolated enum Stay {
    static let maxDays = 30

    /// The quick picks beside the wheels.
    enum Preset: CaseIterable, Hashable {
        case twoHours, fourHours, tonight

        var title: String {
            switch self {
            case .twoHours: "2 H"
            case .fourHours: "4 H"
            case .tonight: "TONIGHT"
            }
        }

        func until(from now: Date = Date(), calendar: Calendar = .current) -> Date {
            switch self {
            case .twoHours: Stay.clamp(now.addingTimeInterval(2 * 3_600), now: now, calendar: calendar)
            case .fourHours: Stay.clamp(now.addingTimeInterval(4 * 3_600), now: now, calendar: calendar)
            case .tonight: Stay.clamp(calendar.date(bySettingHour: 23, minute: 59, second: 0, of: now) ?? now, now: now, calendar: calendar)
            }
        }
    }

    /// Where the picker opens: two hours from now.
    static func defaultEnd(from now: Date = Date(), calendar: Calendar = .current) -> Date {
        Preset.twoHours.until(from: now, calendar: calendar)
    }

    /// From the next whole minute to 30 days ahead.
    static func range(from now: Date = Date(), calendar: Calendar = .current) -> ClosedRange<Date> {
        minute(now.addingTimeInterval(60), calendar: calendar)...now.addingTimeInterval(TimeInterval(maxDays) * 86_400)
    }

    /// `end` without seconds, kept inside `range`.
    static func clamp(_ end: Date, now: Date = Date(), calendar: Calendar = .current) -> Date {
        let allowed = Stay.range(from: now, calendar: calendar)
        return min(max(minute(end, calendar: calendar), allowed.lowerBound), allowed.upperBound)
    }

    static func minute(_ date: Date, calendar: Calendar = .current) -> Date {
        calendar.dateInterval(of: .minute, for: date)?.start ?? date
    }

    /// "13:45", in 24-hour time.
    static func clock(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", parts.hour ?? 0, parts.minute ?? 0)
    }

    /// "TODAY", "TOMORROW" or "SAT 21".
    static func day(_ date: Date, now: Date = Date(), calendar: Calendar = .current) -> String {
        if calendar.isDate(date, inSameDayAs: now) { return "TODAY" }
        if let tomorrow = calendar.date(byAdding: .day, value: 1, to: now), calendar.isDate(date, inSameDayAs: tomorrow) { return "TOMORROW" }
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "EEE d"
        return formatter.string(from: date).uppercased()
    }

    /// "3 H 33 MIN", "45 MIN", "2 D 4 H".
    static func duration(from now: Date, to end: Date) -> String {
        let minutes = max(0, Int((end.timeIntervalSince(now) / 60).rounded(.up)))
        let days = minutes / 1_440, hours = minutes % 1_440 / 60, rest = minutes % 60
        if days > 0 { return hours > 0 ? "\(days) D \(hours) H" : "\(days) D" }
        if hours > 0 { return rest > 0 ? "\(hours) H \(rest) MIN" : "\(hours) H" }
        return "\(rest) MIN"
    }

    /// "UNTIL 13:45", "UNTIL TOMORROW 09:00", "UNTIL SAT 21 18:00" or "ENDED"; nil when there is no end.
    static func timeLeft(until end: Date?, now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard let end else { return nil }
        if end <= now { return "ENDED" }
        let dayLabel = Stay.day(end, now: now, calendar: calendar)
        let time = clock(end, calendar: calendar)
        return dayLabel == "TODAY" ? "UNTIL \(time)" : "UNTIL \(dayLabel) \(time)"
    }

    /// "SHARING UNTIL 13:45 · 3 H 33 MIN LEFT" for the crew page.
    static func sharing(until end: Date?, now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard let end, let left = timeLeft(until: end, now: now, calendar: calendar) else { return nil }
        return end <= now ? left : "SHARING \(left) · \(duration(from: now, to: end)) LEFT"
    }
}

nonisolated enum FriendsError: LocalizedError, Equatable {
    case ownInvite, invalidCode, offline, noName, full

    var errorDescription: String? {
        switch self {
        case .ownInvite: "That's your own code. Let a friend scan it instead."
        case .invalidCode: "That code doesn't look right. Codes are 8 letters and numbers."
        case .offline: "We are offline. Check your signal and try again."
        case .noName: "Add your name first to ride together."
        case .full: "That crew is full, or you are in 10 crews already."
        }
    }
}

protocol FriendsService {
    /// My crews, each with its active members, me included (`GET /v1/crews`).
    func crews() async throws -> [Crew]
    /// Creates a crew I am in until I leave (`POST /v1/crews`).
    func createCrew(name: String) async throws -> Crew
    /// Renames a crew I own (`PUT /v1/crews/{id}`).
    func renameCrew(id: String, name: String) async throws -> Crew
    /// What a code opens, before joining (`GET /v1/crews/preview/{code}`).
    func previewCrew(code: String) async throws -> CrewPreview
    /// Joins until `until`, or until I leave when nil (`POST /v1/crews/join`).
    func joinCrew(code: String, until: Date?) async throws -> Crew
    /// Changes how long I stay (`PUT /v1/crews/{id}/membership`).
    func setStay(crewID: String, until: Date?) async throws -> Crew
    /// Leaves one crew (`DELETE /v1/crews/{id}/membership`).
    func leaveCrew(id: String) async throws
    /// Drops the crew's meet point; the others get a push (`PUT /v1/crews/{id}/meet`).
    func setMeet(crewID: String, latitude: Double, longitude: Double, kind: MeetPoint.Kind, inMinutes: Int) async throws -> Crew
    /// Clears it (`DELETE /v1/crews/{id}/meet`).
    func clearMeet(crewID: String) async throws -> Crew
    /// Tells the server what I ride; my friends see the new tag at once (`PUT /v1/riders/me`). Returns the id the
    /// server knows me by.
    @discardableResult
    func updateSport(_ sport: String) async throws -> String
    /// Heartbeat while a ride is being recorded, with the rider's position so friends can see them on the map.
    /// The server expires it after 90 seconds and keeps only the latest position (`PUT /v1/live`).
    func setRiding(_ riding: Bool, at location: CLLocation?) async throws
    func updateName(_ name: String) async throws
    /// Where the server should send "joined / left your crew" (`PUT /v1/push`).
    func registerPush(token: String, sandbox: Bool) async throws
    /// "Wait for me" to one friend (`POST /v1/friends/{id}/wait`); the server stores it and pushes it. Returns its id.
    func sendWait(to friendID: String, distanceMeters: Double?) async throws -> String?
    /// Requests from friends still waiting for my answer (`GET /v1/waits/incoming`).
    func incomingWaits() async throws -> [IncomingWait]
    /// "OK, waiting" (`POST /v1/waits/{id}/accept`); the sender is notified.
    func acceptWait(id: String) async throws
    /// How a request I sent went (`GET /v1/waits/{id}`): pending, accepted or expired.
    func waitStatus(id: String) async throws -> String
    /// Where the server pushes updates to my running Live Activity; nil when it ended (`/v1/live-activity`).
    func setActivityToken(_ token: String?, sandbox: Bool) async throws
    /// People I rode with in the last 30 days who are in none of my crews now (`GET /v1/riders/recent`).
    func recentRiders() async throws -> [Friend]
    /// Deletes everything the server keeps about me: name, crew memberships, waits and devices (`DELETE /v1/riders/me`).
    func deleteMe() async throws
    /// Asks a recent rider to join a crew; they get a push with its code (`POST /v1/riders/{id}/invite`).
    func invite(riderID: String, crewID: String) async throws
}

/// A friend asking me to wait.
nonisolated struct IncomingWait: Equatable, Identifiable {
    let id: String
    let fromID: String
    let fromName: String
    let initials: String
    let distanceM: Double?
    let receivedAt: Date
}
