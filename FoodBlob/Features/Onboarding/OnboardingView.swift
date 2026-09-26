import SwiftUI

struct OnboardingView: View {
  let onDone: () -> Void

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var page = 0

  var body: some View {
    let design = SkinID.skyMeadow.design

    ZStack {
      SkinWorldBackground(skin: .skyMeadow)

      VStack(spacing: 18) {
        HStack {
          Spacer()
          if page > 0 {
            Button("Skip", action: onDone)
              .foregroundStyle(design.palette.mutedInk)
              .padding(.horizontal, 8)
              .frame(minHeight: 44)
              .buttonStyle(LiquidButtonStyle(tint: design.palette.green))
          }
        }

        TabView(selection: $page) {
          WelcomePage(design: design)
            .tag(0)
          ColorGuidePage(design: design)
            .tag(1)
          WidgetWelcomePage(design: design)
            .tag(2)
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .animation(reduceMotion ? nil : .spring(response: 0.38), value: page)

        // Keep progress outside the scrolling page so larger text never
        // passes underneath the native page-control dots.
        HStack(spacing: 8) {
          ForEach(0..<3) { index in
            Capsule()
              .fill(design.palette.ink.opacity(page == index ? 0.9 : 0.25))
              .frame(width: page == index ? 16 : 7, height: 7)
          }
        }
        .frame(height: 12)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Page \(page + 1) of 3")
        .accessibilityIdentifier("onboarding-progress")

        Button {
          if page < 2 {
            page += 1
          } else {
            onDone()
          }
        } label: {
          Text(page == 2 ? "Start logging" : "Continue")
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 52)
            .foregroundStyle(design.palette.raised)
            .background(design.palette.ink, in: Capsule())
        }
        .buttonStyle(LiquidButtonStyle(tint: design.palette.green, kind: .row, cornerRadius: 100))
        .accessibilityHint(page == 2 ? "Closes setup" : "Shows the next setup page")
      }
      .padding(22)
      .sensoryFeedback(.selection, trigger: page)
    }
  }
}

private struct WelcomePage: View {
  let design: SkinDesign

  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  var body: some View {
    ScrollView {
      VStack(spacing: 22) {
        let mascotSize = OnboardingLayoutPolicy.mascotSize(
          isAccessibilitySize: dynamicTypeSize.isAccessibilitySize
        )
        MascotBlobView()
          .frame(width: mascotSize.width, height: mascotSize.height)
          .shadow(color: Color.green.opacity(0.20), radius: 22, y: 14)

        VStack(spacing: 10) {
          Text("A tiny food diary")
            .font(.system(.largeTitle, design: design.fontDesign, weight: .bold))
            .foregroundStyle(design.palette.ink)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)

          Text("Log a portion in seconds. No calories, photos, account, or cloud.")
            .font(.body)
            .foregroundStyle(design.palette.mutedInk)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .background(design.palette.raised.opacity(0.86), in: RoundedRectangle(cornerRadius: 24))
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 8)
    }
    .scrollIndicators(.hidden)
  }
}

private struct ColorGuidePage: View {
  let design: SkinDesign

  var body: some View {
    ScrollView {
      VStack(spacing: 22) {
        Text("Your colors, your meaning")
          .font(.system(.largeTitle, design: design.fontDesign, weight: .bold))
          .foregroundStyle(design.palette.ink)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)

        VStack(spacing: 12) {
          guideRow(.green, "A choice you want more often")
          guideRow(.yellow, "Somewhere in the middle")
          guideRow(.red, "A choice you want less often")
        }

        Text("These are reflections, not medical grades. You decide what belongs where.")
          .font(.callout)
          .foregroundStyle(design.palette.mutedInk)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)
          .padding(16)
          .frame(maxWidth: .infinity)
          .background(design.palette.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: 18))
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 8)
    }
    .scrollIndicators(.hidden)
  }

  private func guideRow(_ color: FoodColor, _ text: String) -> some View {
    HStack(spacing: 12) {
      Capsule()
        .fill(color.meadowGradient)
        .frame(width: 78, height: 44)
        .overlay {
          Text(color.displayName)
            .font(.caption.bold())
            .foregroundStyle(Color(red: 0.10, green: 0.12, blue: 0.10))
        }
      Text(text)
        .font(.body.weight(.semibold))
        .foregroundStyle(design.palette.ink)
        .fixedSize(horizontal: false, vertical: true)
      Spacer()
    }
    .padding(10)
    .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 18))
  }
}

private struct WidgetWelcomePage: View {
  let design: SkinDesign

  var body: some View {
    ScrollView {
      VStack(spacing: 22) {
        HStack(spacing: 12) {
          FoodWidgetScaledPreview(
            counts: FoodCounts(green: 5, yellow: 2, red: 1),
            skin: design.id,
            presentation: .counter
          )
          .frame(width: 118, height: 118)

          FoodWidgetScaledPreview(
            counts: FoodCounts(green: 5, yellow: 2, red: 1),
            skin: design.id,
            presentation: .combined
          )
          .frame(maxWidth: .infinity)
          .frame(height: 118)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
          "Examples of the small add widget and medium add and remove widget"
        )

        Text("Made for your Home Screen")
          .font(.system(.largeTitle, design: design.fontDesign, weight: .bold))
          .foregroundStyle(design.palette.ink)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)

        Text(
          "Choose a small or medium widget, then pick Sky Meadow or Shrine in the gallery."
        )
          .font(.body)
          .foregroundStyle(design.palette.mutedInk)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)
          .padding(16)
          .frame(maxWidth: .infinity)
          .background(design.palette.raised.opacity(0.94), in: RoundedRectangle(cornerRadius: 18))

        Label(
          "Tap a lens to add a portion. The minus chip takes one back. The blob keeps up.",
          systemImage: "hand.tap.fill"
        )
        .font(.caption)
        .foregroundStyle(design.palette.mutedInk)
        .fixedSize(horizontal: false, vertical: true)
        .padding(12)
        .background(design.palette.surface, in: RoundedRectangle(cornerRadius: 14))
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 8)
    }
    .scrollIndicators(.hidden)
  }
}

enum OnboardingLayoutPolicy {
  static func mascotSize(isAccessibilitySize: Bool) -> CGSize {
    isAccessibilitySize
      ? CGSize(width: 156, height: 148)
      : CGSize(width: 250, height: 238)
  }
}
