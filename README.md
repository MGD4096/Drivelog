# Drivelog

An Android app that records your trips together with the data your car reports: odometer, fuel level, range,
average consumption, temperatures, and which doors, windows or roof are open. The car values come from a **car
plugin**: a separate app on the phone. The first one is **Drivelog ID6**, for BMW with an **NBT Evo** or
**EntryNav2** (EntryEvo) head unit. A car without a plugin works too (odometer typed in by hand). Weather, news, fuel
prices and traffic from the car are deliberately left out.

## How it works

```
 BMW head unit (NBT Evo / EntryNav2)        Phone
 ┌─────────────────────────┐  car's Wi-Fi  ┌──────────────────────┐  binder  ┌──────────────────────────┐
 │ DrivelogBridge          │  TCP 7777,    │ Drivelog ID6         │  (AIDL,  │ Drivelog                 │
 │ (iDrive plugin, CDS)    │ ────────────► │ BMW formats ->       │ ───────► │  • trips, GPS track      │
 │ headunit/               │  JSON lines   │ neutral CarValues    │  Bundle) │  • start/stop by the     │
 └─────────────────────────┘               │ plugins/id6/         │          │    car's Bluetooth       │
                                           └──────────────────────┘          │  • history, GPX/CSV      │
                                                                             └──────────────────────────┘
```

* **Three pieces.** `headunit/` reads the car inside the head unit ([`headunit/README.md`](headunit/README.md)).
  `plugins/id6/` is the phone app that connects to it over the car's Wi-Fi and knows everything about BMW's data.
  Drivelog (`app/`, `core/`) knows nothing about BMW: it only gets neutral values (`car-api/`, [`CarValues`](car-api/src/main/kotlin/dev/drivelog/carapi/CarValues.kt)).
* **Plugins are found, not built in.** A plugin app declares a service with the action `dev.drivelog.CAR_PLUGIN`
  (`plugin-api/`, [`CarPluginContract`](plugin-api/src/main/kotlin/dev/drivelog/pluginapi/CarPluginContract.kt)).
  Once installed it appears in Drivelog under car → **Edit** → **Plugin**. While a trip of that car runs, Drivelog
  binds the plugin, calls `start`, and gets the car's values (at most once a second, on change) and its state.
* **Only your own plugins.** The plugin service is protected by a `signature` permission and Drivelog also checks that
  the plugin is signed with its own key: another app cannot feed values into your log. Both apps are signed with the
  same (debug) key in this project.
* No AAIdrive, no OBD adapter, no change to the car's control units. Nothing writes to the car.
* In iDrive the head-unit part shows up as **DRIVELOG** in the ConnectedDrive menu.

### Writing another plugin

Depend on `:plugin-api`, declare the service as in `CarPluginContract`'s comment, return an `ICarPlugin.Stub` from
`onBind`, and on `start(sink)` send `CarValuesBundle.toBundle(values)` to `sink.onValues` whenever something changes
and your state to `sink.onStatus`. Open parts are the labels of `CarValues.PART_LABELS`. Sign it with Drivelog's key.
Optionally give it a screen: an activity with the action `dev.drivelog.CAR_PLUGIN_SETTINGS` (category DEFAULT,
protected by the plugin permission) is offered on the car's page, so the plugin needs no launcher icon.
`plugins/id6/` is the example ([`Id6PluginService.kt`](plugins/id6/src/main/kotlin/dev/drivelog/id6/Id6PluginService.kt)).

## What it does

* **Starts a trip by itself** when the phone connects to a car you chose (Bluetooth), and ends it when the car
  disconnects. A 90-second grace period turns a Bluetooth hiccup into one trip instead of two.
* **Records the GPS track** (about one point every few seconds while moving; standing still adds almost nothing) and the
  car values on the way.
* **Trip summary:** distance, duration, moving time, average and top speed, odometer start/end, fuel start/end/used,
  refuelling during the trip, consumption in L/100 km, and the car's own average.
* **Languages.** English and Polish (*Settings > Language*: phone language, English or Polski). All texts go through `tr("English text")` in `core/text/Lang.kt`; Polish is in `core/text/Polish.kt`, and a test fails when a text has no translation.
* **Dark theme.** *Settings > Theme*: follow the phone, light or dark. Colours come from `Colors.kt` by name, so every screen draws both themes (the map tiles are darkened with a colour filter too).
* **Backup.** *Settings > Data* exports everything (trips, cars, photos, service log, settings) to one ZIP file and imports it again, for example on a new phone. Importing replaces the current data, and a file that is no backup is refused before anything is touched.
* **Three tabs.** *Cars*, *Trips* and *Settings* (live status, permissions, reminder settings).
* **Cars:** a compact card per car: photo, model, odometer, **fuel and range** (live during a trip, otherwise as the car
  reported them at the end of its latest trip), trips and kilometres **this year**, **icon tiles for oil change,
  inspection and insurance** (the days left and, for oil, the kilometres left, coloured by how close they are), a **weather tile** at the top for where the phone is now (coarse location; the car-details screen shows the parking spot) (temperature, conditions, how it feels, wind), and a
  small map of where it is parked. Tap a card for the car's
  own screen.
* **Car screen:** a **photo gallery** (add several pictures at once, swipe through them, choose the main photo, delete), details with an
  **Edit car** command in the ⋮ menu (make and model, year, engine size, VIN, and whether the car uses a head-unit plugin: if it does you pick the plugin (only BMW for now), if it does not you type in the odometer by hand and the fuel is not shown; the name is the
  Bluetooth device's and cannot be changed), trip statistics with the **last trip** and a button to **all trips of this
  car**, a **doors and windows** card for cars with a plugin (a drawing of the car from above where what is open turns orange and open doors swing out, with the list of open parts; live during a trip, otherwise from the end of the latest trip), the parking spot on a map, **reminders**, and a **service log with costs**. The log takes technical
  inspections and insurance (with a valid-until date), oil changes (next change by date and/or at a kilometre
  reading), repairs and anything else, each with date, odometer, cost and a note. Costs are summed for this year and in
  total, in the currency of the phone's region. The VIN is checked for length and allowed characters only; the car does
  not report it to the phone.
* **Reminders and notifications:** an entry turns orange within the warning time (14 days, or 1,500 km for oil) and red
  when it has run out. A notification is posted once when an entry comes within the warning time and once more when it
  has run out. The check runs about once a day (Android decides the exact time) and whenever the app is opened. In
  **Settings > Reminders** you can switch notifications off and choose the warning time in days and kilometres.
* **12 V battery and tyre pressure** (cars with a plugin): the car's page shows the battery's charge, its history over
  the latest trips (the reading at the start of each trip, e.g. "97 → 95 → 86 %") and whether the car reports a tyre
  pressure warning. A notification comes once per trip when the car warns about tyre pressure, and once when the
  battery's start-of-trip charge drops below 75 % (an assumption: healthy cars showed 85–97 % in scans).
* **Trips:** all recorded trips in one list, newest first, with a one-line banner while a trip is running. Open a trip
  for details, **its route on a map**, **sharing as GPX or CSV**, or delete.
* **Map in the app:** open a trip and tap **Map**. The route is drawn over OpenStreetMap, coloured by speed (blue slow,
  green, yellow, red from 130 km/h), with a green start and a dark end marker. Drag to move, pinch or double-tap to zoom,
  buttons for zoom and "show the whole route". Tiles are cached on the phone (up to 64 MB, refreshed after 7 days), so
  routes you have looked at open without a network. The map needs internet the first time only.
* **Crash-safe:** the trip in progress is saved about once a minute; if the app is killed, the trip is recovered the
  next time a trip starts.
* Location permission is required: Android 14+ will not start a location-type service without it. If GPS simply has no
  fix (tunnel, garage), those points are missing but the odometer and fuel figures still work.

### Exports

* **GPX 1.1**: one `trkpt` per point with `<ele>`, `<time>` and an `<extensions>` block holding `dl:speed` (m/s),
  `dl:odometer` (km), `dl:fuel` (L) and `dl:outside` (°C).
* **CSV**: `time_utc,lat,lon,altitude_m,speed_kmh,accuracy_m,odometer_km,fuel_l,outside_c,inside_c`, one row per point.
  Car values carry forward from the latest reading. Decimal point, comma separator, UTC times.

### How the numbers are worked out

* **Distance** is the GPS distance (GPS jumps faster than about 300 km/h and drift while standing still are ignored).
  The odometer is used instead only when GPS clearly lost part of the trip (GPS under 80 % of an odometer distance of
  5 km or more) or there is no GPS at all. The odometer counts whole kilometres, so it is a poor choice for short trips.
* **Fuel used** is tank level at the start minus at the end, plus any refuelling in between (a rise of 5 L or more).
  The car reports whole litres, so a short trip can show 0 or 1 L. **L/100 km is shown only for trips of at least 5 km
  that burnt at least 1 L**; the car's own long-term average is always shown separately.
* Trips under 200 m are discarded.

## Set it up

**In the car (once):** build and install the head-unit plugin, see [`headunit/README.md`](headunit/README.md).

**On the phone:**

1. Install **Drivelog** and **Drivelog ID6** (both APKs from the build below; they must be signed with the same
   key). The plugin has no icon of its own: its screen (the car connection, whether Drivelog is installed) opens from
   the car's page in Drivelog, **Open Drivelog ID6**. On Android 10+ the launcher may still show a stand-in icon
   for it that only leads to Android's app info: Android does that for every app without an icon that asks for
   permissions (the plugin needs network access).
   **Coming from an earlier build** (package `dev.idrivetrips`, called "idrive-trips")? It is a different app for
   Android now: export a backup there (*Settings > Data*), install this one, import the backup, then uninstall the
   old one. Old backups are read as they are.
2. Open Drivelog. **Allow location, Bluetooth, notifications**, then **Allow location all the time**.
3. **Battery: set to unrestricted.** Android 12+ does not let an app start a foreground service from the background
   because of a Bluetooth event; apps the user has exempted from battery optimisation are allowed. Without this the
   app falls back to a notification, and one tap on it starts the trip.
4. Pair the phone with the car in Android's Bluetooth settings, then **Add a paired Bluetooth device** and pick the car.
5. Open the car → **Edit** → plugin on, **Drivelog ID6**. (Cars set up before plugins were separate apps already
   point to it.)
6. Drive. The phone must be on the car's Wi-Fi, as it is with wireless Android Auto. You can also use **Start trip
   now** to try it without Bluetooth.

## Build

Android Studio (or JDK 17–21 and the Android SDK, platform 35; Kotlin 1.9 does not run on JDK 25):

```
./gradlew :car-api:test :core:test :plugins:id6:testDebugUnitTest :plugins:ride:testDebugUnitTest   # all unit tests
./gradlew :app:assembleDebug :plugins:id6:assembleDebug :plugins:ride:assembleDebug             # Drivelog, Drivelog ID6 and Drivelog Ride
bash headunit/toolchain/check.sh                                       # the head-unit part, ARM under qemu (Docker)
```

| Module | What | Android? |
|---|---|---|
| `car-api/` | the neutral car values (`CarValues`) and the JSON reader/writer | no |
| `plugin-api/` | AIDL (`ICarPlugin`, `ICarDataSink`), `CarPluginContract`, `CarValuesBundle`, the signature permission | library |
| `core/` | trips, statistics, storage, GPX/CSV, texts: Drivelog's logic, unit-tested | no |
| `app/` | Drivelog: service, Bluetooth, plugin discovery and binding (`CarPluginLink.kt`), screens | app `dev.drivelog` |
| `plugins/id6/` | Drivelog ID6: BMW's CDS formats (`Cds.kt`), the head-unit connection (`Bridge.kt`, `CarWifi.kt`), the plugin service | app `dev.drivelog.id6` |
| `plugins/ride/` | Drivelog Ride: a motorcycle's odometer, fuel, range and tyre pressures, read from the BMW Motorrad Connected app's screens (accessibility service) after the ride | app `dev.drivelog.ride` |
| `headunit/` | DrivelogBridge, the iDrive plugin inside the head unit (C++) | – |

Versions: AGP 8.7.3, Kotlin 1.9.25, Gradle 8.9. Bump them if Android Studio asks.

## What has and has not been checked

Checked while writing it:

* All unit tests pass: `car-api` (neutral values, checks, JSON), `core` (including a simulated 15 km drive fed with
  neutral values: recorded, saved, read back, exported) and `plugins/id6` (BMW formats -> neutral values, the
  head-unit connection against a stand-in over a real socket: snapshot, changes, reconnect, silence, refusal;
  `PluginSourceTest`: the property list and port are exactly the head-unit plugin's).
* Both APKs build; the merged manifests carry the plugin action, meta-data, `<queries>` and the signature permission.
* **The head-unit part**, built with the EntryNav2 compiler, passes its host test under qemu, links against the
  EntryNav2 framework libraries, and every framework symbol it uses is exported by both the EntryNav2 and the NBT Evo
  libraries. Details and open points: [`headunit/README.md`](headunit/README.md).
* The map maths and the tile download and cache are unit-tested.

**Not checked:** the two apps installed on a real phone (finding the plugin, binding it, values arriving, the plugin's
process being restarted), how the map *looks* and feels, the NBT Evo build of the head-unit part (needs QNX SDP 6.5)
and anything in a real car, including the plugin finding the head unit on the car's Wi-Fi. The riskiest part on the
phone is Android's background-start rules (step 3 above); expect to tune that on your phone. If anything misbehaves,
look first at the status line of a running trip (it names the plugin and its state) and at the plugin's own screen.

## Known limits

* The connection between the head unit and Drivelog ID6 is not authenticated: anyone on the car's Wi-Fi can read
  the 15 car values, and a device posing as the head unit on a Wi-Fi the phone is on could put wrong numbers in your
  log. While a trip runs the plugin tries the gateway of every Wi-Fi the phone is on (port 7777), not only the car's.
* Plugins must be signed with Drivelog's key. A plugin from someone else cannot be used (on purpose).
* Car values are only as good as what the car reports with the ignition off or on; invalid values are treated as unknown.
* **Map tiles come from OpenStreetMap's own server**, as its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
  allows for light personal use: the app identifies itself with its own User-Agent, caches tiles for 7 days and shows
  the attribution. If you give the app to other people, use a tile provider meant for apps (change `TileProvider`) and
  put a way to reach you in the User-Agent.
* **The weather tile sends the phone's current position (or the parking spot) (rounded to about 1 km) to
  [Open-Meteo](https://open-meteo.com)**, which needs no account or key. It is free for non-commercial use (up to
  10,000 requests a day) and its data is CC BY 4.0, which is why the tile says "Weather data by Open-Meteo.com". A
  reading is reused for 30 minutes and kept on the phone, so it also shows (marked "Offline") for up to 6 hours without
  a network. If you do not want this, remove the `WeatherTile(...).here()` tile in `app/.../main/CarsScreen.kt`.
* No place names (coordinates only), no refuelling log, no cloud sync.

## Credits and licence

* **`headunit/`** is written for this project. How an iDrive PluginApp is loaded and which framework calls it makes
  (manifest, `pluginAppInfo`, the vtable slots, the CDS subscription, the Runnable stand-in) follow the documented,
  car-tested pattern of GaugeApp / CdsScan, which goes back to [nbt-aad](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto)
  (PolyForm Noncommercial 1.0.0); the BMW framework libraries are not included and must not be published.
* **`car-api/`, `plugin-api/`, `core/`, `app/`, `plugins/id6/`** are independent code. The car-data property names
  come from the head unit's CDS. The project has no licence file yet; add one before you publish it.

Not affiliated with BMW, MINI or Toyota. The plugin runs inside the head unit's software: use it at your own risk.
