import SwiftUI
import XCTest

@testable import FoodBlob

final class AppContractTests: XCTestCase {
  func testOnboardingShrinksOnlyTheMascotAtAccessibilityTextSizes() {
    XCTAssertEqual(
      OnboardingLayoutPolicy.mascotSize(isAccessibilitySize: false),
      CGSize(width: 250, height: 238)
    )
    XCTAssertEqual(
      OnboardingLayoutPolicy.mascotSize(isAccessibilitySize: true),
      CGSize(width: 156, height: 148)
    )
  }

  func testTodayDeepLinkMapsOnlyTheSupportedRoute() {
    XCTAssertEqual(
      RootTab.destination(for: URL(string: "foodblob://today")),
      .today
    )
    XCTAssertEqual(
      RootTab.destination(for: URL(string: "foodblob:///today")),
      .today
    )
    XCTAssertNil(RootTab.destination(for: URL(string: "foodblob://history")))
    XCTAssertNil(
      RootTab.destination(for: URL(string: "foodblob://today/extra"))
    )
    XCTAssertNil(RootTab.destination(for: URL(string: "https://today")))
    XCTAssertNil(RootTab.destination(for: nil))
  }

  func testPrivacyManifestDeclaresAppLocalUserDefaultsReason() throws {
    let plist = try repositoryPropertyList("FoodBlob/PrivacyInfo.xcprivacy")
    let accessedTypes = try XCTUnwrap(
      plist["NSPrivacyAccessedAPITypes"] as? [[String: Any]]
    )
    let userDefaults = try XCTUnwrap(
      accessedTypes.first {
        $0["NSPrivacyAccessedAPIType"] as? String
          == "NSPrivacyAccessedAPICategoryUserDefaults"
      }
    )
    let reasons = try XCTUnwrap(
      userDefaults["NSPrivacyAccessedAPITypeReasons"] as? [String]
    )
    XCTAssertTrue(reasons.contains("CA92.1"))
  }

  func testPrivacyManifestDeclaresNoCollectionOrTracking() throws {
    let plist = try repositoryPropertyList("FoodBlob/PrivacyInfo.xcprivacy")

    XCTAssertTrue(
      try XCTUnwrap(plist["NSPrivacyCollectedDataTypes"] as? [[String: Any]])
        .isEmpty
    )
    XCTAssertFalse(try XCTUnwrap(plist["NSPrivacyTracking"] as? Bool))
    XCTAssertTrue(
      try XCTUnwrap(plist["NSPrivacyTrackingDomains"] as? [String]).isEmpty
    )
  }

  func testPrivacyCopyDescribesThePairedWatchWithoutClaimingIPhoneOnly() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlob/Features/Settings/SettingsView.swift"
      ),
      encoding: .utf8
    )

    XCTAssertTrue(source.contains("paired Apple Watch"))
    XCTAssertTrue(source.contains("Watch taps sync back to this iPhone"))
    XCTAssertTrue(source.contains("No tracking or backend"))
    XCTAssertFalse(source.contains("Everything stays on this iPhone"))
  }

  func testUnavailableWidgetHasVisibleRecoveryCopy() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlobWidgets/FoodCounterWidget.swift"
      ),
      encoding: .utf8
    )

    XCTAssertTrue(source.contains("FoodWidgetUnavailableOverlay"))
    XCTAssertTrue(source.contains("Open Food Blob"))
    XCTAssertTrue(source.contains("to finish setup"))
  }

  func testAcceptanceRepairsStayAttachedToTheirUserFacingSurfaces() throws {
    let counterControls = try String(
      contentsOf: repositoryFile("FoodBlob/Design/FoodCounterControls.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(counterControls.contains(".accessibilityValue(\"\\(count)\")"))
    XCTAssertTrue(counterControls.contains("Add one \\(color.displayName.lowercased()) food"))
    XCTAssertTrue(counterControls.contains("Remove one \\(color.displayName.lowercased()) food"))
    XCTAssertFalse(counterControls.contains(".accessibilityAdjustableAction"))

    let today = try String(
      contentsOf: repositoryFile("FoodBlob/Features/Today/TodayView.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(today.contains("Text(changeNotice ??"))
    XCTAssertEqual(today.components(separatedBy: "Button(\"Undo\", action: undo)").count - 1, 1,
      "Undo needs one stable, discoverable location.")
    XCTAssertFalse(today.contains(".safeAreaInset(edge: .bottom"))

    let history = try String(
      contentsOf: repositoryFile("FoodBlob/Features/History/HistoryView.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(history.contains("showsSelectedDateInTitle: true"))

    let onboarding = try String(
      contentsOf: repositoryFile("FoodBlob/Features/Onboarding/OnboardingView.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(onboarding.contains("accessibilityElement(children: .ignore)"))
    XCTAssertTrue(onboarding.contains("Examples of the small add widget"))

    let settings = try String(
      contentsOf: repositoryFile("FoodBlob/Features/Settings/SettingsView.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(settings.contains("if let error = store.lastError"))
    XCTAssertTrue(settings.contains("Food Blob error"))

    let widgetSetup = try String(
      contentsOf: repositoryFile("FoodBlob/Features/Widgets/WidgetSetupView.swift"),
      encoding: .utf8
    )
    XCTAssertTrue(widgetSetup.contains("store.counts(on: Date())"))
    XCTAssertFalse(widgetSetup.contains("counts: store.visibleCounts"))
  }

  func testAppMetadataKeepsTheSupportedRouteAndEncryptionDeclaration() throws {
    let plist = try repositoryPropertyList("FoodBlob/Info.plist")
    let urlTypes = try XCTUnwrap(
      plist["CFBundleURLTypes"] as? [[String: Any]]
    )
    let schemes = urlTypes.flatMap {
      $0["CFBundleURLSchemes"] as? [String] ?? []
    }

    XCTAssertEqual(schemes, ["foodblob"])
    XCTAssertFalse(
      try XCTUnwrap(plist["ITSAppUsesNonExemptEncryption"] as? Bool)
    )
  }

  func testAppAndWidgetEntitlementsUseOnlyTheSharedAppGroup() throws {
    for path in [
      "FoodBlob/FoodBlob.entitlements",
      "FoodBlobWidgets/FoodBlobWidgets.entitlements",
    ] {
      let plist = try repositoryPropertyList(path)

      XCTAssertEqual(
        plist["com.apple.security.application-groups"] as? [String],
        [FoodBlobConstants.appGroupIdentifier],
        path
      )
    }
  }

  func testWatchAppEmbeddingUsesItsBuiltProductAndCompanionContract() throws {
    let watchInfo = try repositoryPropertyList("FoodBlobWatch/Info.plist")
    XCTAssertEqual(
      watchInfo["WKCompanionAppBundleIdentifier"] as? String,
      "org.example.foodblob"
    )
    XCTAssertEqual(watchInfo["WKWatchKitApp"] as? Bool, true)

    let project = try String(
      contentsOf: repositoryFile("FoodBlob.xcodeproj/project.pbxproj"),
      encoding: .utf8
    )
    XCTAssertTrue(project.contains("FoodBlobWatch.app in Embed Watch Content"))
    XCTAssertTrue(project.contains("path = FoodBlobWatch.app;"))
    XCTAssertTrue(
      project.contains(
        "dstPath = \"$(CONTENTS_FOLDER_PATH)/Watch\";\n\t\t\tdstSubfolderSpec = 16;"
      )
    )
    XCTAssertTrue(
      project.contains(
        "name = \"Embed Watch Content\";\n\t\t\trunOnlyForDeploymentPostprocessing = 1;"
      )
    )
    XCTAssertFalse(project.contains("Food Blob Watch.app"))
    let embedBuildFile = project.split(separator: "\n").first {
      $0.contains("FoodBlobWatch.app in Embed Watch Content")
    }
    XCTAssertNotNil(embedBuildFile)
    XCTAssertFalse(embedBuildFile?.contains("platformFilters") == true)
    let appTarget = try XCTUnwrap(
      project.range(of: "name = FoodBlob;\n\t\t\tproductName = FoodBlob;")
    )
    let appTargetText =
      String(project[appTarget.lowerBound...])
      .components(separatedBy: "\n\t\t};")
      .first ?? ""
    XCTAssertFalse(appTargetText.contains("FoodBlobWatch"))
    XCTAssertFalse(project.contains("remoteInfo = FoodBlobWatch;"))
    XCTAssertTrue(
      project.contains(
        "PRODUCT_NAME = FoodBlobWatch;\n\t\t\t\tSDKROOT = watchos;\n\t\t\t\tSKIP_INSTALL = YES;"
      )
    )

    let scheme = try String(
      contentsOf: repositoryFile(
        "FoodBlob.xcodeproj/xcshareddata/xcschemes/FoodBlob.xcscheme"
      ),
      encoding: .utf8
    )
    XCTAssertTrue(
      scheme.contains(
        "buildForTesting = \"NO\"\n            buildForRunning = \"NO\"\n            buildForProfiling = \"NO\"\n            buildForArchiving = \"YES\"\n            buildForAnalyzing = \"NO\""
      )
    )
    XCTAssertTrue(scheme.contains("parallelizeBuildables = \"NO\""))
    let watchEntry = try XCTUnwrap(
      scheme.range(of: "BlueprintName = \"FoodBlobWatch\"")
    )
    let appEntry = try XCTUnwrap(
      scheme.range(of: "BlueprintName = \"FoodBlob\"")
    )
    XCTAssertLessThan(watchEntry.lowerBound, appEntry.lowerBound)
    XCTAssertTrue(
      scheme.contains(
        "BlueprintName = \"FoodBlobWatch\"\n               ReferencedContainer = \"container:FoodBlob.xcodeproj\""
      )
    )

    let watchScheme = try String(
      contentsOf: repositoryFile(
        "FoodBlob.xcodeproj/xcshareddata/xcschemes/FoodBlobWatch.xcscheme"
      ),
      encoding: .utf8
    )
    XCTAssertTrue(watchScheme.contains("BuildableName = \"FoodBlobWatch.app\""))
    XCTAssertFalse(watchScheme.contains("Food Blob Watch.app"))
  }

  func testSkyMeadowAndShrineAreTheOnlyProductSkins() {
    XCTAssertEqual(SkinID.allCases, [.skyMeadow, .shrine])
  }

  func testBothSkinsKeepThreeDistinctNeutralFoodColors() {
    for skin in SkinID.allCases {
      let design = skin.design
      let colors = [
        design.palette.green.description,
        design.palette.yellow.description,
        design.palette.red.description,
      ]
      XCTAssertEqual(Set(colors).count, 3, skin.rawValue)
    }
  }

  func testFoodColorsKeepTheirSharedPresentationPalette() {
    let expected: [(FoodColor, BlobColor, [BlobColor])] = [
      (
        .green,
        .foodGreen,
        [
          BlobColor(red: 0.22, green: 0.86, blue: 0.53),
          BlobColor(red: 0.10, green: 0.69, blue: 0.35),
        ]
      ),
      (
        .yellow,
        .foodYellow,
        [
          BlobColor(red: 1.00, green: 0.87, blue: 0.36),
          BlobColor(red: 0.96, green: 0.72, blue: 0.10),
        ]
      ),
      (
        .red,
        .foodRed,
        [
          BlobColor(red: 1.00, green: 0.43, blue: 0.47),
          BlobColor(red: 0.89, green: 0.22, blue: 0.29),
        ]
      ),
    ]

    for (color, base, stops) in expected {
      XCTAssertEqual(color.blobColor, base)
      XCTAssertEqual(color.meadowGradientStops, stops)
    }
  }

  func testStableWidgetIdentifiersSurviveTheRedesign() {
    XCTAssertEqual(
      WidgetLayoutID.allCases.map(\.rawValue),
      [
        "bubble_stack",
        "pop_columns",
        "blob_stage",
        "four_pops",
        "tap_deck",
        "sidecar_tiles",
        "dice_row",
        "color_courtyard",
        "step_stones",
        "puddle_dock",
        "palette_tray",
      ]
    )
    let kinds =
      [
        FoodBlobConstants.counterWidgetKind,
        FoodBlobConstants.blobWidgetKind,
        FoodBlobConstants.skyMeadowWidgetKind,
        FoodBlobConstants.shrineWidgetKind,
      ]
      + WidgetLayoutID.galleryAlternatives.map {
        FoodBlobConstants.counterWidgetKind(for: $0)
      }
    XCTAssertEqual(kinds.count, 14)
    XCTAssertEqual(Set(kinds).count, 14)
    XCTAssertEqual(FoodBlobConstants.counterWidgetKind, "FoodBlobCounterWidgetV2")
    XCTAssertEqual(FoodBlobConstants.blobWidgetKind, "FoodBlobLivingBlobWidget")
    XCTAssertEqual(
      FoodBlobConstants.skyMeadowWidgetKind,
      "FoodBlobSkyMeadowWidgetV1"
    )
    XCTAssertEqual(
      FoodBlobConstants.shrineWidgetKind,
      "FoodBlobShrineWidgetV1"
    )
  }

  func testOnlyFixedDayAndNightWidgetsStayActiveInTheGallery() {
    XCTAssertEqual(
      FoodBlobConstants.activeWidgetKinds,
      [
        FoodBlobConstants.skyMeadowWidgetKind,
        FoodBlobConstants.shrineWidgetKind,
      ]
    )
  }

  func testCurrentWidgetGeometryKeepsFortyFourPointTouchTargets() {
    for (size, presentation) in [
      (CGSize(width: 172, height: 158), FoodWidgetPresentation.counter),
      (CGSize(width: 364, height: 170), FoodWidgetPresentation.combined),
    ] {
      let geometry = FoodWidgetGeometry.resolve(size: size, presentation: presentation)
      for control in geometry.controls {
        XCTAssertGreaterThanOrEqual(control.rect.width, 44)
        XCTAssertGreaterThanOrEqual(control.rect.height, 44)
        XCTAssertTrue(CGRect(origin: .zero, size: size).contains(control.rect))
      }
    }
  }

  func testTransientBlobMotionRequiresAnActiveUnchangedScopeWithoutReducedMotion() {
    XCTAssertTrue(BlobAnimationPolicy.showsTransientPaint(
      reduceMotion: false, allowsIdleMotion: true, stayedInSameScope: true))
    XCTAssertFalse(BlobAnimationPolicy.showsTransientPaint(
      reduceMotion: true, allowsIdleMotion: true, stayedInSameScope: true))
    XCTAssertFalse(BlobAnimationPolicy.showsTransientPaint(
      reduceMotion: false, allowsIdleMotion: false, stayedInSameScope: true))
    XCTAssertFalse(BlobAnimationPolicy.showsTransientPaint(
      reduceMotion: false, allowsIdleMotion: true, stayedInSameScope: false))
  }

  func testWidgetButtonsKeepTheProvenInteractiveSerializationShape() throws {
    let source = try String(
      contentsOf: repositoryFile("FoodBlobWidgets/FoodCounterWidget.swift"),
      encoding: .utf8
    )

    XCTAssertFalse(source.contains("showsControls: false"))
    XCTAssertFalse(source.contains("FoodWidgetTactileButtonStyle"))
    XCTAssertTrue(source.contains(".buttonStyle(.plain)"))

    let controlButton = try XCTUnwrap(
      source.components(separatedBy: "private func controlButton(")
        .dropFirst()
        .first?
        .components(separatedBy: "  @ViewBuilder")
        .first
    )
    XCTAssertTrue(controlButton.contains("Color.clear"))
    XCTAssertFalse(controlButton.contains("FoodWidgetControlArtwork"))
    XCTAssertFalse(controlButton.contains(".invalidatableContent()"))
  }

  func testHistoryCompactBlobsOmitTheUnitLabel() throws {
    let source = try String(
      contentsOf: repositoryFile("FoodBlob/Features/History/HistoryView.swift"),
      encoding: .utf8
    )
    let dayCell = try XCTUnwrap(
      source.components(separatedBy: "private struct HistoryDayCell")
        .dropFirst()
        .first
    )

    XCTAssertTrue(dayCell.contains("showsUnitLabel: false"))
  }

  func testPublicSourceKeepsReleaseCredentialsAndStoreMaterialOutsideTree() throws {
    for path in [
      "export/appstore", ".github/workflows/testflight.yml",
      "scripts/testflight_build.py", "ios/ci_scripts",
    ] {
      let file = try repositoryFile(path)
      XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), path)
    }
  }

  func testPublicAppAndWatchRetainTheSameBundledIcon() throws {
    let appIcon = try Data(contentsOf: repositoryFile(
      "FoodBlob/Assets.xcassets/AppIcon.appiconset/FoodBlobIcon-v3.png"
    ))
    let watchIcon = try Data(contentsOf: repositoryFile(
      "FoodBlobWatch/Assets.xcassets/AppIcon.appiconset/FoodBlobIcon-v3.png"
    ))
    XCTAssertGreaterThan(appIcon.count, 1_000)
    XCTAssertEqual(appIcon, watchIcon)
  }

  func testWidgetExtensionExcludesTheAppOnlyObservableStore() throws {
    let project = try String(
      contentsOf: repositoryFile("FoodBlob.xcodeproj/project.pbxproj"),
      encoding: .utf8
    )

    XCTAssertEqual(
      project.components(separatedBy: "/* FoodStore.swift in Sources */").count - 1,
      2
    )
  }

  func testWatchForegroundRefreshReadsTheContextReceivedFromIPhone() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlobWatchExtension/FoodWatchSessionClient.swift"
      ),
      encoding: .utf8
    )

    XCTAssertTrue(source.contains("session?.receivedApplicationContext"))
    XCTAssertFalse(source.contains("session?.applicationContext else"))
    XCTAssertFalse(source.contains("guard let session, !started else"))
  }

  func testPhoneRefreshRetriesStagedWatchReceiptsBeforeIngesting() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlob/Connectivity/FoodWatchConnectivityReceiver.swift"
      ),
      encoding: .utf8
    )
    let function = try XCTUnwrap(
      source.components(separatedBy: "private func receiveStateRefreshRequest() {")
        .dropFirst()
        .first?
        .components(separatedBy: "\n  }")
        .first
    )
    let retry = try XCTUnwrap(function.range(of: "retryPendingEvents()"))
    let ingest = try XCTUnwrap(function.range(of: "ingestOnMain()"))

    XCTAssertLessThan(retry.lowerBound, ingest.lowerBound)
  }

  func testWidgetIntentCanWakeTheAppProcessWithoutOpeningIt() throws {
    let intent = try String(
      contentsOf: repositoryFile("Shared/ChangeFoodCountIntent.swift"),
      encoding: .utf8
    )
    let conformance = try String(
      contentsOf: repositoryFile(
        "FoodBlob/Connectivity/ChangeFoodCountIntent+AppProcess.swift"
      ),
      encoding: .utf8
    )
    let project = try String(
      contentsOf: repositoryFile("FoodBlob.xcodeproj/project.pbxproj"),
      encoding: .utf8
    )

    XCTAssertTrue(intent.contains("static var openAppWhenRun = false"))
    XCTAssertFalse(intent.contains("requestToContinueInForeground"))
    XCTAssertTrue(conformance.contains("ForegroundContinuableIntent"))
    XCTAssertFalse(conformance.contains("requestToContinueInForeground"))
    XCTAssertEqual(
      project.components(
        separatedBy: "/* ChangeFoodCountIntent.swift in Sources */"
      ).count - 1,
      4
    )
  }

  func testWidgetIntentPersistsBeforeNotifyingTheAppProcess() throws {
    let source = try String(
      contentsOf: repositoryFile("Shared/ChangeFoodCountIntent.swift"),
      encoding: .utf8
    )
    let append = try XCTUnwrap(source.range(of: "appendWidgetEntry"))
    let notify = try XCTUnwrap(source.range(of: "notifyCommittedAction"))

    XCTAssertLessThan(append.lowerBound, notify.lowerBound)
  }

  func testPhoneAcknowledgementCompletesOnlyFromDeliveryCallback() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlob/Connectivity/FoodWatchConnectivityReceiver.swift"
      ),
      encoding: .utf8
    )
    let sender = try XCTUnwrap(
      source.components(
        separatedBy: "private func sendAcknowledgementIfPossible("
      ).dropFirst().first?.components(separatedBy: "\n  }").first
    )
    let callback = try XCTUnwrap(
      source.components(
        separatedBy: "didFinish userInfoTransfer: WCSessionUserInfoTransfer"
      ).dropFirst().first
    )

    let queuedTransfer = try XCTUnwrap(
      sender.components(separatedBy: "try session.updateApplicationContext")
        .dropFirst().first
    )
    XCTAssertFalse(queuedTransfer.contains("clearResetPending"))
    XCTAssertFalse(sender.contains("markAcknowledgementSent"))
    XCTAssertTrue(callback.contains("deliveryTracker.completed"))
    XCTAssertTrue(callback.contains("clearResetPending"))
  }

  func testWatchBackgroundTaskWaitsForIncomingApplyTail() throws {
    let source = try String(
      contentsOf: repositoryFile(
        "FoodBlobWatchExtension/FoodWatchSessionClient.swift"
      ),
      encoding: .utf8
    )
    let handler = try XCTUnwrap(
      source.components(separatedBy: "func handle(")
        .dropFirst().first?.components(separatedBy: "\n  }").first
    )
    let append = try XCTUnwrap(handler.range(of: "pendingBackgroundTasks.append"))
    let start = try XCTUnwrap(handler.range(of: "start()"))

    XCTAssertLessThan(append.lowerBound, start.lowerBound)
    XCTAssertTrue(source.contains("incomingDeliveryTracker.begin()"))
    XCTAssertTrue(source.contains("incomingDeliveryTracker.finish("))
    XCTAssertTrue(source.contains("acknowledgementApplier.apply"))
    let didFinish = try XCTUnwrap(
      source.components(
        separatedBy: "didFinish userInfoTransfer: WCSessionUserInfoTransfer"
      ).dropFirst().first
    )
    XCTAssertTrue(didFinish.contains("completeBackgroundTasksIfPossible"))
  }

  private func repositoryFile(_ path: String) throws -> URL {
    URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .appendingPathComponent(path)
  }

  private func repositoryPropertyList(_ path: String) throws -> [String: Any] {
    let data = try Data(contentsOf: repositoryFile(path))
    return try XCTUnwrap(
      PropertyListSerialization.propertyList(from: data, format: nil)
        as? [String: Any]
    )
  }

}
