import Foundation
import XCTest

@testable import FoodBlob

final class BlobMaterialPreferenceTests: XCTestCase {
  func testExistingInstallWithoutPreferenceStartsWithOpaquePaint() throws {
    let suite = "FoodBlobMaterialTests.\(UUID().uuidString)"
    let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    XCTAssertEqual(BlobMaterial.read(defaults: defaults), 0)
    XCTAssertNil(defaults.object(forKey: BlobMaterial.preferenceKey),
      "Reading the default must not manufacture a persisted setting.")
  }

  func testPreferenceRoundTripsIndependentlyFromFoodRecords() throws {
    let suite = "FoodBlobMaterialTests.\(UUID().uuidString)"
    let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
    defer { defaults.removePersistentDomain(forName: suite) }
    defaults.set("untouched", forKey: "syntheticFoodRecordSentinel")
    for value in [0.0, 0.5, 1.0] {
      defaults.set(value, forKey: BlobMaterial.preferenceKey)
      XCTAssertEqual(BlobMaterial.read(defaults: defaults), value)
      XCTAssertEqual(defaults.string(forKey: "syntheticFoodRecordSentinel"), "untouched")
    }
  }

  func testInvalidAppearanceCannotReachShaderUniforms() {
    XCTAssertEqual(BlobMaterial.clamped(-0.2), 0)
    XCTAssertEqual(BlobMaterial.clamped(1.2), 1)
    XCTAssertEqual(BlobMaterial.clamped(.nan), 0)
    XCTAssertEqual(BlobMaterial.clamped(.infinity), 0)
    XCTAssertEqual(BlobMaterial.read(defaults: nil), 0)
  }
}
