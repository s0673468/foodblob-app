import Foundation

enum FoodWatchAcknowledgementDeliveryOutcome: Equatable, Sendable {
  case ignored
  case confirmed(UInt64)
  case retry(UInt64)
  case pending(UInt64)
}

/// Tracks the outcome of the durable phone-to-Watch state projection.
/// Queuing a WatchConnectivity transfer is not delivery; only `didFinish`
/// confirms it. Failed generations receive one immediate retry and otherwise
/// remain pending for the next activation or state publication.
struct FoodWatchAcknowledgementDeliveryTracker: Sendable {
  private(set) var desiredGeneration: UInt64 = 1
  private(set) var confirmedGeneration: UInt64 = 0
  private var queuedGenerations: [UUID: UInt64] = [:]
  private var automaticallyRetriedGenerations: Set<UInt64> = []

  var hasPendingDelivery: Bool {
    desiredGeneration != confirmedGeneration
  }

  @discardableResult
  mutating func markPending() -> UInt64 {
    desiredGeneration &+= 1
    automaticallyRetriedGenerations.removeAll(keepingCapacity: true)
    return desiredGeneration
  }

  func generationToQueue(forceTransport: Bool = false) -> UInt64? {
    guard hasPendingDelivery else { return nil }
    if !forceTransport,
      queuedGenerations.values.contains(desiredGeneration)
    {
      return nil
    }
    return desiredGeneration
  }

  mutating func queued(deliveryID: UUID, generation: UInt64) {
    queuedGenerations[deliveryID] = generation
  }

  mutating func cancelled(deliveryID: UUID) {
    queuedGenerations.removeValue(forKey: deliveryID)
  }

  @discardableResult
  mutating func confirmPreviouslyDelivered(
    generation: UInt64
  ) -> FoodWatchAcknowledgementDeliveryOutcome {
    guard generation == desiredGeneration else { return .ignored }
    confirmedGeneration = generation
    automaticallyRetriedGenerations.remove(generation)
    return .confirmed(generation)
  }

  mutating func completed(
    deliveryID: UUID,
    succeeded: Bool
  ) -> FoodWatchAcknowledgementDeliveryOutcome {
    guard let generation = queuedGenerations.removeValue(forKey: deliveryID),
      generation == desiredGeneration,
      generation != confirmedGeneration
    else {
      return .ignored
    }
    if succeeded {
      confirmedGeneration = generation
      automaticallyRetriedGenerations.remove(generation)
      return .confirmed(generation)
    }
    if automaticallyRetriedGenerations.insert(generation).inserted {
      return .retry(generation)
    }
    return .pending(generation)
  }
}
