import CoreGraphics

struct FoodWatchTodayLayoutMetrics: Equatable, Sendable {
  let blobHeight: CGFloat
  let contentSpacing: CGFloat
  let verticalPadding: CGFloat
  let showsInstruction: Bool

  static func resolve(for availableHeight: CGFloat) -> Self {
    if availableHeight < 215 {
      return Self(
        blobHeight: 70,
        contentSpacing: 5,
        verticalPadding: 4,
        showsInstruction: false
      )
    }

    return Self(
      blobHeight: 116,
      contentSpacing: 8,
      verticalPadding: 6,
      showsInstruction: true
    )
  }
}
