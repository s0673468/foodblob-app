import Foundation

struct FoodWidgetLedgerEntry: Codable, Equatable, Identifiable, Sendable {
  private enum CodingKeys: String, CodingKey {
    case id
    case timestamp = "ts"
    case dateKey = "date_key"
    case color
    case delta
  }

  let id: UUID
  let timestamp: Date
  let dateKey: String
  let color: FoodColor
  let delta: Int

  init(
    id: UUID = UUID(),
    timestamp: Date,
    dateKey: String,
    color: FoodColor,
    delta: Int
  ) {
    self.id = id
    self.timestamp = timestamp
    self.dateKey = dateKey
    self.color = color
    self.delta = delta
  }

  init?(jsonLine: String) {
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    let trimmed = jsonLine.trimmingCharacters(in: .whitespacesAndNewlines)
    guard
      let entry = Self.decode(
        trimmedJSONLine: trimmed,
        decoder: decoder
      ),
      !entry.dateKey.isEmpty,
      FoodDateKey.date(from: entry.dateKey) != nil,
      entry.delta != 0
    else {
      return nil
    }
    self = entry
  }

  fileprivate static func decode(
    trimmedJSONLine: String,
    decoder: JSONDecoder
  ) -> Self? {
    guard !trimmedJSONLine.isEmpty,
      let data = trimmedJSONLine.data(using: .utf8)
    else {
      return nil
    }
    return try? decoder.decode(Self.self, from: data)
  }

  func jsonLine() throws -> String {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    encoder.outputFormatting = [.sortedKeys]
    let data = try encoder.encode(self)
    guard let value = String(data: data, encoding: .utf8) else {
      throw CocoaError(.fileWriteInapplicableStringEncoding)
    }
    return value
  }
}

struct FoodWidgetLedgerParse: Equatable, Sendable {
  let entries: [FoodWidgetLedgerEntry]
  let malformedLineCount: Int
}

enum FoodWidgetLedger {
  static func joinedSegments(_ segments: some Sequence<String>) -> String {
    var result = ""
    for segment in segments where !segment.isEmpty {
      result += segment
      if !segment.hasSuffix("\n") {
        result += "\n"
      }
    }
    return result
  }

  static func parse(_ contents: String) -> FoodWidgetLedgerParse {
    parse(contents) { FoodDateKey.date(from: $0) != nil }
  }

  static func parse(
    _ contents: String,
    validateDateKey: (String) -> Bool
  ) -> FoodWidgetLedgerParse {
    let lines = contents.split(
      separator: "\n",
      omittingEmptySubsequences: false
    )
    var entries: [FoodWidgetLedgerEntry] = []
    entries.reserveCapacity(lines.count)
    var dateKeyValidity: [String: Bool] = [:]
    dateKeyValidity.reserveCapacity(
      min(lines.count, FoodStateDocument.maximumHistoryDays)
    )
    var malformedLineCount = 0
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    for line in lines {
      let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
      if trimmed.isEmpty {
        continue
      }
      guard
        let entry = FoodWidgetLedgerEntry.decode(
          trimmedJSONLine: trimmed,
          decoder: decoder
        ),
        !entry.dateKey.isEmpty,
        entry.delta != 0
      else {
        malformedLineCount += 1
        continue
      }
      let dateKeyIsValid: Bool
      if let cached = dateKeyValidity[entry.dateKey] {
        dateKeyIsValid = cached
      } else {
        dateKeyIsValid = validateDateKey(entry.dateKey)
        dateKeyValidity[entry.dateKey] = dateKeyIsValid
      }
      guard dateKeyIsValid else {
        malformedLineCount += 1
        continue
      }
      entries.append(entry)
    }
    return FoodWidgetLedgerParse(
      entries: entries,
      malformedLineCount: malformedLineCount
    )
  }

  static func pending(
    _ entries: some Sequence<FoodWidgetLedgerEntry>,
    consumedIDs: Set<UUID>
  ) -> [FoodWidgetLedgerEntry] {
    var seen: Set<UUID> = []
    var result: [FoodWidgetLedgerEntry] = []
    for entry in entries {
      guard !consumedIDs.contains(entry.id), seen.insert(entry.id).inserted
      else {
        continue
      }
      result.append(entry)
    }
    return result
  }

  static func apply(
    _ entries: some Sequence<FoodWidgetLedgerEntry>,
    to base: FoodCounts,
    dateKey: String
  ) -> FoodCounts {
    var result = base
    for entry in entries where entry.dateKey == dateKey {
      result = result.applying(entry.delta, to: entry.color)
    }
    return result
  }
}
