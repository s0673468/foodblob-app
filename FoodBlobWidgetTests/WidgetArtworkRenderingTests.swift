import SwiftUI
import UIKit
import XCTest

@testable import FoodBlob

@MainActor
final class WidgetArtworkRenderingTests: XCTestCase {
  func testFooterTotalRemainsReadableAndStableWhileTheBlobGrows() {
    for size in [FoodWidgetBlobSize.small, .medium] {
      XCTAssertGreaterThanOrEqual(size.totalFontSize(total: 2), 16)
      XCTAssertLessThanOrEqual(size.totalFontSize(total: 10), 24)
      XCTAssertEqual(size.totalFontSize(total: 0), size.totalFontSize(total: 99_999),
        "The footer is a stable reading anchor; the jelly itself expresses growth")
    }
  }

  func testBlobAndFooterKeepSeparateSpaceInsideEveryReservedRegion() {
    for size in [FoodWidgetBlobSize.small, .medium] {
      for available in [CGSize(width: 125, height: 77), CGSize(width: 142, height: 98),
        CGSize(width: 100, height: 141), CGSize(width: 142, height: 178)] {
        let composition = FoodWidgetBlobComposition.resolve(available: available, size: size)
        let canvas = CGRect(origin: .zero, size: available)
        XCTAssertTrue(canvas.contains(composition.body))
        XCTAssertTrue(canvas.contains(composition.footer))
        XCTAssertLessThanOrEqual(composition.body.maxY + 2, composition.footer.minY)
        XCTAssertGreaterThanOrEqual(composition.footer.height, size.totalFontSize(total: 99_999))
      }
    }
  }

  func testCrampedPortraitKeepsAnInvitingSeedAndDistinctBoundedGrowth() {
    let body = CGRect(x: 0, y: 0, width: 45, height: 26)
    let zero = FoodWidgetBlobComposition.growthScale(total: 0, body: body)
    let one = FoodWidgetBlobComposition.growthScale(total: 1, body: body)
    let two = FoodWidgetBlobComposition.growthScale(total: 2, body: body)
    let ten = FoodWidgetBlobComposition.growthScale(total: 10, body: body)
    XCTAssertGreaterThanOrEqual(zero, 0.5)
    XCTAssertGreaterThan(one, zero)
    XCTAssertGreaterThan(two, one)
    XCTAssertGreaterThan(ten, two * 1.3)
    XCTAssertLessThanOrEqual(FoodWidgetBlobComposition.growthScale(total: 99_999, body: body),
      LivingBlobMetrics.maximumGrowthScale)
  }

  func testFooterInkContrastsWithTheActualWorldBelowTheGlass() throws {
    func linear(_ value: Double) -> Double {
      value <= 0.04045 ? value / 12.92 : pow((value + 0.055) / 1.055, 2.4)
    }
    func luminance(_ rgb: [Double]) -> Double {
      0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2])
    }
    for skin in SkinID.allCases {
      for presentation in [FoodWidgetPresentation.counter, .combined] {
        let size = CGSize(width: presentation == .counter ? 141 : 292, height: 141)
        let geometry = FoodWidgetGeometry.resolve(size: size, presentation: presentation)
        let composition = FoodWidgetBlobComposition.resolve(available: geometry.blob.size,
          size: presentation == .counter ? .small : .medium)
        let renderer = ImageRenderer(content: FoodWidgetCardBackground(skin: skin, presentation: presentation)
          .frame(width: size.width, height: size.height))
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.uiImage?.cgImage)
        var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
        let context = try XCTUnwrap(CGContext(data: &pixels, width: image.width, height: image.height,
          bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        let x = Int(geometry.blob.minX + composition.footer.midX)
        let y = Int(geometry.blob.minY + composition.footer.midY)
        let offset = (y * image.width + x) * 4
        let background = luminance((0..<3).map { Double(pixels[offset + $0]) / 255 })
        let ink = FoodWidgetBlobSize.footerInk(skin: skin)
        let foreground = luminance([ink.red, ink.green, ink.blue])
        let contrast = (max(background, foreground) + 0.05) / (min(background, foreground) + 0.05)
        XCTAssertGreaterThanOrEqual(contrast, 4.5, "Footer in \(skin) / \(presentation)")
      }
    }
  }

  func testPaintIsTheDefaultOpaqueRichMaterialAndGlassRemainsOptional() throws {
    for skin in SkinID.allCases {
      for counts in [FoodCounts(green: 8), FoodCounts(yellow: 8), FoodCounts(red: 8)] {
        let paint = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: skin))
        let half = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: skin, translucency: 0.5))
        let glass = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: skin, translucency: 1))
        let paintBytes = try XCTUnwrap(paint.dataProvider?.data) as Data
        let halfBytes = try XCTUnwrap(half.dataProvider?.data) as Data
        let glassBytes = try XCTUnwrap(glass.dataProvider?.data) as Data
        XCTAssertEqual(paintBytes[3], 0, "Only the silhouette should paint the canvas")
        for (x, y) in [(128, 128), (105, 130), (148, 130)] {
          let offset = (y * 256 + x) * 4
          XCTAssertEqual(paintBytes[offset + 3], 255, "Default paint must not show its backdrop")
          XCTAssertGreaterThan(halfBytes[offset + 3], glassBytes[offset + 3])
          XCTAssertLessThan(halfBytes[offset + 3], paintBytes[offset + 3])
          let midpoint = (Double(paintBytes[offset + 3]) + Double(glassBytes[offset + 3])) / 2
          XCTAssertEqual(Double(halfBytes[offset + 3]), midpoint, accuracy: 1)
        }
        let center = (128 * 256 + 128) * 4
        let rgb = (0..<3).map { Double(paintBytes[center + $0]) / 255 }
        XCTAssertGreaterThan((rgb.max() ?? 0) - (rgb.min() ?? 0), 0.46,
          "Opaque pigment should retain the food colour instead of a milky glass fill")
      }
    }
  }

  func testMaterialIdentitySeparatesCacheEntriesAndClampsInvalidValues() throws {
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    let paint = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine))
    let glass = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine, translucency: 1))
    let half = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine, translucency: 0.5))
    let repeated = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine))
    XCTAssertTrue(paint === repeated, "The bounded cache should reuse the exact material")
    XCTAssertFalse(paint === glass)
    XCTAssertFalse(half === glass)
    XCTAssertNotEqual(paint.dataProvider?.data as Data?, glass.dataProvider?.data as Data?)
    for invalid in [-1.0, Double.nan, Double.infinity] {
      let clamped = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine,
        translucency: invalid))
      XCTAssertEqual(paint.dataProvider?.data as Data?, clamped.dataProvider?.data as Data?)
    }
  }

  func testTranslucencyChangesOnlyTheReservedBlobArtwork() throws {
    for skin in SkinID.allCases {
      for presentation in [FoodWidgetPresentation.counter, .combined] {
        let size = CGSize(width: presentation == .counter ? 158 : 338, height: 158)
        let reserved = FoodWidgetGeometry.resolve(size: size, presentation: presentation).blob
        var images = [CGImage]()
        for translucency in [0.0, 1.0] {
          let view = ZStack {
            FoodWidgetCardBackground(skin: skin, presentation: presentation)
            FoodWidgetArtwork(counts: FoodCounts(green: 4, yellow: 2, red: 1), skin: skin,
              presentation: presentation, translucency: translucency)
          }.frame(width: size.width, height: size.height)
          let renderer = ImageRenderer(content: view)
          renderer.scale = 1
          images.append(try XCTUnwrap(renderer.uiImage?.cgImage))
        }
        let before = rgbaBytes(images[0])
        let after = rgbaBytes(images[1])
        XCTAssertNotEqual(before, after)
        for y in 0..<images[0].height {
          for x in 0..<images[0].width where !reserved.insetBy(dx: -1, dy: -1).contains(CGPoint(x: x, y: y)) {
            let offset = (y * images[0].width + x) * 4
            XCTAssertEqual(before[offset..<(offset + 4)], after[offset..<(offset + 4)],
              "Changing material must not move a control or change its ink")
          }
        }
      }
    }
  }

  private func rgbaBytes(_ image: CGImage) -> [UInt8] {
    var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
    let context = CGContext(data: &pixels, width: image.width, height: image.height,
      bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    return pixels
  }

  func testWidgetGelSnapshotIsBoundedTransparentAndDeterministic() throws {
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    let image = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .skyMeadow, translucency: 1))
    XCTAssertEqual(image.width, 256)
    XCTAssertEqual(image.height, 256)
    let bytes = try XCTUnwrap(image.dataProvider?.data) as Data
    XCTAssertEqual(bytes[3], 0)
    let centerAlpha = bytes[(128 * 256 + 128) * 4 + 3]
    XCTAssertGreaterThan(centerAlpha, 120)
    XCTAssertLessThanOrEqual(centerAlpha, 140, "The resting core must transmit nearly half of its world")
    let repeatImage = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .skyMeadow, translucency: 1))
    XCTAssertEqual(bytes, repeatImage.dataProvider?.data as Data?)
    let shrine = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine, translucency: 1))
    XCTAssertNotEqual(bytes, shrine.dataProvider?.data as Data?)
  }

  func testShrineCoreReceivesFillLightWithoutLosingItsTransparency() throws {
    let counts = FoodCounts(green: 4, yellow: 2, red: 1)
    let day = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .skyMeadow, translucency: 1))
    let night = try XCTUnwrap(FoodWidgetGelSnapshot.image(counts: counts, skin: .shrine, translucency: 1))
    let dayBytes = try XCTUnwrap(day.dataProvider?.data) as Data
    let nightBytes = try XCTUnwrap(night.dataProvider?.data) as Data
    let center = (128 * 256 + 128) * 4
    XCTAssertEqual(dayBytes[center + 3], nightBytes[center + 3])
    let dayBrightness = (0..<3).reduce(0) { $0 + Int(dayBytes[center + $1]) }
    let nightBrightness = (0..<3).reduce(0) { $0 + Int(nightBytes[center + $1]) }
    XCTAssertGreaterThan(nightBrightness, dayBrightness + 5,
      "Pigment needs its own fill light against the dark world, not extra opacity")
  }


  func testWindowReflectionPreservesAGlossySurfaceOverTheTranslucentCore() throws {
    let image = try XCTUnwrap(FoodWidgetGelSnapshot.image(
      counts: FoodCounts(green: 4, yellow: 2, red: 1), skin: .skyMeadow, translucency: 1))
    let bytes = try XCTUnwrap(image.dataProvider?.data) as Data
    func pixel(_ x: Int, _ y: Int) -> (brightness: Double, alpha: Int) {
      let offset = (y * 256 + x) * 4
      let alpha = Int(bytes[offset + 3])
      let sum = Double(Int(bytes[offset]) + Int(bytes[offset + 1]) + Int(bytes[offset + 2]))
      return (alpha > 0 ? sum * 255 / Double(alpha) : 0, alpha)
    }
    let core = pixel(128, 128)
    var reflection = (brightness: 0.0, alpha: 0)
    for y in 48..<119 {
      for x in 62..<126 {
        let candidate = pixel(x, y)
        if candidate.alpha > 120 && candidate.brightness > reflection.brightness {
          reflection = candidate
        }
      }
    }
    XCTAssertGreaterThan(reflection.brightness, core.brightness + 90)
    XCTAssertLessThan(reflection.brightness, 735, "The soft window must retain tint, not clip white")
    XCTAssertGreaterThan(reflection.alpha, core.alpha + 35,
      "A light reflection stays readable while the surrounding body transmits the world")
  }


  /// Run with SIMCTL_CHILD_FOODBLOB_WIDGET_CAPTURE_DIR to export the exact shared
  /// SwiftUI widget artwork. These are native renders, not Home Screen captures;
  /// the launcher still owns button execution and transition timing.
  func testNativeWidgetArtworkAcrossSkinsSizesAndBoundaryCounts() throws {
    let counts: [(String, FoodCounts)] = [
      ("empty", FoodCounts()),
      ("one", FoodCounts(green: 1)),
      ("two", FoodCounts(green: 1, yellow: 1)),
      ("three", FoodCounts(green: 1, yellow: 1, red: 1)),
      ("ten", FoodCounts(green: 6, yellow: 3, red: 1)),
      ("mixed", FoodCounts(green: 4, yellow: 2, red: 1)),
      ("red", FoodCounts(red: 8)),
      ("yellow", FoodCounts(yellow: 8)),
      ("grown", FoodCounts(green: 12, yellow: 4, red: 2)),
      ("twenty-five", FoodCounts(green: 15, yellow: 7, red: 3)),
      ("fifty", FoodCounts(green: 30, yellow: 14, red: 6)),
      ("high", FoodCounts(green: 999, yellow: 100, red: 20)),
      ("five-digits", FoodCounts(green: 99_999)),
    ]
    let sizes: [(String, CGSize, FoodWidgetPresentation)] = [
      ("small", CGSize(width: 158, height: 158), .counter),
      ("medium", CGSize(width: 338, height: 158), .combined),
      ("compact-small", CGSize(width: 141, height: 141), .counter),
      ("compact-medium", CGSize(width: 292, height: 141), .combined),
    ]
    let captureDirectory = ProcessInfo.processInfo.environment["FOODBLOB_WIDGET_CAPTURE_DIR"]
      .map { URL(fileURLWithPath: $0, isDirectory: true) }
    if let captureDirectory {
      try FileManager.default.createDirectory(
        at: captureDirectory, withIntermediateDirectories: true)
    }
    for skin in SkinID.allCases {
      for (sizeName, size, presentation) in sizes {
        for (countName, count) in counts {
          let view = ZStack {
            FoodWidgetCardBackground(skin: skin, presentation: presentation)
            FoodWidgetArtwork(counts: count, skin: skin, presentation: presentation)
          }
          .frame(width: size.width, height: size.height)
          .clipShape(RoundedRectangle(cornerRadius: 22))
          let renderer = ImageRenderer(content: view)
          renderer.scale = 3
          let image = try XCTUnwrap(renderer.uiImage)
          let pixels = try XCTUnwrap(image.cgImage)
          XCTAssertEqual(pixels.width, Int(size.width * 3))
          XCTAssertEqual(pixels.height, Int(size.height * 3))
          let png = try XCTUnwrap(image.pngData())
          XCTAssertGreaterThan(png.count, 1_000)
          let attachment = XCTAttachment(image: image)
          attachment.name = "ios-\(skin.rawValue)-\(sizeName)-\(countName).png"
          attachment.lifetime = .keepAlways
          add(attachment)
          if let captureDirectory {
            try png.write(
              to: captureDirectory.appendingPathComponent(
                "ios-\(skin.rawValue)-\(sizeName)-\(countName).png"))
          }
        }
      }
    }
  }

  func testWatchArtworkKeepsItsFooterLegibleAndFitsComplicationFrames() throws {
    let fixtures = [("empty", FoodCounts()), ("mixed", FoodCounts(green: 4, yellow: 2, red: 1)),
      ("high", FoodCounts(green: 99_999))]
    let frames: [(String, CGSize, Bool)] = [
      ("compact-today", CGSize(width: 170, height: 70), true),
      ("today", CGSize(width: 184, height: 116), true),
      ("circular", CGSize(width: 44, height: 44), false),
      ("rectangular-portrait", CGSize(width: 38, height: 50), false),
    ]
    for skin in SkinID.allCases {
      for (name, frame, showsCount) in frames {
        for (countName, count) in fixtures {
          let view = FoodWatchBlobArtwork(counts: count, skin: skin, showsCount: showsCount)
            .frame(width: frame.width, height: frame.height)
            .background(FoodWatchPalette.forSkin(skin).background)
          let renderer = ImageRenderer(content: view)
          renderer.scale = 3
          let image = try XCTUnwrap(renderer.uiImage)
          XCTAssertEqual(image.cgImage?.width, Int(frame.width * 3))
          XCTAssertEqual(image.cgImage?.height, Int(frame.height * 3))
          XCTAssertGreaterThan(try XCTUnwrap(image.pngData()).count, 400)
          if skin == .shrine && name == "today" && countName == "mixed" {
            let pixelsImage = try XCTUnwrap(image.cgImage)
            var pixels = [UInt8](repeating: 0, count: pixelsImage.width * pixelsImage.height * 4)
            let context = try XCTUnwrap(CGContext(data: &pixels, width: pixelsImage.width,
              height: pixelsImage.height, bitsPerComponent: 8, bytesPerRow: pixelsImage.width * 4,
              space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            context.draw(pixelsImage, in: CGRect(x: 0, y: 0,
              width: pixelsImage.width, height: pixelsImage.height))
            // The paint reflection must survive its mask at Watch size. Exclude
            // the footer so bright numeral ink cannot satisfy this assertion.
            let bodyPixels = Int(Double(pixelsImage.height) * 0.70) * pixelsImage.width
            var brightest = 0
            for index in 0..<bodyPixels {
              let offset = index * 4
              let red = Int(pixels[offset])
              let green = Int(pixels[offset + 1])
              let blue = Int(pixels[offset + 2])
              brightest = max(brightest, red + green + blue)
            }
            XCTAssertGreaterThan(brightest, 420, "A masked-away reflection makes the Watch paint look flat")
          }
          let attachment = XCTAttachment(image: image)
          attachment.name = "watch-\(skin.rawValue)-\(name)-\(countName).png"
          attachment.lifetime = .keepAlways
          add(attachment)
        }
      }
    }
  }

  func testWatchPaintCoreDoesNotTransmitTheBackground() throws {
    var renders = [[UInt8]]()
    for backdrop in [Color.black, .white] {
      let renderer = ImageRenderer(content:
        FoodWatchBlobArtwork(counts: FoodCounts(green: 4, yellow: 2, red: 1),
          skin: .shrine, showsCount: false)
          .frame(width: 96, height: 96)
          .background(backdrop))
      renderer.scale = 1
      renders.append(rgbaBytes(try XCTUnwrap(renderer.uiImage?.cgImage)))
    }
    let center = (54 * 96 + 48) * 4
    XCTAssertEqual(renders[0][center..<(center + 3)], renders[1][center..<(center + 3)],
      "Watch uses dense paint by default, without a new appearance synchronization channel")
  }

  func testNativeWidgetMaterialsAcrossWorldsSizesAndCountExtremes() throws {
    let fixtures = [("empty", FoodCounts()), ("mixed", FoodCounts(green: 4, yellow: 2, red: 1)),
      ("high", FoodCounts(green: 99_999))]
    for skin in SkinID.allCases {
      for presentation in [FoodWidgetPresentation.counter, .combined] {
        let size = CGSize(width: presentation == .counter ? 158 : 338, height: 158)
        for (label, counts) in fixtures {
          for (materialName, translucency) in [("paint", 0.0), ("half", 0.5), ("glass", 1.0)] {
            let view = ZStack {
              FoodWidgetCardBackground(skin: skin, presentation: presentation)
              FoodWidgetArtwork(counts: counts, skin: skin, presentation: presentation,
                translucency: translucency)
            }
            .frame(width: size.width, height: size.height)
            .clipShape(RoundedRectangle(cornerRadius: 22))
            let renderer = ImageRenderer(content: view)
            renderer.scale = 3
            let image = try XCTUnwrap(renderer.uiImage)
            let attachment = XCTAttachment(image: image)
            attachment.name = "ios-\(skin.rawValue)-\(presentation)-\(label)-\(materialName).png"
            attachment.lifetime = .keepAlways
            add(attachment)
          }
        }
      }
    }
  }
}
