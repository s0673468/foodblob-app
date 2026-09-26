import Foundation

/// Bridges an App Intent that is running inside the host app process back to
/// the canonical FoodStore mutation boundary. The widget-extension process
/// has no installed hook and still relies on the durable append-only ledger.
@MainActor
enum FoodWidgetActionRuntime {
  private static var ingestionHook: (() async -> Void)?

  static func install(_ hook: (() async -> Void)?) {
    ingestionHook = hook
  }

  static func notifyCommittedAction() async {
    await ingestionHook?()
  }
}
