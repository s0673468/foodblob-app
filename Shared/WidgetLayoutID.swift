import Foundation

enum WidgetLayoutID: String, Codable, CaseIterable, Identifiable, Sendable {
  case bubbleStack = "bubble_stack"
  case popColumns = "pop_columns"
  case blobStage = "blob_stage"
  case fourPops = "four_pops"
  case tapDeck = "tap_deck"
  case sidecarTiles = "sidecar_tiles"
  case diceRow = "dice_row"
  case colorCourtyard = "color_courtyard"
  case stepStones = "step_stones"
  case puddleDock = "puddle_dock"
  case paletteTray = "palette_tray"

  static let defaultLayout = WidgetLayoutID.bubbleStack
  static let legacyLayout = WidgetLayoutID.popColumns
  static let galleryAlternatives: [WidgetLayoutID] = Array(allCases.dropFirst())

  var id: String { rawValue }

  var name: String {
    switch self {
    case .bubbleStack: "Living Blob"
    case .popColumns: "Living Blob Classic"
    case .blobStage: "Living Blob Stage"
    case .fourPops: "Living Blob Air"
    case .tapDeck: "Living Blob Pebbles"
    case .sidecarTiles: "Living Blob Tide"
    case .diceRow: "Living Blob Reverse"
    case .colorCourtyard: "Living Blob Halo"
    case .stepStones: "Living Blob Steps"
    case .puddleDock: "Living Blob Basin"
    case .paletteTray: "Living Blob Rack"
    }
  }

  var detail: String {
    self == .bubbleStack
      ? "The selected Sky Meadow or Shrine world, with your living blob and controls."
      : "A compatibility slot for an existing Food Blob Home Screen widget."
  }

  init(from decoder: Decoder) throws {
    let container = try decoder.singleValueContainer()
    let rawValue = (try? container.decode(String.self)) ?? ""
    self = WidgetLayoutID(rawValue: rawValue) ?? Self.defaultLayout
  }
}
