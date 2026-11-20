//
//  RideActivityAttributes.swift
//  RiDelirium
//
//  Shared by the app, which starts and updates the Live Activity, and RideWidget, which draws it.
//  The server also pushes updates ("wait for me"), so every field decodes with a default: a pushed state
//  carries only the request, and the app fills in the ride fields on its next update.
//

import ActivityKit
import Foundation

/// A friend on the lock screen. Raw values; the widget formats them.
nonisolated struct LiveFriend: Codable, Hashable {
    enum Status: String, Codable, Hashable { case riding, stopped, offline }

    var initials: String
    /// Index into the five rider colours, the same as on the phone and the watch.
    var colorIndex: Int
    var distanceM: Double?
    /// Degrees clockwise from the rider's heading at the last update (from north when the heading is unknown).
    var bearingDeg: Double?
    var status: Status
    /// Kilometres per hour.
    var speed: Double
    var name: String
}

/// "Wait for me" from a friend, waiting for an answer.
nonisolated struct LiveRequest: Codable, Hashable {
    var id: String
    var fromName: String
    var initials: String
    var colorIndex: Int
    var distanceM: Double?
    var bearingDeg: Double?
    /// Seconds since 1970; a plain number so the server can send it.
    var sentAt: Double
    /// Requests waiting for an answer; shown as a "+N" badge when above one.
    var count: Int
    /// The friend's id, so MAP can centre on them.
    var friendID: String?

    init(id: String, fromName: String, initials: String, colorIndex: Int, distanceM: Double? = nil, bearingDeg: Double? = nil,
         sentAt: Double, count: Int = 1, friendID: String? = nil) {
        self.id = id; self.fromName = fromName; self.initials = initials; self.colorIndex = colorIndex
        self.distanceM = distanceM; self.bearingDeg = bearingDeg; self.sentAt = sentAt; self.count = count; self.friendID = friendID
    }
}

/// After "OK, waiting": the rider is waiting for a friend to catch up.
nonisolated struct LiveWaiting: Codable, Hashable {
    var id: String
    var name: String
    var initials: String
    var colorIndex: Int
    var distanceM: Double
    var bearingDeg: Double?
    /// Kilometres per hour.
    var speed: Double
    var etaSec: Double?
    /// 0...1: how much of the distance at the moment of accepting is covered.
    var progress: Double
    /// True for a few seconds once the friend is within reach.
    var arrived: Bool
}

// The app target defaults to MainActor isolation; ActivityKit uses these types off the main actor.
nonisolated struct RideActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        enum Motion: String, Codable, Hashable {
            case run, lift, still
        }

        /// Metres per second.
        var speed: Double = 0
        var maxSpeed: Double = 0
        /// Metres.
        var distance: Double = 0
        var motion: Motion = .run
        /// The ride timer reads `now - timerStart`, so the system can tick it without updates from the app.
        var timerStart: Date = Date()
        /// Frozen elapsed time while paused; nil while the timer runs.
        var pausedElapsed: TimeInterval?
        /// When the rider last stood or lifted, so "STOPPED · 3 MIN" counts from there. Nil while riding.
        var motionSince: Date?
        /// From the watch; nil without one.
        var heartRate: Double?
        /// Daily distance goal in metres, for the bar under the distance.
        var goalDistance: Double = 20_000
        /// Up to three, nearest first.
        var friends: [LiveFriend] = []
        var request: LiveRequest?
        var waiting: LiveWaiting?
        /// The ride is over; the card shows the summary until it is dismissed.
        var finished = false

        init() {}

        init(speed: Double, maxSpeed: Double, distance: Double, motion: Motion, timerStart: Date, pausedElapsed: TimeInterval?) {
            self.speed = speed; self.maxSpeed = maxSpeed; self.distance = distance; self.motion = motion
            self.timerStart = timerStart; self.pausedElapsed = pausedElapsed
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            speed = try c.decodeIfPresent(Double.self, forKey: .speed) ?? 0
            maxSpeed = try c.decodeIfPresent(Double.self, forKey: .maxSpeed) ?? 0
            distance = try c.decodeIfPresent(Double.self, forKey: .distance) ?? 0
            motion = try c.decodeIfPresent(Motion.self, forKey: .motion) ?? .run
            timerStart = try c.decodeIfPresent(Date.self, forKey: .timerStart) ?? Date()
            pausedElapsed = try c.decodeIfPresent(TimeInterval.self, forKey: .pausedElapsed)
            motionSince = try c.decodeIfPresent(Date.self, forKey: .motionSince)
            heartRate = try c.decodeIfPresent(Double.self, forKey: .heartRate)
            goalDistance = try c.decodeIfPresent(Double.self, forKey: .goalDistance) ?? 20_000
            friends = try c.decodeIfPresent([LiveFriend].self, forKey: .friends) ?? []
            request = try c.decodeIfPresent(LiveRequest.self, forKey: .request)
            waiting = try c.decodeIfPresent(LiveWaiting.self, forKey: .waiting)
            finished = try c.decodeIfPresent(Bool.self, forKey: .finished) ?? false
        }
    }

    var metric: Bool
}
