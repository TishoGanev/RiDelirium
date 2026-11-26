//
//  SettingsScreen.swift
//  RiDelirium
//

import SwiftUI

enum SettingsRoute: Hashable {
    case about
    case health
}

/// "VERSION 2.0.0 (2)" from the bundle, shown at the foot of Settings and in About.
private var appVersionLabel: String {
    let info = Bundle.main.infoDictionary
    let short = info?["CFBundleShortVersionString"] as? String ?? "1.0"
    let build = info?["CFBundleVersion"] as? String ?? "1"
    return "VERSION \(short) (\(build))"
}

struct SettingsScreen: View {
    @Binding var path: NavigationPath
    @AppStorage("unitsMetric") private var unitsMetric = true
    @AppStorage(Sport.storageKey) private var sport = Sport.board
    @EnvironmentObject private var friends: FriendsStore
    @State private var isEditingName = false
    @State private var nameDraft = ""
    @State private var isConfirmingDelete = false
    @State private var isDeleting = false
    @State private var deleteFailed = false
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if verticalSizeClass == .compact {
                    landscape
                } else {
            VStack(spacing: 0) {
                RedTitleHeader(title: "SETTINGS")

                // The rows scroll under the fixed header; the footer's bottom padding clears the floating tab bar.
                ScrollView {
                    VStack(spacing: 0) {
                        nameRow

                        SettingsRow {
                            Text("UNITS")
                                .font(.sb(18))
                                .foregroundStyle(.sbCharcoal)
                            Spacer()
                            SBSegmentedControl(options: [("METRIC", true), ("IMPERIAL", false)], selection: $unitsMetric)
                        }

                        sportRow

                        Button {
                            path.append(SettingsRoute.about)
                        } label: {
                            SettingsRow {
                                Text("ABOUT RIDELIRIUM")
                                    .font(.sb(18))
                                    .foregroundStyle(.sbCharcoal)
                                Spacer()
                                Chevron()
                                    .padding(.trailing, 4)
                            }
                        }
                        .buttonStyle(SettingsRowPressStyle())

                        healthRow

                        socialRows

                        deleteDataRow

                        versionFooter(clearsTabBar: true)
                    }
                }
                .scrollIndicators(.hidden)
            }
                }
            }
            .background(Color.white)
            .ignoresSafeArea(edges: .top)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: SettingsRoute.self) { route in
                switch route {
                case .about: AboutScreen()
                case .health: HealthInfoScreen()
                }
            }
        }
    }

    /// Sideways: the same rows as two columns of cards, with the tabs in the bottom bar (Landscape.swift).
    private var landscape: some View {
        GeometryReader { proxy in
            let insets = proxy.safeAreaInsets
            VStack(alignment: .leading, spacing: 12) {
                Text("SETTINGS")
                    .font(.sbFixed(22))
                    .foregroundStyle(.sbCharcoal)
                    .frame(height: LandscapeMetrics.gearSize)
                    .accessibilityAddTraits(.isHeader)
                ScrollView {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(spacing: 0) {
                            nameRow
                            SettingsRow {
                                Text("UNITS")
                                    .font(.sb(18))
                                    .foregroundStyle(.sbCharcoal)
                                Spacer()
                                SBSegmentedControl(options: [("METRIC", true), ("IMPERIAL", false)], selection: $unitsMetric)
                            }
                            Button {
                                path.append(SettingsRoute.about)
                            } label: {
                                SettingsRow {
                                    Text("ABOUT RIDELIRIUM")
                                        .font(.sb(18))
                                        .foregroundStyle(.sbCharcoal)
                                    Spacer()
                                    Chevron()
                                        .padding(.trailing, 4)
                                }
                            }
                            .buttonStyle(SettingsRowPressStyle())
                            healthRow
                            socialRows
                            deleteDataRow
                        }
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))

                        VStack(spacing: 0) {
                            sportRow
                        }
                        .background(Color.white, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
                        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                    }
                    .padding(.bottom, 12)

                    versionFooter(clearsTabBar: false)
                }
                .scrollIndicators(.hidden)

                LandscapeBar {
                    Text("SETTINGS")
                        .font(.sb(14))
                        .foregroundStyle(.sbSecondary)
                }
            }
            .padding(.top, insets.top + 12)
            .padding(.leading, insets.leading + 8)
            .padding(.trailing, insets.trailing + 8)
            .padding(.bottom, max(insets.bottom, 8) + 4)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(Color(hex: 0xF4F2F0))
            .ignoresSafeArea()
        }
    }

    private func versionFooter(clearsTabBar: Bool) -> some View {
        Text(appVersionLabel)
            .font(.sb(13))
            .tracking(13 * 0.08)
            .foregroundStyle(.sbSecondary)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .padding(.bottom, clearsTabBar ? 90 : 0) // portrait: the floating tab bar overlays the bottom
    }

    /// What the rider rides, and a preview of the tag friends see for it.
    private var sportRow: some View {
        VStack(spacing: 14) {
            HStack(spacing: 12) {
                Text(sport == .hike ? "I WALK/RUN" : "I RIDE")
                    .font(.sb(18))
                    .foregroundStyle(.sbCharcoal)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer()
                SportPicker(selection: $sport)
            }

            HStack(alignment: .center, spacing: 12) {
                Text("How friends see you")
                    .font(.sb(13, .semibold))
                    .foregroundStyle(.white.opacity(0.7))
                Spacer(minLength: 0)
                VStack(alignment: .trailing, spacing: 6) {
                    RiderTag(friend: previewFriend, units: Units(metric: unitsMetric), compact: false, sport: sport)
                    Text(sport.activity)
                        .font(.sb(11))
                        .tracking(11 * 0.04)
                        .foregroundStyle(.white.opacity(0.7))
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(Color.sbMapBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 18)
        .overlay(alignment: .bottom) {
            Rectangle().fill(Color.sbDivider).frame(height: 1)
        }
        // Friends' tags change as soon as the server has it.
        .onChange(of: sport) { _, newSport in friends.setSport(newSport) }
    }

    /// App Store 2.5.1: a section of its own that says plainly where the app uses HealthKit.
    private var healthRow: some View {
        Button {
            path.append(SettingsRoute.health)
        } label: {
            SettingsRow {
                Text("APPLE HEALTH")
                    .font(.sb(18))
                    .foregroundStyle(.sbCharcoal)
                Spacer()
                Chevron()
                    .padding(.trailing, 4)
            }
        }
        .buttonStyle(SettingsRowPressStyle())
    }

    /// Our own accounts, opened in the network's app or the browser.
    @ViewBuilder
    private var socialRows: some View {
        socialRow("FOLLOW ON INSTAGRAM", "https://www.instagram.com/ridelirium.app/")
        socialRow("FOLLOW ON FACEBOOK", "https://www.facebook.com/profile.php?id=61596767940656")
    }

    private func socialRow(_ title: String, _ address: String) -> some View {
        Button {
            if let url = URL(string: address) { openURL(url) }
        } label: {
            SettingsRow {
                Text(title)
                    .font(.sb(18))
                    .foregroundStyle(.sbCharcoal)
                Spacer()
                Chevron()
                    .padding(.trailing, 4)
            }
        }
        .buttonStyle(SettingsRowPressStyle())
        .accessibilityAddTraits(.isLink)
    }

    /// App Store 5.1.1(v): what Ride Together keeps on the server (name, crews, token) can be deleted from the app.
    private var deleteDataRow: some View {
        Button {
            isConfirmingDelete = true
        } label: {
            SettingsRow {
                Text("DELETE MY RIDE TOGETHER DATA")
                    .font(.sb(18))
                    .foregroundStyle(.sbRed)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer()
                if isDeleting { ProgressView() }
            }
        }
        .buttonStyle(SettingsRowPressStyle())
        .disabled(isDeleting)
        .confirmationDialog("Delete your Ride Together data?", isPresented: $isConfirmingDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                isDeleting = true
                Task {
                    let deleted = await friends.deleteRideTogetherData()
                    deleteFailed = !deleted
                    isDeleting = false
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You leave every crew, and your name and crews are removed from the Ride Together server and from this phone. Your recorded rides stay. This can't be undone.")
        }
        .alert("Couldn't delete your data", isPresented: $deleteFailed) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("We are offline. Check your signal and try again.")
        }
    }

    /// You, in the riding state, as a friend's map would draw you.
    private var previewFriend: Friend {
        Friend(
            id: friends.me?.id ?? "preview",
            displayName: friends.me?.displayName ?? "You",
            initials: friends.me?.initials ?? "ME",
            speedKmh: 24,
            updatedAt: .now
        )
    }

    /// The name nearby riders see when you ride together. It stays on this phone.
    private var nameRow: some View {
        SettingsRow {
            Text(friends.me?.initials ?? "SB")
                .font(.sbFixed(14))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Color.sbCharcoal, in: Circle())
            Text((friends.me?.displayName ?? "YOUR NAME").uppercased())
                .font(.sb(18))
                .foregroundStyle(.sbCharcoal)
                .lineLimit(1)
            Spacer()
            Button(friends.me == nil ? "ADD" : "EDIT") {
                nameDraft = friends.me?.displayName ?? ""
                isEditingName = true
            }
            .font(.sb(15))
            .foregroundStyle(.sbRed)
            .buttonStyle(PressableStyle())
        }
        .alert("Your name", isPresented: $isEditingName) {
            TextField("Name", text: $nameDraft)
                .textInputAutocapitalization(.words)
            Button("Cancel", role: .cancel) {}
            Button("Save") { friends.setName(nameDraft) }
        } message: {
            Text("Riders who join your code see this name. It isn't stored anywhere but on your phone.")
        }
    }
}

struct RedTitleHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.sbFixed(40))
            .foregroundStyle(.white)
            .cssLineHeight(1, fontSize: 40)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 62)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
            .background(Color.sbRed)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct SettingsRow<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        HStack(spacing: 12) { content }
            .padding(.horizontal, 20)
            .frame(height: 72)
            .overlay(alignment: .bottom) {
                Rectangle().fill(Color.sbDivider).frame(height: 1)
            }
            .contentShape(Rectangle())
    }
}

private struct SettingsRowPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.sbLightGrey : Color.white)
    }
}

struct AboutScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                BackLink(title: "SETTINGS") { dismiss() }
                Text("ABOUT")
                    .font(.sbFixed(40))
                    .cssLineHeight(1, fontSize: 40)
                    .accessibilityAddTraits(.isHeader)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 58)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
            .background(Color.sbRed)

            VStack(spacing: 14) {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                Text("RIDELIRIUM")
                    .font(.sb(28, .extraBold))
                    .foregroundStyle(.sbCharcoal)
                Text(appVersionLabel)
                    .font(.sb(13))
                    .tracking(13 * 0.08)
                    .foregroundStyle(.sbSecondary)
                Text("Track your runs, speed and altitude on the mountain.")
                    .font(.sb(16, .semibold))
                    .foregroundStyle(.sbSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 40)
            }
            .padding(.top, 40)

            Spacer()
        }
        .background(Color.white)
        .ignoresSafeArea(edges: .top)
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// App Store 2.5.1: where the app says what it does with Apple Health.
struct HealthInfoScreen: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 8) {
                BackLink(title: "SETTINGS") { dismiss() }
                Text("APPLE HEALTH")
                    .font(.sbFixed(40))
                    .cssLineHeight(1, fontSize: 40)
                    .minimumScaleFactor(0.7)
                    .lineLimit(1)
                    .accessibilityAddTraits(.isHeader)
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 58)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
            .background(Color.sbRed)

            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Image(systemName: "heart.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(.sbRed)
                        .frame(maxWidth: .infinity)
                        .accessibilityHidden(true)
                    item("SAVES YOUR RIDES", "With the Apple Watch app, RiDelirium saves each ride to Health as a workout of your sport (skiing, snowboarding, biking or hiking).")
                    item("SHOWS DISTANCE AND HEART RATE", "The ride screen shows the distance and average heart rate Health recorded for that ride, labelled Apple Health.")
                    item("STAYS ON YOUR PHONE", "Nothing from Health is uploaded, shared with friends or used for ads.")
                    item("YOU CHOOSE", "Change or turn off access any time in Settings > Health > Data Access & Devices > RiDelirium.")
                }
                .padding(.horizontal, 24)
                .padding(.top, 32)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .background(Color.white)
        .ignoresSafeArea(edges: .top)
        .toolbar(.hidden, for: .navigationBar)
    }

    private func item(_ title: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.sb(16))
                .tracking(16 * 0.04)
                .foregroundStyle(.sbCharcoal)
            Text(text)
                .font(.sb(16, .semibold))
                .foregroundStyle(.sbSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Four sports as pictograms only, so the row fits beside "I RIDE" without wrapping.
/// The name stays readable under the tag preview and for VoiceOver.
private struct SportPicker: View {
    @Binding var selection: Sport

    var body: some View {
        HStack(spacing: 0) {
            ForEach(Sport.allCases) { sport in
                let selected = sport == selection
                Button { selection = sport } label: {
                    SportGlyph(sport: sport, size: 24)
                        .foregroundStyle(selected ? Color.white : Color.sbCharcoal)
                        .frame(width: 52, height: 44)
                        .background(selected ? Color.sbCharcoal : Color.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(sport.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(Color.sbCharcoal, lineWidth: 1.5)
        )
    }
}
