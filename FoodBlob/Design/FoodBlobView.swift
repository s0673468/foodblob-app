import SwiftUI
import UIKit

enum BlobGrowthPresentation { case compact, hero, materialPreview }

struct FoodBlobView: View {
  let counts: FoodCounts
  let skin: SkinID
  var interactive = false
  var showsTotal = true
  var showsUnitLabel = true
  var allowsIdleMotion = true
  var animationScope: String? = nil
  var growthPresentation: BlobGrowthPresentation = .compact
  var flights: [BlobFlight] = []
  var offeringPoint: CGPoint? = nil

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.scenePhase) private var scenePhase
  @AppStorage(BlobMaterial.preferenceKey, store: UserDefaults(suiteName: BlobMaterial.appGroup))
  private var translucency = BlobMaterial.defaultTranslucency
  @State private var mix: BlobMixTimeline?
  @State private var contacts: [BlobContact] = []
  @State private var dragPoint: CGPoint?
  @State private var press = BlobPressTimeline()
  @State private var bodyPull = CGSize.zero
  @State private var lastDragDistance: CGFloat = 0
  @State private var isVisible = false
  @State private var consumedFlights: Set<UUID> = []
  @State private var greetedAt: TimeInterval = -.infinity

  private var hero: Bool { growthPresentation == .hero }
  private var animationState: BlobAnimationState {
    BlobAnimationState(counts: counts, scope: animationScope, flight: flights.last)
  }

  var body: some View {
    let labelUsesLightInk = JellySurfaceGeometry.labelUsesLightInk(
      color: BlobColor.mixed(counts: counts) ?? .empty, skin: skin, translucency: translucency)
    let settledPose = reduceMotion || mix == nil ? BlobPose(counts: counts, hero: hero) : nil
    return GeometryReader { proxy in
      TimelineView(.animation(paused: !isVisible || scenePhase != .active || reduceMotion || !allowsIdleMotion)) { context in
        let now = context.date.timeIntervalSinceReferenceDate
        let pose = settledPose ?? mix!.pose(at: now)
        let displayScale: CGFloat = growthPresentation == .materialPreview ? 0.90 : pose.scale
        let phase = BlobIdleMotion.phase(at: now, skin: skin)
        let greeting = reduceMotion || !interactive ? 0 : BlobPersonality.greeting(age: now - greetedAt)
        let weight = BlobPersonality.idleAmplitude(total: counts.total)
        let lift = allowsIdleMotion && !reduceMotion ? CGFloat(4 + sin(phase) * 4) * weight + greeting * 7 : 0
        let squash = reduceMotion ? 0 : bodySquash(at: now) + greeting * 0.24
        let fields = contactFields(at: now)
        let shape = PuddleShape(counts: counts,
          tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts),
          presentationRadii: reduceMotion ? nil : pose.radii, contacts: fields)
        ZStack {
          if hero {
            groundShadow(scale: pose.scale, lift: lift, squash: squash, size: proxy.size)
          }
          puddle(shape: shape, pose: pose, fields: fields, time: now, size: proxy.size,
            labelUsesLightInk: labelUsesLightInk)
            .padding(8)
            .scaleEffect(displayScale)
            .scaleEffect(x: 1 + squash * 0.105, y: 1 - squash * 0.09)
            .scaleEffect(x: 1 + (reduceMotion || !allowsIdleMotion ? 0 : CGFloat(sin(phase * 0.83)) * 0.019),
              y: 1 - (reduceMotion || !allowsIdleMotion ? 0 : CGFloat(sin(phase * 0.83)) * 0.016))
            .offset(y: -lift)
            .offset(bodyPull)
            .offset(anticipation(at: now))
            .animation(reduceMotion ? nil : .interactiveSpring(response: 0.28, dampingFraction: 0.82),
              value: offeringPoint)
            .animation(reduceMotion ? nil : .interactiveSpring(response: 0.32, dampingFraction: 0.72),
              value: bodyPull)
            .rotationEffect(.degrees(reduceMotion || !allowsIdleMotion ? 0 : sin(phase) * 1.15))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(BlobPlayHitShape(shape: shape, scale: displayScale, lift: lift))
        .gesture(playGesture(size: proxy.size, scale: displayScale, lift: lift), including: interactive ? .all : [])
      }
    }
    .onAppear { isVisible = true; resetMotion(); greet() }
    .onChange(of: animationState) { previous, next in
      guard previous.scope == next.scope else { resetMotion(); return }
      guard previous.counts != next.counts else { return }
      let now = Date.timeIntervalSinceReferenceDate
      if mix == nil { mix = BlobMixTimeline(counts: previous.counts, hero: hero) }
      guard BlobAnimationPolicy.showsTransientPaint(reduceMotion: reduceMotion,
        allowsIdleMotion: allowsIdleMotion, stayedInSameScope: true) else {
        mix = BlobMixTimeline(counts: next.counts, hero: hero)
        contacts = []
        return
      }
      let isRemoval = next.counts.total < previous.counts.total
      let newFlights = isRemoval ? [] : flights.filter { !$0.returning && !consumedFlights.contains($0.id) }
      let flight = newFlights.last
      let arrival = flight?.arrival ?? now
      if isRemoval {
        contacts = JellySurfaceGeometry.contactsAfterRemoval(contacts, at: now)
      }
      mix?.retarget(next.counts, at: arrival, duration: BlobPersonality.settlingTime(total: next.counts.total) + 0.18)
      if newFlights.isEmpty {
        appendContact(BlobContact(started: arrival, point: CGPoint(x: 0.5, y: 0.9),
          strength: isRemoval ? -0.65 : 1))
      } else {
        for event in newFlights {
          appendContact(BlobContact(started: event.arrival, point: event.contact, color: event.color))
        }
      }
      consumedFlights = Set(flights.map(\.id))
    }
    .onChange(of: reduceMotion) { _, _ in resetMotion() }
    .onChange(of: skin) { _, _ in resetMotion() }
    .onChange(of: flights.isEmpty) { _, empty in
      guard empty else { return }
      let now = Date.timeIntervalSinceReferenceDate
      contacts.removeAll { $0.started > now }
      mix?.retarget(counts, at: now, duration: 0.18)
    }
    .onChange(of: scenePhase) { _, phase in
      if phase != .active { resetMotion() } else { greet() }
    }
    .onDisappear { isVisible = false; resetMotion() }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Food mix")
    .accessibilityValue(
      "\(FoodCountText.offerings(counts.total)). \(counts.green) green, "
        + "\(counts.yellow) yellow, \(counts.red) red.")
    .accessibilityAction(named: Text("Play with blob")) {
      if interactive { poke(at: CGPoint(x: 0.5, y: 0.15)) }
    }
    .accessibilityHint(interactive ? "Touch or stretch to play. Your food counts stay the same." : "")
  }

  private func puddle(shape: PuddleShape, pose: BlobPose, fields: [PuddleImpactField],
    time: TimeInterval, size: CGSize, labelUsesLightInk: Bool) -> some View {
    ZStack {
      JellySurface(shape: shape, pose: pose,
        fields: fields, contacts: reduceMotion ? [] : contacts,
        time: time, skin: skin, dragPoint: reduceMotion ? nil : dragPoint,
        dragDepth: reduceMotion ? 0 : press.depth(at: time), allowsMotion: !reduceMotion && allowsIdleMotion,
        translucency: translucency)
      if showsTotal, counts.total > 0 {
        VStack(spacing: 2) {
          Text("\(counts.total)")
            .font(.system(size: min(size.width, size.height) * 0.23, weight: .heavy, design: .rounded))
            .contentTransition(.numericText())
            .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: counts.total)
          if showsUnitLabel {
            Text("OFFERINGS").font(.system(size: 10, weight: .semibold, design: .rounded))
              .tracking(hero && pose.scale < 0.75 ? 0.8 : 2.4)
          }
        }
        .foregroundStyle(labelUsesLightInk ? Color.white : Color.black)
        .scaleEffect(1 / sqrt(max(pose.scale, 0.01)))
        .minimumScaleFactor(0.65)
      } else if showsTotal, counts.total == 0 {
        Image(systemName: "plus").font(.system(size: 28, weight: .semibold, design: .rounded))
          .foregroundStyle(skin.design.palette.mutedInk)
      }
    }
    .shadow(color: .black.opacity(skin == .shrine ? 0.2 : 0.10), radius: 8, y: 6)
  }

  private func groundShadow(scale: CGFloat, lift: CGFloat, squash: CGFloat, size: CGSize) -> some View {
    ZStack {
      Ellipse()
        .fill(Color(blobColor: BlobColor.mixed(counts: counts) ?? .empty)
          .opacity(skin == .shrine ? 0.32 : 0.24))
        .frame(width: size.width * 0.68 * scale, height: 18)
        .blur(radius: 9)
        .scaleEffect(x: 1 + squash * 0.15, y: 0.8)
        .offset(x: 5, y: size.height * 0.38 * scale + 13)
      Ellipse()
        .fill(skin == .shrine ? Color.black.opacity(0.46) : Color(red: 0.12, green: 0.24, blue: 0.16).opacity(0.24))
        .frame(width: size.width * 0.61 * scale, height: 13)
        .blur(radius: 6 + lift * 0.30)
        .scaleEffect(x: (1 - lift * 0.015) * (1 + squash * 0.13), y: 1 - lift * 0.012)
        .opacity(1 - lift * 0.032)
        .offset(y: size.height * 0.38 * scale + 9)
    }
    .allowsHitTesting(false)
  }

  private func bodySquash(at time: TimeInterval) -> CGFloat {
    let duration = BlobPersonality.settlingTime(total: counts.total)
    let held = dragPoint == nil ? 0 : press.depth(at: time) * BlobPlayGeometry.bodyWeight
    return min(max(contacts.reduce(held) { sum, contact in
      let weightedTime = contact.started + (time - contact.started) * 0.50 / duration
      return sum + (contact.movesBody ? contact.wave(at: weightedTime) * contact.bodyWeight : 0)
    }, -1.7), 1.7)
  }

  private func anticipation(at time: TimeInterval) -> CGSize {
    guard !reduceMotion else { return .zero }
    var x: CGFloat = 0, y: CGFloat = 0
    for flight in flights where !flight.returning && time >= flight.started && time < flight.arrival {
      let strength = sin(flight.progress(at: time) * .pi)
      x += (flight.contact.x - 0.5) * strength * 9
      y += strength * 3
    }
    if let offeringPoint {
      x += (offeringPoint.x - 0.5) * 7
      y += (offeringPoint.y - 0.5) * 4
    }
    return CGSize(width: 9 * tanh(x / 9), height: 5 * tanh(y / 5))
  }

  private func greet() {
    guard interactive, !reduceMotion else { return }
    greetedAt = Date.timeIntervalSinceReferenceDate
  }

  private func contactFields(at time: TimeInterval) -> [PuddleImpactField] {
    guard !reduceMotion else { return [] }
    var result = contacts.compactMap {
      PuddleGeometry.contactField(point: $0.point, depth: $0.wave(at: time))
    }
    if let dragPoint, let field = PuddleGeometry.contactField(point: dragPoint, depth: press.depth(at: time)) {
      result.append(field)
    }
    return result
  }

  private func playGesture(size: CGSize, scale: CGFloat, lift: CGFloat) -> some Gesture {
    DragGesture(minimumDistance: 0)
      .onChanged { value in
        guard interactive else { return }
        let point = dragPoint ?? BlobPlayGeometry.surfacePoint(
          location: value.startLocation, size: size, scale: scale, lift: lift)
        if dragPoint == nil {
          dragPoint = point
          UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.48)
        }
        lastDragDistance = hypot(value.translation.width, value.translation.height)
        guard !reduceMotion else { return }
        bodyPull = CGSize(width: min(max(value.translation.width * 0.10, -14), 14),
          height: min(max(value.translation.height * 0.10, -14), 14))
        let dx = point.x - 0.5, dy = point.y - 0.5
        let length = max(hypot(dx, dy), 0.001)
        let outward = (value.translation.width * dx + value.translation.height * dy) / length
        press.retarget(BlobPressTimeline.pressure(outward: outward),
          at: Date.timeIntervalSinceReferenceDate)
      }
      .onEnded { _ in
        guard let point = dragPoint else { return }
        let releasedDepth = press.depth(at: Date.timeIntervalSinceReferenceDate)
        let strength: CGFloat = lastDragDistance < 8 ? 0.62 : 0.10
        dragPoint = nil; press = BlobPressTimeline(); lastDragDistance = 0; bodyPull = .zero
        poke(at: point, strength: strength, initialDepth: releasedDepth)
      }
  }

  private func poke(at point: CGPoint, strength: CGFloat = 1.05, initialDepth: CGFloat = 0) {
    UIImpactFeedbackGenerator(style: .soft).impactOccurred(intensity: 0.32)
    guard !reduceMotion else { return }
    appendContact(BlobContact(started: Date.timeIntervalSinceReferenceDate, point: point,
      strength: strength, initialDepth: initialDepth, bodyWeight: BlobPlayGeometry.bodyWeight))
  }

  private func appendContact(_ contact: BlobContact) {
    contacts.removeAll { $0.started + JellySurfaceGeometry.contactDuration < Date.timeIntervalSinceReferenceDate }
    contacts.append(contact)
    if contacts.count > 24 { contacts.removeFirst(contacts.count - 24) }
  }

  private func resetMotion() {
    mix = BlobMixTimeline(counts: counts, hero: hero)
    contacts = []
    consumedFlights = Set(flights.map(\.id))
    dragPoint = nil; press = BlobPressTimeline(); bodyPull = .zero
  }
}

private struct BlobAnimationState: Equatable {
  let counts: FoodCounts
  let scope: String?
  let flight: BlobFlight?
}

enum BlobAnimationPolicy {
  static func showsTransientPaint(
    reduceMotion: Bool,
    allowsIdleMotion: Bool,
    stayedInSameScope: Bool
  ) -> Bool {
    !reduceMotion && allowsIdleMotion && stayedInSameScope
  }
}

struct FoodMutation: Equatable {
  let color: FoodColor
  let delta: Int

  static func detect(from previous: FoodCounts, to next: FoodCounts) -> FoodMutation? {
    FoodColor.allCases.compactMap { color -> FoodMutation? in
      let delta = next.count(for: color) - previous.count(for: color)
      return delta == 0 ? nil : FoodMutation(color: color, delta: delta)
    }.first
  }
}
