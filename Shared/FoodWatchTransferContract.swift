import Foundation

enum FoodWatchIdentity {
  // Events written before installation identities existed decode into this
  // namespace. New watch outboxes persist a fresh identity.
  static let legacySenderID = UUID(
    uuidString: "00000000-0000-0000-0000-000000000001"
  )!
}

struct FoodWatchTransferEvent: Codable, Equatable, Sendable {
  let sequence: Int64
  let entry: FoodWidgetLedgerEntry
  let senderID: UUID
  /// Identifies the phone reset epoch that produced this event. A missing
  /// value is the pre-epoch wire format and is retained for migration only.
  let resetGeneration: UUID?

  init(
    sequence: Int64,
    entry: FoodWidgetLedgerEntry,
    senderID: UUID = FoodWatchIdentity.legacySenderID,
    resetGeneration: UUID? = nil
  ) {
    self.sequence = sequence
    self.entry = entry
    self.senderID = senderID
    self.resetGeneration = resetGeneration
  }

  private enum CodingKeys: String, CodingKey {
    case sequence
    case entry
    case senderID
    case resetGeneration
  }

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    sequence = try values.decode(Int64.self, forKey: .sequence)
    entry = try values.decode(FoodWidgetLedgerEntry.self, forKey: .entry)
    senderID =
      try values.decodeIfPresent(UUID.self, forKey: .senderID)
      ?? FoodWatchIdentity.legacySenderID
    resetGeneration = try values.decodeIfPresent(
      UUID.self,
      forKey: .resetGeneration
    )
  }
}

struct FoodWatchAcknowledgement: Codable, Equatable, Sendable {
  let dateKey: String
  let counts: FoodCounts
  let skin: SkinID
  let generatedAt: Date
  let committedEventIDs: [UUID]
  let committedThroughSequence: Int64
  let committedThroughSequencesBySender: [UUID: Int64]?
  let isReset: Bool
  let resetThroughSequence: Int64?
  let senderID: UUID?
  let resetGeneration: UUID?

  init(
    dateKey: String,
    counts: FoodCounts,
    skin: SkinID,
    generatedAt: Date,
    committedEventIDs: [UUID],
    committedThroughSequence: Int64,
    committedThroughSequencesBySender: [UUID: Int64]? = nil,
    isReset: Bool,
    resetThroughSequence: Int64?,
    senderID: UUID? = nil,
    resetGeneration: UUID? = nil
  ) {
    self.dateKey = dateKey
    self.counts = counts
    self.skin = skin
    self.generatedAt = generatedAt
    self.committedEventIDs = committedEventIDs
    self.committedThroughSequence = committedThroughSequence
    self.committedThroughSequencesBySender =
      committedThroughSequencesBySender
    self.isReset = isReset
    self.resetThroughSequence = resetThroughSequence
    self.senderID = senderID
    self.resetGeneration = resetGeneration
  }

  /// Compares only the state visible to the Watch. `generatedAt` records when
  /// the phone published a snapshot, so it must not turn an otherwise
  /// identical acknowledgement into another queued transfer.
  func hasSamePayload(as other: FoodWatchAcknowledgement) -> Bool {
    dateKey == other.dateKey
      && counts == other.counts
      && skin == other.skin
      && Set(committedEventIDs) == Set(other.committedEventIDs)
      && committedThroughSequence == other.committedThroughSequence
      && (committedThroughSequencesBySender ?? [:])
        == (other.committedThroughSequencesBySender ?? [:])
      && isReset == other.isReset
      && resetThroughSequence == other.resetThroughSequence
      && senderID == other.senderID
      && resetGeneration == other.resetGeneration
  }
}

enum FoodWatchDeliveryRoute: Equatable, Sendable {
  case interactive
  case background
}

enum FoodWatchStateTransferRoute: Equatable, Sendable {
  case currentComplication
  case backgroundUserInfo
}

enum FoodWatchDeliveryPolicy {
  static func route(isReachable: Bool) -> FoodWatchDeliveryRoute {
    isReachable ? .interactive : .background
  }

  static func shouldRetryPendingEvents(
    resetStagedButNotReady: Bool
  ) -> Bool {
    !resetStagedButNotReady
  }

  static func shouldRetryInteractiveMessage(
    replyAccepted: Bool,
    durableFallbackQueued: Bool
  ) -> Bool {
    // A negative reply means the phone intentionally deferred the event (for
    // example, until a sequence gap or reset closes). When the same event is
    // already in `transferUserInfo`, wait for that durable path or a later
    // acknowledgement instead of spinning direct messages.
    !replyAccepted && !durableFallbackQueued
  }

  static func shouldRetryStateRefresh(
    transferFailed: Bool,
    hasQueuedRefresh: Bool,
    retriesRemaining: Int
  ) -> Bool {
    transferFailed && !hasQueuedRefresh && retriesRemaining > 0
  }

  // WCSession falls back to regular user-info delivery when the finite
  // complication-transfer budget reaches zero. Keep calling the API while a
  // complication is enabled instead of suppressing that fallback.
  static func shouldSendComplication(isEnabled: Bool) -> Bool {
    isEnabled
  }

  static func stateTransferRoute(
    isComplicationEnabled: Bool
  ) -> FoodWatchStateTransferRoute {
    isComplicationEnabled ? .currentComplication : .backgroundUserInfo
  }
}

enum FoodWatchTransferContract {
  static let eventKey = "food_blob_watch_event_v1"
  static let eventSequenceKey = "sequence"
  static let acknowledgementKey = "food_blob_watch_ack_v1"
  static let acknowledgementDeliveryIDKey =
    "food_blob_watch_ack_delivery_id_v1"
  static let stateRefreshRequestKey = "food_blob_watch_refresh_v1"

  static func stateRefreshRequestUserInfo() -> [String: Any] {
    [stateRefreshRequestKey: true]
  }

  static func isStateRefreshRequest(_ userInfo: [String: Any]) -> Bool {
    userInfo[stateRefreshRequestKey] as? Bool == true
  }

  static func userInfo(for event: FoodWatchTransferEvent) throws -> [String: Any] {
    var userInfo: [String: Any] = [
      eventKey: try event.entry.jsonLine(),
      eventSequenceKey: event.sequence,
    ]
    if event.senderID != FoodWatchIdentity.legacySenderID {
      userInfo["sender_id"] = event.senderID.uuidString
    }
    if let resetGeneration = event.resetGeneration {
      userInfo["reset_generation"] = resetGeneration.uuidString
    }
    return userInfo
  }

  static func event(from userInfo: [String: Any]) -> FoodWatchTransferEvent? {
    guard let line = userInfo[eventKey] as? String,
      let sequence = int64Value(userInfo[eventSequenceKey]),
      sequence > 0,
      let entry = FoodWidgetLedgerEntry(jsonLine: line)
    else {
      return nil
    }
    let senderID =
      (userInfo["sender_id"] as? String)
      .flatMap(UUID.init(uuidString:))
      ?? FoodWatchIdentity.legacySenderID
    let resetGeneration =
      (userInfo["reset_generation"] as? String)
      .flatMap(UUID.init(uuidString:))
    return FoodWatchTransferEvent(
      sequence: sequence,
      entry: entry,
      senderID: senderID,
      resetGeneration: resetGeneration
    )
  }

  static func acknowledgementUserInfo(
    dateKey: String,
    counts: FoodCounts,
    skin: SkinID,
    generatedAt: Date,
    committedEventIDs: [UUID],
    committedThroughSequence: Int64,
    committedThroughSequencesBySender: [UUID: Int64] = [:],
    isReset: Bool = false,
    resetThroughSequence: Int64? = nil,
    senderID: UUID? = nil,
    resetGeneration: UUID? = nil
  ) -> [String: Any] {
    var acknowledgement: [String: Any] = [
      "date_key": dateKey,
      "counts": [
        "green": counts.green,
        "yellow": counts.yellow,
        "red": counts.red,
      ],
      "skin": skin.rawValue,
      "generated_at": timestampString(generatedAt),
      "committed_event_ids": committedEventIDs.map(\.uuidString),
      "committed_through_sequence": committedThroughSequence,
      "committed_through_sequences": Dictionary(
        uniqueKeysWithValues: committedThroughSequencesBySender.map {
          ($0.key.uuidString, $0.value)
        }
      ),
      "is_reset": isReset,
    ]
    if let senderID {
      acknowledgement["sender_id"] = senderID.uuidString
    }
    if let resetThroughSequence {
      acknowledgement["reset_through_sequence"] = resetThroughSequence
    }
    if let resetGeneration {
      acknowledgement["reset_generation"] = resetGeneration.uuidString
    }
    return [acknowledgementKey: acknowledgement]
  }

  static func tagAcknowledgementDelivery(
    _ userInfo: [String: Any],
    deliveryID: UUID
  ) -> [String: Any] {
    var tagged = userInfo
    tagged[acknowledgementDeliveryIDKey] = deliveryID.uuidString
    return tagged
  }

  static func acknowledgementDeliveryID(
    from userInfo: [String: Any]
  ) -> UUID? {
    (userInfo[acknowledgementDeliveryIDKey] as? String)
      .flatMap(UUID.init(uuidString:))
  }

  static func acknowledgement(
    from applicationContext: [String: Any]
  ) -> FoodWatchAcknowledgement? {
    guard let payload = applicationContext[acknowledgementKey] as? [String: Any],
      let dateKey = payload["date_key"] as? String,
      FoodDateKey.date(from: dateKey) != nil,
      let rawCounts = payload["counts"] as? [String: Any],
      let green = rawCounts["green"] as? Int,
      let yellow = rawCounts["yellow"] as? Int,
      let red = rawCounts["red"] as? Int,
      let generatedAtString = payload["generated_at"] as? String,
      let generatedAt = timestampDate(generatedAtString),
      let committedThroughSequence = int64Value(
        payload["committed_through_sequence"]
      )
    else {
      return nil
    }
    let counts = FoodCounts(
      green: green,
      yellow: yellow,
      red: red
    )
    let skin = SkinID(rawValue: payload["skin"] as? String ?? "") ?? .skyMeadow
    let committedEventIDs = (payload["committed_event_ids"] as? [String] ?? [])
      .compactMap(UUID.init(uuidString:))
    let committedThroughSequencesBySender = (
      payload["committed_through_sequences"] as? [String: Any] ?? [:]
    ).reduce(into: [UUID: Int64]()) { result, element in
      guard let senderID = UUID(uuidString: element.key),
        let sequence = int64Value(element.value)
      else { return }
      result[senderID] = sequence
    }
    let isReset = payload["is_reset"] as? Bool ?? false
    let resetThroughSequence = int64Value(payload["reset_through_sequence"])
    let senderID = (payload["sender_id"] as? String)
      .flatMap(UUID.init(uuidString:))
    let resetGeneration = (payload["reset_generation"] as? String)
      .flatMap(UUID.init(uuidString:))
    return FoodWatchAcknowledgement(
      dateKey: dateKey,
      counts: counts,
      skin: skin,
      generatedAt: generatedAt,
      committedEventIDs: committedEventIDs,
      committedThroughSequence: committedThroughSequence,
      committedThroughSequencesBySender:
        committedThroughSequencesBySender.isEmpty
        ? nil
        : committedThroughSequencesBySender,
      isReset: isReset,
      resetThroughSequence: resetThroughSequence,
      senderID: senderID,
      resetGeneration: resetGeneration
    )
  }

  private static func timestampString(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
  }

  private static func timestampDate(_ value: String) -> Date? {
    let fractionalFormatter = ISO8601DateFormatter()
    fractionalFormatter.formatOptions = [
      .withInternetDateTime,
      .withFractionalSeconds,
    ]
    return fractionalFormatter.date(from: value)
      ?? ISO8601DateFormatter().date(from: value)
  }

  private static func int64Value(_ value: Any?) -> Int64? {
    if let value = value as? Int64 { return value }
    if let value = value as? Int { return Int64(value) }
    if let value = value as? NSNumber { return value.int64Value }
    return nil
  }
}
