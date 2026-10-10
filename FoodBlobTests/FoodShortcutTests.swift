import AppIntents
import XCTest

@testable import FoodBlob

@MainActor
final class FoodShortcutTests: XCTestCase {
  private var directory: URL!
  private let now = Date(timeIntervalSince1970: 1_800_000_000)

  override func setUpWithError() throws {
    directory = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory, withIntermediateDirectories: true
    )
  }

  override func tearDownWithError() throws {
    try FileManager.default.removeItem(at: directory)
  }

  func testEveryColourProducesATodayOnlyUnitEntry() throws {
    let id = UUID()
    for color in FoodOfferingColor.allCases {
      for remove in [false, true] {
        let entry = try XCTUnwrap(FoodShortcutAction.entry(
          color: color.foodColor, remove: remove,
          counts: FoodCounts(green: 2, yellow: 3, red: 4),
          at: now, id: id
        ))
        XCTAssertEqual(entry.id, id)
        XCTAssertEqual(entry.timestamp, now)
        XCTAssertEqual(entry.dateKey, FoodDateKey.string(for: now))
        XCTAssertEqual(entry.color, color.foodColor)
        XCTAssertEqual(entry.delta, remove ? -1 : 1)
        XCTAssertEqual(FoodWidgetLedgerEntry(jsonLine: try entry.jsonLine()), entry)
      }
    }
  }

  func testZeroRemovalDoesNotCreateALedgerOrStateFile() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    for color in FoodColor.allCases {
      let result = try FoodShortcutAction.log(
        color: color, remove: true, persistence: persistence, at: now
      )
      XCTAssertFalse(result.didAppend)
      XCTAssertEqual(result.confirmation, "Nothing to remove — 0 \(color.rawValue) today.")
    }
    XCTAssertTrue(try FileManager.default.contentsOfDirectory(atPath: directory.path).isEmpty)
  }

  func testLogWritesOneLinePreservesStateAndIsIngestedExactlyOnce() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let store = FoodStore(containerURL: directory, now: { self.now }, reloadWidgetTimelines: {})
    XCTAssertTrue(store.increment(.yellow))
    let stateURL = directory.appendingPathComponent(FoodBlobConstants.stateFileName)
    let before = try Data(contentsOf: stateURL)

    let result = try FoodShortcutAction.log(
      color: .green, remove: false, persistence: persistence, at: now
    )
    XCTAssertTrue(result.didAppend)
    XCTAssertEqual(result.confirmation, "Added a green offering. 2 today.")
    XCTAssertEqual(try Data(contentsOf: stateURL), before)
    let ledger = try String(contentsOf: directory.appendingPathComponent(FoodBlobConstants.ledgerFileName))
    XCTAssertEqual(ledger.split(separator: "\n").count, 1)
    XCTAssertEqual(FoodWidgetLedger.parse(ledger).malformedLineCount, 0)

    let first = FoodStore(containerURL: directory, now: { self.now }, reloadWidgetTimelines: {})
    XCTAssertEqual(first.visibleCounts, FoodCounts(green: 1, yellow: 1))
    let second = FoodStore(containerURL: directory, now: { self.now }, reloadWidgetTimelines: {})
    XCTAssertEqual(second.visibleCounts, first.visibleCounts)
  }

  func testPendingActionsAreVisibleAndRemovalUsesPostAppendTotal() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    _ = try FoodShortcutAction.log(color: .red, remove: false, persistence: persistence, at: now)
    _ = try FoodShortcutAction.log(color: .yellow, remove: false, persistence: persistence, at: now)
    let mix = try FoodMixEntity.current(persistence: persistence, at: now)
    XCTAssertEqual([mix.green, mix.yellow, mix.red, mix.total], [0, 1, 1, 2])
    XCTAssertEqual(mix.dateKey, FoodDateKey.string(for: now))
    XCTAssertEqual(mix.id, mix.dateKey)
    XCTAssertEqual(mix.summary, "2 offerings — 0 green, 1 yellow, 1 red")
    let removed = try FoodShortcutAction.log(color: .red, remove: true, persistence: persistence, at: now)
    XCTAssertEqual(removed.confirmation, "Removed a red offering. 1 today.")
  }

  func testEmptyDayAndQueryRejectYesterdayWithoutExposingHistory() throws {
    let persistence = FoodBlobPersistence(containerURL: directory)
    let yesterday = now.addingTimeInterval(-86_400)
    _ = try FoodShortcutAction.log(color: .green, remove: false, persistence: persistence, at: yesterday)
    let today = try FoodMixEntity.current(persistence: persistence, at: now)
    XCTAssertEqual([today.green, today.yellow, today.red, today.total], [0, 0, 0, 0])
    XCTAssertEqual(today.summary, "0 offerings — 0 green, 0 yellow, 0 red")
    let matches = try FoodMixQuery.resolve(
      [FoodDateKey.string(for: yesterday), today.id, today.id],
      persistence: persistence, at: now
    )
    XCTAssertEqual(matches.map(\.id), [today.id])
  }

  func testEntryAndEntityUseTheSameLocalCalendarAtMidnight() throws {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try XCTUnwrap(TimeZone(secondsFromGMT: -3 * 3_600))
    let date = try XCTUnwrap(ISO8601DateFormatter().date(from: "2026-10-08T01:30:00Z"))
    let entry = try XCTUnwrap(FoodShortcutAction.entry(
      color: .yellow, remove: false, counts: FoodCounts(), at: date, calendar: calendar
    ))
    XCTAssertEqual(entry.dateKey, "2026-10-07")
    let state = FoodWidgetState(date: date, counts: FoodCounts(yellow: 1), skin: .skyMeadow,
                                widgetLayout: .defaultLayout, isAvailable: true)
    XCTAssertEqual(FoodMixEntity(state: state, calendar: calendar).dateKey, entry.dateKey)
  }

  func testUnavailableStorageThrowsBeforeReportingSuccess() {
    let unavailable = FoodBlobPersistence(containerURL: nil)
    XCTAssertThrowsError(try FoodShortcutAction.log(
      color: .green, remove: false, persistence: unavailable, at: now
    ))
    XCTAssertThrowsError(try FoodShortcutAction.log(
      color: .green, remove: true, persistence: unavailable, at: now
    ))
    XCTAssertThrowsError(try FoodMixEntity.current(persistence: unavailable, at: now))
  }

  func testConfirmationCopyDoesNotScoreFood() {
    for color in FoodColor.allCases {
      for remove in [false, true] {
        for appended in [false, true] {
          let copy = FoodShortcutAction.confirmation(
            color: color, remove: remove, didAppend: appended, counts: FoodCounts(green: 1)
          ).lowercased()
          for word in ["score", "streak", "missed", "fail"] {
            XCTAssertFalse(copy.contains(word))
          }
        }
      }
    }
    XCTAssertFalse(LogFoodOfferingIntent.openAppWhenRun)
    XCTAssertFalse(GetTodayFoodMixIntent.openAppWhenRun)
    XCTAssertEqual(FoodBlobShortcuts.appShortcuts.count, 2)
  }
}
