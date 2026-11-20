//
//  RemoteFriendsService.swift
//  RiDelirium
//
//  Talks to the Ride Together API (Server/api). The server keeps only a random id, a display name and
//  which crews they are in; it never sees ride data or location history. Without a configured host Ride Together is offline.
//

import CoreLocation
import Foundation

nonisolated struct RemoteFriendsService: FriendsService {
    private static let tokenAccount = "apiToken"
    private static let nameKey = "riderName"

    let baseURL: URL
    let identity: String

    /// Nil until `APIHost` is set in the app's Info.plist (build setting API_HOST).
    static func configured(identity: String) -> RemoteFriendsService? {
        guard let host = Bundle.main.object(forInfoDictionaryKey: "APIHost") as? String,
              !host.isEmpty, !host.hasPrefix("$(") else { return nil }
        // A bare IP address is a server on the local network; real hosts must be HTTPS.
        let scheme = host.first?.isNumber == true ? "http" : "https"
        guard let url = URL(string: "\(scheme)://\(host)/v1") else { return nil }
        return RemoteFriendsService(baseURL: url, identity: identity)
    }

    private struct Wire: Decodable {
        let id: String
        let displayName: String
        let initials: String
        let isRiding: Bool
        let lat: Double?
        let lon: Double?
        let speedKmh: Double?
        let ageSeconds: Int?
        let sport: String?

        var friend: Friend {
            Friend(id: id, displayName: displayName, initials: initials, isRiding: isRiding,
                   latitude: lat, longitude: lon, speedKmh: speedKmh,
                   updatedAt: ageSeconds.map { Date().addingTimeInterval(-TimeInterval($0)) },
                   sport: sport.flatMap(Sport.init(rawValue:)))
        }
    }

    // MARK: - FriendsService

    private struct CrewWire: Decodable {
        let id: String
        let name: String
        let code: String
        let ownerId: String
        let myExpiresAt: Double?
        let members: [Wire]
        let meet: MeetWire?

        var crew: Crew {
            Crew(id: id, name: name, code: code, ownerID: ownerId,
                 myExpiresAt: myExpiresAt.map { Date(timeIntervalSince1970: $0) }, members: members.map(\.friend),
                 meet: meet?.point)
        }
    }

    private struct MeetWire: Decodable {
        let lat: Double
        let lon: Double
        let kind: String
        let at: Double
        let byId: String?
        let byName: String?

        var point: MeetPoint {
            MeetPoint(latitude: lat, longitude: lon, kind: MeetPoint.Kind(rawValue: kind) ?? .here,
                      at: Date(timeIntervalSince1970: at), byID: byId, byName: byName)
        }
    }

    func crews() async throws -> [Crew] {
        let (data, _) = try await authorized("GET", "crews")
        return try JSONDecoder().decode([CrewWire].self, from: data).map(\.crew)
    }

    func createCrew(name: String) async throws -> Crew {
        do {
            let (data, _) = try await authorized("POST", "crews", body: ["name": name])
            return try JSONDecoder().decode(CrewWire.self, from: data).crew
        } catch RemoteError.status(409) {
            throw FriendsError.full
        }
    }

    func renameCrew(id: String, name: String) async throws -> Crew {
        let (data, _) = try await authorized("PUT", "crews/\(id)", body: ["name": name])
        return try JSONDecoder().decode(CrewWire.self, from: data).crew
    }

    func previewCrew(code: String) async throws -> CrewPreview {
        struct Wire: Decodable { let name: String; let ownerName: String; let memberCount: Int; let isMember: Bool? }
        do {
            let (data, _) = try await authorized("GET", "crews/preview/\(code)")
            let wire = try JSONDecoder().decode(Wire.self, from: data)
            return CrewPreview(code: code, name: wire.name, ownerName: wire.ownerName, memberCount: wire.memberCount, isMember: wire.isMember ?? false)
        } catch RemoteError.status(404) {
            throw FriendsError.invalidCode
        }
    }

    func joinCrew(code: String, until: Date?) async throws -> Crew {
        var body: [String: Any] = ["code": code]
        if let until { body["until"] = Int(until.timeIntervalSince1970) }
        do {
            let (data, _) = try await authorized("POST", "crews/join", body: body)
            return try JSONDecoder().decode(CrewWire.self, from: data).crew
        } catch RemoteError.status(404) {
            throw FriendsError.invalidCode
        } catch RemoteError.status(409) {
            throw FriendsError.full
        }
    }

    func setStay(crewID: String, until: Date?) async throws -> Crew {
        var body: [String: Any] = [:]
        if let until { body["until"] = Int(until.timeIntervalSince1970) }
        let (data, _) = try await authorized("PUT", "crews/\(crewID)/membership", body: body)
        return try JSONDecoder().decode(CrewWire.self, from: data).crew
    }

    func leaveCrew(id: String) async throws {
        _ = try await authorized("DELETE", "crews/\(id)/membership")
    }

    func setMeet(crewID: String, latitude: Double, longitude: Double, kind: MeetPoint.Kind, inMinutes: Int) async throws -> Crew {
        let body: [String: Any] = ["lat": latitude, "lon": longitude, "kind": kind.rawValue, "inMinutes": inMinutes]
        let (data, _) = try await authorized("PUT", "crews/\(crewID)/meet", body: body)
        return try JSONDecoder().decode(CrewWire.self, from: data).crew
    }

    func clearMeet(crewID: String) async throws -> Crew {
        let (data, _) = try await authorized("DELETE", "crews/\(crewID)/meet")
        return try JSONDecoder().decode(CrewWire.self, from: data).crew
    }

    func setRiding(_ riding: Bool, at location: CLLocation?) async throws {
        var body: [String: Any] = ["riding": riding]
        if riding, let location {
            body["lat"] = location.coordinate.latitude
            body["lon"] = location.coordinate.longitude
            // CLLocation.speed is -1 when unknown.
            body["speedKmh"] = max(0, location.speed) * 3.6
        }
        _ = try await authorized("PUT", "live", body: body)
    }

    @discardableResult
    func updateSport(_ sport: String) async throws -> String {
        let (data, _) = try await authorized("PUT", "riders/me", body: ["sport": sport])
        return try JSONDecoder().decode(Wire.self, from: data).id
    }

    func updateName(_ name: String) async throws {
        _ = try await authorized("PUT", "riders/me", body: ["name": name])
    }

    func registerPush(token: String, sandbox: Bool) async throws {
        _ = try await authorized("PUT", "push", body: ["platform": "ios", "token": token, "sandbox": sandbox])
    }

    func sendWait(to friendID: String, distanceMeters: Double?) async throws -> String? {
        var body: [String: Any] = [:]
        if let distanceMeters { body["distanceMeters"] = distanceMeters }
        let (data, _) = try await authorized("POST", "friends/\(friendID.uppercased())/wait", body: body)
        struct Created: Decodable { let id: String }
        return try? JSONDecoder().decode(Created.self, from: data).id
    }

    func incomingWaits() async throws -> [IncomingWait] {
        struct Wire: Decodable {
            let id: String
            let fromId: String
            let fromName: String
            let initials: String
            let distanceM: Double?
            let ageSeconds: Int
        }
        let (data, _) = try await authorized("GET", "waits/incoming")
        return try JSONDecoder().decode([Wire].self, from: data).map {
            IncomingWait(id: $0.id, fromID: $0.fromId, fromName: $0.fromName, initials: $0.initials, distanceM: $0.distanceM,
                         receivedAt: Date().addingTimeInterval(-TimeInterval($0.ageSeconds)))
        }
    }

    func acceptWait(id: String) async throws {
        _ = try await authorized("POST", "waits/\(id)/accept")
    }

    func waitStatus(id: String) async throws -> String {
        struct Status: Decodable { let status: String }
        let (data, _) = try await authorized("GET", "waits/\(id)")
        return try JSONDecoder().decode(Status.self, from: data).status
    }

    func setActivityToken(_ token: String?, sandbox: Bool) async throws {
        if let token {
            _ = try await authorized("PUT", "live-activity", body: ["token": token, "sandbox": sandbox])
        } else {
            _ = try await authorized("DELETE", "live-activity")
        }
    }

    func recentRiders() async throws -> [Friend] {
        let (data, _) = try await authorized("GET", "riders/recent")
        struct Wire: Decodable { let id: String; let displayName: String; let initials: String; let sport: String? }
        return try JSONDecoder().decode([Wire].self, from: data).map {
            Friend(id: $0.id, displayName: $0.displayName, initials: $0.initials, isRiding: false, sport: $0.sport.flatMap(Sport.init(rawValue:)))
        }
    }

    func invite(riderID: String, crewID: String) async throws {
        _ = try await authorized("POST", "riders/\(riderID.uppercased())/invite", body: ["crewId": crewID])
    }

    /// Never registers first: without a token the server has nothing it would let this install delete.
    func deleteMe() async throws {
        guard let token = Keychain.read(Self.tokenAccount) else { return }
        do {
            _ = try await send("DELETE", "riders/me", body: nil, token: token)
        } catch RemoteError.status(401) {
            // The server had already forgotten this token (it purges inactive riders).
        }
        Keychain.delete(Self.tokenAccount)
    }

    // MARK: - HTTP

    private enum RemoteError: Error {
        case status(Int)
        case notRegistered
    }

    /// Sends an authenticated request, registering this install first if it has no token, and once more
    /// if the server no longer knows the token (for example after it purged an inactive rider).
    private func authorized(_ method: String, _ path: String, body: [String: Any]? = nil) async throws -> (Data, Int) {
        for attempt in 0..<2 {
            let token = try await currentToken()
            do {
                return try await send(method, path, body: body, token: token)
            } catch RemoteError.status(401) where attempt == 0 {
                Keychain.delete(Self.tokenAccount)
            }
        }
        throw RemoteError.status(401)
    }

    private func currentToken() async throws -> String {
        if let token = Keychain.read(Self.tokenAccount) { return token }
        // Several calls can start at once (naming yourself and joining a code); register only once.
        return try await Registrar.shared.token { try await register() }
    }

    private func register() async throws -> String {
        guard let name = UserDefaults.standard.string(forKey: Self.nameKey) else { throw RemoteError.notRegistered }
        let (data, _) = try await send("POST", "riders", body: ["id": identity, "name": name], token: nil)
        struct Registered: Decodable { let token: String }
        let token = try JSONDecoder().decode(Registered.self, from: data).token
        Keychain.write(token, account: Self.tokenAccount)
        return token
    }

    private actor Registrar {
        static let shared = Registrar()
        private var inFlight: Task<String, Error>?

        /// One registration at a time: a caller arriving mid-registration waits for it instead of starting a second.
        func token(register: @escaping @Sendable () async throws -> String) async throws -> String {
            if let token = Keychain.read(RemoteFriendsService.tokenAccount) { return token }
            if let inFlight { return try await inFlight.value }
            let task = Task { try await register() }
            inFlight = task
            defer { inFlight = nil }
            return try await task.value
        }
    }

    private func send(_ method: String, _ path: String, body: [String: Any]?, token: String?) async throws -> (Data, Int) {
        var request = URLRequest(url: baseURL.appending(path: path), timeoutInterval: 10)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else { throw RemoteError.status(status) }
        return (data, status)
    }
}
