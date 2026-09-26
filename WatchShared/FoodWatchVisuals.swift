import SwiftUI

#if canImport(FoodBlob)
  @testable import FoodBlob
#endif

struct FoodWatchPalette {
  let background: Color
  let surface: Color
  let raised: Color
  let ink: Color
  let mutedInk: Color
  let outline: Color
  let accent: Color

  static func forSkin(_ skin: SkinID) -> FoodWatchPalette {
    switch skin {
    case .skyMeadow:
      FoodWatchPalette(
        background: Color(red: 0.66, green: 0.86, blue: 0.94),
        surface: Color(red: 1.00, green: 0.98, blue: 0.93).opacity(0.76),
        raised: Color(red: 0.99, green: 0.96, blue: 0.91),
        ink: Color(red: 0.23, green: 0.19, blue: 0.15),
        mutedInk: Color(red: 0.54, green: 0.47, blue: 0.36),
        outline: Color(red: 0.79, green: 0.69, blue: 0.53),
        accent: Color(red: 0.37, green: 0.60, blue: 0.43)
      )
    case .shrine:
      FoodWatchPalette(
        background: Color(red: 0.04, green: 0.08, blue: 0.13),
        surface: Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.09),
        raised: Color(red: 0.06, green: 0.14, blue: 0.20),
        ink: Color(red: 0.92, green: 0.96, blue: 0.96),
        mutedInk: Color(red: 0.92, green: 0.96, blue: 0.96).opacity(0.52),
        outline: Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.24),
        accent: Color(red: 0.50, green: 0.91, blue: 0.86)
      )
    }
  }

  func color(for foodColor: FoodColor) -> Color {
    switch foodColor {
    case .green: Color(blobColor: .foodGreen)
    case .yellow: Color(blobColor: .foodYellow)
    case .red: Color(blobColor: .foodRed)
    }
  }
}

struct FoodWatchBlobArtwork: View {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  let counts: FoodCounts
  let skin: SkinID
  var showsCount = true

  private var palette: FoodWatchPalette { .forSkin(skin) }

  var body: some View {
    GeometryReader { proxy in
      let footerHeight: CGFloat = showsCount ? min(26, max(20, proxy.size.height * 0.23)) : 0
      let bodyHeight = max(0, proxy.size.height - footerHeight)
      let diameter = min(proxy.size.width, bodyHeight)
      // Complications need an inviting minimum silhouette, while still showing
      // every growth step. Only the paint body scales, never the reading size.
      let growth = 0.26 + 0.62 * LivingBlobMetrics.growthScale(total: counts.total)
      ZStack(alignment: .topLeading) {
        gel(diameter: diameter, growth: growth)
          .frame(width: diameter, height: diameter)
          .position(x: proxy.size.width / 2,
            y: bodyHeight * 0.84 - diameter * growth * 0.34)

        if showsCount {
          Text("\(counts.total)")
            .font(.system(size: min(20, footerHeight - 4), weight: .bold, design: .rounded))
            .foregroundStyle(palette.ink)
            .lineLimit(1)
            .minimumScaleFactor(0.45)
            .contentTransition(reduceMotion ? .identity : .numericText())
            .frame(width: proxy.size.width, height: footerHeight)
            .position(x: proxy.size.width / 2, y: bodyHeight + footerHeight / 2)
            .accessibilityHidden(true)
        }
      }
      .frame(width: proxy.size.width, height: proxy.size.height)
      .clipped()
    }
    .padding(4)
    .animation(
      reduceMotion ? nil : .spring(response: 0.30, dampingFraction: 0.78),
      value: counts
    )
  }

  private func gel(diameter: CGFloat, growth: CGFloat) -> some View {
    let mixed = BlobColor.mixed(counts: counts)
      ?? BlobColor(red: 0.773, green: 0.898, blue: 0.847)
    let pigment = Color(blobColor: mixed)
    func paint(_ light: Double) -> Color {
      Color(red: min(mixed.red * light, 1), green: min(mixed.green * light, 1),
        blue: min(mixed.blue * light, 1))
    }
    let shape = PuddleShape(counts: counts,
      tapSeed: LivingBlobMetrics.derivedTapSeed(counts: counts))
    return ZStack {
      Ellipse()
        .fill(Color.black.opacity(skin == .shrine ? 0.28 : 0.18))
        .frame(width: diameter * 0.62, height: diameter * 0.12)
        .blur(radius: diameter * 0.04)
        .offset(y: diameter * 0.34)

      Ellipse()
        .fill(pigment.opacity(skin == .shrine ? 0.07 : 0.04))
        .frame(width: diameter * 0.75, height: diameter * 0.14)
        .blur(radius: diameter * 0.06)
        .offset(x: diameter * 0.06, y: diameter * 0.35)

      shape
        .fill(RadialGradient(
          colors: [paint(1.03), paint(0.91), paint(0.67)],
          center: UnitPoint(x: 0.28, y: 0.22), startRadius: 0,
          endRadius: max(diameter * 0.78, 1)))
        .overlay {
          shape.stroke(LinearGradient(
            colors: [Color.white.opacity(0.13), .clear, pigment.opacity(0.24)],
            startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.8)
        }
        .overlay {
          ZStack {
            Ellipse()
              .fill(LinearGradient(colors: [Color.white.opacity(0.22), Color.white.opacity(0.015)],
                startPoint: .topLeading, endPoint: .bottomTrailing))
              .frame(width: diameter * 0.22, height: diameter * 0.13)
              .rotationEffect(.degrees(-32))
              .blur(radius: diameter * 0.012)
              .offset(x: -diameter * 0.16, y: -diameter * 0.22)

            Ellipse()
              .fill(paint(0.98).opacity(0.22))
              .frame(width: diameter * 0.48, height: diameter * 0.18)
              .blur(radius: diameter * 0.065)
              .offset(y: diameter * 0.25)
          }
          .frame(width: diameter, height: diameter)
          .clipShape(shape)
        }
    }
    .scaleEffect(growth)
  }
}
