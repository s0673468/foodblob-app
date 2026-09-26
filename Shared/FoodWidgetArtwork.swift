import SwiftUI
import WidgetKit

enum FoodWidgetPresentation: Equatable, Sendable {
  case counter
  case combined
}

enum FoodWidgetControlAction: Equatable {
  case decrease
  case increase
}

struct FoodWidgetControlGeometry {
  let color: FoodColor
  let action: FoodWidgetControlAction
  let rect: CGRect
}

struct FoodWidgetResolvedGeometry {
  let blob: CGRect
  let controls: [FoodWidgetControlGeometry]
}

enum FoodWidgetGeometry {
  static let smallPadding: CGFloat = 8
  static let smallVerticalGap: CGFloat = 4
  static let smallControlGap: CGFloat = 4
  static let smallControlHeight: CGFloat = 44
  static let smallBlobHeight: CGFloat = 90
  static let smallRequiredHeight =
    smallPadding * 2
    + smallBlobHeight
    + smallVerticalGap
    + smallControlHeight

  static let mediumPadding: CGFloat = 12
  static let mediumMaximumBlobWidth: CGFloat = 142
  static let mediumSectionGap: CGFloat = 6
  static let mediumRowGap: CGFloat = 5
  static let mediumRowHeight: CGFloat = 44
  static let mediumControlGap: CGFloat = 8
  static let mediumMinusTouchWidth: CGFloat = 44
  static let mediumMinusVisualSize: CGFloat = 32

  /// Artwork and live App Intent buttons consume these same rectangles. Counts
  /// never enter this calculation, so growth and number changes cannot move a tap.
  static func resolve(size: CGSize, presentation: FoodWidgetPresentation)
    -> FoodWidgetResolvedGeometry
  {
    if presentation == .counter {
      let padding = min(
        smallPadding, max(0, (size.width - 3 * smallControlHeight - 2 * smallControlGap) / 2))
      let gap = smallControlGap
      let controlWidth = (size.width - padding * 2 - gap * 2) / 3
      let controlTop = size.height - padding - smallControlHeight
      return FoodWidgetResolvedGeometry(
        blob: CGRect(
          x: padding, y: padding, width: size.width - padding * 2,
          height: max(0, controlTop - padding - smallVerticalGap)),
        controls: FoodColor.allCases.enumerated().map { index, color in
          FoodWidgetControlGeometry(
            color: color, action: .increase,
            rect: CGRect(
              x: padding + CGFloat(index) * (controlWidth + gap), y: controlTop,
              width: controlWidth, height: smallControlHeight))
        }
      )
    }
    let rowGap = min(mediumRowGap, max(0, (size.height - 3 * mediumRowHeight) / 2))
    let rowsHeight = 3 * mediumRowHeight + 2 * rowGap
    let padding = min(mediumPadding, max(0, (size.height - rowsHeight) / 2))
    let sectionGap = mediumSectionGap
    let blobWidth = min(mediumMaximumBlobWidth, max(100, size.width * 0.34))
    let controlLeft = padding + blobWidth + sectionGap
    let addWidth = size.width - controlLeft - padding - mediumControlGap - mediumMinusTouchWidth
    return FoodWidgetResolvedGeometry(
      blob: CGRect(x: padding, y: padding, width: blobWidth, height: size.height - padding * 2),
      controls: FoodColor.allCases.enumerated().flatMap { index, color in
        let top = (size.height - rowsHeight) / 2 + CGFloat(index) * (mediumRowHeight + rowGap)
        return [
          FoodWidgetControlGeometry(
            color: color, action: .increase,
            rect: CGRect(x: controlLeft, y: top, width: addWidth, height: mediumRowHeight)),
          FoodWidgetControlGeometry(
            color: color, action: .decrease,
            rect: CGRect(
              x: controlLeft + addWidth + mediumControlGap, y: top,
              width: mediumMinusTouchWidth, height: mediumRowHeight)),
        ]
      }
    )
  }
}

/// The same composition is used by setup previews, capture tests and WidgetKit.
/// The widget's serialization-safe transparent App Intent labels use exactly
/// the same control positions as its separately rendered coloured artwork.
struct FoodWidgetLayout<Blob: View, Control: View>: View {
  let presentation: FoodWidgetPresentation
  @ViewBuilder var blob: () -> Blob
  @ViewBuilder var control: (FoodColor, FoodWidgetControlAction) -> Control

  var body: some View {
    GeometryReader { proxy in
      let geometry = FoodWidgetGeometry.resolve(size: proxy.size, presentation: presentation)
      ZStack(alignment: .topLeading) {
        blob()
          .frame(width: geometry.blob.width, height: geometry.blob.height)
          .position(x: geometry.blob.midX, y: geometry.blob.midY)
        ForEach(Array(geometry.controls.enumerated()), id: \.offset) { _, target in
          control(target.color, target.action)
            .frame(width: target.rect.width, height: target.rect.height)
            .position(x: target.rect.midX, y: target.rect.midY)
        }
      }
      .frame(width: proxy.size.width, height: proxy.size.height)
    }
  }
}

extension FoodColor {
  var presentationColor: Color {
    Color(blobColor: blobColor)
  }

  var meadowGradient: LinearGradient {
    LinearGradient(
      colors: meadowGradientStops.map { Color(blobColor: $0) },
      startPoint: .topLeading,
      endPoint: .bottomTrailing
    )
  }
}

struct FoodWidgetCardBackground: View {
  let skin: SkinID
  let presentation: FoodWidgetPresentation

  var body: some View {
    GeometryReader { proxy in
      ZStack {
        if skin == .skyMeadow {
          meadowWorld(in: proxy.size)
        } else {
          shrineWorld(in: proxy.size)
        }
      }
      .frame(width: proxy.size.width, height: proxy.size.height)
      .clipped()
    }
  }

  private func meadowWorld(in size: CGSize) -> some View {
    ZStack {
      LinearGradient(
        stops: [
          .init(color: Color(red: 0.73, green: 0.88, blue: 0.94), location: 0),
          .init(color: Color(red: 0.99, green: 0.92, blue: 0.76), location: 0.65),
          .init(color: Color(red: 0.84, green: 0.85, blue: 0.64), location: 1),
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
      )

      Circle()
        .fill(Color(red: 1.00, green: 0.94, blue: 0.68).opacity(0.46))
        .frame(width: size.height * 0.44, height: size.height * 0.44)
        .blur(radius: 9)
        .position(x: size.width * 0.18, y: size.height * 0.20)

      Ellipse()
        .fill(Color(red: 0.47, green: 0.68, blue: 0.47).opacity(0.82))
        .frame(width: size.width * 0.86, height: size.height * 0.34)
        .position(x: size.width * 0.79, y: size.height * 0.93)

      Ellipse()
        .fill(Color(red: 0.38, green: 0.62, blue: 0.43).opacity(0.78))
        .frame(width: size.width * 0.82, height: size.height * 0.29)
        .position(x: size.width * 0.18, y: size.height * 0.99)

      Ellipse()
        .fill(Color(red: 0.31, green: 0.55, blue: 0.38).opacity(0.70))
        .frame(width: size.width * 1.46, height: size.height * 0.36)
        .position(x: size.width * 0.48, y: size.height * 1.09)
    }
  }

  private func shrineWorld(in size: CGSize) -> some View {
    ZStack {
      RadialGradient(
        colors: [
          Color(red: 0.08, green: 0.28, blue: 0.35),
          Color(red: 0.05, green: 0.17, blue: 0.25),
          Color(red: 0.04, green: 0.11, blue: 0.18),
        ],
        center: presentation == .combined
          ? UnitPoint(x: 0.22, y: 0.46) : UnitPoint(x: 0.50, y: 0.42),
        startRadius: 0,
        endRadius: max(size.width, size.height) * 0.78
      )

      Circle()
        .fill(Color(red: 0.34, green: 0.91, blue: 0.78).opacity(0.10))
        .frame(width: size.height * 0.82, height: size.height * 0.82)
        .blur(radius: 16)
        .position(
          x: presentation == .combined ? size.width * 0.24 : size.width * 0.50,
          y: size.height * 0.46
        )

      shrineStar(size: 3.2, opacity: 0.72)
        .position(x: size.width * 0.16, y: size.height * 0.16)
      shrineStar(size: 2.1, opacity: 0.58)
        .position(x: size.width * 0.72, y: size.height * 0.19)
      shrineStar(size: 2.8, opacity: 0.46)
        .position(x: size.width * 0.88, y: size.height * 0.54)

      LinearGradient(
        colors: [Color.clear, Color(red: 0.03, green: 0.09, blue: 0.15).opacity(0.90)],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: size.height * 0.30)
      .frame(maxHeight: .infinity, alignment: .bottom)

      ContainerRelativeShape()
        .stroke(Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.20), lineWidth: 1)
    }
  }

  private func shrineStar(size: CGFloat, opacity: Double) -> some View {
    Circle()
      .fill(Color(red: 0.78, green: 0.96, blue: 0.93).opacity(opacity))
      .frame(width: size, height: size)
      .shadow(color: Color(red: 0.50, green: 0.91, blue: 0.86).opacity(opacity), radius: 3)
  }
}

struct FoodWidgetArtwork: View {
  let counts: FoodCounts
  let skin: SkinID
  let presentation: FoodWidgetPresentation
  let isAvailable: Bool
  let translucency: Double

  init(
    counts: FoodCounts,
    skin: SkinID,
    presentation: FoodWidgetPresentation,
    isAvailable: Bool = true,
    translucency: Double = 0
  ) {
    self.counts = counts
    self.skin = skin
    self.presentation = presentation
    self.isAvailable = isAvailable
    self.translucency = BlobMaterial.clamped(translucency)
  }

  var body: some View {
    FoodWidgetLayout(presentation: presentation) {
      FoodWidgetLivingBlob(
        counts: counts, skin: skin,
        size: presentation == .counter ? .small : .medium, translucency: translucency)
    } control: { color, action in
      FoodWidgetControlArtwork(
        color: color, skin: skin,
        role: presentation == .counter
          ? .smallAdd
          : (action == .increase
            ? .mediumAdd(count: counts.count(for: color))
            : .mediumRemove(enabled: counts.count(for: color) > 0)))
    }
    .opacity(isAvailable ? 1 : 0.55)
  }
}

enum FoodWidgetBlobSize {
  case small
  case medium

  var dimensions: CGSize {
    switch self {
    case .small: CGSize(width: 92, height: 86)
    case .medium: CGSize(width: 124, height: 118)
    }
  }

  func totalFontSize(total: Int) -> CGFloat {
    self == .small ? 18 : 22
  }

  static func footerInk(skin: SkinID) -> BlobColor {
    skin == .shrine ? BlobColor(red: 1, green: 1, blue: 1)
      : BlobColor(red: 0.12, green: 0.22, blue: 0.16)
  }
}

/// Presentation-only subregions. App Intent geometry never depends on these
/// frames or counts; the total gets a quiet, stable footer below the glass.
struct FoodWidgetBlobComposition {
  let body: CGRect
  let footer: CGRect

  static func growthScale(total: Int, body: CGRect) -> CGFloat {
    let growth = LivingBlobMetrics.growthScale(total: total)
    return min(body.width, body.height) < 60 ? 0.30 + 0.70 * growth : growth
  }

  static func resolve(available: CGSize, size: FoodWidgetBlobSize) -> Self {
    let footerHeight = min(available.height, size.totalFontSize(total: 0) + 4)
    let bodyHeight = max(0, available.height - footerHeight - 2)
    return Self(
      body: CGRect(x: 0, y: 0, width: available.width, height: bodyHeight),
      footer: CGRect(x: 0, y: available.height - footerHeight,
        width: available.width, height: footerHeight))
  }
}

struct FoodWidgetLivingBlob: View {
  let counts: FoodCounts
  let skin: SkinID
  let size: FoodWidgetBlobSize
  var translucency: Double = 0

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    GeometryReader { proxy in
      let composition = FoodWidgetBlobComposition.resolve(available: proxy.size, size: size)
      let fit = min(1,
        min(composition.body.width / size.dimensions.width,
          composition.body.height / size.dimensions.height) / LivingBlobMetrics.maximumGrowthScale)
      let dimensions = CGSize(width: size.dimensions.width * fit, height: size.dimensions.height * fit)
      let growth = FoodWidgetBlobComposition.growthScale(total: counts.total, body: composition.body)
      ZStack(alignment: .topLeading) {
        ZStack {
          Ellipse()
            .fill(skin == .skyMeadow
              ? Color(red: 0.11, green: 0.22, blue: 0.13).opacity(0.28)
              : Color.black.opacity(0.38))
            .frame(width: dimensions.width * growth * 0.68,
              height: dimensions.height * growth * 0.12)
            .blur(radius: dimensions.width * growth * 0.045)
            .offset(y: dimensions.height * growth * 0.34)

          if let mixed = BlobColor.mixed(counts: counts) {
            Ellipse()
              .fill(Color(blobColor: mixed).opacity(
                (skin == .shrine ? 0.24 : 0.14) * (0.3 + 0.7 * BlobMaterial.clamped(translucency))))
              .frame(width: dimensions.width * growth * 0.78,
                height: dimensions.height * growth * 0.18)
              .blur(radius: dimensions.width * growth * 0.065)
              .offset(x: dimensions.width * growth * 0.07,
                y: dimensions.height * growth * 0.35)
          }

          FoodWidgetPuddle(counts: counts, skin: skin, growth: growth, translucency: translucency)
            .frame(width: dimensions.width, height: dimensions.height)
        }
        .offset(y: (composition.body.height - dimensions.height * growth) * 0.34)
        .frame(width: composition.body.width, height: composition.body.height)
        .clipped()
        .position(x: composition.body.midX, y: composition.body.midY)

        Text("\(counts.total)")
          .font(.system(size: size.totalFontSize(total: counts.total), weight: .bold, design: .rounded))
          .foregroundStyle(Color(blobColor: FoodWidgetBlobSize.footerInk(skin: skin)))
          .lineLimit(1)
          .minimumScaleFactor(0.35)
          .contentTransition(
            FoodWidgetMotionPolicy.allowsAnimatedTransitions(reduceMotion: reduceMotion)
              ? .numericText(value: Double(counts.total)) : .identity
          )
          .invalidatableContent()
          .frame(width: composition.footer.width, height: composition.footer.height)
          .position(x: composition.footer.midX, y: composition.footer.midY)
      }
      .frame(width: proxy.size.width, height: proxy.size.height)
      .clipped()
      .animation(
        FoodWidgetMotionPolicy.allowsAnimatedTransitions(reduceMotion: reduceMotion)
          ? .smooth(duration: 0.36) : nil,
        value: counts
      )
    }
  }
}

enum FoodWidgetControlArtworkRole: Equatable {
  case smallAdd
  case mediumAdd(count: Int)
  case mediumRemove(enabled: Bool)
}

struct FoodWidgetControlArtwork: View {
  let color: FoodColor
  let skin: SkinID
  let role: FoodWidgetControlArtworkRole

  @ViewBuilder
  var body: some View {
    switch role {
    case .smallAdd:
      FoodWidgetSmallAddPill(color: color, skin: skin)
    case .mediumAdd(let count):
      FoodWidgetMediumAddArt(color: color, count: count, skin: skin)
    case .mediumRemove(let enabled):
      FoodWidgetMediumMinusArt(enabled: enabled, skin: skin)
    }
  }
}

enum FoodWidgetMotionPolicy {
  static func allowsAnimatedTransitions(reduceMotion: Bool) -> Bool {
    !reduceMotion
  }
}

private struct FoodWidgetSmallAddPill: View {
  let color: FoodColor
  let skin: SkinID

  var body: some View {
    Image(systemName: "plus")
      .font(.system(size: 18, weight: .heavy, design: .rounded))
      .foregroundStyle(foreground)
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .background(background, in: Capsule())
      .overlay { FoodWidgetLensLight() }
      .shadow(
        color: skin == .skyMeadow
          ? Color.black.opacity(0.10) : color.presentationColor.opacity(0.26),
        radius: skin == .skyMeadow ? 3 : 5,
        y: 2
      )
      .padding(.vertical, 4)
  }

  private var foreground: Color {
    skin == .shrine
      ? Color.white.opacity(0.96)
      : Color(red: 0.10, green: 0.12, blue: 0.10)
  }

  private var background: AnyShapeStyle {
    skin == .skyMeadow
      ? AnyShapeStyle(color.meadowGradient)
      : AnyShapeStyle(
        LinearGradient(
          colors: [color.presentationColor.opacity(0.27), color.presentationColor.opacity(0.11)],
          startPoint: .top,
          endPoint: .bottom
        )
      )
  }

}

private struct FoodWidgetMediumAddArt: View {
  let color: FoodColor
  let count: Int
  let skin: SkinID

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    ZStack {
      Capsule().fill(lensBackground)
      FoodWidgetLensLight()

      Text("\(count)")
        .font(.system(size: 23, weight: .heavy, design: .rounded))
        .foregroundStyle(lensForeground)
        .lineLimit(1)
        .minimumScaleFactor(0.35)
        .padding(.horizontal, 12)
        .contentTransition(
          FoodWidgetMotionPolicy.allowsAnimatedTransitions(reduceMotion: reduceMotion)
            ? .numericText(value: Double(count)) : .identity
        )
        .invalidatableContent()
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .animation(reduceMotion ? nil : .smooth(duration: 0.36), value: count)
  }

  private var lensForeground: Color {
    skin == .shrine
      ? Color.white.opacity(0.96)
      : Color(red: 0.10, green: 0.12, blue: 0.10)
  }

  private var lensBackground: AnyShapeStyle {
    skin == .skyMeadow
      ? AnyShapeStyle(color.meadowGradient)
      : AnyShapeStyle(
        LinearGradient(
          colors: [color.presentationColor.opacity(0.27), color.presentationColor.opacity(0.11)],
          startPoint: .top,
          endPoint: .bottom
        )
      )
  }

}

private struct FoodWidgetLensLight: View {
  var body: some View {
    Capsule().strokeBorder(
      LinearGradient(colors: [Color.white.opacity(0.46), .clear, Color.black.opacity(0.09)],
        startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 0.9)
      .allowsHitTesting(false)
  }
}

private struct FoodWidgetMediumMinusArt: View {
  let enabled: Bool
  let skin: SkinID

  var body: some View {
    Image(systemName: "minus")
      .font(.system(size: 14, weight: .bold))
      .foregroundStyle(
        skin == .skyMeadow
          ? Color(red: 0.42, green: 0.36, blue: 0.26)
          : Color(red: 0.92, green: 0.96, blue: 0.96).opacity(0.72)
      )
      .frame(
        width: FoodWidgetGeometry.mediumMinusVisualSize,
        height: FoodWidgetGeometry.mediumMinusVisualSize
      )
      .background(
        skin == .skyMeadow
          ? Color.white.opacity(0.66)
          : Color(red: 0.50, green: 0.91, blue: 0.86).opacity(0.08),
        in: Circle()
      )
      .opacity(enabled ? 1 : 0.38)
  }
}

/// WidgetKit receives a bounded static image, never an animated shader or timer.
/// The small CPU material is cached by authoritative counts and world (at most 1 MB).
@MainActor
enum FoodWidgetGelSnapshot {
  private static let cache: NSCache<NSString, CGImage> = {
    let cache = NSCache<NSString, CGImage>()
    cache.totalCostLimit = 1_048_576
    cache.countLimit = 4
    return cache
  }()

  static func image(counts: FoodCounts, skin: SkinID, translucency: Double = 0) -> CGImage? {
    let material = BlobMaterial.clamped(translucency)
    let key = "\(counts.green):\(counts.yellow):\(counts.red):\(skin):\(material)" as NSString
    if let existing = cache.object(forKey: key) { return existing }
    let side = 256
    let radii = PuddleShape.restingRadii(counts: counts).map(Double.init)
    let base = BlobColor.mixed(counts: counts)
      ?? BlobColor(red: 0.773, green: 0.898, blue: 0.847)
    let channels = [base.red, base.green, base.blue]
    let shrine = skin == .shrine
    let rimColors = shrine ? [0.66, 0.96, 0.91] : [0.97, 1.0, 0.88]
    let causticColors = zip(channels, [1.0, 0.92, 0.60]).map { pigment, light in pigment * 0.65 + light * 0.35 }
    // Transmission and Fresnel depend on thickness, not the pixel position.
    // Interpolate a tiny per-render table instead of four transcendental calls
    // for every covered pixel. The table is discarded with this render.
    let optics = (0...255).map { step -> SIMD4<Double> in
      let z = Double(step) / 255
      return SIMD4(
        exp(-((1 - channels[0]) * 1.35 + 0.12) * (0.25 + z * 1.25)),
        exp(-((1 - channels[1]) * 1.35 + 0.12) * (0.25 + z * 1.25)),
        exp(-((1 - channels[2]) * 1.35 + 0.12) * (0.25 + z * 1.25)),
        0.025 + 0.34 * pow(1 - z, 2.3))
    }
    var pixels = [UInt8](repeating: 0, count: side * side * 4)
    for y in 0..<side {
      let py = (Double(y) + 0.5) / Double(side) * 100 - 50
      for x in 0..<side {
        let px = (Double(x) + 0.5) / Double(side) * 100 - 50
        let angle = (atan2(py, px) + 2 * .pi).truncatingRemainder(dividingBy: 2 * .pi)
        let index = angle / (2 * .pi) * Double(radii.count)
        let low = Int(index) % radii.count
        let fraction = index - floor(index)
        let boundary = radii[low] * (1 - fraction) + radii[(low + 1) % radii.count] * fraction
        let nx = px / max(boundary, 1)
        let ny = py / max(boundary, 1)
        let distance = sqrt(px * px + py * py)
        let radius = distance / max(boundary, 1)
        let coverage = min(max((1 - radius) * Double(side) * 0.38, 0), 1)
        if coverage <= 0 { continue }
        // Broad, nearly flat interior thickness avoids spokes through lobed forms.
        let slope = (radii[(low + 1) % radii.count] - radii[low]) * Double(radii.count) / (2 * .pi)
        let slopeRatio = slope / max(boundary, 1)
        let inset = max(0, boundary - distance) / sqrt(1 + slopeRatio * slopeRatio)
        let z = sqrt(max(0, 1 - exp(-inset / 10)))
        let diffuse = max(0, -0.40 * nx - 0.55 * ny + 0.73 * z)
        let tablePosition = z * 255
        let tableIndex = min(Int(tablePosition), 254)
        let blend = tablePosition - Double(tableIndex)
        let optical = optics[tableIndex] + (optics[tableIndex + 1] - optics[tableIndex]) * blend
        let fresnel = optical[3]
        // A curved window, a thin lower caustic and a cooler rim give the
        // translucent body readable thickness without painting an opaque shine.
        let windowX = (nx + 0.32 + ny * 0.10) / 0.25
        let windowY = (ny + 0.48 - nx * nx * 0.16) / 0.23
        let windowRadius = windowX * windowX + windowY * windowY
        let window = exp(-windowRadius * windowRadius) * 0.72
        let sheenY = (ny + 0.60 - nx * nx * 0.18) / 0.038
        let sheenX = (nx + 0.25) / 0.30
        let sheen = exp(-sheenY * sheenY - pow(sheenX, 4)) * 0.20
        let causticY = (ny - 0.67 + nx * nx * 0.07) / 0.075
        let causticX = nx / 0.62
        let caustic = exp(-causticY * causticY - causticX * causticX) * 0.26
        let rim = fresnel * (shrine ? 0.97 : 0.64)
        let glassAlpha = min(0.78, 0.31 + z * 0.19 + window * 0.32 + fresnel * 0.50)
        let alpha = material == 0 ? coverage
          : (material == 1 ? coverage * glassAlpha : coverage * (1 + (glassAlpha - 1) * material))
        let offset = (y * side + x) * 4
        for channel in 0..<3 {
          let transmission = optical[channel]
          var value = channels[channel] * (shrine ? 0.39 + 0.44 * diffuse : 0.34 + 0.48 * diffuse)
            + transmission * (shrine ? 0.29 : 0.27)
          value = value * (1 - window) + window
          value += sheen * (1 - value)
          value += rim * rimColors[channel] + caustic * causticColors[channel]
          // Dense wet paint keeps its pigment. The glass endpoint preserves
          // the earlier transmission, Fresnel and caustic material exactly.
          var paint = channels[channel] * (0.56 + 0.48 * diffuse)
          paint += (1 - paint) * window * 0.16
          value = material == 0 ? paint : (material == 1 ? value : paint + (value - paint) * material)
          pixels[offset + channel] = UInt8((min(max(value, 0), 1) * alpha * 255).rounded())
        }
        pixels[offset + 3] = UInt8((alpha * 255).rounded())
      }
    }
    guard let provider = CGDataProvider(data: Data(pixels) as CFData),
      let image = CGImage(width: side, height: side, bitsPerComponent: 8, bitsPerPixel: 32,
        bytesPerRow: side * 4, space: CGColorSpaceCreateDeviceRGB(),
        bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
        provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    else { return nil }
    cache.setObject(image, forKey: key, cost: side * side * 4)
    return image
  }
}

private struct FoodWidgetPuddle: View {
  let counts: FoodCounts
  let skin: SkinID
  let growth: CGFloat
  let translucency: Double

  var body: some View {
    if let image = FoodWidgetGelSnapshot.image(counts: counts, skin: skin, translucency: translucency) {
      Image(decorative: image, scale: 2, orientation: .up)
        .resizable()
        .interpolation(.high)
        .scaleEffect(growth)
    }
  }
}
