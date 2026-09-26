import SwiftUI
import XCTest

@testable import FoodBlob

final class ConnectedBlobMotionTests: XCTestCase {
  func testPokeMapsTheVisibleSeedAndGrownBodyToTheSameSurfacePoint() {
    let size = CGSize(width: 260, height: 250)
    for scale in [CGFloat(0.4), 0.66, 1.17] {
      let location = CGPoint(x: size.width / 2 + (size.width - 16) * scale * 0.3,
        y: size.height / 2 - 7)
      let point = BlobPlayGeometry.surfacePoint(location: location, size: size, scale: scale, lift: 7)
      XCTAssertEqual(point.x, 0.8, accuracy: 0.00001)
      XCTAssertEqual(point.y, 0.5, accuracy: 0.00001)
    }
  }

  func testPokePressureIsImmediateButSmoothAndReleaseKeepsBodyContinuity() {
    XCTAssertGreaterThanOrEqual(BlobPressTimeline.pressure(outward: 0), 0.7)
    var press = BlobPressTimeline()
    press.retarget(BlobPressTimeline.pressure(outward: 0), at: 1)
    XCTAssertEqual(press.depth(at: 1), 0)
    XCTAssertGreaterThan(press.depth(at: 1.06), 0.5)
    let held = press.depth(at: 1.2)
    let release = BlobContact(started: 1.2, point: CGPoint(x: 0.5, y: 0.5),
      strength: 0.62, initialDepth: held, bodyWeight: BlobPlayGeometry.bodyWeight)
    XCTAssertEqual(BlobContact.squash([release], at: 1.2), held * BlobPlayGeometry.bodyWeight,
      accuracy: 0.00001)
    XCTAssertLessThan(release.wave(at: 1.5), 0)
    XCTAssertEqual(release.wave(at: 2), 0)
  }

  func testSeedPokeHitAreaIsForgivingWithoutCatchingDistantAir() {
    let shape = PuddleShape(counts: FoodCounts(), tapSeed: 0)
    let area = BlobPlayHitShape(shape: shape, scale: 0.4, lift: 0)
      .path(in: CGRect(x: 0, y: 0, width: 260, height: 250))
    XCTAssertTrue(area.contains(CGPoint(x: 130, y: 125)))
    XCTAssertFalse(area.contains(CGPoint(x: 30, y: 30)))
    XCTAssertFalse(area.contains(CGPoint(x: 130, y: 15)))
  }

  func testFeedDropRequiresUpwardTravelInsideActualBlobAndSameDay() {
    let body = CGRect(x: 100, y: 100, width: 200, height: 180)
    XCTAssertTrue(BlobFeedDropPolicy.accepts(point: CGPoint(x: 200, y: 190),
      translation: CGSize(width: 0, height: -100), blob: body, sameDay: true))
    XCTAssertFalse(BlobFeedDropPolicy.accepts(point: CGPoint(x: 102, y: 102),
      translation: CGSize(width: 0, height: -100), blob: body, sameDay: true))
    XCTAssertFalse(BlobFeedDropPolicy.accepts(point: CGPoint(x: 200, y: 190),
      translation: CGSize(width: 80, height: -8), blob: body, sameDay: true))
    XCTAssertFalse(BlobFeedDropPolicy.accepts(point: CGPoint(x: 200, y: 190),
      translation: CGSize(width: 0, height: -100), blob: body, sameDay: false))
  }

  func testPersonalityGetsHeavierAndFiniteGreetingSettles() {
    XCTAssertGreaterThan(BlobPersonality.settlingTime(total: 20), BlobPersonality.settlingTime(total: 1))
    XCTAssertLessThan(BlobPersonality.idleAmplitude(total: 20), BlobPersonality.idleAmplitude(total: 1))
    XCTAssertEqual(BlobPersonality.greeting(age: -1), 0)
    XCTAssertEqual(BlobPersonality.greeting(age: 2), 0)
    XCTAssertGreaterThan(BlobPersonality.greeting(age: 0.25), 0)
  }

  func testPressureBeginsContinuouslyAndTracksRetargets() {
    var press = BlobPressTimeline()
    press.retarget(0.45, at: 1)
    XCTAssertEqual(press.depth(at: 1), 0, accuracy: 0.000001)
    XCTAssertGreaterThan(press.depth(at: 1.045), 0.25)
    let held = press.depth(at: 1.08)
    press.retarget(-0.5, at: 1.08)
    XCTAssertEqual(press.depth(at: 1.08), held, accuracy: 0.000001)
    XCTAssertLessThan(press.depth(at: 1.25), 0)
    for distance in stride(from: -1000.0, through: 1000, by: 10) {
      let pressure = BlobPressTimeline.pressure(outward: distance)
      XCTAssertLessThanOrEqual(pressure, 1.4)
      XCTAssertGreaterThanOrEqual(pressure, -0.5)
    }
  }

  func testCentralTouchDoesNotInventAnArbitraryEdgeDent() throws {
    let point = CGPoint(x: 0.5001, y: 0.5)
    let field = try XCTUnwrap(PuddleGeometry.contactField(point: point, depth: 1))
    XCTAssertLessThan(abs(field.radiusAdjustment(for: CGVector(dx: 1, dy: 0))), 0.001)
  }

  func testFlightStartsAtMeasuredTileAndEndsAtMeasuredContact() {
    let flight = BlobFlight(color: .green, source: CGPoint(x: 67, y: 581),
      target: CGPoint(x: 164, y: 303), contact: CGPoint(x: 0.4, y: 0.87), started: 10)
    XCTAssertEqual(flight.position(at: 10), flight.source)
    XCTAssertEqual(flight.position(at: 10.2), flight.target)
    XCTAssertEqual(flight.position(at: 12), flight.target)
    XCTAssertNotEqual(flight.position(at: 10.1).x, (67 + 164) / 2)
    XCTAssertGreaterThan(flight.position(at: 10.19).y, flight.target.y,
      "A droplet must approach the lower contact from outside the blob.")
  }

  func testSoftContactHoldsItsFirstCompressionThenReboundsOnce() {
    let contact = BlobContact(started: 1, point: CGPoint(x: 0.5, y: 0.9))
    XCTAssertGreaterThan(contact.wave(at: 1.09), 0.55,
      "A broad first compression should not rush through its peak.")
    XCTAssertLessThan(contact.wave(at: 1.24), 0,
      "The body should still be in its first soft rebound, not vibrating again.")
    XCTAssertEqual(contact.wave(at: 1.5), 0)
  }

  func testAddingAnImpulseDoesNotResetExistingMotion() {
    let first = BlobContact(started: 1, point: CGPoint(x: 0.3, y: 0.9))
    let second = BlobContact(started: 1.1, point: CGPoint(x: 0.7, y: 0.9))
    XCTAssertEqual(BlobContact.squash([first, second], at: 1.1),
      BlobContact.squash([first], at: 1.1), accuracy: 0.00001)
    XCTAssertNotEqual(BlobContact.squash([first, second], at: 1.15),
      BlobContact.squash([second], at: 1.15))
    XCTAssertEqual(BlobContact.squash([first, second], at: 2), 0)
  }

  func testReleasedStretchStartsAtItsCurrentDeformationAndSettles() {
    let release = BlobContact(started: 1, point: CGPoint(x: 1, y: 0.5),
      strength: 0, initialDepth: -1.2, movesBody: false)
    XCTAssertEqual(release.wave(at: 1), -1.2, accuracy: 0.00001)
    XCTAssertEqual(release.wave(at: 1.5), 0)
    XCTAssertEqual(BlobContact.squash([release], at: 1), 0,
      "Releasing a local edge stretch must not jump the whole body's scale.")
  }

  func testOverlappingMixesContinueFromTheVisibleColourAndShape() {
    var motion = BlobMixTimeline(counts: FoodCounts(green: 1), hero: true)
    motion.retarget(FoodCounts(green: 1, yellow: 1), at: 1.2)
    let beforeSecond = motion.pose(at: 1.3)
    motion.retarget(FoodCounts(green: 1, yellow: 1, red: 1), at: 1.3)
    XCTAssertEqual(motion.pose(at: 1.3), beforeSecond)
    XCTAssertEqual(motion.pose(at: 2).color,
      BlobColor.mixed(counts: FoodCounts(green: 1, yellow: 1, red: 1)))
    XCTAssertEqual(motion.pose(at: 2).radii,
      PuddleShape.restingRadii(counts: FoodCounts(green: 1, yellow: 1, red: 1)))
  }

  func testShapeTransformationContinuesUnderTheRippleThenSettlesExactly() {
    let target = FoodCounts(green: 8, yellow: 5, red: 2)
    var motion = BlobMixTimeline(counts: FoodCounts(green: 2), hero: true)
    motion.retarget(target, at: 1)
    XCTAssertNotEqual(motion.pose(at: 1.52), BlobPose(counts: target, hero: true))
    XCTAssertEqual(motion.pose(at: 1.8), BlobPose(counts: target, hero: true))
  }

  func testPendingFlightDoesNotPrematurelyChangeMixAndResetDropsIt() {
    var motion = BlobMixTimeline(counts: FoodCounts(green: 1), hero: true)
    let initial = motion.pose(at: 1)
    motion.retarget(FoodCounts(green: 2), at: 1.2)
    XCTAssertEqual(motion.pose(at: 1.1), initial)
    motion = BlobMixTimeline(counts: FoodCounts(red: 2), hero: true)
    XCTAssertEqual(motion.pose(at: 5).color, BlobColor.mixed(counts: FoodCounts(red: 2)))
  }

  func testRemovalBeforeArrivalCannotResurrectThePendingAddedColour() {
    var motion = BlobMixTimeline(counts: FoodCounts(green: 1), hero: true)
    motion.retarget(FoodCounts(green: 1, red: 1), at: 1.2)
    motion.retarget(FoodCounts(green: 1), at: 1.1)
    XCTAssertEqual(motion.pose(at: 1.4).color, BlobColor.mixed(counts: FoodCounts(green: 1)))
    XCTAssertEqual(motion.pose(at: 2).radii, PuddleShape.restingRadii(counts: FoodCounts(green: 1)))
  }

  func testContactFieldCanPokeAnyEdgeAndStretchOutward() throws {
    let top = try XCTUnwrap(PuddleGeometry.contactField(point: CGPoint(x: 0.5, y: 0), depth: 1))
    XCTAssertLessThan(top.radiusAdjustment(for: CGVector(dx: 0, dy: -1)), 0)
    XCTAssertGreaterThan(top.radiusAdjustment(for: CGVector(dx: 0, dy: 1)), 0,
      "A soft press gently displaces the opposite side.")
    let stretch = try XCTUnwrap(PuddleGeometry.contactField(point: CGPoint(x: 1, y: 0.5), depth: -1))
    XCTAssertGreaterThan(stretch.radiusAdjustment(for: CGVector(dx: 1, dy: 0)), 0)
  }
}
