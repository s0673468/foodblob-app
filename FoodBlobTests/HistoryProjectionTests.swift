import XCTest

@testable import FoodBlob

final class HistoryProjectionTests: XCTestCase {
  func testMonthPortraitsAreWeekdayAlignedAndNeverOpenFutureDays() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    calendar.firstWeekday = 2
    let date = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 7)))
    let cells = HistoryCalendar.cells(month: date, now: date, records: [],
      selectedDate: date, selectedCounts: FoodCounts(green: 4), calendar: calendar)
    XCTAssertNil(cells[0].day, "September 1 is Tuesday in a Monday-first calendar")
    XCTAssertEqual(cells[1].day?.dateKey, "2026-09-01")
    XCTAssertEqual(cells.compactMap(\.day).count, 7)
    XCTAssertEqual(cells[7].day?.counts, FoodCounts(green: 4))
    XCTAssertEqual(cells.count % 7, 0)
  }

  func testFullHistoryDoesNotOfferDatesBeforeRetentionFloor() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 9, day: 7)))
    let records = (0..<180).map { offset -> DayRecord in
      let date = calendar.date(byAdding: .day, value: -offset, to: now)!
      return DayRecord(dateKey: FoodDateKey.string(for: date, calendar: calendar),
        counts: FoodCounts(green: 1), updatedAt: now)
    }
    let oldest = try XCTUnwrap(FoodDateKey.date(from: records.last!.dateKey, calendar: calendar))
    let days = HistoryCalendar.cells(month: oldest, now: now, records: records,
      selectedDate: now, selectedCounts: FoodCounts(green: 1), calendar: calendar).compactMap(\.day)
    XCTAssertEqual(days.first?.dateKey, records.last?.dateKey)
    XCTAssertTrue(days.allSatisfy { $0.date >= oldest })
  }

  func testProjectionBuildsThirtyKeyedDaysAndOverlaysSelectedCounts() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: -5 * 60 * 60)!
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let today = calendar.startOfDay(for: now)
    let yesterday = try XCTUnwrap(
      calendar.date(byAdding: .day, value: -1, to: today)
    )
    let oldDay = try XCTUnwrap(
      calendar.date(byAdding: .day, value: -90, to: today)
    )
    let records = [
      DayRecord(
        dateKey: FoodDateKey.string(for: yesterday, calendar: calendar),
        counts: FoodCounts(green: 2),
        updatedAt: now
      ),
      DayRecord(
        dateKey: FoodDateKey.string(for: oldDay, calendar: calendar),
        counts: FoodCounts(red: 9),
        updatedAt: now
      ),
    ]

    let projection = HistoryProjection.make(
      now: now,
      records: records,
      selectedDate: today,
      selectedCounts: FoodCounts(yellow: 3),
      calendar: calendar
    )

    XCTAssertEqual(projection.count, 30)
    XCTAssertEqual(projection.first?.date, today)
    XCTAssertEqual(projection.first?.counts, FoodCounts(yellow: 3))
    XCTAssertEqual(projection[1].date, yesterday)
    XCTAssertEqual(projection[1].counts, FoodCounts(green: 2))
    XCTAssertFalse(projection.contains { $0.counts.red == 9 })
  }

  func testProjectionUsesStoredCountsWhenSelectionIsOutsideWindow() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let now = Date(timeIntervalSince1970: 1_800_000_000)
    let todayKey = FoodDateKey.string(for: now, calendar: calendar)
    let selectedDate = try XCTUnwrap(
      calendar.date(byAdding: .day, value: -60, to: now)
    )

    let projection = HistoryProjection.make(
      now: now,
      records: [
        DayRecord(
          dateKey: todayKey,
          counts: FoodCounts(green: 1, yellow: 1),
          updatedAt: now
        )
      ],
      selectedDate: selectedDate,
      selectedCounts: FoodCounts(red: 7),
      calendar: calendar
    )

    XCTAssertEqual(projection.first?.counts, FoodCounts(green: 1, yellow: 1))
  }

  func testCheckInSummaryUsesNaturalSingularAndPluralGrammar() {
    XCTAssertEqual(HistoryProjection.checkInSummary(loggedDays: 0), "0 days with a check-in")
    XCTAssertEqual(HistoryProjection.checkInSummary(loggedDays: 1), "1 day with a check-in")
    XCTAssertEqual(HistoryProjection.checkInSummary(loggedDays: 2), "2 days with a check-in")
  }
}
