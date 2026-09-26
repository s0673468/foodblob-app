# Food Blob

Food Blob is a small, local-only iPhone, Apple Watch, and Android product for
noticing the balance of a day without scoring it.

Tap a green, yellow, or red lens row to add an offering, or hold and drag a
colour into the blob. Use its round minus chip to remove one. The three counts mix into one living
puddle: its colour uses an OKLab weighted blend, its silhouette reshuffles on
every change, and its size grows gently with the day's total.

The blob starts as dense, opaque paint with rounded highlights. Settings offers
a Translucency slider from Paint (0%) to Jelly (100%), with a live preview of
the current day. The choice is saved locally and applies to that phone's app
and widgets. It does not change food records. Watch artwork uses the paint
finish; the phone's appearance slider is not synchronized to Watch.

An accepted tap sends a short coloured drop from the pressed control into the
blob. A held colour is only logged after a valid drop into the current day's
blob; releasing elsewhere leaves the food counts unchanged. Contact compresses
the soft edge, folds in a ribbon of pigment and lets the blob settle.
Rapid taps overlap without restarting earlier responses. Tap the blob to poke
it, or hold and stretch it for a softer pull and rebound. This is play only;
it never adds food. Settings offers a quiet, original
plop sound, off by default. The same feedback applies to every food colour.

Sky Meadow and Shrine are the two product skins. They change the surrounding
world, controls, and copy without changing counts or history. Sky
Meadow is a warm dawn field. Shrine is a sparse night ritual with a neutral
consecutive-day streak. Neither skin scores food
or treats red as failure.

The Home Screen gallery offers Sky Meadow and Shrine as separate widget choices,
so a widget does not have to match the skin selected in the app. Each fixed-skin
widget has two supported sizes. The small widget is add-only: a growing blob and
three coloured plus lenses. The medium widget places the growing blob beside
three add lenses with separate 44-point minus chips. Widget interactions use
explicit controls because iOS reserves long-press for editing. Widgets render
authoritative snapshots. iPhone uses native count transitions; Android uses
native control ripples. The app owns the flying drops, contact and idle motion.
The widget gallery intentionally exposes only these four skin-and-size
combinations.

Streaks remain an optional in-app reflection detail. Widgets show only the blob
and food controls.

The app and WidgetKit share the same puddle geometry and count-derived seed, so
the colour, silhouette, and growth agree after each timeline reload. Responsive
widget geometry keeps the artwork aligned with stable touch targets, including
compact sizes and long counts. Reduce Motion removes flight, physics and idle
loops, leaving brief count and colour confirmation.

The friendly face appears only on the app icon and onboarding welcome. The
logging blob remains faceless.

Today gives the jelly a reserved stage, a compact date header and a separate
count below the body. Early additions change its size clearly; larger totals
settle with more weight. A small greeting and free touch/stretch play give the
blob personality without adding food. History is a weekday-aligned month of
small day portraits; selecting one opens that date for editing. Theme previews
use the visible day's actual counts. The same optical direction continues into
the widgets, where totals sit below the body and action regions
stay fixed.

The Apple Watch app and its complications use the same three-category model.
Watch actions enter a durable local outbox first, remain usable while the phone
is unreachable, and reconcile idempotently with the iPhone app over
WatchConnectivity before acknowledged entries are removed.

On Android, app-icon shortcuts offer green, yellow, and red quick logging. A
shortcut opens a private confirmation surface before it writes, so merely
opening one cannot change the day.

## Privacy

Food Blob has no account, analytics, ads, tracking, cloud sync, or paid
backend. Counts stay in each platform's local containers. The iPhone app and
its widgets share their App Group; the Watch app has a separate durable App
Group outbox. On Android, a signature-protected read-only provider lets a
separately installed Track app with the same trusted signing identity read
absolute daily counts; Food Blob itself still has no network access and Track
cannot change its data.

## Build

Apple requirements: Xcode 26.6, iOS 17 or newer, and watchOS 10 or newer.
The Watch target uses the two-target WatchKit layout; Xcode 27 removed support
for that layout. CI selects Xcode 26.6 explicitly until the Watch target is migrated.
Android requirements: JDK 17 or newer and Android SDK 36.

```sh
make check
```

Build only the Android app and its validation artifacts with:

```sh
make android-check
```

Android architecture, signing, widgets, provider semantics, and device-test
commands are documented in [docs/android.md](docs/android.md).

The Xcode project is committed and can be regenerated deterministically:

```sh
make project
```

## Jelly rendering and interaction acceptance

The iPhone app uses a SwiftUI Metal colour shader for the domed jelly surface,
local contact dents, released ripples, and incoming colour. Android uses an
AGSL shader on API 33 and newer, with a normal-lit native mesh on API 26–32.
Both retain the count-derived outline, OKLab food mix, accepted offering
pipeline, and reduced-motion controls. WidgetKit and Android widgets use
bounded CPU-rendered static gel snapshots with adaptive numeral contrast.

Growth uses matching count milestones across platforms: two or three foods
leave a small jelly, while ten foods occupy more than three times its visible
area. Rounded base forms change from 0–10; later counts continue growing toward
a bounded size while blending through further forms. A stable food-mix seed
adds gentle variation without changing on redraw. Soft reflections and travelling
contact ripples give the paint a yielding surface; raising Translucency blends
in the jelly's real alpha and transmission.
Widgets share the settled silhouette, material, and growth. The jelly and its
shadow fit inside a reserved body area above a readable count footer; action
zones remain fixed as the body grows.

The optical polish pass keeps lighting coupled to touch: a bounded reflection
shift follows held pressure and release, while clearer shoulders and a soft
window highlight separate the tinted body from its thin edge. Empty History
days use quiet unshaded markers; skin cards accommodate larger text, and
Android exposes skin selection and the complete sound row as accessible controls.

Food pigments and glass controls use a richer shared palette with dark Meadow
ink. A short compression and contained release light connects calendar cells,
skin choices, settings, navigation and food controls. Accepted additions carry
a stretched drop, returning landing beads and a soft travelling light through
the jelly. These finite effects leave the soft touch contour unchanged and
stop under Reduce Motion. Widgets share the richer static material, with unchanged action geometry and
authoritative count transitions. Android adds a native held light and ripple;
WidgetKit retains its proven serialization-safe button labels.

Rendering work stays in presentation code. iOS reuses settled shader radii and
prepares contact fields once per frame. Android evaluates the contour derivative
directly, reuses uniform buffers, and avoids temporary mesh paint lists. Widget
renderers interpolate a small per-render optics table instead of repeating colour
absorption exponentials per pixel. These are CPU/GPU work reductions, not a
promise of a measured device frame rate. Use physical hardware for macrobenchmark
frame timing and tactile acceptance; widgets remain static snapshots.

Xcode installations with a separately distributed Metal compiler need the
Apple component before building the app:

```sh
xcodebuild -downloadComponent MetalToolchain
```

The optional `FoodBlobAcceptance` scheme runs the normal app through colour
adds, press, stretch, and undo on an **owned synthetic iPhone simulator** with
onboarding already completed. The scheme explicitly enables its test-runner
flag; the tests skip on physical devices and restore the starting food counts.
It is separate from the normal unit-test scheme. Record the simulator while it
runs to inspect held and moving frames; screenshot attachments capture the
states after gestures finish.

```sh
xcodebuild -project FoodBlob.xcodeproj -scheme FoodBlobAcceptance \
  -destination 'platform=iOS Simulator,id=<owned-synthetic-simulator-id>' \
  -derivedDataPath /tmp/foodblob-acceptance CODE_SIGN_IDENTITY=- test
```

## Source and distribution boundary

This standalone source uses example application identifiers and unsigned simulator
validation. It contains no account credentials, personal food records, store
screenshots, TestFlight automation, or original Git history. Choose your own
bundle identifiers and signing team for a separate installation. Existing app
data and signature-protected integrations belong to the original installation.

Signing, distribution, and App Store delivery remain private operator tasks.
No self-hosted runner is attached to this component. Public contribution tests
use standard GitHub-hosted runners, a read-only token, and no artifact upload.

## Product identifiers

- iPhone app: `org.example.foodblob`
- iPhone widget extension: `org.example.foodblob.widgets`
- iPhone App Group: `group.org.example.foodblob`
- Watch app: `org.example.foodblob.watchkitapp`
- Watch app extension: `org.example.foodblob.watchkitapp.watchkitextension`
- Watch widget extension: `org.example.foodblob.watchkitapp.widgets`
- Watch App Group: `group.org.example.foodblob.watch`
- Android app: `org.example.foodblob`
- Android read-only provider: `org.example.foodblob.health_export`
