import XCTest

@testable import FoodBlob

final class WidgetLedgerTests: XCTestCase {
  private func entry(
    id: UUID = UUID(),
    dateKey: String = "2027-01-15",
    color: FoodColor = .green,
    delta: Int = 1
  ) -> FoodWidgetLedgerEntry {
    FoodWidgetLedgerEntry(
      id: id,
      timestamp: Date(timeIntervalSince1970: 1_800_000_000),
      dateKey: dateKey,
      color: color,
      delta: delta
    )
  }

  func testLedgerEntryRoundTripsAsOneJSONLine() throws {
    let original = entry(color: .red, delta: -1)

    let decoded = FoodWidgetLedgerEntry(jsonLine: try original.jsonLine())

    XCTAssertEqual(decoded, original)
  }

  func testParserKeepsGoodEntriesAndCountsATornTrailingLine() throws {
    let first = try entry().jsonLine()
    let second = try entry(color: .yellow).jsonLine()

    let parsed = FoodWidgetLedger.parse(
      first + "\n" + second + "\n" + #"{"id":"torn""#
    )

    XCTAssertEqual(parsed.entries.count, 2)
    XCTAssertEqual(parsed.malformedLineCount, 1)
  }

  func testParserPreservesValidationSemanticsAcrossRepeatedDateKeys() throws {
    let padded = entry(id: UUID(), dateKey: "2027-01-15", color: .green)
    let nonPadded = entry(id: UUID(), dateKey: "2027-1-5", color: .yellow)
    let impossible = entry(id: UUID(), dateKey: "2027-02-30", color: .red)
    let emptyDate = entry(id: UUID(), dateKey: "", color: .green)
    let zeroDelta = entry(id: UUID(), dateKey: "2027-01-15", delta: 0)
    let contents = try [
      "\u{2003}",
      padded.jsonLine(),
      nonPadded.jsonLine(),
      impossible.jsonLine(),
      impossible.jsonLine(),
      emptyDate.jsonLine(),
      zeroDelta.jsonLine(),
      #"{"date_key":42}"#,
      #"{"id":"torn""#,
    ].joined(separator: "\n")

    let parsed = FoodWidgetLedger.parse(contents)

    XCTAssertEqual(parsed.entries, [padded, nonPadded])
    XCTAssertEqual(parsed.malformedLineCount, 6)
  }

  func testParserValidatesEachDistinctDecodedDateKeyOnce() throws {
    let validKey = "2027-01-15"
    let impossibleKey = "2027-02-30"
    let lines = try [
      entry(id: UUID(), dateKey: validKey).jsonLine(),
      entry(id: UUID(), dateKey: validKey).jsonLine(),
      entry(id: UUID(), dateKey: validKey).jsonLine(),
      entry(id: UUID(), dateKey: impossibleKey).jsonLine(),
      entry(id: UUID(), dateKey: impossibleKey).jsonLine(),
      #"{"id":"torn""#,
    ].joined(separator: "\n")
    var validationCounts: [String: Int] = [:]

    let parsed = FoodWidgetLedger.parse(lines) { dateKey in
      validationCounts[dateKey, default: 0] += 1
      return dateKey == validKey
    }

    XCTAssertEqual(parsed.entries.count, 3)
    XCTAssertEqual(parsed.malformedLineCount, 3)
    XCTAssertEqual(validationCounts, [validKey: 1, impossibleKey: 1])
  }

  func testDirectLedgerEntryDecoderStillRejectsInvalidFields() throws {
    XCTAssertNil(
      FoodWidgetLedgerEntry(
        jsonLine: try entry(dateKey: "2027-02-30").jsonLine()
      )
    )
    XCTAssertNil(
      FoodWidgetLedgerEntry(jsonLine: try entry(dateKey: "").jsonLine())
    )
    XCTAssertNil(
      FoodWidgetLedgerEntry(jsonLine: try entry(delta: 0).jsonLine())
    )
  }

  func testTornClaimDoesNotConsumeTheFirstFreshLedgerEntry() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true
    )
    defer { try? FileManager.default.removeItem(at: directory) }
    let persistence = FoodBlobPersistence(containerURL: directory)
    try Data(#"{"id":"torn""#.utf8).write(
      to: directory.appendingPathComponent(FoodBlobConstants.claimFileName)
    )
    let fresh = entry(color: .yellow)
    try persistence.appendWidgetEntry(fresh)

    let parsed = try XCTUnwrap(persistence.drainWidgetLedger())

    XCTAssertEqual(parsed.entries, [fresh])
    XCTAssertEqual(parsed.malformedLineCount, 1)
  }

  func testUnreadableLedgerIsPreservedAndFailsClosed() throws {
    let directory = FileManager.default.temporaryDirectory
      .appendingPathComponent(UUID().uuidString, isDirectory: true)
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true
    )
    defer { try? FileManager.default.removeItem(at: directory) }
    let ledgerURL = directory.appendingPathComponent(
      FoodBlobConstants.ledgerFileName,
      isDirectory: true
    )
    try FileManager.default.createDirectory(
      at: ledgerURL,
      withIntermediateDirectories: true
    )
    let markerURL = ledgerURL.appendingPathComponent("preserve-me")
    try Data("widget-action".utf8).write(to: markerURL)
    let persistence = FoodBlobPersistence(containerURL: directory)

    XCTAssertThrowsError(try persistence.drainWidgetLedger())
    XCTAssertTrue(FileManager.default.fileExists(atPath: markerURL.path))
  }

  func testPendingEntriesDropConsumedAndDuplicateIDs() {
    let duplicateID = UUID()
    let consumedID = UUID()
    let entries = [
      entry(id: duplicateID),
      entry(id: duplicateID),
      entry(id: consumedID),
      entry(color: .red),
    ]

    let pending = FoodWidgetLedger.pending(
      entries,
      consumedIDs: [consumedID]
    )

    XCTAssertEqual(pending.count, 2)
    XCTAssertEqual(pending.first?.id, duplicateID)
    XCTAssertEqual(pending.last?.color, .red)
  }

  func testApplyingLedgerIsScopedByColorAndDayAndFlooredAtZero() {
    let entries = [
      entry(color: .green, delta: 1),
      entry(color: .yellow, delta: 1),
      entry(dateKey: "2027-01-14", color: .green, delta: 10),
      entry(color: .red, delta: -3),
    ]

    let result = FoodWidgetLedger.apply(
      entries,
      to: FoodCounts(green: 2, yellow: 0, red: 1),
      dateKey: "2027-01-15"
    )

    XCTAssertEqual(result, FoodCounts(green: 3, yellow: 1, red: 0))
  }

  func testDocumentAppliesLargeLedgerInEncounterOrderAcrossDays() {
    let firstDay = "2027-01-15"
    let secondDay = "2027-01-14"
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    var entries = (0..<300).map { offset in
      FoodWidgetLedgerEntry(
        timestamp: start.addingTimeInterval(Double(offset)),
        dateKey: offset.isMultiple(of: 2) ? firstDay : secondDay,
        color: offset.isMultiple(of: 2) ? .green : .yellow,
        delta: 1
      )
    }
    entries.append(
      FoodWidgetLedgerEntry(
        timestamp: start.addingTimeInterval(301),
        dateKey: firstDay,
        color: .red,
        delta: -1
      )
    )
    entries.append(
      FoodWidgetLedgerEntry(
        timestamp: start.addingTimeInterval(302),
        dateKey: firstDay,
        color: .red,
        delta: 1
      )
    )
    var document = FoodStateDocument()

    document.applyWidgetEntries(entries)

    XCTAssertEqual(
      document.counts(for: firstDay),
      FoodCounts(green: 150, yellow: 0, red: 1)
    )
    XCTAssertEqual(
      document.counts(for: secondDay),
      FoodCounts(green: 0, yellow: 150, red: 0)
    )
    XCTAssertEqual(document.days.map(\.dateKey), [firstDay, secondDay])
    XCTAssertEqual(
      document.days.first?.updatedAt,
      start.addingTimeInterval(302)
    )
  }
}
