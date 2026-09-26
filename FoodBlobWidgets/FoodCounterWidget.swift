import SwiftUI
import WidgetKit

@available(iOS 17.0, *)
struct FoodCounterWidgetView: View {
  let entry: FoodWidgetEntry

  @Environment(\.widgetFamily) private var family

  private var presentation: FoodWidgetPresentation {
    family == .systemSmall ? .counter : .combined
  }

  var body: some View {
    ZStack {
      FoodWidgetArtwork(
        counts: entry.counts, skin: entry.skin, presentation: presentation,
        isAvailable: entry.isAvailable,
        translucency: BlobMaterial.read()
      )
      .accessibilityHidden(true)
      .allowsHitTesting(false)

      FoodWidgetInteractionOverlay(entry: entry, presentation: presentation)

      if !entry.isAvailable {
        FoodWidgetUnavailableOverlay(skin: entry.skin)
          .allowsHitTesting(false)
      }
    }
    .widgetURL(URL(string: "foodblob://today"))
    .containerBackground(for: .widget) {
      FoodWidgetCardBackground(skin: entry.skin, presentation: presentation)
    }
  }
}

@available(iOS 17.0, *)
private struct FoodWidgetUnavailableOverlay: View {
  let skin: SkinID

  private var foreground: Color {
    skin == .shrine
      ? Color(red: 0.92, green: 0.96, blue: 0.96)
      : Color(red: 0.23, green: 0.19, blue: 0.15)
  }

  private var surface: Color {
    skin == .shrine
      ? Color(red: 0.04, green: 0.08, blue: 0.13).opacity(0.90)
      : Color(red: 1.00, green: 0.98, blue: 0.93).opacity(0.92)
  }

  var body: some View {
    VStack(spacing: 4) {
      Image(systemName: "iphone.badge.exclamationmark")
        .font(.system(size: 18, weight: .semibold, design: .rounded))
      Text("Open Food Blob")
        .font(.system(size: 13, weight: .bold, design: .rounded))
      Text("to finish setup")
        .font(.system(size: 10, weight: .medium, design: .rounded))
        .opacity(0.78)
    }
    .foregroundStyle(foreground)
    .multilineTextAlignment(.center)
    .padding(.horizontal, 14)
    .padding(.vertical, 10)
    .background(surface, in: RoundedRectangle(cornerRadius: 16))
    .overlay {
      RoundedRectangle(cornerRadius: 16)
        .stroke(foreground.opacity(0.18), lineWidth: 1)
    }
    .accessibilityHidden(true)
  }
}

@available(iOS 17.0, *)
private struct FoodWidgetInteractionOverlay: View {
  let entry: FoodWidgetEntry
  let presentation: FoodWidgetPresentation

  var body: some View {
    FoodWidgetLayout(presentation: presentation) {
      Color.clear
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(blobAccessibilityLabel)
        .accessibilitySortPriority(7)
    } control: { color, action in
      controlButton(for: color, action: action)
    }
    .opacity(entry.isAvailable ? 1 : 0.55)
  }

  private func controlButton(
    for color: FoodColor,
    action: FoodWidgetControlAction
  ) -> some View {
    let count = entry.counts.count(for: color)
    let delta = action == .increase ? 1 : -1
    return Button(intent: ChangeFoodCountIntent(color: color, delta: delta)) {
      Color.clear
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .disabled(!entry.isAvailable || (action == .decrease && count == 0))
    .accessibilityLabel(
      entry.isAvailable
        ? "\(action == .increase ? "Add" : "Remove") one \(color.displayName.lowercased()) food. \(count) logged today."
        : "Food Blob setup needed"
    )
    .accessibilityHint(
      action == .increase ? "Adds one offering" : "Removes one offering"
    )
    .accessibilitySortPriority(accessibilityPriority(for: color, action: action))
  }

  private func accessibilityPriority(
    for color: FoodColor,
    action: FoodWidgetControlAction
  ) -> Double {
    let category: Double =
      switch color {
      case .green: 4
      case .yellow: 3
      case .red: 2
      }
    return category + (action == .increase ? 0.1 : 0)
  }

  private var blobAccessibilityLabel: String {
    guard entry.isAvailable else { return "Food Blob setup needed" }
    return
      "Food mix. \(FoodCountText.offerings(entry.counts.total)). "
      + "\(entry.counts.green) green, \(entry.counts.yellow) yellow, "
      + "\(entry.counts.red) red."
  }
}

@available(iOS 17.0, *)
struct FoodCounterWidget: Widget {
  private let fixedSkin: SkinID

  init() {
    fixedSkin = .skyMeadow
  }

  init(fixedSkin: SkinID) {
    self.fixedSkin = fixedSkin
  }

  var body: some WidgetConfiguration {
    StaticConfiguration(
      kind: widgetKind,
      provider: FoodWidgetProvider(fixedSkin: fixedSkin)
    ) { entry in
      FoodCounterWidgetView(entry: entry)
    }
    .configurationDisplayName(displayName)
    .description(description)
    .supportedFamilies([.systemSmall, .systemMedium])
    .contentMarginsDisabled()
  }

  private var widgetKind: String {
    FoodBlobConstants.widgetKind(for: fixedSkin)
  }

  private var displayName: String {
    fixedSkin == .skyMeadow ? "Sky Meadow Food Blob" : "Shrine Food Blob"
  }

  private var description: String {
    "A fixed-skin small add widget or medium add-and-remove widget."
  }
}

@available(iOS 17.0, *)
#Preview("Sky Meadow Add", as: .systemSmall) {
  FoodCounterWidget(fixedSkin: .skyMeadow)
} timeline: {
  FoodWidgetEntry(
    date: Date(),
    counts: FoodCounts(green: 4, yellow: 2, red: 1),
    skin: .skyMeadow,
    isAvailable: true
  )
}

@available(iOS 17.0, *)
#Preview("Shrine Add", as: .systemSmall) {
  FoodCounterWidget(fixedSkin: .shrine)
} timeline: {
  FoodWidgetEntry(
    date: Date(),
    counts: FoodCounts(green: 4, yellow: 2, red: 1),
    skin: .shrine,
    isAvailable: true
  )
}

@available(iOS 17.0, *)
#Preview("Shrine Medium", as: .systemMedium) {
  FoodCounterWidget(fixedSkin: .shrine)
} timeline: {
  FoodWidgetEntry(
    date: Date(),
    counts: FoodCounts(green: 4, yellow: 2, red: 1),
    skin: .shrine,
    isAvailable: true
  )
}
