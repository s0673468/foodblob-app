import SwiftUI

struct WidgetSetupView: View {
  @Environment(FoodStore.self) private var store

  private var design: SkinDesign { store.selectedSkin.design }
  private var todayCounts: FoodCounts { store.counts(on: Date()) }

  var body: some View {
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin)

      ScrollView {
        VStack(alignment: .leading, spacing: 22) {
          VStack(alignment: .leading, spacing: 8) {
            Text("Choose either world")
              .font(.title2.bold())
            Text(
              "Sky Meadow and Shrine are separate choices in the widget gallery. Your widget can use either world, regardless of the skin selected inside Food Blob."
            )
            .font(.subheadline)
            .foregroundStyle(design.palette.mutedInk)
          }
          .foregroundStyle(design.palette.ink)

          ForEach(SkinID.allCases) { skin in
            widgetCard(
              title: skin == .skyMeadow ? "Sky Meadow widget" : "Shrine widget",
              detail:
                "Choose the small add-only widget or the medium widget with add and minus controls.",
              skin: skin
            )
          }

          VStack(alignment: .leading, spacing: 16) {
            setupStep(1, "Touch and hold your Home Screen", "Wait for the apps to jiggle.")
            setupStep(2, "Tap Edit, then Add Widget", "Search for “Food Blob”.")
            setupStep(
              3,
              "Pick Sky Meadow or Shrine",
              "Then swipe between the small and medium sizes."
            )
          }
          .padding(18)
          .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 24))

          VStack(alignment: .leading, spacing: 10) {
            Label("Using the counters", systemImage: "hand.tap.fill")
              .font(.headline)
            Text("On small widgets, tap a coloured plus to add. On medium widgets, tap a colour lens to add or its round minus chip to remove one.")
            Text(
              "Your blob grows and changes colour after each tap. Open the app whenever you want to stretch and play with it."
            )
            .font(.footnote)
            .foregroundStyle(design.palette.mutedInk)
          }
          .foregroundStyle(design.palette.ink)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(18)
          .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 24))
        }
        .padding(18)
      }
      .scrollIndicators(.hidden)
    }
    .navigationTitle("Add widget")
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
  }

  private func widgetCard(
    title: String,
    detail: String,
    skin: SkinID
  ) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Text(title).font(.headline)
      Text(detail)
        .font(.caption)
        .foregroundStyle(design.palette.mutedInk)
      ViewThatFits(in: .horizontal) {
        HStack(spacing: 12) {
          widgetPreviews(skin: skin)
        }
        VStack(spacing: 12) {
          widgetPreviews(skin: skin)
        }
      }
    }
    .foregroundStyle(design.palette.ink)
    .padding(12)
    .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 28))
  }

  @ViewBuilder
  private func widgetPreviews(skin: SkinID) -> some View {
    FoodWidgetScaledPreview(counts: todayCounts, skin: skin, presentation: .counter)
      .frame(width: 112, height: 112)
    FoodWidgetScaledPreview(counts: todayCounts, skin: skin, presentation: .combined)
      .frame(maxWidth: .infinity)
      .frame(height: 112)
  }

  private func setupStep(_ number: Int, _ title: String, _ detail: String) -> some View {
    HStack(alignment: .top, spacing: 14) {
      Text("\(number)")
        .font(.headline)
        .foregroundStyle(
          store.selectedSkin == .shrine ? design.palette.background : design.palette.raised
        )
        .frame(width: 34, height: 34)
        .background(design.palette.ink, in: Circle())
      VStack(alignment: .leading, spacing: 3) {
        Text(title).font(.headline)
        Text(detail)
          .font(.subheadline)
          .foregroundStyle(design.palette.mutedInk)
      }
    }
    .foregroundStyle(design.palette.ink)
    .accessibilityElement(children: .combine)
    .accessibilityLabel("Step \(number). \(title). \(detail)")
  }
}

struct FoodWidgetScaledPreview: View {
  @AppStorage(BlobMaterial.preferenceKey, store: UserDefaults(suiteName: BlobMaterial.appGroup))
  private var translucency = BlobMaterial.defaultTranslucency

  let counts: FoodCounts
  let skin: SkinID
  let presentation: FoodWidgetPresentation

  private var canvasSize: CGSize {
    switch presentation {
    case .combined:
      CGSize(width: 364, height: 170)
    case .counter:
      CGSize(width: 172, height: 172)
    }
  }

  var body: some View {
    GeometryReader { geometry in
      let scale = min(
        geometry.size.width / canvasSize.width,
        geometry.size.height / canvasSize.height
      )

      ZStack {
        FoodWidgetCardBackground(skin: skin, presentation: presentation)
        FoodWidgetArtwork(
          counts: counts,
          skin: skin,
          presentation: presentation,
          translucency: translucency
        )
      }
      .containerShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
      .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
      .frame(width: canvasSize.width, height: canvasSize.height)
      .scaleEffect(scale)
      .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
    }
    .aspectRatio(canvasSize.width / canvasSize.height, contentMode: .fit)
    .frame(
      maxHeight: presentation == .combined ? 170 : 220
    )
  }
}
