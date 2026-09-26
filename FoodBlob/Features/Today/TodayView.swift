import Combine
import SwiftUI

struct TodayView: View {
  var body: some View {
    DayEditorView(title: "Today", resetsToTodayOnAppear: true)
  }
}

struct DayEditorView: View {
  let title: String
  var resetsToTodayOnAppear = false
  var showsSelectedDateInTitle = false

  @Environment(FoodStore.self) private var store
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var changeNotice: String?
  @State private var noticeTask: Task<Void, Never>?
  @State private var flightCleanupTask: Task<Void, Never>?
  @State private var sceneFrames: [BlobSceneElement: CGRect] = [:]
  @State private var flights: [BlobFlight] = []
  @State private var viewportHeight: CGFloat = 650
  @State private var viewportWidth: CGFloat = 393
  @State private var draggedOffering: BlobDraggedOffering?
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @AppStorage("foodBlobInteractionSounds") private var interactionSounds = false

  private var noticeHeight: CGFloat { dynamicTypeSize.isAccessibilitySize ? 100 : 52 }
  private var compactLayout: Bool { viewportHeight < 600 && !dynamicTypeSize.isAccessibilitySize }
  private var heroHeight: CGFloat {
    let reserved: CGFloat = dynamicTypeSize.isAccessibilitySize ? 500 : compactLayout ? 318 : 374
    return min(max(viewportHeight - reserved, compactLayout ? 116 : 220), 320)
  }
  private var heroSize: CGFloat {
    min(246, compactLayout ? heroHeight / 1.20 : heroHeight - 38,
      max(120, (viewportWidth - 44) / 1.38))
  }
  private var normalizedOfferingPoint: CGPoint? {
    guard let offering = draggedOffering, let blob = sceneFrames[.blob] else { return nil }
    return CGPoint(x: min(max((offering.point.x - blob.minX) / blob.width, 0), 1),
      y: min(max((offering.point.y - blob.minY) / blob.height, 0), 1))
  }

  private var design: SkinDesign { store.selectedSkin.design }
  private var calendar: Calendar { .autoupdatingCurrent }
  private var selectedDateKey: String {
    FoodDateKey.string(for: store.selectedDate, calendar: calendar)
  }
  private var interactionScope: String { selectedDateKey + "|" + store.selectedSkin.rawValue }
  private var canMoveForward: Bool {
    store.selectedDate < calendar.startOfDay(for: Date())
  }
  private var canMoveBackward: Bool {
    guard
      store.history.count >= FoodStateDocument.maximumHistoryDays,
      let oldestRetainedDateKey = store.history.last?.dateKey
    else {
      return true
    }
    return selectedDateKey > oldestRetainedDateKey
  }
  private var resolvedTitle: String {
    showsSelectedDateInTitle
      ? store.selectedDate.formatted(.dateTime.month(.wide).day())
      : title
  }
  private var streak: Int {
    FoodStreak.consecutiveDays(
      endingAt: store.selectedDate,
      records: store.history,
      calendar: calendar
    )
  }

  var body: some View {
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin)

      VStack(spacing: 0) {
        ScrollView {
          VStack(spacing: 0) {
            statusBanners
            todayContent
          }
          .padding(.bottom, 12)
        }
        .scrollIndicators(.hidden)
        .simultaneousGesture(daySwipeGesture)

        // One persistent Undo location preserves the hit target without a
        // second toolbar action or a layout jump after each accepted offering.
        HStack(spacing: 10) {
          Text(changeNotice ?? "Tap a colour. Or hold, then drag it into your blob")
            .font(.caption)
            .foregroundStyle(design.palette.mutedInk)
            .fixedSize(horizontal: false, vertical: true)
          Spacer(minLength: 4)
          if store.canUndo(on: store.selectedDate) {
            Button("Undo", action: undo)
              .font(.subheadline.weight(.semibold))
              .frame(minWidth: 52, minHeight: 44)
              .foregroundStyle(design.palette.controlAccent)
              .buttonStyle(LiquidButtonStyle(tint: design.palette.green))
              .accessibilityLabel("Undo last change")
          }
        }
        .frame(minHeight: noticeHeight)
        .padding(.horizontal, 14)
        .background(design.palette.raised.opacity(0.92), in: RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 18)
        .padding(.bottom, 6)
      }
    }
    .coordinateSpace(name: "food-interaction")
    .overlay {
      BlobFlightLayer(flights: reduceMotion ? [] : flights)
      if let offering = draggedOffering {
        Circle()
          .fill(RadialGradient(colors: [.white.opacity(0.9), offering.color.presentationColor],
            center: .topLeading, startRadius: 0, endRadius: 24))
          .frame(width: 28, height: 32)
          .shadow(color: offering.color.presentationColor.opacity(0.35), radius: 8, y: 4)
          .position(offering.point)
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
    }
    .onPreferenceChange(BlobSceneFrames.self) { sceneFrames = $0 }
    .onGeometryChange(for: CGSize.self) { $0.size } action: {
      viewportHeight = $0.height
      viewportWidth = $0.width
    }
    .navigationTitle(resolvedTitle)
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
    .toolbarColorScheme(store.selectedSkin == .shrine ? .dark : .light, for: .navigationBar)
    .onAppear(perform: resetToTodayIfNeeded)
    .onChange(of: scenePhase) { _, phase in
      if phase == .active { resetToTodayIfNeeded() }
      else { cancelFlights() }
    }
    .onReceive(NotificationCenter.default.publisher(for: .NSCalendarDayChanged)) { _ in
      resetToTodayIfNeeded()
    }
    .onReceive(NotificationCenter.default.publisher(for: .NSSystemTimeZoneDidChange)) { _ in
      resetToTodayIfNeeded()
    }
    .onChange(of: selectedDateKey) { _, _ in cancelFlights() }
    .onChange(of: store.selectedSkin) { _, _ in cancelFlights() }
    .onChange(of: reduceMotion) { _, _ in cancelFlights() }
    .onDisappear {
      noticeTask?.cancel()
      cancelFlights()
    }
  }

  @ViewBuilder
  private var statusBanners: some View {
    if !store.isSharedContainerAvailable {
      statusBanner(
        icon: "exclamationmark.triangle.fill",
        text: "Widgets need setup before they can share your counts."
      )
      .padding(.horizontal, 18)
      .padding(.bottom, 10)
    } else if let error = store.lastError {
      statusBanner(icon: "arrow.clockwise.circle", text: error)
        .padding(.horizontal, 18)
        .padding(.bottom, 10)
    }
  }

  private var todayContent: some View {
    VStack(spacing: 0) {
      HStack(spacing: 4) {
        dayButton(systemName: "chevron.left", label: "Previous day", disabled: !canMoveBackward) { moveDay(-1) }
        VStack(spacing: 2) {
          Text(store.selectedDate.formatted(.dateTime.weekday(.wide).month(.abbreviated).day()))
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(design.palette.ink)
          if streak > 0 {
            Label("\(streak) day streak", systemImage: "flame.fill")
              .font(.caption2)
              .foregroundStyle(design.palette.mutedInk)
          }
        }
        .frame(maxWidth: .infinity)
        dayButton(systemName: "chevron.right", label: "Next day", disabled: !canMoveForward) { moveDay(1) }
      }
      .padding(.horizontal, 18)
      .padding(.top, 2)

      ZStack {
        Ellipse()
          .fill(RadialGradient(colors: [design.palette.green.opacity(0.07), .clear],
            center: .center, startRadius: 0, endRadius: 150))
          .frame(width: 310, height: 200)
        FoodBlobView(counts: store.visibleCounts, skin: store.selectedSkin,
          interactive: true, showsTotal: false, animationScope: selectedDateKey,
          growthPresentation: .hero, flights: flights, offeringPoint: normalizedOfferingPoint)
          .frame(width: heroSize, height: heroSize * 0.96)
          .blobSceneFrame(.blob)
      }
      .frame(maxWidth: .infinity)
      .frame(height: heroHeight)

      VStack(spacing: 3) {
        Text(FoodCountText.offerings(store.visibleCounts.total))
          .font(.system(.title3, design: .rounded, weight: .bold))
          .contentTransition(.numericText())
          .foregroundStyle(design.palette.ink)
        if !compactLayout {
          Text(store.visibleCounts.total == 0 ? "A little colour starts something" : "Your day, taking shape")
            .font(.caption)
            .foregroundStyle(design.palette.mutedInk)
        }
      }
      .accessibilityIdentifier("blob-count-footer")
      .accessibilityHidden(true)
      .padding(.bottom, compactLayout ? 10 : 18)

      FoodCounterControls(counts: store.visibleCounts, skin: store.selectedSkin,
        onIncrement: increment, onDecrement: decrement, compact: compactLayout,
        dragScope: interactionScope, onDragChanged: { draggedOffering = $0 }, onDrop: dropOffering)
        .padding(.horizontal, 24)
    }
  }

  private func dropOffering(_ offering: BlobDraggedOffering) -> Bool {
    defer { draggedOffering = nil }
    guard scenePhase == .active, let blob = sceneFrames[.blob] else { return false }
    let scale = LivingBlobMetrics.heroGrowthScale(total: store.visibleCounts.total)
    let width = (blob.width - 16) * scale * 0.88 + 24
    let height = (blob.height - 16) * scale * 0.88 + 24
    let target = CGRect(x: blob.midX - width / 2, y: blob.midY - height / 2,
      width: width, height: height)
    guard BlobFeedDropPolicy.accepts(point: offering.point, translation: offering.translation,
      blob: target, sameDay: offering.scope == interactionScope) else { return false }
    let previous = store.visibleCounts
    guard store.increment(offering.color, on: store.selectedDate) else { return false }
    launchDroplet(offering.color, from: previous, source: offering.point)
    showNotice("Added \(offering.color.displayName.lowercased())")
    return true
  }

  private func dayButton(
    systemName: String,
    label: String,
    disabled: Bool = false,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      Image(systemName: systemName)
        .frame(width: 44, height: 44)
    }
    .buttonStyle(LiquidButtonStyle(tint: design.palette.green, cornerRadius: 100))
    .disabled(disabled)
    .opacity(disabled ? 0.30 : 1)
    .foregroundStyle(design.palette.ink)
    .accessibilityLabel(label)
  }

  private func statusBanner(icon: String, text: String) -> some View {
    Label(text, systemImage: icon)
      .font(.footnote.weight(.semibold))
      .foregroundStyle(design.palette.ink)
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(14)
      .background(design.palette.yellow.opacity(0.28), in: RoundedRectangle(cornerRadius: 16))
  }

  private var daySwipeGesture: some Gesture {
    DragGesture(minimumDistance: 50, coordinateSpace: .named("food-interaction"))
      .onEnded { value in
        // A horizontal edge stretch belongs to the blob, never the day pager.
        if let blob = sceneFrames[.blob], blob.contains(value.startLocation) { return }
        if draggedOffering != nil || FoodColor.allCases.contains(where: {
          sceneFrames[.tile($0)]?.contains(value.startLocation) == true
        }) { return }
        guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
        if value.translation.width > 85, canMoveBackward {
          moveDay(-1)
        } else if value.translation.width < -85, canMoveForward {
          moveDay(1)
        }
      }
  }

  private func increment(_ color: FoodColor) -> Bool {
    let previous = store.visibleCounts
    let changed = store.increment(color, on: store.selectedDate)
    if changed {
      launchDroplet(color, from: previous)
      showNotice("Added \(color.displayName.lowercased())")
    }
    return changed
  }

  private func decrement(_ color: FoodColor) -> Bool {
    guard store.visibleCounts.count(for: color) > 0 else { return false }
    let changed = store.decrement(color, on: store.selectedDate)
    if changed {
      cancelFlights()
      showNotice("Removed \(color.displayName.lowercased())")
    }
    return changed
  }

  private func undo() {
    let previous = store.visibleCounts
    guard store.undo(on: store.selectedDate) else { return }
    cancelFlights()
    if let mutation = FoodMutation.detect(from: previous, to: store.visibleCounts), mutation.delta < 0,
      !reduceMotion, let tile = sceneFrames[.tile(mutation.color)], let blob = sceneFrames[.blob] {
      flights = [BlobFlight(color: mutation.color, source: CGPoint(x: blob.midX, y: blob.midY),
        target: CGPoint(x: tile.midX, y: tile.midY), contact: CGPoint(x: 0.5, y: 0.5),
        started: Date.timeIntervalSinceReferenceDate, returning: true)]
      scheduleFlightCleanup()
    }
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
    showNotice("Undone")
  }

  private func launchDroplet(_ color: FoodColor, from previous: FoodCounts, source: CGPoint? = nil) {
    guard !reduceMotion,
      let tile = sceneFrames[.tile(color)], let blob = sceneFrames[.blob],
      tile.width > 0, blob.width > 0
    else {
      if interactionSounds { BlobPlopSound.shared.play() }
      return
    }
    let now = Date.timeIntervalSinceReferenceDate
    let index = color == .green ? 15 : color == .red ? 9 : 12
    let angle = CGFloat(index) / 48 * 2 * .pi
    let radius = PuddleShape.restingRadii(counts: previous)[index]
    let contact = source.map { point in
      CGPoint(x: min(max((point.x - blob.minX) / blob.width, 0), 1),
        y: min(max((point.y - blob.minY) / blob.height, 0), 1))
    } ?? CGPoint(x: 0.5 + cos(angle) * radius / 100,
      y: 0.52 + sin(angle) * radius / 100)
    let scale = LivingBlobMetrics.heroGrowthScale(total: previous.total)
    let lift = BlobIdleMotion.lift(at: now + BlobFlight.duration, skin: store.selectedSkin)
    let target = CGPoint(x: blob.midX + (contact.x - 0.5) * (blob.width - 16) * scale,
      y: blob.midY + (contact.y - 0.5) * (blob.height - 16) * scale - lift)
    flights.removeAll { $0.arrival + 0.6 < now }
    let flight = BlobFlight(color: color, source: source ?? CGPoint(x: tile.midX, y: tile.midY),
      target: source ?? target, contact: contact,
      started: source == nil ? now : now - BlobFlight.duration)
    flights.append(flight)
    if interactionSounds { BlobPlopSound.shared.schedule(after: source == nil ? BlobFlight.duration : 0) }
    scheduleFlightCleanup()
  }

  private func scheduleFlightCleanup() {
    flightCleanupTask?.cancel()
    flightCleanupTask = Task { @MainActor in
      try? await Task.sleep(for: .milliseconds(850))
      guard !Task.isCancelled else { return }
      flights.removeAll { $0.arrival + 0.5 < Date.timeIntervalSinceReferenceDate }
    }
  }

  private func cancelFlights() {
    flightCleanupTask?.cancel()
    flights = []
    draggedOffering = nil
    BlobPlopSound.shared.cancel()
  }

  private func moveDay(_ delta: Int) {
    if delta < 0, !canMoveBackward { return }
    guard let next = calendar.date(byAdding: .day, value: delta, to: store.selectedDate)
    else { return }
    if next > Date(), delta > 0 { return }
    UISelectionFeedbackGenerator().selectionChanged()
    noticeTask?.cancel()
    withAnimation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.82)) {
      store.selectDate(next)
      changeNotice = nil
    }
  }

  private func showNotice(_ message: String) {
    noticeTask?.cancel()
    withAnimation(reduceMotion ? nil : .spring(response: 0.32)) { changeNotice = message }
    noticeTask = Task { @MainActor in
      try? await Task.sleep(for: .seconds(3))
      guard !Task.isCancelled else { return }
      withAnimation(reduceMotion ? nil : .easeOut(duration: 0.20)) {
        changeNotice = nil
      }
    }
  }

  private func resetToTodayIfNeeded() {
    guard resetsToTodayOnAppear, !calendar.isDateInToday(store.selectedDate) else { return }
    store.selectDate(Date())
    changeNotice = nil
  }
}
