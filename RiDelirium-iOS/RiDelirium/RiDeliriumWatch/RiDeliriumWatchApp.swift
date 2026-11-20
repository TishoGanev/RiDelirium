//
//  RiDeliriumWatchApp.swift
//  RiDeliriumWatch
//

import SwiftUI
import UserNotifications
import WatchConnectivity

@main
struct RiDeliriumWatchApp: App {
    @StateObject private var link = WatchLink()
    @StateObject private var workout = WorkoutManager()
    @StateObject private var gForce = GForceMonitor()
    @StateObject private var compass = WatchCompass()

    init() {
        WatchFont.register()
        UNUserNotificationCenter.current().delegate = WatchNotifications.shared
        UNUserNotificationCenter.current().setNotificationCategories(WaitNotification.categories)
    }

    var body: some Scene {
        WindowGroup {
            WatchTrackView()
                .environmentObject(link)
                .environmentObject(workout)
                .environmentObject(gForce)
                .environmentObject(compass)
        }
    }
}

/// Crew alerts mirrored from the iPhone. While a ride is recorded this app is in front (the workout keeps it
/// there), and watchOS then asks the app whether to show an alert; without an answer it shows nothing.
final class WatchNotifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = WatchNotifications()

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    /// "OK, waiting" on the watch: the phone tells the server, now or as soon as it is reachable.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == WaitNotification.acceptAction,
              let id = response.notification.request.content.userInfo["waitId"] as? String else { return }
        let message = WatchAcceptWait(waitID: id).dictionary
        let session = WCSession.default
        if session.activationState == .activated, session.isReachable {
            session.sendMessage(message, replyHandler: nil) { _ in session.transferUserInfo(message) }
        } else {
            session.transferUserInfo(message)
        }
    }
}
