//
//  RideActivityController.swift
//  RiDelirium
//

import ActivityKit
import Foundation
import os

/// Mirrors the tracked ride onto the Lock Screen and Dynamic Island, and the server's pushes (a friend's
/// "wait for me") land on the same activity.
@MainActor
final class RideActivityController {
    // `Activity` alone is the app's run/lift/still enum.
    private var activity: ActivityKit.Activity<RideActivityAttributes>?
    private var lastSent: RideActivityAttributes.ContentState?
    private var lastSentAt: Date = .distantPast
    private var tokenTask: Task<Void, Never>?

    private static let log = Logger(subsystem: "com.bgdeveloper.thesnowboarder", category: "liveactivity")

    /// The activity's push token as hex, or nil once it ended. The server uses it to update the lock screen
    /// while the app is asleep.
    var onToken: ((String?) -> Void)?

    /// Location fixes arrive about once a second; the card only needs fresh numbers every few seconds.
    private static let minInterval: TimeInterval = 4
    /// The card is marked stale if the app stops updating it for this long.
    private static let staleAfter: TimeInterval = 90
    /// A finished ride stays on the lock screen this long.
    private static let summaryDuration: TimeInterval = 15 * 60

    init() {
        // A ride the app was killed during leaves its activity behind; nothing will update it again.
        for stale in ActivityKit.Activity<RideActivityAttributes>.activities {
            Task { await stale.end(nil, dismissalPolicy: .immediate) }
        }
    }

    /// iOS only lets an app start a Live Activity while it is in the foreground. A ride started from the
    /// watch reaches a phone that is usually locked, so that first request fails; `update` tries again
    /// on every location fix until the app is opened, and the activity then appears with the ride's real time.
    func start(_ state: RideActivityAttributes.ContentState) {
        guard activity == nil else { return }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            Self.log.error("live activity not started: Live Activities are off (Settings > RiDelirium > Live Activities)")
            return
        }
        let metric = UserDefaults.standard.object(forKey: "unitsMetric") as? Bool ?? true
        do {
            let created = try ActivityKit.Activity.request(
                attributes: RideActivityAttributes(metric: metric),
                content: ActivityContent(state: state, staleDate: Date().addingTimeInterval(Self.staleAfter)),
                pushType: .token
            )
            activity = created
            lastSent = state
            lastSentAt = Date()
            Self.log.info("live activity started")
            observeToken(of: created)
        } catch {
            // Typically: the app was not in the foreground, or the push entitlement is missing.
            Self.log.error("live activity request failed: \(String(describing: error), privacy: .public)")
            activity = nil
        }
    }

    private func observeToken(of activity: ActivityKit.Activity<RideActivityAttributes>) {
        tokenTask?.cancel()
        tokenTask = Task { [weak self] in
            for await data in activity.pushTokenUpdates {
                let token = data.map { String(format: "%02x", $0) }.joined()
                Self.log.info("live activity push token received (\(token.prefix(8), privacy: .public)…)")
                self?.onToken?(token)
            }
        }
    }

    /// Pass `force` for changes the rider should see at once, like pausing, a new activity or a friend's request.
    func update(_ state: RideActivityAttributes.ContentState, force: Bool = false) {
        guard let activity else {
            start(state)
            return
        }
        guard state != lastSent else { return }
        let changedMode = state.motion != lastSent?.motion || state.pausedElapsed != lastSent?.pausedElapsed
            || (state.waiting != nil) != (lastSent?.waiting != nil) || state.request?.id != lastSent?.request?.id
        guard force || changedMode || Date().timeIntervalSince(lastSentAt) >= Self.minInterval else { return }
        let incoming = state.request != nil && lastSent?.request?.id != state.request?.id
        let arrivedNow = state.waiting?.arrived == true && lastSent?.waiting?.arrived != true
        lastSent = state
        lastSentAt = Date()
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(Self.staleAfter))
        Task {
            if incoming {
                // A new request wakes the screen and expands the Dynamic Island.
                await activity.update(content, alertConfiguration: AlertConfiguration(
                    title: "WAIT FOR ME", body: "\(state.request?.fromName ?? "A friend") asks you to wait", sound: .default
                ))
            } else if arrivedNow {
                await activity.update(content, alertConfiguration: AlertConfiguration(
                    title: "\(state.waiting?.name ?? "Your friend") is here", body: "", sound: .default
                ))
            } else {
                await activity.update(content)
            }
        }
    }

    func end(_ state: RideActivityAttributes.ContentState) {
        guard let activity else { return }
        self.activity = nil
        lastSent = nil
        tokenTask?.cancel()
        onToken?(nil)
        var final = state
        final.finished = true
        final.request = nil
        final.waiting = nil
        Task {
            await activity.end(ActivityContent(state: final, staleDate: nil), dismissalPolicy: .after(Date().addingTimeInterval(Self.summaryDuration)))
        }
    }
}
