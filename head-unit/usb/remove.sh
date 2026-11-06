#!/bin/sh
# Drivelog - removes the "Meteo" plugin (moved to /opt/aa/plugins_off, so it can be put back). Android Auto
# (NbtAad) is not touched.
#
#   sh /mnt/umass*/remove.sh          remove only Meteo
#   sh /mnt/umass*/remove.sh --all    also remove the other iDrive Plus plugins (Notizie, Locali, Carburanti,
#                                     Traffico, Auto), if they are installed
#
# To switch it off without removing anything:  echo 1 > /opt/aa/ponte.disable   (+ restart)
# To stop only the car data:                    echo 1 > /opt/aa/auto.disable    (+ restart)
#
# The two variables below exist only so the script can be tried on a PC; in the car leave them alone.
P=${PLUGIN_DIR:-/opt/hmi/ID5/data/ro/Plugins}
OFF=${OFF_DIR:-/opt/aa/plugins_off}

LIST="Meteo"
case "$1" in
  "") ;;
  --all) LIST="Meteo Notizie Locali Carburanti Traffico Auto" ;;
  *) echo "ERROR: unknown option '$1' - use no option, or --all"; exit 1 ;;
esac

mkdir -p "$OFF" || { echo "ERROR: cannot create $OFF - is the file system mounted read-write?"; exit 1; }
for N in $LIST; do
  if [ -d "$P/$N" ]; then
    DEST="$OFF/$N"
    if [ -e "$DEST" ]; then DEST="$OFF/${N}_$$"; fi
    if mv "$P/$N" "$DEST"; then
      echo "OK: $N removed (moved to $DEST)"
    else
      echo "WARNING: cannot move $N - switching the plugins off with $OFF/../ponte.disable instead"
      echo 1 > "$(dirname "$OFF")/ponte.disable"
    fi
  else
    echo "$N is not installed, nothing to do"
  fi
done
echo "Plugins left:"; ls "$P"
echo "NOW RESTART BY HAND: hold the power button for 30 seconds."
