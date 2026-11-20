//
//  WaitIntents.swift
//  RiDelirium
//
//  Shared by the app and RideWidget. The button on the lock screen runs this in the app's process.
//

import AppIntents

/// Where the app plugs in what "OK, waiting" does. Set when the app launches; the widget never runs it.
nonisolated enum WaitCoordinator {
    nonisolated(unsafe) static var accept: (@Sendable (String) async -> Void)?
}

struct AcceptWaitIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "OK, waiting"
    static var openAppWhenRun = false

    @Parameter(title: "Request")
    var waitID: String

    init() {}

    init(waitID: String) {
        self.waitID = waitID
    }

    func perform() async throws -> some IntentResult {
        await WaitCoordinator.accept?(waitID)
        return .result()
    }
}
