#!/bin/sh
# Builds the one plugin this project needs, out/libMeteo.so (ARM, QNX), from ponte_plugin.cpp.
# Needs clang and lld 18 (Ubuntu 24.04: apt install clang lld).
#
# The two HMI libraries, libAppFramework.A.so and libCoreFramework.A.so, are NOT part of this project: they belong
# to the head unit and cannot be redistributed. Copy them from YOUR head unit into plugin/link/ first
# (on the head unit:  find / -name "lib*Framework.A.so").
# libc.so.3 and libsocket.so.3 are built here from empty stubs: they are only needed for linking, the head unit's
# own libraries are used when the plugin runs in the car.
#
# Compile-only check (no HMI libraries needed, produces no usable plugin):  sh build.sh --check
set -e
cd "$(dirname "$0")"
L=link
ARM="--target=armv7a-unknown-linux-gnueabi -mfloat-abi=soft -marm -O2 -fPIC -ffreestanding -fno-builtin"
CC="-fno-exceptions -fno-rtti -fno-threadsafe-statics -fno-stack-protector -fvisibility=hidden"
mkdir -p out

if [ "$1" = "--check" ]; then
  clang $ARM $CC -DPLUGIN_METEO -c ponte_plugin.cpp -o out/Meteo.o
  for n in c socket; do
    clang $ARM -nostdlib -shared -fuse-ld=lld -Wl,-soname,lib$n.so.3 -o out/lib$n.so.3 $L/lib${n}_stub.c
  done
  echo "OK: ponte_plugin.cpp compiles for ARM (out/Meteo.o). Nothing to install: run build.sh without --check."
  exit 0
fi

for f in libAppFramework.A.so libCoreFramework.A.so; do
  [ -f "$L/$f" ] || { echo "ERROR: $L/$f is missing - copy it from your head unit (see the top of this file)"; exit 1; }
done
for n in c socket; do
  clang $ARM -nostdlib -shared -fuse-ld=lld -Wl,-soname,lib$n.so.3 -o out/lib$n.so.3 $L/lib${n}_stub.c
done
clang $ARM $CC -DPLUGIN_METEO -c ponte_plugin.cpp -o out/Meteo.o
ld.lld -shared --hash-style=sysv -z norelro -z nognustack --no-rosegment -z max-page-size=0x1000 --no-undefined \
  -soname libMeteo.so -o out/libMeteo.so out/Meteo.o $L/libAppFramework.A.so $L/libCoreFramework.A.so \
  out/libsocket.so.3 out/libc.so.3
rm -f out/Meteo.o
echo "OK: out/libMeteo.so"
echo "Copy it to usb/Meteo/libMeteo.so, then put the whole usb/ folder on a USB stick (see ../README.md)."
