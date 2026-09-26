import XCTest

@testable import FoodBlob

final class FoodBlobSnapshotTests: XCTestCase {
  func testSnapshotRoundTripsWidgetStateWithoutRetiredStreakWork() throws {
    let consumedID = UUID()
    let snapshot = FoodBlobSnapshot(
      generatedAt: Date(timeIntervalSince1970: 1_800_000_000),
      dateKey: "2027-01-15",
      counts: FoodCounts(green: 5, yellow: 2, red: 1),
      skin: .shrine,
      widgetLayout: .blobStage,
      consumedWidgetIDs: [consumedID]
    )

    let encoded = try snapshot.encoded()
    let decoded = FoodBlobSnapshot(data: encoded)

    XCTAssertEqual(decoded?.dateKey, "2027-01-15")
    XCTAssertEqual(decoded?.counts, FoodCounts(green: 5, yellow: 2, red: 1))
    XCTAssertEqual(decoded?.skin, .shrine)
    XCTAssertEqual(decoded?.widgetLayout, .blobStage)
    XCTAssertEqual(decoded?.consumedWidgetIDs, [consumedID])
    XCTAssertFalse(String(decoding: encoded, as: UTF8.self).contains("streak"))
  }

  func testLegacySnapshotStreakIsIgnoredWithoutLosingWidgetState() {
    let data = Data(
      #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{"green":2,"yellow":1,"red":0},"skin":"shrine","widget_layout":"blob_stage","streak":19}"#.utf8
    )

    let decoded = FoodBlobSnapshot(data: data)

    XCTAssertEqual(decoded?.counts, FoodCounts(green: 2, yellow: 1, red: 0))
    XCTAssertEqual(decoded?.skin, .shrine)
    XCTAssertEqual(decoded?.widgetLayout, .blobStage)
  }

  func testSnapshotKeepsEveryConsumedIDNeededForCrashRecovery() {
    let ids = (0..<301).map { _ in UUID() }

    let snapshot = FoodBlobSnapshot(
      generatedAt: Date(),
      dateKey: "2027-01-15",
      counts: FoodCounts(),
      skin: .skyMeadow,
      consumedWidgetIDs: ids
    )

    XCTAssertEqual(snapshot.consumedWidgetIDs, ids)
  }

  func testNewerSnapshotSchemaIsRejected() {
    let data = Data(
      #"{"schema_version":99,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{"green":1,"yellow":0,"red":0},"skin":"bubble_pop"}"#
        .utf8
    )

    XCTAssertNil(FoodBlobSnapshot(data: data))
  }

  func testMalformedAndIncompleteSnapshotsAreRejected() {
    XCTAssertNil(FoodBlobSnapshot(data: Data("not json".utf8)))
    XCTAssertNil(
      FoodBlobSnapshot(
        data: Data(
          #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z"}"#
            .utf8
        )
      )
    )
  }

  func testUnknownSkinFallsBackWithoutLosingCounts() {
    let data = Data(
      #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{"green":2,"yellow":1,"red":0},"skin":"future_skin"}"#
        .utf8
    )

    let decoded = FoodBlobSnapshot(data: data)

    XCTAssertEqual(decoded?.counts.total, 3)
    XCTAssertEqual(decoded?.skin, .skyMeadow)
  }

  func testRetiredSkinFallsBackToSkyMeadow() {
    let data = Data(
      #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{"green":2,"yellow":1,"red":0},"skin":"low_poly"}"#
        .utf8
    )

    let decoded = FoodBlobSnapshot(data: data)

    XCTAssertEqual(decoded?.skin, .skyMeadow)
  }

  func testMissingWidgetLayoutPreservesLegacyColumnsAndUnknownFallsBack() {
    let missing = Data(
      #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{},"skin":"bubble_pop"}"#
        .utf8
    )
    let unknown = Data(
      #"{"schema_version":1,"generated_at":"2027-01-15T12:00:00Z","date_key":"2027-01-15","counts":{},"skin":"bubble_pop","widget_layout":"future_layout"}"#
        .utf8
    )

    XCTAssertEqual(FoodBlobSnapshot(data: missing)?.widgetLayout, .popColumns)
    XCTAssertEqual(FoodBlobSnapshot(data: unknown)?.widgetLayout, .bubbleStack)
  }
}
