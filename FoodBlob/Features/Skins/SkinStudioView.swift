import SwiftUI

struct SkinStudioView: View {
  @Environment(FoodStore.self) private var store
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  private var design: SkinDesign { store.selectedSkin.design }
  private var previewCounts: FoodCounts { store.visibleCounts }

  var body: some View {
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin)

      ScrollView {
        VStack(spacing: 16) {
          Text("Your blob, in a different light")
            .font(.subheadline)
            .foregroundStyle(design.palette.mutedInk)
            .frame(maxWidth: .infinity, alignment: .leading)
          ForEach(SkinID.allCases) { skin in
            skinCard(skin)
          }

          VStack(alignment: .leading, spacing: 8) {
            Text("The blob stays yours")
              .font(.headline)
            Text(
              "Changing the world changes the light, lenses, and atmosphere. Your counts, colour mix, history, and widget data stay exactly the same."
            )
            .font(.subheadline)
            .foregroundStyle(design.palette.mutedInk)
          }
          .foregroundStyle(design.palette.ink)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(18)
          .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 22))
        }
        .padding(18)
      }
      .scrollIndicators(.hidden)
    }
    .sensoryFeedback(.selection, trigger: store.selectedSkin)
    .navigationTitle("Skins")
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
  }

  private func skinCard(_ skin: SkinID) -> some View {
    let skinDesign = skin.design
    let selected = store.selectedSkin == skin
    return Button {
      if reduceMotion {
        _ = store.setSkin(skin)
      } else {
        withAnimation(.spring(response: 0.42, dampingFraction: 0.78)) {
          _ = store.setSkin(skin)
        }
      }
    } label: {
      ZStack {
        SkinWorldBackground(skin: skin, allowsMotion: false)
        VStack(spacing: 12) {
          HStack {
            VStack(alignment: .leading, spacing: 3) {
              Label(skinDesign.name, systemImage: skinDesign.symbol)
                .font(.title3.bold())
              Text(skinDesign.tagline)
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(skinDesign.palette.mutedInk)
            }
            Spacer()
            Image(systemName: selected ? "checkmark.circle.fill" : "circle")
              .font(.title2)
              .foregroundStyle(
                selected ? skinDesign.palette.controlAccent : skinDesign.palette.mutedInk
              )
          }
          .foregroundStyle(skinDesign.palette.ink)

          if dynamicTypeSize.isAccessibilitySize || previewCounts.total > 999 {
            VStack(spacing: 12) {
              blobPreview(skin: skin)
              countSummary(design: skinDesign, selected: selected)
            }
          } else {
            HStack(spacing: 22) {
              blobPreview(skin: skin)
              countSummary(design: skinDesign, selected: selected)
            }
          }
        }
        .padding(18)
      }
      .frame(minHeight: 218)
      .contentShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
      .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
      .overlay {
        RoundedRectangle(cornerRadius: 30, style: .continuous)
          .stroke(
            selected ? skinDesign.palette.controlAccent.opacity(0.90) : skinDesign.palette.outline,
            lineWidth: selected ? 1.5 : 0.5
          )
      }
    }
    .buttonStyle(LiquidButtonStyle(tint: skinDesign.palette.green, kind: .card, cornerRadius: 30))
    .accessibilityIdentifier("skin-\(skin.rawValue)")
    .accessibilityLabel("\(skinDesign.name). \(skinDesign.tagline). \(FoodCountText.offerings(previewCounts.total))")
    .accessibilityValue(selected ? "Selected" : "Not selected")
    .accessibilityAddTraits(selected ? .isSelected : [])
  }

  private func blobPreview(skin: SkinID) -> some View {
    FoodBlobView(counts: previewCounts, skin: skin, showsTotal: false,
      allowsIdleMotion: false)
      .frame(width: 130, height: 124)
      .allowsHitTesting(false)
      .accessibilityHidden(true)
  }

  private func countSummary(design: SkinDesign, selected: Bool) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Text(FoodCountText.offerings(previewCounts.total))
        .font(.subheadline.weight(.semibold))
        .fixedSize(horizontal: false, vertical: true)
      HStack(spacing: 12) {
        ForEach(FoodColor.allCases) { color in
          VStack(spacing: 4) {
            Circle().fill(color.presentationColor).frame(width: 12, height: 12)
            Text("\(previewCounts.count(for: color))")
              .font(.caption.monospacedDigit())
              .lineLimit(1)
              .minimumScaleFactor(0.60)
          }
          .frame(maxWidth: .infinity)
        }
      }
      Text(selected ? "Your current world" : "Tap to try this light")
        .font(.caption)
        .fixedSize(horizontal: false, vertical: true)
    }
    .foregroundStyle(design.palette.ink)
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}
