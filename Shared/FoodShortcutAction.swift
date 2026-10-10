import Foundation

struct FoodShortcutLogResult {
  let didAppend: Bool
  let confirmation: String
}

enum FoodShortcutAction {
  static func entry(
    color: FoodColor,
    remove: Bool,
    counts: FoodCounts,
    at date: Date,
    calendar: Calendar = .autoupdatingCurrent,
    id: UUID = UUID()
  ) -> FoodWidgetLedgerEntry? {
    guard !remove || counts.count(for: color) > 0 else { return nil }
    return FoodWidgetLedgerEntry(
      id: id, timestamp: date,
      dateKey: FoodDateKey.string(for: date, calendar: calendar),
      color: color, delta: remove ? -1 : 1
    )
  }

  static func log(
    color: FoodColor,
    remove: Bool,
    persistence: FoodBlobPersistence,
    at date: Date,
    calendar: Calendar = .autoupdatingCurrent
  ) throws -> FoodShortcutLogResult {
    guard persistence.isAvailable else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    let before = persistence.currentWidgetState(at: date, calendar: calendar)
    let action = entry(
      color: color, remove: remove, counts: before.counts,
      at: date, calendar: calendar
    )
    if let action {
      try persistence.appendWidgetEntry(action)
    }
    let after = persistence.currentWidgetState(at: date, calendar: calendar)
    return FoodShortcutLogResult(
      didAppend: action != nil,
      confirmation: confirmation(
        color: color, remove: remove, didAppend: action != nil, counts: after.counts
      )
    )
  }

  static func confirmation(
    color: FoodColor, remove: Bool, didAppend: Bool, counts: FoodCounts
  ) -> String {
    if !didAppend {
      return "Nothing to remove — 0 \(color.rawValue) today."
    }
    return "\(remove ? "Removed" : "Added") a \(color.rawValue) offering. \(counts.total) today."
  }
}
