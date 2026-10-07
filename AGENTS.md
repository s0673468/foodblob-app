# Food Blob agent guide

Food Blob ships native iPhone, Apple Watch, and Android products. The Apple
surfaces use SwiftUI and WidgetKit; Android uses Kotlin, Jetpack Compose, Room,
and Glance.

- Keep it local-only. Do not add accounts, analytics, ads, tracking, cloud
  storage, or a backend.
- `FoodStore` is the sole mutation boundary.
- The iPhone app and its WidgetKit extension share only the App Group
  `group.org.example.foodblob`.
- The Watch app keeps a durable local outbox in
  `group.org.example.foodblob.watch` and reconciles it with the iPhone app over
  WatchConnectivity. Never treat the paired phone as synchronously available.
- Android is local-only too. Its signature-protected provider is read-only;
  Track may import absolute counts but must never mutate Food Blob state.
- Widget interactions use App Intents and the append-only ledger. Never mutate
  an app-owned history file directly from WidgetKit.
- The iPhone Shortcuts surface has only Log Food Offering and Get Today's Food
  Mix. External writes append UUID-tagged widget ledger entries; queries expose
  only today, including pending actions. Keep Siri/automation activation user-driven
  and do not add OS access requests or automatic meal actions.
- Widget long-press and horizontal swipes belong to the Home Screen. Widgets
  use explicit add and minus App Intent zones.
- Sky Meadow and Shrine are the only product skins. Unknown and retired skin
  identifiers decode to Sky Meadow without losing counts or history. Fixed-skin
  widget kinds let either world be chosen independently of the in-app skin.
- Blob material defaults to opaque paint. The device-local translucency preference
  changes rendering only and applies to the phone app and its widgets. Refresh
  the fixed widget kinds after a material adjustment finishes; keep this setting
  separate from food records, the ledger, exports, and Watch synchronization.
- The current interactive widget designs are an add-only small widget and a
  medium combined widget with separate minus chips. The display-only small blob
  and older medium kinds remain decodable compatibility identifiers but are not
  registered in the widget gallery.
- Preserve all eleven historical layout raw identifiers and stable WidgetKit
  kind strings for decoding. Today-count changes and deletion reload the two
  fixed-skin widget timelines. App-only appearance and past-day count changes
  still publish the current snapshot but skip the timeline reload.
- Keep widget artwork and App Intent hit zones in the same shared geometry.
- Widgets never render the in-app streak number or flame.
- The medium widget uses full-row add zones and separate 44-point minus zones.
  Keep those zones aligned with the visible lens rows. The small widget has only
  three add zones and no decrement control.
- Grow the puddle only inside reserved layout space. Growth must never move,
  resize, overlap, or otherwise change App Intent hit geometry.
- Press and growth feedback is presentation-only and may reflect effective
  counts from pending ledger actions. The append-only ledger and reconciled app
  state remain authoritative; animation must never imply an unrecorded write.
- Preserve accessibility labels and actions. If the small widget omits
  visible category text, keep its stable green/yellow/red order and explicit
  VoiceOver labels.
- The living puddle is shared by the app, widgets, and History. Its colour is
  the existing OKLab mix; its resting shape is a pure function of counts and a
  derived tap seed. Widgets remain static snapshots.
- Gate idle, squash, paint, jelly, halo, cloud, and mote motion with Reduce
  Motion. Haptics remain light for add and medium for remove.
- Keep the mascot face on the app icon and onboarding welcome only. The logging
  blob, History blobs, and widgets stay faceless.
- Add or update focused coverage for testable behavior changes. Follow
  `~/.config/agent-policy/git-golden-standard.md` for change tiers, local
  validation cadence, review, and delivery. CI runs the full `make check` gate;
  run affected platform tests locally and the full local gate when the shared
  tier or integration/debugging risk requires it.
