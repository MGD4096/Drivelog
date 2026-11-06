# Head-unit plugin

The phone app needs something inside the car that reads the car's data and sends it to the phone. That is the
**"Meteo" plugin from [iDrive Plus](https://github.com/deathwalker80/idrive-plus)**. Of that project's six plugins it is
the only one that reads car data, so it is the only one included here.

> **Licence.** This folder is a copy of someone else's work, under the
> [PolyForm Noncommercial License 1.0.0](LICENSE.md): personal and other noncommercial use only. The notices in
> [NOTICE.txt](NOTICE.txt) must stay with it. The rest of this project is independent code and has no licence yet.

## What is here

| Path | What it is |
|---|---|
| `plugin/ponte_plugin.cpp` | The plugin source, **byte for byte the iDrive Plus one** (plugin v9, the one in its v24 USB package). Not modified, so it behaves as the author tested it on a MINI F60. |
| `plugin/host-test/prova_host.cpp` | The author's PC test harness with a stand-in for the iDrive framework, unchanged. |
| `plugin/link/` | Empty stub libraries used only for linking. |
| `plugin/build.sh` | Builds `libMeteo.so` (the original `compila.sh`, cut down to the one plugin). |
| `usb/` | What goes on the USB stick: `install.sh`, `remove.sh` and the `Meteo/` folder. |

What I changed compared with iDrive Plus, all of it only in what you see in the car or in how it is installed:

* **Menu entry:** it is called **Trips** instead of "Weather" (text archive), with a map-pin icon.
* **Page:** the entry's page is in English and says there is nothing to show. The plugin shows this page when no
  page is delivered, and this app delivers none: weather, news, fuel prices and traffic are not part of it.
* **Scripts:** new English `install.sh` and `remove.sh` for this one plugin (the original ones handle all six and
  are in Italian).
* Internally the plugin is still called `Meteo` (folder, library `libMeteo.so`, log `Meteo.log`).

## How it works

```
iDrive reports car values  →  plugin (inside the HMI)  →  GET /auto?n=<property>&v=<JSON>  →  phone, port 8765
```

The plugin finds the phone on the car's Wi-Fi (`172.16.224.x`), checks `GET /ponte`, and sends each changed value
(odometer, fuel, average consumption, temperatures, doors, tailgate, bonnet, sunroof, windows) at most every 2 seconds.
Read only: nothing is written to the car. It remembers the last value it sent for each of its 13 properties and
sends only changes; if the app restarts (the `avvio=` number in the `/ponte` answer changes) it sends all of them again.

## Requirements

* NBT EVO head unit with **root SSH access**, and **wireless Android Auto** from
  [nbt-aad](https://github.com/Sq00ky/Supra-iDrive6-Android-Auto) (the installer looks for its `NbtAad` plugin).
  These are the same requirements as iDrive Plus.
* Tested by its author on a MINI F60 (8.8"). BMW and Supra are not verified by anyone yet.

## 1. Get `libMeteo.so`

The plugin needs two libraries of the head unit's own HMI to link against. They are not redistributable and are not
included. Pick one:

**A. Take the finished library.** Download the USB package from the Releases page of iDrive Plus and copy its
`Meteo/libMeteo.so` to `usb/Meteo/libMeteo.so`. Nothing to build. Use the package that matches this source: plugin
**v9**, whose notes are `LEGGIMI-v24.txt` (the first line of `plugin/ponte_plugin.cpp` names the version). I could not
open the Releases page from here, so I have not checked which release is the newest. A newer plugin may change what it
asks of the phone (`/ponte`, `/auto`): if you use one, read its changelog for that first.

**B. Build it.** Needs `clang` and `lld` 18 (Ubuntu 24.04: `apt install clang lld`).

1. On the head unit, find the two libraries: `find / -name "lib*Framework.A.so"` (`libAppFramework.A.so` and
   `libCoreFramework.A.so`), and copy them to `plugin/link/`.
2. `sh plugin/build.sh` → `plugin/out/libMeteo.so`; copy it to `usb/Meteo/libMeteo.so`.

(`sh plugin/build.sh --check` only checks that the source compiles for ARM and needs no HMI libraries.)

## 2. Put it on the head unit

Same steps as iDrive Plus (its `LEGGIMI-v24.txt`, "AUTO"), with these scripts:

1. Copy **everything inside `usb/`** to the **root** of a USB stick, so that `install.sh` and `Meteo/libMeteo.so` are
   there. Plug it into the car.
2. With PuTTY (or `ssh`): host `169.254.199.99`, port `22`, user `root`.
3. In that shell:
   ```
   mount -o remount rw /net/hu-omap/fs/sda0
   on -f /net/hu-omap/ /bin/sh
   sh /mnt/umass*/install.sh
   ```
4. Restart the head unit: hold the power button for 30 seconds.

After the first drive with the phone app running, `grep auto: /dev/shmem/Meteo.log` (in the same shell) shows the first
values the car sent; the app's **Car data** section shows them too.

If you already have iDrive Plus installed, `install.sh` only replaces `Meteo`. The other five plugins would stay and
just sit on their "waiting for phone" pages; `sh /mnt/umass*/remove.sh --all` removes them.

## Switching off and removing

* `sh /mnt/umass*/remove.sh` moves the plugin to `/opt/aa/plugins_off` (recoverable); restart afterwards.
* Off without removing: `echo 1 > /opt/aa/ponte.disable` (plugin off) or `echo 1 > /opt/aa/auto.disable` (it still
  runs but reads no car data), then restart. Delete the file to switch it on again.
* The plugin runs inside the head unit's HMI process. It protects itself: after 3 starts in a row that did not last
  30 seconds it stays off until `/dev/shmem/Meteo.avvii` is cleared (`install.sh` does that).
* Android Auto (NbtAad) is never touched.

## What has been tested

* The unmodified `ponte_plugin.cpp` **compiles for ARM** (`build.sh --check`).
* `./gradlew :core:test` builds the plugin for the PC against the stand-in framework and runs it against **this
  project's real server** (`PluginHostTest`): it finds the "phone", gets the handshake, registers its 13 properties,
  pushes the values the stand-in feeds it over a real socket, never has one refused, sends again after the app
  "restarts", and the app ends up with the right odometer, fuel and open parts. Another test (`PluginSourceTest`) checks
  that the property list in the plugin source is exactly the list the app accepts, so the two cannot drift apart.
* `install.sh` and `remove.sh` were run against a fake directory tree: clean install, reinstall over a running library,
  missing files, missing `NbtAad`, warnings, removal, `--all`.

**Not tested:** `libMeteo.so` itself (it needs your head unit's libraries), and anything on a real head unit.
