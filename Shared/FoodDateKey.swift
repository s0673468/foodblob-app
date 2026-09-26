import Foundation

enum FoodDateKey {
  static func string(
    for date: Date = Date(),
    calendar suppliedCalendar: Calendar = .autoupdatingCurrent
  ) -> String {
    let calendar = suppliedCalendar
    let components = calendar.dateComponents([.year, .month, .day], from: date)
    let year = components.year ?? 0
    let month = components.month ?? 0
    let day = components.day ?? 0
    return String(format: "%04d-%02d-%02d", year, month, day)
  }

  static func date(
    from dateKey: String,
    calendar suppliedCalendar: Calendar = .autoupdatingCurrent
  ) -> Date? {
    let parts = dateKey.split(separator: "-", omittingEmptySubsequences: false)
    guard parts.count == 3,
      let year = Int(parts[0]),
      let month = Int(parts[1]),
      let day = Int(parts[2])
    else {
      return nil
    }
    let calendar = suppliedCalendar
    guard
      let date = calendar.date(
        from: DateComponents(
          calendar: calendar,
          timeZone: calendar.timeZone,
          year: year,
          month: month,
          day: day
        )
      )
    else {
      return nil
    }
    let resolved = calendar.dateComponents([.year, .month, .day], from: date)
    guard resolved.year == year, resolved.month == month, resolved.day == day
    else {
      return nil
    }
    return date
  }

  static func startOfNextDay(
    after date: Date,
    calendar suppliedCalendar: Calendar = .autoupdatingCurrent
  ) -> Date {
    let calendar = suppliedCalendar
    let tomorrow =
      calendar.date(byAdding: .day, value: 1, to: date)
      ?? date.addingTimeInterval(86_400)
    return calendar.startOfDay(for: tomorrow)
  }
}
