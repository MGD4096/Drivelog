# AGENTS.md

Guide for coding agents working on **Drivelog**: an Android trip log that records trips together with the car's own
values (odometer, fuel, range, consumption, temperatures, open doors/windows). The car values come from separate
**car plugin apps**; the first one, **Drivelog ID6**, reads a BMW with an NBT Evo or EntryNav2 head unit through
an iDrive plugin that runs inside the head unit. The maintainer writes in Polish; code, comments and README are English.

## Layout

| Path | What | Kind |
|---|---|---|
| `car-api/` | `CarValues` (the neutral car values, `checked()` ranges, `PART_LABELS`) and `Json` (tiny reader/writer) | Kotlin/JVM |
| `plugin-api/` | the Drivelog ↔ plugin contract: AIDL `ICarPlugin` / `ICarDataSink`, `CarPluginContract` (actions, meta-data keys, states, `API_VERSION`), `CarValuesBundle`, the `signature` permission in its manifest | Android library |
| `core/` | Drivelog's logic: trips, recorder, statistics, storage, backup, GPX/CSV, reminders, map maths, weather, texts (`Lang.kt`, `Polish.kt`) | Kotlin/JVM |
| `app/` | Drivelog (`dev.drivelog`): `TripService`, Bluetooth, screens, `CarPluginLink.kt` (finds plugins, checks signature, binds) | Android app |
| `plugins/id6/` | Drivelog ID6 (`dev.drivelog.id6`): `Cds.kt` (BMW CDS formats → `CarValues`), `Bridge.kt` (TCP client of the head-unit plugin), `CarWifi.kt`, `Id6PluginService.kt`, `InfoActivity` (no launcher icon) | Android app |
| `headunit/` | DrivelogBridge: iDrive PluginApp in C++98 for NBT Evo (QNX 6.5) and EntryNav2 (Linux ARMv7hf); serves 13 CDS values on TCP 7777 as JSON lines | C++ |

Data path: car CDS → `headunit/` (TCP 7777, JSON lines) → `plugins/id6` (BMW formats → `CarValues`, sent as a
`Bundle` over AIDL at most once a second, on change) → Drivelog `CarState` → trip recorder / screens.

Background knowledge about the head units (CDS property formats, framework ABI, measurements):
`C:\Dev\BMW_Navi\Drivelog_agent\docs\` (Polish) and the sibling projects `C:\Dev\BMW_Navi\HU_Tools\CdsScan`,
`C:\Dev\BMW_Navi\NBTEVO_GAUGE_APP`.

## Commands

Environment: Windows, PowerShell, Git Bash, Docker Desktop. No Python, g++ or Java on the PATH.

- **JDK 21, not 25:** Kotlin 1.9.25 crashes on Android Studio's JBR 25 (`IllegalArgumentException: 25.0.4`).
  `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"` before `gradlew.bat`.
- All unit tests (always before finishing a Kotlin change):
  `.\gradlew.bat :car-api:test :core:test :plugins:id6:testDebugUnitTest`
- Both APKs: `.\gradlew.bat :app:assembleDebug :plugins:id6:assembleDebug`
- Head-unit plugin, built for ARM and run under qemu against a fake framework (always after a `headunit/` change):
  `bash headunit/toolchain/check.sh` (Git Bash; uses the Docker images `gauge-entrynav2-tc` / `entrynav2-runner`,
  or `drivelog-*` built from `headunit/toolchain/`).
- EntryNav2 package: `cd headunit && bash toolchain/run.sh tc make entrynav2-dist` (needs the car's libs in
  `headunit/lib/entrynav2/`). NBT Evo: `headunit\build_nbt.bat` (needs QNX SDP 6.5; not available on this PC).
- Text/image database of the head-unit plugin (after editing `headunit/make_db.py`):
  `cd headunit && bash toolchain/run.sh runner make db`. The zips are committed.

## Rules

- **Drivelog knows nothing about the car's make.** Head-unit formats, CDS names and calibration belong in the plugin
  (`plugins/id6/Cds.kt`). Drivelog only sees `CarValues`.
- **No car maker's name in the code** (identifiers, packages, module names, UI texts, comments): packages are
  `dev.drivelog.*`, the plugin is `plugins/id6` / `dev.drivelog.id6` / "Drivelog ID6", plugin id `"id6"`. The one
  exception is the C++ `namespace bmw` / `bmw::` symbols in `headunit/`: they must match the framework's exported
  (mangled) names. The READMEs may name the cars the plugin works with.
- **Renames keep old data readable:** backups named `idrive-trips-backup` (before the rename) are still imported
  (`Backup.OLD_FORMAT`).
- **Changing the contract** (`car-api`, `plugin-api`): new optional `CarValues` fields / `Bundle` keys / states are
  fine without a version bump (unknown keys are ignored). Raise `CarPluginContract.API_VERSION` only for changes the
  other side cannot ignore, and update both apps. Open parts must be labels of `CarValues.PART_LABELS`; `CarPart`
  in `core/Vehicle.kt` must list exactly the same (`CarStateTest` checks it).
- **New CDS property:** add it to `kProperties` in `headunit/src/bridge_core.hpp` *and* `CdsProperty` in
  `plugins/id6/Cds.kt` (`PluginSourceTest` fails when they differ), add a stub to `kStubs` in
  `drivelog_bridge.cpp` (one per property; a compile-time check counts them), the parsing in `CdsParser`, and the
  host test's expectations (`props`, value counts, screen texts in `make_db.py`). No personal data (GPS, contacts, VIN, addresses)
  without the maintainer's explicit decision.
- **Texts in Drivelog:** every UI string goes through `tr("English text")` / `trn(...)`; add the Polish one to
  `core/Polish.kt` (`LangTest` fails on a missing translation or a lost `{0}` placeholder). The plugin app uses Android
  string resources (`values/`, `values-pl/`).
- **No libraries** beyond the Android framework and JUnit. Logic that can be plain Kotlin goes into a JVM module with
  unit tests; Android classes stay thin.
- **Security:** Drivelog accepts a plugin only if its service is protected by `dev.drivelog.permission.CAR_PLUGIN`
  and it is signed with Drivelog's key (`CarPluginRegistry`). Keep both checks. Both apps are signed with the debug key
  (also for `release`).
- **Head-unit code** runs inside the car's HMI: an error takes down screen, radio and navigation. C++98 only (QNX GCC
  4.4, EntryNav2 GCC 4.6: no `auto`, lambdas, `nullptr`, `std::thread`), no blocking in CDS callbacks or the HMI
  thread, non-blocking sockets in the network thread, keep the crash guard and `app.disable`. Read only: never call
  `cdsSetPropertyAsync`. Check every new framework symbol is exported by both units' `libAppFramework.A.so`.
  Result must need only `GLIBC_2.4` (the Makefile checks).
- **Never commit** the cars' `libAppFramework.A.so` / `libCoreFramework.A.so` (BMW files; `headunit/lib/` and
  `*.A.so` are git-ignored), build output (`build/`, `.gradle/`, `.idea/`, `local.properties` are git-ignored and
  untracked), or APKs.
- **Commits:** only when the maintainer asks. They usually review first.

## Gotchas

- Under qemu on a Windows bind mount, 32-bit `stat()` fails with `EOVERFLOW`: the head-unit host test keeps its data
  folder in the container's `/tmp`. `docker cp` cannot copy symlinks to Windows: the ARM sysroot travels as a tar.
- In Kotlin, `Socket().apply { connect(InetSocketAddress(host, port)) }` reads `Socket.port` (0), not an outer `port`.
- Android launchers: a plugin without a launcher icon may still get a stand-in "App info" icon on Android 10+
  because it requests permissions; that is expected.
- Not verified anywhere yet: the apps on a real phone (plugin discovery, binding, values arriving) and anything in a
  real car (NBT Evo build, head unit's Wi-Fi address, whether the hotspot is on without wireless Android Auto).
  Say so when a change depends on them.
