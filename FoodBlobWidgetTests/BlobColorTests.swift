import XCTest

@testable import FoodBlob

final class BlobColorTests: XCTestCase {
  private func mix(_ green: Int, _ yellow: Int, _ red: Int) -> BlobColor {
    guard
      let color = BlobColor.mixed(
        counts: FoodCounts(green: green, yellow: yellow, red: red)
      )
    else {
      XCTFail("Expected a mixed color")
      return .empty
    }
    return color
  }

  private func chroma(_ color: BlobColor) -> Double {
    max(color.red, max(color.green, color.blue))
      - min(color.red, min(color.green, color.blue))
  }

  func testEmptyCountsHaveNoInventedColor() {
    XCTAssertNil(BlobColor.mixed(counts: FoodCounts()))
  }

  func testFoodPigmentsStayDistinctAndRichWithoutClipping() {
    for color in [BlobColor.foodGreen, .foodYellow, .foodRed] {
      XCTAssertGreaterThan(chroma(color), 0.65)
      XCTAssertLessThan(max(color.red, max(color.green, color.blue)), 1)
      XCTAssertGreaterThan(min(color.red, min(color.green, color.blue)), 0)
    }
  }

  func testPureGreenMatchesTheGreenToken() {
    let mixed = mix(5, 0, 0)

    XCTAssertEqual(mixed.red, BlobColor.foodGreen.red, accuracy: 0.01)
    XCTAssertEqual(mixed.green, BlobColor.foodGreen.green, accuracy: 0.01)
    XCTAssertEqual(mixed.blue, BlobColor.foodGreen.blue, accuracy: 0.01)
  }

  func testMixingIsProportional() {
    let mostlyGreen = mix(9, 0, 1)

    XCTAssertGreaterThan(mostlyGreen.green, mostlyGreen.red)
    XCTAssertLessThan(
      abs(mostlyGreen.green - BlobColor.foodGreen.green),
      0.25
    )
  }

  func testMixedGreenAndRedIsMuddierThanPureGreen() {
    XCTAssertLessThan(chroma(mix(4, 0, 4)), chroma(mix(8, 0, 0)))
  }

  func testScalingCountsPreservesTheColor() {
    let small = mix(1, 2, 1)
    let large = mix(4, 8, 4)

    XCTAssertEqual(small.red, large.red, accuracy: 0.0001)
    XCTAssertEqual(small.green, large.green, accuracy: 0.0001)
    XCTAssertEqual(small.blue, large.blue, accuracy: 0.0001)
  }

  func testRepresentativeMixRemainsNumericallyStable() {
    let mixed = mix(4, 2, 1)

    XCTAssertEqual(mixed.red, 0.6543721688640014, accuracy: 1e-12)
    XCTAssertEqual(mixed.green, 0.7598411653121486, accuracy: 1e-12)
    XCTAssertEqual(mixed.blue, 0.3777161751835682, accuracy: 1e-12)
  }

  func testEverySmallCombinationStaysInSRGBGamut() {
    for green in 0...4 {
      for yellow in 0...4 {
        for red in 0...4 where green + yellow + red > 0 {
          let mixed = mix(green, yellow, red)
          XCTAssertTrue((0...1).contains(mixed.red))
          XCTAssertTrue((0...1).contains(mixed.green))
          XCTAssertTrue((0...1).contains(mixed.blue))
        }
      }
    }
  }
}
