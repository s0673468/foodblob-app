import SwiftUI
import WatchKit

@main
struct FoodBlobWatchApp: App {
  @WKApplicationDelegateAdaptor
  private var applicationDelegate: FoodBlobWatchApplicationDelegate
  @StateObject private var model = FoodWatchModel()
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      FoodBlobWatchTodayView()
        .environmentObject(model)
        .task {
          model.start()
        }
        .onChange(of: scenePhase) { _, phase in
          guard phase == .active else { return }
          model.start()
        }
    }
  }
}
