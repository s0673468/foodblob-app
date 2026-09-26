import XCTest
import SwiftUI
import Metal
@testable import FoodBlob

final class JellySurfaceTests: XCTestCase {
  func testDefaultPaintKeepsDensePigmentIndependentOfTheWorldBehindIt() {
    let paint = JellySurfaceGeometry.settledCoreColor(color: .foodRed, skin: .skyMeadow)
    XCTAssertEqual(paint, JellySurfaceGeometry.settledCoreColor(
      color: .foodRed, skin: .skyMeadow, translucency: 0))
    let jelly = JellySurfaceGeometry.settledCoreColor(color: .foodRed, skin: .skyMeadow, translucency: 1)
    XCTAssertGreaterThan(paint.red - paint.blue, jelly.red - jelly.blue)
    let nightPaint = JellySurfaceGeometry.settledCoreColor(color: .foodRed, skin: .shrine, translucency: 0)
    let nightJelly = JellySurfaceGeometry.settledCoreColor(color: .foodRed, skin: .shrine, translucency: 1)
    XCTAssertLessThan(abs(paint.red - nightPaint.red), abs(jelly.red - nightJelly.red))
    for amount in [0.0, 0.5, 1.0] {
      for skin in SkinID.allCases {
        for pigment in [BlobColor.foodGreen, .foodYellow, .foodRed] {
          let surface = JellySurfaceGeometry.settledCoreColor(color: pigment, skin: skin, translucency: amount)
          let luma = JellySurfaceGeometry.linearLuminance(surface)
          let light = JellySurfaceGeometry.labelUsesLightInk(color: pigment, skin: skin, translucency: amount)
          XCTAssertGreaterThanOrEqual(light ? 1.05 / (luma + 0.05) : (luma + 0.05) / 0.05, 4.5)
        }
      }
    }
  }

  func testLightRespondsToTouchAndReleaseThenReturnsToRest() {
    let resting = JellySurfaceGeometry.lightShift(at: 0, allowsMotion: false,
      dragPoint: nil, dragDepth: 0, contacts: [])
    XCTAssertEqual(resting, .zero)
    let held = JellySurfaceGeometry.lightShift(at: 0, allowsMotion: true,
      dragPoint: CGPoint(x: 0.9, y: 0.2), dragDepth: 1, contacts: [])
    XCTAssertGreaterThan(held.width, 0)
    XCTAssertLessThan(held.height, 0)
    let release = BlobContact(started: 0, point: CGPoint(x: 0.9, y: 0.2), initialDepth: 1)
    let released = JellySurfaceGeometry.lightShift(at: 0, allowsMotion: true,
      dragPoint: nil, dragDepth: 0, contacts: [release])
    XCTAssertEqual(held.width, released.width, accuracy: 0.00001)
    XCTAssertEqual(held.height, released.height, accuracy: 0.00001)
    XCTAssertEqual(JellySurfaceGeometry.lightShift(at: 2, allowsMotion: true,
      dragPoint: nil, dragDepth: 0, contacts: [release]), .zero)
    XCTAssertEqual(JellySurfaceGeometry.lightShift(at: 0, allowsMotion: false,
      dragPoint: CGPoint(x: 1, y: 0), dragDepth: 1, contacts: [release]), .zero)
  }

  func testLightShiftIsBoundedDuringOverlappingImpactsAndWaitsForArrival() {
    let contacts = (0..<30).map { _ in
      BlobContact(started: 1, point: CGPoint(x: 1, y: 0), strength: 2, initialDepth: 2)
    }
    XCTAssertEqual(JellySurfaceGeometry.lightShift(at: 0.9, allowsMotion: true,
      dragPoint: nil, dragDepth: 0, contacts: contacts), .zero)
    let shift = JellySurfaceGeometry.lightShift(at: 1.1, allowsMotion: true,
      dragPoint: nil, dragDepth: 0, contacts: contacts)
    XCTAssertLessThanOrEqual(abs(shift.width), 0.16)
    XCTAssertLessThanOrEqual(abs(shift.height), 0.16)
  }

  func testCachedPoseUniformsFollowMorphAndAvoidStaleTargetGeometry() {
    let source = BlobPose(counts: FoodCounts(green: 2), hero: true)
    let target = BlobPose(counts: FoodCounts(red: 10), hero: true)
    for progress in [CGFloat(0), 0.25, 0.5, 0.75, 1] {
      let pose = source.blending(to: target, progress: progress)
      XCTAssertEqual(pose.surfaceRadii, JellySurfaceGeometry.radii(pose.radii, fields: []))
    }
    XCTAssertNotEqual(source.surfaceRadii, target.surfaceRadii)
  }

  func testAppBundleContainsTheNativeJellyShader() throws {
    guard let device = MTLCreateSystemDefaultDevice() else {
      throw XCTSkip("This test requires a Metal-capable simulator or device.")
    }
    let library = try device.makeDefaultLibrary(bundle: .main)
    XCTAssertTrue(library.functionNames.contains("jellySurface"),
      "The shipping app must bundle its compiled jelly surface, not just Swift uniforms.")
  }

  func testSettledLabelAdaptsToTransmittedWorldAndBrightPigments() {
    XCTAssertTrue(JellySurfaceGeometry.labelUsesLightInk(color: .foodRed, skin: .shrine, translucency: 1))
    XCTAssertFalse(JellySurfaceGeometry.labelUsesLightInk(color: .foodGreen, skin: .shrine, translucency: 1))
    for skin in SkinID.allCases {
      XCTAssertFalse(JellySurfaceGeometry.labelUsesLightInk(color: .foodYellow, skin: skin, translucency: 1))
    }
    XCTAssertFalse(JellySurfaceGeometry.labelUsesLightInk(color: .foodGreen, skin: .skyMeadow, translucency: 1))
    XCTAssertFalse(JellySurfaceGeometry.labelUsesLightInk(color: .foodRed, skin: .skyMeadow, translucency: 1))

    // The pigment-lit Shrine belly is brighter while remaining translucent.
    // Compact labels must adapt to dark ink when the optical core crosses it.
    let greenCore = JellySurfaceGeometry.settledCoreColor(color: .foodGreen, skin: .shrine, translucency: 1)
    let luminance = JellySurfaceGeometry.linearLuminance(greenCore)
    XCTAssertEqual(luminance, 0.208904971, accuracy: 0.00001)
    XCTAssertLessThan(1.05 / (luminance + 0.05), 4.5)
    XCTAssertGreaterThanOrEqual((luminance + 0.05) / 0.05, 4.5)
  }

  func testSettledLabelContrastUsesLinearSRGBAndCoversRepresentativeMixes() {
    XCTAssertEqual(JellySurfaceGeometry.linearLuminance(BlobColor(red: 0.5, green: 0.5, blue: 0.5)),
      0.21404114, accuracy: 0.000001)
    let mixes = [FoodCounts(green: 8), FoodCounts(yellow: 8), FoodCounts(red: 8),
      FoodCounts(green: 1, red: 9), FoodCounts(green: 3, yellow: 2, red: 1),
      FoodCounts(green: 1, yellow: 1, red: 1)]
    for skin in SkinID.allCases {
      for counts in mixes {
        let pigment = BlobColor.mixed(counts: counts)!
        let surface = JellySurfaceGeometry.settledCoreColor(color: pigment, skin: skin)
        let luminance = JellySurfaceGeometry.linearLuminance(surface)
        let light = JellySurfaceGeometry.labelUsesLightInk(color: pigment, skin: skin)
        let contrast = light ? 1.05 / (luminance + 0.05) : (luminance + 0.05) / 0.05
        XCTAssertGreaterThanOrEqual(contrast, 4.5, "\(skin) \(counts)")
      }
    }
  }

  func testSurfaceContourBalancesStretchWithGentleOppositeCompression() {
    let counts = FoodCounts(green: 3)
    let rest = PuddleShape.restingRadii(counts: counts)
    let field = PuddleGeometry.contactField(point: CGPoint(x: 1, y: 0.5), depth: -1)!
    let stretched = JellySurfaceGeometry.radii(rest, fields: [field])
    XCTAssertGreaterThan(stretched[0], Float(rest[0] / 100))
    XCTAssertLessThan(stretched[24], Float(rest[24] / 100))
    XCTAssertEqual(stretched.count, 48)
  }

  func testSurfaceEventsWaitForArrivalOverlapAndExpire() {
    let first = BlobContact(started: 1, point: CGPoint(x: 0.2, y: 0.8), color: .green)
    let second = BlobContact(started: 1.2, point: CGPoint(x: 0.7, y: 0.9), color: .red)
    XCTAssertTrue(JellySurfaceGeometry.events([first, second], at: 0.9).isEmpty)
    XCTAssertEqual(JellySurfaceGeometry.events([first, second], at: 1.1).count, 9)
    XCTAssertEqual(JellySurfaceGeometry.events([first, second], at: 1.3).count, 18)
    XCTAssertTrue(JellySurfaceGeometry.events([first, second], at: 3).isEmpty)
  }

  func testArrivalRippleCrossesTheBodyBeforeItSettles() {
    let contact = BlobContact(started: 1, point: CGPoint(x: 0.2, y: 0.8), color: .green)
    XCTAssertFalse(JellySurfaceGeometry.events([contact], at: 2.02).isEmpty,
      "The surface ripple should outlive the initial body squash.")
    XCTAssertTrue(JellySurfaceGeometry.events([contact], at: 2.3).isEmpty)
  }

  func testRemovalClearsPaintIncludingAlreadyArrivedOfferings() {
    let arrived = BlobContact(started: 1, point: CGPoint(x: 0.2, y: 0.8), color: .red)
    let pending = BlobContact(started: 2, point: CGPoint(x: 0.2, y: 0.8), color: .green)
    let touch = BlobContact(started: 1, point: CGPoint(x: 0.5, y: 0.2))
    let retained = JellySurfaceGeometry.contactsAfterRemoval([arrived, pending, touch], at: 1.1)
    XCTAssertEqual(retained.map(\.id), [touch.id],
      "A removed offering must not repaint the authoritative colour while settling.")
  }

  func testRapidFeedsHaveBoundedGPUWorkWithoutResettingNewestContact() {
    let contacts = (0..<40).map { BlobContact(started: Double($0) * 0.01,
      point: CGPoint(x: 0.5, y: 0.8), color: .yellow) }
    let values = JellySurfaceGeometry.events(contacts, at: 0.4)
    XCTAssertEqual(values.count, 8 * 9)
    XCTAssertEqual(values[values.count - 7], 0.01, accuracy: 0.0001)
    XCTAssertTrue(values.allSatisfy(\.isFinite))
  }
}
