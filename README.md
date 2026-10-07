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

On iOS 17 or newer, Shortcuts offers **Log Food Offering** and **Get Today's
Food Mix** without opening the app. Log chooses Green, Yellow, or Red and has an
optional **Remove Instead** switch, off by default. Removing an empty colour does
nothing. The mix result exposes green, yellow, red, total, and the local date key
as individual fields; it includes pending widget actions and never reads history.
Siri phrases include “Log a green offering in Food Blob” (also yellow or red)
and “What's my Food Blob mix today”. Siri and personal automations use the user's
normal system setup; Food Blob requests no new OS access and schedules no actions.

Shortcuts writes append one UUID-tagged action to the existing widget ledger.
`FoodStore` reconciles it exactly once when the app next becomes active. The
widget count-change intent and persisted formats remain unchanged.

Food Blob has no account, analytics, ads, tracking, cloud sync, or paid
backend. Counts stay in each platform's local containers. The iPhone app and
its widgets share their App Group; the Watch app has a separate durable App
Group outbox. On Android, a signature-protected read-only provider lets a
separately installed Track app with the same trusted signing identity read
absolute daily counts; Food Blob itself still has no network access and Track
cannot change its data.

## Build

Apple requirements: Xcode 26.6 or newer, iOS 17 or newer, and watchOS 10 or newer.
The Watch app uses the single-target layout supported by Xcode 27. CI currently
selects Xcode 26.6 explicitly.
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

## Local test iteration

Use `make check-affected CHECK_BASE=origin/main` to print a JSON plan before a
local run. It includes changes since the merge base, staged and unstaged edits,
untracked files, and both sides of deletions/renames. Additional paths can be
supplied with `python3 scripts/affected_checks.py --path <path>`; these supplement
Git discovery. Missing history, unknown paths, and shared build configuration
select the full platform gates. Documentation still runs the cheap contracts
because some tests check documentation and platform resources.

Run each selected lane on its admitted fleet host:

```sh
# Portable work: quiet, admitted ger-z with JDK 17 and Android SDK 36.
make check-affected-run PLATFORM=portable CHECK_BASE=origin/main
# Ruby lint and affected Apple work: admitted M1 with its installed toolchain.
make check-affected-run PLATFORM=apple CHECK_BASE=origin/main
```

The Apple lane always includes the small Ruby workflow lint because the local
ger-z toolchain has no Ruby; native builds are selected only for affected Apple
paths. Run both planned lanes, or record the same lightweight lint separately.
Apple builds require Xcode, Metal and iPhone/Watch simulator runtimes.

The command does not select a host, reserve capacity, install dependencies, or
run device acceptance. Its JSON lists those acceptance entry points separately
when affected paths touch native surfaces. An affected run is iteration evidence;
run the unchanged `make check` once on the exact final head for delivery. Public
CI keeps its GitHub-hosted platform jobs; those jobs omit `project-check`, so
record `make project-check` separately for the final head when using CI as the
full gate. Reuse a verified passing final receipt while source and environment
stay unchanged.

For a test-first loop, `make android-unit` runs JVM tests and lint;
`make android-build-check` builds validation artifacts, and `make apple-check`
runs iPhone unit tests and the Watch build. `make android-check` retains one
Gradle invocation for all existing tests, lint and build variants. Gradle already
enables its build cache; retain task-owned build/dependency caches rather than
cleaning them between runs. `PYTHON` and `ANDROID_GRADLE_ARGS` Make overrides
are inherited by the selected lane; for example use
`ANDROID_GRADLE_ARGS=--max-workers=2` within an appropriate reservation. Do not run parallel Make targets against the same
Xcode project/DerivedData.

`android-check` compiles the Room/provider/Compose instrumented suites and
macrobenchmarks but does not execute them. Storage/schema/provider changes need
runtime acceptance on an owned synthetic emulator. `make android-device-test
DEVICE_SERIAL=<serial> TEST_SELECTOR=<class-or-class#method>` exposes the existing
device runner. It validates the completed instrumentation report and requires
at least one passed test; crashes, test failures, zero tests and all-skipped
runs fail even when adb exits successfully. Mixed runs report skipped counts. The real widget PendingIntent test requires the explicit
`isolatedWidgetAcceptance=true` instrumentation argument on an isolated emulator,
which the general personal-device wrapper does not pass. Optional capture and
performance tests also require their documented instrumentation arguments.

For native Apple gesture acceptance, prepare an owned synthetic simulator as
specified below, then run `make ios-acceptance ACCEPTANCE_SIMULATOR_ID=<id>`.
A simulator compile, a skipped acceptance test, and an executed gesture test are
different evidence. Preserve test counts and skip reasons in local receipts.

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
- Watch widget extension: `org.example.foodblob.watchkitapp.widgets`
- Watch App Group: `group.org.example.foodblob.watch`
- Android app: `org.example.foodblob`
- Android read-only provider: `org.example.foodblob.health_export`
