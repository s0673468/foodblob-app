import WatchKit

@MainActor
final class FoodBlobWatchApplicationDelegate: NSObject, WKApplicationDelegate {
  func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
    FoodWatchRuntime.shared.sessionClient.handle(backgroundTasks)
  }
}
