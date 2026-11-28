import XCTest

/// Runs the cases in Fixtures/widget-cases.json against Model.swift. The Kotlin WidgetFixturesTest
/// (shared/src/androidHostTest) reads the same file and asserts the same expectations.
final class WidgetRulesTests: XCTestCase {
    private struct Fixture: Decodable { let cases: [Case] }

    private struct Case: Decodable {
        let name: String
        let nowMs: Double
        let snapshot: Snapshot
        let expect: Expect
    }

    private struct Expect: Decodable {
        let nextUp: String
        let stale: Bool
        let weekClassCount: Int
        let title: String?
        let examThisWeek: String?
        let hadToday: Bool?
    }

    private func label(_ up: NextUp) -> String {
        switch up {
        case .noData: "noData"
        case .stale: "stale"
        case .inClass: "inClass"
        case .upcoming: "upcoming"
        case .examSoon: "examSoon"
        case .dayDone: "dayDone"
        }
    }

    func testMatchesTheSharedExpectations() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "widget-cases", withExtension: "json"))
        let fixture = try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: url))
        XCTAssertFalse(fixture.cases.isEmpty)
        for c in fixture.cases {
            let now = Date(timeIntervalSince1970: c.nowMs / 1000)
            let snapshot: Snapshot? = c.snapshot
            let up = snapshot.nextUp(now: now)
            XCTAssertEqual(label(up), c.expect.nextUp, "\(c.name): nextUp")
            XCTAssertEqual(up.featured?.title, c.expect.title, "\(c.name): featured title")
            XCTAssertEqual(snapshot.isStale(at: now), c.expect.stale, "\(c.name): stale")
            let week = snapshot.week(now: now)
            XCTAssertEqual(week.days.reduce(0) { $0 + $1.kinds.filter(\.isClass).count }, c.expect.weekClassCount, "\(c.name): classes this week")
            XCTAssertEqual(snapshot.exam(in: week.days, from: Budapest.day(now))?.title, c.expect.examThisWeek, "\(c.name): exam this week")
            if let hadToday = c.expect.hadToday {
                guard case .dayDone(let had, _) = up else { return XCTFail("\(c.name): expected dayDone") }
                XCTAssertEqual(had, hadToday, "\(c.name): hadToday")
            }
        }
    }
}
