# Drivelog

An Android app that records your trips together with the data your car reports: odometer, fuel level, range,
average consumption, temperatures, and which doors, windows or roof are open. It is a small, English, car-data-only
companion for the iDrive NBT EVO plugin setup from [deathwalker80/idrive-plus](https://github.com/deathwalker80/idrive-plus)
(MINI F5x/F60, BMW with NBT EVO, Toyota GR Supra). Weather, news, fuel prices and traffic are deliberately left out.

## How it works

```
 iDrive head unit (NBT EVO)                         Phone
 ┌────────────────────────────┐   car's Wi-Fi    ┌──────────────────────────────┐
 │ "Meteo" plugin from        │   (wireless      │ Drivelog                     │
 │ idrive-plus reads car      │   Android Auto)  │  • HTTP server on port 8765  │
 │ values from the head unit  │ ───────────────► │  • GPS track                 │
 │ and pushes them to the     │  GET /ponte      │  • trip start/stop by the    │
 │ phone                      │  GET /auto?n=…   │    car's Bluetooth           │
 └────────────────────────────┘                  │  • history, GPX/CSV export   │
                                                 └──────────────────────────────┘
```

* The **head unit plugin does the reading**. This app only receives. The plugin is in [`head-unit/`](head-unit/README.md):
  the "Meteo" plugin of iDrive Plus, unmodified, with English install scripts. It needs root SSH on the head unit and
  wireless Android Auto from [nbt-aad](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto), the same as iDrive Plus.
  Building or getting `libMeteo.so` and installing it is described there.
* No AAIdrive, no OBD adapter, no change to the car's control units.
* The phone must be on the car's Wi-Fi, as it already is with wireless Android Auto.
* **Do not run this app and the original phone app at the same time.** Both listen on port 8765.
* In iDrive the plugin shows up as a **Trips** entry in the online services menu. Its page only says that there is
  nothing to show: this app serves no weather, news or traffic pages. The car data still flows.

## What it does

* **Starts a trip by itself** when the phone connects to a car you chose (Bluetooth), and ends it when the car
  disconnects. A 90-second grace period turns a Bluetooth hiccup into one trip instead of two.
* **Records the GPS track** (about one point every few seconds while moving; standing still adds almost nothing) and the
  car values on the way.
* **Trip summary:** distance, duration, moving time, average and top speed, odometer start/end, fuel start/end/used,
  refuelling during the trip, consumption in L/100 km, and the car's own average.
* **Languages.** English and Polish (*Settings > Language*: phone language, English or Polski). All texts go through `tr("English text")` in `core/Lang.kt`; Polish is in `core/Polish.kt`, and a test fails when a text has no translation.
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

* **GPX 1.1**: one `trkpt` per point with `<ele>`, `<time>` and an `<extensions>` block holding `idt:speed` (m/s),
  `idt:odometer` (km), `idt:fuel` (L) and `idt:outside` (°C).
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

**In the car (once):** put the plugin on the head unit, see [`head-unit/README.md`](head-unit/README.md).

**On the phone:**

1. Install the app, open it.
2. **Allow location, Bluetooth, notifications**, then **Allow location all the time**.
3. **Battery: set to unrestricted.** Android 12+ does not let an app start a foreground service from the background
   because of a Bluetooth event; apps the user has exempted from battery optimisation are allowed. Without this the
   app falls back to a notification, and one tap on it starts the trip.
4. Pair the phone with the car in Android's Bluetooth settings, then **Add a paired Bluetooth device** and pick the car.
5. Drive. You can also use **Start trip now** to try it without Bluetooth.

## Build

Android Studio (or JDK 17+ and the Android SDK, platform 35):

```
./gradlew :core:test            # the unit tests: protocol, car data, trips, storage, export
./gradlew :app:assembleDebug    # the APK
```

* `core/` is plain Kotlin/JVM with no Android code: JSON, car-data parsing, the plugin protocol and HTTP server, trip
  recording and statistics, file storage, GPX/CSV. This is where the logic is, and it is unit-tested.
* `app/` is the thin Android layer: the service, the Bluetooth receiver, notifications, the screen and file sharing. It
  uses no libraries beyond the Android framework.
* Versions follow the idrive-plus project (AGP 8.7.3, Kotlin 1.9.25, Gradle 8.9). Bump them if Android Studio asks.

## What has and has not been checked

Checked while writing it:

* 193 unit tests of `core` pass, including a simulated 15 km drive in which a fake plugin pushes car values over a real
  socket, a GPS track is recorded, saved, read back and exported.
* **The real plugin against the real server.** `PluginHostTest` builds the unmodified plugin source for the PC against
  a stand-in for the iDrive framework and runs it against this project's server: the plugin finds the phone, gets the
  handshake, pushes the values it is fed, none is refused, and all are sent again after an app restart. `PluginSourceTest`
  checks that the property list in the plugin source is exactly the list the app accepts. (Both need `clang++`; the
  host test also needs port 8765 free, and skips itself otherwise.)
* The plugin source compiles for ARM, and the install and remove scripts were run against a fake directory tree.
* `core` also compiles at the Kotlin 1.9 language level (which the Gradle files use).
* The map maths (projection, camera, gestures' zoom-around-a-point, tile selection, speed colours) and the tile
  download and cache (against a local test server: User-Agent, 404 and non-image answers not cached, old tile used
  when offline, size limit) are unit-tested.
* The Android layer type-checks against the Android 34 API, and the manifest and resources are consistent.

**Not checked:** how the map *looks* and feels on a phone (drawing, gestures, loading of real OpenStreetMap tiles; the
sandbox has no access to the tile server), a real Gradle/AGP build, an installed APK, a real phone, `libMeteo.so` itself (it needs your head
unit's libraries) and anything on a real head unit. The protocol was taken from the iDrive Plus source and now also
confirmed by running that source, but never against a real car. The riskiest part on the phone is Android's
background-start rules (step 3 above); expect to tune that on your phone. If anything misbehaves, the first things to
look at are the **Car data** and **Permissions** sections of the screen: they show whether values are arriving and which
permission is missing.

## Known limits

* While a trip runs, the server accepts car values from any device on a private network, including your home Wi-Fi.
  The worst a stranger can do is put wrong numbers in your log. It never answers the public internet.
* Car values are only as good as what the car reports with the ignition off or on; invalid values are treated as unknown.
* **Map tiles come from OpenStreetMap's own server**, as its [tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
  allows for light personal use: the app identifies itself with its own User-Agent, caches tiles for 7 days and shows
  the attribution. If you give the app to other people, use a tile provider meant for apps (change `TileProvider`) and
  put a way to reach you in the User-Agent.
* **The weather tile sends the phone's current position (or the parking spot) (rounded to about 1 km) to
  [Open-Meteo](https://open-meteo.com)**, which needs no account or key. It is free for non-commercial use (up to
  10,000 requests a day) and its data is CC BY 4.0, which is why the tile says "Weather data by Open-Meteo.com". A
  reading is reused for 30 minutes and kept on the phone, so it also shows (marked "Offline") for up to 6 hours without
  a network. If you do not want this, remove the call to `weatherTileHere` in `CarsScreen.kt`.
* No place names (coordinates only), no refuelling log, no cloud sync.

## Credits and licence

* **`head-unit/`** is the "Meteo" plugin of [iDrive Plus](https://github.com/deathwalker80/idrive-plus) by DeathWalkeR,
  which builds on [nbt-aad](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto), copied with the changes listed in
  `head-unit/README.md`. It is under the **PolyForm Noncommercial License 1.0.0** (`head-unit/LICENSE.md`,
  `head-unit/NOTICE.txt`): personal and other noncommercial use only. Keep those two files with it.
* **Everything else** (`core/`, `app/`) is independent code. The plugin protocol and the car-data property names were
  learned from reading iDrive Plus; no code from its phone app was used. This part has no licence file yet; add one
  before you publish it, and note that a project that includes `head-unit/` stays noncommercial.

Not affiliated with BMW, MINI or Toyota. The plugin runs inside the head unit's software: use it at your own risk.
