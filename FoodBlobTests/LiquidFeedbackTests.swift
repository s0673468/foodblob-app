import SwiftUI
import UIKit
import XCTest
@testable import FoodBlob

final class LiquidFeedbackTests: XCTestCase {
  func testControlTintRemainsReadableOnEachWorldsCardsAndSky() {
    for skin in SkinID.allCases {
      let palette = skin.design.palette
      let tint = luminance(palette.controlAccent)
      for background in [palette.raised, palette.background] {
        let surface = luminance(background)
        let contrast = (max(tint, surface) + 0.05) / (min(tint, surface) + 0.05)
        XCTAssertGreaterThanOrEqual(contrast, 4.5,
          "Small settings symbols and selected-state glyphs need readable ink in \(skin).")
      }
    }
  }

  private func luminance(_ color: Color) -> Double {
    var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
    XCTAssertTrue(UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a))
    return JellySurfaceGeometry.linearLuminance(
      BlobColor(red: Double(r), green: Double(g), blue: Double(b)))
  }

  func testPressKeepsSmallControlsInsideTheirReservedHitArea() {
    for kind in LiquidControlKind.allCases {
      let held = LiquidControlMotion.pose(kind: kind, pressed: true, reduceMotion: false)
      XCTAssertLessThanOrEqual(held.x, 1)
      XCTAssertLessThan(held.y, held.x)
      XCTAssertGreaterThanOrEqual(held.y, 0.94)
      XCTAssertLessThanOrEqual(held.lift, 2)
      XCTAssertEqual(LiquidControlMotion.pose(kind: kind, pressed: false, reduceMotion: false), .rest)
    }
  }

  func testReduceMotionLeavesControlsStationaryOnPressAndRelease() {
    for kind in LiquidControlKind.allCases {
      XCTAssertEqual(LiquidControlMotion.pose(kind: kind, pressed: true, reduceMotion: true), .rest)
    }
    XCTAssertFalse(LiquidControlMotion.releasesLight(wasPressed: true, isPressed: false,
      enabled: true, reduceMotion: true))
    XCTAssertFalse(LiquidControlMotion.releasesLight(wasPressed: true, isPressed: false,
      enabled: false, reduceMotion: false))
    XCTAssertFalse(LiquidControlMotion.releasesLight(wasPressed: false, isPressed: false,
      enabled: true, reduceMotion: false))
    XCTAssertTrue(LiquidControlMotion.releasesLight(wasPressed: true, isPressed: false,
      enabled: true, reduceMotion: false))
  }

  func testFlyingDropletStretchesWithoutInflatingAndLandsAtActualContact() {
    let flight = BlobFlight(color: .red, source: CGPoint(x: 40, y: 500),
      target: CGPoint(x: 160, y: 250), contact: CGPoint(x: 0.3, y: 0.8), started: 10)
    for t in stride(from: 0.0, through: 1, by: 0.05) {
      let scale = BlobFlight.dropletScale(progress: t)
      XCTAssertEqual(scale.width * scale.height, 1, accuracy: 0.00001)
      XCTAssertGreaterThanOrEqual(scale.width, 0.72)
    }
    XCTAssertEqual(BlobFlight.dropletScale(progress: 0), CGSize(width: 1, height: 1))
    XCTAssertEqual(BlobFlight.dropletScale(progress: 1).width, 1, accuracy: 0.00001)
    XCTAssertNil(flight.landingProgress(at: flight.arrival - 0.01))
    XCTAssertEqual(flight.landingProgress(at: flight.arrival), 0)
    XCTAssertNil(flight.landingProgress(at: flight.arrival + BlobFlight.landingDuration + 0.001))
    XCTAssertEqual(flight.position(at: flight.arrival), flight.target)
  }
}
