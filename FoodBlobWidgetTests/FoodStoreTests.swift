import XCTest

@testable import FoodBlob

@MainActor
final class FoodStoreTests: XCTestCase {
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

  func testWidgetLedgerIsIngestedExactlyOnce() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)
    let action = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: dateKey,
      color: .green,
      delta: 1
    )
    try persistence.appendWidgetEntry(action)

    let first = FoodStore(containerURL: directory, now: { self.now })
    XCTAssertEqual(first.visibleCounts.green, 1)

    let second = FoodStore(containerURL: directory, now: { self.now })
    XCTAssertEqual(second.visibleCounts.green, 1)
  }

  func testMutationOlderThanFullRetentionWindowIsRejectedWithoutUndo() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
    let today = calendar.startOfDay(for: now)
    var document = FoodStateDocument()
    for offset in 0..<FoodStateDocument.maximumHistoryDays {
      let date = try XCTUnwrap(
        calendar.date(byAdding: .day, value: -offset, to: today)
      )
      document.setCounts(
        FoodCounts(green: 1),
        for: FoodDateKey.string(for: date, calendar: calendar),
        updatedAt: now
      )
    }
    let persistence = FoodBlobPersistence(containerURL: directory)
    try persistence.saveDocument(document)
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      calendar: calendar,
      reloadWidgetTimelines: {}
    )
    let tooOld = try XCTUnwrap(
      calendar.date(
        byAdding: .day,
        value: -FoodStateDocument.maximumHistoryDays,
        to: today
      )
    )

    let changed = store.increment(.red, on: tooOld)

    XCTAssertFalse(changed)
    XCTAssertEqual(store.history.count, FoodStateDocument.maximumHistoryDays)
    XCTAssertFalse(store.canUndo(on: tooOld))
    XCTAssertFalse(
      store.history.contains {
        $0.dateKey == FoodDateKey.string(for: tooOld, calendar: calendar)
      }
    )
  }

  func testCountsForTodayDoNotChangeTheDateBeingEdited() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: 0))
    let yesterday = try XCTUnwrap(
      calendar.date(byAdding: .day, value: -1, to: now)
    )
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      calendar: calendar,
      reloadWidgetTimelines: {}
    )
    store.increment(.green, on: now)
    store.increment(.red, on: yesterday)
    store.selectDate(yesterday)

    XCTAssertEqual(store.counts(on: now), FoodCounts(green: 1))
    XCTAssertEqual(store.visibleCounts, FoodCounts(red: 1))
    XCTAssertTrue(calendar.isDate(store.selectedDate, inSameDayAs: yesterday))
  }

  func testRepeatedReloadWithoutWidgetFilesDoesNotRepublishSnapshot() throws {
    var currentNow = now
    let persistence = FoodBlobPersistence(containerURL: directory)
    let store = FoodStore(containerURL: directory, now: { currentNow })
    let firstGeneratedAt = try XCTUnwrap(
      persistence.readSnapshot()?.generatedAt
    )
    currentNow = now.addingTimeInterval(60)

    store.reloadAndIngestWidgetActions()

    XCTAssertEqual(
      try XCTUnwrap(persistence.readSnapshot()).generatedAt,
      firstGeneratedAt
    )
  }

  func testSecondColdStartReusesCurrentSnapshotWithoutReloadingWidgets() throws {
    var currentNow = now
    let persistence = FoodBlobPersistence(containerURL: directory)
    let first = FoodStore(containerURL: directory, now: { currentNow })
    first.increment(.green, on: currentNow)
    let firstGeneratedAt = try XCTUnwrap(
      persistence.readSnapshot()?.generatedAt
    )
    currentNow = now.addingTimeInterval(60)
    var reloadCount = 0

    let reopened = FoodStore(
      containerURL: directory,
      now: { currentNow },
      reloadWidgetTimelines: { reloadCount += 1 }
    )

    XCTAssertEqual(reopened.visibleCounts.green, 1)
    XCTAssertEqual(reloadCount, 0)
    XCTAssertEqual(
      try XCTUnwrap(persistence.readSnapshot()).generatedAt,
      firstGeneratedAt
    )
  }

  func testColdStartRepublishesSnapshotForANewCalendarDay() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    var currentNow = now
    let persistence = FoodBlobPersistence(containerURL: directory)
    _ = FoodStore(
      containerURL: directory,
      now: { currentNow },
      calendar: calendar
    )
    currentNow = try XCTUnwrap(
      calendar.date(byAdding: .day, value: 1, to: now)
    )
    var reloadCount = 0

    _ = FoodStore(
      containerURL: directory,
      now: { currentNow },
      calendar: calendar,
      reloadWidgetTimelines: { reloadCount += 1 }
    )

    XCTAssertEqual(reloadCount, 1)
    XCTAssertEqual(
      try XCTUnwrap(persistence.readSnapshot()).dateKey,
      FoodDateKey.string(for: currentNow, calendar: calendar)
    )
  }

  func testReloadAfterInitializationIngestsANewWidgetEntry() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let store = FoodStore(containerURL: directory, now: { self.now })
    try persistence.appendWidgetEntry(
      FoodWidgetLedgerEntry(
        timestamp: now,
        dateKey: FoodDateKey.string(for: now),
        color: .yellow,
        delta: 1
      )
    )

    store.reloadAndIngestWidgetActions()

    XCTAssertEqual(store.visibleCounts.yellow, 1)
  }

  func testPublishedSnapshotDoesNotDoubleCountASurvivingConsumedEntry() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)
    let action = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: dateKey,
      color: .green,
      delta: 1
    )
    try persistence.appendWidgetEntry(action)

    var document = FoodStateDocument()
    document.setCounts(
      FoodCounts(green: 1),
      for: dateKey,
      updatedAt: now
    )
    document.addConsumedWidgetIDs([action.id])
    try persistence.saveDocument(document)
    try persistence.publishSnapshot(from: document, now: now)
    // Simulates the crash point before the ledger/claim can be cleared.

    let widgetState = persistence.currentWidgetState(at: now)

    XCTAssertEqual(widgetState.counts.green, 1)
  }

  func testWidgetStateAppliesPendingEntriesOnlyToTheRequestedDay() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let yesterday = now.addingTimeInterval(-86_400)
    try persistence.appendWidgetEntry(
      FoodWidgetLedgerEntry(
        timestamp: now.addingTimeInterval(-2),
        dateKey: FoodDateKey.string(for: now),
        color: .red,
        delta: 1
      )
    )
    try persistence.appendWidgetEntry(
      FoodWidgetLedgerEntry(
        timestamp: now.addingTimeInterval(-1),
        dateKey: FoodDateKey.string(for: yesterday),
        color: .yellow,
        delta: 1
      )
    )

    let widgetState = persistence.currentWidgetState(at: now)

    XCTAssertEqual(widgetState.counts.red, 1)
    XCTAssertEqual(widgetState.counts.yellow, 0)
  }

  func testNextDayWidgetStateResetsCountsWithoutAnotherDiskLoad() {
    let state = FoodWidgetState(
      date: now,
      counts: FoodCounts(green: 5, yellow: 2, red: 1),
      skin: .shrine,
      widgetLayout: .blobStage,
      isAvailable: true
    )
    let midnight = now.addingTimeInterval(86_400)

    let next = state.resettingCounts(at: midnight)

    XCTAssertEqual(next.date, midnight)
    XCTAssertEqual(next.counts, FoodCounts())
    XCTAssertEqual(next.skin, .shrine)
    XCTAssertEqual(next.widgetLayout, .blobStage)
    XCTAssertTrue(next.isAvailable)
  }

  func testSurvivingClaimWithMoreThanConsumedIDLimitIsNeverReplayed() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)
    let actions = (0..<301).map { _ in
      FoodWidgetLedgerEntry(
        timestamp: now,
        dateKey: dateKey,
        color: .green,
        delta: 1
      )
    }
    var document = FoodStateDocument()
    document.setCounts(
      FoodCounts(green: actions.count),
      for: dateKey,
      updatedAt: now
    )
    document.addConsumedWidgetIDs(actions.map(\.id))
    try persistence.saveDocument(document)
    try persistence.publishSnapshot(from: document, now: now)
    let claim = try actions.map { try $0.jsonLine() }.joined(separator: "\n") + "\n"
    try Data(claim.utf8).write(
      to: directory.appendingPathComponent(FoodBlobConstants.claimFileName)
    )

    let recovered = FoodStore(containerURL: directory, now: { self.now })

    XCTAssertEqual(recovered.visibleCounts.green, actions.count)
    XCTAssertTrue(try persistence.loadDocument().consumedWidgetIDs.isEmpty)
  }

  func testCorruptStateIsPreservedAndBlocksEveryMutation() throws {
    let stateURL = directory.appendingPathComponent(
      FoodBlobConstants.stateFileName
    )
    let original = Data("not-json".utf8)
    try original.write(to: stateURL)

    let store = FoodStore(containerURL: directory, now: { self.now })
    store.increment(.green, on: now)
    store.deleteAll()

    XCTAssertNotNil(store.lastError)
    XCTAssertEqual(try Data(contentsOf: stateURL), original)
  }

  func testNewerSchemaStateIsPreservedAndBlocksWidgetIngestion() throws {
    let stateURL = directory.appendingPathComponent(
      FoodBlobConstants.stateFileName
    )
    let original = Data(
      #"{"schema_version":99,"days":[],"selected_skin":"bubble_pop"}"#.utf8
    )
    try original.write(to: stateURL)
    let persistence = FoodBlobPersistence(containerURL: directory)
    let action = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: FoodDateKey.string(for: now),
      color: .yellow,
      delta: 1
    )
    try persistence.appendWidgetEntry(action)

    let store = FoodStore(containerURL: directory, now: { self.now })

    XCTAssertNotNil(store.lastError)
    XCTAssertEqual(try Data(contentsOf: stateURL), original)
    XCTAssertEqual(
      persistence.pendingWidgetEntries().map(\.id),
      [action.id]
    )
  }

  func testUnreadableStatePathIsPreservedAndBlocksMutation() throws {
    let stateURL = directory.appendingPathComponent(
      FoodBlobConstants.stateFileName,
      isDirectory: true
    )
    try FileManager.default.createDirectory(
      at: stateURL,
      withIntermediateDirectories: true
    )

    let store = FoodStore(containerURL: directory, now: { self.now })
    store.increment(.red, on: now)

    var isDirectory: ObjCBool = false
    XCTAssertNotNil(store.lastError)
    XCTAssertTrue(
      FileManager.default.fileExists(
        atPath: stateURL.path,
        isDirectory: &isDirectory
      )
    )
    XCTAssertTrue(isDirectory.boolValue)
  }

  func testFailedMutationDoesNotPublishOrExportPhantomState() throws {
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {}
    )
    let stateURL = directory.appendingPathComponent(
      FoodBlobConstants.stateFileName
    )
    if FileManager.default.fileExists(atPath: stateURL.path) {
      try FileManager.default.removeItem(at: stateURL)
    }
    try FileManager.default.createDirectory(
      at: stateURL,
      withIntermediateDirectories: true
    )

    let changed = store.increment(.green, on: now)
    let exported = try JSONDecoder().decode(
      FoodStateDocument.self,
      from: store.exportData()
    )

    XCTAssertFalse(changed)
    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertTrue(store.history.isEmpty)
    XCTAssertTrue(exported.days.isEmpty)
    XCTAssertTrue(exported.undoStack.isEmpty)
    XCTAssertNotNil(store.lastError)
  }

  func testUndoRestoresThePreviousCount() {
    let store = FoodStore(containerURL: directory, now: { self.now })

    store.increment(.yellow, on: now)
    XCTAssertEqual(store.visibleCounts.yellow, 1)
    XCTAssertTrue(store.canUndo)

    store.undo()

    XCTAssertEqual(store.visibleCounts.yellow, 0)
    XCTAssertFalse(store.canUndo)
  }

  func testUndoPreservesAnInterleavedWidgetIncrement() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let dateKey = FoodDateKey.string(for: now)
    let first = FoodStore(containerURL: directory, now: { self.now })
    first.increment(.green, on: now)
    try persistence.appendWidgetEntry(
      FoodWidgetLedgerEntry(
        timestamp: now.addingTimeInterval(1),
        dateKey: dateKey,
        color: .green,
        delta: 1
      )
    )

    let reopened = FoodStore(containerURL: directory, now: { self.now })
    XCTAssertEqual(reopened.visibleCounts.green, 2)

    reopened.undo()

    XCTAssertEqual(reopened.visibleCounts.green, 1)
  }

  func testLegacyPreviousValueUndoIsDroppedWithoutChangingCounts() throws {
    let legacy = Data(
      #"{"schema_version":1,"days":[{"dateKey":"2027-01-15","counts":{"green":2,"yellow":0,"red":0},"updatedAt":"2027-01-15T08:00:00Z"}],"undo_stack":[{"dateKey":"2027-01-15","color":"green","previousValue":0}]}"#
        .utf8
    )
    try legacy.write(
      to: directory.appendingPathComponent(FoodBlobConstants.stateFileName)
    )

    let store = FoodStore(containerURL: directory, now: { self.now })

    XCTAssertNil(store.lastError)
    XCTAssertEqual(store.visibleCounts.green, 2)
    XCTAssertFalse(store.canUndo)
  }

  func testUndoOfADecrementRestoresTheCount() {
    let store = FoodStore(containerURL: directory, now: { self.now })
    store.increment(.red, on: now)
    store.decrement(.red, on: now)

    XCTAssertEqual(store.visibleCounts.red, 0)

    store.undo()

    XCTAssertEqual(store.visibleCounts.red, 1)
  }

  func testUndoCanBeScopedToOneDay() {
    var changingCalendar = Calendar(identifier: .gregorian)
    changingCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let yesterday = changingCalendar.date(
      byAdding: .day,
      value: -1,
      to: now
    )!
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      calendar: changingCalendar
    )
    store.increment(.green, on: yesterday)
    store.increment(.red, on: now)

    XCTAssertTrue(store.canUndo(on: yesterday))
    XCTAssertTrue(store.canUndo(on: now))

    store.undo(on: yesterday)

    store.selectDate(yesterday)
    XCTAssertEqual(store.visibleCounts.green, 0)
    XCTAssertFalse(store.canUndo(on: yesterday))
    XCTAssertTrue(store.canUndo(on: now))
    XCTAssertTrue(store.canUndo)
  }

  func testCalendarProviderIsReevaluatedAfterTimezoneChange() {
    let boundary = Date(timeIntervalSince1970: 1_800_061_200)
    var changingCalendar = Calendar(identifier: .gregorian)
    changingCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let store = FoodStore(
      containerURL: directory,
      now: { boundary },
      calendarProvider: { changingCalendar }
    )
    store.increment(.green, on: boundary)
    let utcDateKey = FoodDateKey.string(
      for: boundary,
      calendar: changingCalendar
    )

    changingCalendar.timeZone = TimeZone(secondsFromGMT: -8 * 60 * 60)!
    let shiftedDateKey = FoodDateKey.string(
      for: boundary,
      calendar: changingCalendar
    )
    store.increment(.red, on: boundary)

    XCTAssertNotEqual(utcDateKey, shiftedDateKey)
    XCTAssertEqual(
      store.history.first(where: { $0.dateKey == utcDateKey })?.counts.green,
      1
    )
    XCTAssertEqual(
      store.history.first(where: { $0.dateKey == shiftedDateKey })?.counts.red,
      1
    )
  }

  func testHistorySurvivesAColdStart() {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = .current
    let yesterday = calendar.date(byAdding: .day, value: -1, to: now)!
    let first = FoodStore(containerURL: directory, now: { self.now })

    first.increment(.red, on: yesterday)
    let second = FoodStore(containerURL: directory, now: { self.now })

    XCTAssertEqual(second.selectedSkin, .skyMeadow)
    XCTAssertEqual(second.history.count, 1)
    XCTAssertEqual(second.history.first?.counts.red, 1)
  }

  func testLegacyStateWithoutWidgetLayoutPreservesPopColumns() throws {
    let legacy = Data(
      #"{"schema_version":1,"days":[],"selected_skin":"bubble_pop"}"#.utf8
    )
    try legacy.write(
      to: directory.appendingPathComponent(FoodBlobConstants.stateFileName)
    )

    let store = FoodStore(containerURL: directory, now: { self.now })
    let widgetState = FoodBlobPersistence(containerURL: directory)
      .currentWidgetState(at: now)

    XCTAssertEqual(store.selectedWidgetLayout, .popColumns)
    XCTAssertEqual(widgetState.widgetLayout, .popColumns)
  }

  func testEveryWidgetLayoutSurvivesAColdStartAndReachesTheWidgetSnapshot() {
    for layout in WidgetLayoutID.allCases {
      let first = FoodStore(containerURL: directory, now: { self.now })

      first.setWidgetLayout(layout)
      let second = FoodStore(containerURL: directory, now: { self.now })
      let widgetState = FoodBlobPersistence(containerURL: directory)
        .currentWidgetState(at: now)

      XCTAssertEqual(second.selectedWidgetLayout, layout)
      XCTAssertEqual(widgetState.widgetLayout, layout)
    }
  }

  func testDeleteAllClearsHistoryAndKeepsAppearancePreferences() {
    let store = FoodStore(containerURL: directory, now: { self.now })
    store.setSkin(.shrine)
    store.setWidgetLayout(.blobStage)
    store.increment(.green, on: now)
    store.deleteAll()

    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertTrue(store.history.isEmpty)
    XCTAssertEqual(store.selectedSkin, .shrine)
    XCTAssertEqual(store.selectedWidgetLayout, .blobStage)
  }

  func testPastDayMutationAndUndoPublishWithoutReloadingTimelines() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    var reloadCount = 0
    let persistence = FoodBlobPersistence(containerURL: directory)
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      calendar: calendar,
      reloadWidgetTimelines: { reloadCount += 1 }
    )
    reloadCount = 0
    let yesterday = try XCTUnwrap(
      calendar.date(byAdding: .day, value: -1, to: now)
    )
    let yesterdayKey = FoodDateKey.string(for: yesterday, calendar: calendar)
    let snapshotURL = directory.appendingPathComponent(
      FoodBlobConstants.snapshotFileName
    )
    try FileManager.default.removeItem(at: snapshotURL)

    store.increment(.green, on: yesterday)

    XCTAssertEqual(reloadCount, 0)
    XCTAssertEqual(
      try persistence.loadDocument().counts(for: yesterdayKey).green,
      1
    )
    XCTAssertNotNil(persistence.readSnapshot())
    try FileManager.default.removeItem(at: snapshotURL)

    store.undo(on: yesterday)

    XCTAssertEqual(reloadCount, 0)
    XCTAssertEqual(
      try persistence.loadDocument().counts(for: yesterdayKey).green,
      0
    )
    XCTAssertNotNil(persistence.readSnapshot())
  }

  func testTodayMutationAndUndoReloadWidgetTimelines() {
    var reloadCount = 0
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: { reloadCount += 1 }
    )
    XCTAssertEqual(reloadCount, 1)
    reloadCount = 0

    store.increment(.yellow, on: now)
    XCTAssertEqual(reloadCount, 1)

    store.undo(on: now)
    XCTAssertEqual(reloadCount, 2)
  }

  func testAppearanceChangesSkipFixedWidgetReloadsButDeleteAllReloads() {
    var reloadCount = 0
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: { reloadCount += 1 }
    )
    reloadCount = 0

    store.setSkin(.shrine)
    XCTAssertEqual(reloadCount, 0)

    store.setWidgetLayout(.blobStage)
    XCTAssertEqual(reloadCount, 0)

    store.deleteAll()
    XCTAssertEqual(reloadCount, 1)
  }

  func testDeleteAllDoesNotErasePhoneStateIfWatchBoundaryCannotBeSaved() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onBeforeDeleteAllData: {
        throw NSError(domain: "FoodWatchReceiptStore", code: 1)
      }
    )
    store.increment(.green, on: now)

    store.deleteAll()

    XCTAssertEqual(store.visibleCounts, FoodCounts(green: 1))
    XCTAssertEqual(
      try persistence.loadDocument().counts(for: FoodDateKey.string(for: now)),
      FoodCounts(green: 1)
    )
    XCTAssertNotNil(store.lastError)
  }

  func testDeleteAllFailureCancelsStagedWatchResetAndKeepsPendingActions() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let action = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: FoodDateKey.string(for: now),
      color: .green,
      delta: 1
    )
    try receipts.stage(FoodWatchTransferEvent(sequence: 1, entry: action))

    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onBeforeDeleteAllData: {
        try receipts.reset(at: self.now)
      },
      onAfterDeleteAllData: {
        try? receipts.markResetReady()
      },
      onDeleteAllDataFailed: {
        try? receipts.cancelReset()
      }
    )
    store.increment(.red, on: now)
    try persistence.appendWidgetEntry(action)

    let snapshotURL = directory.appendingPathComponent(
      FoodBlobConstants.snapshotFileName
    )
    try FileManager.default.removeItem(at: snapshotURL)
    try FileManager.default.createDirectory(
      at: snapshotURL,
      withIntermediateDirectories: true
    )

    store.deleteAll()

    XCTAssertEqual(store.visibleCounts, FoodCounts(red: 1))
    XCTAssertEqual(
      try persistence.loadDocument().counts(for: FoodDateKey.string(for: now)),
      FoodCounts(red: 1)
    )
    XCTAssertEqual(persistence.pendingWidgetEntries(), [action])
    XCTAssertFalse(try receipts.resetPending())
    XCTAssertFalse(try receipts.resetStagedButNotReady())
    XCTAssertEqual(try receipts.pendingEntries(), [action])
    XCTAssertNotNil(store.lastError)
  }

  @MainActor
  func testDeleteAllKeepsMutationsBlockedWhenWatchResetCommitFails() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onBeforeDeleteAllData: {
        try receipts.reset(at: self.now)
      },
      onAfterDeleteAllData: {
        throw NSError(domain: "FoodWatchReceiptStore", code: 2)
      }
    )

    store.increment(.red, on: now)
    store.deleteAll()

    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertTrue(try receipts.resetStagedButNotReady())
    store.increment(.green, on: now)
    store.reloadAndIngestWidgetActions()
    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertNotNil(store.lastError)
  }

  @MainActor
  func testDeleteAllCanRetryAfterAResetCommitFailure() throws {
    let receipts = FoodWatchReceiptStore(containerURL: directory)
    var shouldFailCommit = true
    let store = FoodStore(
      containerURL: directory,
      now: { self.now },
      reloadWidgetTimelines: {},
      onBeforeDeleteAllData: {
        try receipts.reset(at: self.now)
      },
      onAfterDeleteAllData: {
        if shouldFailCommit {
          shouldFailCommit = false
          throw NSError(domain: "FoodWatchReceiptStore", code: 3)
        }
        try receipts.markResetReady()
      }
    )

    store.increment(.red, on: now)
    store.deleteAll()
    XCTAssertTrue(try receipts.resetStagedButNotReady())

    store.deleteAll()

    XCTAssertEqual(store.visibleCounts, FoodCounts())
    XCTAssertTrue(try receipts.resetPending())
    store.increment(.green, on: now)
    XCTAssertEqual(store.visibleCounts, FoodCounts(green: 1))
  }

  func testResetRollbackMergesFreshAppendOnlyLedgerData() throws {
    let first = FoodWidgetLedgerEntry(
      timestamp: now,
      dateKey: FoodDateKey.string(for: now),
      color: .green,
      delta: 1
    )
    let second = FoodWidgetLedgerEntry(
      timestamp: now.addingTimeInterval(1),
      dateKey: FoodDateKey.string(for: now.addingTimeInterval(1)),
      color: .red,
      delta: 1
    )
    let merged = FoodBlobPersistence.mergeAppendOnlyData(
      try Data((first.jsonLine() + "\n").utf8),
      with: try Data((second.jsonLine() + "\n").utf8)
    )

    XCTAssertEqual(
      FoodWidgetLedger.parse(
        String(decoding: merged, as: UTF8.self)
      ).entries.map(\.id),
      [first.id, second.id]
    )
  }

  func testExportIsAReadableStateDocument() throws {
    let store = FoodStore(containerURL: directory, now: { self.now })
    store.increment(.green, on: now)

    let exported = store.exportData()
    let object = try JSONSerialization.jsonObject(with: exported) as? [String: Any]

    XCTAssertEqual(object?["schema_version"] as? Int, 1)
    XCTAssertNotNil(object?["days"])
  }

  func testLegacySkinMigratesToSkyMeadow() throws {
    let legacy = Data(
      #"{"schema_version":1,"days":[],"selected_skin":"circuit_glow"}"#.utf8
    )
    try legacy.write(
      to: directory.appendingPathComponent(FoodBlobConstants.stateFileName)
    )

    let store = FoodStore(containerURL: directory, now: { self.now })

    XCTAssertEqual(store.selectedSkin, .skyMeadow)
  }

  func testSkinSelectionPersistsAndReachesWidgetSnapshot() {
    let first = FoodStore(containerURL: directory, now: { self.now })
    first.setSkin(.shrine)

    let second = FoodStore(containerURL: directory, now: { self.now })
    let widgetState = FoodBlobPersistence(containerURL: directory)
      .currentWidgetState(at: now)

    XCTAssertEqual(second.selectedSkin, .shrine)
    XCTAssertEqual(widgetState.skin, .shrine)
  }
}
