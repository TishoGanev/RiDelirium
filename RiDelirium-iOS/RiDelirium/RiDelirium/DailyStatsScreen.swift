//
//  DailyStatsScreen.swift
//  RiDelirium
//

import SwiftUI
import MapKit
import UIKit

struct DailyStatsScreen: View {
    let session: SessionSummary
    let placeName: String

    @Environment(\.dismiss) private var dismiss
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @AppStorage("unitsMetric") private var unitsMetric = true
    @State private var cameraPosition: MapCameraPosition
    @State private var shareContent: ShareContent?
    @State private var isPreparingShare = false
    @StateObject private var replay: RideReplay
    @State private var isReplaying = false
    @StateObject private var health = HealthRideStats()
    /// Computed once: the replay redraws the map ~20 times a second.
    private let routeCoordinates: [CLLocationCoordinate2D]
    private let analysis: RideAnalysis
    private let routeParts: [RoutePart]
    private let stillSpots: [CLLocationCoordinate2D]
    @State private var showSegments = false
    @State private var showInsights = false
    @StateObject private var insights = RideInsightsModel()
    private let insightFacts: RideInsightFacts
    @State private var committed: [CLLocationCoordinate2D] = []

    private var units: Units { Units(metric: unitsMetric) }

    init(session: SessionSummary, placeName: String) {
        self.session = session
        self.placeName = placeName
        routeCoordinates = session.coordinates
        let analysis = session.analysis
        self.analysis = analysis
        let coordinates = session.coordinates
        routeParts = analysis.segments.filter { $0.activity != .still }.map { segment in
            // Run one point into the next segment so the coloured lines join up.
            let end = min(segment.endIndex + 1, coordinates.count - 1)
            return RoutePart(id: segment.id, activity: segment.activity, coordinates: Array(coordinates[segment.startIndex...end]))
        }
        insightFacts = RideInsightFacts(
            analysis: analysis, points: session.points, placeName: placeName,
            units: Units(metric: UserDefaults.standard.object(forKey: "unitsMetric") as? Bool ?? true),
            sport: session.sport
        )
        stillSpots = analysis.stills.map { coordinates[($0.startIndex + $0.endIndex) / 2] }
        _cameraPosition = State(initialValue: .rect(Self.routeRect(for: session.coordinates)))
        _replay = StateObject(wrappedValue: RideReplay(points: session.points))
    }

    /// "APPLE HEALTH · 5.20 KM ON FOOT · 128 BPM AVG": what Health recorded for this ride.
    private var healthLine: String {
        var parts = ["APPLE HEALTH"]
        if let meters = health.distanceMeters {
            parts.append("\(units.distance(meters: meters)) \(HealthRideStats.distanceLabel(for: session.sport))")
        }
        if let average = health.averageHeartRate {
            parts.append("\(Int(average.rounded())) BPM AVG")
        }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        Group {
            if verticalSizeClass == .compact {
                landscape
            } else {
        VStack(spacing: 0) {
            header
            map
            if isReplaying {
                ReplayControls(replay: replay, units: units, onClose: closeReplay)
            } else {
                buttons
            }
        }
            }
        }
        .task { await health.load(from: session.startDate, to: session.endDate, sport: session.sport) }
        .onDisappear { replay.pause() }
        .onChange(of: replay.committedCount) { _, _ in committed = replay.committedTrail }
        .onChange(of: isReplaying) { _, _ in committed = replay.committedTrail }
        .background(Color.white)
        .ignoresSafeArea(edges: [.top, .bottom])
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showInsights) {
            InsightsSheet(model: insights, facts: insightFacts)
                .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showSegments) {
            SegmentsSheet(session: session, analysis: analysis, units: units)
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $shareContent) { content in
            ActivityView(items: content.items)
                .presentationDetents([.medium, .large])
        }
    }

    // MARK: - Landscape

    /// Sideways: the map fills the screen, the day's numbers and buttons sit in the bottom bar (Landscape.swift).
    private var landscape: some View {
        GeometryReader { proxy in
            let insets = proxy.safeAreaInsets
            ZStack {
                map
                    .ignoresSafeArea()
                VStack(spacing: 10) {
                    HStack(alignment: .top, spacing: 10) {
                        Button { dismiss() } label: {
                            HStack(spacing: 10) {
                                Chevron(color: .sbCharcoal, lineWidth: 2.5, size: 9, pointsLeft: true)
                                Text(kicker)
                                    .font(.sb(14))
                                    .foregroundStyle(.sbCharcoal)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.7)
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 42)
                            .background(Color.white, in: Capsule())
                            .sbShadow(y: 6, blur: 18, opacity: 0.4)
                        }
                        .buttonStyle(PressableStyle())
                        .accessibilityLabel("Back to Stats")
                        Spacer(minLength: 0)
                        legend
                            .padding(-12)
                            .padding(.top, 2)
                        Button { showInsights = true } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "sparkles").font(.system(size: 13, weight: .bold))
                                Text("INSIGHTS").font(.sb(13)).tracking(13 * 0.04)
                            }
                            .foregroundStyle(Color.sbRed)
                            .padding(.horizontal, 14)
                            .frame(height: 42)
                            .background(Color.white, in: Capsule())
                            .sbShadow(y: 4, blur: 14, opacity: 0.3)
                        }
                        .buttonStyle(PressableStyle())
                        .accessibilityLabel("Ride insights")
                    }
                    .padding(.trailing, LandscapeMetrics.gearSize + 10)

                    Spacer(minLength: 0)

                    if isReplaying {
                        ReplayControls(replay: replay, units: units, onClose: closeReplay)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
                            .sbShadow(y: 14, blur: 40, opacity: 0.35)
                            .frame(maxWidth: 560)
                    } else {
                        LandscapeBar {
                            HStack(spacing: 18) {
                                LandscapeStat(label: units.distanceUnit.uppercased(),
                                              value: String(format: "%.1f", units.distanceValue(meters: session.distance)),
                                              size: 34, labelColor: .sbRed)
                                LandscapeStat(label: "MAX", value: units.speed(metersPerSecond: session.maxSpeed), size: 20)
                                if !analysis.segments.isEmpty {
                                    Button { showSegments = true } label: {
                                        LandscapeStat(label: "RIDING · \(analysis.runs.count) \(analysis.runs.count == 1 ? "RUN" : "RUNS")",
                                                      value: units.compactDuration(analysis.duration(of: .run)), size: 20)
                                    }
                                    .buttonStyle(PressableStyle())
                                    .accessibilityLabel("Show runs and lifts")
                                }
                            }
                        } trailing: {
                            HStack(spacing: 8) {
                                landscapeGreyButton("play.fill", label: "Replay", action: startReplay)
                                    .disabled(session.points.count < 2)
                                landscapeGreyButton("arrow.down.to.line", label: "Export CSV", action: export)
                                Button(action: share) {
                                    Group {
                                        if isPreparingShare {
                                            ProgressView().tint(.white)
                                        } else {
                                            HStack(spacing: 8) {
                                                Image(systemName: "square.and.arrow.up").font(.system(size: 15, weight: .bold))
                                                Text("SHARE").font(.sb(19))
                                            }
                                        }
                                    }
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 18)
                                    .frame(height: 60)
                                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                                    .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
                                }
                                .buttonStyle(PressableStyle())
                                .disabled(isPreparingShare)
                            }
                        }
                    }
                }
                .padding(.top, insets.top + 12)
                .padding(.leading, insets.leading + 8)
                .padding(.trailing, insets.trailing + 8)
                // The screen ignores the bottom safe area, so keep clear of the home indicator here.
                .padding(.bottom, max(insets.bottom, 20))
            }
        }
    }

    private func landscapeGreyButton(_ systemImage: String, label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(.sbGreyButtonText)
                .frame(width: 56, height: 60)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressableStyle(pressedBrightness: -0.05))
        .accessibilityLabel(label)
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 0) {
                BackLink(title: "STATS") { dismiss() }
                Spacer(minLength: 8)
                insightsButton
            }

            Text(kicker)
                .font(.sb(15))
                .tracking(15 * 0.04)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .cssLineHeight(1, fontSize: 15)
                .padding(.top, 6)

            HeroNumber(
                value: String(format: "%.1f", units.distanceValue(meters: session.distance)),
                captionLines: [
                    units.distanceUnit.uppercased(),
                    "\(units.speed(metersPerSecond: session.maxSpeed)) MAX",
                ],
                size: 56
            )
            .padding(.top, 6)

            if health.hasData {
                Text(healthLine)
                    .font(.sb(13))
                    .tracking(13 * 0.04)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .opacity(0.88)
                    .padding(.top, 3)
            }
            if !analysis.segments.isEmpty {
                Button { showSegments = true } label: {
                    HStack(spacing: 6) {
                        Text(breakdown)
                            .font(.sb(13))
                            .tracking(13 * 0.04)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Chevron(color: .white, lineWidth: 2.5, size: 9)
                    }
                    .opacity(0.88)
                    .padding(.top, 3)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressableStyle())
                .accessibilityLabel("Show runs and lifts")
            }
            if !analysis.runs.isEmpty {
                Text(runSummary)
                    .font(.sb(13))
                    .tracking(13 * 0.04)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .opacity(0.88)
                    .padding(.top, 3)
            }

        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 58)
        .padding(.horizontal, 20)
        .padding(.bottom, 16)
        .background(Color.sbRed)
    }

    private var insightsButton: some View {
        Button { showInsights = true } label: {
            HStack(spacing: 6) {
                Image(systemName: "sparkles")
                    .font(.system(size: 13, weight: .bold))
                Text("INSIGHTS")
                    .font(.sb(14))
                    .tracking(14 * 0.04)
                    .cssLineHeight(1, fontSize: 14)
                Chevron(color: .sbRed, lineWidth: 2.5, size: 8)
            }
            .foregroundStyle(Color.sbRed)
            .padding(.horizontal, 12)
            .frame(height: 32)
            .background(Color.white, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(PressableStyle())
        .accessibilityLabel("Ride insights")
    }

    private var runSummary: String {
        let count = analysis.runs.count
        return "\(count) \(count == 1 ? "RUN" : "RUNS") · \(units.distance(meters: analysis.downhillDistance)) DOWNHILL · "
            + "\(units.altitude(meters: analysis.totalVertical)) DESCENT"
    }

    private var breakdown: String {
        let lifts = analysis.lifts.count
        return "RIDING \(units.compactDuration(analysis.duration(of: .run))) · "
            + "\(lifts) \(lifts == 1 ? "LIFT" : "LIFTS") \(units.compactDuration(analysis.duration(of: .lift))) · "
            + "STOPPED \(units.compactDuration(analysis.duration(of: .still)))"
    }

    private var kicker: String {
        let day = session.startDate.formatted(.dateTime.day(.twoDigits).month(.abbreviated)).uppercased()
        let start = session.startDate.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        let end = session.endDate.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
        return "\(placeName.uppercased()) · \(day) · \(start)–\(end)"
    }

    // MARK: - Map

    private var map: some View {
        Map(position: $cameraPosition) {
            if isReplaying {
                routeLines(opacity: 0.3)
                MapPolyline(coordinates: committed)
                    .stroke(Color.sbRed, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                MapPolyline(coordinates: replay.headTrail)
                    .stroke(Color.sbRed, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                if let position = replay.position {
                    Annotation("", coordinate: position, anchor: .center) { UserDot() }
                        .annotationTitles(.hidden)
                }
            } else {
                routeLines(opacity: 1)
                ForEach(Array(stillSpots.enumerated()), id: \.offset) { _, spot in
                    Annotation("Stopped", coordinate: spot, anchor: .center) {
                        Circle().fill(Color.sbStill).frame(width: 9, height: 9)
                    }
                    .annotationTitles(.hidden)
                }
            }
            if let start = routeCoordinates.first {
                Annotation("Start", coordinate: start, anchor: .center) {
                    RouteMarker(fill: .white, stroke: .sbRed)
                }
                .annotationTitles(.hidden)
            }
            if routeCoordinates.count > 1, let end = routeCoordinates.last {
                Annotation("Finish", coordinate: end, anchor: .center) {
                    RouteMarker(fill: .sbRed, stroke: .white)
                }
                .annotationTitles(.hidden)
            }
        }
        .mapControls {}
        .environment(\.colorScheme, .dark)
        .background(Color.sbMapBackground)
        .frame(maxHeight: .infinity)
        .overlay(alignment: .topTrailing) { if verticalSizeClass != .compact { legend } }
    }

    /// Runs red, lifts blue. Without detected segments the whole route is one red line.
    @MapContentBuilder
    private func routeLines(opacity: Double) -> some MapContent {
        if routeParts.isEmpty {
            MapPolyline(coordinates: routeCoordinates)
                .stroke(Color.sbRed.opacity(opacity), style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
        } else {
            ForEach(routeParts) { part in
                MapPolyline(coordinates: part.coordinates)
                    .stroke(
                        (part.activity == .lift ? Color.sbLift : Color.sbRed).opacity(opacity),
                        style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                    )
            }
        }
    }

    @ViewBuilder
    private var legend: some View {
        if !routeParts.isEmpty {
            HStack(spacing: 12) {
                legendItem("RUN", .sbRed)
                legendItem("LIFT", .sbLift)
                if !stillSpots.isEmpty { legendItem("STOP", .sbStill) }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.black.opacity(0.55), in: Capsule())
            .padding(12)
        }
    }

    private func legendItem(_ title: String, _ color: Color) -> some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(title).font(.sb(12)).tracking(12 * 0.04).foregroundStyle(.white)
        }
    }

    // MARK: - Buttons

    private var buttons: some View {
        // Weights 1 : 1.3 : 1, like the Android row.
        GeometryReader { geo in
            let unit = max(0, geo.size.width - 20) / 3.3
            HStack(spacing: 10) {
                greyButton("play.fill", title: "REPLAY", weight: nil, action: startReplay)
                    .disabled(session.points.count < 2)
                    .frame(width: unit)

                Button(action: share) {
                    Group {
                        if isPreparingShare {
                            ProgressView().tint(.white)
                        } else {
                            HStack(spacing: 8) {
                                Image(systemName: "square.and.arrow.up").font(.system(size: 16, weight: .bold))
                                Text("SHARE").font(.sb(20))
                            }
                        }
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .sbShadow(y: 8, blur: 20, opacity: 0.3, color: .sbRed)
                }
                .buttonStyle(PressableStyle())
                .disabled(isPreparingShare)
                .frame(width: unit * 1.3)

                greyButton("arrow.down.to.line", title: "CSV", weight: .bold, action: export)
                    .accessibilityLabel("Export CSV")
                    .frame(width: unit)
            }
        }
        .frame(height: 56)
        .padding(.top, 12)
        .padding(.horizontal, 16)
        .padding(.bottom, 106)
    }

    private func greyButton(_ systemImage: String, title: String, weight: Font.Weight?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: systemImage).font(.system(size: 16, weight: weight ?? .regular))
                Text(title).font(.sb(18))
            }
            .foregroundStyle(.sbGreyButtonText)
            .frame(maxWidth: .infinity)
            .frame(height: 56)
            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressableStyle(pressedBrightness: -0.05))
    }

    // MARK: - Actions

    private var summaryText: String {
        "\(placeName) · \(session.startDate.formatted(date: .abbreviated, time: .omitted)) — "
            + "\(units.distance(meters: session.distance)), top speed \(units.speed(metersPerSecond: session.maxSpeed)). "
            + "Tracked with RiDelirium."
    }

    private func startReplay() {
        isReplaying = true
        replay.restart()
    }

    private func closeReplay() {
        replay.pause()
        isReplaying = false
    }

    private func share() {
        isPreparingShare = true
        Task {
            // Only the 4:5 card for now; the 9:16 Story card waits for Instagram / Facebook Stories.
            let format = ShareCardFormat.feed
            let map = await RouteSnapshot.make(
                coordinates: routeCoordinates,
                parts: routeParts.map { ($0.activity, $0.coordinates) },
                stills: stillSpots,
                size: format.mapSize
            )
            let card = RideShareCard(
                format: format,
                map: map,
                placeName: placeName,
                date: session.startDate.formatted(.dateTime.day(.twoDigits).month(.abbreviated)),
                distance: String(format: "%.1f", units.distanceValue(meters: session.distance)),
                distanceUnit: units.distanceUnit,
                maxSpeed: units.speed(metersPerSecond: session.maxSpeed)
            )
            isPreparingShare = false
            if let image = card.render() {
                shareContent = ShareContent(items: [image, summaryText])
            } else {
                shareContent = ShareContent(items: [summaryText])
            }
        }
    }

    private func export() {
        var metadata = SessionMetadata.load(session: session.session)
        metadata.sport = session.sport.rawValue
        let csv = TrackCSV.csv(points: session.points, metadata: metadata)
        let day = session.startDate.formatted(.iso8601.year().month().day())
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("RiDelirium-\(day)-session-\(session.session).csv")
        do {
            try csv.write(to: url, atomically: true, encoding: .utf8)
            shareContent = ShareContent(items: [url])
        } catch {
            shareContent = ShareContent(items: [csv])
        }
    }

    // MARK: - Geometry

    static func routeRect(for coordinates: [CLLocationCoordinate2D]) -> MKMapRect {
        let points = coordinates.map(MKMapPoint.init)
        guard let first = points.first else { return .world }
        var rect = MKMapRect(origin: first, size: MKMapSize(width: 0, height: 0))
        for point in points.dropFirst() {
            rect = rect.union(MKMapRect(origin: point, size: MKMapSize(width: 0, height: 0)))
        }
        // Keep single-point or very short routes from zooming in to street level.
        let minimumSide = MKMapPointsPerMeterAtLatitude(first.coordinate.latitude) * 400
        let width = max(rect.width, minimumSide)
        let height = max(rect.height, minimumSide)
        let padded = MKMapRect(
            x: rect.midX - width * 0.65,
            y: rect.midY - height * 0.65,
            width: width * 1.3,
            height: height * 1.3
        )
        return padded
    }
}

/// Back link used by pushed screens, which hide the system navigation bar.
struct BackLink: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Chevron(color: .white, lineWidth: 3, size: 11, pointsLeft: true)
                Text(title)
                    .font(.sb(16))
                    .cssLineHeight(1, fontSize: 16)
            }
            .foregroundStyle(.white)
            .frame(height: 44)
            .padding(.trailing, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressableStyle())
        .padding(.leading, -4)
        // The design reserves 34 pt; the extra 10 pt of hit area overlaps the surrounding padding.
        .padding(.vertical, -5)
        .accessibilityLabel("Back to \(title.capitalized)")
    }
}

private struct RoutePart: Identifiable {
    let id: Int
    let activity: Activity
    let coordinates: [CLLocationCoordinate2D]
}

private struct RouteMarker: View {
    let fill: Color
    let stroke: Color

    var body: some View {
        Circle()
            .fill(fill)
            .overlay(Circle().strokeBorder(stroke, lineWidth: 3))
            .frame(width: 12, height: 12)
    }
}

private struct ShareContent: Identifiable {
    let id = UUID()
    let items: [Any]
}

private struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

/// Renders the route on a dark map image for sharing; Map views can't be captured with ImageRenderer.
/// Runs red, lifts blue, stops grey, as on the stats map.
enum RouteSnapshot {
    static func make(
        coordinates: [CLLocationCoordinate2D],
        parts: [(Activity, [CLLocationCoordinate2D])],
        stills: [CLLocationCoordinate2D],
        size: CGSize
    ) async -> UIImage? {
        guard !coordinates.isEmpty else { return nil }
        let options = MKMapSnapshotter.Options()
        options.mapRect = DailyStatsScreen.routeRect(for: coordinates)
        options.size = size
        options.traitCollection = UITraitCollection(userInterfaceStyle: .dark)

        guard let snapshot = try? await MKMapSnapshotter(options: options).start() else { return nil }

        let red = UIColor(Color.sbRed)
        let lift = UIColor(Color.sbLift)
        let still = UIColor(Color.sbStill)
        return UIGraphicsImageRenderer(size: options.size).image { _ in
            snapshot.image.draw(at: .zero)

            func line(_ coordinates: [CLLocationCoordinate2D], color: UIColor) {
                guard coordinates.count > 1 else { return }
                let route = UIBezierPath()
                route.lineWidth = 4
                route.lineCapStyle = .round
                route.lineJoinStyle = .round
                for (index, coordinate) in coordinates.enumerated() {
                    let point = snapshot.point(for: coordinate)
                    index == 0 ? route.move(to: point) : route.addLine(to: point)
                }
                color.setStroke()
                route.stroke()
            }
            // Without detected segments the whole route is one red line.
            if parts.isEmpty {
                line(coordinates, color: red)
            } else {
                for (activity, points) in parts { line(points, color: activity == .lift ? lift : red) }
            }

            func marker(at point: CGPoint, fill: UIColor, stroke: UIColor?, radius: CGFloat = 4.5) {
                let circle = UIBezierPath(ovalIn: CGRect(x: point.x - radius, y: point.y - radius, width: radius * 2, height: radius * 2))
                fill.setFill()
                circle.fill()
                if let stroke {
                    circle.lineWidth = 3
                    stroke.setStroke()
                    circle.stroke()
                }
            }
            for spot in stills { marker(at: snapshot.point(for: spot), fill: still, stroke: nil, radius: 4) }
            if let first = coordinates.first { marker(at: snapshot.point(for: first), fill: .white, stroke: red) }
            if coordinates.count > 1, let last = coordinates.last { marker(at: snapshot.point(for: last), fill: red, stroke: .white) }
        }
    }
}
