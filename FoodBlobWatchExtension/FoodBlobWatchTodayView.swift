import SwiftUI

struct FoodBlobWatchTodayView: View {
  @EnvironmentObject private var model: FoodWatchModel
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  private var palette: FoodWatchPalette {
    .forSkin(model.displayState.skin)
  }

  var body: some View {
    GeometryReader { geometry in
      let layout = FoodWatchTodayLayoutMetrics.resolve(
        for: geometry.size.height
      )
      ScrollView {
        watchContent(layout: layout)
      }
      .scrollIndicators(.hidden)
    }
    .background(palette.background)
    .tint(palette.accent)
    .accessibilityElement(children: .contain)
  }

  @ViewBuilder
  private func watchContent(
    layout: FoodWatchTodayLayoutMetrics
  ) -> some View {
    VStack(spacing: layout.contentSpacing) {
        Text("Today")
          .font(.system(size: 14, weight: .semibold, design: .rounded))
          .foregroundStyle(palette.mutedInk)

        if model.displayState.isAvailable {
          if !model.displayState.hasPhoneState {
            Text(waitingForPhoneText)
              .font(.system(size: 11, weight: .semibold, design: .rounded))
              .foregroundStyle(palette.mutedInk)
              .multilineTextAlignment(.center)
              .accessibilityLabel(
                model.displayState.pendingCount > 0
                  ? "\(FoodCountText.changes(model.displayState.pendingCount)) waiting to reach the iPhone"
                  : "Waiting for the latest state from iPhone"
              )
          }

          FoodWatchBlobArtwork(
            counts: model.displayState.counts,
            skin: model.displayState.skin
          )
          .frame(height: layout.blobHeight)
          .accessibilityElement()
          .accessibilityLabel(blobAccessibilityLabel)

          HStack(spacing: 5) {
            ForEach(FoodColor.allCases) { color in
              foodLens(color)
            }
          }

          if model.displayState.hasPhoneState,
            model.displayState.pendingCount > 0
          {
            Text(
              "\(FoodCountText.changes(model.displayState.pendingCount)) waiting"
            )
              .font(.system(size: 11, weight: .medium, design: .rounded))
              .foregroundStyle(palette.mutedInk)
              .multilineTextAlignment(.center)
          }

          if layout.showsInstruction {
            Text("Tap to add · hold to remove")
              .font(.system(size: 10, weight: .medium, design: .rounded))
              .foregroundStyle(palette.mutedInk.opacity(0.86))
              .multilineTextAlignment(.center)
              .accessibilityHidden(true)
          }
        } else {
          VStack(spacing: 7) {
            Image(systemName: "exclamationmark.circle")
              .font(.system(size: 24, weight: .medium, design: .rounded))
              .foregroundStyle(palette.mutedInk)
            Text("Food Blob unavailable")
              .font(.system(size: 13, weight: .semibold, design: .rounded))
              .foregroundStyle(palette.ink)
            Text("Reopen Food Blob here. If needed, open the iPhone app.")
              .font(.system(size: 10, weight: .medium, design: .rounded))
              .foregroundStyle(palette.mutedInk)
              .multilineTextAlignment(.center)
          }
          .frame(maxWidth: .infinity)
          .padding(.vertical, 24)
          .accessibilityElement(children: .combine)
        }
      }
      .padding(.horizontal, 8)
      .padding(.vertical, layout.verticalPadding)
  }

  @ViewBuilder
  private func foodLens(_ color: FoodColor) -> some View {
    let count = model.displayState.counts.count(for: color)
    let lens = VStack(spacing: 3) {
      Circle()
        .fill(palette.color(for: color))
        .frame(width: 13, height: 13)
        .overlay {
          Circle().stroke(Color.white.opacity(0.42), lineWidth: 0.7)
        }
      Text("\(count)")
        .font(.system(size: 13, weight: .bold, design: .rounded))
        .foregroundStyle(palette.ink)
        .contentTransition(.numericText())
      Text(color.displayName)
        .font(.system(size: 10, weight: .medium, design: .rounded))
        .foregroundStyle(palette.mutedInk)
        .minimumScaleFactor(0.75)
    }
    .frame(maxWidth: .infinity, minHeight: 49)
    .padding(.vertical, 3)
    .background(palette.surface, in: RoundedRectangle(cornerRadius: 12))
    .contentShape(RoundedRectangle(cornerRadius: 12))
    .gesture(
      LongPressGesture(minimumDuration: 0.45)
        .exclusively(before: TapGesture())
        .onEnded { result in
          switch result {
          case .first(true):
            model.remove(color)
          case .second:
            model.add(color)
          default:
            break
          }
        }
    )
    .accessibilityElement()
    .accessibilityLabel("\(color.displayName) food, \(count) logged")
    .accessibilityHint(
      count > 0
        ? "Tap to add one. Press and hold to remove one."
        : "Tap to add one."
    )
    .accessibilityAddTraits(.isButton)
    .accessibilityAction(.default) {
      model.add(color)
    }
    .animation(reduceMotion ? nil : .easeOut(duration: 0.16), value: count)

    if count > 0 {
      lens.accessibilityAction(named: "Remove one") {
        model.remove(color)
      }
    } else {
      lens
    }
  }

  private var blobAccessibilityLabel: String {
    let counts = model.displayState.counts
    return
      "Food mix. \(FoodCountText.offerings(counts.total)). "
      + "\(counts.green) green, \(counts.yellow) yellow, \(counts.red) red."
  }

  private var waitingForPhoneText: String {
    model.displayState.pendingCount > 0
      ? "Waiting for iPhone · \(model.displayState.pendingCount)"
      : "Waiting for iPhone"
  }
}
