import SwiftUI

struct BlobFlight: Identifiable, Equatable {
  static let duration: TimeInterval = 0.20
  static let landingDuration: TimeInterval = 0.28
  static let dropletRadii = Array(repeating: Float(0.5), count: 48)
  let id = UUID()
  let color: FoodColor
  let source: CGPoint
  let target: CGPoint
  let contact: CGPoint
  let started: TimeInterval
  var returning = false
  var arrival: TimeInterval { started + Self.duration }

  func progress(at time: TimeInterval) -> CGFloat {
    if time - started >= Self.duration - 0.000000001 { return 1 }
    return CGFloat(min(max((time - started) / Self.duration, 0), 1))
  }

  static func dropletScale(progress: CGFloat) -> CGSize {
    let stretch = sin(min(max(progress, 0), 1) * .pi) * 0.24
    return CGSize(width: 1 - stretch, height: 1 / (1 - stretch))
  }

  func landingProgress(at time: TimeInterval) -> CGFloat? {
    let age = time - arrival
    guard age >= 0, age < Self.landingDuration else { return nil }
    return CGFloat(age / Self.landingDuration)
  }

  func position(at time: TimeInterval) -> CGPoint {
    let t = progress(at: time)
    let control = CGPoint(x: source.x + (target.x - source.x) * 0.22,
      y: target.y + (source.y - target.y) * 0.16)
    return CGPoint(
      x: (1 - t) * (1 - t) * source.x + 2 * (1 - t) * t * control.x + t * t * target.x,
      y: (1 - t) * (1 - t) * source.y + 2 * (1 - t) * t * control.y + t * t * target.y)
  }
}

struct BlobContact: Identifiable {
  let id = UUID()
  let started: TimeInterval
  let point: CGPoint
  var strength: CGFloat = 1
  var color: FoodColor? = nil
  var initialDepth: CGFloat = 0
  var movesBody = true
  var bodyWeight: CGFloat = 1

  func wave(at time: TimeInterval) -> CGFloat {
    let age = time - started
    guard age >= 0, age < 0.50 else { return 0 }
    // Superposition preserves the phase and velocity of every earlier tap.
    let t = age / 0.50
    let envelope = 1 - t * t * (3 - 2 * t)
    return (CGFloat(sin(age * 14)) * strength
      + CGFloat(cos(age * 14)) * initialDepth) * CGFloat(envelope)
  }

  static func squash(_ contacts: [BlobContact], at time: TimeInterval) -> CGFloat {
    let sum = contacts.reduce(CGFloat.zero) { $0 + ($1.movesBody ? $1.wave(at: time) * $1.bodyWeight : 0) }
    return min(max(sum, -1.7), 1.7)
  }
}

/// A short pressure ramp follows the finger without a one-frame dent on touch-down.
struct BlobPressTimeline {
  private var source: CGFloat = 0
  private var target: CGFloat = 0
  private var started: TimeInterval = 0

  func depth(at time: TimeInterval) -> CGFloat {
    target + (source - target) * CGFloat(exp(-max(0, time - started) / 0.045))
  }

  mutating func retarget(_ depth: CGFloat, at time: TimeInterval) {
    source = self.depth(at: time)
    target = depth
    started = time
  }

  static func pressure(outward: CGFloat) -> CGFloat {
    min(1.4, 0.75 - 1.10 * tanh(outward / 65))
  }
}

/// Convert a finger to the actual rendered material, including the small seed.
/// The enclosing hero is reserved layout space, not the jelly's optical bounds.
enum BlobPlayGeometry {
  static let bodyWeight: CGFloat = 0.7

  static func renderedRect(size: CGSize, scale: CGFloat, lift: CGFloat) -> CGRect {
    let width = max(1, size.width - 16) * max(scale, 0.01)
    let height = max(1, size.height - 16) * max(scale, 0.01)
    return CGRect(x: size.width / 2 - width / 2, y: size.height / 2 - height / 2 - lift,
      width: width, height: height)
  }

  static func surfacePoint(location: CGPoint, size: CGSize, scale: CGFloat, lift: CGFloat) -> CGPoint {
    let rect = renderedRect(size: size, scale: scale, lift: lift)
    return CGPoint(x: min(max((location.x - rect.minX) / rect.width, 0.03), 0.97),
      y: min(max((location.y - rect.minY) / rect.height, 0.03), 0.97))
  }
}

struct BlobPlayHitShape: Shape {
  let shape: PuddleShape
  let scale: CGFloat
  let lift: CGFloat

  func path(in rect: CGRect) -> Path {
    let body = BlobPlayGeometry.renderedRect(size: rect.size, scale: scale, lift: lift)
      .offsetBy(dx: rect.minX, dy: rect.minY)
    let outline = shape.path(in: body)
    var hit = outline
    hit.addPath(outline.strokedPath(StrokeStyle(lineWidth: 24, lineCap: .round, lineJoin: .round)))
    return hit
  }
}

struct BlobPose: Equatable {
  var color: BlobColor
  private(set) var radii: [CGFloat]
  private(set) var surfaceRadii: [Float]
  var scale: CGFloat
  var fill: CGFloat

  init(counts: FoodCounts, hero: Bool) {
    color = BlobColor.mixed(counts: counts) ?? .empty
    radii = PuddleShape.restingRadii(counts: counts)
    surfaceRadii = JellySurfaceGeometry.radii(radii, fields: [])
    scale = hero ? LivingBlobMetrics.heroGrowthScale(total: counts.total)
      : LivingBlobMetrics.growthScale(total: counts.total)
    fill = counts.total > 0 ? 1 : 0
  }

  func blending(to target: BlobPose, progress: CGFloat) -> BlobPose {
    let t = min(max(progress, 0), 1)
    if t == 0 { return self }
    if t == 1 { return target }
    var result = self
    result.color = BlobColor(
      red: color.red + (target.color.red - color.red) * Double(t),
      green: color.green + (target.color.green - color.green) * Double(t),
      blue: color.blue + (target.color.blue - color.blue) * Double(t))
    result.radii = zip(radii, target.radii).map { $0 + ($1 - $0) * t }
    result.surfaceRadii = JellySurfaceGeometry.radii(result.radii, fields: [])
    result.scale += (target.scale - scale) * t
    result.fill += (target.fill - fill) * t
    return result
  }
}

struct BlobMixTimeline {
  private struct Transition {
    let source: BlobPose
    let target: BlobPose
    let started: TimeInterval
    let duration: TimeInterval
  }
  private var initial: BlobPose
  private var transitions: [Transition] = []
  private let hero: Bool

  init(counts: FoodCounts, hero: Bool) {
    self.hero = hero
    initial = BlobPose(counts: counts, hero: hero)
  }

  func pose(at time: TimeInterval) -> BlobPose {
    guard let event = transitions.last(where: { $0.started <= time }) else { return initial }
    let t = CGFloat(min(max((time - event.started) / event.duration, 0), 1))
    return event.source.blending(to: event.target, progress: t * t * (3 - 2 * t))
  }

  mutating func retarget(_ counts: FoodCounts, at time: TimeInterval, duration: TimeInterval = 0.68) {
    let source = pose(at: time)
    transitions.removeAll { $0.started > time }
    if let completed = transitions.last(where: { $0.started + $0.duration < time - 1 }) {
      initial = completed.target
      transitions.removeAll { $0.started <= completed.started }
    }
    transitions.append(Transition(source: source, target: BlobPose(counts: counts, hero: hero),
      started: time, duration: duration))
  }
}

enum BlobSceneElement: Hashable { case blob; case tile(FoodColor) }

struct BlobSceneFrames: PreferenceKey {
  static let defaultValue: [BlobSceneElement: CGRect] = [:]
  static func reduce(value: inout [BlobSceneElement: CGRect],
    nextValue: () -> [BlobSceneElement: CGRect]) {
    value.merge(nextValue(), uniquingKeysWith: { _, new in new })
  }
}

extension View {
  func blobSceneFrame(_ element: BlobSceneElement) -> some View {
    background {
      GeometryReader { proxy in
        Color.clear.preference(key: BlobSceneFrames.self,
          value: [element: proxy.frame(in: .named("food-interaction"))])
      }
    }
  }
}

struct BlobFlightLayer: View {
  let flights: [BlobFlight]
  @AppStorage(BlobMaterial.preferenceKey, store: UserDefaults(suiteName: BlobMaterial.appGroup))
  private var translucency = BlobMaterial.defaultTranslucency
  var body: some View {
    TimelineView(.animation(paused: flights.isEmpty)) { context in
      let time = context.date.timeIntervalSinceReferenceDate
      ZStack {
        ForEach(flights) { flight in
          let progress = flight.progress(at: time)
          if progress < 1 {
            let stretch = BlobFlight.dropletScale(progress: progress)
            let before = flight.position(at: time - 0.012)
            let after = flight.position(at: time + 0.012)
            let angle = atan2(after.y - before.y, after.x - before.x) - .pi / 2
            // A trailing bead joins the leading drop before contact. Its small
            // volume and the conserved body area read as liquid, not confetti.
            Circle()
              .fill(flight.color.presentationColor.opacity(0.46))
              .frame(width: 7 * sin(progress * .pi), height: 7 * sin(progress * .pi))
              .position(flight.position(at: time - 0.025))
            Capsule()
              .fill(.white)
              .colorEffect(ShaderLibrary.jellySurface(
                .boundingRect,
                .float3(flight.color.blobColor.red, flight.color.blobColor.green,
                  flight.color.blobColor.blue),
                .floatArray(BlobFlight.dropletRadii),
                .floatArray([]), .float4(0.5, 0.5, 0.0, 1.0), .float(0),
                .float(JellySurfaceGeometry.contactDuration), .float2(0, 0),
                .float(BlobMaterial.clamped(translucency))))
              .frame(width: 23 * stretch.width, height: 23 * stretch.height)
              .rotationEffect(.radians(angle))
              .shadow(color: flight.color.presentationColor.opacity(0.28), radius: 7, y: 3)
              .position(flight.position(at: time))
          } else if !flight.returning, let spread = flight.landingProgress(at: time) {
            Ellipse()
              .strokeBorder(flight.color.presentationColor.opacity(0.55 * (1 - spread)),
                lineWidth: 2.5 * (1 - spread))
              .frame(width: 13 + spread * 45, height: 5 + spread * 15)
              .position(x: flight.target.x, y: flight.target.y + 3)
            ForEach(0..<3) { index in
              let side = CGFloat(index - 1)
              Ellipse()
                .fill(LinearGradient(colors: [.white.opacity(0.75),
                  flight.color.presentationColor.opacity(0.76)],
                  startPoint: .topLeading, endPoint: .bottomTrailing))
                .frame(width: 4.5 - spread * 2.5, height: 6 - spread * 4)
                .opacity((1 - spread) * 0.85)
                .position(x: flight.target.x + side * (5 + spread * 21),
                  y: flight.target.y - sin(spread * .pi) * (index == 1 ? 14 : 9) + spread * 6)
            }
          }
        }
      }
    }
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }
}

enum BlobIdleMotion {
  static func phase(at time: TimeInterval, skin: SkinID) -> Double {
    time * (skin == .skyMeadow ? 0.9 : 0.7)
  }
  static func lift(at time: TimeInterval, skin: SkinID) -> CGFloat {
    CGFloat(4 + sin(phase(at: time, skin: skin)) * 4)
  }
}

/// A drag is an alternative input to the same accepted increment, never a preview write.
enum BlobFeedDropPolicy {
  static func accepts(point: CGPoint, translation: CGSize, blob: CGRect, sameDay: Bool) -> Bool {
    guard sameDay, translation.height < -28, blob.width > 0, blob.height > 0 else { return false }
    let x = (point.x - blob.midX) / (blob.width * 0.5)
    let y = (point.y - blob.midY) / (blob.height * 0.5)
    return x * x + y * y <= 1
  }
}

enum BlobPersonality {
  static func settlingTime(total: Int) -> TimeInterval { 0.50 + min(Double(max(total, 0)), 24) / 24 * 0.22 }
  static func idleAmplitude(total: Int) -> CGFloat { 1 - min(CGFloat(max(total, 0)), 24) / 24 * 0.50 }
  static func greeting(age: TimeInterval) -> CGFloat {
    guard age >= 0, age < 1.1 else { return 0 }
    return CGFloat(sin(age / 1.1 * .pi) * pow(1 - age / 1.1, 1.4))
  }
}

struct BlobDraggedOffering: Equatable {
  let color: FoodColor
  let point: CGPoint
  let translation: CGSize
  let scope: String
}
