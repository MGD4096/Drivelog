# Drivelog bridge – head-unit plugin (NBT Evo and EntryNav2)

`libDrivelogBridge.so` is an iDrive PluginApp. It runs inside the head unit's HMI (`HmiMain3D`), subscribes to the
15 car values Drivelog uses and serves them to the phone over the car's Wi-Fi. The phone app Drivelog ID6
(`plugins/id6/`) connects to it and hands the values to Drivelog; the
plugin never looks for the phone. **Read only:** it never writes anything to the car.

```
car signals ─► HmiMain3D (CDS) ─► libDrivelogBridge.so ──TCP :7777, JSON lines──► car Wi-Fi ─► Drivelog ID6 ─► Drivelog
                                   CDS callbacks → queue → network thread
```

One source for both head units:

| | NBT Evo | EntryNav2 (EntryEvo) |
|---|---|---|
| System | QNX 6.5, ARMv7 | Linux, ARMv7 hard-float, eglibc 2.15 |
| Compiler | QNX SDP 6.5 `qcc` (GCC 4.4) | `arm-linux-gnueabihf-g++` 4.6 (Docker, `toolchain/Dockerfile`) |
| Plugin folder | `/opt/hmi/ID5/data/ro/Plugins/DrivelogBridge` | `/opt/hmi/data/ro/Plugins/DrivelogBridge` |
| Data folder (log, settings) | `/var/opt/drivelogbridge` | `/var/local/data/DrivelogBridge` |
| Build | `build_nbt.bat` (or `make nbt-dist`) | `bash toolchain/run.sh tc make entrynav2-dist` |

In iDrive it is **ConnectedDrive → DRIVELOG**: a page with three lines – the state (car Wi-Fi off / waiting for the
phone / phone connected / port busy), how many phones are connected and how many of the 15 values have arrived.

## Car values

`driving.odometer`, `sensors.fuel`, `driving.averageConsumption`, `sensors.temperatureExterior`,
`sensors.temperatureInterior`, `sensors.doors`, `sensors.trunk`, `sensors.lid`, `controls.sunroof`,
`controls.windowDriverFront`, `controls.windowPassengerFront`, `controls.windowDriverRear`,
`controls.windowPassengerRear`, `sensors.battery` (12 V battery, %), `sensors.tirePressureWarning` (`status` 0 = no
warning) – the list in `src/bridge_core.hpp`. A test of the phone plugin (`PluginSourceTest` in
`plugins/id6`) fails when it and `CdsProperty.ALL` there differ. None of them is personal data (no GPS, contacts or VIN), so nothing is
masked.

## Protocol (version 1)

TCP, port **7777** on the head unit's Wi-Fi address, UTF-8, one JSON object per line. The plugin sends:

```json
{"type":"hello","proto":1,"app":"drivelog-bridge-0.1.0 EntryNav2","t":123456,"props":13}
{"type":"snapshot","t":123457,"values":{"driving.odometer":{"odometer":12345},"sensors.fuel":{"fuel":{"tanklevel":31,"range":420}}}}
{"type":"cds","t":123512,"name":"sensors.fuel","value":{"fuel":{"tanklevel":30,"range":410}}}
{"type":"ping","t":128512}
{"type":"error","msg":"too many phones"}
```

* `hello` then `snapshot` (the latest value of every property that has one) right after the phone connects.
* `cds` for each change, `value` exactly the JSON the car gave (UTF-16 in the car, converted to UTF-8; Polish letters
  are kept). A value that is not a JSON object is logged and not sent. Repeats of the same value are not sent.
* `ping` after 5 s without anything else. The phone treats 15 s of silence as a lost connection.
* `t` = the head unit's monotonic clock in ms.
* What the phone sends is read and ignored. At most 2 phones (`clients=`); a third gets `error` and is closed. A phone
  with more than 256 KB not yet read is dropped – a slow phone can never hold up the HMI.
* If the plugin falls more than 256 changes behind, it sends a fresh `snapshot` instead of the lost changes.

Test it from a laptop on the car's Wi-Fi: `nc <head unit address> 7777`.

### Which address it listens on

Never `0.0.0.0`: the head unit also has internal and service interfaces (for example `169.254.1.0`). Without
settings it picks the Wi-Fi interface: `wlan0` (EntryNav2, as in entry-aad), else an interface named `uap*`/`wl*`,
else one with an address in `172.16.224.0/24` (where the iDrive Plus plugin found phones on NBT Evo). Loopback and
`169.254.x.x` are never used. The address is checked every 3 s, so the plugin starts listening when the hotspot comes
up (for example with wireless Android Auto) and follows an address change.

On the phone, the Drivelog ID6 app (`plugins/id6/`) binds its socket to the car's Wi-Fi (that network has no internet, so Android would otherwise
use mobile data) and connects to the network's gateway, then the DHCP server, then the first address of the subnet.

## Settings: `bridge.conf` (optional)

In the data folder (or the plugin folder). Every line is optional:

```
port=7777          # TCP port (the app uses 7777)
interface=wlan0    # listen on this interface only
address=a.b.c.d    # listen on this address (wins over interface)
clients=2          # phones at the same time, 1..4
```

The log says what was used: `config (…): port 7777, interface 'auto', …` and `listening on …:7777`.

## Safety

* **Crash guard.** An error in a plugin takes the whole HMI down (screen, radio, navigation). Every start adds a
  line to `app-guard` in the data folder; after 120 s of normal work the file is deleted. After 3 HMI restarts in a
  row right after loading, the plugin stays off. Delete `app-guard` (and read `bridge.log`) to try again.
* **Turn off without uninstalling:** create `app.disable` in the data folder or the plugin folder, restart.
* **Log:** `bridge.log` in the data folder (512 KB, then `bridge.old.log`). A file `drivelog-bridge.log` on a USB stick
  gets a copy (EntryNav2: `/media/*`, `/mnt/*`, `/run/media/*`; NBT: `/fs/usb*`, `/mnt/umass*`). NBT also writes to
  `slog`.
* Sockets are non-blocking and live in their own thread; the HMI thread only puts values in a queue. `SIGPIPE` is
  blocked in that thread, so a phone that disappears cannot kill the HMI.
* Etch connector id 213, menu ids `entryButtonFocusID` 32755 / `applicationWeight` 213: different from GaugeApp,
  CdsScan, NBT-AAD and entry-aad, so they can all be installed together.

## Build

The car's `libAppFramework.A.so` and `libCoreFramework.A.so` are BMW's files: they are **not** in git
(`.gitignore`). Copy them from the head unit first:

* EntryNav2: `/opt/hmi/lib/` → `headunit/lib/entrynav2/`
* NBT Evo: `/opt/hmi/ID5/lib/` → `headunit/lib/nbt/`

**EntryNav2** (Docker Desktop; the image is built once, or an existing `gauge-entrynav2-tc` is used):

```
docker build -t drivelog-entrynav2-tc headunit/toolchain
cd headunit && bash toolchain/run.sh tc make entrynav2-dist     # -> dist/drivelog-bridge-entrynav2.tar
```

The build fails if the library needs a newer glibc than the car's 2.15.

**NBT Evo** (QNX SDP 6.5.0, Windows): `headunit\build_nbt.bat [C:\QNX650]` → `build\nbt\libDrivelogBridge.so`;
then `make nbt-dist` in Git Bash for the archive (or copy the files listed in `Makefile` by hand).

**Text and image database** (only after changing `make_db.py`): `bash toolchain/run.sh runner make db`.

## Test without a car

```
bash headunit/toolchain/check.sh
```

Builds the plugin and `tests/host_test.cpp` with the EntryNav2 compiler and runs them under `qemu-arm`. The test loads
the library the way the HMI does (`dlsym("pluginAppInfo")`, the vtable slots, `onInit`, posted Runnables) against
`tests/fake_framework.cpp` – a stand-in that exports the same `bmw::` symbols – feeds CDS values and checks over a real
TCP connection: hello, snapshot, changes, no repeats, bad values dropped, Polish letters in UTF-8, the queue
overflowing, ping, a second phone, a third one refused, a phone leaving, the screen texts, `onFinalize`, `app.disable`
and the crash guard. The phone side of the same lines is tested in `plugins/id6` (`BridgeClientTest`).

## Install

**EntryNav2** (root SSH over the service link, like entry-aad / GaugeApp):

```
scp dist/drivelog-bridge-entrynav2.tar root@169.254.1.0:/tmp/
ssh root@169.254.1.0
mkdir -p /tmp/drivelog && tar -xf /tmp/drivelog-bridge-entrynav2.tar -C /tmp/drivelog && cd /tmp/drivelog
sh install.sh        # checks MD5SUMS, makes / writable, copies, makes / read-only again
reboot
```

**NBT Evo:** unpack `dist/drivelog-bridge-nbt.tar` onto a USB stick, and in the head unit's shell where
`/opt/hmi/ID5/data/ro` is writable (the same way NBT-AAD is installed) run `sh install.sh` from the unpacked folder;
then restart the head unit.

**Remove:** `sh remove.sh` (add `--keep` to keep the log and settings), then restart.

## What has and has not been checked

Checked here: the host test (68 checks) passes with the car's EntryNav2 compiler under qemu; the EntryNav2 build links
against the EntryNav2 framework libraries and needs only `GLIBC_2.4`; each of the 10 `bmw::` symbols the plugin uses
is exported by **both** the EntryNav2 and the NBT Evo `libAppFramework.A.so`; the framework calls follow GaugeApp and
CdsScan, which run on an EntryNav2.

**Not checked:** anything in a real car. In particular: the NBT Evo build (no QNX SDP here – `getifaddrs`, `poll`,
`pipe` and `-lsocket` are standard on QNX 6.5 but untested), whether the NBT data folder `/var/opt` is writable, the
head unit's Wi-Fi address and interface name on both units, whether the head unit is the Wi-Fi gateway the phone sees,
and whether the hotspot is on without wireless Android Auto / CarPlay.
