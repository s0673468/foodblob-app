import SwiftUI
import UIKit
import WidgetKit

struct SettingsView: View {
  let onShowWelcome: () -> Void

  @Environment(FoodStore.self) private var store
  @State private var confirmsDeletion = false
  @State private var exportItem: FoodBlobExportItem?
  @AppStorage("foodBlobInteractionSounds") private var interactionSounds = false
  @AppStorage(BlobMaterial.preferenceKey, store: UserDefaults(suiteName: BlobMaterial.appGroup))
  private var translucency = BlobMaterial.defaultTranslucency
  @State private var lastReloadedMaterial = BlobMaterial.read()

  private var design: SkinDesign { store.selectedSkin.design }

  var body: some View {
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin)

      ScrollView {
        VStack(spacing: 24) {
          if let error = store.lastError {
            Label(error, systemImage: "exclamationmark.triangle.fill")
              .font(.footnote.weight(.semibold))
              .foregroundStyle(design.palette.ink)
              .frame(maxWidth: .infinity, alignment: .leading)
              .padding(14)
              .background(
                design.palette.yellow.opacity(0.28),
                in: RoundedRectangle(cornerRadius: 16)
              )
              .accessibilityLabel("Food Blob error. \(error)")
          }

          settingsSection("Appearance") {
            settingsCard {
              materialControls
            }
          }

          settingsSection("Food Blob") {
            settingsCard {
              NavigationLink {
                WidgetSetupView()
              } label: {
                SettingsRow(
                  icon: "square.grid.2x2.fill",
                  title: "Add the Home Screen widget",
                  subtitle: "Choose a world and size",
                  disclosure: true
                )
              }
              settingsDivider
              NavigationLink {
                PrivacyView()
              } label: {
                SettingsRow(
                  icon: "hand.raised.fill",
                  title: "Privacy",
                  subtitle: "No account or backend",
                  disclosure: true
                )
              }
            }
          }

          settingsSection("Your data") {
            settingsCard {
              Button(action: prepareExport) {
                SettingsRow(
                  icon: "square.and.arrow.up",
                  title: "Export my data",
                  subtitle: "Plain JSON you can keep"
                )
              }
              settingsDivider
              Button(role: .destructive) {
                confirmsDeletion = true
              } label: {
                SettingsRow(
                  icon: "trash.fill",
                  title: "Delete all data",
                  subtitle: "Clears history and today",
                  iconColor: design.palette.red
                )
              }
            }
          }

          settingsSection("Experience") {
            settingsCard {
              Toggle(isOn: $interactionSounds) {
                SettingsRow(icon: "speaker.wave.2.fill", title: "Soft blob sounds",
                  subtitle: "A little plop when food lands. Respects silent mode.")
              }
              .padding(.trailing, 16)
              .sensoryFeedback(.selection, trigger: interactionSounds)
              .onChange(of: interactionSounds) { _, enabled in
                if !enabled { BlobPlopSound.shared.cancel() }
              }
              settingsDivider
              Button(action: onShowWelcome) {
                SettingsRow(
                  icon: "sparkles",
                  title: "Show welcome again",
                  subtitle: "Colors and widget basics"
                )
              }
            }
          }

          Text(
            "Food Blob is a simple reflection tool. It does not provide medical or nutritional advice."
          )
          .font(.footnote)
          .foregroundStyle(design.palette.mutedInk)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(18)
          .background(
            design.palette.raised.opacity(store.selectedSkin == .shrine ? 0.82 : 0.88),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
          )
        }
        .padding(18)
        .padding(.bottom, 72)
      }
      .scrollIndicators(.hidden)
      .tint(design.palette.controlAccent)
    }
    .navigationTitle("Settings")
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
    .toolbarColorScheme(store.selectedSkin == .shrine ? .dark : .light, for: .navigationBar)
    .onDisappear { reloadMaterialWidgetsIfNeeded() }
    .alert("Delete all Food Blob data?", isPresented: $confirmsDeletion) {
      Button("Cancel", role: .cancel) {}
      Button("Delete everything", role: .destructive) {
        store.deleteAll()
      }
    } message: {
      Text("This permanently removes every saved day from this iPhone. It cannot be undone.")
    }
    .sheet(item: $exportItem) { item in
      FoodBlobShareSheet(item: item)
        .ignoresSafeArea()
    }
  }

  private var materialControls: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text("Blob material")
        .font(.headline)
        .foregroundStyle(design.palette.ink)
      Text("Rich paint or light, translucent jelly.")
        .font(.subheadline)
        .foregroundStyle(design.palette.mutedInk)
      VStack(spacing: 4) {
        FoodBlobView(counts: store.visibleCounts, skin: store.selectedSkin,
          interactive: true, showsTotal: false, animationScope: "material-preview",
          growthPresentation: .materialPreview)
          .frame(width: 144, height: 126)
          // A material swatch stays easy to inspect even before the first food.
          // It uses the actual day's colour and silhouette, never sample food.
          .accessibilityIdentifier("blob-material-preview")
        Text("Tap to poke")
          .font(.footnote.weight(.medium))
          .foregroundStyle(design.palette.mutedInk)
      }
      .frame(maxWidth: .infinity)
      Slider(value: Binding(
        get: { BlobMaterial.clamped(translucency) },
        set: { translucency = BlobMaterial.clamped($0) }), in: 0...1, step: 0.05,
        onEditingChanged: { editing in
          if !editing { reloadMaterialWidgetsIfNeeded() }
        })
        .accessibilityIdentifier("blob-translucency-slider")
        .accessibilityLabel("Blob translucency")
        .accessibilityValue("\(Int((BlobMaterial.clamped(translucency) * 100).rounded())) percent jelly")
        .accessibilityHint("Paint is opaque. Jelly lets the world show through.")
        .accessibilityAdjustableAction { direction in
          switch direction {
          case .increment: translucency = BlobMaterial.clamped(translucency + 0.05)
          case .decrement: translucency = BlobMaterial.clamped(translucency - 0.05)
          @unknown default: break
          }
          reloadMaterialWidgetsIfNeeded()
        }
      HStack {
        Text("Paint")
        Spacer()
        Text("Jelly")
      }
      .font(.subheadline.weight(.semibold))
      .foregroundStyle(design.palette.ink)
      .accessibilityHidden(true)
    }
    .padding(18)
  }

  private func reloadMaterialWidgetsIfNeeded() {
    let material = BlobMaterial.clamped(translucency)
    guard material != lastReloadedMaterial else { return }
    lastReloadedMaterial = material
    for kind in FoodBlobConstants.activeWidgetKinds {
      WidgetCenter.shared.reloadTimelines(ofKind: kind)
    }
  }

  private func prepareExport() {
    exportItem = FoodBlobExportItem(
      text: String(data: store.exportData(), encoding: .utf8) ?? "{}"
    )
  }

  private func settingsSection<Content: View>(
    _ title: String,
    @ViewBuilder content: () -> Content
  ) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Text(title)
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(design.palette.ink)
        .padding(.horizontal, 4)
      content()
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }

  private func settingsCard<Content: View>(
    @ViewBuilder content: () -> Content
  ) -> some View {
    VStack(spacing: 0) { content() }
      .buttonStyle(LiquidButtonStyle(tint: design.palette.green, kind: .quiet, cornerRadius: 18))
      .foregroundStyle(design.palette.ink)
      .background(
        design.palette.raised.opacity(store.selectedSkin == .shrine ? 0.90 : 0.94),
        in: RoundedRectangle(cornerRadius: 24, style: .continuous)
      )
      .shadow(color: Color.black.opacity(store.selectedSkin == .shrine ? 0.07 : 0.03), radius: 10, y: 4)
  }

  private var settingsDivider: some View {
    Rectangle()
      .fill(design.palette.outline.opacity(0.54))
      .frame(height: 1)
      .padding(.leading, 58)
  }
}

private struct FoodBlobExportItem: Identifiable {
  let id = UUID()
  let text: String
}

private struct FoodBlobShareSheet: UIViewControllerRepresentable {
  let item: FoodBlobExportItem

  func makeUIViewController(
    context: Context
  ) -> UIActivityViewController {
    UIActivityViewController(
      activityItems: [item.text],
      applicationActivities: nil
    )
  }

  func updateUIViewController(
    _ uiViewController: UIActivityViewController,
    context: Context
  ) {}
}

private struct SettingsRow: View {
  let icon: String
  let title: String
  let subtitle: String
  var disclosure = false
  var iconColor: Color? = nil

  @Environment(FoodStore.self) private var store

  private var design: SkinDesign { store.selectedSkin.design }

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: icon)
        .foregroundStyle(iconColor ?? design.palette.controlAccent)
        .frame(width: 28)
      VStack(alignment: .leading, spacing: 2) {
        Text(title)
          .font(.body.weight(.medium))
          .foregroundStyle(design.palette.ink)
        Text(subtitle)
          .font(.caption)
          .foregroundStyle(design.palette.mutedInk)
      }
      Spacer(minLength: 8)
      if disclosure {
        Image(systemName: "chevron.right")
          .font(.caption.bold())
          .foregroundStyle(design.palette.mutedInk)
      }
    }
    .frame(maxWidth: .infinity, minHeight: 58, alignment: .leading)
    .padding(.horizontal, 16)
    .contentShape(Rectangle())
  }
}

private struct PrivacyView: View {
  @Environment(FoodStore.self) private var store

  private var design: SkinDesign { store.selectedSkin.design }

  var body: some View {
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin)

      ScrollView {
        VStack(alignment: .leading, spacing: 18) {
          privacyCard(
            icon: "iphone",
            title: "Kept on your devices",
            detail:
              "History and undo stay on this iPhone. Today's counts and appearance sync to your paired Apple Watch; Watch taps sync back to this iPhone."
          )
          privacyCard(
            icon: "person.crop.circle.badge.xmark",
            title: "No account",
            detail: "Food Blob does not ask for your name, email address, sign-in, or subscription."
          )
          privacyCard(
            icon: "network.slash",
            title: "No tracking or backend",
            detail: "Food Blob has no analytics, ads, or backend. Nothing is sent to a company server."
          )
          privacyCard(
            icon: "square.and.arrow.up",
            title: "You control the copy",
            detail:
              "Export creates plain JSON only when you ask. Delete all data removes the local history."
          )
        }
        .padding(18)
      }
      .scrollIndicators(.hidden)
    }
    .navigationTitle("Privacy")
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
    .toolbarColorScheme(store.selectedSkin == .shrine ? .dark : .light, for: .navigationBar)
  }

  private func privacyCard(icon: String, title: String, detail: String) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Label(title, systemImage: icon)
        .font(.headline)
        .foregroundStyle(design.palette.ink)
      Text(detail)
        .font(.body)
        .foregroundStyle(design.palette.mutedInk)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(18)
    .background(
      design.palette.raised.opacity(store.selectedSkin == .shrine ? 0.90 : 0.94),
      in: RoundedRectangle(cornerRadius: 22)
    )
    .accessibilityElement(children: .combine)
  }
}
