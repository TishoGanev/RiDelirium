//
//  Push.swift
//  RiDelirium
//

import UIKit
import UserNotifications

extension Notification.Name {
    static let pushTokenChanged = Notification.Name("pushTokenChanged")
    /// A "joined / left your crew" notification arrived or was tapped.
    static let crewPushReceived = Notification.Name("crewPushReceived")
    static let crewPushOpened = Notification.Name("crewPushOpened")
    /// The rider tapped "X invites you to ride together"; `userInfo["code"]` is the crew's code.
    static let invitePushOpened = Notification.Name("invitePushOpened")
}

enum Push {
    static let tokenKey = "pushToken"

    /// Debug builds run from Xcode register with Apple's sandbox; TestFlight and App Store builds with production.
    static var isSandbox: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }

    static var token: String? { UserDefaults.standard.string(forKey: tokenKey) }

    /// Asks for permission the first time, and always refreshes the device token when allowed (it can change).
    @MainActor
    static func enable() async {
        let center = UNUserNotificationCenter.current()
        let status = await center.notificationSettings().authorizationStatus
        if status == .notDetermined {
            guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        } else if status == .denied {
            return
        }
        UIApplication.shared.registerForRemoteNotifications()
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().setNotificationCategories(WaitNotification.categories)
        // "OK, waiting" on the lock screen runs here, even when the app was not open.
        WaitCoordinator.accept = { id in
            if let store = await FriendsStore.current {
                await store.acceptWait(id: id)
            } else if let service = RemoteFriendsService.configured(identity: await FriendsStore.deviceIdentity) {
                try? await service.acceptWait(id: id)
            }
        }
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        UserDefaults.standard.set(token, forKey: Push.tokenKey)
        NotificationCenter.default.post(name: .pushTokenChanged, object: nil)
    }

    func application(_ application: UIApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // No signal or a simulator: the crew still works, just without notifications.
    }

    /// Silent pushes (a friend changed their sport or name): refresh the crew now.
    func application(_ application: UIApplication, didReceiveRemoteNotification userInfo: [AnyHashable: Any]) async -> UIBackgroundFetchResult {
        let event = userInfo["event"] as? String
        NotificationCenter.default.post(name: .crewPushReceived, object: nil, userInfo: ["event": event ?? ""])
        return .newData
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        let event = notification.request.content.userInfo["event"] as? String
        NotificationCenter.default.post(name: .crewPushReceived, object: nil, userInfo: ["event": event ?? ""])
        return [.banner, .sound]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        if response.actionIdentifier == WaitNotification.acceptAction, let id = info["waitId"] as? String {
            await WaitCoordinator.accept?(id)
        } else if info["event"] as? String == "invite", let code = info["code"] as? String {
            NotificationCenter.default.post(name: .invitePushOpened, object: nil, userInfo: ["code": code])
        } else {
            NotificationCenter.default.post(name: .crewPushOpened, object: nil)
        }
    }
}
