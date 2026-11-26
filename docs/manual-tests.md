# Manual tests on a phone and in the car

The unit tests and the head-unit host test cover the logic. What they cannot cover is listed here: Android itself
(permissions, Bluetooth, background starts, launchers, file pickers), and the car. Go through the parts a change
touches; before a release, go through all of it. Note the phone, Android version and head unit with each run.

Legend: **[phone]** needs only the phone, **[car]** needs the car (or its head unit on the bench).

## 0. Install

- [ ] [phone] Install both APKs over the previous versions (no uninstall). Both install as updates: same signing key.
- [ ] [phone] Settings → About shows `Version 1.0.<n>`; the car's ⓘ dialog shows the same for Drivelog and the plugin.
- [ ] [phone] The plugin has no launcher icon of its own (an "App info" stand-in is fine, AGENTS.md).

## 1. Permissions (Settings tab)

- [ ] [phone] Fresh install: every row shows ✗ with "Allow"; each "Allow" opens the right system dialog or page.
- [ ] [phone] After allowing, the rows turn ✓ within a second, without leaving the tab.
- [ ] [phone] With TalkBack on, the ✓/✗ are read as "Allowed"/"Not allowed".

## 2. A trip

- [ ] [car] Connecting the phone to the car's Bluetooth starts recording (notification "Recording …"), also with the
      app closed and the screen off.
- [ ] [car] With the plugin: the notification says "car data ✓" within about 10 s; Settings shows "connected to
      drivelog-bridge-0.2.0 …". An older head-unit plugin adds "(update the head-unit plugin to 0.2.0 or newer)".
- [ ] [car] Wireless Android Auto on: values still arrive (the head unit calls the phone on 7778).
- [ ] [car] Engine off, Bluetooth gone: "waiting for reconnect" with a countdown; reconnecting within 90 s carries on
      the same trip; otherwise the trip is saved and the "Trip finished" notification offers "Mark as business".
- [ ] [phone] A trip of a few metres is not saved, but "Parked" on the car page moves to where it ended.
- [ ] [phone] Force-stop the app during a trip, start it again: the earlier part is recovered as a saved trip.
- [ ] [phone] "Start trip now" (Settings) asks which car; "Stop and save" ends it.
- [ ] [car] A tyre pressure warning in the car gives one notification per trip; a low 12 V battery one after the trip.

## 3. Car page

- [ ] [phone] Live part: odometer, fuel and doors update every 2 s while a trip of this car runs.
- [ ] [phone] Photos: add several at once, open the gallery, the first is the main photo.
- [ ] [phone] ⓘ: Bluetooth device, plugin with version, the car's values and where they come from.
- [ ] [phone] Edit car: wrong year/VIN keep the dialog open with an error; switching "No plugin" shows the odometer field.
- [ ] [phone] Service log: add, edit and delete an entry; a due date before the entry date is refused; reminders update.
- [ ] [phone] ⋮ → Archive / Restore, Change Bluetooth device (history moves along), Remove car.
- [ ] [phone] Fuel log: a detected refuel can be confirmed with a price or dismissed; consumption and cost per km appear.

## 4. Trips

- [ ] [phone] List grouped by month; a trip's details show top speed with "(car's speed)" or "(GPS)", and for short trips
      "Fuel used by the car's fuel counter … (estimate)".
- [ ] [phone] Mileage log of a trip: business/private, purpose, route (filled from GPS when empty), driver.
- [ ] [phone] Share a trip as GPX and as CSV: the share sheet opens and the file opens in another app.
- [ ] [phone] Delete a trip.
- [ ] [phone] Car page → Mileage log → each period, filter and format; the PDF opens and prints on A4 landscape;
      the CSV opens in a spreadsheet with Polish characters right.
- [ ] [phone] Statistics by month: charts for all cars and for one car.

## 5. Home-screen widget

- [ ] [phone] Adding the widget asks which car (or "the car that reported last"); backing out adds nothing.
- [ ] [phone] It shows the name on top, then fuel, range, battery and the padlock with "Closed"/"Open: …", the time below.
- [ ] [phone] Long-press → widget settings changes the car (Android 12+, launchers that offer it).
- [ ] [phone] Two widgets for two cars show their own cars; tapping opens that car's page.
- [ ] [phone] Dark mode: the widget's colours follow the phone.
- [ ] [car] During a trip it says "live" and updates.
- [ ] [phone] The "Drivelog: vehicles" widget asks which vehicles (up to 3, more are refused); a tile each, side by side, with its
      picture (a motorcycle its drawing), fuel, range, 12 V and Closed/Open, the time; tapping a tile opens that vehicle.
- [ ] [phone] The "Drivelog: services" widget lists what comes up on all vehicles, most pressing first (overdue red,
      soon orange); adding or deleting a service log entry updates it; tapping a row opens that vehicle.
- [ ] [phone] "Drivelog: last trip": the latest trip's route on a map (also offline from the tile cache), distance,
      time, speed, consumption; tapping opens the route on the full map. Updates after a trip.
- [ ] [phone] "Drivelog: parked" asks which vehicle; map with a pin and since when; tapping offers navigation there.
- [ ] [phone] "Drivelog: this month": km and the change against last month, business/private km, fuel and service
      money, per km; tapping opens the statistics. Updates after a trip, a refuelling, a service entry.
- [ ] [phone] "Drivelog: fuel" asks which vehicle; average over the latest full tanks, the tank and range, the last
      refuelling and its price, the year's fuel bill per km. Updates after a refuelling.
- [ ] [phone] Both widgets update together after a trip or a reading from the motorcycle plugin.

## 6. Settings and data

- [ ] [phone] Language and theme switch at once on every screen.
- [ ] [phone] Reminders: "Send a test notification" shows one.
- [ ] [phone] Export all data, then Import it on a second phone (or after reinstalling): everything is back.
- [ ] [phone] Automatic backup: choose a folder → a `drivelog-auto-<date>.zip` appears at once; the status says when.
      Change the phone's date a week ahead and open the app: a second one. After more than four, the oldest go.
- [ ] [phone] Automatic backup with the folder deleted, or its access removed: the status shows the error in orange.
- [ ] [phone] Import a `drivelog-auto-…zip`: it is a normal backup.

## 7. The plugin app (Drivelog ID6)

- [ ] [phone] Opened from the car's ⓘ → "Open Drivelog ID6": shows the state and how it tries to reach the head unit.
- [ ] [phone] A plugin signed with another key, or without the permission, is not offered in "Edit car".

## 7a. Drivelog Ride (motorcycle)

- [ ] [phone] Edit car → "Motorcycle": the car page shows the motorcycle drawing with the tyre pressures instead of
      the doors card; the widget shows the drawing too. Back to "Car": the doors card returns.
- [ ] [phone] Drivelog → motorcycle → ⓘ → "Open Drivelog Ride": "Screen reader: off"; the button opens the
      accessibility settings; after turning "Drivelog Ride" on, the screen says "on". Enter the tank size and save.
- [ ] [phone] Open the motorcycle app: the plugin's screen shows fuel and range; tap the motorcycle: also the odometer
      (and the tyres when the app shows them). The time is the app's "Updated …", not now.
- [ ] [phone] Drivelog's car page and widget show the same values; ⓘ shows the tyre pressures.
- [ ] [ride] A ride with the phone: at the start the trip gets the last reading (odometer before the ride); after the
      ride, open the motorcycle app and tap the motorcycle: the trip's end odometer and fuel change to the new ones.
- [ ] [phone] Plugin screen → "Read now": the motorcycle app opens, the motorcycle is tapped, the details show and the
      phone goes back to the home screen within a few seconds; the odometer is in Drivelog.
- [ ] [ride] After a ride, lock the phone; at the next unlock (within 2 h) the same happens by itself. With the switch
      off, nothing opens.
- [ ] [phone] Opening another app with the screen reader on: nothing is read (only the motorcycle app is).

## 8. Head unit

- [ ] [car] EntryNav2: install `dist/drivelog-bridge-entrynav2.tar` (headunit/README.md); after the restart the plugin's
      screen shows its three lines; the HMI, radio and navigation work as before.
- [ ] [car] NBT Evo: build with QNX (`build_nbt.bat dist`), install from USB; same checks. Not built on this PC so far.
- [ ] [car] `drivelog-bridge.log` on a USB stick gets the log; `bridge.log` in the data folder too.
- [ ] [car] `app.disable` in the data folder turns the plugin off at the next start.
- [ ] [car] Crash guard: three HMI restarts right after loading turn the plugin off (only if it ever crashes; read the log).
