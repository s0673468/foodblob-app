import AVFoundation

/// A short original falling tone, synthesized locally. Ambient playback respects
/// the iPhone's silent switch and never interrupts another app's audio.
@MainActor
final class BlobPlopSound {
  static let shared = BlobPlopSound()
  private var players: [AVAudioPlayer] = []
  private var pending: [UUID: Task<Void, Never>] = [:]
  private lazy var wave = Self.makeWave()

  func schedule(after delay: TimeInterval) {
    let id = UUID()
    pending[id] = Task { @MainActor in
      try? await Task.sleep(for: .seconds(delay))
      guard !Task.isCancelled else { return }
      pending.removeValue(forKey: id)
      play()
    }
  }

  func play() {
    guard UserDefaults.standard.bool(forKey: "foodBlobInteractionSounds") else { return }
    do {
      try AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default)
      players.removeAll { !$0.isPlaying }
      let player = try AVAudioPlayer(data: wave)
      player.volume = 0.3
      players.append(player)
      player.play()
    } catch {
      // Sound is optional presentation. Logging has already succeeded.
    }
  }

  func cancel() {
    pending.values.forEach { $0.cancel() }
    pending.removeAll()
    players.forEach { $0.stop() }
    players.removeAll()
  }

  private static func makeWave() -> Data {
    let rate = 22050
    let count = Int(Double(rate) * 0.115)
    var data = Data()
    func text(_ value: String) { data.append(contentsOf: value.utf8) }
    func word(_ value: UInt16) {
      data.append(UInt8(value & 0xff)); data.append(UInt8(value >> 8))
    }
    func long(_ value: UInt32) {
      word(UInt16(value & 0xffff)); word(UInt16(value >> 16))
    }
    text("RIFF"); long(UInt32(36 + count * 2)); text("WAVEfmt ")
    long(16); word(1); word(1); long(UInt32(rate)); long(UInt32(rate * 2))
    word(2); word(16); text("data"); long(UInt32(count * 2))
    var phase = 0.0
    for index in 0..<count {
      let time = Double(index) / Double(rate)
      let progress = Double(index) / Double(count)
      phase += 2 * .pi * (155 + 520 * exp(-time * 36)) / Double(rate)
      let envelope = sin(min(progress * 9, 1) * .pi / 2) * pow(1 - progress, 3)
      let sample = (sin(phase) + sin(phase * 2.03) * 0.16) * envelope * 14500
      word(UInt16(bitPattern: Int16(sample)))
    }
    return data
  }
}
