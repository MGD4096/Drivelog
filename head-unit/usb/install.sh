#!/bin/sh
# Drivelog - installs the "Meteo" plugin on the head unit (NBT EVO). The plugin reads the car's data (odometer,
# fuel, consumption, temperatures, doors, windows, roof) and sends it to the phone app over the car's Wi-Fi. Read only.
# Android Auto (NbtAad) is not touched.
#
# Run it INSIDE the omap shell (after "mount" and "on -f", see ../README.md):
#   sh /mnt/umass*/install.sh
#
# A library that the HMI is using cannot be overwritten: the old one is renamed (.old_*) and the new one copied next to
# it, and it is picked up at the next restart.
#
# The four variables below exist only so the script can be tried on a PC; in the car leave them alone.
P=${PLUGIN_DIR:-/opt/hmi/ID5/data/ro/Plugins}
SHM=${SHM_DIR:-/dev/shmem}
AA=${AA_DIR:-/opt/aa}
N=Meteo

fail() { echo "ERROR: $1"; exit 1; }

U=${STICK_DIR:-}
if [ -z "$U" ]; then
  for d in /mnt/umass*; do
    if [ -d "$d" ]; then U=$d; break; fi
  done
fi
[ -n "$U" ] && [ -d "$U" ] || fail "USB stick not found in /mnt (looked for /mnt/umass*)"
echo "USB stick: $U"

[ -d "$P/NbtAad" ] || fail "$P/NbtAad does not exist - are you in the omap shell, with wireless Android Auto installed?"

for f in lib$N.so $N.manifest $N.xml ${N}_ALL_Texts.zip ${N}_ALL_Images.zip attesa.png; do
  [ -f "$U/$N/$f" ] || fail "$U/$N/$f is missing - did you copy the contents of usb/ to the ROOT of the stick? (lib$N.so comes from build.sh, see README)"
done

mkdir -p "$P/$N" || fail "cannot create $P/$N - is the file system mounted read-write? (see README, step 2)"
rm -f "$P/$N"/lib$N.so.old_* 2>/dev/null
if [ -f "$P/$N/lib$N.so" ]; then
  mv "$P/$N/lib$N.so" "$P/$N/lib$N.so.old_$$" || fail "cannot rename the old lib$N.so"
  echo "OK: old lib$N.so renamed (it stays in use until the restart)"
fi
cp "$U/$N"/* "$P/$N/" || fail "copying $N failed - the old library is renamed, run the script again or restore $P/$N/lib$N.so.old_$$"
rm -f "$SHM/$N.avvii"      # the plugin counts failed starts of the HMI in this file; start counting from zero
echo "OK: $N installed"

if [ -e "$AA/ponte.disable" ]; then echo "WARNING: $AA/ponte.disable exists, the plugin will stay off (remove that file)"; fi
if [ -e "$AA/auto.disable" ]; then echo "NOTE: $AA/auto.disable exists, the plugin will not read the car data (remove that file)"; fi
for other in Notizie Locali Carburanti Traffico Auto; do
  if [ -d "$P/$other" ]; then
    echo "NOTE: the iDrive Plus plugin $other is also installed. It has nothing to show with this app and only waits on its"
    echo "      'waiting for phone' page. To remove it: sh $U/remove.sh --all"
  fi
done

echo "===== PLUGINS ON THE HEAD UNIT ====="
ls "$P"
echo ""
echo "########## DONE - NOW RESTART: hold the power button for 30 seconds ##########"
echo "Check after the first drive:  grep auto: $SHM/$N.log   (the first values received from the car)"
