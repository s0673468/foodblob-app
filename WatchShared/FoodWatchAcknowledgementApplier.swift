import Foundation

#if FOOD_BLOB_TESTS
  @testable import FoodBlob
#endif

struct FoodWatchAcknowledgementApplier {
  private let persist: (FoodWatchAcknowledgement) throws -> Bool
  private let reloadTimeline: (String) -> Void

  init(
    outbox: FoodWatchOutboxStore,
    reloadTimeline: @escaping (String) -> Void
  ) {
    persist = { try outbox.apply($0) }
    self.reloadTimeline = reloadTimeline
  }

  init(
    persist: @escaping (FoodWatchAcknowledgement) throws -> Bool,
    reloadTimeline: @escaping (String) -> Void
  ) {
    self.persist = persist
    self.reloadTimeline = reloadTimeline
  }

  @discardableResult
  func apply(_ acknowledgement: FoodWatchAcknowledgement) throws -> Bool {
    do {
      let changed = try persist(acknowledgement)
      if changed {
        reloadTimeline(FoodWatchConstants.widgetKind)
      }
      return changed
    } catch {
      do {
        let changed = try persist(acknowledgement)
        if changed {
          reloadTimeline(FoodWatchConstants.widgetKind)
        }
        return changed
      } catch {
        // A second failure should still refresh the complication's error or
        // unavailable presentation before the system ends background work.
        reloadTimeline(FoodWatchConstants.widgetKind)
        throw error
      }
    }
  }
}
