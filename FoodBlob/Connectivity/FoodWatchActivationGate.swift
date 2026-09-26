import Foundation

/// Lets a background App Intent wait briefly for WCSession activation without
/// keeping UI open. The wait is bounded so a missing or unavailable Watch can
/// never strand the widget interaction.
@MainActor
final class FoodWatchActivationGate {
  private var resolution: Bool?
  private var waiters: [UUID: CheckedContinuation<Bool, Never>] = [:]

  var hasWaiters: Bool {
    !waiters.isEmpty
  }

  func prepareForActivation() {
    resolveWaiters(with: false)
    resolution = nil
  }

  func resolve(_ succeeded: Bool) {
    resolution = succeeded
    resolveWaiters(with: succeeded)
  }

  func waitForActivation(
    timeoutNanoseconds: UInt64 = 2_000_000_000
  ) async -> Bool {
    if let resolution { return resolution }
    let waiterID = UUID()
    return await withCheckedContinuation { continuation in
      waiters[waiterID] = continuation
      Task { @MainActor [weak self] in
        try? await Task.sleep(nanoseconds: timeoutNanoseconds)
        self?.timeout(waiterID)
      }
    }
  }

  private func timeout(_ waiterID: UUID) {
    waiters.removeValue(forKey: waiterID)?.resume(returning: false)
  }

  private func resolveWaiters(with result: Bool) {
    let pending = waiters.values
    waiters.removeAll(keepingCapacity: true)
    for waiter in pending {
      waiter.resume(returning: result)
    }
  }
}
