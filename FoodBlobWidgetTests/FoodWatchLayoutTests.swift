import XCTest

@testable import FoodBlob

final class FoodWatchLayoutTests: XCTestCase {
  func testFortyMillimetreWatchKeepsPrimaryControlsInCompactLayout() {
    let metrics = FoodWatchTodayLayoutMetrics.resolve(for: 197)

    XCTAssertEqual(metrics.blobHeight, 70)
    XCTAssertEqual(metrics.contentSpacing, 5)
    XCTAssertEqual(metrics.verticalPadding, 4)
    XCTAssertFalse(metrics.showsInstruction)
  }

  func testLargerWatchKeepsTheFullBlobAndInstruction() {
    let metrics = FoodWatchTodayLayoutMetrics.resolve(for: 242)

    XCTAssertEqual(metrics.blobHeight, 116)
    XCTAssertEqual(metrics.contentSpacing, 8)
    XCTAssertEqual(metrics.verticalPadding, 6)
    XCTAssertTrue(metrics.showsInstruction)
  }
}
