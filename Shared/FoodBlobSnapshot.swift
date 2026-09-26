import Foundation

struct FoodBlobSnapshot: Codable, Equatable, Sendable {
  static let supportedSchemaVersion = 1

  private enum CodingKeys: String, CodingKey {
    case schemaVersion = "schema_version"
    case generatedAt = "generated_at"
    case dateKey = "date_key"
    case counts
    case skin
    case widgetLayout = "widget_layout"
    case consumedWidgetIDs = "consumed_widget_ids"
  }

  let schemaVersion: Int
  let generatedAt: Date
  let dateKey: String
  let counts: FoodCounts
  let skin: SkinID
  let widgetLayout: WidgetLayoutID
  let consumedWidgetIDs: [UUID]

  init(
    schemaVersion: Int = Self.supportedSchemaVersion,
    generatedAt: Date,
    dateKey: String,
    counts: FoodCounts,
    skin: SkinID,
    widgetLayout: WidgetLayoutID = .defaultLayout,
    consumedWidgetIDs: [UUID] = []
  ) {
    self.schemaVersion = schemaVersion
    self.generatedAt = generatedAt
    self.dateKey = dateKey
    self.counts = counts
    self.skin = skin
    self.widgetLayout = widgetLayout
    self.consumedWidgetIDs = consumedWidgetIDs
  }

  init?(data: Data) {
    let decoder = JSONDecoder()
    decoder.dateDecodingStrategy = .iso8601
    guard let decoded = try? decoder.decode(Self.self, from: data),
      decoded.schemaVersion <= Self.supportedSchemaVersion,
      !decoded.dateKey.isEmpty,
      FoodDateKey.date(from: decoded.dateKey) != nil
    else {
      return nil
    }
    self = decoded
  }

  init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    schemaVersion =
      try values.decodeIfPresent(Int.self, forKey: .schemaVersion) ?? 0
    generatedAt = try values.decode(Date.self, forKey: .generatedAt)
    dateKey = try values.decode(String.self, forKey: .dateKey)
    counts =
      try values.decodeIfPresent(FoodCounts.self, forKey: .counts)
      ?? FoodCounts()
    skin =
      try values.decodeIfPresent(SkinID.self, forKey: .skin) ?? .skyMeadow
    widgetLayout =
      try values.decodeIfPresent(WidgetLayoutID.self, forKey: .widgetLayout)
      ?? .legacyLayout
    let ids =
      try values.decodeIfPresent([UUID].self, forKey: .consumedWidgetIDs)
      ?? []
    consumedWidgetIDs = ids
  }

  func encoded(prettyPrinted: Bool = false) throws -> Data {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    encoder.outputFormatting = prettyPrinted ? [.prettyPrinted, .sortedKeys] : [.sortedKeys]
    return try encoder.encode(self)
  }
}
