import SwiftUI

struct HistoryView: View {
  @Environment(FoodStore.self) private var store
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var selectedDate: Date?
  @State private var displayedMonth = Date()
  @Namespace private var portraits

  private var design: SkinDesign { store.selectedSkin.design }
  private var calendar: Calendar { .autoupdatingCurrent }
  private var columns: [GridItem] { Array(repeating: GridItem(.flexible(), spacing: 0), count: 7) }
  private var canMoveBackward: Bool {
    guard store.history.count >= FoodStateDocument.maximumHistoryDays,
      let oldest = store.history.last?.dateKey,
      let date = FoodDateKey.date(from: oldest, calendar: calendar) else { return true }
    return calendar.compare(displayedMonth, to: date, toGranularity: .month) == .orderedDescending
  }

  var body: some View {
    let cells = HistoryCalendar.cells(month: displayedMonth, now: Date(), records: store.history,
      selectedDate: store.selectedDate, selectedCounts: store.visibleCounts, calendar: calendar)
    ZStack {
      SkinWorldBackground(skin: store.selectedSkin, allowsMotion: false)
      ScrollView {
        VStack(alignment: .leading, spacing: 20) {
          HStack {
            monthButton("chevron.left", label: "Previous month", enabled: canMoveBackward) { moveMonth(-1) }
            Text(displayedMonth.formatted(.dateTime.month(.wide).year()))
              .font(.system(.title3, design: .rounded, weight: .bold))
              .frame(maxWidth: .infinity)
            monthButton("chevron.right", label: "Next month",
              enabled: !calendar.isDate(displayedMonth, equalTo: Date(), toGranularity: .month)) { moveMonth(1) }
          }
          .foregroundStyle(design.palette.ink)

          ViewThatFits(in: .horizontal) {
            calendarGrid(cells: cells).frame(minWidth: dynamicTypeSize.isAccessibilitySize ? 490 : 308)
            ScrollView(.horizontal) {
              calendarGrid(cells: cells).frame(width: dynamicTypeSize.isAccessibilitySize ? 490 : 308)
            }
          }

          VStack(alignment: .leading, spacing: 10) {
          Text(HistoryProjection.checkInSummary(loggedDays: cells.compactMap(\.day).filter { $0.counts.total > 0 }.count))
            .font(.subheadline)
            .foregroundStyle(design.palette.ink)
          Text("Each little blob is a day. Tap one to take a closer look or add a colour.")
            .font(.subheadline)
            .foregroundStyle(design.palette.mutedInk)
          Text("Food Blob keeps up to 180 days on this iPhone.")
            .font(.footnote)
            .foregroundStyle(design.palette.mutedInk)
          }
          .padding(16)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(design.palette.raised.opacity(0.92), in: RoundedRectangle(cornerRadius: 20))
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 14)
        .padding(.bottom, 64)
      }
      .scrollIndicators(.hidden)
    }
    .sensoryFeedback(.selection, trigger: selectedDate)
    .navigationTitle("History")
    .navigationBarTitleDisplayMode(.inline)
    .toolbarBackground(.hidden, for: .navigationBar)
    .toolbarColorScheme(store.selectedSkin == .shrine ? .dark : .light, for: .navigationBar)
    .navigationDestination(item: $selectedDate) { date in
      if reduceMotion {
        DayEditorView(title: date.formatted(.dateTime.month(.wide).day()), showsSelectedDateInTitle: true)
      } else if #available(iOS 18.0, *) {
        DayEditorView(title: date.formatted(.dateTime.month(.wide).day()), showsSelectedDateInTitle: true)
          .navigationTransition(.zoom(sourceID: FoodDateKey.string(for: date, calendar: calendar), in: portraits))
      } else {
        DayEditorView(title: date.formatted(.dateTime.month(.wide).day()), showsSelectedDateInTitle: true)
      }
    }
  }

  private func calendarGrid(cells: [HistoryCalendar.Cell]) -> some View {
    LazyVGrid(columns: columns, spacing: 7) {
      ForEach(0..<7) { index in
        Text(calendar.veryShortStandaloneWeekdaySymbols[(calendar.firstWeekday - 1 + index) % 7])
          .font(.caption.weight(.semibold))
          .foregroundStyle(design.palette.mutedInk)
          .frame(maxWidth: .infinity, minHeight: 24)
          .accessibilityHidden(true)
      }
      ForEach(cells) { cell in
        if let day = cell.day {
          Button {
            store.selectDate(day.date)
            selectedDate = day.date
          } label: {
            HistoryDayCell(date: day.date, counts: day.counts, skin: store.selectedSkin)
              .foodPortraitSource(id: day.dateKey, namespace: portraits)
          }
          .frame(maxWidth: .infinity, minHeight: 83)
          .contentShape(Rectangle())
          .buttonStyle(LiquidButtonStyle(tint: design.palette.green, kind: .card, cornerRadius: 16))
          .accessibilityIdentifier("history-day-\(day.dateKey)")
        } else {
          Text(cell.number.map(String.init) ?? "")
            .font(.caption)
            .foregroundStyle(design.palette.mutedInk.opacity(0.38))
            .frame(maxWidth: .infinity, minHeight: 83, alignment: .top)
            .padding(.top, 7)
            .accessibilityHidden(true)
        }
      }
    }
    .background(design.palette.raised.opacity(0.76), in: RoundedRectangle(cornerRadius: 22))
  }

  private func monthButton(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Image(systemName: symbol).frame(width: 44, height: 44)
    }
    .buttonStyle(LiquidButtonStyle(tint: design.palette.green))
    .disabled(!enabled)
    .opacity(enabled ? 1 : 0.3)
    .accessibilityLabel(label)
  }

  private func moveMonth(_ delta: Int) {
    guard let month = calendar.date(byAdding: .month, value: delta, to: displayedMonth) else { return }
    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { displayedMonth = month }
  }
}

enum HistoryCalendar {
  struct Cell: Identifiable {
    let id: Int
    let number: Int?
    let day: HistoryDayProjection?
  }

  static func cells(month: Date, now: Date, records: [DayRecord], selectedDate: Date,
    selectedCounts: FoodCounts, calendar: Calendar) -> [Cell] {
    guard let start = calendar.dateInterval(of: .month, for: month)?.start,
      let range = calendar.range(of: .day, in: .month, for: start) else { return [] }
    let leading = (calendar.component(.weekday, from: start) - calendar.firstWeekday + 7) % 7
    var counts: [String: FoodCounts] = [:]
    for record in records { counts[record.dateKey] = record.counts }
    counts[FoodDateKey.string(for: selectedDate, calendar: calendar)] = selectedCounts
    let today = calendar.startOfDay(for: now)
    let oldestKey = records.count >= FoodStateDocument.maximumHistoryDays ? records.map(\.dateKey).min() : nil
    let cellCount = ((leading + range.count + 6) / 7) * 7
    return (0..<cellCount).map { index in
      let number = index - leading + 1
      guard range.contains(number), let date = calendar.date(byAdding: .day, value: number - 1, to: start)
      else { return Cell(id: index, number: nil, day: nil) }
      guard date <= today else { return Cell(id: index, number: number, day: nil) }
      let key = FoodDateKey.string(for: date, calendar: calendar)
      if let oldestKey, key < oldestKey { return Cell(id: index, number: number, day: nil) }
      return Cell(id: index, number: number,
        day: HistoryDayProjection(date: date, dateKey: key, counts: counts[key] ?? FoodCounts()))
    }
  }
}

struct HistoryDayProjection: Identifiable, Equatable {
  let date: Date
  let dateKey: String
  let counts: FoodCounts

  var id: String { dateKey }
}

enum HistoryProjection {
  static func checkInSummary(loggedDays: Int) -> String {
    "\(loggedDays) \(loggedDays == 1 ? "day" : "days") with a check-in"
  }

  static func make(
    now: Date,
    records: [DayRecord],
    selectedDate: Date,
    selectedCounts: FoodCounts,
    calendar: Calendar
  ) -> [HistoryDayProjection] {
    var countsByDateKey: [String: FoodCounts] = [:]
    for record in records {
      countsByDateKey[record.dateKey] = record.counts
    }
    let selectedDateKey = FoodDateKey.string(
      for: selectedDate,
      calendar: calendar
    )
    let today = calendar.startOfDay(for: now)

    return (0..<30).compactMap { offset in
      guard
        let date = calendar.date(
          byAdding: .day,
          value: -offset,
          to: today
        )
      else {
        return nil
      }
      let dateKey = FoodDateKey.string(for: date, calendar: calendar)
      return HistoryDayProjection(
        date: date,
        dateKey: dateKey,
        counts: dateKey == selectedDateKey
          ? selectedCounts
          : countsByDateKey[dateKey] ?? FoodCounts()
      )
    }
  }
}

private struct HistoryDayCell: View {
  let date: Date
  let counts: FoodCounts
  let skin: SkinID
  private var design: SkinDesign { skin.design }

  var body: some View {
    VStack(spacing: 1) {
      Text(date.formatted(.dateTime.day()))
        .font(.caption.weight(Calendar.autoupdatingCurrent.isDateInToday(date) ? .bold : .medium))
        .foregroundStyle(design.palette.ink)
      ZStack {
        if counts.total > 0 {
          FoodBlobView(counts: counts, skin: skin, showsTotal: false,
            showsUnitLabel: false, allowsIdleMotion: false)
        } else {
          Circle().fill(design.palette.mutedInk.opacity(0.2)).frame(width: 5, height: 5)
        }
      }
      .frame(height: 43)
      Text(counts.total > 0 ? "\(counts.total)" : " ")
        .font(.caption2.monospacedDigit())
        .foregroundStyle(design.palette.mutedInk)
    }
    .padding(.vertical, 7)
    .frame(maxWidth: .infinity, minHeight: 83)
    .contentShape(Rectangle())
    .background {
      if Calendar.autoupdatingCurrent.isDateInToday(date) {
        RoundedRectangle(cornerRadius: 16).fill(design.palette.surface.opacity(0.8))
      }
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(date.formatted(date: .complete, time: .omitted)), \(FoodCountText.offerings(counts.total))")
    .accessibilityHint("Opens this day for editing")
  }
}

private extension View {
  @ViewBuilder
  func foodPortraitSource(id: String, namespace: Namespace.ID) -> some View {
    if #available(iOS 18.0, *) { matchedTransitionSource(id: id, in: namespace) }
    else { self }
  }
}
