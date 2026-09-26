import SwiftUI

struct FoodBlobRootView: View {
  @Environment(FoodStore.self) private var store
  @AppStorage("has_completed_onboarding") private var hasCompletedOnboarding = false
  @State private var selectedTab: RootTab = .today
  @State private var showsOnboarding = false

  var body: some View {
    TabView(selection: $selectedTab) {
      NavigationStack {
        TodayView()
      }
      .modifier(FoodTabBackdrop(skin: store.selectedSkin))
      .tag(RootTab.today)
      .tabItem {
        Label(
          "Today",
          systemImage: store.selectedSkin == .shrine ? "sun.max.fill" : "leaf.fill"
        )
      }

      NavigationStack {
        HistoryView()
      }
      .modifier(FoodTabBackdrop(skin: store.selectedSkin))
      .tag(RootTab.history)
      .tabItem {
        Label("History", systemImage: "calendar")
      }

      NavigationStack {
        SkinStudioView()
      }
      .modifier(FoodTabBackdrop(skin: store.selectedSkin))
      .tag(RootTab.skins)
      .tabItem {
        Label("Skins", systemImage: "paintpalette.fill")
      }

      NavigationStack {
        SettingsView(onShowWelcome: { showsOnboarding = true })
      }
      .modifier(FoodTabBackdrop(skin: store.selectedSkin))
      .tag(RootTab.settings)
      .tabItem {
        Label("Settings", systemImage: "gearshape.fill")
      }
    }
    // Keep native tab glyphs semantic over both light and dark skin previews.
    .tint(.primary)
    .sensoryFeedback(.selection, trigger: selectedTab)
    .preferredColorScheme(store.selectedSkin == .shrine ? .dark : .light)
    .onOpenURL { url in
      guard let destination = RootTab.destination(for: url) else { return }
      selectedTab = destination
    }
    .sheet(
      isPresented: Binding(
        get: { !hasCompletedOnboarding || showsOnboarding },
        set: { isPresented in
          if !isPresented {
            hasCompletedOnboarding = true
            showsOnboarding = false
          }
        }
      )
    ) {
      OnboardingView {
        hasCompletedOnboarding = true
        showsOnboarding = false
      }
      .interactiveDismissDisabled(!hasCompletedOnboarding)
    }
  }
}

/// Let the native floating tab bar sample the selected world's surface rather
/// than a contrasting preview card scrolling beneath it. Safe-area geometry
/// owns the backdrop height, including accessibility and device variations.
private struct FoodTabBackdrop: ViewModifier {
  let skin: SkinID

  func body(content: Content) -> some View {
    content.safeAreaInset(edge: .bottom, spacing: 0) {
      Color.clear.frame(height: 1)
        .background(skin.design.palette.raised.ignoresSafeArea(edges: .bottom))
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
  }
}

enum RootTab: Hashable {
  case today
  case history
  case skins
  case settings

  static func destination(for url: URL?) -> RootTab? {
    guard let url,
      url.scheme?.lowercased() == "foodblob",
      url.query == nil,
      url.fragment == nil
    else {
      return nil
    }

    let path = url.pathComponents.filter { $0 != "/" }
    if url.host == "today", path.isEmpty {
      return .today
    }
    if url.host == nil, path == ["today"] {
      return .today
    }
    return nil
  }
}
