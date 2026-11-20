//
//  LiveActivityCards.swift
//  RideWidget
//
//  The cards of the RiDelirium Live Activity handoff: riding (3a), stopped (3b), incoming "wait for me" (1a)
//  and waiting (1c), plus the pieces they share.
//

import AppIntents
import SwiftUI
import WidgetKit

// MARK: - Look

enum LivePalette {
    static let card = Color(red: 0x14 / 255, green: 0x14 / 255, blue: 0x16 / 255)
    static let surface2 = Color(red: 0x2C / 255, green: 0x2C / 255, blue: 0x2E / 255)
    static let secondary = Color(red: 0x8E / 255, green: 0x8E / 255, blue: 0x93 / 255)
    static let red = Color(red: 0xED / 255, green: 0x21 / 255, blue: 0x3D / 255)
    static let amber = Color(red: 1, green: 0xB0 / 255, blue: 0x20 / 255)
    static let pink = Color(red: 1, green: 0x4F / 255, blue: 0x8B / 255)
    static let green = Color(red: 0x30 / 255, green: 0xD1 / 255, blue: 0x58 / 255)
    static let goalTrack = Color(red: 0x3A / 255, green: 0x0E / 255, blue: 0x15 / 255)
    /// Same order as the phone and the watch.
    static let friends: [Color] = [0x3D7FD9, 0x3A9E57, 0x8C6AD6, 0xC17F1E, 0xD2556B].map {
        Color(red: Double(($0 >> 16) & 0xFF) / 255, green: Double(($0 >> 8) & 0xFF) / 255, blue: Double($0 & 0xFF) / 255)
    }

    static func friend(_ index: Int) -> Color { friends[((index % friends.count) + friends.count) % friends.count] }
}

extension Font {
    /// Open Sans Condensed is bundled with the extension (UIAppFonts); the system condensed face is the fallback.
    static func live(_ size: CGFloat, extraBold: Bool = true) -> Font {
        .custom(extraBold ? "OpenSans-CondensedExtraBold" : "OpenSans-CondensedBold", size: size)
    }
}

struct LiveFormat {
    let metric: Bool

    func speed(_ kmh: Double) -> String { String(Int((metric ? kmh : kmh * 0.621).rounded())) }
    func speed(metersPerSecond: Double) -> String { speed(max(metersPerSecond, 0) * 3.6) }
    var speedUnit: String { metric ? "KM/H" : "MPH" }
    var distanceUnit: String { metric ? "KM" : "MI" }

    func distance(_ meters: Double) -> String {
        String(format: "%.1f", metric ? meters / 1000 : meters / 1000 * 0.621)
    }

    /// "340 M", or "1.2 KM" from a kilometre out.
    func short(_ meters: Double) -> String {
        if metric {
            return meters >= 1000 ? String(format: "%.1f KM", meters / 1000) : "\(Int(meters.rounded())) M"
        }
        let feet = meters * 3.281
        return feet >= 5280 ? String(format: "%.1f MI", feet / 5280) : "\(Int(feet.rounded())) FT"
    }
}

// MARK: - Pieces

struct LiveAvatar: View {
    let initials: String
    let colorIndex: Int
    var size: CGFloat = 26
    var border: Color = LivePalette.card
    var borderWidth: CGFloat = 2
    var fontSize: CGFloat = 10

    var body: some View {
        Text(initials)
            .font(.live(fontSize))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(LivePalette.friend(colorIndex), in: Circle())
            .overlay(Circle().strokeBorder(border, lineWidth: borderWidth))
    }
}

/// An arrow that points the way to a friend, relative to where the rider is heading.
struct LiveArrow: View {
    let degrees: Double?
    var size: CGFloat = 16
    var color: Color = .white

    var body: some View {
        if let degrees {
            Image(systemName: "arrow.up")
                .font(.system(size: size, weight: .heavy))
                .foregroundStyle(color)
                .rotationEffect(.degrees(degrees))
        }
    }
}

struct LiveDot: View {
    let color: Color
    var size: CGFloat = 9

    var body: some View { Circle().fill(color).frame(width: size, height: size) }
}

private struct LiveBar: View {
    let progress: Double
    let fill: Color
    let track: Color
    let height: CGFloat

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(fill).frame(width: proxy.size.width * min(max(progress, 0), 1))
            }
        }
        .frame(height: height)
    }
}

/// Counts up on its own while riding; shows the frozen time while paused.
struct LiveTimer: View {
    let state: RideActivityAttributes.ContentState

    var body: some View {
        if let paused = state.pausedElapsed {
            Text(Duration.seconds(paused).formatted(.time(pattern: paused >= 3600 ? .hourMinuteSecond : .minuteSecond)))
        } else {
            Text(timerInterval: state.timerStart...Date.distantFuture, countsDown: false)
        }
    }
}

extension RideActivityAttributes.ContentState {
    enum Face { case riding, stopped, lift, request, waiting, finished }

    /// What the card shows: a request or a wait takes over; otherwise the rider's motion.
    var face: Face {
        if finished { return .finished }
        if request != nil { return .request }
        if waiting != nil { return .waiting }
        switch motion {
        case .run: return .riding
        case .lift: return .lift
        case .still: return .stopped
        }
    }
}

// MARK: - 3a Riding

struct RidingCard: View {
    let state: RideActivityAttributes.ContentState
    let format: LiveFormat
    var showGoal = true

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 6) {
                    LiveDot(color: LivePalette.red)
                    Text(state.pausedElapsed != nil ? "PAUSED · " : "RECORDING · ")
                        .font(.live(14)).tracking(14 * 0.06)
                    LiveTimer(state: state).font(.live(14)).monospacedDigit()
                }
                .foregroundStyle(LivePalette.red)
                Spacer(minLength: 8)
                FriendStack(friends: state.friends)
            }
            HStack(alignment: .bottom) {
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text(format.distance(state.distance))
                        .font(.live(56)).monospacedDigit().foregroundStyle(.white)
                        .minimumScaleFactor(0.6).lineLimit(1)
                    Text(format.distanceUnit).font(.live(15, extraBold: false)).foregroundStyle(LivePalette.secondary)
                }
                Spacer(minLength: 8)
                HStack(spacing: 18) {
                    stat(format.speed(metersPerSecond: state.speed), format.speedUnit, LivePalette.amber)
                    stat(format.speed(metersPerSecond: state.maxSpeed), "MAX", .white)
                    if let heartRate = state.heartRate {
                        stat(String(Int(heartRate.rounded())), "BPM", LivePalette.pink)
                    }
                }
            }
            if showGoal {
                LiveBar(progress: state.goalDistance > 0 ? state.distance / state.goalDistance : 0,
                        fill: LivePalette.red, track: LivePalette.goalTrack, height: 6)
            }
        }
    }

    private func stat(_ value: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .trailing, spacing: 0) {
            Text(value).font(.live(24)).monospacedDigit().foregroundStyle(color)
            Text(label).font(.live(11, extraBold: false)).foregroundStyle(LivePalette.secondary)
        }
    }
}

/// Up to three avatars, overlapping.
struct FriendStack: View {
    let friends: [LiveFriend]

    var body: some View {
        HStack(spacing: -8) {
            ForEach(Array(friends.prefix(3).enumerated()), id: \.offset) { _, friend in
                LiveAvatar(initials: friend.initials, colorIndex: friend.colorIndex, size: 26)
            }
        }
    }
}

// MARK: - 3b Stopped / on lift

struct StoppedCard: View {
    let state: RideActivityAttributes.ContentState
    let format: LiveFormat

    private var label: String { state.face == .lift ? "ON LIFT" : "STOPPED" }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                HStack(spacing: 0) {
                    Text("\(label) · ")
                    if let since = state.motionSince {
                        Text(timerInterval: since...Date.distantFuture, countsDown: false).monospacedDigit()
                    } else {
                        Text("NOW")
                    }
                }
                .font(.live(14)).foregroundStyle(LivePalette.secondary)
                Spacer(minLength: 8)
                HStack(spacing: 4) {
                    Text("\(format.distance(state.distance)) \(format.distanceUnit) ·")
                    LiveTimer(state: state).monospacedDigit()
                }
                .font(.live(14)).foregroundStyle(.white)
            }
            .padding(.bottom, 2)

            if state.friends.isEmpty {
                // No zeros: without friends the card shows the one number that still means something.
                Text("TOP SPEED \(format.speed(metersPerSecond: state.maxSpeed)) \(format.speedUnit)")
                    .font(.live(16)).foregroundStyle(LivePalette.amber)
            } else {
                ForEach(Array(state.friends.prefix(3).enumerated()), id: \.offset) { _, friend in
                    FriendRow(friend: friend, format: format)
                }
            }
        }
    }
}

struct FriendRow: View {
    let friend: LiveFriend
    let format: LiveFormat

    private var detailColor: Color {
        friend.status == .riding ? LivePalette.friend(friend.colorIndex) : LivePalette.secondary
    }

    var body: some View {
        HStack(spacing: 10) {
            LiveAvatar(initials: friend.initials, colorIndex: friend.colorIndex, size: 30, fontSize: 11)
            Text(friend.name.uppercased()).font(.live(18)).foregroundStyle(.white).lineLimit(1)
            Spacer(minLength: 8)
            HStack(spacing: 4) {
                if let distance = friend.distanceM { Text(format.short(distance)) }
                LiveArrow(degrees: friend.distanceM == nil ? nil : friend.bearingDeg, size: 14, color: detailColor)
                Text("· \(friend.status.rawValue.uppercased())")
            }
            .font(.live(16)).foregroundStyle(detailColor).lineLimit(1)
        }
    }
}

// MARK: - 1a Incoming "wait for me"

struct RequestCard: View {
    let request: LiveRequest
    let format: LiveFormat

    private var ageText: String {
        let age = Date().timeIntervalSince1970 - request.sentAt
        return age < 60 ? "JUST NOW" : "\(Int(age / 60)) MIN AGO"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                ZStack(alignment: .topTrailing) {
                    LiveAvatar(initials: request.initials, colorIndex: request.colorIndex, size: 52, border: .white, borderWidth: 3, fontSize: 19)
                    if request.count > 1 {
                        Text("+\(request.count - 1)").font(.live(11)).foregroundStyle(LivePalette.red)
                            .padding(.horizontal, 5).padding(.vertical, 1).background(.white, in: Capsule())
                            .offset(x: 6, y: -4)
                    }
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text("\(request.fromName.uppercased()) · \(ageText)")
                        .font(.live(13, extraBold: false)).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    Text("WAIT FOR ME").font(.live(30)).foregroundStyle(.white).minimumScaleFactor(0.7).lineLimit(1)
                }
                Spacer(minLength: 4)
                VStack(spacing: 2) {
                    LiveArrow(degrees: request.bearingDeg, size: 34)
                    if let distance = request.distanceM { Text(format.short(distance)).font(.live(15)).foregroundStyle(.white) }
                }
            }
            WaitButtons(request: request, buttonHeight: 48, mapWidth: 96, mapFill: .black.opacity(0.22))
        }
    }
}

/// OK, WAITING answers through an App Intent (no need to open the app); MAP opens the app on the sender.
struct WaitButtons: View {
    let request: LiveRequest
    let buttonHeight: CGFloat
    let mapWidth: CGFloat
    let mapFill: Color
    var okFill: Color = .white
    var okText: Color = LivePalette.red

    var body: some View {
        HStack(spacing: 8) {
            Button(intent: AcceptWaitIntent(waitID: request.id)) {
                Text("OK, WAITING").font(.live(18)).foregroundStyle(okText)
                    .frame(maxWidth: .infinity).frame(height: buttonHeight)
                    .background(okFill, in: Capsule())
            }
            .buttonStyle(.plain)
            Link(destination: URL(string: "ridelirium://ride?friend=\(request.friendID ?? "")")!) {
                Text("MAP").font(.live(18)).foregroundStyle(.white)
                    .frame(width: mapWidth, height: buttonHeight)
                    .background(mapFill, in: Capsule())
            }
        }
    }
}

// MARK: - 1c Waiting

struct WaitingCard: View {
    let waiting: LiveWaiting
    let format: LiveFormat

    var body: some View {
        let color = LivePalette.friend(waiting.colorIndex)
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                HStack(spacing: 6) {
                    LiveDot(color: LivePalette.green)
                    Text(waiting.arrived ? "\(waiting.name.uppercased()) IS HERE" : "YOU'RE WAITING FOR \(waiting.name.uppercased())")
                        .font(.live(14)).foregroundStyle(LivePalette.green).lineLimit(1).minimumScaleFactor(0.7)
                }
                Spacer(minLength: 8)
                if !waiting.arrived, let eta = waiting.etaSec {
                    Text(eta >= 90 ? "~\(Int((eta / 60).rounded())) MIN" : "~\(Int(eta.rounded())) S")
                        .font(.live(14, extraBold: false)).foregroundStyle(LivePalette.secondary)
                }
            }
            HStack(spacing: 12) {
                LiveAvatar(initials: waiting.initials, colorIndex: waiting.colorIndex, size: 52, border: .white, borderWidth: 3, fontSize: 19)
                HStack(alignment: .lastTextBaseline, spacing: 4) {
                    Text(format.short(waiting.distanceM).components(separatedBy: " ").first ?? "")
                        .font(.live(40)).monospacedDigit().foregroundStyle(.white)
                    Text("\(format.short(waiting.distanceM).components(separatedBy: " ").last ?? "") AWAY")
                        .font(.live(14, extraBold: false)).foregroundStyle(LivePalette.secondary)
                }
                Spacer(minLength: 4)
                HStack(spacing: 4) {
                    LiveArrow(degrees: waiting.bearingDeg, size: 16, color: color)
                    Text("\(format.speed(waiting.speed)) \(format.speedUnit)").font(.live(16)).foregroundStyle(color)
                }
            }
            LiveBar(progress: waiting.progress, fill: color, track: LivePalette.surface2, height: 8)
        }
    }
}

// MARK: - 3 Finished

struct FinishedCard: View {
    let state: RideActivityAttributes.ContentState
    let format: LiveFormat

    var body: some View {
        HStack(alignment: .lastTextBaseline) {
            Text("RIDE COMPLETE").font(.live(14)).foregroundStyle(LivePalette.red)
            Spacer()
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(format.distance(state.distance)).font(.live(40)).monospacedDigit().foregroundStyle(.white)
                Text(format.distanceUnit).font(.live(15, extraBold: false)).foregroundStyle(LivePalette.secondary)
            }
            LiveTimer(state: state).font(.live(18)).monospacedDigit().foregroundStyle(.white).frame(maxWidth: 80, alignment: .trailing)
        }
    }
}

// MARK: - Lock screen

struct LockScreenView: View {
    let state: RideActivityAttributes.ContentState
    let format: LiveFormat

    var body: some View {
        Group {
            switch state.face {
            case .riding: RidingCard(state: state, format: format)
            case .stopped, .lift: StoppedCard(state: state, format: format)
            case .request:
                if let request = state.request { RequestCard(request: request, format: format) }
            case .waiting:
                if let waiting = state.waiting { WaitingCard(waiting: waiting, format: format) }
            case .finished: FinishedCard(state: state, format: format)
            }
        }
        .padding(16)
    }
}
