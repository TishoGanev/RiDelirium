//
//  Sport.swift
//  RiDelirium
//

import SwiftUI

/// What the rider rides. Shown as the glyph on their map tag and crew chip.
nonisolated enum Sport: String, CaseIterable, Identifiable, Codable {
    case ski, board, bike, walk

    static let storageKey = "sport"

    var id: String { rawValue }

    /// Label in the Settings control.
    var title: String {
        switch self {
        case .ski: "SKI"
        case .board: "BOARD"
        case .bike: "BIKE"
        case .walk: "WALK"
        }
    }

    /// What friends read under the tag preview.
    var activity: String {
        switch self {
        case .ski: "SKIING"
        case .board: "SNOWBOARDING"
        case .bike: "BIKING"
        case .walk: "WALKING"
        }
    }

    var assetName: String {
        switch self {
        case .ski: "SportSki"
        case .board: "SportBoard"
        case .bike: "SportBike"
        case .walk: "SportWalk"
        }
    }

    /// The settings button: a gear with this sport inside.
    var settingsAssetName: String {
        switch self {
        case .ski: "SettingsSki"
        case .board: "SettingsBoard"
        case .bike: "SettingsBike"
        case .walk: "SettingsWalk"
        }
    }

    /// The rider's own choice, as saved on this phone.
    static var saved: Sport {
        Sport(rawValue: UserDefaults.standard.string(forKey: storageKey) ?? "") ?? .board
    }
}

/// The sport pictogram, drawn as a template so it takes the tint it is given.
struct SportGlyph: View {
    let sport: Sport
    var size: CGFloat = 20

    var body: some View {
        Image(sport.assetName)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}
