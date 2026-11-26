//
//  MotionActivity.swift
//  RiDelirium
//

import Foundation
import CoreMotion

enum MotionKind: String {
    case automotive, walking, cycling, stationary, unknown
}

struct MotionSample: Equatable {
    var kind: MotionKind
    /// CMMotionActivityConfidence raw value: 0 low, 1 medium, 2 high.
    var confidence: Int

    init(kind: MotionKind, confidence: Int) {
        self.kind = kind
        self.confidence = confidence
    }

    init(_ activity: CMMotionActivity) {
        let kind: MotionKind
        if activity.automotive { kind = .automotive }
        else if activity.cycling { kind = .cycling }
        else if activity.walking || activity.running { kind = .walking }
        else if activity.stationary { kind = .stationary }
        else { kind = .unknown }
        self.init(kind: kind, confidence: activity.confidence.rawValue)
    }
}

/// Streams CoreMotion activity. Does nothing where motion data is unavailable or not permitted.
@MainActor
final class MotionActivityMonitor {
    private let manager = CMMotionActivityManager()
    private(set) var latest: MotionSample?

    private var isRunning = false

    func start() {
        guard !isRunning, CMMotionActivityManager.isActivityAvailable() else { return }
        isRunning = true
        manager.startActivityUpdates(to: .main) { [weak self] activity in
            guard let activity else { return }
            MainActor.assumeIsolated {
                guard let self else { return }
                let sample = MotionSample(activity)
                self.latest = sample
            }
        }
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        latest = nil
        manager.stopActivityUpdates()
    }
}
