import WidgetKit

struct FoodWidgetEntry: TimelineEntry {
  let date: Date
  let counts: FoodCounts
  let skin: SkinID
  let isAvailable: Bool
}

struct FoodWidgetProvider: TimelineProvider {
  private let fixedSkin: SkinID
  private let persistence: FoodBlobPersistence

  init(
    fixedSkin: SkinID,
    persistence: FoodBlobPersistence = FoodBlobPersistence()
  ) {
    self.fixedSkin = fixedSkin
    self.persistence = persistence
  }

  func placeholder(in context: Context) -> FoodWidgetEntry {
    FoodWidgetEntry(
      date: Date(),
      counts: FoodCounts(green: 5, yellow: 2, red: 1),
      skin: fixedSkin,
      isAvailable: true
    )
  }

  func getSnapshot(
    in context: Context,
    completion: @escaping (FoodWidgetEntry) -> Void
  ) {
    guard !context.isPreview else {
      completion(placeholder(in: context))
      return
    }
    completion(entry(from: persistence.currentWidgetState(at: Date())))
  }

  func getTimeline(
    in context: Context,
    completion: @escaping (Timeline<FoodWidgetEntry>) -> Void
  ) {
    let now = Date()
    let midnight = FoodDateKey.startOfNextDay(after: now)
    let current = persistence.currentWidgetState(at: now)
    completion(
      Timeline(
        entries: [
          entry(from: current),
          entry(from: current.resettingCounts(at: midnight)),
        ],
        policy: .after(midnight)
      )
    )
  }

  private func entry(from state: FoodWidgetState) -> FoodWidgetEntry {
    return FoodWidgetEntry(
      date: state.date,
      counts: state.counts,
      skin: fixedSkin,
      isAvailable: state.isAvailable
    )
  }
}
