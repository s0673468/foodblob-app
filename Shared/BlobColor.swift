import Foundation

struct BlobColor: Equatable, Sendable {
  let red: Double
  let green: Double
  let blue: Double

  static let foodGreen = BlobColor(red: 0.130, green: 0.820, blue: 0.440)
  static let foodYellow = BlobColor(red: 0.990, green: 0.790, blue: 0.180)
  static let foodRed = BlobColor(red: 0.960, green: 0.300, blue: 0.350)
  static let empty = BlobColor(red: 0.50, green: 0.52, blue: 0.56)

  private static let foodGreenOKLab = linearToOKLab(sRGBToLinear(foodGreen))
  private static let foodYellowOKLab = linearToOKLab(sRGBToLinear(foodYellow))
  private static let foodRedOKLab = linearToOKLab(sRGBToLinear(foodRed))

  var relativeLuminance: Double {
    0.2126 * red + 0.7152 * green + 0.0722 * blue
  }

  /// Mixes food counts proportionally in OKLab, which avoids the lifeless grey
  /// produced by averaging saturated stoplight colors directly in sRGB.
  static func mixed(counts: FoodCounts) -> BlobColor? {
    let total = Double(counts.total)
    guard total > 0 else { return nil }

    let weightedColors = [
      (Double(counts.green) / total, foodGreenOKLab),
      (Double(counts.yellow) / total, foodYellowOKLab),
      (Double(counts.red) / total, foodRedOKLab),
    ]

    var mixed = (lightness: 0.0, a: 0.0, b: 0.0)
    for (weight, lab) in weightedColors where weight > 0 {
      mixed.lightness += lab.lightness * weight
      mixed.a += lab.a * weight
      mixed.b += lab.b * weight
    }
    return linearToSRGB(okLabToLinear(mixed))
  }

  private static func sRGBToLinear(_ color: BlobColor) -> BlobColor {
    func channel(_ value: Double) -> Double {
      value <= 0.04045
        ? value / 12.92
        : pow((value + 0.055) / 1.055, 2.4)
    }
    return BlobColor(
      red: channel(color.red),
      green: channel(color.green),
      blue: channel(color.blue)
    )
  }

  private static func linearToSRGB(
    _ color: (red: Double, green: Double, blue: Double)
  ) -> BlobColor {
    func channel(_ value: Double) -> Double {
      let clamped = min(max(value, 0), 1)
      return clamped <= 0.0031308
        ? clamped * 12.92
        : 1.055 * pow(clamped, 1 / 2.4) - 0.055
    }
    return BlobColor(
      red: channel(color.red),
      green: channel(color.green),
      blue: channel(color.blue)
    )
  }

  private static func linearToOKLab(_ color: BlobColor) -> (
    lightness: Double, a: Double, b: Double
  ) {
    let l =
      0.4122214708 * color.red + 0.5363325363 * color.green
      + 0.0514459929 * color.blue
    let m =
      0.2119034982 * color.red + 0.6806995451 * color.green
      + 0.1073969566 * color.blue
    let s =
      0.0883024619 * color.red + 0.2817188376 * color.green
      + 0.6299787005 * color.blue
    let lRoot = cbrt(l)
    let mRoot = cbrt(m)
    let sRoot = cbrt(s)
    return (
      lightness:
        0.2104542553 * lRoot + 0.7936177850 * mRoot
        - 0.0040720468 * sRoot,
      a:
        1.9779984951 * lRoot - 2.4285922050 * mRoot
        + 0.4505937099 * sRoot,
      b:
        0.0259040371 * lRoot + 0.7827717662 * mRoot
        - 0.8086757660 * sRoot
    )
  }

  private static func okLabToLinear(
    _ color: (lightness: Double, a: Double, b: Double)
  ) -> (red: Double, green: Double, blue: Double) {
    let lRoot =
      color.lightness + 0.3963377774 * color.a + 0.2158037573 * color.b
    let mRoot =
      color.lightness - 0.1055613458 * color.a - 0.0638541728 * color.b
    let sRoot =
      color.lightness - 0.0894841775 * color.a - 1.2914855480 * color.b
    let l = lRoot * lRoot * lRoot
    let m = mRoot * mRoot * mRoot
    let s = sRoot * sRoot * sRoot
    return (
      red: 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
      green: -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
      blue: -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    )
  }
}

extension FoodColor {
  var blobColor: BlobColor {
    switch self {
    case .green: .foodGreen
    case .yellow: .foodYellow
    case .red: .foodRed
    }
  }

  var meadowGradientStops: [BlobColor] {
    switch self {
    case .green:
      [
        BlobColor(red: 0.22, green: 0.86, blue: 0.53),
        BlobColor(red: 0.10, green: 0.69, blue: 0.35),
      ]
    case .yellow:
      [
        BlobColor(red: 1.00, green: 0.87, blue: 0.36),
        BlobColor(red: 0.96, green: 0.72, blue: 0.10),
      ]
    case .red:
      [
        BlobColor(red: 1.00, green: 0.43, blue: 0.47),
        BlobColor(red: 0.89, green: 0.22, blue: 0.29),
      ]
    }
  }
}
