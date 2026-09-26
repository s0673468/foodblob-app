import SwiftUI
import UIKit

struct FoodCounterControls: View {
  let counts: FoodCounts
  let skin: SkinID
  let onIncrement: (FoodColor) -> Bool
  let onDecrement: (FoodColor) -> Bool
  var compact = false
  var dragScope = ""
  var onDragChanged: (BlobDraggedOffering?) -> Void = { _ in }
  var onDrop: (BlobDraggedOffering) -> Bool = { _ in false }

  var body: some View {
    VStack(spacing: skin == .skyMeadow ? 8 : 10) {
      ForEach(FoodColor.allCases) { color in
        FoodLensRow(
          color: color,
          count: counts.count(for: color),
          skin: skin,
          onIncrement: { onIncrement(color) },
          onDecrement: { onDecrement(color) },
          compact: compact, dragScope: dragScope, onDragChanged: onDragChanged, onDrop: onDrop
        )
      }
    }
  }

}

private struct FoodLensRow: View {
  let color: FoodColor
  let count: Int
  let skin: SkinID
  let onIncrement: () -> Bool
  let onDecrement: () -> Bool
  let compact: Bool
  let dragScope: String
  let onDragChanged: (BlobDraggedOffering?) -> Void
  let onDrop: (BlobDraggedOffering) -> Bool

  var body: some View {
    HStack(spacing: 8) {
      FoodAddLens(
        color: color,
        count: count,
        skin: skin,
        onIncrement: onIncrement,
        onRemove: onDecrement, compact: compact, dragScope: dragScope,
        onDragChanged: onDragChanged, onDrop: onDrop
      )
      FoodMinusButton(
        color: color,
        count: count,
        skin: skin,
        onDecrement: onDecrement
      )
    }
  }
}

private struct FoodAddLens: View {
  let color: FoodColor
  let count: Int
  let skin: SkinID
  let onIncrement: () -> Bool
  let onRemove: () -> Bool
  let compact: Bool
  let dragScope: String
  let onDragChanged: (BlobDraggedOffering?) -> Void
  let onDrop: (BlobDraggedOffering) -> Bool

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @State private var suppressNextTap = false
  @State private var jellyTrigger = 0
  @State private var dragging = false
  @State private var startedScope: String?
  @GestureState private var dragIsActive = false

  var body: some View {
    let motionDisabled = reduceMotion
    Button {
      guard !suppressNextTap else {
        suppressNextTap = false
        return
      }
      if onIncrement() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
      }
    } label: {
      tileFace
        .frame(maxWidth: .infinity)
        .frame(minHeight: dynamicTypeSize.isAccessibilitySize ? 68 : compact ? 48 : 56)
        .contentShape(Capsule())
    }
    .buttonStyle(
      LiquidButtonStyle(tint: color.presentationColor, kind: .row, cornerRadius: 100)
    )
    .shadow(color: color.presentationColor.opacity(skin == .shrine ? 0.12 : 0.16), radius: 5, y: 3)
    .simultaneousGesture(
      LongPressGesture(minimumDuration: 0.45)
        .sequenced(before: DragGesture(minimumDistance: 0, coordinateSpace: .named("food-interaction")))
        .updating($dragIsActive) { value, active, _ in
          if case .second(true, _) = value { active = true }
        }
        .onChanged { value in
          guard case .second(true, let drag) = value else { return }
          suppressNextTap = true
          if startedScope == nil { startedScope = dragScope }
          guard let drag, drag.translation.height < -18, startedScope == dragScope else { return }
          dragging = true
          onDragChanged(BlobDraggedOffering(color: color, point: drag.location,
            translation: drag.translation, scope: startedScope ?? dragScope))
        }
        .onEnded { value in
          if case .second(true, let drag?) = value, dragging {
            let offering = BlobDraggedOffering(color: color, point: drag.location,
              translation: drag.translation, scope: startedScope ?? dragScope)
            if onDrop(offering) { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
          }
          finishDrag()
        }
    )
    .onChange(of: dragIsActive) { _, active in
      if !active {
        DispatchQueue.main.async { if !dragIsActive { finishDrag() } }
      }
    }
    .onChange(of: dragScope) { _, _ in finishDrag() }
    .onDisappear { finishDrag() }
    .keyframeAnimator(
      initialValue: TileJellyValues(),
      trigger: jellyTrigger
    ) { content, value in
      content
        .overlay {
          if !motionDisabled {
            GeometryReader { geometry in
              Capsule()
                .fill(LinearGradient(colors: [.clear, .white.opacity(0.48), .clear],
                  startPoint: .leading, endPoint: .trailing))
                .frame(width: geometry.size.width * 0.28, height: geometry.size.height)
                .offset(x: geometry.size.width * value.sweep)
                .opacity(value.light)
            }
            .clipShape(Capsule())
            .allowsHitTesting(false)
          }
        }
        .scaleEffect(x: motionDisabled ? 1 : value.x, y: motionDisabled ? 1 : value.y)
    } keyframes: { _ in
      KeyframeTrack(\.x) {
        CubicKeyframe(1.035, duration: 0.10)
        CubicKeyframe(0.98, duration: 0.13)
        CubicKeyframe(1.01, duration: 0.13)
        CubicKeyframe(1.00, duration: 0.10)
      }
      KeyframeTrack(\.y) {
        CubicKeyframe(0.94, duration: 0.10)
        CubicKeyframe(1.025, duration: 0.13)
        CubicKeyframe(0.99, duration: 0.13)
        CubicKeyframe(1.00, duration: 0.10)
      }
      KeyframeTrack(\.sweep) {
        LinearKeyframe(-0.3, duration: 0.001)
        CubicKeyframe(1.1, duration: 0.44)
      }
      KeyframeTrack(\.light) {
        LinearKeyframe(1, duration: 0.08)
        CubicKeyframe(0, duration: 0.36)
      }
    }
    .onChange(of: count) { _, _ in
      if !reduceMotion {
        jellyTrigger += 1
      }
    }
    .blobSceneFrame(.tile(color))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(color.displayName) food, \(count) logged")
    .accessibilityValue("\(count)")
    .accessibilityHint("Double tap to add. Or hold briefly, then drag a colour into the blob. Use the minus button to remove.")
    .accessibilityAction(named: Text("Add one \(color.displayName.lowercased()) food")) {
      if onIncrement() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
      }
    }
    .accessibilityAction(named: Text("Remove one \(color.displayName.lowercased()) food")) {
      guard count > 0 else { return }
      if onRemove() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
      }
    }
  }

  private func finishDrag() {
    dragging = false
    startedScope = nil
    onDragChanged(nil)
    // A Button can receive its release in the same run loop as the gesture.
    // Keep that release consumed, then restore ordinary tap input.
    DispatchQueue.main.async { suppressNextTap = false }
  }

  @ViewBuilder
  private var tileFace: some View {
    if skin == .skyMeadow {
      ZStack(alignment: .topLeading) {
        Capsule()
          .fill(color.meadowGradient)
          .overlay(alignment: .bottom) {
            Capsule()
              .stroke(color.deepShade.opacity(0.20), lineWidth: 6)
              .mask(alignment: .bottom) {
                Rectangle().frame(height: 8)
              }
          }
        Capsule().fill(LinearGradient(colors: [.white.opacity(0.5), .white.opacity(0.04)],
          startPoint: .topLeading, endPoint: .bottomTrailing))
          .frame(height: 8).padding(.horizontal, 20).padding(.top, 5)
          .allowsHitTesting(false)
        tileText
      }
    } else {
      ZStack {
        Capsule()
          .fill(
            LinearGradient(
              colors: [color.presentationColor.opacity(0.23), color.presentationColor.opacity(0.08)],
              startPoint: .top,
              endPoint: .bottom
            )
          )
          .overlay {
            Capsule()
              .stroke(color.presentationColor.opacity(0.60), lineWidth: 1)
          }
        tileText
      }
    }
  }

  private var tileText: some View {
    HStack(spacing: 12) {
      if skin == .shrine {
        Circle()
          .fill(color.presentationColor)
          .frame(width: 12, height: 12)
          .shadow(color: color.presentationColor.opacity(0.85), radius: 9)
      }
      Text(color.displayName)
        .font(.system(.subheadline, design: .rounded, weight: .semibold))
        .foregroundStyle(color.tileLabelInk(for: skin))
      Spacer(minLength: 4)
      Text("\(count)")
        .font(.system(.title2, design: .rounded, weight: .bold))
        .contentTransition(.numericText())
        .foregroundStyle(color.tileInk(for: skin))
        .minimumScaleFactor(0.60)
    }
    .padding(.horizontal, 18)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
  }
}

private struct FoodMinusButton: View {
  let color: FoodColor
  let count: Int
  let skin: SkinID
  let onDecrement: () -> Bool

  var body: some View {
    Button {
      guard count > 0 else { return }
      if onDecrement() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
      }
    } label: {
      Image(systemName: "minus")
        .font(.system(size: 15, weight: .semibold))
        .frame(width: 32, height: 32)
        .foregroundStyle(
          skin == .skyMeadow
            ? Color(red: 0.48, green: 0.40, blue: 0.29)
            : Color(red: 0.92, green: 0.96, blue: 0.96).opacity(0.60)
        )
        .background(
          skin == .skyMeadow
            ? Color(red: 1.00, green: 0.98, blue: 0.93).opacity(0.55)
            : Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.06),
          in: Circle()
        )
        .overlay {
          Circle()
            .stroke(
              skin == .skyMeadow
                ? Color(red: 0.79, green: 0.69, blue: 0.53).opacity(0.70)
                : Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.18),
              lineWidth: skin == .skyMeadow ? 1.5 : 1
            )
        }
        .frame(width: 44, height: 44)
        .contentShape(Rectangle())
    }
    .buttonStyle(LiquidButtonStyle(tint: color.presentationColor, cornerRadius: 100))
    .disabled(count == 0)
    .opacity(count == 0 ? 0.42 : 1)
    .accessibilityLabel("Remove one \(color.displayName.lowercased()) food")
  }
}

private struct TileJellyValues {
  var x: CGFloat = 1
  var y: CGFloat = 1
  var sweep: CGFloat = -0.3
  var light: Double = 0
}

extension FoodColor {
  fileprivate var deepShade: Color {
    switch self {
    case .green: Color(red: 0.05, green: 0.29, blue: 0.18)
    case .yellow: Color(red: 0.51, green: 0.37, blue: 0.03)
    case .red: Color(red: 0.47, green: 0.09, blue: 0.09)
    }
  }

  fileprivate func tileInk(for skin: SkinID) -> Color {
    if skin == .shrine { return Color(red: 0.92, green: 0.96, blue: 0.96) }
    return Color(red: 0.10, green: 0.12, blue: 0.10)
  }

  fileprivate func tileLabelInk(for skin: SkinID) -> Color {
    if skin == .shrine { return Color(red: 0.92, green: 0.96, blue: 0.96).opacity(0.82) }
    return Color(red: 0.10, green: 0.12, blue: 0.10)
  }
}
