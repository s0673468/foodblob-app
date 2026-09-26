import CoreGraphics
import XCTest

@testable import FoodBlob

final class LivingBlobTests: XCTestCase {
  func testStartingHeroIsInvitingAndEarlyAdditionsStillGrowDistinctly() {
    // A 250-point stage contains a roughly 74%-diameter resting silhouette.
    // The empty blob should already invite a fingertip without sacrificing
    // the independently checked three-to-ten-food area contrast below.
    XCTAssertGreaterThanOrEqual(LivingBlobMetrics.heroGrowthScale(total: 0) * 250 * 0.74, 70)
    for total in 0..<3 {
      let before = LivingBlobMetrics.heroGrowthScale(total: total)
      let after = LivingBlobMetrics.heroGrowthScale(total: total + 1)
      XCTAssertGreaterThanOrEqual(after * after / (before * before), 1.25)
    }
  }

  func testHeldContactHasNoNarrowShoulderKinks() {
    let field = PuddleImpactField(unitContact: CGVector(dx: 1, dy: 0), depth: 1.2)
    let values = (0..<360).map { index -> CGFloat in
      let angle = CGFloat(index) * .pi / 180
      return field.radiusAdjustment(for: CGVector(dx: cos(angle), dy: sin(angle)))
    }
    for i in values.indices {
      XCTAssertLessThan(abs(values[(i + 1) % 360] - 2 * values[i] + values[(i + 359) % 360]), 0.005)
    }
    XCTAssertEqual(values.reduce(0, +) / 360, 0, accuracy: 0.001,
      "Compression should redistribute radius instead of shrinking the whole blob.")
  }

  func testGrowthMakesTenFoodsVisiblyLargerThanTwoOrThree() {
    func area(total: Int, hero: Bool) -> CGFloat {
      let radii = PuddleShape.restingRadii(counts: FoodCounts(green: total))
      let twice = radii.indices.reduce(CGFloat.zero) { result, i in
        result + radii[i] * radii[(i + 1) % radii.count] * sin(2 * .pi / CGFloat(radii.count))
      }
      let scale = hero ? LivingBlobMetrics.heroGrowthScale(total: total)
        : LivingBlobMetrics.growthScale(total: total)
      return twice * 0.5 * scale * scale
    }
    for hero in [false, true] {
      XCTAssertGreaterThanOrEqual(area(total: 10, hero: hero) / area(total: 3, hero: hero), 3)
      XCTAssertGreaterThanOrEqual(area(total: 10, hero: hero) / area(total: 2, hero: hero), 3.8)
    }
    XCTAssertEqual(LivingBlobMetrics.growthScale(total: -2), 0.34, accuracy: 0.0001)
    XCTAssertEqual(LivingBlobMetrics.heroGrowthScale(total: 0), 0.40, accuracy: 0.0001)
    for count in 0..<200 {
      XCTAssertGreaterThan(LivingBlobMetrics.growthScale(total: count + 1), LivingBlobMetrics.growthScale(total: count))
      XCTAssertGreaterThan(LivingBlobMetrics.heroGrowthScale(total: count + 1), LivingBlobMetrics.heroGrowthScale(total: count))
      XCTAssertLessThanOrEqual(LivingBlobMetrics.growthScale(total: count), 1.1806)
      XCTAssertLessThanOrEqual(LivingBlobMetrics.heroGrowthScale(total: count), 1.3744)
    }
  }

  func testEarlySilhouettesDifferAndSeededVariationsPreserveArea() {
    func area(_ r: [CGFloat]) -> CGFloat {
      r.indices.reduce(0) { $0 + r[$1] * r[($1 + 1) % 48] * sin(2 * .pi / 48) / 2 }
    }
    let target = CGFloat.pi * 37 * 37
    for total in 0...120 {
      let counts = FoodCounts(green: total / 2, yellow: total - total / 2)
      let radii = PuddleShape.restingRadii(counts: counts)
      XCTAssertEqual(area(radii), target, accuracy: 0.001)
      XCTAssertTrue(radii.allSatisfy { $0.isFinite && $0 >= 25 && $0 <= 46 })
      if total < 3 {
        let next = PuddleShape.restingRadii(counts: FoodCounts(green: total + 1))
        XCTAssertGreaterThan(zip(radii, next).map { abs($0 - $1) }.reduce(0, +) / 48, 1.5)
      }
    }
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    XCTAssertNotEqual(PuddleShape.restingRadii(counts: counts, tapSeed: 7),
      PuddleShape.restingRadii(counts: counts, tapSeed: 8))
  }

  func testSeededContourMatchesAndroidParityFixture() {
    // Same independent fixture in LivingBlobTest.kt; prevents platform drift.
    let expected: [CGFloat] = [33.5125807824, 40.8754926552, 40.6596391322, 40.2938821281, 33.2042397032, 34.0215370631, 39.6170165289, 33.0038664]
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    let radii = PuddleShape.restingRadii(counts: counts)
    for index in expected.indices {
      XCTAssertEqual(radii[index * 6], expected[index], accuracy: 0.000001)
    }
  }

  func testFriendlyContourHasBroadCurvesWithoutTightKinks() {
    for count in 0...120 {
      let radii = PuddleShape.restingRadii(counts: FoodCounts(green: count))
      for i in radii.indices {
        let bend = radii[(i + 1) % 48] - 2 * radii[i] + radii[(i + 47) % 48]
        XCTAssertLessThan(abs(bend), 0.8, "Sharp contour at count \(count), sample \(i)")
      }
    }
  }

  func testImpactFieldIndentsContactAndPushesOutItsShoulders() {
    let contact = PuddleGeometry.impactRadiusAdjustment(
      direction: CGVector(dx: 0, dy: 1),
      originX: 0.5,
      depth: 1
    )
    let leftShoulder = PuddleGeometry.impactRadiusAdjustment(
      direction: CGVector(dx: -0.64, dy: 0.77),
      originX: 0.5,
      depth: 1
    )
    let rightShoulder = PuddleGeometry.impactRadiusAdjustment(
      direction: CGVector(dx: 0.64, dy: 0.77),
      originX: 0.5,
      depth: 1
    )

    XCTAssertLessThan(contact, -5)
    XCTAssertLessThan(leftShoulder, 0, "The fingertip depression spreads broadly before bulging.")
    XCTAssertEqual(leftShoulder, rightShoulder, accuracy: 0.0001)
  }

  func testImpactFieldIsDirectionalAndDisappearsAtRest() {
    XCTAssertEqual(
      PuddleGeometry.impactRadiusAdjustment(
        direction: CGVector(dx: 0.6, dy: 0.8),
        originX: 0.25,
        depth: 0
      ),
      0,
      accuracy: 0.0001
    )

    let leftImpactOnLeftEdge = PuddleGeometry.impactRadiusAdjustment(
      direction: CGVector(dx: -0.6, dy: 0.8),
      originX: 0.25,
      depth: 1
    )
    let leftImpactOnRightEdge = PuddleGeometry.impactRadiusAdjustment(
      direction: CGVector(dx: 0.6, dy: 0.8),
      originX: 0.25,
      depth: 1
    )
    XCTAssertLessThan(leftImpactOnLeftEdge, leftImpactOnRightEdge)
  }

  func testImpactFieldClampsDepthAndOrigin() {
    let direction = CGVector(dx: -0.6, dy: 0.8)
    XCTAssertEqual(
      PuddleGeometry.impactRadiusAdjustment(
        direction: direction,
        originX: 0.25,
        depth: -1
      ),
      0,
      accuracy: 0.0001
    )
    XCTAssertEqual(
      PuddleGeometry.impactRadiusAdjustment(
        direction: direction,
        originX: 0.25,
        depth: 2
      ),
      PuddleGeometry.impactRadiusAdjustment(
        direction: direction,
        originX: 0.25,
        depth: 1
      ),
      accuracy: 0.0001
    )
    XCTAssertEqual(
      PuddleGeometry.impactRadiusAdjustment(
        direction: direction,
        originX: -1,
        depth: 1
      ),
      PuddleGeometry.impactRadiusAdjustment(
        direction: direction,
        originX: 0,
        depth: 1
      ),
      accuracy: 0.0001
    )
  }

  func testImpactedPuddlePathsStayFiniteAndInsideTheirGeometryBox() {
    let rect = CGRect(x: 0, y: 0, width: 320, height: 280)
    for total in [0, 1, 15, 18, 99] {
      let counts = FoodCounts(green: total)
      for origin in [CGFloat(0.25), 0.50, 0.75] {
        for depth in [CGFloat(0), 0.5, 1] {
          let bounds = PuddleShape(
            counts: counts,
            tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts),
            boost: depth * 0.35,
            impactOriginX: origin,
            impactDepth: depth
          ).path(in: rect).boundingRect

          XCTAssertFalse(bounds.isNull)
          XCTAssertFalse(bounds.isInfinite)
          XCTAssertGreaterThan(bounds.width, 0)
          XCTAssertGreaterThan(bounds.height, 0)
          XCTAssertGreaterThanOrEqual(bounds.minX, rect.minX - 0.5)
          XCTAssertGreaterThanOrEqual(bounds.minY, rect.minY - 0.5)
          XCTAssertLessThanOrEqual(bounds.maxX, rect.maxX + 0.5)
          XCTAssertLessThanOrEqual(bounds.maxY, rect.maxY + 0.5)
        }
      }
    }
  }

  func testPuddleMorphStartsAtThePreviousShapeAndEndsAtTheNewShape() {
    let rect = CGRect(x: 0, y: 0, width: 320, height: 280)
    let previous = FoodCounts(green: 1)
    let next = FoodCounts(green: 4, yellow: 2, red: 1)
    let previousBounds = PuddleShape(
      counts: previous,
      tapSeed: LivingBlobMetrics.derivedTapSeed(counts: previous)
    ).path(in: rect).boundingRect
    let nextBounds = PuddleShape(
      counts: next,
      tapSeed: LivingBlobMetrics.derivedTapSeed(counts: next)
    ).path(in: rect).boundingRect

    let morphStart = PuddleShape(
      counts: next,
      tapSeed: LivingBlobMetrics.derivedTapSeed(counts: next),
      morphFromCounts: previous,
      morphProgress: 0
    ).path(in: rect).boundingRect
    let morphEnd = PuddleShape(
      counts: next,
      tapSeed: LivingBlobMetrics.derivedTapSeed(counts: next),
      morphFromCounts: previous,
      morphProgress: 1
    ).path(in: rect).boundingRect

    XCTAssertEqual(morphStart.minX, previousBounds.minX, accuracy: 1e-6)
    XCTAssertEqual(morphStart.minY, previousBounds.minY, accuracy: 1e-6)
    XCTAssertEqual(morphStart.width, previousBounds.width, accuracy: 1e-6)
    XCTAssertEqual(morphStart.height, previousBounds.height, accuracy: 1e-6)
    XCTAssertEqual(morphEnd.minX, nextBounds.minX, accuracy: 1e-6)
    XCTAssertEqual(morphEnd.minY, nextBounds.minY, accuracy: 1e-6)
    XCTAssertEqual(morphEnd.width, nextBounds.width, accuracy: 1e-6)
    XCTAssertEqual(morphEnd.height, nextBounds.height, accuracy: 1e-6)
  }

  func testAdditionCarriesTheCategoryColorIntoADifferentFinalMix() throws {
    let previous = FoodCounts(green: 1)
    let next = FoodCounts(green: 1, red: 1)
    let mutation = try XCTUnwrap(FoodMutation.detect(from: previous, to: next))

    XCTAssertEqual(mutation, FoodMutation(color: .red, delta: 1))
    XCTAssertEqual(mutation.color.blobColor, .foodRed)
    XCTAssertNotEqual(mutation.color.blobColor, BlobColor.mixed(counts: next))
  }

  func testFriendlyGeometryIsDeterministicAndChangesWithFoodMix() {
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    let first = PuddleShape.restingRadii(counts: counts)
    XCTAssertEqual(first, PuddleShape.restingRadii(counts: counts))
    XCTAssertNotEqual(first, PuddleShape.restingRadii(counts: FoodCounts(green: 3, yellow: 3, red: 1)))
    XCTAssertTrue(first.allSatisfy { (25...46).contains($0) })
  }

  func testFriendlyPuddleStaysInsideStageAtRestAndDuringSquash() {
    let rect = CGRect(x: 0, y: 0, width: 320, height: 280)
    for total in 0...18 {
      let counts = FoodCounts(green: total)
      for boost: CGFloat in [0, 0.625, 1] {
        let bounds = PuddleShape(counts: counts, tapSeed: total, boost: boost)
          .path(in: rect).boundingRect
        XCTAssertTrue(rect.contains(bounds))
        XCTAssertGreaterThan(bounds.width, 190)
        XCTAssertGreaterThan(bounds.height, 160)
      }
    }
  }

  func testStreakCountsConsecutiveLoggedDaysEndingToday() {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let today = Date(timeIntervalSince1970: 1_800_000_000)
    let keys = (0..<4).map {
      FoodDateKey.string(
        for: calendar.date(byAdding: .day, value: -$0, to: today)!,
        calendar: calendar
      )
    }
    let records = [
      DayRecord(dateKey: keys[0], counts: FoodCounts(green: 1), updatedAt: today),
      DayRecord(dateKey: keys[1], counts: FoodCounts(yellow: 1), updatedAt: today),
      DayRecord(dateKey: keys[2], counts: FoodCounts(red: 1), updatedAt: today),
      DayRecord(dateKey: keys[3], counts: FoodCounts(), updatedAt: today),
    ]

    XCTAssertEqual(
      FoodStreak.consecutiveDays(endingAt: today, records: records, calendar: calendar), 3)
  }

  func testStreakIsHiddenWhenTodayIsEmpty() {
    XCTAssertEqual(
      FoodStreak.consecutiveDays(
        endingAt: Date(timeIntervalSince1970: 1_800_000_000),
        records: [],
        calendar: Calendar(identifier: .gregorian)
      ),
      0
    )
  }

  func testTransientPaintRunsOnlyForFullMotionInteractiveBlobs() {
    XCTAssertTrue(
      BlobAnimationPolicy.showsTransientPaint(
        reduceMotion: false,
        allowsIdleMotion: true,
        stayedInSameScope: true
      )
    )
    XCTAssertFalse(
      BlobAnimationPolicy.showsTransientPaint(
        reduceMotion: true,
        allowsIdleMotion: true,
        stayedInSameScope: true
      )
    )
    XCTAssertFalse(
      BlobAnimationPolicy.showsTransientPaint(
        reduceMotion: false,
        allowsIdleMotion: false,
        stayedInSameScope: true
      )
    )
    XCTAssertFalse(
      BlobAnimationPolicy.showsTransientPaint(
        reduceMotion: false,
        allowsIdleMotion: true,
        stayedInSameScope: false
      )
    )
  }

  func testFoodControlPressMotionStopsUnderReduceMotion() {
    XCTAssertEqual(
      LiquidControlMotion.pose(kind: .row, pressed: true, reduceMotion: true).x,
      1
    )
    XCTAssertEqual(
      LiquidControlMotion.pose(kind: .row, pressed: true, reduceMotion: true).lift,
      0
    )
    XCTAssertLessThan(
      LiquidControlMotion.pose(kind: .row, pressed: true, reduceMotion: false).x,
      1
    )
    XCTAssertGreaterThan(
      LiquidControlMotion.pose(kind: .row, pressed: true, reduceMotion: false).lift,
      0
    )
  }

  func testWidgetCountTransitionsStopUnderReduceMotion() {
    XCTAssertTrue(
      FoodWidgetMotionPolicy.allowsAnimatedTransitions(reduceMotion: false)
    )
    XCTAssertFalse(
      FoodWidgetMotionPolicy.allowsAnimatedTransitions(reduceMotion: true)
    )
  }
}
