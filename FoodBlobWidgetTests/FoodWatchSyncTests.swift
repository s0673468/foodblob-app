import XCTest

@testable import FoodBlob

final class FoodWatchSyncTests: XCTestCase {
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

  func testReceiptStoreStagesAndCommitsOnlyItsWatchEntries() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let watchEntry = entry(color: .green)
    let phoneWidgetEntry = entry(color: .yellow)
    let watchEvent = FoodWatchTransferEvent(sequence: 1, entry: watchEntry)

    XCTAssertEqual(try receipts.stage(watchEvent), .staged)
    XCTAssertEqual(try receipts.stage(watchEvent), .alreadyPending)
    try receipts.commit([phoneWidgetEntry, watchEntry])

    XCTAssertTrue(try receipts.pendingEntries().isEmpty)
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
    XCTAssertTrue(try receipts.committedEventIDs().isEmpty)
    XCTAssertEqual(try receipts.stage(watchEvent), .alreadyCommitted)
  }

  func testAcknowledgementStateProjectsOneReceiptDocument() throws {
    let senderID = UUID()
    let resetGeneration = UUID()
    let committedID = UUID()
    var document = FoodWatchReceiptDocument()
    document.committedReceipts = [
      FoodWatchCommittedReceipt(
        sequence: 3,
        id: committedID,
        senderID: senderID
      )
    ]
    document.committedThroughSequence = 1
    document.activeSenderID = senderID
    document.resetThroughSequence = 7
    document.resetGeneration = resetGeneration
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    try encoder.encode(document).write(
      to: directory.appendingPathComponent(FoodBlobConstants.watchReceiptFileName)
    )

    let state = try FoodWatchReceiptStore(containerURL: directory)
      .acknowledgementState()

    XCTAssertEqual(state.committedEventIDs, [committedID])
    XCTAssertEqual(state.committedThroughSequence, 1)
    XCTAssertEqual(state.committedThroughSequencesBySender, [senderID: 1])
    XCTAssertEqual(state.senderID, senderID)
    XCTAssertFalse(state.isReset)
    XCTAssertEqual(state.resetThroughSequence, 7)
    XCTAssertEqual(state.resetGeneration, resetGeneration)
    XCTAssertFalse(state.isResetStagedButNotReady)
  }

  func testAcknowledgementStateTracksResetCommitPhases() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)

    try receipts.reset(at: now)
    let staged = try receipts.acknowledgementState()
    XCTAssertTrue(staged.isResetStagedButNotReady)
    XCTAssertFalse(staged.isReset)

    try receipts.markResetReady()
    let ready = try receipts.acknowledgementState()
    XCTAssertFalse(ready.isResetStagedButNotReady)
    XCTAssertTrue(ready.isReset)
    XCTAssertEqual(ready.resetThroughSequence, staged.resetThroughSequence)
    XCTAssertEqual(ready.resetGeneration, staged.resetGeneration)

    try receipts.clearResetPending()
    let cleared = try receipts.acknowledgementState()
    XCTAssertFalse(cleared.isResetStagedButNotReady)
    XCTAssertFalse(cleared.isReset)
    XCTAssertEqual(cleared.resetThroughSequence, staged.resetThroughSequence)
    XCTAssertEqual(cleared.resetGeneration, staged.resetGeneration)
  }

  func testAcknowledgementStateFailsClosedForUnreadableReceipts() throws {
    try FileManager.default.createDirectory(
      at: directory.appendingPathComponent(FoodBlobConstants.watchReceiptFileName),
      withIntermediateDirectories: true
    )

    XCTAssertThrowsError(
      try FoodWatchReceiptStore(containerURL: directory).acknowledgementState()
    ) { error in
      XCTAssertEqual(error as? FoodWatchReceiptStoreError, .unreadable)
    }
  }

  @MainActor
  func testPhoneCommitMakesWatchDeliveryExactlyOnceAcrossReloads() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let action = entry(color: .red)
    try receipts.stage(FoodWatchTransferEvent(sequence: 1, entry: action))
    try persistence.appendWidgetEntry(action)

    var committed: [[UUID]] = []
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onCommittedWidgetEntries: { entries in
        try receipts.commit(entries)
        committed.append(entries.map(\.id))
      }
    )

    XCTAssertEqual(store.visibleCounts, FoodCounts(red: 1))
    XCTAssertEqual(committed, [[action.id]])
    XCTAssertTrue(try receipts.pendingEntries().isEmpty)

    store.reloadAndIngestWidgetActions()

    XCTAssertEqual(store.visibleCounts, FoodCounts(red: 1))
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
    XCTAssertTrue(try receipts.committedEventIDs().isEmpty)
  }

  @MainActor
  func testDirectPhoneChangesCanRefreshTheWatchSnapshot() throws {
    var publications = 0
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onStatePublished: {
        publications += 1
      }
    )
    publications = 0

    store.increment(.yellow)

    XCTAssertEqual(store.visibleCounts, FoodCounts(yellow: 1))
    XCTAssertEqual(publications, 1)
  }

  func testOutOfOrderCommitKeepsAStableReceiptUntilTheGapArrives() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let first = FoodWatchTransferEvent(sequence: 1, entry: entry(color: .green))
    let third = FoodWatchTransferEvent(sequence: 3, entry: entry(color: .red))
    let second = FoodWatchTransferEvent(sequence: 2, entry: entry(color: .yellow))

    XCTAssertEqual(try receipts.stage(third), .staged)
    try receipts.commit([third.entry])
    XCTAssertEqual(try receipts.committedThroughSequence(), 0)
    XCTAssertEqual(try receipts.stage(third), .alreadyCommitted)

    XCTAssertEqual(try receipts.stage(first), .staged)
    try receipts.commit([first.entry])
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
    XCTAssertEqual(try receipts.committedEventIDs(), [third.entry.id])

    XCTAssertEqual(try receipts.stage(second), .staged)
    try receipts.commit([second.entry])
    XCTAssertEqual(try receipts.committedThroughSequence(), 3)
    XCTAssertTrue(try receipts.committedEventIDs().isEmpty)
    XCTAssertEqual(try receipts.stage(third), .alreadyCommitted)
  }

  func testReadyPrefixReconcilesCommittedGapForTheActiveSender() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()
    let first = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: firstSender
    )
    let other = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .yellow),
      senderID: secondSender
    )
    let next = FoodWatchTransferEvent(
      sequence: 2,
      entry: entry(color: .red),
      senderID: firstSender
    )

    XCTAssertEqual(try receipts.stage(first), .staged)
    XCTAssertEqual(try receipts.stage(other), .staged)
    // Commit sender A while sender B is active. A's receipt is durable, but
    // it is intentionally left outside the active sender's contiguous floor.
    try receipts.commit([first.entry])
    XCTAssertEqual(try receipts.stage(next), .staged)

    XCTAssertEqual(
      try receipts.pendingEventsReadyForIngest().map(\.sequence),
      [2]
    )
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
  }

  func testAlreadyPendingSenderSwitchIsPersisted() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()
    let first = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: firstSender
    )
    let other = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .yellow),
      senderID: secondSender
    )

    XCTAssertEqual(try receipts.stage(first), .staged)
    XCTAssertEqual(try receipts.stage(other), .staged)
    XCTAssertEqual(try receipts.stage(first), .alreadyPending)
    XCTAssertEqual(try receipts.activeSenderID(), firstSender)
  }

  func testResetRejectsLateEventsButAcceptsNewerSequence() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let old = FoodWatchTransferEvent(sequence: 4, entry: entry(color: .green))
    let newerEntry = FoodWidgetLedgerEntry(
      timestamp: now.addingTimeInterval(120),
      dateKey: FoodDateKey.string(for: now.addingTimeInterval(120)),
      color: .red,
      delta: 1
    )
    XCTAssertEqual(try receipts.stage(old), .staged)
    try receipts.commit([old.entry])
    try receipts.reset(at: now.addingTimeInterval(60))
    XCTAssertTrue(try receipts.resetStagedButNotReady())
    let duringReset = FoodWatchTransferEvent(
      sequence: 5,
      entry: FoodWidgetLedgerEntry(
        timestamp: now.addingTimeInterval(120),
        dateKey: FoodDateKey.string(for: now.addingTimeInterval(120)),
        color: .yellow,
        delta: 1
      ),
      senderID: old.senderID
    )
    XCTAssertEqual(try receipts.stage(duringReset), .resetInProgress)
    XCTAssertTrue(try receipts.pendingEntries().isEmpty)
    try receipts.markResetReady()

    XCTAssertTrue(try receipts.resetPending())
    XCTAssertEqual(try receipts.resetThroughSequence(), 4)
    XCTAssertEqual(try receipts.committedThroughSequence(), 0)
    let generation = try XCTUnwrap(receipts.resetGeneration())
    XCTAssertEqual(try receipts.stage(old), .resetInProgress)
    XCTAssertEqual(
      try receipts.stage(
        FoodWatchTransferEvent(
          sequence: 1,
          entry: newerEntry,
          senderID: old.senderID,
          resetGeneration: generation
        )
      ),
      .staged
    )

    try receipts.clearResetPending()
    XCTAssertFalse(try receipts.resetPending())
  }

  func testLatePreResetEventIsRejectedByGenerationAndNewSequenceStartsFresh() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let late = FoodWatchTransferEvent(sequence: 1, entry: entry(color: .green))
    let newerEntry = FoodWidgetLedgerEntry(
      timestamp: now.addingTimeInterval(120),
      dateKey: FoodDateKey.string(for: now.addingTimeInterval(120)),
      color: .red,
      delta: 1
    )
    try receipts.reset(at: now.addingTimeInterval(60))
    try receipts.markResetReady()
    let generation = try XCTUnwrap(receipts.resetGeneration())

    XCTAssertEqual(try receipts.stage(late), .resetInProgress)
    XCTAssertTrue(try receipts.committedEventIDs().isEmpty)

    let current = FoodWatchTransferEvent(
      sequence: 1,
      entry: newerEntry,
      senderID: late.senderID,
      resetGeneration: generation
    )
    XCTAssertEqual(try receipts.stage(current), .staged)
    try receipts.commit([current.entry])
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
    XCTAssertTrue(try receipts.committedEventIDs().isEmpty)
  }

  func testResetBoundaryIsScopedToTheCurrentSender() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()

    for sequence in 1...4 {
      let event = FoodWatchTransferEvent(
        sequence: Int64(sequence),
        entry: entry(color: .green),
        senderID: firstSender
      )
      XCTAssertEqual(try receipts.stage(event), .staged)
      try receipts.commit([event.entry])
    }
    try receipts.reset(at: now)
    try receipts.markResetReady()
    try receipts.clearResetPending()
    let generation = try XCTUnwrap(receipts.resetGeneration())

    for sequence in 1...2 {
      let event = FoodWatchTransferEvent(
        sequence: Int64(sequence),
        entry: entry(color: .yellow),
        senderID: secondSender,
        resetGeneration: generation
      )
      XCTAssertEqual(try receipts.stage(event), .staged)
      try receipts.commit([event.entry])
    }

    try receipts.reset(at: now.addingTimeInterval(1))
    XCTAssertEqual(try receipts.resetThroughSequence(), 2)
    try receipts.markResetReady()

    let next = FoodWatchTransferEvent(
      sequence: 3,
      entry: entry(color: .red),
      senderID: secondSender,
      resetGeneration: try XCTUnwrap(receipts.resetGeneration())
    )
    XCTAssertEqual(try receipts.stage(next), .staged)
  }

  func testReinstalledWatchCanReuseSequenceNumbersWithANewSenderID() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()
    let first = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: firstSender
    )
    let second = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .yellow),
      senderID: secondSender
    )

    XCTAssertEqual(try receipts.stage(first), .staged)
    try receipts.commit([first.entry])
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)

    XCTAssertEqual(try receipts.stage(second), .staged)
    try receipts.commit([second.entry])
    XCTAssertEqual(try receipts.activeSenderID(), secondSender)
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
  }

  func testCommittedOldSenderEventDoesNotStealTheActiveSender() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()
    let first = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: firstSender
    )
    let second = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .yellow),
      senderID: secondSender
    )
    let delayedFirst = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .red),
      senderID: firstSender
    )

    XCTAssertEqual(try receipts.stage(first), .staged)
    try receipts.commit([first.entry])
    XCTAssertEqual(try receipts.stage(second), .staged)
    try receipts.commit([second.entry])

    XCTAssertEqual(try receipts.stage(delayedFirst), .alreadyCommitted)
    XCTAssertTrue(try receipts.pendingEntries().isEmpty)
    XCTAssertEqual(try receipts.activeSenderID(), secondSender)
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
  }

  func testAcknowledgementCarriesACommittedFloorForEverySender() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let firstSender = UUID()
    let secondSender = UUID()
    let first = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: firstSender
    )
    let second = FoodWatchTransferEvent(
      sequence: 2,
      entry: entry(color: .yellow),
      senderID: secondSender
    )

    XCTAssertEqual(try receipts.stage(first), .staged)
    try receipts.commit([first.entry])
    XCTAssertEqual(try receipts.stage(second), .staged)
    XCTAssertEqual(try receipts.stage(first), .alreadyCommitted)

    let state = try receipts.acknowledgementState()
    XCTAssertEqual(state.senderID, secondSender)
    XCTAssertEqual(state.committedThroughSequencesBySender[firstSender], 1)
    XCTAssertEqual(state.committedThroughSequencesBySender[secondSender], 0)
  }

  @MainActor
  func testOutOfOrderWatchEntriesAreIngestedInSequenceOrder() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let persistence = FoodBlobPersistence(containerURL: directory)
    let senderID = UUID()
    let add = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: senderID
    )
    let remove = FoodWatchTransferEvent(
      sequence: 2,
      entry: entry(color: .green, delta: -1),
      senderID: senderID
    )

    XCTAssertEqual(try receipts.stage(remove), .staged)
    XCTAssertTrue(try receipts.pendingEventsReadyForIngest().isEmpty)
    XCTAssertEqual(try receipts.stage(add), .staged)
    let ready = try receipts.pendingEventsReadyForIngest()
    XCTAssertEqual(ready.map(\.sequence), [1, 2])
    for event in ready {
      try persistence.appendWidgetEntryIfNeeded(event.entry)
    }

    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onCommittedWidgetEntries: { entries in
        try receipts.commit(entries)
      }
    )

    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertEqual(try receipts.committedThroughSequence(), 2)
  }

  func testLegacyResetTimestampStillTombstonesLateEvents() throws {
    let senderID = UUID()
    var legacy = FoodWatchReceiptDocument()
    legacy.schemaVersion = 4
    legacy.resetAt = now.addingTimeInterval(60)
    legacy.resetThroughSequence = 0
    legacy.activeSenderID = senderID
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    try encoder.encode(legacy).write(
      to: directory.appendingPathComponent(FoodBlobConstants.watchReceiptFileName)
    )

    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let late = FoodWatchTransferEvent(
      sequence: 1,
      entry: entry(color: .green),
      senderID: senderID
    )

    XCTAssertEqual(try receipts.stage(late), .alreadyCommitted)
    // The tombstone advances the legacy contiguous floor, so a later queued
    // event is no longer stranded behind the rejected sequence 1.
    XCTAssertEqual(try receipts.committedThroughSequence(), 1)
    let next = FoodWatchTransferEvent(
      sequence: 2,
      entry: FoodWidgetLedgerEntry(
        timestamp: now.addingTimeInterval(120),
        dateKey: FoodDateKey.string(for: now.addingTimeInterval(120)),
        color: .red,
        delta: 1
      ),
      senderID: senderID
    )
    XCTAssertEqual(try receipts.stage(next), .staged)
    XCTAssertEqual(
      try receipts.pendingEventsReadyForIngest().map(\.sequence),
      [2]
    )
  }

  func testResetRecoveryRequiresAnEmptyPhoneState() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let snapshot = FoodBlobSnapshot(
      generatedAt: now.addingTimeInterval(1),
      dateKey: FoodDateKey.string(for: now),
      counts: FoodCounts(),
      skin: .skyMeadow
    )

    try receipts.reset(at: now)
    try receipts.recoverReset(using: snapshot, phoneStateIsEmpty: false)
    XCTAssertTrue(try receipts.resetStagedButNotReady())

    try receipts.reset(at: now)
    try receipts.recoverReset(using: snapshot, phoneStateIsEmpty: true)
    XCTAssertTrue(try receipts.resetPending())
  }

  func testWatchTransferContractRoundTripsEventAndAcknowledgement() throws {
    let action = entry(color: .yellow, delta: -1)
    let senderID = UUID()
    let event = FoodWatchTransferEvent(
      sequence: 7,
      entry: action,
      senderID: senderID,
      resetGeneration: UUID()
    )
    let userInfo = try FoodWatchTransferContract.userInfo(for: event)
    let decoded = FoodWatchTransferContract.event(from: userInfo)

    XCTAssertEqual(decoded, event)

    let acknowledgement = FoodWatchTransferContract.acknowledgementUserInfo(
      dateKey: "2027-01-15",
      counts: FoodCounts(green: 2, yellow: 3, red: 1),
      skin: .shrine,
      generatedAt: now.addingTimeInterval(0.123),
      committedEventIDs: [action.id],
      committedThroughSequence: event.sequence,
      committedThroughSequencesBySender: [senderID: event.sequence],
      isReset: true,
      resetThroughSequence: event.sequence,
      senderID: senderID,
      resetGeneration: event.resetGeneration
    )
    let decodedAcknowledgement = FoodWatchTransferContract.acknowledgement(
      from: acknowledgement
    )

    XCTAssertEqual(decodedAcknowledgement?.dateKey, "2027-01-15")
    XCTAssertEqual(
      decodedAcknowledgement?.counts,
      FoodCounts(green: 2, yellow: 3, red: 1)
    )
    XCTAssertEqual(decodedAcknowledgement?.skin, .shrine)
    let decodedGeneratedAt = try XCTUnwrap(decodedAcknowledgement?.generatedAt)
    XCTAssertLessThan(
      abs(decodedGeneratedAt.timeIntervalSince(now.addingTimeInterval(0.123))),
      0.001
    )
    XCTAssertEqual(decodedAcknowledgement?.committedEventIDs, [action.id])
    XCTAssertEqual(decodedAcknowledgement?.committedThroughSequence, 7)
    XCTAssertEqual(
      decodedAcknowledgement?.committedThroughSequencesBySender,
      [senderID: 7]
    )
    XCTAssertTrue(decodedAcknowledgement?.isReset == true)
    XCTAssertEqual(decodedAcknowledgement?.resetThroughSequence, 7)
    XCTAssertEqual(decodedAcknowledgement?.senderID, senderID)
    XCTAssertEqual(decodedAcknowledgement?.resetGeneration, event.resetGeneration)
  }

  func testWatchRefreshRequestHasADistinctTransferContract() {
    let request = FoodWatchTransferContract.stateRefreshRequestUserInfo()

    XCTAssertTrue(FoodWatchTransferContract.isStateRefreshRequest(request))
    XCTAssertNil(FoodWatchTransferContract.event(from: request))
    XCTAssertFalse(
      FoodWatchTransferContract.isStateRefreshRequest([
        "unrelated": true
      ])
    )
  }

  func testAcknowledgementPayloadEqualityIgnoresOnlyGenerationTime() {
    let eventID = UUID()
    let senderID = UUID()
    let resetGeneration = UUID()
    let base = FoodWatchAcknowledgement(
      dateKey: "2027-01-15",
      counts: FoodCounts(green: 2, yellow: 1),
      skin: .shrine,
      generatedAt: now,
      committedEventIDs: [eventID],
      committedThroughSequence: 4,
      committedThroughSequencesBySender: [senderID: 4],
      isReset: false,
      resetThroughSequence: nil,
      senderID: senderID,
      resetGeneration: resetGeneration
    )
    let republished = FoodWatchAcknowledgement(
      dateKey: base.dateKey,
      counts: base.counts,
      skin: base.skin,
      generatedAt: now.addingTimeInterval(60),
      committedEventIDs: base.committedEventIDs,
      committedThroughSequence: base.committedThroughSequence,
      committedThroughSequencesBySender:
        base.committedThroughSequencesBySender,
      isReset: base.isReset,
      resetThroughSequence: base.resetThroughSequence,
      senderID: base.senderID,
      resetGeneration: base.resetGeneration
    )
    let changedCounts = FoodWatchAcknowledgement(
      dateKey: base.dateKey,
      counts: FoodCounts(green: 3, yellow: 1),
      skin: base.skin,
      generatedAt: republished.generatedAt,
      committedEventIDs: base.committedEventIDs,
      committedThroughSequence: base.committedThroughSequence,
      committedThroughSequencesBySender:
        base.committedThroughSequencesBySender,
      isReset: base.isReset,
      resetThroughSequence: base.resetThroughSequence,
      senderID: base.senderID,
      resetGeneration: base.resetGeneration
    )

    XCTAssertTrue(base.hasSamePayload(as: republished))
    XCTAssertFalse(base.hasSamePayload(as: changedCounts))
  }

  func testWatchDeliveryUsesInteractiveRouteOnlyWhenReachable() {
    XCTAssertEqual(
      FoodWatchDeliveryPolicy.route(isReachable: true),
      .interactive
    )
    XCTAssertEqual(
      FoodWatchDeliveryPolicy.route(isReachable: false),
      .background
    )
    XCTAssertTrue(
      FoodWatchDeliveryPolicy.shouldSendComplication(isEnabled: true)
    )
    XCTAssertFalse(
      FoodWatchDeliveryPolicy.shouldSendComplication(isEnabled: false)
    )
    XCTAssertEqual(
      FoodWatchDeliveryPolicy.stateTransferRoute(isComplicationEnabled: true),
      .currentComplication
    )
    XCTAssertEqual(
      FoodWatchDeliveryPolicy.stateTransferRoute(isComplicationEnabled: false),
      .backgroundUserInfo
    )
    XCTAssertTrue(
      FoodWatchDeliveryPolicy.shouldRetryPendingEvents(
        resetStagedButNotReady: false
      )
    )
    XCTAssertFalse(
      FoodWatchDeliveryPolicy.shouldRetryPendingEvents(
        resetStagedButNotReady: true
      )
    )
    XCTAssertFalse(
      FoodWatchDeliveryPolicy.shouldRetryInteractiveMessage(
        replyAccepted: false,
        durableFallbackQueued: true
      )
    )
    XCTAssertTrue(
      FoodWatchDeliveryPolicy.shouldRetryInteractiveMessage(
        replyAccepted: false,
        durableFallbackQueued: false
      )
    )
    XCTAssertTrue(
      FoodWatchDeliveryPolicy.shouldRetryStateRefresh(
        transferFailed: true,
        hasQueuedRefresh: false,
        retriesRemaining: 1
      )
    )
    XCTAssertFalse(
      FoodWatchDeliveryPolicy.shouldRetryStateRefresh(
        transferFailed: true,
        hasQueuedRefresh: true,
        retriesRemaining: 1
      )
    )
    XCTAssertFalse(
      FoodWatchDeliveryPolicy.shouldRetryStateRefresh(
        transferFailed: true,
        hasQueuedRefresh: false,
        retriesRemaining: 0
      )
    )
  }

  func testAcknowledgementDeliveryStaysPendingUntilItsCallbackSucceeds() {
    var tracker = FoodWatchAcknowledgementDeliveryTracker()
    let generation = tracker.markPending()
    let deliveryID = UUID()

    XCTAssertEqual(tracker.generationToQueue(), generation)
    tracker.queued(deliveryID: deliveryID, generation: generation)
    XCTAssertNil(tracker.generationToQueue())
    XCTAssertTrue(tracker.hasPendingDelivery)

    XCTAssertEqual(
      tracker.completed(deliveryID: deliveryID, succeeded: true),
      .confirmed(generation)
    )
    XCTAssertFalse(tracker.hasPendingDelivery)
    XCTAssertNil(tracker.generationToQueue())
  }

  func testAcknowledgementDeliveryFailureRetriesOnceThenRemainsPending() {
    var tracker = FoodWatchAcknowledgementDeliveryTracker()
    let generation = tracker.markPending()
    let firstDeliveryID = UUID()
    tracker.queued(deliveryID: firstDeliveryID, generation: generation)

    XCTAssertEqual(
      tracker.completed(deliveryID: firstDeliveryID, succeeded: false),
      .retry(generation)
    )
    XCTAssertEqual(tracker.generationToQueue(), generation)

    let retryDeliveryID = UUID()
    tracker.queued(deliveryID: retryDeliveryID, generation: generation)
    XCTAssertEqual(
      tracker.completed(deliveryID: retryDeliveryID, succeeded: false),
      .pending(generation)
    )
    XCTAssertTrue(tracker.hasPendingDelivery)
    XCTAssertEqual(tracker.generationToQueue(), generation)
  }

  func testAcknowledgementDeliveryIgnoresSupersededAndDuplicateCallbacks() {
    var tracker = FoodWatchAcknowledgementDeliveryTracker()
    let oldGeneration = tracker.markPending()
    let oldDeliveryID = UUID()
    tracker.queued(deliveryID: oldDeliveryID, generation: oldGeneration)
    let currentGeneration = tracker.markPending()
    let currentDeliveryID = UUID()
    tracker.queued(deliveryID: currentDeliveryID, generation: currentGeneration)

    XCTAssertEqual(
      tracker.completed(deliveryID: oldDeliveryID, succeeded: true),
      .ignored
    )
    XCTAssertTrue(tracker.hasPendingDelivery)
    XCTAssertEqual(
      tracker.completed(deliveryID: currentDeliveryID, succeeded: true),
      .confirmed(currentGeneration)
    )
    XCTAssertEqual(
      tracker.completed(deliveryID: currentDeliveryID, succeeded: true),
      .ignored
    )
  }

  func testAcknowledgementDeliveryIdentifierRoundTripsBesideThePayload() {
    let deliveryID = UUID()
    let context = FoodWatchTransferContract.acknowledgementUserInfo(
      dateKey: FoodDateKey.string(for: now),
      counts: FoodCounts(green: 1),
      skin: .skyMeadow,
      generatedAt: now,
      committedEventIDs: [],
      committedThroughSequence: 0
    )
    let tagged = FoodWatchTransferContract.tagAcknowledgementDelivery(
      context,
      deliveryID: deliveryID
    )

    XCTAssertEqual(
      FoodWatchTransferContract.acknowledgementDeliveryID(from: tagged),
      deliveryID
    )
    XCTAssertNotNil(FoodWatchTransferContract.acknowledgement(from: tagged))
  }

  @MainActor
  func testSuspendedWidgetActionHookIngestsDurableLedgerExactlyOnce() async throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let action = entry(color: .green)
    var publications = 0
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onStatePublished: {
        publications += 1
      }
    )
    let baselinePublications = publications
    FoodWidgetActionRuntime.install {
      store.reloadAndIngestWidgetActions()
    }
    defer { FoodWidgetActionRuntime.install(nil) }

    try persistence.appendWidgetEntry(action)
    await FoodWidgetActionRuntime.notifyCommittedAction()
    await FoodWidgetActionRuntime.notifyCommittedAction()

    XCTAssertEqual(store.visibleCounts, FoodCounts(green: 1))
    XCTAssertEqual(publications, baselinePublications + 1)
    XCTAssertTrue(persistence.pendingWidgetEntries().isEmpty)
  }

  @MainActor
  func testSuspendedWidgetActionWaitsForDelayedSessionActivation() async {
    let gate = FoodWatchActivationGate()
    gate.prepareForActivation()
    let waiter = Task { @MainActor in
      await gate.waitForActivation(timeoutNanoseconds: 1_000_000_000)
    }

    await Task.yield()
    XCTAssertTrue(gate.hasWaiters)
    gate.resolve(true)

    let didActivate = await waiter.value
    XCTAssertTrue(didActivate)
    XCTAssertFalse(gate.hasWaiters)
  }

  @MainActor
  func testSuspendedWidgetActionActivationWaitIsBounded() async {
    let gate = FoodWatchActivationGate()
    gate.prepareForActivation()

    let activated = await gate.waitForActivation(timeoutNanoseconds: 1_000_000)

    XCTAssertFalse(activated)
    XCTAssertFalse(gate.hasWaiters)
  }

  func testWatchReceiptRetryDoesNotDuplicateLedgerEntry() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let action = entry(color: .green)

    try persistence.appendWidgetEntry(action)
    _ = try persistence.drainWidgetLedger()
    try persistence.appendWidgetEntryIfNeeded(action)

    XCTAssertEqual(persistence.pendingWidgetEntries(), [action])
  }

  func testWatchReceiptBatchAppendParsesExistingLedgersOnlyOnceAndKeepsOrder() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let first = entry(color: .green)
    let second = entry(color: .yellow)
    let third = entry(color: .red)
    try persistence.appendWidgetEntry(first)
    _ = try persistence.drainWidgetLedger()

    try persistence.appendWidgetEntriesIfNeeded([
      first,
      second,
      second,
      third,
    ])

    XCTAssertEqual(
      persistence.pendingWidgetEntries().map(\.id),
      [first.id, second.id, third.id]
    )
  }

  private func entry(
    color: FoodColor,
    delta: Int = 1
  ) -> FoodWidgetLedgerEntry {
    FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: FoodDateKey.string(for: now),
      color: color,
      delta: delta
    )
  }
}
