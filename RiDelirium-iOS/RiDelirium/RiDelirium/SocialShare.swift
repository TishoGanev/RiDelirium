//
//  SocialShare.swift
//  RiDelirium
//

import SwiftUI
import UIKit

/// The two card sizes: 9:16 for Stories, 4:5 for the feed (the tallest ratio feeds show uncropped).
enum ShareCardFormat: CaseIterable {
    case story, feed

    /// Points; rendered at 3x, so 1080 × 1920 and 1080 × 1350 pixels.
    var size: CGSize {
        switch self {
        case .story: CGSize(width: 360, height: 640)
        case .feed: CGSize(width: 360, height: 450)
        }
    }

    /// The map takes the top 52%, as on the website mockup; the red panel the rest.
    var mapSize: CGSize { CGSize(width: size.width, height: (size.height * 0.52).rounded()) }

    /// Type scale for the red panel; the Story card has more room.
    fileprivate var textScale: CGFloat { self == .story ? 1.25 : 1 }
}

/// Share card: route map on top, ride stats on a red panel underneath.
struct RideShareCard: View {
    let format: ShareCardFormat
    let map: UIImage?
    let placeName: String
    let date: String
    let distance: String
    let distanceUnit: String
    let maxSpeed: String

    var body: some View {
        let s = format.textScale
        VStack(spacing: 0) {
            Group {
                if let map {
                    Image(uiImage: map).resizable().scaledToFill()
                } else {
                    Color.sbMapBackground
                }
            }
            .frame(width: format.mapSize.width, height: format.mapSize.height)
            .clipped()

            VStack(alignment: .leading, spacing: 4 * s) {
                Text("\(placeName.uppercased()) · \(date.uppercased())")
                    .font(.sb(16 * s))
                    .tracking(16 * s * 0.04)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)

                HStack(alignment: .firstTextBaseline, spacing: 8 * s) {
                    Text(distance).font(.sbFixed(76 * s))
                    Text(distanceUnit.uppercased()).font(.sb(24 * s))
                }

                Text("\(maxSpeed.uppercased()) MAX")
                    .font(.sb(24 * s))

                Spacer(minLength: 0)

                Text("RIDELIRIUM")
                    .font(.sb(14 * s))
                    .tracking(14 * s * 0.2)
                    .opacity(0.7)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .padding(18 * s)
            .background(Color.sbRed)
        }
        .frame(width: format.size.width, height: format.size.height)
    }

    @MainActor func render() -> UIImage? {
        let renderer = ImageRenderer(content: self)
        renderer.scale = 3 // 1080 wide
        return renderer.uiImage
    }
}

/// Instagram and Facebook Stories, which take a background image through the pasteboard.
///
/// Both want a Meta app id: `FacebookAppID` in Info.plist, filled from the `FACEBOOK_APP_ID`
/// build setting. TODO(FACEBOOK_APP_ID): it is empty today and Facebook refuses the Story without
/// it; set it once the app is registered at developers.facebook.com (Android reads the same id
/// from local.properties).
enum StoryTarget: CaseIterable, Identifiable {
    case instagram, facebook

    var id: Self { self }

    var title: String {
        switch self {
        case .instagram: "Instagram Story"
        case .facebook: "Facebook Story"
        }
    }

    private var scheme: String {
        switch self {
        case .instagram: "instagram-stories"
        case .facebook: "facebook-stories"
        }
    }

    private var pasteboardPrefix: String {
        switch self {
        case .instagram: "com.instagram.sharedSticker"
        case .facebook: "com.facebook.sharedSticker"
        }
    }

    static var appID: String {
        (Bundle.main.object(forInfoDictionaryKey: "FacebookAppID") as? String) ?? ""
    }

    private var url: URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "share"
        components.queryItems = [URLQueryItem(name: "source_application", value: Self.appID)]
        return components.url
    }

    /// Needs the scheme in `LSApplicationQueriesSchemes`.
    @MainActor var isInstalled: Bool {
        guard let url = URL(string: "\(scheme)://share") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    /// [image] is the 9:16 Story card.
    @MainActor func share(_ image: UIImage) {
        guard let url, let png = image.pngData() else { return }
        var item: [String: Any] = ["\(pasteboardPrefix).backgroundImage": png]
        if self == .facebook { item["\(pasteboardPrefix).appID"] = Self.appID }
        UIPasteboard.general.setItems([item], options: [.expirationDate: Date().addingTimeInterval(5 * 60)])
        UIApplication.shared.open(url)
    }
}
