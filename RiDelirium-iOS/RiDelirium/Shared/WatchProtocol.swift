//
//  WatchProtocol.swift
//  Shared by the iPhone app and the Watch app.
//

import Foundation
import UserNotifications

/// Tracking snapshot the phone pushes to the watch. Raw SI values; the watch formats them.
nonisolated struct WatchState: Codable, Equatable {
    var isTracking = false
    var isPaused = false
    var elapsed: TimeInterval = 0
    var distance: Double = 0       // metres
    var speed: Double = 0          // m/s
    var maxSpeed: Double = 0       // m/s
    var altitude: Double?          // metres
    var metric = true
    var sentAt = Date()
    /// Daily distance goal for the outer ring (metres); editable on the phone later, 20 km by default.
    var goalDistance: Double = 20_000
    /// Upper heart rate the pulse ring fills towards.
    var maxHeartRate: Double = 190
    /// Metres descended while riding this session (a live estimate from GPS altitude).
    var descent: Double = 0
    /// Where the rider is, for the watch map.
    var latitude: Double?
    var longitude: Double?
    /// Whether GPS has a usable fix; the idle ring fills when it does.
    var hasFix = false
    /// Distance of the last finished ride, shown on the watch face while idle.
    var lastRideDistance: Double?

    static let messageKey = "state"

    var dictionary: [String: Any] {
        guard let data = try? JSONEncoder().encode(self) else { return [:] }
        return [Self.messageKey: data]
    }

    init() {}

    init?(dictionary: [String: Any]) {
        guard let data = dictionary[Self.messageKey] as? Data,
              let state = try? JSONDecoder().decode(Self.self, from: data) else { return nil }
        self = state
    }
}

nonisolated struct WatchCoordinate: Codable, Equatable {
    var lat: Double
    var lon: Double
}

/// A friend as the watch shows them. Colour is the phone's choice so a rider looks the same everywhere.
nonisolated struct WatchFriend: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    var initials: String
    var colorHex: UInt32
    var latitude: Double?
    var longitude: Double?
    var speedKmh: Double?
    var updatedAt: Date?
}

/// What changes slowly: the route so far and the crew. Sent every few seconds and when it changes.
nonisolated struct WatchExtras: Codable, Equatable {
    var route: [WatchCoordinate] = []
    var friends: [WatchFriend] = []

    static let messageKey = "extras"

    var dictionary: [String: Any] {
        guard let data = try? JSONEncoder().encode(self) else { return [:] }
        return [Self.messageKey: data]
    }

    init() {}

    init?(dictionary: [String: Any]) {
        guard let data = dictionary[Self.messageKey] as? Data else { return nil }
        let decoder = JSONDecoder()
        guard let extras = try? decoder.decode(Self.self, from: data) else { return nil }
        self = extras
    }
}

/// "Wait for me", sent from the watch. One fixed message; the phone passes it on through the server.
nonisolated struct WatchWait {
    var friendID: String
    var distanceMeters: Double?

    static let messageKey = "wait"

    var dictionary: [String: Any] {
        var inner: [String: Any] = ["id": friendID]
        if let distanceMeters { inner["distance"] = distanceMeters }
        return [Self.messageKey: inner]
    }

    init(friendID: String, distanceMeters: Double?) {
        self.friendID = friendID
        self.distanceMeters = distanceMeters
    }

    init?(dictionary: [String: Any]) {
        guard let inner = dictionary[Self.messageKey] as? [String: Any], let id = inner["id"] as? String else { return nil }
        friendID = id
        distanceMeters = inner["distance"] as? Double
    }
}

/// "OK, waiting", tapped on a wait notification on the watch. The phone tells the server.
nonisolated struct WatchAcceptWait {
    var waitID: String

    static let messageKey = "acceptWait"

    var dictionary: [String: Any] { [Self.messageKey: waitID] }

    init(waitID: String) { self.waitID = waitID }

    init?(dictionary: [String: Any]) {
        guard let id = dictionary[Self.messageKey] as? String else { return nil }
        waitID = id
    }
}

/// The "wait for me" notification's category, shared by the phone and the watch so both show "OK, waiting".
nonisolated enum WaitNotification {
    static let category = "WAIT"
    static let acceptAction = "OK_WAITING"

    static var categories: Set<UNNotificationCategory> {
        let accept = UNNotificationAction(identifier: acceptAction, title: "OK, waiting", options: [])
        return [UNNotificationCategory(identifier: category, actions: [accept], intentIdentifiers: [], options: [])]
    }
}

/// The watch's heart rate, so the phone's Live Activity can show it.
nonisolated struct WatchHeartRate {
    static let messageKey = "heartRate"

    static func dictionary(_ bpm: Double) -> [String: Any] { [messageKey: bpm] }

    static func value(in dictionary: [String: Any]) -> Double? { dictionary[messageKey] as? Double }
}

/// Control the watch sends to the phone.
nonisolated enum WatchCommand: String {
    case start, pause, resume, stop

    static let messageKey = "command"

    var dictionary: [String: Any] { [Self.messageKey: rawValue] }

    init?(dictionary: [String: Any]) {
        guard let raw = dictionary[Self.messageKey] as? String, let command = Self(rawValue: raw) else { return nil }
        self = command
    }
}
