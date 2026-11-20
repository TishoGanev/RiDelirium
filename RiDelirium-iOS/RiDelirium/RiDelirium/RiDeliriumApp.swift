//
//  RiDeliriumApp.swift
//  RiDelirium
//

import SwiftUI
import SwiftData
import FirebaseCore

@main
struct RiDeliriumApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // Crashlytics reads GoogleService-Info.plist; debug builds skip it so test crashes stay out of the dashboard.
        #if !DEBUG
        FirebaseApp.configure()
        #endif
        // The app is English only; without this MapKit labels follow the phone's language (Cyrillic on a Bulgarian phone).
        UserDefaults.standard.set(["en"], forKey: "AppleLanguages")
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(for: TrackPoint.self)
    }
}
