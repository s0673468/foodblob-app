import AppIntents
import WidgetKit

@available(iOS 17.0, *)
struct ChangeFoodCountIntent: AppIntent {
  static var title: LocalizedStringResource = "Change food count"
  static var description = IntentDescription(
    "Adds or removes one food offering for today."
  )
  static var openAppWhenRun = false

  @Parameter(title: "Color")
  var color: String

  @Parameter(title: "Change")
  var delta: Int

  init() {
    color = FoodColor.green.rawValue
    delta = 1
  }

  init(color: FoodColor, delta: Int) {
    self.color = color.rawValue
    self.delta = delta
  }

  func perform() async throws -> some IntentResult {
    guard let foodColor = FoodColor(rawValue: color) else {
      return .result()
    }
    let normalizedDelta = delta < 0 ? -1 : 1
    let now = Date()
    try FoodBlobPersistence().appendWidgetEntry(
      FoodWidgetLedgerEntry(
        timestamp: now,
        dateKey: FoodDateKey.string(for: now),
        color: foodColor,
        delta: normalizedDelta
      )
    )
    await FoodWidgetActionRuntime.notifyCommittedAction()
    WidgetCenter.shared.reloadAllTimelines()
    return .result()
  }
}
