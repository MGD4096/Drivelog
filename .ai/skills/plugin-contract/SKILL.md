---
name: plugin-contract
description: Changing the contract between Drivelog and car plugin apps (car-api CarValues, plugin-api AIDL ICarPlugin/ICarDataSink, CarPluginContract, CarValuesBundle) or adding a new car value end to end, from the head unit to Drivelog's screens.
---

# The Drivelog ↔ plugin contract

Data path: car CDS → `headunit/` (TCP, JSON lines) → `plugins/id6` (`Cds.kt` turns head-unit formats into
`CarValues`) → `Bundle` over AIDL, at most once a second and only on change → Drivelog `CarState` → recorder and
screens. Drivelog and the plugin are **separate APKs** that can be updated apart, so every change must work with an
older other side.

## Compatibility rules

- New **optional** `CarValues` fields, `Bundle` keys or states need no version bump: the other side ignores unknown
  keys and treats a missing key as "not sent" (`null`).
- Raise `CarPluginContract.API_VERSION` only for a change the other side cannot ignore (a meaning or unit changes, a
  key is removed, a call is added that must be answered), and update both apps.
- Never reuse a removed key with a new meaning. Never change a unit of an existing key: add a new key.
- AIDL: add methods only at the end of an interface. Never reorder, rename or change the parameters of an existing
  one, because the transaction codes are positional. Mark callbacks `oneway` where the caller must not wait.
- Values cross the boundary in the neutral format (km, litres, °C, %, km/h). The plugin converts units (mph → km/h,
  raw counters → litres); Drivelog never sees a car maker's format.
- `CarValues.checked()` drops values outside their plausible ranges. Give every new field a range and a test.
- Open parts are labels of `CarValues.PART_LABELS`, and `CarPart` in `core/car/OpenParts.kt` lists exactly the same.
- Two ways in: live during a trip (Drivelog binds `ICarPlugin`), and after it (`CarPluginContract.ACTION_READING`, a
  broadcast to Drivelog that only apps holding the plugin permission can send; `PluginReadingReceiver` keeps it as
  the latest values and, through `core/trip/LateReading.kt`, as the end of the newest trip when the time and the
  odometer fit). A plugin that only learns the values later (Drivelog Ride) sends its last reading at `start()`.

## SOLID in the contract

The contract is the project's clearest dependency-inversion boundary. Keep it that way.

- **D - dependency inversion.** Drivelog depends on the abstraction (`CarValues` + `ICarPlugin`/`ICarDataSink` in
  `car-api`/`plugin-api`), never on a plugin's classes. A plugin depends on the same abstraction, never on Drivelog's.
  Neither module of the contract depends on `core`, `app` or a plugin.
- **I - interface segregation.** Keep the AIDL interfaces small and one-purpose: a plugin offers values
  (`ICarPlugin`), Drivelog receives them (`ICarDataSink`). A new capability (e.g. a plugin's settings screen) gets its
  own optional interface or action, not new required methods that every plugin must implement.
- **L - Liskov substitution.** Every plugin behaves the same from Drivelog's side: neutral units, `null` for values
  it does not have, values within `checked()` ranges, no more than one update a second, and it survives being
  unbound at any time. A second plugin for another car must be usable without changes in Drivelog.
- **O - open/closed.** The contract grows by optional fields and keys; existing ones are never changed (see the
  rules above). Drivelog is extended with a new car by installing a new plugin, not by editing Drivelog.
- **S - single responsibility.** Car-specific conversion lives in the plugin (`Cds.kt`), transport in
  `CarValuesBundle`/AIDL, discovery and the trust checks in `CarPluginLink`/`CarPluginRegistry`, and what values mean
  for a trip in `core`.

## Security (keep both)

Drivelog binds a plugin only if its service is protected by the `signature` permission
`dev.drivelog.permission.CAR_PLUGIN` **and** the package is signed with Drivelog's key (`CarPluginRegistry`). Don't
weaken either check, and don't add an exported service without the permission. Treat everything that comes in over
binder as untrusted: check types and ranges, and never crash on a bad `Bundle`.

## Adding a new car value end to end

1. `car-api/CarValues.kt`: the field (nullable, unit in its name), its range in `checked()`, `toJson`/`fromJson`;
   a test in `car-api`.
2. `plugin-api/CarPluginContract.kt`: the `CarValuesBundle` key and both directions of the conversion.
3. If it comes from a new CDS property, follow the head-unit checklist in the `cpp98-headunit` skill
   (`kProperties`, `CdsProperty`, `kStubs`, `CdsParser`, host test, `make_db.py`). `PluginSourceTest` fails when the
   two lists differ.
4. `plugins/id6/Cds.kt`: the parsing into `CarValues`, with a test using real-looking raw values.
5. Drivelog: `CarState`/`TripRecorder` if trips should keep it, `LastReading.merge` so the latest value is stored
   apart from trips, the screens, and the texts via `tr()` + the string resources.
6. Decide what happens when the plugin does not send it (older plugin, other car): the screen hides it or falls back
   (e.g. the car's speed falls back to the phone's GPS).
7. No personal data (GPS, contacts, VIN, addresses) without the maintainer's explicit decision.

## Verify

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"
.\gradlew.bat :car-api:test :core:test :plugins:id6:testDebugUnitTest :plugins:ride:testDebugUnitTest :app:assembleDebug :plugins:id6:assembleDebug :plugins:ride:assembleDebug
```

```bash
bash headunit/toolchain/check.sh   # when headunit/ changed
```

Plugin discovery, binding and values arriving have not been verified on a real phone yet. Say so.
