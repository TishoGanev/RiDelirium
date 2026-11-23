//
//  StatsScreen.swift
//  RiDelirium
//

import SwiftUI
import SwiftData
import CoreLocation
import Combine

struct SessionSummary: Identifiable {
    let session: Int
    let points: [TrackPoint]

    var id: Int { session }
    var startDate: Date { points.first?.timestamp ?? Date() }
    var endDate: Date { points.last?.timestamp ?? Date() }
    var maxSpeed: Double { analysis.segments.isEmpty ? (points.map(\.speed).max() ?? 0) : analysis.movingMaxSpeed }
    var coordinates: [CLLocationCoordinate2D] { points.map(\.coordinate) }

    /// What the rider rode, as stored with the recording. Rides saved before it was stored fall back to the
    /// sport currently chosen in Settings.
    var sport: Sport {
        points.first?.sport.flatMap(Sport.init(rawValue:)) ?? Sport.saved
    }

    /// Stable across launches even if a deleted session's number is reused.
    var placeKey: String { "\(session)-\(Int(startDate.timeIntervalSince1970))" }

    /// Runs, lifts and stops. Cached because Stats asks for it on every redraw.
    var analysis: RideAnalysis {
        let key = "\(session)-\(points.count)-\(Int(startDate.timeIntervalSince1970))" as NSString
        if let cached = Self.analysisCache.object(forKey: key) { return cached.analysis }
        let analysis = ActivityDetector.analyze(points)
        Self.analysisCache.setObject(AnalysisBox(analysis), forKey: key)
        return analysis
    }

    private static let analysisCache = NSCache<NSString, AnalysisBox>()

    /// Distance covered while moving; GPS drift from standing still isn't counted.
    var distance: Double {
        let analysis = analysis
        if !analysis.segments.isEmpty { return analysis.movingDistance }
        return DistanceRule.steps(points).reduce(0, +)
    }
}

private final class AnalysisBox {
    let analysis: RideAnalysis
    init(_ analysis: RideAnalysis) { self.analysis = analysis }
}

/// Ski seasons run across New Year, so July starts the next one (e.g. Nov 2025 and Mar 2026 are both 2025/26).
struct Season: Hashable {
    let startYear: Int

    init(containing date: Date, calendar: Calendar = .current) {
        let components = calendar.dateComponents([.year, .month], from: date)
        let year = components.year ?? 2000
        startYear = (components.month ?? 1) >= 7 ? year : year - 1
    }

    var label: String {
        String(format: "%d/%02d", startYear, (startYear + 1) % 100)
    }
}

enum StatsRoute: Hashable {
    case day(Int)
}

struct StatsScreen: View {
    @Binding var path: NavigationPath

    @Environment(\.modelContext) private var modelContext
    @Query(sort: \TrackPoint.timestamp) private var allPoints: [TrackPoint]
    @AppStorage("unitsMetric") private var unitsMetric = true
    @StateObject private var placeNames = PlaceNameStore()
    @State private var pendingDelete: SessionSummary?
    @AppStorage(StatsRange.storageKey) private var rangeRaw = StatsRange.all.rawValue
    @State private var periodInsights: PeriodInsights?
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    private var range: StatsRange { StatsRange(rawValue: rangeRaw) ?? .all }

    private var units: Units { Units(metric: unitsMetric) }

    private var sessions: [SessionSummary] {
        Dictionary(grouping: allPoints, by: \.session)
            .map { SessionSummary(session: $0.key, points: $0.value) }
            .sorted { $0.startDate > $1.startDate }
    }

    var body: some View {
        let allSessions = sessions
        let now = Date()
        let sessions = allSessions.filter { range.contains($0.startDate, now: now) }
        NavigationStack(path: $path) {
            Group {
                if verticalSizeClass == .compact {
                    landscape(for: sessions, hasAnyRides: !allSessions.isEmpty)
                } else {
            VStack(spacing: 0) {
                header(for: sessions)
                rangeBar(for: sessions)
                list(for: sessions, hasAnyRides: !allSessions.isEmpty)
            }
                }
            }
            .background(Color.white)
            .ignoresSafeArea(edges: .top)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: StatsRoute.self) { route in
                switch route {
                case .day(let id):
                    if let session = sessions.first(where: { $0.id == id }) {
                        DailyStatsScreen(session: session, placeName: placeNames.name(for: session))
                    }
                }
            }
        }
        .sheet(item: $periodInsights) { presentation in
            InsightsSheet(model: presentation.model, facts: presentation.facts, title: "PROGRESS · \(presentation.rangeTitle)")
                .presentationDetents([.medium, .large])
        }
        .confirmationDialog(
            "Delete this ride?",
            isPresented: Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("Delete Ride", role: .destructive) {
                if let session = pendingDelete { delete(session) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        }
    }

    // MARK: - Landscape

    /// Sideways: ride cards you swipe through, the period's totals in the bottom bar (Landscape.swift).
    private func landscape(for sessions: [SessionSummary], hasAnyRides: Bool) -> some View {
        GeometryReader { proxy in
            let insets = proxy.safeAreaInsets
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text("YOUR RIDES")
                        .font(.sbFixed(22))
                        .foregroundStyle(.sbCharcoal)
                        .padding(.trailing, 6)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            ForEach(StatsRange.allCases) { option in
                                Button { rangeRaw = option.rawValue } label: {
                                    Text(option.title)
                                        .font(.sb(12))
                                        .tracking(12 * 0.04)
                                        .foregroundStyle(option == range ? Color.white : Color.sbGreyButtonText)
                                        .padding(.horizontal, 12)
                                        .frame(height: 30)
                                        .background(option == range ? Color.sbRed : Color.white, in: Capsule())
                                }
                                .buttonStyle(PressableStyle(pressedBrightness: -0.05))
                                .accessibilityAddTraits(option == range ? .isSelected : [])
                            }
                        }
                    }
                }
                .padding(.trailing, LandscapeMetrics.gearSize + 10)
                .padding(.leading, insets.leading + 8)

                if sessions.isEmpty {
                    Text(hasAnyRides ? "NO RIDES IN THIS PERIOD" : "NO RIDES YET")
                        .font(.sb(18))
                        .tracking(18 * 0.04)
                        .foregroundStyle(.sbSecondary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(sessions) { session in
                                Button { path.append(StatsRoute.day(session.id)) } label: {
                                    rideCard(for: session)
                                }
                                .buttonStyle(PressableStyle())
                                .contextMenu {
                                    Button(role: .destructive) { pendingDelete = session } label: {
                                        Label("Delete", systemImage: "trash")
                                    }
                                }
                                .task { placeNames.resolve(session) }
                            }
                        }
                        .padding(.leading, insets.leading + 8)
                        .padding(.trailing, insets.trailing + 8)
                    }
                    .frame(maxHeight: .infinity)
                }

                LandscapeBar {
                    landscapeTotals(for: sessions)
                } trailing: {
                    if sessions.count >= 2 {
                        Button { showProgressInsights(for: sessions) } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "sparkles").font(.system(size: 16, weight: .bold))
                                Text("INSIGHTS").font(.sb(17))
                            }
                            .foregroundStyle(Color.sbRed)
                            .padding(.horizontal, 18)
                            .frame(height: 60)
                            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .buttonStyle(PressableStyle(pressedBrightness: -0.05))
                        .accessibilityLabel("AI progress insights for the rides shown")
                    }
                }
                .padding(.leading, insets.leading + 8)
                .padding(.trailing, insets.trailing + 8)
            }
            .padding(.top, insets.top + 12)
            .padding(.bottom, max(insets.bottom, 8) + 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(hex: 0xF4F2F0))
            .ignoresSafeArea()
        }
    }

    private func landscapeTotals(for sessions: [SessionSummary]) -> some View {
        let days = Set(sessions.map { Calendar.current.startOfDay(for: $0.startDate) }).count
        let totalDistance = sessions.reduce(0) { $0 + $1.distance }
        let topSpeed = sessions.map(\.maxSpeed).max() ?? 0
        let title: String = switch range {
        case .all: "ALL TIME"
        case .season: "\(Season(containing: Date()).label) SEASON"
        case .today: "TODAY"
        case .days3: "LAST 3 DAYS"
        case .days7: "LAST 7 DAYS"
        case .month: "LAST MONTH"
        }
        return HStack(spacing: 28) {
            LandscapeStat(label: title, value: "\(days) \(days == 1 ? "DAY" : "DAYS")", labelColor: .sbRed)
            LandscapeStat(label: "RIDDEN", value: units.distance(meters: totalDistance))
            LandscapeStat(label: "TOP SPEED", value: units.speed(metersPerSecond: topSpeed))
        }
    }

    private func rideCard(for session: SessionSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            RouteSketch(coordinates: session.coordinates)
                .frame(height: 92)
                .frame(maxWidth: .infinity)
                .background(Color.sbMapBackground)
            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(session.startDate.formatted(.dateTime.day(.twoDigits)))
                        .font(.sbFixed(26))
                        .monospacedDigit()
                        .foregroundStyle(.sbCharcoal)
                    Text(session.startDate.formatted(.dateTime.month(.abbreviated)).uppercased())
                        .font(.sb(12))
                        .foregroundStyle(.sbRed)
                    Text(session.startDate.formatted(.dateTime.year()))
                        .font(.sb(12, .semibold))
                        .foregroundStyle(.sbSecondary)
                }
                Text(placeNames.name(for: session))
                    .font(.sb(16))
                    .foregroundStyle(.sbCharcoal)
                    .lineLimit(1)
                Text("\(units.distance(meters: session.distance)) · \(units.speed(metersPerSecond: session.maxSpeed))")
                    .font(.sb(13, .semibold))
                    .monospacedDigit()
                    .foregroundStyle(.sbSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            Spacer(minLength: 0)
        }
        .frame(width: 176)
        .frame(maxHeight: 210)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(Color(hex: 0xE3E3E3), lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    // MARK: - Header

    private func header(for sessions: [SessionSummary]) -> some View {
        let days = Set(sessions.map { Calendar.current.startOfDay(for: $0.startDate) }).count
        let totalDistance = sessions.reduce(0) { $0 + $1.distance }
        let topSpeed = sessions.map(\.maxSpeed).max() ?? 0
        let title: String = switch range {
        case .all: "ALL TIME"
        case .season: "\(Season(containing: Date()).label) SEASON"
        case .today: "TODAY"
        case .days3: "LAST 3 DAYS"
        case .days7: "LAST 7 DAYS"
        case .month: "LAST MONTH"
        }

        return VStack(alignment: .leading, spacing: 0) {
            Text("\(title) · \(days) RIDING \(days == 1 ? "DAY" : "DAYS")")
                .font(.sb(18))
                .tracking(18 * 0.04)
                .cssLineHeight(1, fontSize: 18)

            HeroNumber(
                value: String(format: "%.1f", units.distanceValue(meters: totalDistance)),
                captionLines: [
                    "\(units.distanceUnit.uppercased()) RIDDEN",
                    "\(units.speed(metersPerSecond: topSpeed)) TOP SPEED",
                ]
            )
            .padding(.top, 8)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 62)
        .padding(.horizontal, 20)
        .padding(.bottom, 22)
        .background(Color.sbRed)
    }

    // MARK: - Time filter and progress insights

    private func rangeBar(for sessions: [SessionSummary]) -> some View {
        VStack(spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(StatsRange.allCases) { option in
                        Button { rangeRaw = option.rawValue } label: {
                            Text(option.title)
                                .font(.sb(14))
                                .tracking(14 * 0.04)
                                .foregroundStyle(option == range ? Color.white : Color.sbGreyButtonText)
                                .padding(.horizontal, 14)
                                .frame(height: 36)
                                .background(option == range ? Color.sbRed : Color.sbLightGrey, in: Capsule())
                        }
                        .buttonStyle(PressableStyle(pressedBrightness: -0.05))
                        .accessibilityAddTraits(option == range ? .isSelected : [])
                    }
                }
                .padding(.horizontal, 16)
            }

            // One ride has nothing to compare against.
            if sessions.count >= 2 {
            Button { showProgressInsights(for: sessions) } label: {
                HStack(spacing: 10) {
                    Image(systemName: "sparkles").font(.system(size: 18, weight: .bold))
                    Text("AI PROGRESS INSIGHTS").font(.sb(16)).tracking(16 * 0.04)
                    Spacer(minLength: 0)
                    Text("\(sessions.count) \(sessions.count == 1 ? "RIDE" : "RIDES")")
                        .font(.sb(13))
                        .opacity(0.7)
                    Chevron(color: .sbRed, lineWidth: 3, size: 10)
                }
                .foregroundStyle(Color.sbRed)
                .padding(.horizontal, 16)
                .frame(height: 48)
                .frame(maxWidth: .infinity)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(PressableStyle(pressedBrightness: -0.05))
            .padding(.horizontal, 16)
            .accessibilityLabel("AI progress insights for the rides shown")
            }
        }
        .padding(.vertical, 12)
        .background(Color.white)
    }

    private func showProgressInsights(for sessions: [SessionSummary]) {
        // Progress only makes sense within one sport, so a mixed period is summarised for the sport ridden most.
        let counts = Dictionary(grouping: sessions, by: \.sport).mapValues(\.count)
        let sport = counts.max { $0.value < $1.value }?.key ?? Sport.saved
        let rides = sessions.filter { $0.sport == sport }
        let facts = RideInsightFacts(period: rides.map(DayMetrics.init), rangeTitle: range.title, units: units, sport: sport)
        periodInsights = PeriodInsights(rangeTitle: range.title, facts: facts, model: RideInsightsModel())
    }

    // MARK: - Rows

    @ViewBuilder
    private func list(for sessions: [SessionSummary], hasAnyRides: Bool) -> some View {
        if sessions.isEmpty {
            Text(hasAnyRides ? "NO RIDES IN THIS PERIOD" : "NO RIDES YET")
                .font(.sb(18))
                .tracking(18 * 0.04)
                .foregroundStyle(.sbSecondary)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.bottom, 110)
        } else {
            List(sessions) { session in
                Button {
                    path.append(StatsRoute.day(session.id))
                } label: {
                    row(for: session)
                }
                .buttonStyle(RowPressStyle())
                .listRowInsets(EdgeInsets())
                .listRowSeparator(.hidden)
                .listRowBackground(Color.white)
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive) {
                        pendingDelete = session
                    } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
                .task { placeNames.resolve(session) }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .contentMargins(.bottom, 110, for: .scrollContent)
            .environment(\.defaultMinListRowHeight, 78)
        }
    }

    private func row(for session: SessionSummary) -> some View {
        HStack(spacing: 14) {
            Text(session.startDate.formatted(.dateTime.day(.twoDigits)))
                .font(.sb(40))
                .monospacedDigit()
                .foregroundStyle(.sbCharcoal)
                .cssLineHeight(1, fontSize: 40)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .frame(minWidth: 44, alignment: .trailing)

            VStack(alignment: .leading, spacing: 3) {
                Text(session.startDate.formatted(.dateTime.month(.abbreviated)).uppercased())
                    .font(.sb(14))
                    .foregroundStyle(.sbRed)
                    .cssLineHeight(1, fontSize: 14)
                Text(session.startDate.formatted(.dateTime.year()))
                    .font(.sb(13, .semibold))
                    .foregroundStyle(.sbSecondary)
                    .cssLineHeight(1, fontSize: 13)
            }
            .frame(width: 40, alignment: .leading)

            VStack(alignment: .leading, spacing: 3) {
                Text(placeNames.name(for: session))
                    .font(.sb(18))
                    .foregroundStyle(.sbCharcoal)
                    .lineLimit(1)
                    .cssLineHeight(1.1, fontSize: 18)
                Text("\(units.distance(meters: session.distance)) · \(units.speed(metersPerSecond: session.maxSpeed))")
                    .font(.sb(14, .semibold))
                    .monospacedDigit()
                    .foregroundStyle(.sbSecondary)
                    .lineLimit(1)
                    .cssLineHeight(1.2, fontSize: 14)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Chevron()
                .padding(.trailing, 4)
        }
        .padding(.leading, 18)
        .padding(.trailing, 16)
        .frame(height: 78)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.sbDivider).frame(height: 1)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func delete(_ session: SessionSummary) {
        for point in session.points {
            modelContext.delete(point)
        }
        try? modelContext.save()
    }
}

/// One opening of the progress sheet; a new model each time so a new period never shows an old summary.
private struct PeriodInsights: Identifiable {
    let id = UUID()
    let rangeTitle: String
    let facts: RideInsightFacts
    let model: RideInsightsModel
}

private struct RowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.sbLightGrey : Color.white)
    }
}

/// The headline number with its two-line caption, shared by Stats (88 pt) and Daily stats (56 pt).
struct HeroNumber: View {
    let value: String
    let captionLines: [String]
    var size: CGFloat = 88

    private var captionSize: CGFloat { size == 88 ? 20 : 15 }
    private var captionBottom: CGFloat { size == 88 ? 8 : 5 }

    var body: some View {
        HStack(alignment: .bottom, spacing: 10) {
            Text(value)
                .font(.sbFixed(size))
                .tracking(-size * 0.02)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .cssLineHeight(0.9, fontSize: size)
            VStack(alignment: .leading, spacing: 0) {
                ForEach(captionLines, id: \.self) { line in
                    Text(line)
                        .font(.sb(captionSize))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .cssLineHeight(1.25, fontSize: captionSize)
                }
            }
            .padding(.bottom, captionBottom)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Resolves a resort or town name for each session's start point. Results are cached across launches.
@MainActor
final class PlaceNameStore: ObservableObject {
    @Published private var names: [String: String]

    private var queue: [(key: String, location: CLLocation)] = []
    private var attempted: Set<String> = []
    private var isResolving = false
    private let geocoder = CLGeocoder()
    private static let defaultsKey = "sessionPlaceNames"

    init() {
        names = UserDefaults.standard.dictionary(forKey: Self.defaultsKey) as? [String: String] ?? [:]
    }

    func name(for session: SessionSummary) -> String {
        names[session.placeKey] ?? "Session \(session.session)"
    }

    func resolve(_ session: SessionSummary) {
        let key = session.placeKey
        guard names[key] == nil, !attempted.contains(key), let first = session.points.first else { return }
        attempted.insert(key)
        queue.append((key, CLLocation(latitude: first.lat, longitude: first.lon)))
        processQueue()
    }

    /// CLGeocoder is rate-limited, so requests run one at a time.
    private func processQueue() {
        guard !isResolving, !queue.isEmpty else { return }
        isResolving = true
        let next = queue.removeFirst()
        Task {
            let placemark = try? await geocoder.reverseGeocodeLocation(next.location).first
            if let name = placemark?.areasOfInterest?.first ?? placemark?.locality ?? placemark?.name {
                names[next.key] = name
                UserDefaults.standard.set(names, forKey: Self.defaultsKey)
            }
            isResolving = false
            processQueue()
        }
    }
}

/// The ride's route drawn flat, scaled to fit, for the landscape ride cards.
struct RouteSketch: View {
    let coordinates: [CLLocationCoordinate2D]

    var body: some View {
        Canvas { context, size in
            guard coordinates.count > 1 else { return }
            let lats = coordinates.map(\.latitude), lons = coordinates.map(\.longitude)
            guard let minLat = lats.min(), let maxLat = lats.max(), let minLon = lons.min(), let maxLon = lons.max() else { return }
            let inset: CGFloat = 14
            let w = size.width - inset * 2, h = size.height - inset * 2
            // Longitude degrees shrink towards the poles; keep the shape's proportions.
            let lonScale = cos((minLat + maxLat) / 2 * .pi / 180)
            let spanX = max((maxLon - minLon) * lonScale, 1e-9), spanY = max(maxLat - minLat, 1e-9)
            let scale = min(w / spanX, h / spanY)
            let offsetX = inset + (w - spanX * scale) / 2, offsetY = inset + (h - spanY * scale) / 2
            // Every point is plenty for a thumbnail only up to a few hundred.
            let step = max(1, coordinates.count / 400)
            var path = Path()
            for (i, c) in coordinates.enumerated() where i % step == 0 || i == coordinates.count - 1 {
                let p = CGPoint(x: offsetX + (c.longitude - minLon) * lonScale * scale,
                                y: offsetY + (maxLat - c.latitude) * scale)
                if path.isEmpty { path.move(to: p) } else { path.addLine(to: p) }
            }
            context.stroke(path, with: .color(.sbRed), style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
        }
        .accessibilityHidden(true)
    }
}
