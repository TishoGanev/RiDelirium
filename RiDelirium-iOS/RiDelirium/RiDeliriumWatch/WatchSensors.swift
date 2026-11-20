//
//  WatchSensors.swift
//  RiDeliriumWatch
//

import Foundation
import CoreMotion
import CoreLocation

/// Peak g-force of the ride, from the watch's accelerometer. Gravity is included, so standing still reads 1.0.
final class GForceMonitor: ObservableObject {
    @Published private(set) var maxG: Double = 0

    private let manager = CMMotionManager()
    private var recent: [Double] = []

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        maxG = 0
        recent = []
        manager.deviceMotionUpdateInterval = 1.0 / 20
        manager.startDeviceMotionUpdates(to: .main) { [weak self] motion, _ in
            guard let self, let motion else { return }
            let a = motion.userAcceleration, g = motion.gravity
            let total = sqrt(pow(a.x + g.x, 2) + pow(a.y + g.y, 2) + pow(a.z + g.z, 2))
            // A quarter second average, so one jolt of the wrist is not a record.
            self.recent.append(total)
            if self.recent.count > 5 { self.recent.removeFirst() }
            if self.recent.count == 5 {
                let average = self.recent.reduce(0, +) / 5
                if average > self.maxG { self.maxG = average }
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
    }
}

/// The watch's heading, so a friend's arrow points the way to walk rather than north.
final class WatchCompass: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var heading: Double?
    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
    }

    func start() {
        manager.requestWhenInUseAuthorization()
        guard CLLocationManager.headingAvailable() else { return }
        manager.startUpdatingHeading()
    }

    func stop() {
        manager.stopUpdatingHeading()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        let value = newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading
        DispatchQueue.main.async { self.heading = value }
    }
}

enum Bearing {
    /// Degrees clockwise from north, from one point to another.
    static func degrees(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) -> Double {
        let lat1 = fromLat * .pi / 180, lat2 = toLat * .pi / 180
        let dLon = (toLon - fromLon) * .pi / 180
        let y = sin(dLon) * cos(lat2)
        let x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    static func meters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double) -> Double {
        CLLocation(latitude: fromLat, longitude: fromLon).distance(from: CLLocation(latitude: toLat, longitude: toLon))
    }
}
