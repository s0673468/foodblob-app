import SwiftUI

extension Color {
  init(blobColor: BlobColor, opacity: Double = 1) {
    self.init(
      .sRGB,
      red: blobColor.red,
      green: blobColor.green,
      blue: blobColor.blue,
      opacity: opacity
    )
  }
}
