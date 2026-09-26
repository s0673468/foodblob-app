import SwiftUI

struct SkinWorldBackground: View {
  let skin: SkinID
  var allowsMotion = true

  var body: some View {
    Group {
      if skin == .skyMeadow {
        SkyMeadowBackground(allowsMotion: allowsMotion)
      } else {
        ShrineBackground()
      }
    }
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }
}

struct SkyMeadowBackground: View {
  var allowsMotion = true

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var cloudTravel = false

  var body: some View {
    GeometryReader { proxy in
      ZStack(alignment: .bottom) {
        LinearGradient(
          stops: [
            .init(color: Color(red: 0.66, green: 0.86, blue: 0.94), location: 0),
            .init(color: Color(red: 0.81, green: 0.91, blue: 0.94), location: 0.26),
            .init(color: Color(red: 0.97, green: 0.89, blue: 0.77), location: 0.58),
            .init(color: Color(red: 0.95, green: 0.80, blue: 0.63), location: 1),
          ],
          startPoint: .top,
          endPoint: .bottom
        )

        RadialGradient(
          colors: [Color(red: 1.00, green: 0.94, blue: 0.78).opacity(0.95), .clear],
          center: UnitPoint(x: 0.24, y: 0.10),
          startRadius: 0,
          endRadius: proxy.size.width * 0.72
        )
        RadialGradient(
          colors: [Color(red: 1.00, green: 0.75, blue: 0.63).opacity(0.50), .clear],
          center: UnitPoint(x: 0.16, y: 0.24),
          startRadius: 0,
          endRadius: proxy.size.width * 0.56
        )

        cloud(width: 120, height: 26, top: 132, opacity: 0.55, duration: 64, proxy: proxy)
        cloud(width: 78, height: 18, top: 198, opacity: 0.40, duration: 96, proxy: proxy)

        Ellipse()
          .fill(Color(red: 0.58, green: 0.75, blue: 0.55))
          .frame(width: proxy.size.width * 0.78, height: 380)
          .offset(x: -proxy.size.width * 0.26, y: 105)

        Ellipse()
          .fill(Color(red: 0.48, green: 0.69, blue: 0.49))
          .frame(width: proxy.size.width * 0.86, height: 460)
          .offset(x: proxy.size.width * 0.28, y: 135)

        LinearGradient(
          colors: [
            Color(red: 0.37, green: 0.60, blue: 0.43),
            Color(red: 0.30, green: 0.52, blue: 0.38),
            Color(red: 0.25, green: 0.45, blue: 0.33),
          ],
          startPoint: .top,
          endPoint: .bottom
        )
        .clipShape(Ellipse())
        .frame(width: proxy.size.width * 2.2, height: 360)
        .offset(y: 165)
      }
      .onAppear {
        guard allowsMotion, !reduceMotion else { return }
        cloudTravel = true
      }
    }
    .ignoresSafeArea()
  }

  private func cloud(
    width: CGFloat,
    height: CGFloat,
    top: CGFloat,
    opacity: Double,
    duration: Double,
    proxy: GeometryProxy
  ) -> some View {
    Capsule()
      .fill(Color.white.opacity(opacity))
      .frame(width: width, height: height)
      .blur(radius: height > 20 ? 8 : 7)
      .position(
        x: reduceMotion || !allowsMotion
          ? proxy.size.width * 0.40 : (cloudTravel ? proxy.size.width + width : -width),
        y: top
      )
      .animation(
        reduceMotion || !allowsMotion
          ? nil : .linear(duration: duration).repeatForever(autoreverses: false),
        value: cloudTravel
      )
  }
}

struct ShrineBackground: View {
  var body: some View {
    GeometryReader { proxy in
      ZStack(alignment: .bottom) {
        LinearGradient(
          stops: [
            .init(color: Color(red: 0.04, green: 0.08, blue: 0.13), location: 0),
            .init(color: Color(red: 0.07, green: 0.16, blue: 0.23), location: 0.55),
            .init(color: Color(red: 0.05, green: 0.09, blue: 0.15), location: 1),
          ],
          startPoint: .top,
          endPoint: .bottom
        )
        RadialGradient(
          colors: [Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.11), .clear],
          center: UnitPoint(x: 0.50, y: 0.36),
          startRadius: 0,
          endRadius: proxy.size.width * 0.42
        )
        star(x: 58, y: 74, opacity: 1)
        star(x: 170, y: 52, opacity: 0.50)
        star(x: 294, y: 90, opacity: 0.40)
        LinearGradient(
          colors: [
            Color(red: 0.06, green: 0.13, blue: 0.19), Color(red: 0.04, green: 0.09, blue: 0.14),
          ],
          startPoint: .top,
          endPoint: .bottom
        )
        .clipShape(Ellipse())
        .frame(width: proxy.size.width * 2.1, height: 320)
        .offset(y: 170)
      }
    }
    .ignoresSafeArea()
  }

  private func star(x: CGFloat, y: CGFloat, opacity: Double) -> some View {
    Circle()
      .fill(Color(red: 0.87, green: 0.96, blue: 0.95).opacity(opacity))
      .frame(width: 2, height: 2)
      .position(x: x, y: y)
  }
}
