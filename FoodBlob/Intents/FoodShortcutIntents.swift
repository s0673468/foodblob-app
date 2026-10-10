import AppIntents
import WidgetKit

enum FoodOfferingColor: String, AppEnum {
  case green, yellow, red

  static var typeDisplayRepresentation: TypeDisplayRepresentation = "Colour"
  static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
    .green: "Green", .yellow: "Yellow", .red: "Red",
  ]

  var foodColor: FoodColor {
    switch self {
    case .green: .green
    case .yellow: .yellow
    case .red: .red
    }
  }
}

struct FoodMixEntity: AppEntity {
  static var typeDisplayRepresentation: TypeDisplayRepresentation = "Today's Food Mix"
  static var defaultQuery = FoodMixQuery()

  var id: String { dateKey }
  @Property(title: "Green") var green: Int
  @Property(title: "Yellow") var yellow: Int
  @Property(title: "Red") var red: Int
  @Property(title: "Total") var total: Int
  @Property(title: "Date Key") var dateKey: String

  var summary: String {
    "\(FoodCountText.offerings(total)) — \(green) green, \(yellow) yellow, \(red) red"
  }

  var displayRepresentation: DisplayRepresentation {
    DisplayRepresentation(title: "\(summary)")
  }

  init(state: FoodWidgetState, calendar: Calendar = .autoupdatingCurrent) {
    green = state.counts.green
    yellow = state.counts.yellow
    red = state.counts.red
    total = state.counts.total
    dateKey = FoodDateKey.string(for: state.date, calendar: calendar)
  }

  static func current(
    persistence: FoodBlobPersistence, at date: Date,
    calendar: Calendar = .autoupdatingCurrent
  ) throws -> Self {
    guard persistence.isAvailable else {
      throw FoodBlobPersistenceError.appGroupUnavailable
    }
    return Self(state: persistence.currentWidgetState(at: date, calendar: calendar), calendar: calendar)
  }
}

struct FoodMixQuery: EntityQuery {
  func entities(for identifiers: [FoodMixEntity.ID]) async throws -> [FoodMixEntity] {
    try Self.resolve(identifiers, persistence: FoodBlobPersistence(), at: Date())
  }

  func suggestedEntities() async throws -> [FoodMixEntity] {
    [try FoodMixEntity.current(persistence: FoodBlobPersistence(), at: Date())]
  }

  // An identifier saved in a Shortcut never becomes a historical read action.
  static func resolve(
    _ identifiers: [String], persistence: FoodBlobPersistence, at date: Date
  ) throws -> [FoodMixEntity] {
    let today = try FoodMixEntity.current(persistence: persistence, at: date)
    return identifiers.contains(today.id) ? [today] : []
  }
}

struct LogFoodOfferingIntent: AppIntent {
  static var title: LocalizedStringResource = "Log Food Offering"
  static var description = IntentDescription("Adds or removes one food offering for today.")
  static var openAppWhenRun = false

  @Parameter(title: "Colour") var color: FoodOfferingColor
  @Parameter(title: "Remove Instead", default: false) var removeInstead: Bool

  static var parameterSummary: some ParameterSummary {
    Summary("Log a \(\.$color) offering") {
      \.$removeInstead
    }
  }

  func perform() async throws -> some IntentResult & ProvidesDialog {
    let result = try FoodShortcutAction.log(
      color: color.foodColor, remove: removeInstead,
      persistence: FoodBlobPersistence(), at: Date()
    )
    if result.didAppend {
      await FoodWidgetActionRuntime.notifyCommittedAction()
      WidgetCenter.shared.reloadAllTimelines()
    }
    return .result(dialog: IntentDialog(stringLiteral: result.confirmation))
  }
}

struct GetTodayFoodMixIntent: AppIntent {
  static var title: LocalizedStringResource = "Get Today's Food Mix"
  static var description = IntentDescription("Returns today's green, yellow, red and total offerings.")
  static var openAppWhenRun = false

  func perform() async throws -> some IntentResult & ReturnsValue<FoodMixEntity> {
    .result(value: try FoodMixEntity.current(persistence: FoodBlobPersistence(), at: Date()))
  }
}

struct FoodBlobShortcuts: AppShortcutsProvider {
  static var appShortcuts: [AppShortcut] {
    AppShortcut(
      intent: LogFoodOfferingIntent(),
      phrases: ["Log a \(\.$color) offering in \(.applicationName)"],
      shortTitle: "Log Food Offering", systemImageName: "plus.circle"
    )
    AppShortcut(
      intent: GetTodayFoodMixIntent(),
      phrases: ["What's my \(.applicationName) mix today"],
      shortTitle: "Today's Food Mix", systemImageName: "circle.lefthalf.filled"
    )
  }
}
