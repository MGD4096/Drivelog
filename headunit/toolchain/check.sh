#!/bin/bash
# Builds the plugin and its host test for ARM (EntryNav2 compiler) and runs the test under qemu-arm.
#   bash headunit/toolchain/check.sh
# The test loads the plugin like the HMI does, against tests/fake_framework.cpp, feeds CDS values and talks to it
# over a real TCP connection on 127.0.0.1. None of the car's libraries is needed.
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
run="$here/toolchain/run.sh"
bash "$run" tc make test-build
# The ARM C library for qemu, packed once in the compiler image (a tar keeps its symlinks, also on Windows).
[ -s "$here/build/sysroot.tar" ] || bash "$run" tc tar -C /usr/arm-linux-gnueabihf -cf build/sysroot.tar lib
bash "$run" runner sh -c 'set -e
  mkdir -p /sysroot && tar -xf build/sysroot.tar -C /sysroot
  [ -e /sysroot/lib/ld-linux.so.3 ] || ln -s ld-linux-armhf.so.3 /sysroot/lib/ld-linux.so.3
  rm -rf /tmp/drivelog-test && mkdir -p /tmp/drivelog-test/plugin
  printf "address=127.0.0.1\nport=17777\n" > /tmp/drivelog-test/bridge.conf
  qemu-arm -L /sysroot build/test/host_test /work/build/test/libDrivelogBridge.so /tmp/drivelog-test || { cat /tmp/drivelog-test/bridge.log; exit 1; }'
