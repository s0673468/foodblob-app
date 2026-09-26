import SwiftUI

struct SkinPalette: Equatable {
  let background: Color
  let surface: Color
  let raised: Color
  let ink: Color
  let mutedInk: Color
  let green: Color
  let yellow: Color
  let red: Color
  let outline: Color
  let ground: Color
  let accent: Color
  let controlAccent: Color
  let gold: Color
}

struct SkinDesign: Identifiable {
  let id: SkinID
  let name: String
  let tagline: String
  let symbol: String
  let palette: SkinPalette
  let fontDesign: Font.Design
  let cornerRadius: CGFloat
  let springResponse: Double
  let springDamping: Double
}

extension SkinID {
  var design: SkinDesign {
    switch self {
    case .skyMeadow:
      SkinDesign(
        id: .skyMeadow,
        name: "Sky Meadow",
        tagline: "A quiet field at first light",
        symbol: "leaf.fill",
        palette: SkinPalette(
          background: Color(red: 0.66, green: 0.86, blue: 0.94),
          surface: Color(red: 1.00, green: 0.98, blue: 0.93).opacity(0.72),
          raised: Color(red: 0.99, green: 0.96, blue: 0.91),
          ink: Color(red: 0.23, green: 0.19, blue: 0.15),
          mutedInk: Color(red: 0.43, green: 0.37, blue: 0.27),
          green: Color(blobColor: .foodGreen),
          yellow: Color(blobColor: .foodYellow),
          red: Color(blobColor: .foodRed),
          outline: Color(red: 0.79, green: 0.69, blue: 0.53),
          ground: Color(red: 0.37, green: 0.60, blue: 0.43),
          accent: Color(red: 0.99, green: 0.96, blue: 0.91),
          controlAccent: Color(red: 0.10, green: 0.32, blue: 0.22),
          gold: Color(red: 0.91, green: 0.79, blue: 0.47)
        ),
        fontDesign: .rounded,
        cornerRadius: 24,
        springResponse: 0.50,
        springDamping: 0.70
      )
    case .shrine:
      SkinDesign(
        id: .shrine,
        name: "Shrine",
        tagline: "A small ritual at night",
        symbol: "moon.stars.fill",
        palette: SkinPalette(
          background: Color(red: 0.04, green: 0.08, blue: 0.13),
          surface: Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.06),
          raised: Color(red: 0.06, green: 0.14, blue: 0.20),
          ink: Color(red: 0.92, green: 0.96, blue: 0.96),
          mutedInk: Color(red: 0.92, green: 0.96, blue: 0.96).opacity(0.70),
          green: Color(blobColor: .foodGreen),
          yellow: Color(blobColor: .foodYellow),
          red: Color(blobColor: .foodRed),
          outline: Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.18),
          ground: Color(red: 0.06, green: 0.13, blue: 0.19),
          accent: Color(red: 0.50, green: 0.91, blue: 0.86),
          controlAccent: Color(red: 0.50, green: 0.91, blue: 0.86),
          gold: Color(red: 0.91, green: 0.79, blue: 0.47)
        ),
        fontDesign: .rounded,
        cornerRadius: 20,
        springResponse: 0.52,
        springDamping: 0.72
      )
    }
  }
}

extension SkinPalette {
  func color(for foodColor: FoodColor) -> Color {
    switch foodColor {
    case .green: green
    case .yellow: yellow
    case .red: red
    }
  }
}
