//
//  InsightsSheet.swift
//  RiDelirium
//

import SwiftUI

/// Written insights from the on-device model (when available) above the numbers they are based on.
struct InsightsSheet: View {
    @ObservedObject var model: RideInsightsModel
    let facts: RideInsightFacts
    var title = "INSIGHTS"
    @StateObject private var chat = InsightChatModel()
    @State private var draft = ""
    @FocusState private var typing: Bool

    private var canAsk: Bool {
        guard InsightChat.isEnabled else { return false }
        if case .ready = model.state { return true }
        return false
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(title)
                    .font(.sb(24, .extraBold))
                    .foregroundStyle(.sbCharcoal)

                aiSection

                VStack(alignment: .leading, spacing: 10) {
                    Text("THE NUMBERS")
                        .font(.sb(13))
                        .tracking(13 * 0.04)
                        .foregroundStyle(.sbSecondary)
                    ForEach(facts.bullets, id: \.self) { bullet in
                        HStack(alignment: .top, spacing: 10) {
                            Circle().fill(Color.sbRed).frame(width: 7, height: 7).padding(.top, 8)
                            Text(bullet).font(.sb(18, .semibold)).foregroundStyle(.sbCharcoal)
                        }
                    }
                }

                if canAsk { conversation }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.white)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if canAsk { questionBar }
        }
        .task { await model.generate(facts) }
    }

    /// Questions and answers, or a few questions to start from.
    private var conversation: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("ASK ABOUT THIS \(facts.isPeriod ? "PERIOD" : "RIDE")")
                .font(.sb(13))
                .tracking(13 * 0.04)
                .foregroundStyle(.sbSecondary)
            if chat.messages.isEmpty {
                ForEach(InsightChat.suggestions(facts), id: \.self) { suggestion in
                    Button { send(suggestion) } label: {
                        Text(suggestion)
                            .font(.sb(16, .semibold))
                            .foregroundStyle(.sbCharcoal)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(Color.sbLightGrey, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            ForEach(chat.messages) { message in
                VStack(alignment: .leading, spacing: 8) {
                    Text(message.question)
                        .font(.sb(16, .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(Color.sbCharcoal, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                    if let answer = message.answer {
                        Text(answer)
                            .font(.sb(17, .semibold))
                            .foregroundStyle(.sbCharcoal)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    } else if message.failed {
                        Text("Couldn't answer that. Try asking it another way.")
                            .font(.sb(15, .semibold))
                            .foregroundStyle(.sbSecondary)
                    } else {
                        ProgressView()
                    }
                }
            }
            Text("Answers come from your ride data, written on your iPhone.")
                .font(.sb(12))
                .foregroundStyle(.sbSecondary)
        }
    }

    private var questionBar: some View {
        HStack(spacing: 10) {
            TextField("Ask a question", text: $draft, axis: .vertical)
                .lineLimit(1...3)
                .font(.sb(17, .semibold))
                .focused($typing)
                .submitLabel(.send)
                .onSubmit { send(draft) }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            Button { send(draft) } label: {
                Image(systemName: "arrow.up")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(Color.sbRed, in: Circle())
            }
            .disabled(chat.isAnswering || InsightChat.clean(draft) == nil)
            .opacity(chat.isAnswering || InsightChat.clean(draft) == nil ? 0.4 : 1)
            .accessibilityLabel("Ask")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.white)
        .overlay(alignment: .top) { Rectangle().fill(Color.sbDivider).frame(height: 1) }
    }

    private func send(_ text: String) {
        guard !chat.isAnswering, InsightChat.clean(text) != nil else { return }
        draft = ""
        Task { await chat.ask(text, facts: facts) }
    }

    @ViewBuilder
    private var aiSection: some View {
        switch model.state {
        case .idle, .generating:
            HStack(spacing: 10) {
                ProgressView()
                Text("Writing insights on your iPhone…")
                    .font(.sb(16, .semibold))
                    .foregroundStyle(.sbSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))

        case .ready(let summary):
            VStack(alignment: .leading, spacing: 12) {
                Label("ON-DEVICE AI", systemImage: "sparkles")
                    .font(.sb(13))
                    .tracking(13 * 0.04)
                    .foregroundStyle(.white.opacity(0.85))
                Text(summary.headline)
                    .font(.sb(26, .extraBold))
                    .foregroundStyle(.white)
                ForEach(summary.observations, id: \.self) { line in
                    Text("• \(line)").font(.sb(17, .semibold)).foregroundStyle(.white)
                }
                Text("TIP: \(summary.tip)")
                    .font(.sb(17))
                    .foregroundStyle(.white)
                    .padding(.top, 4)
                Button("WRITE AGAIN") { Task { await model.regenerate(facts) } }
                    .font(.sb(14))
                    .foregroundStyle(.white.opacity(0.85))
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(Color.sbRed, in: RoundedRectangle(cornerRadius: 24, style: .continuous))

        case .unavailable(let message), .failed(let message):
            Text(message)
                .font(.sb(16, .semibold))
                .foregroundStyle(.sbSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(Color.sbLightGrey, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }
}
