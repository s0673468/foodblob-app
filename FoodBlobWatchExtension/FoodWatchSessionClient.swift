import Foundation
import WatchConnectivity
import WatchKit
import WidgetKit

@MainActor
final class FoodWatchSessionClient: NSObject, WCSessionDelegate {
  private let session: WCSession?
  private let outbox: FoodWatchOutboxStore
  private let acknowledgementApplier: FoodWatchAcknowledgementApplier
  nonisolated private let incomingDeliveryTracker =
    FoodWatchIncomingDeliveryTracker()
  private var transferredIDs: Set<UUID> = []
  private var started = false
  private var stateRefreshRetriesRemaining = 1
  private var pendingBackgroundTasks: [WKWatchConnectivityRefreshBackgroundTask] = []
  private var requiredIncomingGeneration: UInt64?

  var onStateChanged: (() -> Void)?

  init(outbox: FoodWatchOutboxStore) {
    session = WCSession.isSupported() ? WCSession.default : nil
    self.outbox = outbox
    acknowledgementApplier = FoodWatchAcknowledgementApplier(
      outbox: outbox,
      reloadTimeline: { kind in
        WidgetCenter.shared.reloadTimelines(ofKind: kind)
      }
    )
    super.init()
  }

  func start() {
    guard let session else { return }
    stateRefreshRetriesRemaining = 1
    if !started {
      started = true
      session.delegate = self
      session.activate()
    }
    guard session.activationState == .activated else { return }
    applyLatestApplicationContext()
    transferPendingIfPossible()
    requestLatestPhoneState()
  }

  @discardableResult
  func enqueue(_ entry: FoodWidgetLedgerEntry) -> Bool {
    do {
      guard try outbox.append(entry) else { return false }
      transferPendingIfPossible()
      notifyStateChanged()
      return true
    } catch {
      notifyStateChanged()
      return false
    }
  }

  func handle(_ backgroundTasks: Set<WKRefreshBackgroundTask>) {
    var addedConnectivityTask = false
    for task in backgroundTasks {
      guard let connectivityTask = task as? WKWatchConnectivityRefreshBackgroundTask
      else {
        task.setTaskCompletedWithSnapshot(false)
        continue
      }
      connectivityTask.expirationHandler = { [weak self] in
        DispatchQueue.main.async {
          self?.completeAllBackgroundTasks()
        }
      }
      pendingBackgroundTasks.append(connectivityTask)
      addedConnectivityTask = true
    }
    if addedConnectivityTask {
      let requirement =
        incomingDeliveryTracker.completionRequirementForNewTask()
      requiredIncomingGeneration = max(
        requiredIncomingGeneration ?? 0,
        requirement
      )
    }
    start()
    if session == nil {
      completeAllBackgroundTasks()
    }
  }

  nonisolated func session(
    _ session: WCSession,
    activationDidCompleteWith activationState: WCSessionActivationState,
    error: Error?
  ) {
    DispatchQueue.main.async { [weak self] in
      self?.activationDidComplete(with: activationState)
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveApplicationContext applicationContext: [String: Any]
  ) {
    let deliveryGeneration = incomingDeliveryTracker.begin()
    DispatchQueue.main.async { [weak self] in
      defer {
        self?.finishIncomingDelivery(generation: deliveryGeneration)
      }
      self?.apply(applicationContext: applicationContext)
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveUserInfo userInfo: [String: Any]
  ) {
    let deliveryGeneration = incomingDeliveryTracker.begin()
    DispatchQueue.main.async { [weak self] in
      defer {
        self?.finishIncomingDelivery(generation: deliveryGeneration)
      }
      self?.apply(applicationContext: userInfo)
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any]
  ) {
    let deliveryGeneration = incomingDeliveryTracker.begin()
    DispatchQueue.main.async { [weak self] in
      defer {
        self?.finishIncomingDelivery(generation: deliveryGeneration)
      }
      self?.apply(applicationContext: message)
    }
  }

  private func activationDidComplete(with activationState: WCSessionActivationState) {
    guard activationState == .activated else {
      // A deactivated session can be replaced when the paired phone changes.
      // Let the next scene/background activation install the new delegate and
      // retry the outbox instead of permanently latching `started`.
      started = false
      completeAllBackgroundTasks()
      return
    }
    stateRefreshRetriesRemaining = 1
    applyLatestApplicationContext()
    transferPendingIfPossible()
    requestLatestPhoneState()
    notifyStateChanged()
    completeBackgroundTasksIfPossible()
  }

  private func applyLatestApplicationContext() {
    guard let applicationContext = session?.receivedApplicationContext else {
      return
    }
    apply(applicationContext: applicationContext)
  }

  private func apply(applicationContext: [String: Any]) {
    guard
      let acknowledgement = FoodWatchTransferContract.acknowledgement(
        from: applicationContext
      )
    else {
      return
    }
    do {
      let changed = try acknowledgementApplier.apply(acknowledgement)
      transferredIDs.subtract(acknowledgement.committedEventIDs)
      // An acknowledgement is also a retry opportunity. A phone may have
      // rejected an interactive event while a reset was staged, then cancel
      // that reset without changing the event's UUID. Reconsider every
      // surviving event; outstanding durable transfers are re-added by
      // transferPendingIfPossible(), while completed ones are sent again.
      transferredIDs.removeAll()
      transferPendingIfPossible()
      if changed {
        onStateChanged?()
      }
    } catch {
      onStateChanged?()
    }
  }

  private func transferPendingIfPossible() {
    guard let session, session.activationState == .activated else { return }
    let pending = outbox.pendingEvents()
    let pendingIDs = Set(pending.map { $0.entry.id })
    transferredIDs.formIntersection(pendingIDs)
    let queuedIDs = Set(
      session.outstandingUserInfoTransfers.compactMap {
        FoodWatchTransferContract.event(from: $0.userInfo)?.entry.id
      }
    )
    transferredIDs.formUnion(queuedIDs.intersection(pendingIDs))
    for event in pending
    where !transferredIDs.contains(event.entry.id) {
      guard let userInfo = try? FoodWatchTransferContract.userInfo(for: event)
      else { continue }
      switch FoodWatchDeliveryPolicy.route(isReachable: session.isReachable) {
      case .interactive:
        // The direct message makes an active tap feel immediate. Keep the
        // same event in WatchConnectivity's durable queue as a crash/restart
        // fallback; the phone receipt and ledger boundaries deduplicate it.
        let eventID = event.entry.id
        session.sendMessage(
          userInfo,
          replyHandler: { [weak self] reply in
            guard
              FoodWatchDeliveryPolicy.shouldRetryInteractiveMessage(
                replyAccepted: (reply["accepted"] as? Bool) == true,
                durableFallbackQueued: true
              )
            else { return }
            Task { @MainActor in
              self?.retry(eventID: eventID)
            }
          },
          errorHandler: { [weak self] _ in
            Task { @MainActor in
              self?.retry(eventID: eventID)
            }
          }
        )
        session.transferUserInfo(userInfo)
      case .background:
        session.transferUserInfo(userInfo)
      }
      transferredIDs.insert(event.entry.id)
    }
  }

  private func requestLatestPhoneState() {
    guard let session, session.activationState == .activated else { return }
    let request = FoodWatchTransferContract.stateRefreshRequestUserInfo()
    for transfer in session.outstandingUserInfoTransfers
    where FoodWatchTransferContract.isStateRefreshRequest(transfer.userInfo) {
      transfer.cancel()
    }
    session.transferUserInfo(request)
    if session.isReachable {
      session.sendMessage(request, replyHandler: nil) { _ in }
    }
  }

  private func retry(eventID: UUID) {
    transferredIDs.remove(eventID)
    transferPendingIfPossible()
    notifyStateChanged()
  }

  private func notifyStateChanged() {
    WidgetCenter.shared.reloadTimelines(ofKind: FoodWatchConstants.widgetKind)
    onStateChanged?()
  }

  nonisolated func session(
    _ session: WCSession,
    didFinish userInfoTransfer: WCSessionUserInfoTransfer,
    error: Error?
  ) {
    let eventID = FoodWatchTransferContract.event(from: userInfoTransfer.userInfo)?
      .entry.id
    let isStateRefresh = FoodWatchTransferContract.isStateRefreshRequest(
      userInfoTransfer.userInfo
    )
    Task { @MainActor [weak self] in
      guard let self else { return }
      if error != nil, let eventID {
        self.transferredIDs.remove(eventID)
        self.transferPendingIfPossible()
      } else if isStateRefresh {
        let hasQueuedRefresh = session.outstandingUserInfoTransfers.contains {
          FoodWatchTransferContract.isStateRefreshRequest($0.userInfo)
        }
        if FoodWatchDeliveryPolicy.shouldRetryStateRefresh(
          transferFailed: error != nil,
          hasQueuedRefresh: hasQueuedRefresh,
          retriesRemaining: self.stateRefreshRetriesRemaining
        ) {
          self.stateRefreshRetriesRemaining -= 1
          self.requestLatestPhoneState()
        }
      }
      self.completeBackgroundTasksIfPossible()
    }
  }

  private func finishIncomingDelivery(generation: UInt64) {
    incomingDeliveryTracker.finish(generation: generation)
    completeBackgroundTasksIfPossible()
  }

  private func completeBackgroundTasksIfPossible() {
    guard !pendingBackgroundTasks.isEmpty else { return }
    guard let requiredIncomingGeneration else { return }
    guard let session else {
      completeAllBackgroundTasks()
      return
    }
    guard session.activationState == .activated else { return }
    guard incomingDeliveryTracker.canComplete(
      requiredGeneration: requiredIncomingGeneration,
      hasContentPending: session.hasContentPending
    ) else {
      return
    }
    completeAllBackgroundTasks()
  }

  private func completeAllBackgroundTasks() {
    let tasks = pendingBackgroundTasks
    pendingBackgroundTasks.removeAll()
    requiredIncomingGeneration = nil
    for task in tasks {
      task.setTaskCompletedWithSnapshot(false)
    }
  }
}

@MainActor
final class FoodWatchRuntime {
  static let shared = FoodWatchRuntime()

  let outbox: FoodWatchOutboxStore
  let sessionClient: FoodWatchSessionClient

  private init() {
    let outbox = FoodWatchOutboxStore()
    self.outbox = outbox
    sessionClient = FoodWatchSessionClient(outbox: outbox)
  }
}
