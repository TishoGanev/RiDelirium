//
//  LocationTracker.swift
//  RiDelirium
//

import Foundation
import CoreLocation
import SwiftData
import Combine

@MainActor
final class LocationTracker: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var isTracking = false
    @Published private(set) var currentLocation: CLLocation?
    @Published private(set) var traveledDistance: CLLocationDistance = 0
    @Published private(set) var maxSpeed: CLLocationSpeed = 0
    /// Metres descended while riding this session. A live estimate from GPS altitude; the ride detail
    /// works it out again from the whole track.
    @Published private(set) var descent: Double = 0
    private var lastAltitude: Double?
    @Published private(set) var trackCoordinates: [CLLocationCoordinate2D] = []
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var hasFixedLocation = false
    @Published private(set) var isPaused = false
    /// What the rider is doing right now; standing still adds nothing to distance or the track.
    @Published private(set) var activity: Activity = .still
    private var classifier = LiveActivityClassifier()
    private let gSensor = GForceMonitor()
    /// Total G-force right now (about 1.0 at rest) and the highest this ride; nil without an accelerometer.
    @Published private(set) var currentG: Double?
    @Published private(set) var maxG: Double?

    private(set) var startTime: Date?
    private(set) var startAltitude: Double?
    @Published private(set) var lastSessionId: Int?
    private var session: Int = 0
    private var lastLocation: CLLocation?
    private let manager = CLLocationManager()
    private var modelContext: ModelContext?
    private var durationTimer: Timer?
    private var segmentStart: Date?
    private var accumulatedBeforePause: TimeInterval = 0
    private let liveActivity = RideActivityController()

    /// What the Live Activity shows besides the ride itself: the crew and any "wait for me". Set by ContentView,
    /// which owns the friends.
    struct LiveExtras {
        var friends: [LiveFriend] = []
        var request: LiveRequest?
        var waiting: LiveWaiting?
        var heartRate: Double?
    }
    var liveExtras: () -> LiveExtras = { LiveExtras() }
    /// The watch's current heart rate, saved with each recorded point. Set by ContentView.
    var heartRate: () -> Double? = { nil }
    /// Rider's heading while moving, for the arrows to friends.
    var heading: Double? {
        guard let location = currentLocation, location.course >= 0, location.speed > 1 else { return nil }
        return location.course
    }
    /// Forwards the activity's push token so the server can update the lock screen.
    var onActivityToken: ((String?) -> Void)? {
        didSet { liveActivity.onToken = onActivityToken }
    }
    private var motionSince: Date?

    /// A friend's request or a wait changed: show it at once.
    func refreshLiveActivity() {
        guard isTracking else { return }
        liveActivity.update(liveActivityState, force: true)
    }

    /// Fixes less accurate than this are not recorded. Stricter than the 50 m used as the default in
    /// `FixFilter`, which is only a fallback.
    private static let accuracyThreshold: CLLocationDistance = 20
    static let lastRideDistanceKey = "lastRideDistance"

    private var fixFilter = FixFilter(maxHorizontalAccuracy: LocationTracker.accuracyThreshold)
    private let motion = MotionActivityMonitor()

    override init() {
        super.init()
        manager.delegate = self
        // Update rate is left to the system on purpose: no distanceFilter, no auto-pausing and
        // `.other`, with Best accuracy while recording. Fixes can still come every 6-12 s when the
        // device is standing still; that is iOS saving power and is separate from timestamp precision.
        manager.activityType = .other
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        manager.pausesLocationUpdatesAutomatically = false
        authorizationStatus = manager.authorizationStatus
        startUpdatingIfAuthorized()
    }

    func configure(context: ModelContext) {
        self.modelContext = context
    }

    func requestAuthorization() {
        manager.requestAlwaysAuthorization()
    }

    private func startUpdatingIfAuthorized() {
        switch authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            manager.allowsBackgroundLocationUpdates = true
            manager.startUpdatingLocation()
        default:
            break
        }
    }

    func startTracking() {
        guard !isTracking else { return }
        session = nextSessionId()
        startTime = Date()
        startAltitude = currentLocation?.altitude
        lastLocation = nil
        fixFilter = FixFilter(maxHorizontalAccuracy: Self.accuracyThreshold, notBefore: startTime)
        traveledDistance = 0
        maxSpeed = 0
        descent = 0
        lastAltitude = nil
        trackCoordinates = []
        elapsed = 0
        accumulatedBeforePause = 0
        classifier.reset()
        activity = .still
        currentG = nil
        maxG = nil
        gSensor.start()
        isTracking = true
        isPaused = false
        manager.desiredAccuracy = kCLLocationAccuracyBest
        motion.start()

        startTimer()
        liveActivity.start(liveActivityState)
    }

    func pauseTracking() {
        guard isTracking, !isPaused else { return }
        isPaused = true
        accumulatedBeforePause = currentSegmentElapsed()
        segmentStart = nil
        elapsed = accumulatedBeforePause
        durationTimer?.invalidate()
        durationTimer = nil
        liveActivity.update(liveActivityState, force: true)
    }

    func resumeTracking() {
        guard isTracking, isPaused else { return }
        isPaused = false
        lastLocation = nil
        classifier.reset()
        activity = .still
        _ = gSensor.takePeak()
        startTimer()
        liveActivity.update(liveActivityState, force: true)
    }

    private func currentSegmentElapsed() -> TimeInterval {
        accumulatedBeforePause + (segmentStart.map { Date().timeIntervalSince($0) } ?? 0)
    }

    private func startTimer() {
        segmentStart = Date()
        durationTimer?.invalidate()
        durationTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.elapsed = self.currentSegmentElapsed()
                if let g = self.gSensor.latest { self.currentG = g }
            }
        }
    }

    func stopTracking() {
        guard isTracking else { return }
        isTracking = false
        isPaused = false
        gSensor.stop()
        // Before `segmentStart` is cleared, so the final banner keeps the ride's time.
        liveActivity.end(liveActivityState)
        lastSessionId = session
        UserDefaults.standard.set(traveledDistance, forKey: Self.lastRideDistanceKey)
        fixFilter.metadata.save(session: session)
        manager.desiredAccuracy = kCLLocationAccuracyThreeKilometers
        motion.stop()
        segmentStart = nil
        durationTimer?.invalidate()
        durationTimer = nil
    }

    func discardLastSession() {
        guard let context = modelContext, let sessionId = lastSessionId else { return }
        let descriptor = FetchDescriptor<TrackPoint>(predicate: #Predicate { $0.session == sessionId })
        if let points = try? context.fetch(descriptor) {
            for point in points { context.delete(point) }
            try? context.save()
        }
        lastSessionId = nil
    }

    private func nextSessionId() -> Int {
        guard let context = modelContext else { return 1 }
        let descriptor = FetchDescriptor<TrackPoint>(sortBy: [SortDescriptor(\.session, order: .reverse)])
        let maxSession = (try? context.fetch(descriptor))?.first?.session ?? 0
        return maxSession + 1
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            self.authorizationStatus = status
            self.startUpdatingIfAuthorized()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        // iOS can deliver several fixes at once; dropping all but the last left gaps in the timeline.
        Task { @MainActor in
            for location in locations { self.handle(location: location) }
        }
    }

    private var liveActivityState: RideActivityAttributes.ContentState {
        let motion: RideActivityAttributes.ContentState.Motion = switch activity {
        case .run: .run
        case .lift: .lift
        case .still: .still
        }
        let running = currentSegmentElapsed()
        var state = RideActivityAttributes.ContentState(
            speed: activity == .still ? 0 : currentSpeed,
            maxSpeed: max(0, maxSpeed),
            distance: traveledDistance,
            motion: motion,
            timerStart: Date().addingTimeInterval(-running),
            pausedElapsed: isPaused || !isTracking ? running : nil
        )
        // "STOPPED · 3 MIN" counts from when the rider stopped, not from the last update.
        if motion != .run {
            if motionSince == nil { motionSince = Date() }
        } else {
            motionSince = nil
        }
        state.motionSince = motionSince
        let extras = liveExtras()
        state.friends = extras.friends
        state.request = extras.request
        state.waiting = extras.waiting
        state.heartRate = extras.heartRate
        state.goalDistance = UserDefaults.standard.object(forKey: "goalDistance") as? Double ?? 20_000
        return state
    }

    /// CLLocation.speed is -1 when the device can't compute a valid speed; clamp it to 0.
    var currentSpeed: CLLocationSpeed { max(0, currentLocation?.speed ?? 0) }

    private func handle(location: CLLocation) {
        currentLocation = location
        hasFixedLocation = true
        guard isTracking, !isPaused else { return }
        guard fixFilter.check(location) == .accept else { return }

        classifier.add(location)
        activity = classifier.activity
        // While standing, GPS drift would add distance and speed from nothing. The point is still
        // stored below, so the ride keeps its timeline.
        if activity != .still {
            if let last = lastLocation {
                let step = location.distance(from: last)
                // Noise-sized steps are skipped and the anchor stays, so slow real movement still adds up.
                // The map line grows only with real steps; the fix itself is saved below either way.
                if DistanceRule.counts(displacement: step, speed: location.speed, accuracy: location.horizontalAccuracy) {
                    traveledDistance += step
                    lastLocation = location
                    trackCoordinates.append(location.coordinate)
                }
            } else {
                lastLocation = location
                trackCoordinates.append(location.coordinate)
            }
            maxSpeed = max(maxSpeed, location.speed)
            if activity == .run, location.verticalAccuracy >= 0, location.verticalAccuracy <= 10 {
                // Drops under 1 m between fixes are mostly altimeter noise.
                if let previous = lastAltitude, previous - location.altitude >= 1 { descent += previous - location.altitude }
                if lastAltitude == nil || abs((lastAltitude ?? 0) - location.altitude) >= 1 { lastAltitude = location.altitude }
            }
        }
        liveActivity.update(liveActivityState)

        let peakG = gSensor.takePeak()
        if let peakG { maxG = max(maxG ?? 0, peakG) }
        if let context = modelContext {
            let point = TrackPoint(location: location, session: session, motion: motion.latest, gForce: peakG, heartRate: heartRate(), sport: Sport.saved)
            context.insert(point)
            try? context.save()
        }
    }
}
