#!/bin/sh
# Installs the Drivelog bridge plugin. Run it as root on the head unit, in the folder the archive was unpacked into:
#   EntryNav2:  cd /tmp/drivelog && sh install.sh          (the script makes / writable and read-only again)
#   NBT Evo:    cd <unpacked folder> && sh install.sh      (in the shell where /opt/hmi/ID5/data/ro is writable,
#                                                           the same way NBT-AAD is installed)
# A library the HMI is using cannot be overwritten: an old one is renamed (.old_<pid>) and removed next time.
# Then restart the head unit. Remove again: sh remove.sh
N=DrivelogBridge
HERE=$(cd "$(dirname "$0")" && pwd)

fail() { echo "ERROR: $1"; exit 1; }

if [ -d "$HERE/opt/hmi/ID5" ]; then
  KIND="NBT Evo"; DEST=/opt/hmi/ID5/data/ro/Plugins/$N; DATA=/var/opt/drivelogbridge; SRC="$HERE/opt/hmi/ID5/data/ro/Plugins/$N"
else
  KIND="EntryNav2"; DEST=/opt/hmi/data/ro/Plugins/$N; DATA=/var/local/data/$N; SRC="$HERE/opt/hmi/data/ro/Plugins/$N"
fi
[ -f "$SRC/lib$N.so" ] || fail "$SRC/lib$N.so is missing: unpack the whole archive and run the script from there"
[ -d "$(dirname "$DEST")" ] || fail "$(dirname "$DEST") does not exist: is this archive ($KIND) for this head unit?"
echo "$KIND: installing into $DEST"

if command -v md5sum >/dev/null 2>&1 && [ -f "$HERE/MD5SUMS" ]; then
  (cd "$HERE" && md5sum -c MD5SUMS >/dev/null) || fail "MD5SUMS do not match: copy the archive again"
fi

REMOUNTED=
if [ "$KIND" = "EntryNav2" ] && ! touch "$(dirname "$DEST")/.w" 2>/dev/null; then
  mount -o remount,rw / || fail "cannot make / writable"
  REMOUNTED=1
fi
rm -f "$(dirname "$DEST")/.w" 2>/dev/null
finish() {
  sync
  [ -n "$REMOUNTED" ] && mount -o remount,ro /
}

mkdir -p "$DEST" || { finish; fail "cannot create $DEST (file system read-only?)"; }
rm -f "$DEST"/lib$N.so.old_* 2>/dev/null
if [ -f "$DEST/lib$N.so" ]; then
  mv "$DEST/lib$N.so" "$DEST/lib$N.so.old_$$" || { finish; fail "cannot rename the old lib$N.so"; }
fi
cp "$SRC"/* "$DEST/" || { finish; fail "copying failed"; }
chmod 755 "$DEST/lib$N.so"
mkdir -p "$DATA" 2>/dev/null
rm -f "$DATA/app-guard"            # a fresh install starts the crash guard from zero
[ -e "$DATA/app.disable" ] && echo "NOTE: $DATA/app.disable exists, the plugin stays off until it is deleted"
finish

echo "OK: installed. Restart the head unit; then ConnectedDrive -> DRIVELOG shows the state."
echo "Log: $DATA/bridge.log    Settings (optional): $DATA/bridge.conf    Turn off: touch $DATA/app.disable"
