//
//  HealthRideStats.swift
//  RiDelirium
//

import Foundation
import HealthKit
import Combine

/// Reads what Apple Health holds for a ride's time window: the distance for the rider's sport and the heart
/// rate. The watch app records a workout of the rider's sport while the phone tracks; watchOS fills these in
/// itself.
@MainActor
final class HealthRideStats: ObservableObject {
    /// Metres, or nil when Health has nothing for the ride (no watch, no permission, no data).
    @Published private(set) var distanceMeters: Double?
    @Published private(set) var averageHeartRate: Double?
    @Published private(set) var maxHeartRate: Double?

    private let store = HKHealthStore()
    private let heartRate = HKQuantityType(.heartRate)

    /// Health files a distance under the activity: downhill snow sports, cycling, or walking and running.
    static func distanceType(for sport: Sport) -> HKQuantityType {
        switch sport {
        case .ski, .board: HKQuantityType(.distanceDownhillSnowSports)
        case .bike: HKQuantityType(.distanceCycling)
        case .hike: HKQuantityType(.distanceWalkingRunning)
        }
    }

    /// What the distance is called on the ride screen.
    static func distanceLabel(for sport: Sport) -> String {
        switch sport {
        case .ski, .board: "DOWNHILL"
        case .bike: "CYCLED"
        case .hike: "ON FOOT"
        }
    }

    var hasData: Bool { distanceMeters != nil || averageHeartRate != nil }

    func load(from start: Date, to end: Date, sport: Sport) async {
        guard HKHealthStore.isHealthDataAvailable() else { return }
        let distanceType = Self.distanceType(for: sport)
        do {
            try await store.requestAuthorization(toShare: [], read: [distanceType, heartRate])
        } catch {
            return
        }
        // The workout starts and ends a little either side of the phone's first and last GPS point.
        let predicate = HKQuery.predicateForSamples(
            withStart: start.addingTimeInterval(-60), end: end.addingTimeInterval(60)
        )
        let distance = await statistics(distanceType, predicate, .cumulativeSum)?.sumQuantity()?.doubleValue(for: .meter())
        let heart = await statistics(heartRate, predicate, [.discreteAverage, .discreteMax])
        let perMinute = HKUnit.count().unitDivided(by: .minute())
        distanceMeters = (distance ?? 0) > 0 ? distance : nil
        averageHeartRate = heart?.averageQuantity()?.doubleValue(for: perMinute)
        maxHeartRate = heart?.maximumQuantity()?.doubleValue(for: perMinute)
    }

    private func statistics(_ type: HKQuantityType, _ predicate: NSPredicate, _ options: HKStatisticsOptions) async -> HKStatistics? {
        await withCheckedContinuation { continuation in
            let query = HKStatisticsQuery(quantityType: type, quantitySamplePredicate: predicate, options: options) { _, statistics, _ in
                continuation.resume(returning: statistics)
            }
            store.execute(query)
        }
    }
}
