//
//  GForceMonitor.swift
//  RiDelirium
//

import Foundation
import CoreMotion

/// Total G-force from the accelerometer, gravity included, so a phone at rest reads about 1.0 g.
/// Only listens while recording. `isAvailable` is false on devices without an accelerometer.
/// Keep in sync with `GForceMonitor.kt` on Android.
final class GForceMonitor: @unchecked Sendable {
    private let manager = CMMotionManager()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "GForceMonitor"
        queue.maxConcurrentOperationCount = 1
        return queue
    }()
    private let lock = NSLock()
    private var _latest: Double?
    private var _peak = 0.0
    private var running = false

    var isAvailable: Bool { manager.isAccelerometerAvailable }

    /// The latest reading in g, or nil when not listening.
    var latest: Double? { lock.withLock { _latest } }

    func start() {
        guard !running, manager.isAccelerometerAvailable else { return }
        running = true
        lock.withLock { _peak = 0 }
        manager.accelerometerUpdateInterval = 1.0 / 25
        manager.startAccelerometerUpdates(to: queue) { [weak self] data, _ in
            guard let self, let a = data?.acceleration else { return }
            let g = (a.x * a.x + a.y * a.y + a.z * a.z).squareRoot()
            self.lock.withLock {
                self._latest = g
                if g > self._peak { self._peak = g }
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        manager.stopAccelerometerUpdates()
        lock.withLock { _latest = nil }
    }

    /// The highest reading since the last call, or nil if there was none. Starts a new window.
    func takePeak() -> Double? {
        lock.withLock {
            let value = _peak
            _peak = 0
            return value > 0 ? value : nil
        }
    }
}
