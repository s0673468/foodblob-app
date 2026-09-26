import XCTest

@testable import FoodBlob

final class FoodCountsTests: XCTestCase {
  func testFoodColorDisplayNamesMatchAppCopy() {
    XCTAssertEqual(
      FoodColor.allCases.map(\.displayName),
      ["Green", "Yellow", "Red"]
    )
  }

  func testIncrementAndDecrementAreColorSpecific() {
    var counts = FoodCounts()

    counts.increment(.green)
    counts.increment(.green)
    counts.increment(.yellow)
    counts.decrement(.green)

    XCTAssertEqual(counts, FoodCounts(green: 1, yellow: 1, red: 0))
    XCTAssertEqual(counts.total, 2)
  }

  func testDecrementNeverCreatesNegativeCounts() {
    var counts = FoodCounts()

    counts.decrement(.green)
    counts.decrement(.yellow)
    counts.decrement(.red)

    XCTAssertEqual(counts, FoodCounts())
  }

  func testDecodedNegativeCountsAreClamped() throws {
    let data = Data(#"{"green":-4,"yellow":2,"red":-1}"#.utf8)

    let counts = try JSONDecoder().decode(FoodCounts.self, from: data)

    XCTAssertEqual(counts, FoodCounts(green: 0, yellow: 2, red: 0))
  }

  func testCountLookupMatchesStoredComponents() {
    let counts = FoodCounts(green: 3, yellow: 2, red: 1)

    XCTAssertEqual(counts.count(for: .green), 3)
    XCTAssertEqual(counts.count(for: .yellow), 2)
    XCTAssertEqual(counts.count(for: .red), 1)
  }

  func testCountTextUsesNaturalSingularAndPluralGrammar() {
    XCTAssertEqual(FoodCountText.offerings(0), "0 offerings")
    XCTAssertEqual(FoodCountText.offerings(1), "1 offering")
    XCTAssertEqual(FoodCountText.offerings(2), "2 offerings")
    XCTAssertEqual(FoodCountText.changes(0), "0 changes")
    XCTAssertEqual(FoodCountText.changes(1), "1 change")
    XCTAssertEqual(FoodCountText.changes(2), "2 changes")
  }
}
