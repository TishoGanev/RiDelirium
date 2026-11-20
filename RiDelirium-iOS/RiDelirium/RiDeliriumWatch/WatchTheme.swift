//
//  WatchTheme.swift
//  RiDeliriumWatch
//

import SwiftUI
import CoreText

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Colours from the RiDelirium Watch handoff.
enum WatchPalette {
    static let surface = Color(hex: 0x1C1C1E)
    static let surface2 = Color(hex: 0x2C2C2E)
    static let secondary = Color(hex: 0x9A9A9F)
    static let red = Color(hex: 0xED213D)
    static let amber = Color(hex: 0xFFB020)
    static let pink = Color(hex: 0xFF4F8B)
    static let cyan = Color(hex: 0x5AC8FA)
    static let green = Color(hex: 0x30D158)
    static let redTrack = Color(hex: 0x3A0E15)
    static let amberTrack = Color(hex: 0x3A2A08)
    static let pinkTrack = Color(hex: 0x3A1424)
    static let userBlue = Color(hex: 0x2E7BF6)
    static let offlineFill = Color(hex: 0x5A6270)
    static let offlineBorder = Color(hex: 0x9AA1AB)
    static let offlineText = Color(hex: 0xC4C9D0)
}

/// The design is drawn on the 410 x 502 pt Apple Watch Ultra canvas; `k` scales it to the watch it runs on.
struct WatchScale {
    let k: CGFloat
    static let designWidth: CGFloat = 410
    static let designHeight: CGFloat = 502

    func pt(_ value: CGFloat) -> CGFloat { value * k }
}

private struct WatchScaleKey: EnvironmentKey {
    static let defaultValue = WatchScale(k: 1)
}

extension EnvironmentValues {
    var watchScale: WatchScale {
        get { self[WatchScaleKey.self] }
        set { self[WatchScaleKey.self] = newValue }
    }
}

enum WatchFont {
    /// Fonts live in the bundle but watchOS has no UIAppFonts, so they are registered at launch.
    static func register() {
        for name in ["OpenSans-CondensedBold", "OpenSans-CondensedExtraBold"] {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    static func extraBold(_ size: CGFloat) -> Font { .custom("OpenSans-CondensedExtraBold", fixedSize: size) }
    static func bold(_ size: CGFloat) -> Font { .custom("OpenSans-CondensedBold", fixedSize: size) }
}

/// Positions a view by its top-left corner in design points.
extension View {
    func placed(x: CGFloat, y: CGFloat, width: CGFloat? = nil, height: CGFloat? = nil, scale: WatchScale) -> some View {
        self
            .frame(width: width.map(scale.pt), height: height.map(scale.pt))
            .offset(x: scale.pt(x), y: scale.pt(y))
    }
}

enum WatchFormat {
    static func km(_ meters: Double, metric: Bool) -> String {
        String(format: "%.1f", metric ? meters / 1000 : meters / 1000 * 0.621)
    }

    static func speed(_ metersPerSecond: Double, metric: Bool) -> Int {
        let kmh = max(metersPerSecond, 0) * 3.6
        return Int((metric ? kmh : kmh * 0.621).rounded())
    }

    static func elapsed(_ seconds: TimeInterval) -> String {
        let total = max(Int(seconds), 0)
        let h = total / 3600, m = (total / 60) % 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    /// "340 M", or "1.2 KM" from a kilometre out.
    static func short(_ meters: Double, metric: Bool) -> String {
        if metric {
            return meters >= 1000 ? String(format: "%.1f KM", meters / 1000) : "\(Int(meters.rounded())) M"
        }
        let feet = meters * 3.281
        return feet >= 5280 ? String(format: "%.1f MI", feet / 5280) : "\(Int(feet.rounded())) FT"
    }
}
