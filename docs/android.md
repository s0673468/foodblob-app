# Food Blob for Android

Food Blob for Android is a native Kotlin and Jetpack Compose application. It
uses Room for local state and Glance for Home Screen widgets. The Android app
does not depend on the Apple targets, and adding it does not change the iOS,
Watch, or WidgetKit contracts.

## Build and test

The checked-in toolchain uses Android Gradle Plugin 9.1.1, Gradle 9.3.1, JDK
17 or newer, and Android SDK 36. On macOS, `scripts/android_gradle.sh` uses
`ANDROID_JAVA_HOME`, then `JAVA_HOME`, then Android Studio's bundled runtime.

Build the debug app:

```sh
scripts/android_gradle.sh :app:assembleDebug
```

Run the Android host tests, lint, release build, instrumentation-test build,
and macrobenchmark build:

```sh
make android-check
```

Run the complete Apple and Android repository gate:

```sh
make check
```

Run the Room, provider, accessibility, and Compose instrumentation tests on a
personal connected device with the checked-in data-preserving wrapper. It
builds both APKs, installs them with `adb install -r`, invokes
`AndroidJUnitRunner` directly, and removes only the test APK afterward. It
never uninstalls or clears `org.example.foodblob`; tests create and remove only
their own isolated database fixtures, and UI tests restore the original app
state:

```sh
scripts/android_device_test.sh <adb-serial>
```

Pass a fully qualified class or `class#method` as the optional second argument
for a focused hardware run. Reserve Gradle's `connectedDebugAndroidTest` task
for disposable emulators: Android Gradle Plugin owns that deployment lifecycle
and can remove the target APK when the task finishes. Android's official
[command-line testing guide](https://developer.android.com/studio/test/command-line)
documents direct `adb` execution for focused instrumented tests, and the
[AndroidJUnitRunner guide](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)
documents that `clearPackageData` is the state-clearing option.

Build and install the minified, non-debuggable internal release with the
data-preserving guard. The script verifies the exact device, refuses a
downgrade or signing mismatch, uses only `adb install -r`, and reads back the
version, signer, non-debuggable flag, and unchanged first-install time:

```sh
scripts/android_release_install.sh <adb-serial>
```

Check an already-built release APK against a connected device without building
or installing anything:

```sh
scripts/android_release_install.sh --preflight <adb-serial> /absolute/path/to/app-release.apk
```

Preflight reads device/package metadata and temporary copies of installed APKs
to verify the expected package, version, non-debuggable flag, and signing
compatibility. It runs no Gradle build, install, uninstall, data clear, or app
launch. Its temporary APK copies are removed on exit. A successful preflight
is a compatibility check; it does not install the update or verify app behavior.

The installer requires a trust anchor before any first install: an already
installed compatible Food Blob or Track APK, including an Android-accepted
signature-permission certificate lineage for the connected device API, or an explicit non-secret
`FOODBLOB_EXPECTED_SIGNER_SHA256` certificate digest. It fails closed when no
anchor exists, so a machine-local fallback signer can never silently establish
a new cross-app trust identity.

The macrobenchmark module targets the minified, non-debuggable `benchmark`
variant and measures cold start, warm start, and logging/navigation frame time
on real hardware. Its logging journey performs add, remove, undo, and undo so
the initial count is restored:

```sh
scripts/android_gradle.sh :benchmark:connectedBenchmarkAndroidTest
```

## Storage and crash behavior

`FoodStore` is the sole Android mutation boundary. Canonical days, settings,
undo records, pending widget events, and consumed-event receipts live in one
Room database using SQLite write-ahead logging.

Widget callbacks enter through an epoch-validated UUID action boundary. The
append and one bounded reconciliation batch commit in the same transaction;
the callback never overwrites a day directly. App mutations also reconcile
pending widget actions before applying their own change. The effective read
projection combines canonical rows and still-pending valid actions exactly
once, so a process death between a callback and redraw cannot lose or double an
action.

The committed Room schema is under `android/app/schemas`. Every future schema
change must add an explicit migration and a migration test. Destructive Room
migration fallback is intentionally not enabled. Missing or invalid migrations
fail closed and do not replace data. Only verified SQLite corruption or a
failed read-only `quick_check` permits recovery. A no-op SQLite corruption
handler prevents the platform from deleting the source. The database, WAL, and
shared-memory files move into a marked app-private no-backup bundle before Room
can create a replacement. An interrupted move resumes on the next process
start; conflicting live and preserved files fail closed. The Settings screen
reports the retained recovery bundle. Delete all removes only complete, known
recovery artifacts and leaves unrelated files untouched.

Effective snapshots are re-materialized in one Room transaction whenever any
participating table changes. Provider reads are side-effect-free, including on
a fresh store with no settings row. JSON export includes the selected skin,
historical layout identifier, absolute days, undo stack, and pending/recent
consumed widget event identifiers without consuming or rewriting them.
Consumed UUID receipts retain the newest 2,048 actions, bounding database size,
export memory, and startup integrity work while covering callback retries
across redraws and process death. Receipt compaction rotates the persisted
widget epoch before pruning, invalidating all older launcher tokens so removed
UUIDs cannot be replayed.

Day keys are recorded as local `yyyy-MM-dd` values at mutation time together
with the event's IANA zone. Existing actions do not move when the device zone
changes. Counts are nonnegative and saturate rather than wrapping on integer
overflow. History retains 180 days and undo retains 20 actions.

Settings can select a local JSON export for restoration. Food Blob strictly
validates the whole schema, dates, counts, enums, UUIDs, size, and retention
bounds before it shows an absolute summary. The user must explicitly confirm
replacement. The preview is cryptographically bound to that exact payload, and
the replacement happens in one Room transaction through `FoodStore`. An export
with still-pending widget IDs is rejected because the legacy export does not
contain enough action data to restore those writes honestly.

Immediately before replacement, Food Blob writes a complete rollback artifact
to its app-private no-backup directory using owner-only permissions, a synced
temporary file, atomic rename, and directory sync. Settings exposes that prior
state for an explicit rollback. A stale, changed, malformed, or partial artifact
fails closed. Delete all removes the known import artifact before deleting live
state, so a failed cleanup cannot produce a misleading partial reset.

## Home Screen widgets

The gallery exposes fixed Sky Meadow and Shrine widgets in two control styles:

- Quick Add defaults to a native Android 3×1 strip. It can resize into a 2×2 grid,
  a featured landscape card, or a tall stack while keeping green, yellow, and red
  in stable order.
- Full Controls defaults to native 4×2 columns with add above remove. At taller
  sizes it switches to roomier horizontal add/remove rows.

Every action target is at least 48 dp. Glance renders a static authoritative
snapshot; it does not run the app's idle animation. A widget refresh happens
only after its durable append succeeds. If a redraw is interrupted, the next
refresh reads the durable effective snapshot.

The puddle uses the same count-weighted mix and resting shape as iPhone.
Native rounded ripples confirm control presses. Short strips show each colour's
count even when there is no room for a puddle. Counts fit their reserved space;
changing a count never moves its touch target.

Settings can request placement of any of the four fixed variants directly through
the launcher. Android still owns the confirmation prompt, so Food Blob only says
that the request was sent; launchers that do not support it fall back to the manual
widget-picker instructions. No permission, account, or network access is involved.

The renderer uses the launcher's exact current dimensions. Artwork and Glance tap
regions consume the same geometry, and blob growth is clipped to its reserved area.
If a launcher briefly exposes a shape too small for three 48 dp controls, the widget
shows only one safe open-app region until it is resized.

Each rendered action carries a UUID in the data URI and the current reset epoch in an
explicit broadcast to a non-exported receiver. Android includes that URI in
`PendingIntent` identity, so a completed redraw gives every visible control a genuinely
new action. Delivery retries before redraw keep the same UUID and remain one idempotent
write. The receiver holds the broadcast lifetime until the atomic commit and launcher
redraw complete. Delete All rotates the epoch in the deletion transaction, so consumed
and never-fired old controls cannot restore a count. Legacy callbacks without an epoch
refresh every pinned widget without mutating. While a Glance session is alive, its composition observes the Room
snapshot flow rather than retaining its first snapshot; an explicit refresh signal also
re-evaluates the local day after clock or timezone changes. A private one-shot inexact
alarm schedules the next local midnight;
boot, manual clock changes, and timezone changes redraw and reschedule it. This
avoids relying on `ACTION_DATE_CHANGED`, which target-26+ manifest receivers do
not receive under Android's implicit-broadcast limits. The platform widget host
also requests its minimum 30-minute refresh interval as a fallback for delayed
background alarms; the app still redraws immediately after every durable
today-count write.

## Read-only Track contract

Food Blob exposes one Android `ContentProvider` for the separately installed
Track app:

```text
content://org.example.foodblob.health_export/v1/daily_counts
```

Access requires the signature permission
`org.example.foodblob.permission.READ_HEALTH_EXPORT`. The provider is exported,
does not grant URI permissions, has no write permission, and rejects every
mutation, file, batch, and `call` surface.

A query accepts only the exact URI and returns rows with these columns:

```text
date, green, yellow, red
```

The cursor extras are:

```text
schema_version = 1
snapshot_revision = opaque non-empty string
generated_at_epoch_ms = query generation time
```

Each cursor is one transactionally consistent absolute snapshot of canonical
days plus valid, unconsumed widget actions, deduplicated by event UUID. Querying
does not claim, consume, clear, repair, or rewrite Food Blob state. A successful
empty cursor is different from an absent or inaccessible provider.

Track must validate the whole cursor before importing any row. Provider
absence, permission failure, unknown schema, missing envelope data, malformed
or duplicate dates, negative counts, or IPC failure defer the entire read.
Track must never synthesize zero or delete a Health day because Food Blob is
unavailable. Repeated absolute snapshots are safe through Track's per-day
fingerprints and durable count outbox; an explicit zero row is the only zero
correction.

The private Room store is version 2. Its explicit v1-to-v2 migration preserves
all counts and settings while adding a widget action epoch. Delete All rotates
that epoch atomically, so a launcher callback rendered before the reset cannot
recreate deleted data. Every current widget callback also reconciles a bounded
batch in the same transaction, keeping widget-only use bounded without making
the read-only Track query consume or mutate anything.

Release Food Blob and Track APKs must use the same signing certificate or an
accepted signing lineage. Local shared signing is supported only through the
untracked `android/signing.properties` file. Its keys are `storeFile`,
`storePassword`, `keyAlias`, and `keyPassword`; never commit that file or the
keystore. When that file is absent, internal direct-install release and
benchmark variants intentionally use the existing local debug certificate. That
preserves upgrade and signature-permission continuity with a compatible existing
acceptance installation while still producing a minified, non-debuggable binary.
The guarded installer verifies it against every available installed trust anchor
and refuses an unanchored first install. This fallback is
not a Google Play signing plan; Play
publication and durable production-key custody remain out of scope.

Food Blob does not request `INTERNET`, provide a cross-app write surface, or
ship a Supabase, account, analytics, or advertising SDK. Backup and
device-to-device extraction rules exclude app state and recovery artifacts.

Track already implements the Android consumer in Kotlin and Dart. Its tests
cover provider identity and whole-snapshot validation, import idempotency and
missing-source behavior, and launch/resume orchestration. Food Blob's provider
tests independently prove its schema, atomicity, and write denial. A compatible
signed installation and runtime readback are still required before claiming
device-level cross-app Track-to-Supabase acceptance.

## Quick logging and Shelf

Android publishes three ordered dynamic app shortcuts: green, yellow, and red.
Each targets a non-exported, no-intent-filter confirmation activity inside Food
Blob. Opening a shortcut does not mutate state. A localized 48 dp-class action
must be pressed before an idempotent UUID action enters the same atomic local
mutation boundary as the app and widgets. Success and its light haptic appear
only after the write is durable; retries cannot add twice. Locked or failed
startup states leave counts untouched and show an honest failure.

A Quick Settings tile is deliberately not registered. One tile cannot express
Food Blob's three categories as a direct, predictable action; adding a cycling
or hidden-mode control would make quick logging easier to misrecord than the
three explicit launcher shortcuts.

The Shelf keeps its full 30-day calendar and adds a static seven-day pattern.
Sky Meadow grows calm sprouts; Shrine presents a sparse teal night ritual. The
scene uses only existing absolute counts, does not score or grade a day, has no
idle animation, exposes each date and exact total to accessibility services,
and adapts to compact, large-text, reduced-motion, and landscape layouts.

## Platform behavior

The app targets Android 16/API 36. It uses edge-to-edge content, adaptive
compact and expanded layouts, and explicitly enabled predictive back. Startup
opens and integrity-checks Room on the application I/O scope while the splash
remains visible; a non-corruption startup failure keeps data intact and shows a
closed recovery state. Compose motion follows the system animator duration
scale. Reduced motion removes paint flood, squash, jelly, tilt, idle atmosphere,
and animated onboarding paging. App haptics use platform feedback only after a
successful transaction; no vibration permission or fixed waveform is requested.

Today and day detail connect each accepted press to a 200 ms coloured drop from
that control, followed by a local dent, ripple, puff and mixed-colour fill.
Responses settle within 700 ms and overlap during rapid taps. Blob poke and
stretch only change presentation. Date, skin, layout and lifecycle changes
cancel stale effects. Food writes complete independently of animation.

Controls sit closer to the blob. Undo stays in the counter panel so add/remove
notifications do not cover another control. Settings includes an original quiet
plop, disabled by default and stored in local UI preferences. Reduced motion
keeps brief count/colour confirmation and still honours the sound preference.

Implementation choices follow the current Android guidance for
[edge-to-edge](https://developer.android.com/develop/ui/compose/system/setup-e2e),
[predictive back](https://developer.android.com/develop/ui/compose/system/predictive-back),
[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions),
[SQLite database opening](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase),
[corruption handlers](https://developer.android.com/reference/android/database/DatabaseErrorHandler),
[Glance widgets](https://developer.android.com/develop/ui/compose/glance),
[implicit broadcast limits](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions),
[inexact alarms](https://developer.android.com/develop/background-work/services/alarms),
[haptics](https://developer.android.com/develop/ui/views/haptics/haptic-feedback),
and [provider access control](https://developer.android.com/privacy-and-security/risks/access-control-to-exported-components).
