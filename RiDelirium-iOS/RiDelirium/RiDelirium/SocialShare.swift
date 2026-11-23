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
