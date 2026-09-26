import Foundation

enum FoodBlobConstants {
  static let appGroupIdentifier = "group.org.example.foodblob"
  static let watchAppGroupIdentifier = "group.org.example.foodblob.watch"
  static let counterWidgetKind = "FoodBlobCounterWidgetV2"
  static let blobWidgetKind = "FoodBlobLivingBlobWidget"
  static let skyMeadowWidgetKind = "FoodBlobSkyMeadowWidgetV1"
  static let shrineWidgetKind = "FoodBlobShrineWidgetV1"
  static let activeWidgetKinds = [
    skyMeadowWidgetKind,
    shrineWidgetKind,
  ]

  static func counterWidgetKind(for layout: WidgetLayoutID) -> String {
    "\(counterWidgetKind).layout.\(layout.rawValue)"
  }

  static func widgetKind(for skin: SkinID) -> String {
    switch skin {
    case .skyMeadow: skyMeadowWidgetKind
    case .shrine: shrineWidgetKind
    }
  }

  static let stateFileName = "food_blob_state.json"
  static let snapshotFileName = "food_blob_snapshot.json"
  static let ledgerFileName = "food_blob_widget_ledger.jsonl"
  static let claimFileName = "food_blob_widget_ledger.claim.jsonl"
  static let watchReceiptFileName = "food_blob_watch_receipts.json"
}

enum FoodBlobPersistenceError: LocalizedError {
  case appGroupUnavailable
  case coordinatedFileAccessFailed
  case resetRecoveryFailed
  case savedStateUnreadable
  case savedStateInvalid

  var errorDescription: String? {
    switch self {
    case .appGroupUnavailable:
      "The Food Blob App Group is unavailable."
    case .coordinatedFileAccessFailed:
      "Food Blob could not coordinate access to shared widget data."
    case .resetRecoveryFailed:
      "Food Blob could not safely recover an incomplete data reset."
    case .savedStateUnreadable:
      "Saved Food Blob data could not be read. It was preserved unchanged."
    case .savedStateInvalid:
      "Saved Food Blob data is invalid or belongs to a newer app version. It was preserved unchanged."
    }
  }
}

struct FoodWidgetState: Equatable, Sendable {
  let date: Date
  let counts: FoodCounts
  let skin: SkinID
  let widgetLayout: WidgetLayoutID
  let isAvailable: Bool
}

final class FoodBlobPersistence: @unchecked Sendable {
  private let containerURL: URL?

  static func mergeAppendOnlyData(_ backup: Data, with current: Data) -> Data {
    guard !current.isEmpty else { return backup }
    guard !backup.isEmpty else { return current }
    var merged = backup
    if merged.last != 0x0A {
      merged.append(0x0A)
    }
    merged.append(current)
    return merged
  }

  init(
    containerURL: URL? = FileManager.default.containerURL(
      forSecurityApplicationGroupIdentifier:
        FoodBlobConstants.appGroupIdentifier
    )
  ) {
    self.containerURL = containerURL
    if let containerURL {
      try? FileManager.default.createDirectory(
        at: containerURL,
        withIntermediateDirectories: true
      )
    }
  }

  var isAvailable: Bool { containerURL != nil }

  var hasWidgetActionFiles: Bool {
    guard let containerURL else { return false }
    return [
      FoodBlobConstants.ledgerFileName,
      FoodBlobConstants.claimFileName,
    ].contains { fileName in
      FileManager.default.fileExists(
        atPath: containerURL.appendingPathComponent(fileName).path
      )
    }
  }

  func loadDocument() throws -> FoodStateDocument {
    guard let url = fileURL(named: FoodBlobConstants.stateFileName) else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    guard FileManager.default.fileExists(atPath: url.path) else {
      return FoodStateDocument()
    }
    let data: Data
    do {
      data = try coordinatedRead(url, body: { try Data(contentsOf: $0) })
    } catch {
      throw FoodBlobPersistenceError.savedStateUnreadable
    }
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    var document: FoodStateDocument
    do {
      document = try decoder.decode(FoodStateDocument.self, from: data)
    } catch {
      throw FoodBlobPersistenceError.savedStateInvalid
    }
    document.normalize()
    return document
  }

  func saveDocument(_ document: FoodStateDocument) throws {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    encoder.outputFormatting = [.sortedKeys]
    let data = try encoder.encode(document)
    try write(data, to: FoodBlobConstants.stateFileName)
  }

  func exportDocument(_ document: FoodStateDocument) -> Data {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    return (try? encoder.encode(document)) ?? Data()
  }

  func publishSnapshot(
    from document: FoodStateDocument,
    now: Date = Date(),
    calendar: Calendar = .autoupdatingCurrent
  ) throws {
    let snapshot = makeSnapshot(from: document, now: now, calendar: calendar)
    try write(
      snapshot.encoded(),
      to: FoodBlobConstants.snapshotFileName
    )
  }

  @discardableResult
  func publishSnapshotIfNeeded(
    from document: FoodStateDocument,
    now: Date = Date(),
    calendar: Calendar = .autoupdatingCurrent
  ) throws -> Bool {
    let expected = makeSnapshot(from: document, now: now, calendar: calendar)
    if let current = readSnapshot(), current.hasSamePayload(as: expected) {
      return false
    }
    try write(expected.encoded(), to: FoodBlobConstants.snapshotFileName)
    return true
  }

  func readSnapshot() -> FoodBlobSnapshot? {
    guard let url = fileURL(named: FoodBlobConstants.snapshotFileName),
      FileManager.default.fileExists(atPath: url.path),
      let data = try? coordinatedRead(url, body: { try Data(contentsOf: $0) })
    else {
      return nil
    }
    return FoodBlobSnapshot(data: data)
  }

  func appendWidgetEntry(_ entry: FoodWidgetLedgerEntry) throws {
    let line = try entry.jsonLine() + "\n"
    let data = Data(line.utf8)
    guard let url = fileURL(named: FoodBlobConstants.ledgerFileName) else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    try coordinatedWrite(url) { coordinatedURL in
      if FileManager.default.fileExists(atPath: coordinatedURL.path) {
        let handle = try FileHandle(forWritingTo: coordinatedURL)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
        try handle.synchronize()
      } else {
        try data.write(to: coordinatedURL, options: .atomic)
      }
    }
  }

  /// Appends a watch-delivered entry unless it is already present in either
  /// the live ledger or its crash-recovery claim. WatchConnectivity can
  /// deliver the same message through both its interactive and queued paths;
  /// keeping this check at the append boundary makes retries cheap and
  /// idempotent without changing the normal widget intent path above.
  func appendWidgetEntryIfNeeded(_ entry: FoodWidgetLedgerEntry) throws {
    try appendWidgetEntriesIfNeeded([entry])
  }

  /// Appends a Watch reconnect batch with one coordinated read and one flush.
  /// WatchConnectivity may replay entries through multiple routes, so IDs are
  /// deduplicated against both crash-recovery segments and within the batch.
  func appendWidgetEntriesIfNeeded(
    _ entries: [FoodWidgetLedgerEntry]
  ) throws {
    guard !entries.isEmpty else { return }
    guard let ledgerURL = fileURL(named: FoodBlobConstants.ledgerFileName),
      let claimURL = fileURL(named: FoodBlobConstants.claimFileName)
    else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    try coordinatedWrite(
      ledgerURL,
      options: [],
      claimURL,
      options: []
    ) { coordinatedLedgerURL, coordinatedClaimURL in
      let existing = FoodWidgetLedger.parse(
        FoodWidgetLedger.joinedSegments([
          try self.contentsIfPresent(at: coordinatedClaimURL),
          try self.contentsIfPresent(at: coordinatedLedgerURL),
        ])
      ).entries
      var knownIDs = Set(existing.map(\.id))
      let newEntries = entries.filter { knownIDs.insert($0.id).inserted }
      guard !newEntries.isEmpty else { return }
      let lines = try newEntries.map { try $0.jsonLine() }.joined(separator: "\n")
      let data = Data((lines + "\n").utf8)
      if FileManager.default.fileExists(atPath: coordinatedLedgerURL.path) {
        let handle = try FileHandle(forWritingTo: coordinatedLedgerURL)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
        try handle.synchronize()
      } else {
        try data.write(to: coordinatedLedgerURL, options: .atomic)
      }
    }
  }

  /// Claims the live ledger before returning it. The claim remains on disk
  /// until `clearWidgetClaim`, so a killed app replays the same UUID-tagged
  /// actions and the consumed-id set makes that replay harmless.
  func drainWidgetLedger() throws -> FoodWidgetLedgerParse? {
    guard let ledgerURL = fileURL(named: FoodBlobConstants.ledgerFileName),
      let claimURL = fileURL(named: FoodBlobConstants.claimFileName)
    else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }

    var combined: String?
    try coordinatedWrite(
      ledgerURL,
      options: .forMoving,
      claimURL,
      options: []
    ) { coordinatedLedgerURL, coordinatedClaimURL in
      let claimed = try self.contentsIfPresent(at: coordinatedClaimURL)
      let fresh = try self.contentsIfPresent(at: coordinatedLedgerURL)
      let contents = FoodWidgetLedger.joinedSegments([claimed, fresh])
      guard !contents.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      else {
        return
      }
      try contents.write(
        to: coordinatedClaimURL,
        atomically: true,
        encoding: .utf8
      )
      if FileManager.default.fileExists(atPath: coordinatedLedgerURL.path) {
        try FileManager.default.removeItem(at: coordinatedLedgerURL)
      }
      combined = contents
    }
    return combined.map(FoodWidgetLedger.parse)
  }

  func clearWidgetClaim() throws {
    guard let url = fileURL(named: FoodBlobConstants.claimFileName) else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    try coordinatedWrite(url, options: .forDeleting) { coordinatedURL in
      guard FileManager.default.fileExists(atPath: coordinatedURL.path) else {
        return
      }
      try FileManager.default.removeItem(at: coordinatedURL)
    }
  }

  func pendingWidgetEntries(
    consumedIDs: Set<UUID> = []
  ) -> [FoodWidgetLedgerEntry] {
    guard let containerURL else { return [] }
    var segments: [String] = []
    for fileName in [
      FoodBlobConstants.claimFileName,
      FoodBlobConstants.ledgerFileName,
    ] {
      let url = containerURL.appendingPathComponent(fileName)
      guard FileManager.default.fileExists(atPath: url.path) else {
        continue
      }
      let part =
        (try? coordinatedRead(url) {
          (try? String(contentsOf: $0, encoding: .utf8)) ?? ""
        }) ?? ""
      segments.append(part)
    }
    return FoodWidgetLedger.pending(
      FoodWidgetLedger.parse(
        FoodWidgetLedger.joinedSegments(segments)
      ).entries,
      consumedIDs: consumedIDs
    )
  }

  func currentWidgetState(
    at date: Date = Date(),
    calendar: Calendar = .autoupdatingCurrent
  ) -> FoodWidgetState {
    let dateKey = FoodDateKey.string(for: date, calendar: calendar)
    let snapshot = readSnapshot()
    let base = snapshot?.dateKey == dateKey ? snapshot?.counts ?? FoodCounts() : FoodCounts()
    let pending = pendingWidgetEntries(
      consumedIDs: Set(snapshot?.consumedWidgetIDs ?? [])
    )
    let optimistic = FoodWidgetLedger.apply(
      pending,
      to: base,
      dateKey: dateKey
    )
    return FoodWidgetState(
      date: date,
      counts: optimistic,
      skin: snapshot?.skin ?? .skyMeadow,
      widgetLayout: snapshot?.widgetLayout ?? .defaultLayout,
      isAvailable: isAvailable
    )
  }

  func resetAll(
    keeping widgetLayout: WidgetLayoutID = .defaultLayout,
    skin: SkinID = .skyMeadow,
    now: Date = Date(),
    calendar: Calendar = .autoupdatingCurrent
  ) throws {
    let empty = FoodStateDocument(
      selectedSkin: skin,
      selectedWidgetLayout: widgetLayout
    )
    let fileNames = [
      FoodBlobConstants.stateFileName,
      FoodBlobConstants.snapshotFileName,
      FoodBlobConstants.ledgerFileName,
      FoodBlobConstants.claimFileName,
    ]
    let backups = try fileNames.prefix(2).compactMap { fileName -> ResetFileBackup? in
      guard let url = fileURL(named: fileName) else { return nil }
      return try makeResetBackup(for: url)
    }
    var inputBackups: [ResetFileBackup] = []

    do {
      // Remove input ledgers before publishing the empty state. If a later
      // write fails, the rollback below restores both the old state and the
      // old inputs, so a future activation cannot replay deleted history.
      for fileName in [
        FoodBlobConstants.ledgerFileName,
        FoodBlobConstants.claimFileName,
      ] {
        guard let url = fileURL(named: fileName) else { continue }
        inputBackups.append(try removeResetInput(at: url))
      }
      try saveDocument(empty)
      try publishSnapshot(from: empty, now: now, calendar: calendar)
    } catch {
      do {
        try restoreResetBackups(backups + inputBackups)
      } catch {
        throw FoodBlobPersistenceError.resetRecoveryFailed
      }
      throw error
    }
  }

  private struct ResetFileBackup {
    let url: URL
    let data: Data?
    let mergeAppendOnly: Bool
  }

  private func makeResetBackup(for url: URL) throws -> ResetFileBackup {
    guard FileManager.default.fileExists(atPath: url.path) else {
      return ResetFileBackup(url: url, data: nil, mergeAppendOnly: false)
    }
    let data = try coordinatedRead(url) { coordinatedURL in
      let values = try coordinatedURL.resourceValues(forKeys: [.isDirectoryKey])
      guard values.isDirectory != true else {
        // A directory at one of the private file paths is an invalid target.
        // Refuse the reset before touching any other file.
        throw FoodBlobPersistenceError.coordinatedFileAccessFailed
      }
      return try Data(contentsOf: coordinatedURL)
    }
    return ResetFileBackup(
      url: url,
      data: data,
      mergeAppendOnly: false
    )
  }

  private func removeResetInput(at url: URL) throws -> ResetFileBackup {
    guard FileManager.default.fileExists(atPath: url.path) else {
      return ResetFileBackup(url: url, data: nil, mergeAppendOnly: true)
    }
    var data: Data?
    try coordinatedWrite(url, options: .forDeleting) { coordinatedURL in
      guard FileManager.default.fileExists(atPath: coordinatedURL.path)
      else {
        return
      }
      let values = try coordinatedURL.resourceValues(forKeys: [.isDirectoryKey])
      guard values.isDirectory != true else {
        throw FoodBlobPersistenceError.coordinatedFileAccessFailed
      }
      data = try Data(contentsOf: coordinatedURL)
      try FileManager.default.removeItem(at: coordinatedURL)
    }
    return ResetFileBackup(url: url, data: data, mergeAppendOnly: true)
  }

  private func restoreResetBackups(_ backups: [ResetFileBackup]) throws {
    for backup in backups {
      if let data = backup.data {
        if backup.mergeAppendOnly {
          try restoreAppendOnlyFile(data, at: backup.url)
        } else {
          try coordinatedWrite(backup.url) { coordinatedURL in
            try data.write(to: coordinatedURL, options: .atomic)
          }
        }
      } else if FileManager.default.fileExists(atPath: backup.url.path) {
        let values = try backup.url.resourceValues(forKeys: [.isDirectoryKey])
        guard values.isDirectory != true else {
          throw FoodBlobPersistenceError.coordinatedFileAccessFailed
        }
        if !backup.mergeAppendOnly {
          try coordinatedWrite(backup.url, options: .forDeleting) { coordinatedURL in
            guard FileManager.default.fileExists(atPath: coordinatedURL.path)
            else { return }
            try FileManager.default.removeItem(at: coordinatedURL)
          }
        }
      }
    }
  }

  private func restoreAppendOnlyFile(_ backup: Data, at url: URL) throws {
    try coordinatedWrite(url) { coordinatedURL in
      let current =
        FileManager.default.fileExists(atPath: coordinatedURL.path)
        ? try Data(contentsOf: coordinatedURL)
        : Data()
      let merged = Self.mergeAppendOnlyData(backup, with: current)
      try merged.write(to: coordinatedURL, options: .atomic)
    }
  }

  private func fileURL(named fileName: String) -> URL? {
    guard let containerURL else { return nil }
    return containerURL.appendingPathComponent(fileName)
  }

  private func makeSnapshot(
    from document: FoodStateDocument,
    now: Date,
    calendar: Calendar
  ) -> FoodBlobSnapshot {
    let dateKey = FoodDateKey.string(for: now, calendar: calendar)
    return FoodBlobSnapshot(
      generatedAt: now,
      dateKey: dateKey,
      counts: document.counts(for: dateKey),
      skin: document.selectedSkin,
      widgetLayout: document.selectedWidgetLayout,
      consumedWidgetIDs: document.consumedWidgetIDs
    )
  }

  private func write(_ data: Data, to fileName: String) throws {
    guard let url = fileURL(named: fileName) else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    try coordinatedWrite(url) { coordinatedURL in
      try data.write(to: coordinatedURL, options: .atomic)
    }
  }

  private func contentsIfPresent(at url: URL) throws -> String {
    guard FileManager.default.fileExists(atPath: url.path) else {
      return ""
    }
    return try String(contentsOf: url, encoding: .utf8)
  }

  private func coordinatedWrite(
    _ firstURL: URL,
    options firstOptions: NSFileCoordinator.WritingOptions,
    _ secondURL: URL,
    options secondOptions: NSFileCoordinator.WritingOptions,
    body: @escaping (URL, URL) throws -> Void
  ) throws {
    var coordinationError: NSError?
    var bodyResult: Result<Void, Error>?
    NSFileCoordinator().coordinate(
      writingItemAt: firstURL,
      options: firstOptions,
      writingItemAt: secondURL,
      options: secondOptions,
      error: &coordinationError
    ) { coordinatedFirstURL, coordinatedSecondURL in
      bodyResult = Result {
        try body(coordinatedFirstURL, coordinatedSecondURL)
      }
    }
    if let coordinationError {
      throw coordinationError
    }
    guard let bodyResult else {
      throw FoodBlobPersistenceError.coordinatedFileAccessFailed
    }
    try bodyResult.get()
  }

  private func coordinatedWrite(
    _ url: URL,
    options: NSFileCoordinator.WritingOptions = [],
    body: @escaping (URL) throws -> Void
  ) throws {
    var coordinationError: NSError?
    var bodyResult: Result<Void, Error>?
    NSFileCoordinator().coordinate(
      writingItemAt: url,
      options: options,
      error: &coordinationError
    ) { coordinatedURL in
      bodyResult = Result { try body(coordinatedURL) }
    }
    if let coordinationError {
      throw coordinationError
    }
    guard let bodyResult else {
      throw FoodBlobPersistenceError.coordinatedFileAccessFailed
    }
    try bodyResult.get()
  }

  private func coordinatedRead<T>(
    _ url: URL,
    body: @escaping (URL) throws -> T
  ) throws -> T {
    var coordinationError: NSError?
    var bodyResult: Result<T, Error>?
    NSFileCoordinator().coordinate(
      readingItemAt: url,
      options: [],
      error: &coordinationError
    ) { coordinatedURL in
      bodyResult = Result { try body(coordinatedURL) }
    }
    if let coordinationError {
      throw coordinationError
    }
    guard let bodyResult else {
      throw FoodBlobPersistenceError.coordinatedFileAccessFailed
    }
    return try bodyResult.get()
  }
}

extension FoodBlobSnapshot {
  fileprivate func hasSamePayload(as other: FoodBlobSnapshot) -> Bool {
    schemaVersion == other.schemaVersion
      && dateKey == other.dateKey
      && counts == other.counts
      && skin == other.skin
      && widgetLayout == other.widgetLayout
      && consumedWidgetIDs == other.consumedWidgetIDs
  }
}

extension FoodWidgetState {
  func resettingCounts(at date: Date) -> FoodWidgetState {
    FoodWidgetState(
      date: date,
      counts: FoodCounts(),
      skin: skin,
      widgetLayout: widgetLayout,
      isAvailable: isAvailable
    )
  }
}
