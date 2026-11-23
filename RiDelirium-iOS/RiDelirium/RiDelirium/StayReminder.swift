//
//  StayReminder.swift
//  RiDelirium
//

import Foundation
import UserNotifications

/// "Sharing with ANNA'S CREW ends at 13:45": a local notification 10 minutes before a timed stay in a crew ends,
/// with +1 HOUR to stay longer. Tapping it opens Ride Together, where the crew's CHANGE TIME picks any end.
/// Kept in step with the crews by `FriendsStore`; a stay "until I leave" gets none.
@MainActor
enum StayReminder {
    static let category = "stayEnding"
    static let extendAction = "stayExtend"
    static let event = "stayEnding"
    /// How long before the end the reminder shows.
    static let lead: TimeInterval = 10 * 60
    private static let prefix = "stay-"

    static var categories: Set<UNNotificationCategory> {
        let extend = UNNotificationAction(identifier: extendAction, title: "+1 HOUR", options: [])
        return [UNNotificationCategory(identifier: category, actions: [extend], intentIdentifiers: [], options: [])]
    }

    /// One reminder per crew whose end is still more than 10 minutes away; reminders for any other crew go.
    static func sync(_ crews: [Crew], now: Date = Date()) {
        let wanted = crews.filter { ($0.myExpiresAt ?? .distantPast).timeIntervalSince(now) > lead }
        let keep = Set(wanted.map { prefix + $0.id })
        let center = UNUserNotificationCenter.current()
        Task {
            let stale = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) && !keep.contains($0) }
            center.removePendingNotificationRequests(withIdentifiers: stale)
        }
        wanted.forEach { schedule($0, now: now) }
    }

    /// Sets this crew's reminder for its current end, replacing the one it had.
    static func schedule(_ crew: Crew, now: Date = Date()) {
        guard let end = crew.myExpiresAt else { return }
        let fireIn = end.timeIntervalSince(now) - lead
        guard fireIn > 0 else { return }
        let content = UNMutableNotificationContent()
        content.title = "RiDelirium"
        content.body = "Sharing with \(crew.name.uppercased()) ends at \(Stay.clock(end))."
        content.sound = .default
        content.categoryIdentifier = category
        content.threadIdentifier = "crew"
        content.userInfo = ["event": event, "crewId": crew.id, "endsAt": end.timeIntervalSince1970]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: fireIn, repeats: false)
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: prefix + crew.id, content: content, trigger: trigger))
    }

    /// +1 HOUR: the stay ends an hour after it would have. Works when the app wasn't open too.
    static func extend(crewID: String, from end: Date) async {
        let until = end.addingTimeInterval(3600)
        if let store = FriendsStore.current {
            await store.setStay(until: until, in: crewID)
        } else if let service = RemoteFriendsService.configured(identity: FriendsStore.deviceIdentity),
                  let crew = try? await service.setStay(crewID: crewID, until: Stay.clamp(until)) {
            schedule(crew)
        }
    }
}
