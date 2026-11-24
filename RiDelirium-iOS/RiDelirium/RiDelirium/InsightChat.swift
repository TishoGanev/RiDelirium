//
//  InsightChat.swift
//  RiDelirium
//

import Foundation
import Combine
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Questions about one ride or period, answered by the on-device model from the same facts as the summary.
/// Keep in sync with `InsightChat.kt` on Android.
enum InsightChat {
    struct Exchange: Equatable {
        let question: String
        let answer: String
    }

    /// Hidden for now: the answers were not reliable enough. Set to true to show the question box again.
    static let isEnabled = false

    /// Earlier exchanges sent along with a question; the on-device model's window is small.
    static let maxHistory = 3
    static let maxQuestionLength = 300

    /// The question trimmed and capped, or nil when there is nothing to ask.
    static func clean(_ question: String) -> String? {
        let trimmed = question.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(maxQuestionLength))
    }

    /// Who the model is, the rules, and every fact it may use.
    static func instructions(_ facts: RideInsightFacts) -> String {
        let scope = facts.isPeriod ? "several days of ride data" : "one ride"
        return """
            You are a concise \(facts.coachRole) answering the rider's questions about \(scope). \
            Use only the data below. If the data does not contain the answer, say you don't have that. \
            Answer in one to three short sentences. Never invent numbers, places or events. \
            Plain, friendly language. No emojis. If asked about anything other than this ride's data, \
            say you can only answer questions about the ride. \
            \(facts.coachBrief)

            Data:
            \(facts.prompt)
            """
    }

    /// The last few exchanges, then the new question.
    static func prompt(question: String, history: [Exchange]) -> String {
        var lines = history.suffix(maxHistory).flatMap { ["Rider: \($0.question)", "Coach: \($0.answer)"] }
        lines.append("Rider: \(question)")
        lines.append("Coach:")
        return lines.joined(separator: "\n")
    }

    /// A few questions to start from, matched to the sport and to ride or period.
    static func suggestions(_ facts: RideInsightFacts) -> [String] {
        if facts.isPeriod { return ["Am I improving?", "What changed the most?"] }
        let words = facts.sports.count > 1 ? Sport.bike.words : facts.sport.words
        var list = ["Which \(words.run) was my biggest?", "How did I pace the day?"]
        if facts.sports.contains(where: \.showsSpeed) { list.insert("Which \(words.run) was fastest?", at: 0) }
        return list
    }
}

@MainActor
final class InsightChatModel: ObservableObject {
    struct Message: Identifiable, Equatable {
        let id = UUID()
        let question: String
        var answer: String?
        var failed = false
    }

    @Published private(set) var messages: [Message] = []
    @Published private(set) var isAnswering = false

    func ask(_ raw: String, facts: RideInsightFacts) async {
        guard !isAnswering, let question = InsightChat.clean(raw) else { return }
        let history = messages.compactMap { message in
            message.answer.map { InsightChat.Exchange(question: message.question, answer: $0) }
        }
        messages.append(Message(question: question))
        let index = messages.count - 1
        isAnswering = true
        defer { isAnswering = false }

        var answer: String?
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            answer = await Self.respond(question: question, history: history, facts: facts)
        }
        #endif
        if let answer {
            messages[index].answer = answer
        } else {
            messages[index].failed = true
        }
    }

    #if canImport(FoundationModels)
    @available(iOS 26.0, *)
    private static func respond(question: String, history: [InsightChat.Exchange], facts: RideInsightFacts) async -> String? {
        guard case .available = SystemLanguageModel.default.availability else { return nil }
        let session = LanguageModelSession(instructions: InsightChat.instructions(facts))
        do {
            let response = try await session.respond(to: InsightChat.prompt(question: question, history: history))
            let text = response.content.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : text
        } catch {
            return nil
        }
    }
    #endif
}
