import SwiftUI

@main
struct FoodBlobApp: App {
  @State private var store: FoodStore
  private let watchReceiver: FoodWatchConnectivityReceiver
  private let persistence: FoodBlobPersistence
  private let receipts: FoodWatchReceiptStore
  @Environment(\.scenePhase) private var scenePhase

  init() {
    let persistence = FoodBlobPersistence()
    let receipts = FoodWatchReceiptStore()
    self.persistence = persistence
    self.receipts = receipts
    try? receipts.recoverReset(
      using: persistence.readSnapshot(),
      phoneStateIsEmpty: Self.phoneStateIsEmpty(using: persistence)
    )
    let resetRecoveryBlocked =
      (try? receipts.resetStagedButNotReady()) != false
    var receiver: FoodWatchConnectivityReceiver?
    let store = FoodStore(
      onCommittedWidgetEntries: { entries in
        try receipts.commit(entries)
      },
      onBeforeDeleteAllData: {
        try receipts.reset()
      },
      onAfterDeleteAllData: {
        try receipts.markResetReady()
      },
      onDeleteAllDataFailed: {
        try? receipts.cancelReset()
        receiver?.requestAcknowledgement()
      },
      onStatePublished: {
        receiver?.requestAcknowledgement()
      },
      resetRecoveryBlocked: resetRecoveryBlocked
    )
    self._store = State(initialValue: store)
    let watchReceiver = FoodWatchConnectivityReceiver(
      receipts: receipts,
      ingestOnMain: {
        store.reloadAndIngestWidgetActions()
      }
    )
    receiver = watchReceiver
    self.watchReceiver = watchReceiver
    FoodWidgetActionRuntime.install { [weak store, weak watchReceiver] in
      store?.reloadAndIngestWidgetActions()
      await watchReceiver?.synchronizeAfterWidgetAction()
    }
    // Activate WatchConnectivity before SwiftUI creates its first scene. A
    // queued watch event should not wait for the phone app to reach a view's
    // task modifier after install or a cold launch.
    watchReceiver.start()
  }

  var body: some Scene {
    WindowGroup {
      FoodBlobRootView()
        .environment(store)
        .task {
          recoverWatchResetIfPossible()
          watchReceiver.start()
          store.reloadAndIngestWidgetActions()
        }
        .onChange(of: scenePhase) { _, phase in
          guard phase == .active else { return }
          recoverWatchResetIfPossible()
          store.reloadAndIngestWidgetActions()
        }
    }
  }

  private func recoverWatchResetIfPossible() {
    let wasStaged = (try? receipts.resetStagedButNotReady()) == true
    try? receipts.recoverReset(
      using: persistence.readSnapshot(),
      phoneStateIsEmpty: Self.phoneStateIsEmpty(using: persistence)
    )
    let isStaged = (try? receipts.resetStagedButNotReady()) == true
    if wasStaged && !isStaged {
      store.resolveResetRecovery()
      watchReceiver.requestAcknowledgement()
    }
  }

  private static func phoneStateIsEmpty(
    using persistence: FoodBlobPersistence
  ) -> Bool {
    guard let document = try? persistence.loadDocument() else { return false }
    return document.days.isEmpty
      && document.undoStack.isEmpty
      && document.consumedWidgetIDs.isEmpty
      && !persistence.hasWidgetActionFiles
  }
}
