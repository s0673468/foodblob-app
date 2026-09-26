import Foundation

enum FoodWatchReceiptStoreError: LocalizedError, Equatable {
  case appGroupUnavailable
  case unreadable

  var errorDescription: String? {
    switch self {
    case .appGroupUnavailable:
      "Food Blob could not open its watch receipt store."
    case .unreadable:
      "Food Blob could not read its watch receipt store."
    }
  }
}

struct FoodWatchCommittedReceipt: Codable, Equatable, Sendable {
  let sequence: Int64
  let id: UUID
  let senderID: UUID

  init(
    sequence: Int64,
    id: UUID,
    senderID: UUID = FoodWatchIdentity.legacySenderID
  ) {
    self.sequence = sequence
    self.id = id
    self.senderID = senderID
  }

  private enum CodingKeys: String, CodingKey {
    case sequence
    case id
    case senderID
  }

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    sequence = try values.decode(Int64.self, forKey: .sequence)
    id = try values.decode(UUID.self, forKey: .id)
    senderID =
      try values.decodeIfPresent(UUID.self, forKey: .senderID)
      ?? FoodWatchIdentity.legacySenderID
  }
}

struct FoodWatchSenderSequenceFloor: Codable, Equatable, Sendable {
  let senderID: UUID
  var sequence: Int64
}

struct FoodWatchReceiptDocument: Codable, Equatable, Sendable {
  static let supportedSchemaVersion = 5

  private enum CodingKeys: String, CodingKey {
    case schemaVersion
    case pendingEvents
    case committedReceipts
    case committedThroughSequence
    case committedSequenceFloors
    case resetPending
    case resetThroughSequence
    case resetAt
    case activeSenderID
    case resetReady
    case resetSenderID
    case resetGeneration
    case resetPreviousGeneration
  }

  private enum LegacyCodingKeys: String, CodingKey {
    case legacyPendingEntries = "pendingEntries"
    case legacyCommittedEventIDs = "committedEventIDs"
  }

  var schemaVersion: Int = Self.supportedSchemaVersion
  var pendingEvents: [FoodWatchTransferEvent] = []
  var committedReceipts: [FoodWatchCommittedReceipt] = []
  var committedThroughSequence: Int64 = 0
  var committedSequenceFloors: [FoodWatchSenderSequenceFloor] = []
  var resetPending = false
  var resetThroughSequence: Int64?
  var resetAt: Date?
  var activeSenderID: UUID?
  var resetReady = false
  var resetSenderID: UUID?
  var resetGeneration: UUID?
  var resetPreviousGeneration: UUID?

  init() {}

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    let legacyValues = try decoder.container(keyedBy: LegacyCodingKeys.self)
    schemaVersion =
      try values.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 0
    guard schemaVersion <= Self.supportedSchemaVersion else {
      throw DecodingError.dataCorruptedError(
        forKey: .schemaVersion,
        in: values,
        debugDescription: "Watch receipts were written by a newer version."
      )
    }

    pendingEvents =
      try values.decodeIfPresent(
        [FoodWatchTransferEvent].self,
        forKey: .pendingEvents
      ) ?? []
    committedReceipts =
      try values.decodeIfPresent(
        [FoodWatchCommittedReceipt].self,
        forKey: .committedReceipts
      ) ?? []
    committedThroughSequence =
      try values.decodeIfPresent(
        Int64.self,
        forKey: .committedThroughSequence
      ) ?? 0
    committedSequenceFloors =
      try values.decodeIfPresent(
        [FoodWatchSenderSequenceFloor].self,
        forKey: .committedSequenceFloors
      ) ?? []
    resetPending =
      try values.decodeIfPresent(Bool.self, forKey: .resetPending) ?? false
    resetThroughSequence =
      try values.decodeIfPresent(
        Int64.self,
        forKey: .resetThroughSequence
      )
    resetAt = try values.decodeIfPresent(Date.self, forKey: .resetAt)
    activeSenderID = try values.decodeIfPresent(UUID.self, forKey: .activeSenderID)
    resetReady =
      try values.decodeIfPresent(Bool.self, forKey: .resetReady)
      ?? (schemaVersion < Self.supportedSchemaVersion && resetPending)
    resetSenderID = try values.decodeIfPresent(UUID.self, forKey: .resetSenderID)
    resetGeneration = try values.decodeIfPresent(
      UUID.self,
      forKey: .resetGeneration
    )
    resetPreviousGeneration = try values.decodeIfPresent(
      UUID.self,
      forKey: .resetPreviousGeneration
    )

    // The old shape was only present in development builds. Keeping a small
    // compatibility read makes an interrupted local rollout recoverable.
    if pendingEvents.isEmpty {
      let legacyEntries =
        try legacyValues.decodeIfPresent(
          [FoodWidgetLedgerEntry].self,
          forKey: .legacyPendingEntries
        ) ?? []
      let firstSequence = max(committedThroughSequence, 0) + 1
      pendingEvents = legacyEntries.enumerated().map { offset, entry in
        FoodWatchTransferEvent(
          sequence: firstSequence + Int64(offset),
          entry: entry
        )
      }
    }
    if committedReceipts.isEmpty {
      let legacyIDs =
        try legacyValues.decodeIfPresent(
          [UUID].self,
          forKey: .legacyCommittedEventIDs
        ) ?? []
      committedReceipts = legacyIDs.map {
        FoodWatchCommittedReceipt(sequence: 0, id: $0)
      }
    }
    if activeSenderID == nil {
      activeSenderID =
        pendingEvents.first?.senderID
        ?? committedReceipts.first?.senderID
        ?? (committedThroughSequence > 0
          ? FoodWatchIdentity.legacySenderID
          : nil)
    }
    if resetSenderID == nil, resetThroughSequence != nil {
      resetSenderID = activeSenderID
    }
    if let activeSenderID {
      let existingFloor =
        committedSequenceFloors.first {
          $0.senderID == activeSenderID
        }?.sequence ?? 0
      let floor = max(existingFloor, committedThroughSequence)
      if let index = committedSequenceFloors.firstIndex(where: {
        $0.senderID == activeSenderID
      }) {
        committedSequenceFloors[index].sequence = floor
      } else if floor > 0 {
        committedSequenceFloors.append(
          FoodWatchSenderSequenceFloor(
            senderID: activeSenderID,
            sequence: floor
          )
        )
      }
      committedThroughSequence = floor
    }
    schemaVersion = Self.supportedSchemaVersion
  }
}

enum FoodWatchReceiptStage: Equatable {
  case staged
  case alreadyPending
  case alreadyCommitted
  case resetInProgress
}

struct FoodWatchReceiptAcknowledgementState: Equatable, Sendable {
  let committedEventIDs: [UUID]
  let committedThroughSequence: Int64
  let committedThroughSequencesBySender: [UUID: Int64]
  let senderID: UUID?
  let isReset: Bool
  let resetThroughSequence: Int64?
  let resetGeneration: UUID?
  let isResetStagedButNotReady: Bool
}

final class FoodWatchReceiptStore: @unchecked Sendable {
  private let fileURL: URL?
  private let lock = NSLock()

  init(
    containerURL: URL? = FileManager.default.containerURL(
      forSecurityApplicationGroupIdentifier: FoodBlobConstants.appGroupIdentifier
    )
  ) {
    if let containerURL {
      try? FileManager.default.createDirectory(
        at: containerURL,
        withIntermediateDirectories: true
      )
      fileURL = containerURL.appendingPathComponent(
        FoodBlobConstants.watchReceiptFileName
      )
    } else {
      fileURL = nil
    }
  }

  @discardableResult
  func stage(_ event: FoodWatchTransferEvent) throws -> FoodWatchReceiptStage {
    try withLock {
      var document = try load()
      if document.committedReceipts.contains(where: { $0.id == event.entry.id }) {
        return .alreadyCommitted
      }
      if document.resetPending && !document.resetReady {
        return .resetInProgress
      }
      if document.resetGeneration == nil,
        document.resetAt.map({ event.entry.timestamp <= $0 }) == true
      {
        // Receipts written before reset generations existed still carry a
        // timestamp boundary. Keep the old tombstone behavior during this
        // migration window so a queued pre-reset event cannot resurrect data.
        let legacyBoundary =
          document.resetSenderID == event.senderID
          ? document.resetThroughSequence ?? 0
          : 0
        let senderFloor = max(
          sequenceFloor(for: event.senderID, in: document),
          legacyBoundary
        )
        if document.activeSenderID != event.senderID {
          document.activeSenderID = event.senderID
          document.committedThroughSequence = senderFloor
        } else if senderFloor > document.committedThroughSequence {
          document.committedThroughSequence = senderFloor
          setSequenceFloor(senderFloor, for: event.senderID, in: &document)
        }
        if event.sequence <= senderFloor {
          try save(document)
          return .alreadyCommitted
        }
        if !document.committedReceipts.contains(where: { $0.id == event.entry.id }) {
          document.committedReceipts.append(
            FoodWatchCommittedReceipt(
              sequence: event.sequence,
              id: event.entry.id,
              senderID: event.senderID
            )
          )
          advanceContiguousSequence(in: &document)
          try save(document)
        }
        return .alreadyCommitted
      }
      if let resetGeneration = document.resetGeneration {
        guard event.resetGeneration == resetGeneration else {
          // Events carry the phone reset epoch. A missing or older epoch is
          // a queued pre-reset action, not a new action with a bad clock.
          return .resetInProgress
        }
      } else if let eventResetGeneration = event.resetGeneration {
        // A migrated phone may see an already-epoch-tagged watch first. Adopt
        // that epoch before staging so future events use one boundary.
        document.resetGeneration = eventResetGeneration
        try save(document)
      }
      let senderFloor = sequenceFloor(for: event.senderID, in: document)
      if event.sequence <= senderFloor {
        // A delayed delivery from an already-committed Watch installation
        // must not steal the acknowledgement namespace from the active Watch.
        return .alreadyCommitted
      }
      if document.activeSenderID != event.senderID {
        // Floors are sender-scoped. A reinstalled watch must start from its
        // own floor rather than inheriting the previous installation's
        // sequence counter.
        document.activeSenderID = event.senderID
        document.committedThroughSequence = senderFloor
        // Persist the namespace switch even when this delivery is a duplicate
        // of an event already waiting on disk. The next ready-prefix retry
        // must inspect the sender that supplied this event after a relaunch.
        try save(document)
      }
      if document.committedReceipts.contains(where: {
        $0.senderID == event.senderID && $0.sequence == event.sequence
      }) {
        return .alreadyCommitted
      }
      if document.pendingEvents.contains(where: {
        $0.entry.id == event.entry.id
          || ($0.senderID == event.senderID && $0.sequence == event.sequence)
      }) {
        return .alreadyPending
      }
      document.pendingEvents.append(event)
      try save(document)
      return .staged
    }
  }

  func pendingEvents() throws -> [FoodWatchTransferEvent] {
    try withLock { try load().pendingEvents }
  }

  /// Returns only the active sender's contiguous prefix. WatchConnectivity
  /// may deliver sequence 2 before sequence 1; withholding the suffix keeps
  /// append-only ledger order identical to tap order.
  func pendingEventsReadyForIngest() throws -> [FoodWatchTransferEvent] {
    try withLock {
      var document = try load()
      guard let senderID = document.activeSenderID else { return [] }

      // A receipt can be committed while another sender is active. Reconcile
      // that sender's committed prefix before looking for the next pending
      // event; otherwise a later sequence can wait forever for a predecessor
      // that is already recorded in `committedReceipts`.
      let beforeAdvance = document
      advanceContiguousSequence(in: &document)
      if document != beforeAdvance {
        try save(document)
      }

      var expected = sequenceFloor(for: senderID, in: document) + 1
      let sorted = document.pendingEvents
        .filter { $0.senderID == senderID }
        .sorted { $0.sequence < $1.sequence }
      var ready: [FoodWatchTransferEvent] = []
      for event in sorted {
        if event.sequence < expected { continue }
        guard event.sequence == expected else { break }
        ready.append(event)
        expected += 1
      }
      return ready
    }
  }

  func pendingEntries() throws -> [FoodWidgetLedgerEntry] {
    try withLock { try load().pendingEvents.map(\.entry) }
  }

  /// Projects every field needed for one acknowledgement from the same
  /// receipt document version. The value is detached before any snapshot I/O
  /// or WatchConnectivity calls, so the store lock never crosses subsystems.
  func acknowledgementState() throws -> FoodWatchReceiptAcknowledgementState {
    try withLock {
      let document = try load()
      var committedThroughSequencesBySender = Dictionary(
        uniqueKeysWithValues: document.committedSequenceFloors.map {
          ($0.senderID, $0.sequence)
        }
      )
      if let activeSenderID = document.activeSenderID {
        committedThroughSequencesBySender[activeSenderID] = max(
          committedThroughSequencesBySender[activeSenderID] ?? 0,
          document.committedThroughSequence
        )
      }
      return FoodWatchReceiptAcknowledgementState(
        committedEventIDs: document.committedReceipts.map(\.id),
        committedThroughSequence: document.committedThroughSequence,
        committedThroughSequencesBySender:
          committedThroughSequencesBySender,
        senderID: document.activeSenderID,
        isReset: document.resetPending && document.resetReady,
        resetThroughSequence: document.resetThroughSequence,
        resetGeneration: document.resetGeneration,
        isResetStagedButNotReady:
          document.resetPending && !document.resetReady
      )
    }
  }

  func committedEventIDs() throws -> [UUID] {
    try withLock { try load().committedReceipts.map(\.id) }
  }

  func committedThroughSequence() throws -> Int64 {
    try withLock { try load().committedThroughSequence }
  }

  func activeSenderID() throws -> UUID? {
    try withLock { try load().activeSenderID }
  }

  func resetGeneration() throws -> UUID? {
    try withLock { try load().resetGeneration }
  }

  func resetPending() throws -> Bool {
    try withLock {
      let document = try load()
      return document.resetPending && document.resetReady
    }
  }

  func resetStagedButNotReady() throws -> Bool {
    try withLock {
      let document = try load()
      return document.resetPending && !document.resetReady
    }
  }

  func resetThroughSequence() throws -> Int64? {
    try withLock { try load().resetThroughSequence }
  }

  /// Commits only IDs that were staged as watch events. Other widget ledger
  /// entries are intentionally ignored because the phone widget shares the
  /// same ingestion path.
  func commit(_ entries: [FoodWidgetLedgerEntry]) throws {
    guard !entries.isEmpty else { return }
    try withLock {
      var document = try load()
      let ids = Set(entries.map(\.id))
      let staged = document.pendingEvents.filter { ids.contains($0.entry.id) }
      guard !staged.isEmpty else { return }

      document.pendingEvents.removeAll { ids.contains($0.entry.id) }
      var knownIDs = Set(document.committedReceipts.map(\.id))
      var knownSequenceKeys = Set(
        document.committedReceipts.map {
          "\($0.senderID.uuidString):\($0.sequence)"
        }
      )
      for event in staged {
        let sequenceKey = "\(event.senderID.uuidString):\(event.sequence)"
        guard knownIDs.insert(event.entry.id).inserted,
          knownSequenceKeys.insert(sequenceKey).inserted
        else {
          continue
        }
        document.committedReceipts.append(
          FoodWatchCommittedReceipt(
            sequence: event.sequence,
            id: event.entry.id,
            senderID: event.senderID
          )
        )
      }
      advanceContiguousSequence(in: &document)
      try save(document)
    }
  }

  /// Clears phone history and records a durable boundary for queued watch
  /// actions. The boundary survives acknowledgement so a late duplicate can
  /// never resurrect deleted data.
  func reset(at date: Date = Date()) throws {
    try withLock {
      var document = try load()
      let senderID = document.activeSenderID ?? FoodWatchIdentity.legacySenderID
      let knownSequences =
        document.pendingEvents.filter {
          $0.senderID == senderID
        }.map(\.sequence)
        + document.committedReceipts.compactMap {
          guard $0.senderID == senderID, $0.sequence > 0
          else { return nil }
          return $0.sequence
        }
      let boundary = max(
        sequenceFloor(
          for: senderID,
          in: document
        ),
        max(
          document.resetSenderID == senderID
            ? document.resetThroughSequence ?? 0
            : 0,
          knownSequences.max() ?? 0
        )
      )
      document.resetPending = true
      document.resetReady = false
      document.resetThroughSequence = boundary
      document.resetAt = date
      document.resetSenderID = senderID
      document.resetPreviousGeneration = document.resetGeneration
      document.resetGeneration = UUID()
      try save(document)
    }
  }

  /// Makes a staged reset visible to the watch only after phone persistence
  /// has been reset successfully. The old receipt state remains available
  /// until this commit point so a failed reset can be cancelled safely.
  func markResetReady() throws {
    try withLock {
      var document = try load()
      guard document.resetPending else { return }
      document.pendingEvents.removeAll()
      document.committedReceipts.removeAll()
      // Sequence numbers restart in the new reset generation. The old
      // boundary remains in `resetThroughSequence` for the watch's one reset
      // acknowledgement, while the receipt floor tracks only this epoch.
      document.committedThroughSequence = 0
      document.committedSequenceFloors.removeAll()
      document.resetReady = true
      document.resetPreviousGeneration = nil
      try save(document)
    }
  }

  /// Cancels a staged reset without dropping the pre-reset receipt state.
  func cancelReset() throws {
    try withLock {
      var document = try load()
      guard document.resetPending && !document.resetReady else { return }
      document.resetPending = false
      document.resetReady = false
      document.resetThroughSequence = nil
      document.resetAt = nil
      document.resetGeneration = document.resetPreviousGeneration
      document.resetPreviousGeneration = nil
      document.resetSenderID = document.activeSenderID
      try save(document)
    }
  }

  /// Finishes an interrupted staged reset only after comparing the phone
  /// snapshot and state written by `FoodBlobPersistence`. If completion is
  /// uncertain, the staged boundary is preserved and acknowledgements remain
  /// suppressed until Delete All succeeds or the user retries it.
  func recoverReset(
    using snapshot: FoodBlobSnapshot?,
    phoneStateIsEmpty: Bool = false
  ) throws {
    try withLock {
      var document = try load()
      guard document.resetPending && !document.resetReady,
        let resetAt = document.resetAt
      else { return }
      if phoneStateIsEmpty, let snapshot, snapshot.generatedAt >= resetAt {
        document.pendingEvents.removeAll()
        document.committedReceipts.removeAll()
        document.committedThroughSequence = 0
        document.committedSequenceFloors.removeAll()
        document.resetReady = true
        document.resetPreviousGeneration = nil
      }
      try save(document)
    }
  }

  func clearResetPending() throws {
    try withLock {
      var document = try load()
      guard document.resetPending else { return }
      document.resetPending = false
      document.resetReady = false
      document.resetPreviousGeneration = nil
      try save(document)
    }
  }

  private func advanceContiguousSequence(in document: inout FoodWatchReceiptDocument) {
    guard let activeSenderID = document.activeSenderID else { return }
    var sequences: Set<Int64> = Set(
      document.committedReceipts.compactMap {
        guard $0.senderID == activeSenderID, $0.sequence > 0
        else { return nil }
        return $0.sequence
      }
    )
    var floor = sequenceFloor(for: activeSenderID, in: document)
    var next = floor + 1
    while sequences.remove(next) != nil {
      floor = next
      next += 1
    }
    document.committedThroughSequence = floor
    setSequenceFloor(floor, for: activeSenderID, in: &document)
    document.committedReceipts.removeAll {
      $0.senderID == activeSenderID
        && $0.sequence > 0
        && $0.sequence <= floor
    }
  }

  private func sequenceFloor(
    for senderID: UUID,
    in document: FoodWatchReceiptDocument
  ) -> Int64 {
    let stored =
      document.committedSequenceFloors.first {
        $0.senderID == senderID
      }?.sequence ?? 0
    if senderID == document.activeSenderID {
      return max(stored, document.committedThroughSequence)
    }
    return stored
  }

  private func setSequenceFloor(
    _ sequence: Int64,
    for senderID: UUID,
    in document: inout FoodWatchReceiptDocument
  ) {
    if let index = document.committedSequenceFloors.firstIndex(where: {
      $0.senderID == senderID
    }) {
      document.committedSequenceFloors[index].sequence = max(
        document.committedSequenceFloors[index].sequence,
        sequence
      )
    } else if sequence > 0 {
      document.committedSequenceFloors.append(
        FoodWatchSenderSequenceFloor(senderID: senderID, sequence: sequence)
      )
    }
  }

  private func load() throws -> FoodWatchReceiptDocument {
    guard let fileURL else { throw FoodWatchReceiptStoreError.appGroupUnavailable }
    guard FileManager.default.fileExists(atPath: fileURL.path) else {
      return FoodWatchReceiptDocument()
    }
    do {
      let data = try Data(contentsOf: fileURL)
      let decoder = JSONDecoder()
      decoder.dateDecodingStrategy = .iso8601
      return try decoder.decode(FoodWatchReceiptDocument.self, from: data)
    } catch let error as FoodWatchReceiptStoreError {
      throw error
    } catch {
      throw FoodWatchReceiptStoreError.unreadable
    }
  }

  private func save(_ document: FoodWatchReceiptDocument) throws {
    guard let fileURL else { throw FoodWatchReceiptStoreError.appGroupUnavailable }
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    encoder.outputFormatting = [.sortedKeys]
    let data = try encoder.encode(document)
    try data.write(to: fileURL, options: .atomic)
  }

  private func withLock<T>(_ body: () throws -> T) rethrows -> T {
    lock.lock()
    defer { lock.unlock() }
    return try body()
  }
}
