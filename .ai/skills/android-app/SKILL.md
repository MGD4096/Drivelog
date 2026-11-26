---
name: android-app
description: Writing or changing the Android layer of Drivelog (app/, dev.drivelog) or of the Drivelog ID6 plugin app (plugins/id6): activities and screens built in code, the trip service, Bluetooth, notifications, the home-screen widget, receivers, resources and the manifest.
---

# Android app code (app/, plugins/id6)

Kotlin, compileSdk/targetSdk 35, minSdk 26, **only the Android framework** (no AndroidX, no Compose, no
coroutines, no Material components). Screens are built in code (`LinearLayout`, `TextView`...) with the helpers in
`Ui.kt` and the colours in `Colors.kt`. Logic lives in the JVM modules (see the `kotlin-core` skill); Android classes
stay thin: read input, call `core`, show the result.

Files live in one package per feature folder (`main/`, `car/`, `recording/`, `trips/`, `map/`, `backup/`, `plugin/`,
`reminders/`; AGENTS.md lists them); put a new file in the folder of the feature it belongs to. The root package keeps
the shared pieces and the classes Android remembers by name: `App` and `MainActivity` (the launcher icon). The
home-screen widgets and their pickers live in the folder `widget/` but stay in package `dev.drivelog` (the one
exception to "package = folder"): placed widgets disappear when a provider's class name changes. Never move them to
another package or rename them; a new widget goes there too, in package `dev.drivelog`.
Other components may move, with their `AndroidManifest.xml` entry; anything Android persisted for them (a scheduled
job, a pending intent) must be replaced on the next start, as `ReminderScheduler.ensureScheduled` does.

## Project rules that apply here

- **Texts:** every UI string through `tr()` / `trn()`, the texts in `res/values/strings.xml` and `values-pl/strings.xml`
  (`t_…` / `p_…` keys, same English text as in the code; AGENTS.md, Rules). The plugin apps use
  string resources (`values/`, `values-pl/`) instead.
- **Drivelog knows nothing about the car's make.** It only sees `CarValues`; head-unit details stay in `plugins/id6`.
- **Security of plugins:** keep both checks in `CarPluginLink.kt` / `CarPluginRegistry`: the service must be protected
  by `dev.drivelog.permission.CAR_PLUGIN` *and* signed with Drivelog's key.
- **No personal data leaves the phone** without the maintainer's decision. Geocoding sends coordinates to the
  phone's location service; anything new of that kind needs a word in the README.
- Both apps are signed with the debug key, also for `release`. Never commit APKs or `local.properties`.

## Good practices (follow them in new code even where older code does not)

### Threads
- Nothing slow on the main thread: files, JSON of all trips, PDF, Geocoder, Bluetooth, binder calls that may block.
  Use the activity's single `io` executor (`Executors.newSingleThreadExecutor()`), then `runOnUiThread { if
  (isDestroyed) return@runOnUiThread; ... }`.
- Shut the executor down in `onDestroy()` (`io.shutdown()`), so no work outlives the screen.
- `BroadcastReceiver.onReceive` and `AppWidgetProvider.onUpdate` run on the main thread with a ~10 s limit: keep
  them short, or `goAsync()` and finish on a worker, always calling `pending.finish()` in `finally`.
- State shared with the trip service (`AppState`) must be thread-safe: immutable snapshots or `@Volatile`
  references to immutable objects, not mutable collections read from two threads.

### Components and the manifest
- `android:exported="false"` unless another app (launcher, system) must start it. An exported component validates
  everything in its `Intent` (e.g. a widget configure activity checks `EXTRA_APPWIDGET_ID`).
- `PendingIntent`: always `FLAG_IMMUTABLE` (unless the system must fill it in), and a **distinct request code** per
  target when extras differ. Otherwise `FLAG_UPDATE_CURRENT` overwrites the extras of the other one.
- Foreground services declare their `foregroundServiceType` and matching permissions (Android 14+). Start them
  only from allowed states.
- Ask runtime permissions (Bluetooth connect/scan, location, notifications) where they are needed and handle
  "denied" without crashing. Check `checkSelfPermission` before every call that needs one.
- Notifications: a channel per kind, created once; actions go to non-exported receivers.

### Lifecycle and state
- Never keep an `Activity` or `View` in an `object` or a static field; keep `applicationContext` if anything.
- Save what the person typed or picked in `onSaveInstanceState`, or rebuild it from storage in `onResume`.
- Listeners and periodic `Handler` callbacks registered in `onResume` are removed in `onPause`.
- `SharedPreferences`: `apply()`, not `commit()`, on the main thread. Use one file per concern (`settings`, `widgets`).

### UI built in code
- Sizes in dp/sp through `ui.dp(...)`; touch targets at least 48 dp.
- Light and dark: call `Colors.prepare(this)` before `super.onCreate`; take colours from `Colors`, not literals.
  Widgets and other launcher-drawn views use `values/` + `values-night/` colour resources instead.
- **Edge-to-edge:** with targetSdk 35, Android 15 draws behind the status and navigation bars. Apply window insets
  (`setOnApplyWindowInsetsListener`) to the top bar and the bottom content.
- Accessibility: `contentDescription` on icons that carry meaning (`importantForAccessibility="no"` on decoration);
  don't show a state by colour alone (icon or text too).
- Long lists: don't put hundreds of rows into a `LinearLayout` at once. Page them or use a `ListView` with a simple
  adapter.
- Dialogs: `AlertDialog.Builder(this)`; dismiss them in `onDestroy` if they may outlive the activity.

### Home-screen widget (RemoteViews)
- Only RemoteViews-supported views and methods (`setTextViewText`, `setImageViewResource`, `setInt(id,
  "setColorFilter", ...)`, `setViewVisibility`, `setTextColor`, `setContentDescription`).
- Bitmaps sent to the launcher stay small (the binder limit is ~1 MB for all of them together).
- Each widget id keeps its own settings and its own `PendingIntent` request code; remove them in `onDeleted`.
- `updateAppWidget` can throw when the launcher is gone: catch `RuntimeException` there only.

### SOLID (where it pays off)

Android classes are the hardest place to keep these. The pattern to follow: `CarActivity` loads the data and lays out
the page, and each part with dialogs of its own is a class (`CarPhotos`, `CarInfoDialog`, `CarEditor`,
`CarRecordActions`, `ServiceLogPanel`, `FuelPanel`), with shared views in `CarPageViews`. Stores come from `Stores`,
typed as the interfaces from `core`. `MainActivity` follows it too: tab bar,
lifecycle and loading, with `TripsTab`, `SettingsTab`, `PermissionsPanel`, `BackupFlow` and `TripStarter` as its parts.

- **S - single responsibility.** An activity wires things together and handles the lifecycle. A part of a screen
  with its own data and dialogs goes into its own class (as `FuelPanel`, `MileageExport`, `TripActions` already do).
  Formatting for people lives in `Ui`/`CarTexts`, calculations in `core`, file access in stores.
- **O - open/closed.** New kinds of things (a new service type, a new reminder, a new widget row) should be a new
  entry in a list or enum that the screen iterates over, not another `if` in five places.
- **L - Liskov substitution.** Subclasses of framework types (`View`, `AppWidgetProvider`, `Service`) honour the
  framework's contract: call `super`, respect measure/layout rules, return the right `onStartCommand` value, finish
  `goAsync()` results. Don't override to do nothing where the base class must run.
- **I - interface segregation.** A panel gets the few callbacks it needs (`onChanged: () -> Unit`), not the whole
  activity. Pass `Context` only where a `Context` is really needed; pass data otherwise.
- **D - dependency inversion.** Take stores from `Stores(context)` (the one place that knows the file paths) and pass
  them, typed as the `core` interfaces, into panels and helpers.
  Classes that use them don't know file names. This also makes it possible to test the logic on the JVM with a fake.

Keep it pragmatic: no dependency-injection framework (no libraries), no interface for something with one
implementation and no fake.

### Errors
- Catch the specific exception (`IOException`, `SecurityException`, `RemoteException`, `DeadObjectException`) where
  it can happen and tell the person in words (`Toast`, a line on the screen), never a stack trace.
- Binder calls to the plugin assume the other side can die at any moment.

## Verify

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"
.\gradlew.bat :car-api:test :core:test :plugins:id6:testDebugUnitTest :plugins:ride:testDebugUnitTest :plugins:ha:testDebugUnitTest :app:assembleDebug :plugins:id6:assembleDebug :plugins:ride:assembleDebug :plugins:ha:assembleDebug
```

Nothing here is verified on a real phone or car until someone tries it there. Say so when a change depends on the
device (widget on a launcher, Bluetooth, plugin binding, notifications).
