---
name: kotlin-core
description: Writing or changing plain Kotlin/JVM code in Drivelog's car-api, core and the JVM-testable parts of plugins/id6 (trips, statistics, stores, parsers, texts), with its unit tests. Use for any logic that does not need Android classes.
---

# Kotlin on the JVM (car-api, core, plugin logic)

Drivelog keeps its logic in plain Kotlin modules so it can be unit-tested without a device. Android classes stay thin
and call into these modules. Kotlin 1.9.25, JVM target from Gradle, JUnit 4, **no libraries** (no coroutines,
kotlinx.serialization, Moshi, Mockito, AssertJ...).

## Where code goes

| What | Module |
|---|---|
| Neutral car values, ranges (`CarValues.checked()`), `PART_LABELS`, the tiny `Json` | `car-api/` |
| Trips, recorder, summaries, stores, backup, GPX/CSV, mileage and fuel logs, monthly stats, texts (`Lang`, `Polish`) | `core/` |
| Head-unit formats → `CarValues` (`Cds.kt`: `CdsProperty`, `CdsParser`, `FuelCounter`) | `plugins/id6` (JVM unit tests) |

`core` must not import `android.*`. If a function needs a `Context`, it belongs in `app/`; pass the data in instead.

## Project rules that apply here

- No car maker's name in identifiers, packages, comments or texts. Car-specific formats stay in `plugins/id6/Cds.kt`.
- Every UI string is `tr("English text")` / `trn(...)`; the texts are in `app/src/main/res/values/strings.xml` (English)
  and `values-pl/strings.xml` (Polish) under the same `t_…` / `p_…` key (AGENTS.md, Rules). Keep `{0}`
  placeholders in both. `LangTest` fails otherwise. A string with `\n` is a different text from one without. `core`
  never reads resources: it gets the texts through `Lang.texts` (the app: `ResourceTexts`; tests: `XmlTexts`).
- Renames keep old data readable (e.g. `Backup.OLD_FORMAT`). A stored JSON field that is renamed or removed still
  has to be read; new fields are optional with a default.
- `CarPart` in `core/car/OpenParts.kt` lists exactly `CarValues.PART_LABELS` (`CarStateTest`).
- `core` has one package per folder: `trip/`, `car/`, `map/`, `storage/`, `text/` (`dev.drivelog.core.trip`...). A
  new file goes into the folder of its subject; the package line matches the folder. The tests stay in
  `dev.drivelog.core` and import what they test.

## Good practices (follow them in new code even where older code does not)

### Types and nullability
- Prefer `val`, immutable `data class`es and read-only `List`/`Map` in APIs. Return new copies (`copy(...)`), don't
  mutate shared objects.
- **Avoid `!!`.** Use `?: return`, `?: error("why")`, `requireNotNull(x) { "why" }` or a smart-cast local `val`.
  (Cross-module properties don't smart-cast: copy them into a local first.)
- A missing value is `null`, not `0`, `-1` or `NaN`. Car values in particular: "not sent" ≠ "zero".
- Use `enum class` for closed sets of values (`TripKind`) and a `sealed class`/`sealed interface` when the cases
  carry different data. Avoid pairs of nullable fields plus a boolean that must agree.
- Validate input at the edges with `require(...)` (arguments) and `check(...)` (state); keep the inside free of
  defensive checks.
- Keep constants `const val` in a companion or object; no magic numbers in the middle of a formula. Name the unit
  in the identifier (`distanceKm`, `durationMs`, `fuelL`, `atMs`).

### Numbers, time, text
- `java.time` only. Pass the `ZoneId` (and a `Clock` or "now" value) in as parameters instead of reading
  `ZoneId.systemDefault()` / `System.currentTimeMillis()` deep inside logic; tests pass `ZoneOffset.UTC`.
- Formatting for files and machines uses `Locale.ROOT`; formatting for people uses the app's language
  (`Lang.code`) and goes through the UI layer.
- Compare doubles with a tolerance; in tests use `assertEquals(expected, actual, 1e-9)`.
- Use `Charsets.UTF_8` explicitly when reading/writing bytes. CSV for spreadsheets gets a BOM.
- Guard divisions (`if (km < 5) null else ...`), and clamp estimates to sane ranges (`coerceIn`).

### Files and stores
- Write files atomically: write `name.tmp`, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` with a
  fallback when atomic moves are not supported. Several stores repeat this block: put new ones behind one shared
  helper instead of copying it again.
- A corrupt or missing file is not a crash: return an empty value and keep the rest of the data.
- Close everything with `use { }`.
- Stores keyed by car address compare addresses case-insensitively (`lowercase(Locale.ROOT)`).
- Never catch `Exception`/`Throwable` to hide bugs. Catch the specific `IOException` or `Json.JsonException` you expect.

### Functions and objects
- Prefer pure functions (`object MonthlyStats { fun of(...) }`) that take everything they need. Side effects (files,
  clock) at the edges.
- Keep functions short; a `when` over an enum must be exhaustive without `else` so new cases fail to compile.
- Use `internal` for helpers that only tests and the module need; keep the public API small.
- Doc comments (`/** */`) on public types and on anything with a non-obvious unit, range or rule. Explain *why*,
  not what the code says.

### SOLID (where it pays off)

The stores have small interfaces in `core/storage/Stores.kt` (`TripLoader`, `TripLister`, `TripStore`, `CarDetailsStore`,
`CarEventsStore`, `FuelStore`, `LastReadingStore`, `PerCarData`); the `File...Store` classes implement them. Logic takes
the narrowest one it needs (`CarHealth.batteryHistory` takes a `TripLoader`). Keep it that way for new code.

- **S - single responsibility.** One reason to change per class/object: `FuelLog` computes, `FileFuelStore` stores,
  `MileageLog.csv` formats. Don't let a store compute statistics or a calculator read files. A file over ~300 lines
  or a class whose name needs "And" is a hint to split it.
- **O - open/closed.** Add behaviour by adding a case, not by editing many `if`s: an `enum` with its own properties
  (`TripKind.label`), a `sealed` hierarchy with an exhaustive `when`, or a small strategy passed in (e.g. how
  consumption is estimated: tank level, counter, car average). New `CarValues` fields are optional so old code keeps
  working unchanged.
- **L - Liskov substitution.** Any implementation of a store or parser keeps the contract of its type: same nulls,
  units, ordering ("newest first"), case-insensitive car keys and "missing file = empty". A test fake must behave
  like the real store, or tests prove nothing.
- **I - interface segregation.** When you introduce an interface, make it small and shaped by its caller:
  `interface FuelEntries { fun list(car: String): List<FuelEntry> }` for a reader, a separate writer if needed, not
  one `Storage` with twenty methods.
- **D - dependency inversion.** Logic depends on what it needs, passed in: data (`List<TripMeta>`), small
  interfaces (a store), functions (`clock: () -> Long`, as in `CarState`) and `ZoneId`. It never constructs files,
  clocks or Android objects itself. Constructors with default values keep call sites short
  (`CarState(clock = System::currentTimeMillis)`). No service locators or global mutable singletons for dependencies.

Don't add an interface for a class with one implementation and no test that needs a fake: Kotlin functions and
data are often the simpler abstraction.

### Tests (JUnit 4)
- Every change in these modules comes with a test. Name tests as sentences
  (`fun findsRefuelsBetweenAndDuringTripsButNotOnesAlreadyLogged()`).
- One behaviour per test, with the edge cases: empty input, one element, the boundary value, unknown/`null` input,
  old stored format.
- Build test data with small local factory functions (see `LogsTest.summary(...)`), not huge literals.
- Temporary files: `Files.createTempDirectory(...)`, delete in `@After`/`finally`.
- No `Thread.sleep`, no real clock, no network, no device.

## Verify

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"
.\gradlew.bat :car-api:test :core:test :plugins:id6:testDebugUnitTest :plugins:ride:testDebugUnitTest
```

JDK 21, not 25 (Kotlin 1.9.25 crashes on JBR 25).
