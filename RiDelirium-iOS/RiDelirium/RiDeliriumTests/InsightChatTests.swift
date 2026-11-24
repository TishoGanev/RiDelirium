import XCTest
@testable import RiDelirium

final class InsightChatTests: XCTestCase {
    private func facts(sport: Sport = .board, isPeriod: Bool = false) -> RideInsightFacts {
        RideInsightFacts(bullets: ["a"], prompt: "Runs: 3\nRun 1: 2 min", hasRuns: true, isPeriod: isPeriod, sport: sport)
    }

    func testBlankQuestionsAreDropped() {
        XCTAssertNil(InsightChat.clean("   \n"))
        XCTAssertEqual(InsightChat.clean("  fastest run? "), "fastest run?")
    }

    func testLongQuestionIsCapped() {
        XCTAssertEqual(InsightChat.clean(String(repeating: "a", count: 900))?.count, InsightChat.maxQuestionLength)
    }

    func testInstructionsCarryTheFactsAndTheRules() {
        let text = InsightChat.instructions(facts())
        XCTAssertTrue(text.contains("Run 1: 2 min"))
        XCTAssertTrue(text.contains("If the data does not contain the answer, say you don't have that"))
        XCTAssertTrue(text.contains("snowboarding coach"))
        XCTAssertTrue(InsightChat.instructions(facts(sport: .hike)).contains("hiking guide"))
        XCTAssertTrue(InsightChat.instructions(facts(isPeriod: true)).contains("several days"))
    }

    func testPromptKeepsOnlyTheLastExchanges() {
        let history = (1...5).map { InsightChat.Exchange(question: "q\($0)", answer: "a\($0)") }
        let prompt = InsightChat.prompt(question: "now?", history: history)
        XCTAssertFalse(prompt.contains("q1"))
        XCTAssertFalse(prompt.contains("q2"))
        XCTAssertTrue(prompt.contains("Rider: q3"))
        XCTAssertTrue(prompt.hasSuffix("Rider: now?\nCoach:"))
    }

    func testHikeSuggestionsNeverAskForFastest() {
        XCTAssertFalse(InsightChat.suggestions(facts(sport: .hike)).contains { $0.contains("fastest") })
        XCTAssertTrue(InsightChat.suggestions(facts(sport: .ski)).contains { $0.contains("fastest") })
        XCTAssertEqual(InsightChat.suggestions(facts(isPeriod: true)).count, 2)
    }
}
