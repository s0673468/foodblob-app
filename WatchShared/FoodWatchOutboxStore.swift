import Foundation

#if FOOD_BLOB_TESTS
  @testable import FoodBlob
#endif

enum FoodWatchConstants {
  static let appGroupIdentifier = "group.org.example.foodblob.watch"
  static let outboxFileName = "food_blob_watch_outbox.json"
  static let widgetKind = "FoodBlobWatchComplicationV1"
}

struct FoodWatchOutboxDocument: Codable, Equatable, Sendable {
  static let supportedSchemaVersion = 4

  private enum CodingKeys: String, CodingKey {
    case schemaVersion
    case events
    case nextSequence
    case acknowledgement
    case senderID
    case resetGeneration
  }

  private enum LegacyCodingKeys: String, CodingKey {
    case legacyEntries = "entries"
  }

  var schemaVersion: Int = Self.supportedSchemaVersion
  var events: [FoodWatchTransferEvent] = []
  var nextSequence: Int64 = 1
  var acknowledgement: FoodWatchAcknowledgement?
  var senderID: UUID = UUID()
  var resetGeneration: UUID?

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
        debugDescription: "Watch outbox was written by a newer version."
      )
    }
    let decodedEvents =
      try values.decodeIfPresent(
        [FoodWatchTransferEvent].self,
        forKey: .events
      ) ?? []
    nextSequence =
      try values.decodeIfPresent(Int64.self, forKey: .nextSequence) ?? 1
    acknowledgement =
      try values.decodeIfPresent(
        FoodWatchAcknowledgement.self,
        forKey: .acknowledgement
      )
    senderID =
      try values.decodeIfPresent(UUID.self, forKey: .senderID)
      ?? decodedEvents.first(where: {
        $0.senderID != FoodWatchIdentity.legacySenderID
      })?.senderID
      ?? FoodWatchIdentity.legacySenderID
    resetGeneration =
      try values.decodeIfPresent(
        UUID.self,
        forKey: .resetGeneration
      ) ?? acknowledgement?.resetGeneration
    events = decodedEvents.map { event in
      FoodWatchTransferEvent(
        sequence: event.sequence,
        entry: event.entry,
        senderID: event.senderID == FoodWatchIdentity.legacySenderID
          ? senderID
          : event.senderID,
        resetGeneration: event.resetGeneration ?? resetGeneration
      )
    }

    if events.isEmpty {
      let legacyEntries =
        try legacyValues.decodeIfPresent(
          [FoodWidgetLedgerEntry].self,
          forKey: .legacyEntries
        ) ?? []
      events = legacyEntries.enumerated().map { offset, entry in
        FoodWatchTransferEvent(
          sequence: Int64(offset + 1),
          entry: entry,
          senderID: senderID,
          resetGeneration: resetGeneration
        )
      }
      nextSequence = max(
        nextSequence,
        (events.map(\.sequence).max() ?? 0) + 1
      )
    }
    nextSequence = max(nextSequence, (events.map(\.sequence).max() ?? 0) + 1)
    nextSequence = max(nextSequence, 1)
    schemaVersion = Self.supportedSchemaVersion
  }
}

struct FoodWatchDisplayState: Equatable, Sendable {
  let dateKey: String
  let counts: FoodCounts
  let skin: SkinID
  let pendingCount: Int
  let isAvailable: Bool
  let hasPhoneState: Bool
}

enum FoodWatchOutboxError: LocalizedError {
  case appGroupUnavailable
  case unreadable
  case sequenceExhausted

  var errorDescription: String? {
    switch self {
    case .appGroupUnavailable:
      "Food Blob could not open its watch notebook."
    case .unreadable:
      "Food Blob could not read its watch notebook."
    case .sequenceExhausted:
      "Food Blob could not create another watch action."
    }
  }
}

final class FoodWatchOutboxStore: @unchecked Sendable {
  private let fileURL: URL?
  private let lock = NSLock()

  init(
    containerURL: URL? = FileManager.default.containerURL(
      forSecurityApplicationGroupIdentifier: FoodWatchConstants.appGroupIdentifier
    )
  ) {
    if let containerURL {
      try? FileManager.default.createDirectory(
        at: containerURL,
        withIntermediateDirectories: true
      )
      fileURL = containerURL.appendingPathComponent(
        FoodWatchConstants.outboxFileName
      )
    } else {
      fileURL = nil
    }
  }

  @discardableResult
  func append(_ entry: FoodWidgetLedgerEntry) throws -> Bool {
    try withLock {
      var document = try load()
      guard !document.events.contains(where: { $0.entry.id == entry.id }) else {
        return false
      }
      guard document.nextSequence < Int64.max else {
        throw FoodWatchOutboxError.sequenceExhausted
      }
      let event = FoodWatchTransferEvent(
        sequence: document.nextSequence,
        entry: entry,
        senderID: document.senderID,
        resetGeneration: document.resetGeneration
      )
      document.events.append(event)
      document.nextSequence += 1
      try save(document)
      return true
    }
  }

  func pendingEvents() -> [FoodWatchTransferEvent] {
    (try? withLock { try load().events }) ?? []
  }

  func pendingEntries() -> [FoodWidgetLedgerEntry] {
    pendingEvents().map(\.entry)
  }

  @discardableResult
  func apply(_ acknowledgement: FoodWatchAcknowledgement) throws -> Bool {
    try withLock {
      var document = try load()
      var acknowledgement = acknowledgement
      if acknowledgement.senderID != document.senderID,
        let senderFloor = acknowledgement
          .committedThroughSequencesBySender?[document.senderID]
      {
        if let current = document.acknowledgement {
          let repeatsCurrentSnapshot =
            acknowledgement.generatedAt == current.generatedAt
            && acknowledgement.dateKey == current.dateKey
            && acknowledgement.counts == current.counts
            && acknowledgement.skin == current.skin
            && acknowledgement.isReset == current.isReset
            && acknowledgement.resetGeneration == current.resetGeneration
          guard acknowledgement.generatedAt > current.generatedAt
            || repeatsCurrentSnapshot
          else { return false }
        }
        // First prove the phone snapshot is not stale. Only then may the
        // sender-specific floor be projected into this Watch's namespace.
        acknowledgement = FoodWatchAcknowledgement(
          dateKey: acknowledgement.dateKey,
          counts: acknowledgement.counts,
          skin: acknowledgement.skin,
          generatedAt: acknowledgement.generatedAt,
          committedEventIDs: acknowledgement.committedEventIDs,
          committedThroughSequence: senderFloor,
          committedThroughSequencesBySender:
            acknowledgement.committedThroughSequencesBySender,
          isReset: acknowledgement.isReset,
          resetThroughSequence: nil,
          senderID: document.senderID,
          resetGeneration: acknowledgement.resetGeneration
        )
      } else if let current = document.acknowledgement,
        acknowledgement.senderID != document.senderID,
        current.senderID == document.senderID
          || (current.senderID == nil
            && document.senderID == FoodWatchIdentity.legacySenderID)
      {
        guard acknowledgement.generatedAt > current.generatedAt else {
          return false
        }
        // Counts, date, skin, and reset generation describe the phone's global
        // state. Sequence floors and committed IDs belong to one Watch
        // installation. Preserve the current Watch's receipt namespace while
        // still accepting a newer global snapshot that was published after a
        // delayed old-installation event reached the phone.
        acknowledgement = FoodWatchAcknowledgement(
          dateKey: acknowledgement.dateKey,
          counts: acknowledgement.counts,
          skin: acknowledgement.skin,
          generatedAt: acknowledgement.generatedAt,
          committedEventIDs: current.committedEventIDs,
          committedThroughSequence: current.committedThroughSequence,
          committedThroughSequencesBySender:
            acknowledgement.committedThroughSequencesBySender,
          isReset: acknowledgement.isReset,
          resetThroughSequence: nil,
          senderID: current.senderID,
          resetGeneration: acknowledgement.resetGeneration
        )
      }
      if let current = document.acknowledgement,
        isOlder(
          acknowledgement,
          than: current,
          documentSenderID: document.senderID
        )
      {
        return false
      }
      let previousEvents = document.events
      let previousAcknowledgement = document.acknowledgement
      let committedIDs = Set(acknowledgement.committedEventIDs)
      let senderMatches = acknowledgement.senderID == document.senderID
      if let resetGeneration = acknowledgement.resetGeneration,
        resetGeneration != document.resetGeneration
      {
        // The phone has crossed a Delete All boundary. Events from the old
        // epoch are no longer safe to replay, even when their sequence is
        // newer than the old reset floor or their clocks are ahead.
        document.events.removeAll { $0.resetGeneration != resetGeneration }
        document.resetGeneration = resetGeneration
        // Sequence numbers are scoped by the reset generation. Restarting at
        // one avoids carrying a gap from actions that the reset just dropped.
        document.nextSequence = max(
          1,
          (document.events.map(\.sequence).max() ?? 0) + 1
        )
      }
      // A reset boundary belongs to the generation that existed before the
      // reset. Once the new generation is installed, applying that old
      // sequence floor to generation-scoped sequence 1 would drop a fresh
      // tap. Keep the legacy reset floor only for legacy, nil-generation
      // acknowledgements.
      let throughSequence: Int64
      if senderMatches {
        if acknowledgement.resetGeneration == nil,
          document.resetGeneration == nil,
          acknowledgement.isReset
        {
          throughSequence = max(
            acknowledgement.committedThroughSequence,
            acknowledgement.resetThroughSequence ?? 0
          )
        } else {
          throughSequence = acknowledgement.committedThroughSequence
        }
      } else {
        throughSequence = 0
      }
      document.events.removeAll {
        committedIDs.contains($0.entry.id)
          || ($0.resetGeneration == document.resetGeneration
            && $0.sequence <= throughSequence)
      }
      document.acknowledgement = acknowledgement
      try save(document)
      return previousEvents != document.events
        || previousAcknowledgement?.hasSamePayload(as: acknowledgement) != true
    }
  }

  private func isOlder(
    _ acknowledgement: FoodWatchAcknowledgement,
    than current: FoodWatchAcknowledgement,
    documentSenderID: UUID
  ) -> Bool {
    let incomingIsCurrentSender =
      acknowledgement.senderID == documentSenderID
      || (acknowledgement.senderID == nil
        && documentSenderID == FoodWatchIdentity.legacySenderID)
    let currentIsCurrentSender =
      current.senderID == documentSenderID
      || (current.senderID == nil
        && documentSenderID == FoodWatchIdentity.legacySenderID)
    if currentIsCurrentSender && !incomingIsCurrentSender {
      return true
    }
    if incomingIsCurrentSender && !currentIsCurrentSender {
      return false
    }
    if acknowledgement.senderID != current.senderID {
      return current.generatedAt >= acknowledgement.generatedAt
    }
    let incomingFloor = max(
      acknowledgement.committedThroughSequence,
      acknowledgement.resetThroughSequence ?? 0
    )
    let currentFloor = max(
      current.committedThroughSequence,
      current.resetThroughSequence ?? 0
    )
    if incomingFloor != currentFloor {
      if acknowledgement.senderID != current.senderID {
        return false
      }
      return incomingFloor < currentFloor
    }
    if acknowledgement.senderID != current.senderID {
      return false
    }
    return acknowledgement.generatedAt < current.generatedAt
  }

  func displayState(
    at date: Date = Date(),
    calendar: Calendar = .autoupdatingCurrent
  ) -> FoodWatchDisplayState {
    guard let document = try? withLock({ try load() }) else {
      return FoodWatchDisplayState(
        dateKey: FoodDateKey.string(for: date, calendar: calendar),
        counts: FoodCounts(),
        skin: .skyMeadow,
        pendingCount: 0,
        isAvailable: false,
        hasPhoneState: false
      )
    }

    let dateKey = FoodDateKey.string(for: date, calendar: calendar)
    let hasCurrentPhoneState = document.acknowledgement?.dateKey == dateKey
    var counts =
      hasCurrentPhoneState
      ? document.acknowledgement?.counts ?? FoodCounts()
      : FoodCounts()
    let pending = document.events.filter { $0.entry.dateKey == dateKey }
    counts = FoodWidgetLedger.apply(
      pending.map(\.entry),
      to: counts,
      dateKey: dateKey
    )
    return FoodWatchDisplayState(
      dateKey: dateKey,
      counts: counts,
      skin: document.acknowledgement?.skin ?? .skyMeadow,
      pendingCount: pending.count,
      isAvailable: true,
      hasPhoneState: hasCurrentPhoneState
    )
  }

  /// Clears local pending actions while preserving the sequence counter. A
  /// reset must not make a later action reuse an old event number.
  func reset() throws {
    try withLock {
      var document = try load()
      document.events.removeAll()
      document.acknowledgement = nil
      try save(document)
    }
  }

  private func load() throws -> FoodWatchOutboxDocument {
    guard let fileURL else { throw FoodWatchOutboxError.appGroupUnavailable }
    guard FileManager.default.fileExists(atPath: fileURL.path) else {
      return FoodWatchOutboxDocument()
    }
    do {
      let data = try Data(contentsOf: fileURL)
      let decoder = JSONDecoder()
      decoder.dateDecodingStrategy = .iso8601
      return try decoder.decode(FoodWatchOutboxDocument.self, from: data)
    } catch let error as FoodWatchOutboxError {
      throw error
    } catch {
      throw FoodWatchOutboxError.unreadable
    }
  }

  private func save(_ document: FoodWatchOutboxDocument) throws {
    guard let fileURL else { throw FoodWatchOutboxError.appGroupUnavailable }
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
