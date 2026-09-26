import SwiftUI

/// A shared material response for native buttons. Layout and semantic actions
/// belong to the original control; only its visible face compresses and relaxes.
enum LiquidControlKind: CaseIterable {
  case compact, row, card, quiet
}

struct LiquidControlPose: Equatable {
  var x: CGFloat
  var y: CGFloat
  var lift: CGFloat
  static let rest = Self(x: 1, y: 1, lift: 0)
}

enum LiquidControlMotion {
  static func pose(kind: LiquidControlKind, pressed: Bool, reduceMotion: Bool) -> LiquidControlPose {
    guard pressed, !reduceMotion else { return .rest }
    switch kind {
    case .compact: return LiquidControlPose(x: 0.97, y: 0.94, lift: 1.5)
    case .row: return LiquidControlPose(x: 0.988, y: 0.965, lift: 1)
    case .card: return LiquidControlPose(x: 0.988, y: 0.98, lift: 2)
    case .quiet: return LiquidControlPose(x: 0.998, y: 0.992, lift: 0)
    }
  }

  static func releasesLight(wasPressed: Bool, isPressed: Bool, enabled: Bool,
    reduceMotion: Bool) -> Bool {
    wasPressed && !isPressed && enabled && !reduceMotion
  }
}

struct LiquidButtonStyle: ButtonStyle {
  var tint: Color = .accentColor
  var kind: LiquidControlKind = .compact
  var cornerRadius: CGFloat = 22

  func makeBody(configuration: Configuration) -> some View {
    LiquidButtonFace(label: configuration.label, isPressed: configuration.isPressed,
      tint: tint, kind: kind, cornerRadius: cornerRadius)
  }
}

private struct LiquidButtonFace<Label: View>: View {
  let label: Label
  let isPressed: Bool
  let tint: Color
  let kind: LiquidControlKind
  let cornerRadius: CGFloat
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.isEnabled) private var enabled
  @State private var release = 0

  var body: some View {
    let held = isPressed && enabled
    let motionDisabled = reduceMotion
    let pose = LiquidControlMotion.pose(kind: kind, pressed: held, reduceMotion: reduceMotion)
    let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
    return label
      .overlay {
        shape.fill(tint.opacity(held ? 0.12 : 0))
          .overlay {
            shape.strokeBorder(.white.opacity(held ? 0.23 : 0), lineWidth: 1)
          }
          .allowsHitTesting(false)
          .accessibilityHidden(true)
      }
      .brightness(held ? -0.035 : 0)
      .keyframeAnimator(initialValue: LiquidReleaseValues(), trigger: release) { content, value in
        content.overlay {
          if !motionDisabled && kind != .quiet {
            GeometryReader { geometry in
              Ellipse()
                .strokeBorder(
                  LinearGradient(colors: [.white.opacity(0.75), tint.opacity(0.25), .clear],
                    startPoint: .topLeading, endPoint: .bottomTrailing),
                  lineWidth: 5
                )
                .frame(width: geometry.size.width * (0.25 + value.spread * 1.9),
                  height: geometry.size.height * (0.45 + value.spread * 2))
                .position(x: geometry.size.width * 0.5, y: geometry.size.height * 0.65)
                .opacity(value.light)
            }
            .clipShape(shape)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
          }
        }
      } keyframes: { _ in
        KeyframeTrack(\.spread) {
          LinearKeyframe(0, duration: 0.001)
          CubicKeyframe(1, duration: 0.40)
        }
        KeyframeTrack(\.light) {
          LinearKeyframe(0.48, duration: 0.05)
          CubicKeyframe(0, duration: 0.35)
        }
      }
      .scaleEffect(x: pose.x, y: pose.y)
      .offset(y: pose.lift)
      .animation(reduceMotion ? nil : .spring(response: held ? 0.16 : 0.32,
        dampingFraction: held ? 0.9 : 0.7), value: held)
      .onChange(of: isPressed) { previous, next in
        if LiquidControlMotion.releasesLight(wasPressed: previous, isPressed: next,
          enabled: enabled, reduceMotion: reduceMotion) {
          release += 1
        }
      }
  }
}

private struct LiquidReleaseValues {
  var spread: CGFloat = 0
  var light: Double = 0
}
