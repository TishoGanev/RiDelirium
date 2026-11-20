//
//  WatchLink.swift
//  RiDeliriumWatch
//

import Foundation
import WatchConnectivity

/// Receives live tracking state from the phone and sends it start/pause/stop commands.
final class WatchLink: NSObject, ObservableObject, WCSessionDelegate {
    @Published private(set) var state = WatchState()
    @Published private(set) var isReachable = false
    @Published private(set) var extras = WatchExtras()
    /// When "wait for me" was last sent to each friend; the row shows SENT for 30 seconds.
    @Published private(set) var waitSent: [String: Date] = [:]
    private var receivedAt = Date()

    override init() {
        super.init()
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
    }

    /// Elapsed time at `date`, running locally between phone updates while recording.
    func elapsed(at date: Date) -> TimeInterval {
        guard state.isTracking, !state.isPaused else { return state.elapsed }
        return state.elapsed + max(0, date.timeIntervalSince(receivedAt))
    }

    func send(_ command: WatchCommand) {
        guard WCSession.default.isReachable else { return }
        WCSession.default.sendMessage(command.dictionary, replyHandler: nil, errorHandler: nil)
    }

    /// Passes the heart rate to the phone for its Live Activity; fire and forget.
    func sendHeartRate(_ bpm: Double) {
        guard WCSession.default.isReachable else { return }
        WCSession.default.sendMessage(WatchHeartRate.dictionary(bpm), replyHandler: nil, errorHandler: nil)
    }

    /// One fixed message through the phone. False when the phone can't be reached, so nothing is shown as sent.
    func sendWait(to friend: WatchFriend, distanceMeters: Double?) {
        guard WCSession.default.isReachable else { return }
        waitSent[friend.id] = Date()
        WCSession.default.sendMessage(WatchWait(friendID: friend.id, distanceMeters: distanceMeters).dictionary, replyHandler: { [weak self] reply in
            guard (reply["ok"] as? Bool) == false else { return }
            DispatchQueue.main.async { self?.waitSent[friend.id] = nil }
        }, errorHandler: { [weak self] _ in
            DispatchQueue.main.async { self?.waitSent[friend.id] = nil }
        })
    }

    func isWaitActive(for friendID: String, at date: Date) -> Bool {
        guard let sent = waitSent[friendID] else { return false }
        return date.timeIntervalSince(sent) < 30
    }

    private func receive(_ dictionary: [String: Any]) {
        let newState = WatchState(dictionary: dictionary)
        let newExtras = WatchExtras(dictionary: dictionary)
        guard newState != nil || newExtras != nil else { return }
        DispatchQueue.main.async {
            if let newState {
                self.state = newState
                self.receivedAt = Date()
            }
            if let newExtras { self.extras = newExtras }
        }
    }

    // MARK: - WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        DispatchQueue.main.async { self.isReachable = session.isReachable }
        receive(session.receivedApplicationContext)
    }

    func sessionReachabilityDidChange(_ session: WCSession) {
        DispatchQueue.main.async { self.isReachable = session.isReachable }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        receive(message)
    }

    func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        receive(applicationContext)
    }
}
