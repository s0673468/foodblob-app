import AppIntents

// Widget App Intents normally run in the extension. This app-only conformance
// asks the system to run the intent in the background host-app process on
// iOS 17+, without presenting UI or requesting foreground continuation.
@available(iOS, introduced: 17.0, deprecated: 26.0)
extension ChangeFoodCountIntent: ForegroundContinuableIntent {}
