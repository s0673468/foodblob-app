import Foundation
import WatchConnectivity

@MainActor
final class FoodWatchConnectivityReceiver: NSObject, WCSessionDelegate {
  private struct PendingAcknowledgement {
    let generation: UInt64
    let acknowledgement: FoodWatchAcknowledgement
    let wasReset: Bool
  }

  private let session: WCSession?
  private let persistence: FoodBlobPersistence
  private let receipts: FoodWatchReceiptStore
  private let ingestOnMain: () -> Void
  private let activationGate = FoodWatchActivationGate()
  private var deliveryTracker = FoodWatchAcknowledgementDeliveryTracker()
  private var pendingAcknowledgements: [UUID: PendingAcknowledgement] = [:]
  private var lastConfirmedAcknowledgement: FoodWatchAcknowledgement?
  private var started = false

  init(
    persistence: FoodBlobPersistence = FoodBlobPersistence(),
    receipts: FoodWatchReceiptStore,
    ingestOnMain: @escaping () -> Void
  ) {
    session = WCSession.isSupported() ? WCSession.default : nil
    self.persistence = persistence
    self.receipts = receipts
    self.ingestOnMain = ingestOnMain
    super.init()
  }

  func start() {
    guard let session else { return }
    if !started {
      started = true
      activationGate.prepareForActivation()
      session.delegate = self
      session.activate()
    }
    guard session.activationState == .activated else { return }
    markAcknowledgementPending()
    retryPendingEvents()
    sendAcknowledgementIfPossible(forceTransport: true)
  }

  func requestAcknowledgement() {
    markAcknowledgementPending()
    sendAcknowledgementIfPossible()
  }

  func synchronizeAfterWidgetAction() async {
    start()
    if sendAcknowledgementIfPossible() { return }
    guard await activationGate.waitForActivation() else { return }
    _ = sendAcknowledgementIfPossible()
  }

  nonisolated func session(
    _ session: WCSession,
    activationDidCompleteWith activationState: WCSessionActivationState,
    error: Error?
  ) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      guard activationState == .activated else {
        self.started = false
        self.activationGate.resolve(false)
        return
      }
      self.markAcknowledgementPending()
      self.retryPendingEvents()
      self.sendAcknowledgementIfPossible(forceTransport: true)
      self.activationGate.resolve(true)
    }
  }

  nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}

  nonisolated func sessionDidDeactivate(_ session: WCSession) {
    Task { @MainActor [weak self] in
      self?.resetDeliveryProof()
      self?.activationGate.prepareForActivation()
      self?.started = false
      session.activate()
    }
  }

  nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
    Task { @MainActor [weak self] in
      guard let self else { return }
      self.resetDeliveryProof()
      self.markAcknowledgementPending()
      self.sendAcknowledgementIfPossible(forceTransport: true)
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveUserInfo userInfo: [String: Any]
  ) {
    if FoodWatchTransferContract.isStateRefreshRequest(userInfo) {
      Task { @MainActor [weak self] in self?.receiveStateRefreshRequest() }
    } else if let event = FoodWatchTransferContract.event(from: userInfo) {
      Task { @MainActor [weak self] in _ = self?.receiveOnMain(event) }
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didFinish userInfoTransfer: WCSessionUserInfoTransfer,
    error: Error?
  ) {
    guard
      let deliveryID = FoodWatchTransferContract.acknowledgementDeliveryID(
        from: userInfoTransfer.userInfo
      )
    else { return }
    Task { @MainActor [weak self] in
      guard let self else { return }
      let pending = self.pendingAcknowledgements.removeValue(
        forKey: deliveryID
      )
      let outcome = self.deliveryTracker.completed(
        deliveryID: deliveryID,
        succeeded: error == nil
      )
      switch outcome {
      case .confirmed(let generation):
        guard pending?.generation == generation else { return }
        if let acknowledgement = pending?.acknowledgement {
          self.lastConfirmedAcknowledgement = acknowledgement
        }
        if pending?.wasReset == true {
          do {
            try self.receipts.clearResetPending()
          } catch {
            self.markAcknowledgementPending()
          }
        }
      case .retry:
        self.sendAcknowledgementIfPossible(forceTransport: true)
      case .pending, .ignored:
        break
      }
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any]
  ) {
    if FoodWatchTransferContract.isStateRefreshRequest(message) {
      Task { @MainActor [weak self] in self?.receiveStateRefreshRequest() }
    } else if let event = FoodWatchTransferContract.event(from: message) {
      Task { @MainActor [weak self] in _ = self?.receiveOnMain(event) }
    }
  }

  nonisolated func session(
    _ session: WCSession,
    didReceiveMessage message: [String: Any],
    replyHandler: @escaping ([String: Any]) -> Void
  ) {
    if FoodWatchTransferContract.isStateRefreshRequest(message) {
      Task { @MainActor [weak self] in
        self?.receiveStateRefreshRequest()
        replyHandler(["accepted": self != nil])
      }
    } else if let event = FoodWatchTransferContract.event(from: message) {
      Task { @MainActor [weak self] in
        replyHandler(["accepted": self?.receiveOnMain(event) ?? false])
      }
    } else {
      replyHandler(["accepted": false])
    }
  }

  private func receiveStateRefreshRequest() {
    // The phone widget may have written to the append-only ledger while the
    // host app was suspended. Opening the Watch is an explicit opportunity to
    // ingest that ledger before projecting the latest state back.
    retryPendingEvents()
    ingestOnMain()
    markAcknowledgementPending()
    sendAcknowledgementIfPossible(forceTransport: true)
  }

  @discardableResult
  private func receiveOnMain(_ event: FoodWatchTransferEvent) -> Bool {
    do {
      let result = try receipts.stage(event)
      switch result {
      case .alreadyCommitted:
        markAcknowledgementPending()
        sendAcknowledgementIfPossible()
        return true
      case .resetInProgress:
        // The receipt store owns this decision under its lock. Do not append
        // an event that has no receipt and could be replayed after a reset.
        markAcknowledgementPending()
        sendAcknowledgementIfPossible()
        return false
      case .staged, .alreadyPending:
        break
      }
      return try appendReadyPendingEvents()
    } catch {
      // The staged entry remains on disk. The next app activation retries it.
      markAcknowledgementPending()
      return false
    }
  }

  private func retryPendingEvents() {
    guard
      FoodWatchDeliveryPolicy.shouldRetryPendingEvents(
        resetStagedButNotReady: (try? receipts.resetStagedButNotReady()) == true
      )
    else {
      return
    }
    do {
      let pending = try receipts.pendingEventsReadyForIngest()
      try persistence.appendWidgetEntriesIfNeeded(pending.map(\.entry))
      if !pending.isEmpty {
        scheduleIngest()
      }
    } catch {
      // Keep the staged receipt and retry it on the next activation.
      markAcknowledgementPending()
    }
  }

  private func appendReadyPendingEvents() throws -> Bool {
    let pending = try receipts.pendingEventsReadyForIngest()
    try persistence.appendWidgetEntriesIfNeeded(pending.map(\.entry))
    if !pending.isEmpty {
      scheduleIngest()
    }
    return !pending.isEmpty
  }

  private func scheduleIngest() {
    Task { @MainActor [weak self] in
      guard let self else { return }
      await Task.yield()
      self.ingestOnMain()
      self.markAcknowledgementPending()
      self.sendAcknowledgementIfPossible()
    }
  }

  @discardableResult
  private func sendAcknowledgementIfPossible(
    forceTransport: Bool = false
  ) -> Bool {
    guard let session else { return true }
    guard session.activationState == .activated else { return false }
    guard session.isPaired, session.isWatchAppInstalled else { return true }
    guard
      let generation = deliveryTracker.generationToQueue(
        forceTransport: forceTransport
      )
    else { return true }
    do {
      let receiptState = try receipts.acknowledgementState()
      guard !receiptState.isResetStagedButNotReady else { return false }
      guard let snapshot = persistence.readSnapshot() else { return false }
      let context = FoodWatchTransferContract.acknowledgementUserInfo(
        dateKey: snapshot.dateKey,
        counts: snapshot.counts,
        skin: snapshot.skin,
        generatedAt: snapshot.generatedAt,
        committedEventIDs: receiptState.committedEventIDs,
        committedThroughSequence: receiptState.committedThroughSequence,
        committedThroughSequencesBySender:
          receiptState.committedThroughSequencesBySender,
        isReset: receiptState.isReset,
        resetThroughSequence: receiptState.resetThroughSequence,
        senderID: receiptState.senderID,
        resetGeneration: receiptState.resetGeneration
      )
      guard
        let acknowledgement = FoodWatchTransferContract.acknowledgement(
          from: context
        )
      else { return false }
      if !forceTransport,
        lastConfirmedAcknowledgement?.hasSamePayload(as: acknowledgement)
          == true
      {
        if receiptState.isReset {
          try receipts.clearResetPending()
        }
        deliveryTracker.confirmPreviouslyDelivered(generation: generation)
        return true
      }

      try session.updateApplicationContext(context)
      if FoodWatchDeliveryPolicy.route(isReachable: session.isReachable)
        == .interactive
      {
        // Context is the durable latest snapshot. A direct message is the
        // low-latency path while the Watch app is reachable.
        session.sendMessage(context, replyHandler: nil) { _ in }
      }

      // Keep only the newest queued state projection. Watch actions are left
      // untouched because their append-only replay contract is independent.
      for transfer in session.outstandingUserInfoTransfers
      where FoodWatchTransferContract.acknowledgement(from: transfer.userInfo) != nil {
        if let deliveryID =
          FoodWatchTransferContract.acknowledgementDeliveryID(
            from: transfer.userInfo
          )
        {
          deliveryTracker.cancelled(deliveryID: deliveryID)
          pendingAcknowledgements.removeValue(forKey: deliveryID)
        }
        transfer.cancel()
      }
      let deliveryID = UUID()
      let taggedContext = FoodWatchTransferContract.tagAcknowledgementDelivery(
        context,
        deliveryID: deliveryID
      )
      switch FoodWatchDeliveryPolicy.stateTransferRoute(
        isComplicationEnabled: session.isComplicationEnabled
      ) {
      case .currentComplication:
        _ = session.transferCurrentComplicationUserInfo(taggedContext)
      case .backgroundUserInfo:
        session.transferUserInfo(taggedContext)
      }
      deliveryTracker.queued(
        deliveryID: deliveryID,
        generation: generation
      )
      pendingAcknowledgements[deliveryID] = PendingAcknowledgement(
        generation: generation,
        acknowledgement: acknowledgement,
        wasReset: receiptState.isReset
      )
      return true
    } catch {
      // The phone snapshot or receipt file may be temporarily unavailable.
      // The current generation remains pending for the next retry opportunity.
      return false
    }
  }

  private func markAcknowledgementPending() {
    deliveryTracker.markPending()
  }

  private func resetDeliveryProof() {
    lastConfirmedAcknowledgement = nil
    for transfer in session?.outstandingUserInfoTransfers ?? []
    where FoodWatchTransferContract.acknowledgement(from: transfer.userInfo) != nil {
      if let deliveryID =
        FoodWatchTransferContract.acknowledgementDeliveryID(
          from: transfer.userInfo
        )
      {
        deliveryTracker.cancelled(deliveryID: deliveryID)
        pendingAcknowledgements.removeValue(forKey: deliveryID)
      }
      transfer.cancel()
    }
  }
}
