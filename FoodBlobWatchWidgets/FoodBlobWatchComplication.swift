import SwiftUI
import WidgetKit

struct FoodWatchComplicationEntry: TimelineEntry {
  let date: Date
  let state: FoodWatchDisplayState
}

struct FoodWatchComplicationProvider: TimelineProvider {
  private let outbox = FoodWatchOutboxStore()

  func placeholder(in context: Context) -> FoodWatchComplicationEntry {
    FoodWatchComplicationEntry(
      date: Date(),
      state: FoodWatchDisplayState(
        dateKey: FoodDateKey.string(),
        counts: FoodCounts(green: 3, yellow: 2, red: 1),
        skin: .skyMeadow,
        pendingCount: 0,
        isAvailable: true,
        hasPhoneState: true
      )
    )
  }

  func getSnapshot(
    in context: Context,
    completion: @escaping (FoodWatchComplicationEntry) -> Void
  ) {
    if context.isPreview {
      completion(placeholder(in: context))
    } else {
      completion(entry(at: Date()))
    }
  }

  func getTimeline(
    in context: Context,
    completion: @escaping (Timeline<FoodWatchComplicationEntry>) -> Void
  ) {
    let now = Date()
    let nextDay = FoodDateKey.startOfNextDay(after: now)
    completion(
      Timeline(
        entries: [entry(at: now), entry(at: nextDay)],
        policy: .after(nextDay)
      )
    )
  }

  private func entry(at date: Date) -> FoodWatchComplicationEntry {
    FoodWatchComplicationEntry(
      date: date,
      state: outbox.displayState(at: date)
    )
  }
}

struct FoodBlobWatchComplication: Widget {
  let kind = FoodWatchConstants.widgetKind

  var body: some WidgetConfiguration {
    StaticConfiguration(
      kind: kind,
      provider: FoodWatchComplicationProvider()
    ) { entry in
      FoodBlobWatchComplicationView(entry: entry)
    }
    .configurationDisplayName("Food Blob")
    .description("A quiet view of today's offering mix.")
    .supportedFamilies([
      .accessoryCircular,
      .accessoryRectangular,
      .accessoryCorner,
      .accessoryInline,
    ])
  }
}

struct FoodBlobWatchComplicationView: View {
  let entry: FoodWatchComplicationEntry

  @Environment(\.widgetFamily) private var family

  private var palette: FoodWatchPalette {
    .forSkin(entry.state.skin)
  }

  private var hasCurrentState: Bool {
    entry.state.hasPhoneState || entry.state.pendingCount > 0
  }

  var body: some View {
    Group {
      switch family {
      case .accessoryCircular:
        circular
      case .accessoryRectangular:
        rectangular
      case .accessoryCorner:
        corner
      case .accessoryInline:
        inline
      default:
        inline
      }
    }
    .widgetURL(URL(string: "foodblob://today"))
    .containerBackground(for: .widget) {
      palette.background
    }
  }

  private var circular: some View {
    ZStack {
      FoodWatchBlobArtwork(
        counts: entry.state.counts,
        skin: entry.state.skin,
        showsCount: false
      )
      if hasCurrentState {
        Text("\(entry.state.counts.total)")
          .font(.system(size: 17, weight: .heavy, design: .rounded))
          .foregroundStyle(.primary)
      } else {
        Text("—")
          .font(.system(size: 20, weight: .bold, design: .rounded))
      }
    }
    .accessibilityLabel(accessibilityLabel)
  }

  private var rectangular: some View {
    HStack(spacing: 6) {
      FoodWatchBlobArtwork(
        counts: entry.state.counts,
        skin: entry.state.skin,
        showsCount: false
      )
      .frame(width: 38)
      VStack(alignment: .leading, spacing: 1) {
        Text("Today")
          .font(.system(size: 11, weight: .semibold, design: .rounded))
        if hasCurrentState {
          Text(FoodCountText.offerings(entry.state.counts.total))
            .font(.system(size: 12, weight: .bold, design: .rounded))
          Text(
            "G \(entry.state.counts.green) · Y \(entry.state.counts.yellow) · R \(entry.state.counts.red)"
          )
          .font(.system(size: 8, weight: .medium, design: .rounded))
          .minimumScaleFactor(0.75)
        } else {
          Text(waitingText)
            .font(.system(size: 10, weight: .medium, design: .rounded))
        }
      }
      .foregroundStyle(.primary)
      .lineLimit(1)
    }
    .accessibilityLabel(accessibilityLabel)
  }

  private var corner: some View {
    ZStack {
      FoodWatchBlobArtwork(
        counts: entry.state.counts,
        skin: entry.state.skin,
        showsCount: false
      )
      if hasCurrentState {
        Text("\(entry.state.counts.total)")
          .font(.system(size: 15, weight: .heavy, design: .rounded))
          .foregroundStyle(.primary)
      } else {
        Text("—")
          .font(.system(size: 17, weight: .bold, design: .rounded))
          .foregroundStyle(.primary)
      }
    }
    .accessibilityLabel(accessibilityLabel)
  }

  private var inline: some View {
    Text(
      hasCurrentState
        ? "Today \(FoodCountText.offerings(entry.state.counts.total))"
        : waitingText
    )
    .font(.system(size: 12, weight: .semibold, design: .rounded))
    .accessibilityLabel(accessibilityLabel)
  }

  private var accessibilityLabel: String {
    guard entry.state.isAvailable else { return "Food Blob unavailable" }
    guard hasCurrentState else { return "Food Blob is waiting for iPhone" }
    return
      "Today. \(FoodCountText.offerings(entry.state.counts.total)). "
      + "\(entry.state.counts.green) green, "
      + "\(entry.state.counts.yellow) yellow, "
      + "\(entry.state.counts.red) red."
  }

  private var waitingText: String {
    entry.state.isAvailable ? "Waiting for iPhone" : "Food Blob unavailable"
  }
}
