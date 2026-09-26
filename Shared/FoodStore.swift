import Foundation
import Observation
import WidgetKit

@MainActor
@Observable
final class FoodStore {
  private(set) var selectedDate: Date
  private(set) var visibleCounts: FoodCounts
  private(set) var history: [DayRecord]
  private(set) var selectedSkin: SkinID
  private(set) var selectedWidgetLayout: WidgetLayoutID
  private(set) var canUndo: Bool
  private(set) var lastError: String?

  @ObservationIgnored private let persistence: FoodBlobPersistence
  @ObservationIgnored private let now: () -> Date
  @ObservationIgnored private let calendarProvider: () -> Calendar
  @ObservationIgnored private let widgetTimelineReload: () -> Void
  @ObservationIgnored private let onCommittedWidgetEntries:
    (([FoodWidgetLedgerEntry]) throws -> Void)?
  @ObservationIgnored private let onBeforeDeleteAllData: (() throws -> Void)?
  @ObservationIgnored private let onAfterDeleteAllData: (() throws -> Void)?
  @ObservationIgnored private let onDeleteAllDataFailed: (() -> Void)?
  @ObservationIgnored private let onStatePublished: (() -> Void)?
  @ObservationIgnored private var document: FoodStateDocument
  @ObservationIgnored private var stateLoadError: String?
  @ObservationIgnored private var hasLoadedDocument: Bool
  @ObservationIgnored private var resetRecoveryBlocked: Bool

  private static let resetRecoveryMessage =
    "Food Blob is finishing its data reset. Please reopen the app to continue."
  private static let historyLimitMessage =
    "Food Blob keeps the newest 180 logged days. Choose a newer day."

  private var calendar: Calendar { calendarProvider() }

  convenience init() {
    self.init(
      persistence: FoodBlobPersistence(),
      now: Date.init,
      calendarProvider: { .autoupdatingCurrent },
      reloadWidgetTimelines: { WidgetCenter.shared.reloadAllTimelines() },
      onCommittedWidgetEntries: nil,
      onBeforeDeleteAllData: nil,
      onAfterDeleteAllData: nil,
      onDeleteAllDataFailed: nil,
      onStatePublished: nil,
      initialResetRecoveryBlocked: false
    )
  }

  convenience init(
    onCommittedWidgetEntries:
      (([FoodWidgetLedgerEntry]) throws -> Void)? = nil,
    onBeforeDeleteAllData: (() throws -> Void)? = nil,
    onAfterDeleteAllData: (() throws -> Void)? = nil,
    onDeleteAllDataFailed: (() -> Void)? = nil,
    onStatePublished: (() -> Void)? = nil,
    reloadWidgetTimelines: @escaping () -> Void = {
      WidgetCenter.shared.reloadAllTimelines()
    },
    resetRecoveryBlocked: Bool = false
  ) {
    self.init(
      persistence: FoodBlobPersistence(),
      now: Date.init,
      calendarProvider: { .autoupdatingCurrent },
      reloadWidgetTimelines: reloadWidgetTimelines,
      onCommittedWidgetEntries: onCommittedWidgetEntries,
      onBeforeDeleteAllData: onBeforeDeleteAllData,
      onAfterDeleteAllData: onAfterDeleteAllData,
      onDeleteAllDataFailed: onDeleteAllDataFailed,
      onStatePublished: onStatePublished,
      initialResetRecoveryBlocked: resetRecoveryBlocked
    )
  }

  convenience init(
    containerURL: URL,
    now: @escaping () -> Date = Date.init,
    calendar: Calendar = .autoupdatingCurrent,
    reloadWidgetTimelines: @escaping () -> Void = {
      WidgetCenter.shared.reloadAllTimelines()
    },
    onCommittedWidgetEntries:
      (([FoodWidgetLedgerEntry]) throws -> Void)? = nil,
    onBeforeDeleteAllData: (() throws -> Void)? = nil,
    onAfterDeleteAllData: (() throws -> Void)? = nil,
    onDeleteAllDataFailed: (() -> Void)? = nil,
    onStatePublished: (() -> Void)? = nil
  ) {
    self.init(
      persistence: FoodBlobPersistence(containerURL: containerURL),
      now: now,
      calendarProvider: { calendar },
      reloadWidgetTimelines: reloadWidgetTimelines,
      onCommittedWidgetEntries: onCommittedWidgetEntries,
      onBeforeDeleteAllData: onBeforeDeleteAllData,
      onAfterDeleteAllData: onAfterDeleteAllData,
      onDeleteAllDataFailed: onDeleteAllDataFailed,
      onStatePublished: onStatePublished,
      initialResetRecoveryBlocked: false
    )
  }

  convenience init(
    containerURL: URL,
    now: @escaping () -> Date = Date.init,
    calendarProvider: @escaping () -> Calendar,
    reloadWidgetTimelines: @escaping () -> Void = {
      WidgetCenter.shared.reloadAllTimelines()
    },
    onCommittedWidgetEntries:
      (([FoodWidgetLedgerEntry]) throws -> Void)? = nil,
    onBeforeDeleteAllData: (() throws -> Void)? = nil,
    onAfterDeleteAllData: (() throws -> Void)? = nil,
    onDeleteAllDataFailed: (() -> Void)? = nil,
    onStatePublished: (() -> Void)? = nil
  ) {
    self.init(
      persistence: FoodBlobPersistence(containerURL: containerURL),
      now: now,
      calendarProvider: calendarProvider,
      reloadWidgetTimelines: reloadWidgetTimelines,
      onCommittedWidgetEntries: onCommittedWidgetEntries,
      onBeforeDeleteAllData: onBeforeDeleteAllData,
      onAfterDeleteAllData: onAfterDeleteAllData,
      onDeleteAllDataFailed: onDeleteAllDataFailed,
      onStatePublished: onStatePublished,
      initialResetRecoveryBlocked: false
    )
  }

  private init(
    persistence: FoodBlobPersistence,
    now: @escaping () -> Date,
    calendarProvider: @escaping () -> Calendar,
    reloadWidgetTimelines: @escaping () -> Void,
    onCommittedWidgetEntries:
      (([FoodWidgetLedgerEntry]) throws -> Void)?,
    onBeforeDeleteAllData: (() throws -> Void)?,
    onAfterDeleteAllData: (() throws -> Void)?,
    onDeleteAllDataFailed: (() -> Void)?,
    onStatePublished: (() -> Void)?,
    initialResetRecoveryBlocked: Bool
  ) {
    self.persistence = persistence
    self.now = now
    self.calendarProvider = calendarProvider
    widgetTimelineReload = reloadWidgetTimelines
    self.onCommittedWidgetEntries = onCommittedWidgetEntries
    self.onBeforeDeleteAllData = onBeforeDeleteAllData
    self.onAfterDeleteAllData = onAfterDeleteAllData
    self.onDeleteAllDataFailed = onDeleteAllDataFailed
    self.onStatePublished = onStatePublished
    let calendar = calendarProvider()
    let initialDate = calendar.startOfDay(for: now())
    selectedDate = initialDate
    visibleCounts = FoodCounts()
    history = []
    selectedSkin = .skyMeadow
    selectedWidgetLayout = .defaultLayout
    canUndo = false
    lastError = nil
    document = FoodStateDocument()
    stateLoadError = nil
    hasLoadedDocument = false
    resetRecoveryBlocked = initialResetRecoveryBlocked
    if initialResetRecoveryBlocked {
      stateLoadError = Self.resetRecoveryMessage
      lastError = Self.resetRecoveryMessage
      if let loaded = try? persistence.loadDocument() {
        document = loaded
        hasLoadedDocument = true
      }
      refreshPublishedState()
    } else {
      reloadAndIngestWidgetActions()
    }
  }

  var isSharedContainerAvailable: Bool { persistence.isAvailable }

  @discardableResult
  func increment(_ color: FoodColor, on date: Date) -> Bool {
    mutate(color, delta: 1, on: date)
  }

  @discardableResult
  func increment(_ color: FoodColor) -> Bool {
    increment(color, on: selectedDate)
  }

  @discardableResult
  func decrement(_ color: FoodColor, on date: Date) -> Bool {
    mutate(color, delta: -1, on: date)
  }

  @discardableResult
  func decrement(_ color: FoodColor) -> Bool {
    decrement(color, on: selectedDate)
  }

  @discardableResult
  func undo() -> Bool {
    guard stateCanBeWritten() else { return false }
    let previousDocument = document
    guard let action = document.undoStack.popLast() else { return false }
    return applyUndo(action, previousDocument: previousDocument)
  }

  func canUndo(on date: Date) -> Bool {
    let dateKey = FoodDateKey.string(for: date, calendar: calendar)
    return document.undoStack.contains { $0.dateKey == dateKey }
  }

  @discardableResult
  func undo(on date: Date) -> Bool {
    guard stateCanBeWritten() else { return false }
    let dateKey = FoodDateKey.string(for: date, calendar: calendar)
    guard
      let index = document.undoStack.lastIndex(
        where: { $0.dateKey == dateKey }
      )
    else {
      return false
    }
    let previousDocument = document
    let action = document.undoStack.remove(at: index)
    return applyUndo(action, previousDocument: previousDocument)
  }

  private func applyUndo(
    _ action: FoodUndoAction,
    previousDocument: FoodStateDocument
  ) -> Bool {
    let activeCalendar = calendar
    let mutationTime = now()
    let counts = document.counts(for: action.dateKey)
      .applying(-action.delta, to: action.color)
    document.setCounts(
      counts,
      for: action.dateKey,
      updatedAt: mutationTime
    )
    let todayKey = FoodDateKey.string(
      for: mutationTime,
      calendar: activeCalendar
    )
    return persistAndPublish(
      previousDocument: previousDocument,
      shouldReloadWidgets: action.dateKey == todayKey
    )
  }

  func selectDate(_ date: Date) {
    selectedDate = calendar.startOfDay(for: date)
    refreshPublishedState()
  }

  func counts(on date: Date) -> FoodCounts {
    document.counts(
      for: FoodDateKey.string(for: date, calendar: calendar)
    )
  }

  func deleteAll() {
    // A failed reset is the one state in which the destructive operation
    // itself must remain available: retrying is how the user completes the
    // durable phone/watch boundary. Ordinary mutations stay blocked below.
    if !resetRecoveryBlocked {
      guard stateCanBeWritten() else { return }
    }
    let widgetLayout = document.selectedWidgetLayout
    let skin = document.selectedSkin
    do {
      try onBeforeDeleteAllData?()
    } catch {
      onDeleteAllDataFailed?()
      lastError = error.localizedDescription
      return
    }

    do {
      try persistence.resetAll(
        keeping: widgetLayout,
        skin: skin,
        now: now(),
        calendar: calendar
      )
    } catch {
      // Keep the staged receipt boundary unready. The receiver suppresses
      // acknowledgements until the phone reset reaches its commit point.
      let recoveryFailed: Bool
      if let persistenceError = error as? FoodBlobPersistenceError {
        if case .resetRecoveryFailed = persistenceError {
          recoveryFailed = true
        } else {
          recoveryFailed = false
        }
      } else {
        recoveryFailed = false
      }
      if !recoveryFailed {
        onDeleteAllDataFailed?()
      } else {
        resetRecoveryBlocked = true
      }
      let message = error.localizedDescription
      // `resetAll` rolls its files back before throwing. Keep the in-memory
      // document locked until the next activation reloads that durable state;
      // otherwise a failed reset could immediately save stale history again.
      stateLoadError = message
      lastError = message
      return
    }

    do {
      try onAfterDeleteAllData?()
    } catch {
      // Phone persistence is already empty, but the receipt boundary is not
      // safe to advertise. Keep the in-memory store blocked until a later
      // launch can recover or finish the receipt commit.
      document = FoodStateDocument(
        selectedSkin: skin,
        selectedWidgetLayout: widgetLayout
      )
      selectedDate = calendar.startOfDay(for: now())
      hasLoadedDocument = true
      resetRecoveryBlocked = true
      stateLoadError = error.localizedDescription
      lastError = error.localizedDescription
      refreshPublishedState()
      reloadWidgetTimelines()
      return
    }
    resetRecoveryBlocked = false
    stateLoadError = nil
    document = FoodStateDocument(
      selectedSkin: skin,
      selectedWidgetLayout: widgetLayout
    )
    selectedDate = calendar.startOfDay(for: now())
    lastError = nil
    refreshPublishedState()
    reloadWidgetTimelines()
    onStatePublished?()
  }

  func exportData() -> Data {
    persistence.exportDocument(document)
  }

  @discardableResult
  func setWidgetLayout(_ layout: WidgetLayoutID) -> Bool {
    guard stateCanBeWritten() else { return false }
    guard document.selectedWidgetLayout != layout else { return false }
    let previousDocument = document
    document.selectedWidgetLayout = layout
    return persistAndPublish(
      previousDocument: previousDocument,
      shouldReloadWidgets: false
    )
  }

  @discardableResult
  func setSkin(_ skin: SkinID) -> Bool {
    guard stateCanBeWritten() else { return false }
    guard document.selectedSkin != skin else { return false }
    let previousDocument = document
    document.selectedSkin = skin
    return persistAndPublish(
      previousDocument: previousDocument,
      shouldReloadWidgets: false
    )
  }

  /// Called on launch and whenever the app becomes active. Widget intents only
  /// append UUID-tagged events; this folds them into durable day history.
  func reloadAndIngestWidgetActions() {
    guard !resetRecoveryBlocked else {
      lastError = stateLoadError ?? Self.resetRecoveryMessage
      refreshPublishedState()
      return
    }
    if hasLoadedDocument,
      lastError == nil,
      document.consumedWidgetIDs.isEmpty,
      !persistence.hasWidgetActionFiles
    {
      return
    }

    do {
      document = try persistence.loadDocument()
      stateLoadError = nil
      hasLoadedDocument = true
    } catch {
      let message = error.localizedDescription
      stateLoadError = message
      lastError = message
      refreshPublishedState()
      reloadWidgetTimelines()
      return
    }

    do {
      let parsed =
        persistence.hasWidgetActionFiles
        ? try persistence.drainWidgetLedger()
        : nil
      if let parsed {
        let pending = FoodWidgetLedger.pending(
          parsed.entries,
          consumedIDs: Set(document.consumedWidgetIDs)
        )
        document.applyWidgetEntries(pending)
        document.addConsumedWidgetIDs(pending.map(\.id))

        // State is the commit point. If the app dies after this write but
        // before the claim is removed, consumed ids make replay idempotent.
        try persistence.saveDocument(document)
        try persistence.publishSnapshot(
          from: document,
          now: now(),
          calendar: calendar
        )
        try onCommittedWidgetEntries?(parsed.entries)
        try persistence.clearWidgetClaim()
        document.consumedWidgetIDs.removeAll()
        try persistence.saveDocument(document)
        try persistence.publishSnapshot(
          from: document,
          now: now(),
          calendar: calendar
        )
        onStatePublished?()
        if parsed.malformedLineCount > 0 {
          lastError =
            "Skipped \(parsed.malformedLineCount) incomplete widget action(s)."
        } else {
          lastError = nil
        }
      } else {
        if !document.consumedWidgetIDs.isEmpty {
          document.consumedWidgetIDs.removeAll()
          try persistence.saveDocument(document)
        }
        let published = try persistence.publishSnapshotIfNeeded(
          from: document,
          now: now(),
          calendar: calendar
        )
        lastError = nil
        refreshPublishedState()
        if published {
          reloadWidgetTimelines()
          onStatePublished?()
        }
        return
      }
    } catch {
      // Leave the claim in place. The next activation replays it safely.
      lastError = error.localizedDescription
    }
    refreshPublishedState()
    reloadWidgetTimelines()
  }

  private func mutate(_ color: FoodColor, delta: Int, on date: Date) -> Bool {
    guard stateCanBeWritten() else { return false }
    let activeCalendar = calendar
    let dateKey = FoodDateKey.string(for: date, calendar: activeCalendar)
    let alreadyRetained = document.days.contains { $0.dateKey == dateKey }
    if !alreadyRetained,
      document.days.count >= FoodStateDocument.maximumHistoryDays,
      let oldestRetained = document.days.last?.dateKey,
      dateKey <= oldestRetained
    {
      lastError = Self.historyLimitMessage
      refreshPublishedState()
      return false
    }
    var counts = document.counts(for: dateKey)
    let previous = counts.count(for: color)
    let updated = max(0, previous + delta)
    guard updated != previous else { return false }

    let previousDocument = document
    counts.setCount(updated, for: color)
    let mutationTime = now()
    document.setCounts(counts, for: dateKey, updatedAt: mutationTime)
    document.pushUndo(
      FoodUndoAction(
        dateKey: dateKey,
        color: color,
        delta: updated - previous
      )
    )
    let todayKey = FoodDateKey.string(
      for: mutationTime,
      calendar: activeCalendar
    )
    return persistAndPublish(
      previousDocument: previousDocument,
      shouldReloadWidgets: dateKey == todayKey
    )
  }

  private func persistAndPublish(
    previousDocument: FoodStateDocument,
    shouldReloadWidgets: Bool = true
  ) -> Bool {
    do {
      try persistence.saveDocument(document)
    } catch {
      document = previousDocument
      lastError = error.localizedDescription
      refreshPublishedState()
      return false
    }

    do {
      try persistence.publishSnapshot(
        from: document,
        now: now(),
        calendar: calendar
      )
      onStatePublished?()
      lastError = nil
      refreshPublishedState()
      if shouldReloadWidgets {
        reloadWidgetTimelines()
      }
      return true
    } catch {
      // The state document is already the durable commit point. Keep the
      // committed app state visible, but surface that its widget projection
      // could not be refreshed yet.
      lastError = error.localizedDescription
      refreshPublishedState()
      return true
    }
  }

  private func stateCanBeWritten() -> Bool {
    guard !resetRecoveryBlocked else {
      lastError = stateLoadError ?? Self.resetRecoveryMessage
      return false
    }
    guard let stateLoadError else { return true }
    lastError = stateLoadError
    return false
  }

  func blockMutationsForResetRecovery() {
    resetRecoveryBlocked = true
    stateLoadError = Self.resetRecoveryMessage
    lastError = Self.resetRecoveryMessage
    refreshPublishedState()
  }

  func resolveResetRecovery() {
    guard resetRecoveryBlocked else { return }
    resetRecoveryBlocked = false
    stateLoadError = nil
    lastError = nil
    reloadAndIngestWidgetActions()
  }

  private func refreshPublishedState() {
    document.normalize()
    history = document.days
    selectedSkin = document.selectedSkin
    selectedWidgetLayout = document.selectedWidgetLayout
    canUndo = !document.undoStack.isEmpty
    visibleCounts = document.counts(
      for: FoodDateKey.string(for: selectedDate, calendar: calendar)
    )
  }

  private func reloadWidgetTimelines() {
    widgetTimelineReload()
  }
}
