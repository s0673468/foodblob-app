import Combine
import Foundation
import WatchKit

@MainActor
final class FoodWatchModel: ObservableObject {
  let outbox: FoodWatchOutboxStore
  private let sessionClient: FoodWatchSessionClient
  private var dayChangeObserver: NSObjectProtocol?

  @Published private(set) var displayState: FoodWatchDisplayState

  init() {
    let runtime = FoodWatchRuntime.shared
    self.outbox = runtime.outbox
    self.sessionClient = runtime.sessionClient
    displayState = outbox.displayState()
    sessionClient.onStateChanged = { [weak self] in
      self?.refresh()
    }
    dayChangeObserver = NotificationCenter.default.addObserver(
      forName: .NSCalendarDayChanged,
      object: nil,
      queue: .main
    ) { [weak self] _ in
      Task { @MainActor in
        self?.refresh()
      }
    }
  }

  deinit {
    if let dayChangeObserver {
      NotificationCenter.default.removeObserver(dayChangeObserver)
    }
  }

  func start() {
    sessionClient.start()
    refresh()
  }

  func add(_ color: FoodColor) {
    guard displayState.isAvailable else { return }
    append(color: color, delta: 1, haptic: .click)
  }

  func remove(_ color: FoodColor) {
    guard displayState.isAvailable,
      displayState.counts.count(for: color) > 0
    else { return }
    append(color: color, delta: -1, haptic: .directionDown)
  }

  private func append(color: FoodColor, delta: Int, haptic: WKHapticType) {
    let entry = FoodWidgetLedgerEntry(
      timestamp: Date(),
      dateKey: FoodDateKey.string(),
      color: color,
      delta: delta
    )
    guard sessionClient.enqueue(entry) else { return }
    WKInterfaceDevice.current().play(haptic)
  }

  private func refresh() {
    displayState = outbox.displayState()
  }
}
