import SwiftUI

/// A friendly face for first-touch and marketing surfaces only.
/// The logging blob deliberately remains faceless.
struct MascotBlobView: View {
  let counts: FoodCounts

  init(counts: FoodCounts = FoodCounts(green: 5, yellow: 2, red: 1)) {
    self.counts = counts
  }

  var body: some View {
    GeometryReader { proxy in
      let size = min(proxy.size.width, proxy.size.height)
      let shape = PuddleShape(
        counts: counts,
        tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts)
      )

      ZStack {
        shape
          .fill(Color(blobColor: BlobColor.mixed(counts: counts) ?? .empty))
        shape
          .fill(
            RadialGradient(
              colors: [Color.white.opacity(0.58), .clear],
              center: UnitPoint(x: 0.28, y: 0.18),
              startRadius: 0,
              endRadius: size * 0.46
            )
          )
          .blendMode(.screen)
        shape
          .fill(
            RadialGradient(
              colors: [Color.black.opacity(0.16), .clear],
              center: UnitPoint(x: 0.78, y: 0.88),
              startRadius: 0,
              endRadius: size * 0.48
            )
          )
          .blendMode(.multiply)

        HStack(spacing: size * 0.18) {
          mascotEye(size: size)
          mascotEye(size: size)
        }
        .offset(y: -size * 0.10)

        Capsule()
          .fill(Color(red: 0.16, green: 0.12, blue: 0.09))
          .frame(width: size * 0.20, height: size * 0.115)
          .overlay(alignment: .bottom) {
            Capsule()
              .fill(Color(red: 0.94, green: 0.48, blue: 0.43))
              .frame(width: size * 0.105, height: size * 0.045)
              .offset(y: -size * 0.012)
          }
          .clipShape(
            UnevenRoundedRectangle(
              topLeadingRadius: size * 0.025,
              bottomLeadingRadius: size * 0.10,
              bottomTrailingRadius: size * 0.10,
              topTrailingRadius: size * 0.025
            )
          )
          .offset(y: size * 0.12)

        HStack(spacing: size * 0.40) {
          cheek(size: size)
          cheek(size: size)
        }
        .offset(y: size * 0.09)
      }
      .rotationEffect(.degrees(-3))
      .shadow(color: Color(red: 0.20, green: 0.30, blue: 0.18).opacity(0.22), radius: 12, y: 8)
    }
    .aspectRatio(1.06, contentMode: .fit)
    .accessibilityHidden(true)
  }

  private func mascotEye(size: CGFloat) -> some View {
    Capsule()
      .fill(Color(red: 0.16, green: 0.12, blue: 0.09))
      .frame(width: size * 0.105, height: size * 0.15)
      .overlay(alignment: .topLeading) {
        Circle()
          .fill(Color.white.opacity(0.96))
          .frame(width: size * 0.043, height: size * 0.043)
          .padding(size * 0.021)
      }
  }

  private func cheek(size: CGFloat) -> some View {
    Capsule()
      .fill(Color(red: 1.00, green: 0.47, blue: 0.43).opacity(0.36))
      .frame(width: size * 0.14, height: size * 0.06)
      .blur(radius: size * 0.015)
  }
}
