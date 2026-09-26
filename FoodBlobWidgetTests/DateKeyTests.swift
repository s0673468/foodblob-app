import XCTest

@testable import FoodBlob

final class DateKeyTests: XCTestCase {
  private var calendar: Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: -3 * 60 * 60)!
    return calendar
  }

  private func date(
    _ year: Int,
    _ month: Int,
    _ day: Int,
    hour: Int,
    minute: Int = 0
  ) -> Date {
    calendar.date(
      from: DateComponents(
        calendar: calendar,
        timeZone: calendar.timeZone,
        year: year,
        month: month,
        day: day,
        hour: hour,
        minute: minute
      )
    )!
  }

  func testDateKeyUsesTheSuppliedLocalCalendar() {
    XCTAssertEqual(
      FoodDateKey.string(
        for: date(2027, 1, 15, hour: 23, minute: 59),
        calendar: calendar
      ),
      "2027-01-15"
    )
  }

  func testNextDayCrossesMonthAndYearBoundaries() {
    let next = FoodDateKey.startOfNextDay(
      after: date(2027, 12, 31, hour: 23),
      calendar: calendar
    )

    XCTAssertEqual(FoodDateKey.string(for: next, calendar: calendar), "2028-01-01")
    XCTAssertEqual(next, calendar.startOfDay(for: next))
  }

  func testDateKeyParserRejectsRolloverDates() {
    XCTAssertNil(FoodDateKey.date(from: "2027-13-01", calendar: calendar))
    XCTAssertNil(FoodDateKey.date(from: "2027-02-30", calendar: calendar))
    XCTAssertNotNil(FoodDateKey.date(from: "2028-02-29", calendar: calendar))
  }
}
