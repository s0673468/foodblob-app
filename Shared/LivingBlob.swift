import CoreGraphics
import SwiftUI

enum LivingBlobMetrics {
  static let maximumGrowthScale: CGFloat = 1.1806
  static let maximumHeroGrowthScale: CGFloat = 1.3744
  // The later rational tail keeps growing past 10 without expanding the
  // reserved stage. Early milestones keep every first addition noticeable.
  private static func scale(total: Int, anchors: [CGFloat], limit: CGFloat) -> CGFloat {
    let count = max(total, 0)
    if count <= 3 { return anchors[count] }
    if count <= 10 { return anchors[3] + (anchors[4] - anchors[3]) * CGFloat(count - 3) / 7 }
    let later = CGFloat(count - 10)
    return anchors[4] + (limit - anchors[4]) * later / (later + 18)
  }

  static func growthScale(total: Int) -> CGFloat {
    scale(total: total, anchors: [0.34, 0.40, 0.50, 0.55, 1.04], limit: maximumGrowthScale)
  }

  static func heroGrowthScale(total: Int) -> CGFloat {
    // The seed is already large enough to play with. A full day still has
    // over three times the visible area of the three-food silhouette.
    scale(total: total, anchors: [0.40, 0.48, 0.56, 0.66, 1.17], limit: maximumHeroGrowthScale)
  }

  static func derivedTapSeed(counts: FoodCounts) -> Int {
    counts.total
  }
}

struct PuddleImpactField: Equatable, Sendable {
  let unitContact: CGVector
  let depth: CGFloat

  func radiusAdjustment(for direction: CGVector) -> CGFloat {
    let alignment = max(
      -1,
      min(
        1,
        direction.dx * unitContact.dx + direction.dy * unitContact.dy
      )
    )
    // Two low-frequency modes spread a fingertip press across the body.
    // Both have zero angular mean and continuous derivatives everywhere;
    // the old triangular shoulders pinched at their peaks.
    return depth * (-3.8 * alignment - 1.4 * (2 * alignment * alignment - 1))
  }
}

enum PuddleGeometry {
  /// A broad compression with gentle displacement around the whole body.
  /// Values are percentages of the shape's radial size.
  static func impactRadiusAdjustment(
    direction: CGVector,
    originX: CGFloat,
    depth: CGFloat
  ) -> CGFloat {
    let length = max(hypot(direction.dx, direction.dy), 0.0001)
    let unitDirection = CGVector(
      dx: direction.dx / length,
      dy: direction.dy / length
    )
    return impactField(originX: originX, depth: depth)?
      .radiusAdjustment(for: unitDirection) ?? 0
  }

  static func impactField(originX: CGFloat, depth: CGFloat) -> PuddleImpactField? {
    let safeDepth = depth.clamped(to: 0...1)
    guard safeDepth > 0 else { return nil }

    let safeOriginX = originX.clamped(to: 0...1)
    let contact = CGVector(dx: (safeOriginX - 0.5) * 2, dy: 0.78)
    let contactLength = max(hypot(contact.dx, contact.dy), 0.0001)
    let unitContact = CGVector(
      dx: contact.dx / contactLength,
      dy: contact.dy / contactLength
    )
    return PuddleImpactField(unitContact: unitContact, depth: safeDepth)
  }

  /// App-only play can touch any edge. A negative depth pulls that edge outward.
  static func contactField(point: CGPoint, depth: CGFloat) -> PuddleImpactField? {
    guard abs(depth) > 0.0001 else { return nil }
    let vector = CGVector(dx: (point.x - 0.5) * 2, dy: (point.y - 0.5) * 2)
    let length = max(hypot(vector.dx, vector.dy), 0.0001)
    let influence = min(length / 0.32, 1)
    return PuddleImpactField(
      unitContact: CGVector(dx: vector.dx / length, dy: vector.dy / length),
      depth: depth.clamped(to: -1.8...1.8) * influence * influence * (3 - 2 * influence))
  }

  static func softenedAdjustment(_ value: CGFloat) -> CGFloat {
    12 * tanh(value / 12)
  }
}

struct PuddleShape: Shape {
  private static let sampleDirections: [CGVector] = (0..<48).map { index in
    let angle = CGFloat(index) / 48 * 2 * .pi
    return CGVector(dx: cos(angle), dy: sin(angle))
  }

  let counts: FoodCounts
  let tapSeed: Int
  var boost: CGFloat = 0
  var impactOriginX: CGFloat = 0.5
  var impactDepth: CGFloat = 0
  var morphFromCounts: FoodCounts? = nil
  var morphProgress: CGFloat = 1
  // App presentation can interpolate exact contour samples without changing
  // the count-derived resting geometry used by widgets and History.
  var presentationRadii: [CGFloat]? = nil
  var contacts: [PuddleImpactField] = []

  static func restingRadii(counts: FoodCounts, tapSeed: Int? = nil) -> [CGFloat] {
    let field = restingField(counts: counts,
      tapSeed: tapSeed ?? LivingBlobMetrics.derivedTapSeed(counts: counts), boost: 0)
    return field.radii
  }

  var animatableData:
    AnimatablePair<AnimatablePair<CGFloat, CGFloat>, AnimatablePair<CGFloat, CGFloat>>
  {
    get {
      AnimatablePair(
        AnimatablePair(boost, impactOriginX),
        AnimatablePair(impactDepth, morphProgress)
      )
    }
    set {
      boost = newValue.first.first
      impactOriginX = newValue.first.second
      impactDepth = newValue.second.first
      morphProgress = newValue.second.second
    }
  }

  func path(in rect: CGRect) -> Path {
    let safeBoost = min(max(boost, 0), 1)
    let center = CGPoint(x: 50, y: 52)
    let targetField = Self.restingField(
      counts: counts,
      tapSeed: tapSeed,
      boost: safeBoost
    )
    let safeMorphProgress = morphProgress.clamped(to: 0...1)
    let sourceField = morphFromCounts.map {
      Self.restingField(
        counts: $0,
        tapSeed: LivingBlobMetrics.derivedTapSeed(counts: $0),
        boost: safeBoost
      )
    }
    let impactField = PuddleGeometry.impactField(
      originX: impactOriginX,
      depth: impactDepth
    )
    let points = Self.sampleDirections.indices.map { index -> CGPoint in
      let direction = Self.sampleDirections[index]
      let targetRadius = targetField.radii[index]
      let radius: CGFloat
      if let sourceField {
        let sourceRadius = sourceField.radii[index]
        radius = sourceRadius + (targetRadius - sourceRadius) * safeMorphProgress
      } else {
        radius = targetRadius
      }
      let presentedRadius = presentationRadii?.count == Self.sampleDirections.count
        ? presentationRadii![index] : radius
      let contactAdjustment = PuddleGeometry.softenedAdjustment(contacts.reduce(CGFloat.zero) { result, field in
        result + field.radiusAdjustment(for: direction)
      })
      let impactedRadius = presentedRadius
        + (impactField?.radiusAdjustment(for: direction) ?? 0) + contactAdjustment
      return CGPoint(
        x: (rect.minX + (center.x + direction.dx * impactedRadius) / 100 * rect.width).clamped(
          to: rect.minX...rect.maxX),
        y: (rect.minY + (center.y + direction.dy * impactedRadius) / 100 * rect.height).clamped(
          to: rect.minY...rect.maxY)
      )
    }

    guard let first = points.first else { return Path() }
    var path = Path()
    path.move(to: first)
    for index in points.indices {
      let previous = points[(index - 1 + points.count) % points.count]
      let current = points[index]
      let next = points[(index + 1) % points.count]
      let after = points[(index + 2) % points.count]
      let firstControl = CGPoint(
        x: (current.x + (next.x - previous.x) / 6)
          .clamped(to: rect.minX...rect.maxX),
        y: (current.y + (next.y - previous.y) / 6)
          .clamped(to: rect.minY...rect.maxY)
      )
      let secondControl = CGPoint(
        x: (next.x - (after.x - current.x) / 6)
          .clamped(to: rect.minX...rect.maxX),
        y: (next.y - (after.y - current.y) / 6)
          .clamped(to: rect.minY...rect.maxY)
      )
      path.addCurve(to: next, control1: firstControl, control2: secondControl)
    }
    path.closeSubpath()
    return path
  }

  // Soft unions of overlapping elliptical volumes, shared with Android.
  // Sampled once, then reused by the spring, widgets and History.
  private static let forms: [[[Double]]] = [
    [[0, 0, 0.9, 0.86]],
    [[0, 0.25, 0.73, 0.78], [-0.13, -0.42, 0.48, 0.61]],
    [[-0.39, -0.22, 0.65, 0.63], [0.3, 0.28, 0.74, 0.59]],
    [[0, -0.36, 0.61, 0.62], [-0.4, 0.29, 0.62, 0.59], [0.4, 0.29, 0.62, 0.59]],
    [[0, 0.22, 0.78, 0.74], [-0.48, -0.5, 0.38, 0.39], [0.48, -0.5, 0.38, 0.39]],
    [[-0.57, 0.15, 0.5, 0.57], [0, -0.18, 0.58, 0.71], [0.57, 0.15, 0.5, 0.57]],
    [[-0.37, -0.32, 0.56, 0.56], [0.37, -0.32, 0.56, 0.56], [-0.37, 0.39, 0.53, 0.51], [0.37, 0.39, 0.53, 0.51]],
    [[0, -0.57, 0.43, 0.48], [0, -0.05, 0.66, 0.56], [0, 0.47, 0.88, 0.47]],
    [[0, -0.25, 0.97, 0.6], [0, 0.39, 0.53, 0.65]],
    [[-0.29, 0.06, 0.86, 0.64], [0.59, -0.15, 0.46, 0.43]],
    [[0, 0.2, 0.7, 0.84], [-0.39, -0.63, 0.39, 0.4], [0.39, -0.63, 0.39, 0.4], [-0.57, 0.29, 0.35, 0.43], [0.57, 0.29, 0.35, 0.43]],
  ]
  private static let archetypes: [[Double]] = forms.map { shape in
    let samples = (0..<48).map { index -> Double in
      let angle = Double(index) / 48 * 2 * .pi
      var low = 0.0
      var high = 2.5
      for _ in 0..<23 {
        let radius = (low + high) / 2
        let x = cos(angle) * radius
        let y = sin(angle) * radius
        let field = shape.reduce(0.0) { sum, ellipse in
          let dx = (x - ellipse[0]) / ellipse[2]
          let dy = (y - ellipse[1]) / ellipse[3]
          return sum + exp(5 * (1 - dx * dx - dy * dy))
        }
        if field > 1 { low = radius } else { high = radius }
      }
      return (low + high) / 2
    }
    var rounded = normalized(samples, radius: 0.88)
    for _ in 0..<20 {
      let previous = rounded
      rounded = (0..<48).map { index -> Double in
        let left = previous[(index + 47) % 48]
        let center = previous[index]
        let right = previous[(index + 1) % 48]
        return (left + 2 * center + right) / 4
      }
    }
    return rounded
  }

  private static func normalized(_ radii: [Double], radius: Double) -> [Double] {
    let area = radii.indices.reduce(0.0) { result, index in
      result + radii[index] * radii[(index + 1) % 48] * sin(2 * .pi / 48) / 2
    }
    let factor = sqrt(.pi * radius * radius / area)
    return radii.map { $0 * factor }
  }

  // Exact attenuation of the same circular blur for the two variation waves.
  private static let secondHarmonic = pow((1 + cos(4 * Double.pi / 48)) / 2, 20)
  private static let thirdHarmonic = pow((1 + cos(6 * Double.pi / 48)) / 2, 20)

  private static func restingField(
    counts: FoodCounts,
    tapSeed: Int,
    boost: CGFloat
  ) -> RestingField {
    let total = max(counts.total, 0)
    let base: [Double]
    if total <= 10 {
      base = archetypes[total]
    } else {
      let cycle = [10, 5, 8, 6, 9, 3, 7, 4]
      let step = total - 10
      let index = (step / 4) % cycle.count
      let t = Double(step % 4) / 4
      let blend = t * t * (3 - 2 * t)
      let from = archetypes[cycle[index]]
      let to = archetypes[cycle[(index + 1) % cycle.count]]
      base = (0..<48).map { from[$0] + (to[$0] - from[$0]) * blend }
    }
    // Integer modular seed is stable across redraws and both languages. Keep
    // variation low-frequency so randomness never introduces pointed edges.
    let seed = ((counts.green % 65521) * 31 + (counts.yellow % 65521) * 57
      + (counts.red % 65521) * 97 + ((tapSeed % 65521 + 65521) % 65521) * 17 + 19) % 65521
    let phase = Double(seed) / 65521 * 2 * .pi
    var radii = (0..<48).map { index -> Double in
      let angle = Double(index) / 48 * 2 * .pi
      let variation = 0.014 * secondHarmonic * sin(2 * angle + phase * 7)
        + 0.009 * thirdHarmonic * cos(3 * angle - phase * 11)
      return 0.88 + (base[index] - 0.88) * 0.8 + variation
    }
    radii = normalized(radii, radius: 37)
    // Reserve deformation headroom. Normalize after reducing contrast so the
    // visible resting area cannot shrink when the next silhouette appears.
    for _ in 0..<8 where (radii.max() ?? 0) > 45.5 {
      radii = normalized(radii.map { 37 + ($0 - 37) * 0.94 }, radius: 37)
    }
    let amount = Double(boost.clamped(to: 0...1))
    return RestingField(radii: radii.enumerated().map { index, radius in
      let angle = Double(index) / 48 * 2 * .pi
      return CGFloat(radius * (1 + amount * 0.055 * cos(2 * angle)))
    })
  }

  private struct RestingField {
    let radii: [CGFloat]
  }

}

enum FoodStreak {
  static func consecutiveDays(
    endingAt date: Date,
    records: [DayRecord],
    calendar: Calendar = .autoupdatingCurrent
  ) -> Int {
    let totals = Dictionary(
      uniqueKeysWithValues: records.map { ($0.dateKey, $0.counts.total) }
    )
    let today = calendar.startOfDay(for: date)
    var streak = 0
    for offset in 0..<FoodStateDocument.maximumHistoryDays {
      guard let day = calendar.date(byAdding: .day, value: -offset, to: today)
      else { break }
      let key = FoodDateKey.string(for: day, calendar: calendar)
      guard (totals[key] ?? 0) > 0 else { break }
      streak += 1
    }
    return streak
  }
}

extension CGFloat {
  fileprivate func clamped(to range: ClosedRange<CGFloat>) -> CGFloat {
    Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
  }
}
