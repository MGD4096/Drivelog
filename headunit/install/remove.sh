#!/bin/sh
# Removes the Drivelog bridge plugin and its data folder. Run as root on the head unit, then restart it.
#   sh remove.sh            plugin and data (log, settings)
#   sh remove.sh --keep     plugin only
N=DrivelogBridge
if [ -d /opt/hmi/ID5/data/ro/Plugins ]; then
  DEST=/opt/hmi/ID5/data/ro/Plugins/$N; DATA=/var/opt/drivelogbridge; KIND="NBT Evo"
else
  DEST=/opt/hmi/data/ro/Plugins/$N; DATA=/var/local/data/$N; KIND="EntryNav2"
fi
[ -d "$DEST" ] || { echo "$N is not installed ($DEST)"; exit 0; }

REMOUNTED=
if [ "$KIND" = "EntryNav2" ] && ! touch "$DEST/.w" 2>/dev/null; then
  mount -o remount,rw / || { echo "ERROR: cannot make / writable"; exit 1; }
  REMOUNTED=1
fi
rm -rf "$DEST" || echo "ERROR: cannot remove $DEST"
[ "$1" = "--keep" ] || rm -rf "$DATA"
sync
[ -n "$REMOUNTED" ] && mount -o remount,ro /
echo "OK: removed. Restart the head unit."
