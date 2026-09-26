import Dispatch
import Foundation
import SwiftUI

private struct BenchmarkMetric: Codable {
  let name: String
  let iterations: Int
  let samples: Int
  let medianNanosecondsPerOperation: Double
  let minimumNanosecondsPerOperation: Double
  let maximumNanosecondsPerOperation: Double
  let checksum: Double

  private enum CodingKeys: String, CodingKey {
    case name
    case iterations
    case samples
    case medianNanosecondsPerOperation = "median_ns_per_op"
    case minimumNanosecondsPerOperation = "minimum_ns_per_op"
    case maximumNanosecondsPerOperation = "maximum_ns_per_op"
    case checksum
  }
}

private struct BenchmarkReport: Codable {
  let configuration: String
  let seed: UInt64
  let metrics: [BenchmarkMetric]
}

@main
private enum PerformanceBenchmarks {
  static func main() throws {
    let arguments = BenchmarkArguments(arguments: Array(CommandLine.arguments.dropFirst()))
    let repeatedDateLedger = try makeLedgerFixture(lineCount: 1_000, uniqueDates: false)
    let uniqueDateLedger = try makeLedgerFixture(lineCount: 1_000, uniqueDates: true)
    let receiptFixture = try ReceiptFixture(receiptCount: 100)
    defer { receiptFixture.remove() }
    let metrics = [
      measure(
        name: "blob_color_mix",
        iterations: arguments.colorIterations,
        samples: arguments.samples,
        seed: arguments.seed,
        body: exerciseBlobColorMix
      ),
      measure(
        name: "puddle_path",
        iterations: arguments.pathIterations,
        samples: arguments.samples,
        seed: arguments.seed,
        body: exercisePuddlePath
      ),
      measure(
        name: "puddle_path_impact",
        iterations: arguments.pathIterations,
        samples: arguments.samples,
        seed: arguments.seed,
        body: exerciseImpactedPuddlePath
      ),
      measure(
        name: "ledger_parse_repeated_dates",
        iterations: arguments.ledgerIterations,
        samples: arguments.samples,
        seed: arguments.seed
      ) { iterations, seed in
        exerciseLedgerParse(
          iterations: iterations,
          seed: seed,
          contents: repeatedDateLedger
        )
      },
      measure(
        name: "ledger_parse_unique_dates",
        iterations: arguments.ledgerIterations,
        samples: arguments.samples,
        seed: arguments.seed
      ) { iterations, seed in
        exerciseLedgerParse(
          iterations: iterations,
          seed: seed,
          contents: uniqueDateLedger
        )
      },
      measure(
        name: "watch_receipt_separate_reads",
        iterations: arguments.receiptIterations,
        samples: arguments.samples,
        seed: arguments.seed
      ) { iterations, seed in
        exerciseSeparateReceiptReads(
          iterations: iterations,
          seed: seed,
          store: receiptFixture.store
        )
      },
      measure(
        name: "watch_receipt_snapshot",
        iterations: arguments.receiptIterations,
        samples: arguments.samples,
        seed: arguments.seed
      ) { iterations, seed in
        exerciseReceiptSnapshot(
          iterations: iterations,
          seed: seed,
          store: receiptFixture.store
        )
      },
    ]
    let report = BenchmarkReport(
      configuration: "swiftc -O -whole-module-optimization",
      seed: arguments.seed,
      metrics: metrics
    )
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    let data = try encoder.encode(report)
    FileHandle.standardOutput.write(data)
    FileHandle.standardOutput.write(Data("\n".utf8))
  }

  private static func measure(
    name: String,
    iterations: Int,
    samples: Int,
    seed: UInt64,
    body: (Int, UInt64) -> Double
  ) -> BenchmarkMetric {
    _ = body(max(1, iterations / 10), seed &+ 1)
    _ = body(max(1, iterations / 10), seed &+ 2)

    var durations: [Double] = []
    var checksum = 0.0
    for sample in 0..<samples {
      let start = DispatchTime.now().uptimeNanoseconds
      checksum += body(iterations, seed &+ UInt64(sample))
      let elapsed = DispatchTime.now().uptimeNanoseconds - start
      durations.append(Double(elapsed) / Double(iterations))
    }
    durations.sort()
    return BenchmarkMetric(
      name: name,
      iterations: iterations,
      samples: samples,
      medianNanosecondsPerOperation: durations[durations.count / 2],
      minimumNanosecondsPerOperation: durations[0],
      maximumNanosecondsPerOperation: durations[durations.count - 1],
      checksum: checksum
    )
  }

  @inline(never)
  private static func exerciseBlobColorMix(iterations: Int, seed: UInt64) -> Double {
    var state = seed
    var checksum = 0.0
    for _ in 0..<iterations {
      state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
      let counts = FoodCounts(
        green: Int((state >> 8) % 19),
        yellow: Int((state >> 24) % 19),
        red: Int((state >> 40) % 19)
      )
      if let color = BlobColor.mixed(counts: counts) {
        checksum += color.red + color.green * 3 + color.blue * 7
      }
    }
    return checksum
  }

  @inline(never)
  private static func exercisePuddlePath(iterations: Int, seed: UInt64) -> Double {
    var state = seed ^ 0x4F1B_5A6D_87C2_39E1
    var checksum = 0.0
    let rect = CGRect(x: 0, y: 0, width: 320, height: 280)
    for _ in 0..<iterations {
      state = state &* 2_862_933_555_777_941_757 &+ 3_037_000_493
      let counts = FoodCounts(
        green: Int((state >> 7) % 19),
        yellow: Int((state >> 23) % 19),
        red: Int((state >> 39) % 19)
      )
      let boost = CGFloat((state >> 56) & 0xFF) / 255
      let bounds = PuddleShape(
        counts: counts,
        tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts),
        boost: boost
      ).path(in: rect).boundingRect
      checksum += bounds.origin.x + bounds.origin.y + bounds.width + bounds.height
    }
    return checksum
  }

  @inline(never)
  private static func exerciseImpactedPuddlePath(iterations: Int, seed: UInt64) -> Double {
    var state = seed ^ 0xA7C9_1D3E_62B4_508F
    var checksum = 0.0
    let rect = CGRect(x: 0, y: 0, width: 320, height: 280)
    let origins: [CGFloat] = [0.25, 0.50, 0.75]
    for _ in 0..<iterations {
      state = state &* 2_862_933_555_777_941_757 &+ 3_037_000_493
      let counts = FoodCounts(
        green: Int((state >> 7) % 19),
        yellow: Int((state >> 23) % 19),
        red: Int((state >> 39) % 19)
      )
      let depth = CGFloat((state >> 56) & 0xFF) / 255
      let origin = origins[Int((state >> 52) % UInt64(origins.count))]
      let bounds = PuddleShape(
        counts: counts,
        tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts),
        boost: depth * 0.35,
        impactOriginX: origin,
        impactDepth: depth
      ).path(in: rect).boundingRect
      checksum += bounds.origin.x + bounds.origin.y + bounds.width + bounds.height
    }
    return checksum
  }

  @inline(never)
  private static func exerciseLedgerParse(
    iterations: Int,
    seed: UInt64,
    contents: String
  ) -> Double {
    var checksum = Double(seed % 97)
    for _ in 0..<iterations {
      let parsed = FoodWidgetLedger.parse(contents)
      checksum += Double(parsed.entries.count * 3 + parsed.malformedLineCount * 7)
      for entry in parsed.entries {
        checksum += Double(entry.dateKey.utf8.count + entry.delta)
      }
    }
    return checksum
  }

  @inline(never)
  private static func exerciseSeparateReceiptReads(
    iterations: Int,
    seed: UInt64,
    store: FoodWatchReceiptStore
  ) -> Double {
    var checksum = Double(seed % 97)
    for _ in 0..<iterations {
      let ids = try! store.committedEventIDs()
      let through = try! store.committedThroughSequence()
      let senderID = try! store.activeSenderID()
      let isReset = try! store.resetPending()
      let resetThrough = try! store.resetThroughSequence()
      let resetGeneration = try! store.resetGeneration()
      let stagedReset = try! store.resetStagedButNotReady()
      checksum += receiptChecksum(
        committedEventIDs: ids,
        committedThroughSequence: through,
        senderID: senderID,
        isReset: isReset,
        resetThroughSequence: resetThrough,
        resetGeneration: resetGeneration,
        isResetStagedButNotReady: stagedReset
      )
    }
    return checksum
  }

  @inline(never)
  private static func exerciseReceiptSnapshot(
    iterations: Int,
    seed: UInt64,
    store: FoodWatchReceiptStore
  ) -> Double {
    var checksum = Double(seed % 97)
    for _ in 0..<iterations {
      let state = try! store.acknowledgementState()
      checksum += receiptChecksum(
        committedEventIDs: state.committedEventIDs,
        committedThroughSequence: state.committedThroughSequence,
        senderID: state.senderID,
        isReset: state.isReset,
        resetThroughSequence: state.resetThroughSequence,
        resetGeneration: state.resetGeneration,
        isResetStagedButNotReady: state.isResetStagedButNotReady
      )
    }
    return checksum
  }

  private static func receiptChecksum(
    committedEventIDs: [UUID],
    committedThroughSequence: Int64,
    senderID: UUID?,
    isReset: Bool,
    resetThroughSequence: Int64?,
    resetGeneration: UUID?,
    isResetStagedButNotReady: Bool
  ) -> Double {
    Double(committedEventIDs.count * 11)
      + Double(committedThroughSequence * 13)
      + Double(resetThroughSequence ?? 0) * 17
      + (senderID == nil ? 0 : 19)
      + (isReset ? 23 : 0)
      + (resetGeneration == nil ? 0 : 29)
      + (isResetStagedButNotReady ? 31 : 0)
  }

  private static func makeLedgerFixture(
    lineCount: Int,
    uniqueDates: Bool
  ) throws -> String {
    let start = Date(timeIntervalSince1970: 1_800_000_000)
    return try (0..<lineCount).map { offset in
      let dateKey: String
      if uniqueDates {
        dateKey = FoodDateKey.string(
          for: start.addingTimeInterval(Double(offset) * 86_400)
        )
      } else {
        dateKey =
          [
            "2027-01-15",
            "2027-01-16",
            "2027-01-17",
            "2027-02-30",
            "2027-13-01",
          ][offset % 5]
      }
      return try FoodWidgetLedgerEntry(
        timestamp: start.addingTimeInterval(Double(offset)),
        dateKey: dateKey,
        color: FoodColor.allCases[offset % FoodColor.allCases.count],
        delta: offset.isMultiple(of: 7) ? -1 : 1
      ).jsonLine()
    }.joined(separator: "\n")
  }
}

private final class ReceiptFixture {
  let directory: URL
  let store: FoodWatchReceiptStore

  init(receiptCount: Int) throws {
    directory = FileManager.default.temporaryDirectory.appendingPathComponent(
      "FoodBlobReceiptBenchmark-\(UUID().uuidString)",
      isDirectory: true
    )
    try FileManager.default.createDirectory(
      at: directory,
      withIntermediateDirectories: true
    )
    let senderID = UUID()
    var document = FoodWatchReceiptDocument()
    document.activeSenderID = senderID
    document.committedThroughSequence = 1
    document.resetThroughSequence = 7
    document.resetGeneration = UUID()
    document.committedReceipts = (0..<receiptCount).map { offset in
      FoodWatchCommittedReceipt(
        sequence: Int64(offset + 2),
        id: UUID(),
        senderID: senderID
      )
    }
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = .iso8601
    try encoder.encode(document).write(
      to: directory.appendingPathComponent(FoodBlobConstants.watchReceiptFileName)
    )
    store = FoodWatchReceiptStore(containerURL: directory)
  }

  func remove() {
    try? FileManager.default.removeItem(at: directory)
  }
}

private struct BenchmarkArguments {
  let samples: Int
  let colorIterations: Int
  let pathIterations: Int
  let ledgerIterations: Int
  let receiptIterations: Int
  let seed: UInt64

  init(arguments: [String]) {
    var values: [String: Int] = [:]
    var index = 0
    while index + 1 < arguments.count {
      if arguments[index].hasPrefix("--"), let value = Int(arguments[index + 1]) {
        values[arguments[index]] = value
        index += 2
      } else {
        index += 1
      }
    }
    samples = max(3, values["--samples"] ?? 7)
    colorIterations = max(1, values["--color-iterations"] ?? 250_000)
    pathIterations = max(1, values["--path-iterations"] ?? 10_000)
    ledgerIterations = max(1, values["--ledger-iterations"] ?? 100)
    receiptIterations = max(1, values["--receipt-iterations"] ?? 100)
    seed = UInt64(max(1, values["--seed"] ?? 1_104_271_941))
  }
}
