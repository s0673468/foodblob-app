import SwiftUI

/// Bounded presentation uniforms; no food state or mutation lives in the renderer.
enum JellySurfaceGeometry {
  static let contactDuration: TimeInterval = 1.15

  // Predict the flat optical core behind the label, including real alpha over
  // the world. Raw pigment luminance misses the darker translucent Shrine red.
  // Constants mirror the settled centre of jellySurface in JellySurface.metal.
  static func settledCoreColor(color: BlobColor, skin: SkinID,
    translucency: Double = BlobMaterial.defaultTranslucency) -> BlobColor {
    let material = BlobMaterial.clamped(translucency)
    let reflection = exp(-pow(0.44 / 0.38, 2) - pow(0.47 / 0.43, 2))
    let diffuse = 0.72 / sqrt(0.48 * 0.48 + 0.66 * 0.66 + 0.72 * 0.72)
    let alpha = 1 + ((0.60 + reflection * 0.09) - 1) * material
    let backdrop = skin == .shrine
      ? BlobColor(red: 0.08, green: 0.16, blue: 0.22)
      : BlobColor(red: 0.90, green: 0.89, blue: 0.82)
    let rim = skin == .shrine
      ? BlobColor(red: 0.43, green: 0.93, blue: 0.88)
      : BlobColor(red: 0.70, green: 0.92, blue: 1.0)
    func channel(_ pigment: Double, absorptionTint: Double, rim: Double,
      window: Double, background: Double) -> Double {
      let absorption = exp(-((1 - pigment) * 1.45 + 0.08))
      let diffuseColour = pigment * (0.37 + 0.38 * diffuse)
        + absorption * absorptionTint + 0.025 * rim + (skin == .shrine ? pigment * 0.10 : 0)
      let reflected = diffuseColour * (1 - reflection * 0.44) + window * reflection * 0.44
      let paint = pigment * (0.62 + 0.38 * diffuse + (skin == .shrine ? 0.025 : 0))
        + 0.025 * 0.05 * rim
      let painted = paint * (1 - reflection * 0.22) + window * reflection * 0.22
      let surface = painted + (reflected - painted) * material
      return min(max(surface, 0), 1) * alpha + background * (1 - alpha)
    }
    return BlobColor(
      red: channel(color.red, absorptionTint: 0.25, rim: rim.red, window: 0.97, background: backdrop.red),
      green: channel(color.green, absorptionTint: 0.28, rim: rim.green, window: 1, background: backdrop.green),
      blue: channel(color.blue, absorptionTint: 0.26, rim: rim.blue, window: 1, background: backdrop.blue))
  }

  static func linearLuminance(_ color: BlobColor) -> Double {
    func linear(_ channel: Double) -> Double {
      channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
  }

  static func labelUsesLightInk(color: BlobColor, skin: SkinID,
    translucency: Double = BlobMaterial.defaultTranslucency) -> Bool {
    let luminance = linearLuminance(settledCoreColor(color: color, skin: skin, translucency: translucency))
    return 1.05 / (luminance + 0.05) > (luminance + 0.05) / 0.05
  }

  static func contactsAfterRemoval(_ contacts: [BlobContact], at time: TimeInterval) -> [BlobContact] {
    contacts.filter { $0.started <= time && $0.color == nil }
  }

  // The normalised outline is immutable between count morphs. Reuse it for
  // resting frames; only a real contact needs the per-angle displacement pass.
  private static let directions: [CGVector] = (0..<48).map { index in
    let angle = CGFloat(index) / 48 * 2 * .pi
    return CGVector(dx: cos(angle), dy: sin(angle))
  }

  static func radii(_ resting: [CGFloat], fields: [PuddleImpactField]) -> [Float] {
    guard !fields.isEmpty else { return resting.map { Float(max(0.08, $0 / 100)) } }
    return resting.enumerated().map { index, radius in
      let direction: CGVector
      if resting.count == directions.count {
        direction = directions[index]
      } else {
        let angle = CGFloat(index) / CGFloat(resting.count) * 2 * .pi
        direction = CGVector(dx: cos(angle), dy: sin(angle))
      }
      let adjustment = fields.reduce(CGFloat.zero) { $0 + $1.radiusAdjustment(for: direction) }
      return Float(max(0.08, (radius + PuddleGeometry.softenedAdjustment(adjustment)) / 100))
    }
  }

  static func lightShift(at time: TimeInterval, allowsMotion: Bool,
    dragPoint: CGPoint?, dragDepth: CGFloat, contacts: [BlobContact]) -> CGSize {
    guard allowsMotion else { return .zero }
    var shift = CGSize.zero
    func accumulate(_ point: CGPoint, _ depth: CGFloat) {
      shift.width += (point.x - 0.5) * depth * 0.24
      shift.height += (point.y - 0.5) * depth * 0.24
    }
    for contact in contacts.suffix(8) {
      accumulate(contact.point, contact.wave(at: time))
    }
    if let dragPoint { accumulate(dragPoint, dragDepth) }
    return CGSize(width: min(max(shift.width, -0.16), 0.16),
      height: min(max(shift.height, -0.16), 0.16))
  }

  // Each event is x, y, age, strength, released depth, R, G, B, has pigment.
  static func events(_ contacts: [BlobContact], at time: TimeInterval) -> [Float] {
    contacts.filter { time >= $0.started && time - $0.started < contactDuration }
      .suffix(8).flatMap { event -> [Float] in
        let color = event.color?.blobColor ?? .empty
        return [Float(event.point.x), Float(event.point.y), Float(time - event.started),
          Float(event.strength), Float(event.initialDepth), Float(color.red),
          Float(color.green), Float(color.blue), event.color == nil ? 0 : 1]
      }
  }
}

struct JellySurface: View {
  let shape: PuddleShape
  let pose: BlobPose
  let fields: [PuddleImpactField]
  let contacts: [BlobContact]
  let time: TimeInterval
  let skin: SkinID
  let dragPoint: CGPoint?
  let dragDepth: CGFloat
  var allowsMotion = true
  var translucency = BlobMaterial.defaultTranslucency

  private var lightShift: CGSize {
    JellySurfaceGeometry.lightShift(at: time, allowsMotion: allowsMotion,
      dragPoint: dragPoint, dragDepth: dragDepth, contacts: contacts)
  }

  var body: some View {
    let shift = lightShift
    return shape.fill(.white)
      .colorEffect(ShaderLibrary.jellySurface(
        .boundingRect,
        .float3(pose.color.red, pose.color.green, pose.color.blue),
        .floatArray(fields.isEmpty ? pose.surfaceRadii : JellySurfaceGeometry.radii(pose.radii, fields: fields)),
        .floatArray(JellySurfaceGeometry.events(contacts, at: time)),
        .float4(dragPoint?.x ?? 0.5, dragPoint?.y ?? 0.5,
          dragPoint == nil ? 0 : dragDepth, pose.fill),
        .float(skin == .shrine ? 1 : 0), .float(JellySurfaceGeometry.contactDuration),
        .float2(shift.width, shift.height), .float(BlobMaterial.clamped(translucency))))
      .allowsHitTesting(false)
      .accessibilityHidden(true)
  }
}
