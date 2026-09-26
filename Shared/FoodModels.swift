import Foundation

enum FoodColor: String, Codable, CaseIterable, Identifiable, Sendable {
  case green
  case yellow
  case red

  var id: String { rawValue }

  var displayName: String {
    switch self {
    case .green: "Green"
    case .yellow: "Yellow"
    case .red: "Red"
    }
  }
}

struct FoodCounts: Codable, Equatable, Sendable {
  private enum CodingKeys: String, CodingKey {
    case green
    case yellow
    case red
  }

  var green: Int
  var yellow: Int
  var red: Int

  init(green: Int = 0, yellow: Int = 0, red: Int = 0) {
    self.green = max(0, green)
    self.yellow = max(0, yellow)
    self.red = max(0, red)
  }

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    self.init(
      green: try values.decodeIfPresent(Int.self, forKey: .green) ?? 0,
      yellow: try values.decodeIfPresent(Int.self, forKey: .yellow) ?? 0,
      red: try values.decodeIfPresent(Int.self, forKey: .red) ?? 0
    )
  }

  var total: Int { green + yellow + red }
  var isEmpty: Bool { total == 0 }

  func count(for color: FoodColor) -> Int {
    switch color {
    case .green: green
    case .yellow: yellow
    case .red: red
    }
  }

  mutating func setCount(_ value: Int, for color: FoodColor) {
    switch color {
    case .green: green = max(0, value)
    case .yellow: yellow = max(0, value)
    case .red: red = max(0, value)
    }
  }

  mutating func increment(_ color: FoodColor) {
    setCount(count(for: color) + 1, for: color)
  }

  mutating func decrement(_ color: FoodColor) {
    setCount(count(for: color) - 1, for: color)
  }

  func applying(_ delta: Int, to color: FoodColor) -> FoodCounts {
    var next = self
    next.setCount(count(for: color) + delta, for: color)
    return next
  }
}

enum FoodCountText {
  static func offerings(_ count: Int) -> String {
    "\(count) \(count == 1 ? "offering" : "offerings")"
  }

  static func changes(_ count: Int) -> String {
    "\(count) \(count == 1 ? "change" : "changes")"
  }
}

enum SkinID: String, Codable, CaseIterable, Identifiable, Sendable {
  case skyMeadow = "sky_meadow"
  case shrine

  var id: String { rawValue }

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    let rawValue = (try? container.decode(String.self)) ?? ""
    self = SkinID(rawValue: rawValue) ?? .skyMeadow
  }
}

struct DayRecord: Codable, Equatable, Identifiable, Sendable {
  var dateKey: String
  var counts: FoodCounts
  var updatedAt: Date

  var id: String { dateKey }

  var date: Date {
    FoodDateKey.date(from: dateKey) ?? .distantPast
  }
}

struct FoodUndoAction: Codable, Equatable, Sendable {
  var dateKey: String
  var color: FoodColor
  var delta: Int
}

private struct CompatibleUndoAction: Decodable {
  private enum CodingKeys: String, CodingKey {
    case dateKey
    case color
    case delta
    case previousValue
  }

  let action: FoodUndoAction?

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)

    if values.contains(.delta) {
      let delta = try values.decode(Int.self, forKey: .delta)
      guard delta == -1 || delta == 1 else {
        throw DecodingError.dataCorruptedError(
          forKey: .delta,
          in: values,
          debugDescription: "Undo deltas must be -1 or 1."
        )
      }
      action = FoodUndoAction(
        dateKey: try values.decode(String.self, forKey: .dateKey),
        color: try values.decode(FoodColor.self, forKey: .color),
        delta: delta
      )
      return
    }

    if values.contains(.previousValue) {
      _ = try values.decode(String.self, forKey: .dateKey)
      _ = try values.decode(FoodColor.self, forKey: .color)
      _ = try values.decode(Int.self, forKey: .previousValue)
      action = nil
      return
    }

    throw DecodingError.keyNotFound(
      CodingKeys.delta,
      DecodingError.Context(
        codingPath: decoder.codingPath,
        debugDescription: "Undo record has no supported value."
      )
    )
  }
}

struct FoodStateDocument: Codable, Equatable, Sendable {
  static let supportedSchemaVersion = 1
  static let maximumHistoryDays = 180
  static let maximumUndoActions = 20

  private enum CodingKeys: String, CodingKey {
    case schemaVersion = "schema_version"
    case days
    case selectedSkin = "selected_skin"
    case selectedWidgetLayout = "selected_widget_layout"
    case undoStack = "undo_stack"
    case consumedWidgetIDs = "consumed_widget_ids"
  }

  var schemaVersion: Int
  var days: [DayRecord]
  var selectedSkin: SkinID
  var selectedWidgetLayout: WidgetLayoutID
  var undoStack: [FoodUndoAction]
  var consumedWidgetIDs: [UUID]

  init(
    schemaVersion: Int = Self.supportedSchemaVersion,
    days: [DayRecord] = [],
    selectedSkin: SkinID = .skyMeadow,
    selectedWidgetLayout: WidgetLayoutID = .defaultLayout,
    undoStack: [FoodUndoAction] = [],
    consumedWidgetIDs: [UUID] = []
  ) {
    self.schemaVersion = schemaVersion
    self.days = days
    self.selectedSkin = selectedSkin
    self.selectedWidgetLayout = selectedWidgetLayout
    self.undoStack = undoStack
    self.consumedWidgetIDs = consumedWidgetIDs
  }

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    let version =
      try values.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 0
    guard version <= Self.supportedSchemaVersion else {
      throw DecodingError.dataCorruptedError(
        forKey: .schemaVersion,
        in: values,
        debugDescription: "State was written by a newer Food Blob version."
      )
    }
    schemaVersion = version
    days = try values.decodeIfPresent([DayRecord].self, forKey: .days) ?? []
    selectedSkin =
      try values.decodeIfPresent(SkinID.self, forKey: .selectedSkin)
      ?? .skyMeadow
    selectedWidgetLayout =
      try values.decodeIfPresent(
        WidgetLayoutID.self,
        forKey: .selectedWidgetLayout
      ) ?? .legacyLayout
    let compatibleUndoStack =
      try values.decodeIfPresent(
        [CompatibleUndoAction].self,
        forKey: .undoStack
      ) ?? []
    undoStack = compatibleUndoStack.compactMap(\.action)
    consumedWidgetIDs =
      try values.decodeIfPresent([UUID].self, forKey: .consumedWidgetIDs)
      ?? []
  }

  func counts(for dateKey: String) -> FoodCounts {
    days.first(where: { $0.dateKey == dateKey })?.counts ?? FoodCounts()
  }

  mutating func setCounts(
    _ counts: FoodCounts,
    for dateKey: String,
    updatedAt: Date
  ) {
    if let index = days.firstIndex(where: { $0.dateKey == dateKey }) {
      days[index].counts = counts
      days[index].updatedAt = updatedAt
    } else {
      days.append(
        DayRecord(dateKey: dateKey, counts: counts, updatedAt: updatedAt)
      )
    }
    normalize()
  }

  mutating func applyWidgetEntries(
    _ entries: some Sequence<FoodWidgetLedgerEntry>
  ) {
    var recordsByDay: [String: DayRecord] = [:]
    for record in days {
      if let existing = recordsByDay[record.dateKey],
        existing.updatedAt >= record.updatedAt
      {
        continue
      }
      recordsByDay[record.dateKey] = record
    }
    var didApplyEntry = false

    for entry in entries {
      var record = recordsByDay[entry.dateKey]
        ?? DayRecord(
          dateKey: entry.dateKey,
          counts: FoodCounts(),
          updatedAt: entry.timestamp
        )
      record.counts = record.counts.applying(entry.delta, to: entry.color)
      record.updatedAt = entry.timestamp
      recordsByDay[entry.dateKey] = record
      didApplyEntry = true
    }

    guard didApplyEntry else { return }
    days = Array(recordsByDay.values)
    normalize()
  }

  mutating func pushUndo(_ action: FoodUndoAction) {
    undoStack.append(action)
    if undoStack.count > Self.maximumUndoActions {
      undoStack.removeFirst(undoStack.count - Self.maximumUndoActions)
    }
  }

  mutating func addConsumedWidgetIDs(_ ids: some Sequence<UUID>) {
    var ordered = consumedWidgetIDs
    var seen = Set(ordered)
    for id in ids where seen.insert(id).inserted {
      ordered.append(id)
    }
    consumedWidgetIDs = ordered
  }

  mutating func normalize() {
    var latestByDay: [String: DayRecord] = [:]
    for record in days {
      if let existing = latestByDay[record.dateKey],
        existing.updatedAt >= record.updatedAt
      {
        continue
      }
      latestByDay[record.dateKey] = record
    }
    days = latestByDay.values.sorted { $0.dateKey > $1.dateKey }
    if days.count > Self.maximumHistoryDays {
      days.removeLast(days.count - Self.maximumHistoryDays)
    }
  }
}
