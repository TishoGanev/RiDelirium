//
//  PhoneWatchBridge.swift
//  RiDelirium
//

import Foundation
import Combine
import CoreLocation
import WatchConnectivity

/// Mirrors the tracker to the paired Apple Watch and applies start/pause/stop commands from it.
@MainActor
final class PhoneWatchBridge: NSObject, ObservableObject, WCSessionDelegate {
    private weak var tracker: LocationTracker?
    private weak var friends: FriendsStore?
    /// Latest heart rate from the watch, dropped when it goes quiet for 20 seconds.
    @Published private(set) var heartRate: Double?
    private var heartRateAt = Date.distantPast
    private var lastExtrasAt = Date.distantPast
    private var cancellables = Set<AnyCancellable>()
    private var lastSent = WatchState()
    private var lastSentAt = Date.distantPast

    private var session: WCSession? { WCSession.isSupported() ? WCSession.default : nil }

    func start(tracker: LocationTracker, friends: FriendsStore) {
        guard self.tracker == nil, let session else { return }
        self.tracker = tracker
        self.friends = friends
        session.delegate = self
        session.activate()

        // Elapsed time ticks every second, so this also acts as the update heartbeat.
        tracker.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] in self?.push() }
            .store(in: &cancellables)
    }

    private func currentState() -> WatchState {
        var state = WatchState()
        guard let tracker else { return state }
        state.isTracking = tracker.isTracking
        state.isPaused = tracker.isPaused
        state.elapsed = tracker.elapsed
        state.distance = tracker.traveledDistance
        state.speed = tracker.isPaused ? 0 : tracker.currentSpeed
        state.maxSpeed = tracker.maxSpeed
        state.altitude = tracker.currentLocation?.altitude
        state.metric = UserDefaults.standard.object(forKey: "unitsMetric") as? Bool ?? true
        let defaults = UserDefaults.standard
        // Not editable in the app yet; set the keys to change them.
        state.goalDistance = defaults.object(forKey: "goalDistance") as? Double ?? 20_000
        state.maxHeartRate = defaults.object(forKey: "maxHeartRate") as? Double ?? 190
        state.descent = tracker.descent
        if let location = tracker.currentLocation {
            state.latitude = location.coordinate.latitude
            state.longitude = location.coordinate.longitude
            state.hasFix = location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 20
        }
        state.lastRideDistance = defaults.object(forKey: LocationTracker.lastRideDistanceKey) as? Double
        return state
    }

    /// The route so far (thinned to about 150 points) and the crew.
    private func currentExtras() -> WatchExtras {
        var extras = WatchExtras()
        if let tracker {
            let coordinates = tracker.trackCoordinates
            let stride = max(1, coordinates.count / 150)
            extras.route = coordinates.enumerated()
                .filter { $0.offset % stride == 0 || $0.offset == coordinates.count - 1 }
                .map { WatchCoordinate(lat: $0.element.latitude, lon: $0.element.longitude) }
        }
        extras.friends = (friends?.friends ?? []).map {
            WatchFriend(id: $0.id, name: $0.displayName, initials: $0.initials, colorHex: $0.avatarColor,
                        latitude: $0.latitude, longitude: $0.longitude, speedKmh: $0.speedKmh, updatedAt: $0.updatedAt)
        }
        return extras
    }

    private func push() {
        guard let session, session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return }
        let state = currentState()
        let modeChanged = state.isTracking != lastSent.isTracking || state.isPaused != lastSent.isPaused
        guard modeChanged || Date().timeIntervalSince(lastSentAt) >= 1 else { return }
        lastSent = state
        lastSentAt = Date()

        // Route and crew change slowly, so they ride along every few seconds rather than every second.
        let sendExtras = modeChanged || Date().timeIntervalSince(lastExtrasAt) >= 5
        let extras = sendExtras ? currentExtras() : nil
        if sendExtras { lastExtrasAt = Date() }

        if session.isReachable {
            session.sendMessage(state.dictionary, replyHandler: nil, errorHandler: nil)
            if let extras { session.sendMessage(extras.dictionary, replyHandler: nil, errorHandler: nil) }
        }
        // The latest context also reaches a watch app that launches later.
        if modeChanged || !state.isTracking {
            var context = state.dictionary
            context.merge((extras ?? currentExtras()).dictionary) { current, _ in current }
            try? session.updateApplicationContext(context)
        }
    }

    private func apply(_ command: WatchCommand) {
        guard let tracker else { return }
        switch command {
        case .start: tracker.startTracking()
        case .pause: tracker.pauseTracking()
        case .resume: tracker.resumeTracking()
        case .stop: tracker.stopTracking()
        }
        lastSentAt = .distantPast
        push()
    }

    // MARK: - WCSessionDelegate

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in self.lastSentAt = .distantPast; self.push() }
    }

    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.lastSentAt = .distantPast; self.push() }
    }

    /// The heart rate the watch last sent, or nil if it has gone stale.
    var currentHeartRate: Double? {
        Date().timeIntervalSince(heartRateAt) < 20 ? heartRate : nil
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        if let bpm = WatchHeartRate.value(in: message) {
            Task { @MainActor in
                self.heartRate = bpm
                self.heartRateAt = Date()
            }
            return
        }
        if let accept = WatchAcceptWait(dictionary: message) {
            Task { await WaitCoordinator.accept?(accept.waitID) }
            return
        }
        guard let command = WatchCommand(dictionary: message) else { return }
        Task { @MainActor in self.apply(command) }
    }

    /// Queued while the phone was out of reach: "OK, waiting" from the watch.
    nonisolated func session(_ session: WCSession, didReceiveUserInfo userInfo: [String: Any] = [:]) {
        if let accept = WatchAcceptWait(dictionary: userInfo) {
            Task { await WaitCoordinator.accept?(accept.waitID) }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        if let command = WatchCommand(dictionary: message) {
            Task { @MainActor in self.apply(command) }
        }
        if let wait = WatchWait(dictionary: message) {
            nonisolated(unsafe) let reply = replyHandler
            Task { @MainActor in
                let sent = await self.friends?.sendWait(to: wait.friendID, distanceMeters: wait.distanceMeters) ?? false
                reply(["ok": sent])
            }
            return
        }
        replyHandler([:])
    }
}
