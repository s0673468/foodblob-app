import XCTest

@testable import FoodBlob

final class FoodWatchOutboxTests: XCTestCase {
  private var directory: URL!
  private let now = Date(timeIntervalSince1970: 1_800_000_000)

  override func setUpWithError() throws {
    directory = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true
    )
  }

  override func tearDownWithError() throws {
    try? FileManager.default.removeItem(at: directory)
  }

  func testFreshInstallIsAvailableButStillWaitingForPhoneState() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)

    let initial = store.displayState(at: now)

    XCTAssertTrue(initial.isAvailable)
    XCTAssertFalse(initial.hasPhoneState)

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 3),
        skin: .skyMeadow,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 0,
        isReset: false,
        resetThroughSequence: nil
      )
    )

    XCTAssertTrue(store.displayState(at: now).hasPhoneState)
  }

  func testDisplayStateUsesPhoneBaseThenOverlaysPendingWatchEvents() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)
    let acknowledgedID = UUID()
    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: dateKey,
        counts: FoodCounts(green: 2, yellow: 1),
        skin: .shrine,
        generatedAt: now,
        committedEventIDs: [acknowledgedID],
        committedThroughSequence: 0,
        isReset: false,
        resetThroughSequence: nil
      )
    )
    try store.append(entry(id: UUID(), color: .red))

    let state = store.displayState(at: now)

    XCTAssertEqual(state.counts, FoodCounts(green: 2, yellow: 1, red: 1))
    XCTAssertEqual(state.skin, .shrine)
    XCTAssertEqual(state.pendingCount, 1)
    XCTAssertTrue(state.isAvailable)
    XCTAssertTrue(state.hasPhoneState)
  }

  func testAcknowledgementRemovesDeliveredEntriesAndResetsAtMidnight() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    let entryID = UUID()
    try store.append(entry(id: entryID, color: .green))
    let event = try XCTUnwrap(store.pendingEvents().first)
    let tomorrow = Calendar.autoupdatingCurrent.date(
      byAdding: .day,
      value: 1,
      to: now
    )!
    let tomorrowKey = FoodDateKey.string(for: tomorrow)

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: tomorrowKey,
        counts: FoodCounts(yellow: 4),
        skin: .skyMeadow,
        generatedAt: tomorrow,
        committedEventIDs: [],
        committedThroughSequence: event.sequence,
        isReset: false,
        resetThroughSequence: nil,
        senderID: event.senderID
      )
    )

    XCTAssertTrue(store.pendingEntries().isEmpty)
    XCTAssertEqual(
      store.displayState(at: tomorrow).counts,
      FoodCounts(yellow: 4)
    )
    XCTAssertEqual(store.displayState(at: now).counts, FoodCounts())
  }

  func testPreviousDayAcknowledgementIsNotCurrentPhoneStateAfterMidnight() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
    let tomorrow = try XCTUnwrap(
      calendar.date(byAdding: .day, value: 1, to: now)
    )
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now, calendar: calendar),
        counts: FoodCounts(green: 4),
        skin: .shrine,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 0,
        isReset: false,
        resetThroughSequence: nil
      )
    )

    let state = store.displayState(at: tomorrow, calendar: calendar)

    XCTAssertEqual(state.counts, FoodCounts())
    XCTAssertFalse(state.hasPhoneState)
  }

  func testPendingCountDescribesOnlyActionsForDisplayedDay() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
    let tomorrow = try XCTUnwrap(
      calendar.date(byAdding: .day, value: 1, to: now)
    )
    let yesterdayEntry = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: FoodDateKey.string(for: now, calendar: calendar),
      color: .green,
      delta: 1
    )
    let todayEntry = FoodWidgetLedgerEntry(
      timestamp: tomorrow,
      dateKey: FoodDateKey.string(for: tomorrow, calendar: calendar),
      color: .red,
      delta: 1
    )
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(yesterdayEntry)
    try store.append(todayEntry)

    let state = store.displayState(at: tomorrow, calendar: calendar)

    XCTAssertEqual(state.counts, FoodCounts(red: 1))
    XCTAssertEqual(state.pendingCount, 1)
    XCTAssertEqual(store.pendingEntries().count, 2)
  }

  func testStaleAcknowledgementCannotRewindNewerPhoneState() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: dateKey,
        counts: FoodCounts(red: 2),
        skin: .shrine,
        generatedAt: now.addingTimeInterval(60),
        committedEventIDs: [],
        committedThroughSequence: 1,
        isReset: false,
        resetThroughSequence: nil,
        senderID: nil
      )
    )
    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: dateKey,
        counts: FoodCounts(green: 1),
        skin: .skyMeadow,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 0,
        isReset: false,
        resetThroughSequence: nil,
        senderID: nil
      )
    )

    let state = store.displayState(at: now)
    XCTAssertEqual(state.counts, FoodCounts(red: 2))
    XCTAssertEqual(state.skin, .shrine)
  }

  func testResetAcknowledgementDropsOldEventsButKeepsNewerOnes() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    try store.append(entry(id: UUID(), color: .red))
    let events = store.pendingEvents()
    XCTAssertEqual(events.map(\.sequence), [1, 2])

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(),
        skin: .skyMeadow,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 1,
        isReset: true,
        resetThroughSequence: 1,
        senderID: events[0].senderID
      )
    )

    XCTAssertEqual(store.pendingEvents().map(\.sequence), [2])
    XCTAssertEqual(
      store.displayState(at: now).counts,
      FoodCounts(red: 1)
    )
  }

  func testResetGenerationDropsOldEventsAndRestartsSequenceNumbers() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    let old = try XCTUnwrap(store.pendingEvents().first)
    let generation = UUID()

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(),
        skin: .skyMeadow,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 0,
        isReset: true,
        resetThroughSequence: 0,
        senderID: old.senderID,
        resetGeneration: generation
      )
    )

    XCTAssertTrue(store.pendingEvents().isEmpty)
    try store.append(entry(id: UUID(), color: .red))
    let current = try XCTUnwrap(store.pendingEvents().first)
    XCTAssertEqual(current.sequence, 1)
    XCTAssertEqual(current.resetGeneration, generation)
  }

  func testDuplicateResetAcknowledgementDoesNotDropNewGenerationEvent() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    let old = try XCTUnwrap(store.pendingEvents().first)
    let generation = UUID()
    let resetAcknowledgement = FoodWatchAcknowledgement(
      dateKey: FoodDateKey.string(for: now),
      counts: FoodCounts(),
      skin: .skyMeadow,
      generatedAt: now,
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: true,
      resetThroughSequence: 4,
      senderID: old.senderID,
      resetGeneration: generation
    )

    try store.apply(resetAcknowledgement)
    try store.append(entry(id: UUID(), color: .red))
    XCTAssertEqual(store.pendingEvents().map(\.sequence), [1])

    try store.apply(resetAcknowledgement)

    XCTAssertEqual(store.pendingEvents().map(\.sequence), [1])
    XCTAssertEqual(store.pendingEvents().first?.resetGeneration, generation)
  }

  func testOldSenderAcknowledgementUpdatesPhoneStateWithoutDroppingCurrentTap() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    let event = try XCTUnwrap(store.pendingEvents().first)
    let oldSender = UUID()

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 5),
        skin: .skyMeadow,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 5,
        isReset: false,
        resetThroughSequence: nil,
        senderID: oldSender
      )
    )

    XCTAssertEqual(store.pendingEvents().map(\.entry.id), [event.entry.id])
    XCTAssertEqual(
      store.displayState(at: now).counts,
      FoodCounts(green: 6)
    )

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 6),
        skin: .skyMeadow,
        generatedAt: now.addingTimeInterval(1),
        committedEventIDs: [event.entry.id],
        committedThroughSequence: 1,
        isReset: false,
        resetThroughSequence: nil,
        senderID: event.senderID
      )
    )

    XCTAssertTrue(store.pendingEvents().isEmpty)

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 99),
        skin: .shrine,
        generatedAt: now.addingTimeInterval(2),
        committedEventIDs: [],
        committedThroughSequence: 5,
        isReset: false,
        resetThroughSequence: nil,
        senderID: oldSender
      )
    )

    XCTAssertEqual(
      store.displayState(at: now).counts,
      FoodCounts(green: 99)
    )
    XCTAssertEqual(store.displayState(at: now).skin, .shrine)
  }

  func testEverySenderFloorCanCommitTheCurrentWatchThroughAnotherSenderAck() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    let event = try XCTUnwrap(store.pendingEvents().first)

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 1),
        skin: .shrine,
        generatedAt: now,
        committedEventIDs: [],
        committedThroughSequence: 2,
        committedThroughSequencesBySender: [event.senderID: 1],
        isReset: false,
        resetThroughSequence: nil,
        senderID: UUID()
      )
    )

    XCTAssertTrue(store.pendingEvents().isEmpty)
    XCTAssertEqual(store.displayState(at: now).counts, FoodCounts(green: 1))
    XCTAssertEqual(store.displayState(at: now).skin, .shrine)
  }

  func testStaleCrossSenderFloorCannotRewindPhoneStateOrResetGeneration() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    try store.append(entry(id: UUID(), color: .green))
    let senderID = try XCTUnwrap(store.pendingEvents().first?.senderID)
    let currentGeneration = UUID()

    try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 2),
        skin: .shrine,
        generatedAt: now.addingTimeInterval(2),
        committedEventIDs: [],
        committedThroughSequence: 0,
        committedThroughSequencesBySender: [senderID: 0],
        isReset: false,
        resetThroughSequence: nil,
        senderID: UUID(),
        resetGeneration: currentGeneration
      )
    )
    try store.append(entry(id: UUID(), color: .green))
    let currentEvent = try XCTUnwrap(store.pendingEvents().first)

    let applied = try store.apply(
      FoodWatchAcknowledgement(
        dateKey: FoodDateKey.string(for: now),
        counts: FoodCounts(green: 99),
        skin: .skyMeadow,
        generatedAt: now.addingTimeInterval(1),
        committedEventIDs: [],
        committedThroughSequence: 9,
        committedThroughSequencesBySender: [senderID: currentEvent.sequence],
        isReset: false,
        resetThroughSequence: nil,
        senderID: UUID(),
        resetGeneration: UUID()
      )
    )

    XCTAssertFalse(applied)
    XCTAssertEqual(store.pendingEvents(), [currentEvent])
    XCTAssertEqual(store.displayState(at: now).counts, FoodCounts(green: 3))
    XCTAssertEqual(store.displayState(at: now).skin, .shrine)
  }

  func testLegacyOutboxSenderIdentityIsStableAcrossReloads() throws {
    var document = FoodWatchOutboxDocument()
    document.events = [
      FoodWatchTransferEvent(sequence: 1, entry: entry(id: UUID(), color: .green))
    ]
    document.nextSequence = 2
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    let encoded = try encoder.encode(document)
    var object = try XCTUnwrap(
      JSONSerialization.jsonObject(with: encoded) as? [String: Any]
    )
    object.removeValue(forKey: "senderID")
    let migrated = try JSONSerialization.data(withJSONObject: object)
    try migrated.write(
      to: directory.appendingPathComponent(FoodWatchConstants.outboxFileName)
    )

    let first = FoodWatchOutboxStore(containerURL: directory).pendingEvents()
    let second = FoodWatchOutboxStore(containerURL: directory).pendingEvents()

    XCTAssertEqual(first, second)
    XCTAssertEqual(first.first?.senderID, FoodWatchIdentity.legacySenderID)
  }

  func testChangedAcknowledgementReloadsOnlyTheWatchComplicationKind() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    var reloadedKinds: [String] = []
    let applier = FoodWatchAcknowledgementApplier(
      outbox: store,
      reloadTimeline: { reloadedKinds.append($0) }
    )
    let acknowledgement = FoodWatchAcknowledgement(
      dateKey: FoodDateKey.string(for: now),
      counts: FoodCounts(green: 2),
      skin: .skyMeadow,
      generatedAt: now,
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: false,
      resetThroughSequence: nil
    )

    XCTAssertTrue(try applier.apply(acknowledgement))
    XCTAssertEqual(reloadedKinds, [FoodWatchConstants.widgetKind])
  }

  func testDuplicateAndStaleAcknowledgementsDoNotSpendAnotherReload() throws {
    let store = FoodWatchOutboxStore(containerURL: directory)
    var reloadCount = 0
    let applier = FoodWatchAcknowledgementApplier(
      outbox: store,
      reloadTimeline: { _ in reloadCount += 1 }
    )
    let current = FoodWatchAcknowledgement(
      dateKey: FoodDateKey.string(for: now),
      counts: FoodCounts(yellow: 2),
      skin: .shrine,
      generatedAt: now,
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: false,
      resetThroughSequence: nil
    )
    let duplicate = FoodWatchAcknowledgement(
      dateKey: current.dateKey,
      counts: current.counts,
      skin: current.skin,
      generatedAt: now.addingTimeInterval(1),
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: false,
      resetThroughSequence: nil
    )
    let stale = FoodWatchAcknowledgement(
      dateKey: current.dateKey,
      counts: FoodCounts(red: 99),
      skin: .skyMeadow,
      generatedAt: now.addingTimeInterval(-1),
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: false,
      resetThroughSequence: nil
    )

    XCTAssertTrue(try applier.apply(current))
    XCTAssertFalse(try applier.apply(duplicate))
    XCTAssertFalse(try applier.apply(stale))
    XCTAssertEqual(reloadCount, 1)
  }

  func testAcknowledgementApplyRetriesOneTransientPersistenceFailure() throws {
    var attempts = 0
    var reloadedKinds: [String] = []
    let applier = FoodWatchAcknowledgementApplier(
      persist: { _ in
        attempts += 1
        if attempts == 1 {
          throw CocoaError(.fileWriteUnknown)
        }
        return true
      },
      reloadTimeline: { reloadedKinds.append($0) }
    )

    XCTAssertTrue(try applier.apply(acknowledgement(counts: .init(green: 1))))
    XCTAssertEqual(attempts, 2)
    XCTAssertEqual(reloadedKinds, [FoodWatchConstants.widgetKind])
  }

  func testPersistentAcknowledgementApplyFailureReloadsUnavailableState() {
    var attempts = 0
    var reloadedKinds: [String] = []
    let applier = FoodWatchAcknowledgementApplier(
      persist: { _ in
        attempts += 1
        throw CocoaError(.fileWriteOutOfSpace)
      },
      reloadTimeline: { reloadedKinds.append($0) }
    )

    XCTAssertThrowsError(
      try applier.apply(acknowledgement(counts: .init(red: 1)))
    )
    XCTAssertEqual(attempts, 2)
    XCTAssertEqual(reloadedKinds, [FoodWatchConstants.widgetKind])
  }

  func testSuspendedIncomingDeliveryWaitsForMainActorApplyToFinish() {
    let tracker = FoodWatchIncomingDeliveryTracker()
    let requiredGeneration = tracker.completionRequirementForNewTask()

    let generation = tracker.begin()
    XCTAssertFalse(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: false
      )
    )
    tracker.finish(generation: generation)

    XCTAssertTrue(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: false
      )
    )
  }

  func testMultipleIncomingDeliveriesCompleteOnlyAfterTheLastApply() {
    let tracker = FoodWatchIncomingDeliveryTracker()
    let requiredGeneration = tracker.completionRequirementForNewTask()

    let first = tracker.begin()
    let second = tracker.begin()
    tracker.finish(generation: first)
    XCTAssertFalse(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: false
      )
    )
    tracker.finish(generation: second)

    XCTAssertTrue(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: false
      )
    )
    XCTAssertFalse(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: true
      )
    )
  }

  func testIncomingDeliveryRechecksAfterPendingContentDrains() {
    let tracker = FoodWatchIncomingDeliveryTracker()
    let requiredGeneration = tracker.completionRequirementForNewTask()
    let generation = tracker.begin()
    tracker.finish(generation: generation)

    XCTAssertFalse(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: true
      )
    )
    XCTAssertTrue(
      tracker.canComplete(
        requiredGeneration: requiredGeneration,
        hasContentPending: false
      )
    )
  }

  private func entry(
    id: UUID,
    color: FoodColor,
    delta: Int = 1
  ) -> FoodWidgetLedgerEntry {
    FoodWidgetLedgerEntry(
      id: id,
      timestamp: now,
      dateKey: FoodDateKey.string(for: now),
      color: color,
      delta: delta
    )
  }

  private func acknowledgement(
    counts: FoodCounts
  ) -> FoodWatchAcknowledgement {
    FoodWatchAcknowledgement(
      dateKey: FoodDateKey.string(for: now),
      counts: counts,
      skin: .skyMeadow,
      generatedAt: now,
      committedEventIDs: [],
      committedThroughSequence: 0,
      isReset: false,
      resetThroughSequence: nil
    )
  }
}
