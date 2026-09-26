import Foundation

/// Keeps Watch background execution alive until every delegate callback has
/// finished its MainActor persistence and complication-reload work.
final class FoodWatchIncomingDeliveryTracker: @unchecked Sendable {
  private let lock = NSLock()
  private var callbacksInFlight = 0
  private var latestStartedGeneration: UInt64 = 0
  private var latestFinishedGeneration: UInt64 = 0

  @discardableResult
  func begin() -> UInt64 {
    lock.lock()
    callbacksInFlight += 1
    latestStartedGeneration &+= 1
    let generation = latestStartedGeneration
    lock.unlock()
    return generation
  }

  func finish(generation: UInt64) {
    lock.lock()
    callbacksInFlight = max(0, callbacksInFlight - 1)
    latestFinishedGeneration = max(latestFinishedGeneration, generation)
    lock.unlock()
  }

  func completionRequirementForNewTask() -> UInt64 {
    lock.lock()
    let requirement = latestStartedGeneration &+ 1
    lock.unlock()
    return requirement
  }

  func canComplete(
    requiredGeneration: UInt64,
    hasContentPending: Bool
  ) -> Bool {
    lock.lock()
    let canComplete = callbacksInFlight == 0
      && latestFinishedGeneration >= requiredGeneration
      && !hasContentPending
    lock.unlock()
    return canComplete
  }
}
