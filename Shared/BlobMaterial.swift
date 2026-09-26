import Foundation

/// A device-local rendering preference, separate from food records and ledgers.
/// Zero is opaque paint; one is the original translucent jelly material.
enum BlobMaterial {
  static let preferenceKey = "foodBlobTranslucency"
  static let appGroup = "group.org.example.foodblob"
  static let defaultTranslucency = 0.0

  static func clamped(_ value: Double) -> Double {
    guard value.isFinite else { return defaultTranslucency }
    return min(max(value, 0), 1)
  }

  static func read(defaults: UserDefaults? = UserDefaults(suiteName: appGroup)) -> Double {
    clamped(defaults?.double(forKey: preferenceKey) ?? defaultTranslucency)
  }
}
