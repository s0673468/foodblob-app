import SwiftUI
import XCTest

@testable import FoodBlob

final class WidgetGeometryTests: XCTestCase {
  func testSmallWidgetKeepsControlsInsideCompactHostHeight() {
    XCTAssertLessThanOrEqual(FoodWidgetGeometry.smallRequiredHeight, 158)
    XCTAssertGreaterThanOrEqual(FoodWidgetGeometry.smallControlHeight, 44)
  }

  func testEverySupportedWidgetSizeKeepsVisibleControlsAndTouchTargetsAligned() {
    let fixtures: [(CGSize, FoodWidgetPresentation)] = [
      (CGSize(width: 141, height: 141), .counter),
      (CGSize(width: 158, height: 158), .counter),
      (CGSize(width: 182, height: 182), .counter),
      (CGSize(width: 292, height: 141), .combined),
      (CGSize(width: 338, height: 158), .combined),
      (CGSize(width: 364, height: 170), .combined),
      (CGSize(width: 425, height: 202), .combined),
    ]
    for (size, presentation) in fixtures {
      let geometry = FoodWidgetGeometry.resolve(size: size, presentation: presentation)
      let canvas = CGRect(origin: .zero, size: size)
      XCTAssertTrue(canvas.contains(geometry.blob))
      XCTAssertEqual(geometry.controls.count, presentation == .counter ? 3 : 6)
      for (index, control) in geometry.controls.enumerated() {
        XCTAssertTrue(canvas.contains(control.rect), "\(size): \(control.rect)")
        XCTAssertGreaterThanOrEqual(control.rect.width, 44)
        XCTAssertGreaterThanOrEqual(control.rect.height, 44)
        XCTAssertFalse(geometry.blob.intersects(control.rect))
        for other in geometry.controls.dropFirst(index + 1) {
          XCTAssertFalse(control.rect.intersects(other.rect))
        }
      }
    }
  }

  func testMediumMakesRoomForInvitingRowsOnCompactPhones() {
    let geometry = FoodWidgetGeometry.resolve(
      size: CGSize(width: 292, height: 141), presentation: .combined
    )
    for control in geometry.controls where control.action == .increase {
      XCTAssertGreaterThanOrEqual(control.rect.width, 96)
    }
    XCTAssertEqual(geometry.controls.map(\.color), [.green, .green, .yellow, .yellow, .red, .red])
    XCTAssertEqual(
      geometry.controls.map(\.action),
      [.increase, .decrease, .increase, .decrease, .increase, .decrease])
  }
}
