import XCTest

/// Explicitly enabled, synthetic-simulator acceptance only. Uses the normal app
/// and visible controls; every accepted addition is undone before leaving.
@MainActor
final class JellyInteractionUITests: XCTestCase {
  private let app = XCUIApplication(bundleIdentifier: "org.example.foodblob")
  private var baseline: [Int]?
  private var attemptedAdds = 0
  private let categories = ["Green", "Yellow", "Red"]
  private var originalMaterial: CGFloat?
  private var originalWorld: String?

  override func setUpWithError() throws {
    continueAfterFailure = false
    #if !targetEnvironment(simulator)
    throw XCTSkip("Jelly acceptance may only touch an isolated synthetic simulator.")
    #endif
    let environment = ProcessInfo.processInfo.environment
    guard environment["FOODBLOB_JELLY_ACCEPTANCE"] == "1" else {
      throw XCTSkip("Set FOODBLOB_JELLY_ACCEPTANCE=1 explicitly on the test runner.")
    }
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    // Do not dismiss onboarding or mutate preferences as part of acceptance.
    // The caller must prepare and own this synthetic simulator beforehand.
    let today = app.tabBars.buttons["Today"]
    if today.waitForExistence(timeout: 3) { today.tap() }
    XCTAssertTrue(foodButton(0).waitForExistence(timeout: 8),
      "Prepare the synthetic fixture and finish onboarding before this test.")
    baseline = try readCounts()
  }

  override func tearDownWithError() throws {
    if let originalMaterial {
      let slider = materialSlider()
      setMaterial(originalMaterial, slider: slider)
      XCTAssertEqual(materialPercentage(slider), Int((originalMaterial * 100).rounded()))
      self.originalMaterial = nil
    }
    if let originalWorld {
      app.tabBars.buttons["Skins"].tap()
      app.buttons[originalWorld].tap()
      self.originalWorld = nil
    }
    defer { baseline = nil; attemptedAdds = 0 }
    guard let baseline else { return }
    let today = app.tabBars.buttons["Today"]
    if today.exists { today.tap() }
    // A tap can commit before a subsequent assertion fails. Read the UI before
    // undoing and stop at the captured starting counts, never past them.
    for _ in 0..<attemptedAdds {
      if try readCounts() == baseline { break }
      let undo = app.buttons["Undo last change"]
      guard undo.waitForExistence(timeout: 3) else {
        XCTFail("Could not restore the synthetic fixture: Undo disappeared.")
        return
      }
      let previous = try readCounts()
      undo.tap()
      XCTAssertTrue(waitForCountsDifferent(from: previous), "Undo did not change the fixture.")
    }
    XCTAssertEqual(try readCounts(), baseline, "Synthetic starting food counts must be restored.")
    capture("restored-starting-counts")
  }

  func testPaintJellySliderPersistsWithoutChangingFoodAndBothWorldsStayPlayable() throws {
    let start = try XCTUnwrap(baseline)
    app.tabBars.buttons["Skins"].tap()
    originalWorld = app.buttons["skin-shrine"].value as? String == "Selected"
      ? "skin-shrine" : "skin-sky_meadow"
    let initial = materialSlider()
    originalMaterial = CGFloat(try XCTUnwrap(materialPercentage(initial))) / 100
    XCTAssertEqual(materialPercentage(initial), 0, "Prepare a default-paint synthetic fixture.")
    capture("paint-default-settings")
    app.descendants(matching: .any)["blob-material-preview"].press(forDuration: 0.75)
    capture("paint-settings-preview-after-poke")
    app.tabBars.buttons["Today"].tap()
    XCTAssertEqual(try readCounts(), start, "The material preview must never add food.")
    for _ in 0..<3 {
      attemptedAdds += 1
      foodButton(0).tap()
    }
    let fed = [start[0] + 3, start[1], start[2]]
    XCTAssertTrue(waitForCounts(fed))
    for amount in [CGFloat(0.5), 1, 0] {
      let slider = materialSlider()
      let applied = setMaterial(amount, slider: slider)
      capture("material-settings-\(applied)")
      app.terminate()
      app.launch()
      XCTAssertEqual(materialPercentage(materialSlider()), applied,
        "The selected material must survive a normal app relaunch.")
      for world in ["skin-sky_meadow", "skin-shrine"] {
        app.tabBars.buttons["Skins"].tap()
        app.buttons[world].tap()
        XCTAssertEqual(app.buttons[world].value as? String, "Selected")
        app.tabBars.buttons["Today"].tap()
        XCTAssertEqual(try readCounts(), fed)
        capture("\(world)-material-\(applied)-resting")
        let blob = app.descendants(matching: .any).matching(
          NSPredicate(format: "label == %@", "Food mix")).firstMatch
        let center = blob.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        center.press(forDuration: 0.75)
        center.press(forDuration: 0.08)
        center.press(forDuration: 0.08)
        center.press(forDuration: 0.08)
        let edge = blob.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.46))
        edge.press(forDuration: 0.25, thenDragTo: edge.withOffset(CGVector(dx: 22, dy: -10)),
          withVelocity: .slow, thenHoldForDuration: 0.3)
        XCTAssertEqual(try readCounts(), fed, "Poking and stretching are presentation only.")
        capture("\(world)-material-\(applied)-after-poke")
      }
    }
  }

  private func materialSlider() -> XCUIElement {
    app.tabBars.buttons["Settings"].tap()
    let slider = app.sliders["blob-translucency-slider"]
    // Scroll in the leading gutter, outside the material slider/preview.
    for _ in 0..<4 where !slider.isHittable {
      let top = app.navigationBars.firstMatch.frame.maxY + 36
      let bottom = app.tabBars.firstMatch.frame.minY - 36
      let origin = app.coordinate(withNormalizedOffset: .zero)
      origin.withOffset(CGVector(dx: 8, dy: top))
        .press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: 8, dy: bottom)),
          withVelocity: .slow, thenHoldForDuration: 0)
    }
    XCTAssertTrue(slider.waitForExistence(timeout: 3))
    return slider
  }

  private func materialPercentage(_ slider: XCUIElement) -> Int? {
    Int((slider.value as? String ?? "").filter(\.isNumber))
  }

  @discardableResult
  private func setMaterial(_ amount: CGFloat, slider: XCUIElement) -> Int {
    slider.adjust(toNormalizedSliderPosition: amount)
    if amount == 0 || amount == 1 {
      // XCTest's normalized adjustment can stop one 5% step inside the
      // track. Drag the visible thumb beyond the track to select its endpoint.
      let position = CGFloat(materialPercentage(slider) ?? -1) / 100
      XCTAssertTrue((0...1).contains(position))
      let frame = slider.frame
      let inset = min(frame.height / 2, frame.width / 2)
      let thumbX = (inset + (frame.width - 2 * inset) * position) / frame.width
      slider.coordinate(withNormalizedOffset: CGVector(dx: thumbX, dy: 0.5))
        .press(forDuration: 0.1, thenDragTo: slider.coordinate(
          withNormalizedOffset: CGVector(dx: amount == 0 ? -0.1 : 1.1, dy: 0.5)),
          withVelocity: .slow, thenHoldForDuration: 0.1)
    }
    let applied = materialPercentage(slider) ?? -1
    capture("material-request-\(Int(amount * 100))-actual-\(applied)")
    if amount == 0.5 {
      // XCTest thumb drags are approximate. Verify the real intermediate value
      // persists; the renderer's exact 0.5 interpolation has a unit test.
      XCTAssertTrue((45...55).contains(applied), "The midpoint drag must choose an intermediate material.")
    } else {
      XCTAssertEqual(applied, Int((amount * 100).rounded()))
    }
    return applied
  }

  func testDragOfferingCommitsOnceAndCancelledDragDoesNotLog() throws {
    let start = try XCTUnwrap(baseline)
    let blob = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Food mix")).firstMatch
    let lens = foodButton(1)
    lens.press(forDuration: 0.65)
    XCTAssertEqual(try readCounts(), start, "Holding a colour alone must never add or remove food.")
    attemptedAdds += 1
    lens.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
      .press(forDuration: 0.55, thenDragTo: blob.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55)),
        withVelocity: .slow, thenHoldForDuration: 0.15)
    var expected = start
    expected[1] += 1
    XCTAssertTrue(waitForCounts(expected), "A valid dropped colour must record exactly one addition.")
    capture("drag-offering-accepted")
    let press = foodButton(2).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    press.press(forDuration: 0.55, thenDragTo: press.withOffset(CGVector(dx: 130, dy: -55)),
      withVelocity: .fast, thenHoldForDuration: 0)
    XCTAssertEqual(try readCounts(), expected, "A missed drop must cancel without logging.")
    capture("drag-offering-cancelled")
  }

  func testGrowthPortraitsAndBothWorlds() throws {
    guard baseline == [0, 0, 0] else { throw XCTSkip("Growth capture needs the owned zero-count Today fixture.") }
    capture("growth-00")
    for total in 1...20 {
      attemptedAdds += 1
      foodButton((total - 1) % 3).tap()
      let expected = [(total + 2) / 3, (total + 1) / 3, total / 3]
      XCTAssertTrue(waitForCounts(expected), "Growth capture requires every accepted food addition.")
      if [1, 2, 3, 10, 20].contains(total) {
        RunLoop.current.run(until: Date().addingTimeInterval(0.9))
        capture(String(format: "growth-%02d", total))
      }
    }
    app.tabBars.buttons["Skins"].tap()
    capture("worlds-actual-twenty-offerings")
    let shrine = app.buttons["skin-shrine"]
    XCTAssertTrue(shrine.waitForExistence(timeout: 3))
    shrine.tap()
    XCTAssertEqual(shrine.value as? String, "Selected")
    capture("worlds-shrine-selected")
    app.tabBars.buttons["Today"].tap()
    XCTAssertEqual(try readCounts(), [7, 7, 6], "Changing skins must preserve all twenty offerings.")
    capture("shrine-twenty-offerings")
    app.tabBars.buttons["Skins"].tap()
    app.buttons["skin-sky_meadow"].tap()
    XCTAssertEqual(app.buttons["skin-sky_meadow"].value as? String, "Selected")
    app.tabBars.buttons["Today"].tap()
  }

  func testHistoryPortraitExpandsAndSettingsWelcomeAreReachable() throws {
    app.tabBars.buttons["History"].tap()
    let day = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH %@", "history-day-")).firstMatch
    XCTAssertTrue(day.waitForExistence(timeout: 3))
    capture("calendar-portraits")
    day.tap()
    XCTAssertTrue(foodButton(0).waitForExistence(timeout: 3))
    capture("calendar-expanded-day")
    app.navigationBars.buttons.element(boundBy: 0).tap()
    app.buttons["Previous month"].tap()
    capture("calendar-previous-month")
    app.tabBars.buttons["Settings"].tap()
    app.swipeUp()
    let deletion = app.buttons.containing(.staticText, identifier: "Delete all data").firstMatch
    if deletion.isHittable {
      deletion.tap()
      XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 2))
      capture("delete-confirmation")
      app.alerts.buttons["Cancel"].tap()
    }
    let welcome = app.buttons.containing(.staticText, identifier: "Show welcome again").firstMatch
    if !welcome.isHittable { app.swipeUp() }
    XCTAssertTrue(welcome.isHittable)
    welcome.tap()
    capture("welcome-mascot")
    app.buttons["Continue"].tap()
    capture("welcome-colours")
    app.buttons["Continue"].tap()
    capture("welcome-widget")
    app.buttons["Start logging"].tap()
    app.tabBars.buttons["Today"].tap()
  }

  func testMenusRemainNavigableAndPreserveCounts() throws {
    for title in ["History", "Skins", "Settings"] {
      let tabTitle = title == "History" && !app.tabBars.buttons["History"].exists ? "Shelf" : title
      let tab = app.tabBars.buttons[tabTitle]
      XCTAssertTrue(tab.waitForExistence(timeout: 3))
      tab.tap()
      XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 3))
      if title == "History" {
        let firstDay = app.buttons.matching(
          NSPredicate(format: "identifier BEGINSWITH %@", "history-day-")).firstMatch
        XCTAssertTrue(firstDay.waitForExistence(timeout: 3))
        XCTAssertGreaterThanOrEqual(firstDay.frame.width, 44,
          "Empty and populated days must occupy their grid column, not collapse around the marker.")
        XCTAssertGreaterThanOrEqual(firstDay.frame.height, 44)
      }
      capture("menu-\(title.lowercased())-top")
      app.swipeUp()
      capture("menu-\(title.lowercased())-scrolled")
    }
    app.tabBars.buttons["Today"].tap()
    XCTAssertEqual(try readCounts(), baseline)
  }

  /// Run with the prepared simulator at standard text size. Logging colours
  /// must be visible immediately, including on the shortest supported phone.
  func testAllFoodControlsFitWithoutScrollingAtStandardTextSize() throws {
    let tabBar = app.tabBars.firstMatch
    XCTAssertTrue(tabBar.exists)
    for category in 0..<3 {
      let control = foodButton(category)
      XCTAssertTrue(control.isHittable, "Every colour must be reachable without scrolling.")
      XCTAssertGreaterThanOrEqual(control.frame.height, 44)
      XCTAssertGreaterThanOrEqual(control.frame.width, 44)
      XCTAssertLessThan(control.frame.maxY, tabBar.frame.minY,
        "A logging control must not be hidden behind navigation.")
    }
    capture("all-colours-visible-standard-text")
    XCTAssertEqual(try readCounts(), baseline)
  }

  func testCancelledLensPressAndSettingsLinksPreserveCounts() throws {
    let start = try XCTUnwrap(baseline)
    // Verify held cancellation against the real recognizer, then retain
    // the separate short dragged-away press contract with absolute geometry.
    attemptedAdds = 1
    let lens = foodButton(0)
    let visibleLens = lens.frame.intersection(app.frame)
    XCTAssertFalse(visibleLens.isEmpty)
    let startPoint = CGPoint(x: visibleLens.midX, y: visibleLens.midY)
    let origin = app.coordinate(withNormalizedOffset: .zero)
    let press = origin.withOffset(CGVector(dx: startPoint.x - app.frame.minX, dy: startPoint.y - app.frame.minY))
    let blob = app.descendants(matching: .any).matching(
      NSPredicate(format: "label == %@", "Food mix")).firstMatch
    // Release in empty navigation space, well outside the visible controls
    // and the hero/drop region. Use absolute coordinates for the expanded AX frame.
    let destination = CGPoint(x: app.frame.minX + 8, y: app.navigationBars.firstMatch.frame.midY)
    XCTAssertFalse(lens.frame.contains(destination))
    XCTAssertFalse(blob.frame.contains(destination))
    let geometry = XCTAttachment(string: "lens=\(lens.frame), visibleLens=\(visibleLens), start=\(startPoint), blob=\(blob.frame), cancellation=\(destination), heldDuration=0.55, shortDuration=0.08, recognizerMinimum=0.45")
    geometry.name = "cancelled-lens-geometry"; geometry.lifetime = .keepAlways; add(geometry)
    capture("before-cancelled-lens-press")
    let target = app.coordinate(withNormalizedOffset: .zero)
      .withOffset(CGVector(dx: destination.x - app.frame.minX, dy: destination.y - app.frame.minY))
    press.press(forDuration: 0.55, thenDragTo: target,
      withVelocity: .slow, thenHoldForDuration: 0.15)
    capture("cancelled-held-lens-press")
    XCTAssertEqual(try readCounts(), start, "A held offering released outside the drop region must cancel.")
    let shortLens = foodButton(0).frame.intersection(app.frame)
    let shortPress = origin.withOffset(CGVector(dx: shortLens.midX - app.frame.minX,
      dy: shortLens.midY - app.frame.minY))
    capture("before-cancelled-short-lens-press")
    shortPress.press(forDuration: 0.08, thenDragTo: target,
      withVelocity: .slow, thenHoldForDuration: 0.15)
    capture("cancelled-short-lens-press")
    XCTAssertEqual(try readCounts(), start, "A short dragged-away press must not add an offering.")
    app.tabBars.buttons["Settings"].tap()
    for (row, title) in [("Privacy", "Privacy"), ("Add the Home Screen widget", "Add widget")] {
      let link = app.buttons.containing(.staticText, identifier: row).firstMatch
      XCTAssertTrue(link.waitForExistence(timeout: 3))
      link.tap()
      XCTAssertTrue(app.navigationBars[title].waitForExistence(timeout: 3))
      capture("settings-\(title.lowercased())")
      app.navigationBars.buttons.element(boundBy: 0).tap()
    }
    app.tabBars.buttons["Today"].tap()
    XCTAssertEqual(try readCounts(), start)
  }

  func testJellyFeedingStretchingAndUndoPreserveCounts() throws {
    let start = try XCTUnwrap(baseline)
    capture("resting-before-feed")
    var expected = start
    for category in 0..<3 {
      attemptedAdds += 1
      foodButton(category).tap()
      expected[category] += 1
      XCTAssertTrue(waitForCounts(expected), "A colour tap must add exactly one offering.")
      capture("after-\(categories[category].lowercased())-feed")
    }

    // Re-query by label prefix because the visible count changes after each tap.
    // Do not wait for the animation between these inputs.
    for _ in 0..<3 {
      attemptedAdds += 1
      foodButton(0).tap()
    }
    expected[0] += 3
    XCTAssertTrue(waitForCounts(expected), "A burst must retain every accepted addition.")

    let blob = app.descendants(matching: .any).matching(
      NSPredicate(format: "label == %@", "Food mix")).firstMatch
    XCTAssertTrue(blob.waitForExistence(timeout: 3))
    let center = blob.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
    let edge = blob.coordinate(withNormalizedOffset: CGVector(dx: 0.76, dy: 0.52))
    let stretched = edge.withOffset(CGVector(dx: 42, dy: -16))
    // The root records the simulator externally to inspect the genuinely held
    // and moving frames; XCTest screenshots here are labelled after release.
    center.press(forDuration: 0.65)
    capture("after-local-press")
    edge.press(forDuration: 0.25, thenDragTo: stretched,
      withVelocity: .slow, thenHoldForDuration: 0.65)
    capture("after-stretch-release")
    XCTAssertEqual(try readCounts(), expected, "Playing with jelly must never log food.")
    RunLoop.current.run(until: Date().addingTimeInterval(1.1))
    capture("settled-after-burst-and-stretch")

    // Exercise the production undo control explicitly; tearDown restores the rest.
    app.buttons["Undo last change"].tap()
    expected[0] -= 1
    XCTAssertTrue(waitForCounts(expected))
    XCTAssertEqual(expected.reduce(0, +), start.reduce(0, +) + 5)
    capture("after-undo")
  }

  private func foodButton(_ index: Int) -> XCUIElement {
    app.descendants(matching: .any).matching(
      NSPredicate(format: "label BEGINSWITH %@", "\(categories[index]) food, ")).firstMatch
  }

  private func readCounts() throws -> [Int] {
    try categories.indices.map { index in
      let button = foodButton(index)
      guard button.exists,
        let match = button.label.range(of: #"\d[\d,]*(?= logged)"#, options: .regularExpression),
        let count = Int(button.label[match].filter(\.isNumber)) else {
        throw FixtureError.unreadableCount(categories[index], button.label)
      }
      return count
    }
  }

  private func waitForCounts(_ expected: [Int]) -> Bool {
    let deadline = Date().addingTimeInterval(4)
    repeat {
      if (try? readCounts()) == expected { return true }
      RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    } while Date() < deadline
    return false
  }

  private func waitForCountsDifferent(from previous: [Int]) -> Bool {
    let deadline = Date().addingTimeInterval(4)
    repeat {
      if let counts = try? readCounts(), counts != previous { return true }
      RunLoop.current.run(until: Date().addingTimeInterval(0.05))
    } while Date() < deadline
    return false
  }

  private func capture(_ name: String) {
    let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }

  private enum FixtureError: Error {
    case unreadableCount(String, String)
  }
}
