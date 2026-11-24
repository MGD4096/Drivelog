#!/bin/bash
# Runs a command in one of the two build images with headunit/ on /work.
#   bash toolchain/run.sh tc make entrynav2-dist        cross compiler for EntryNav2 (Dockerfile)
#   bash toolchain/run.sh runner make db                qemu-arm + python3 (Dockerfile.runner)
# Existing images of the other EntryNav2 projects on this PC are used when there (same tools).
set -e
here="$(cd "$(dirname "$0")/.." && pwd)"
pick() { for i in "$@"; do docker image inspect "$i" >/dev/null 2>&1 && { echo "$i"; return; }; done; echo "$1"; }
case "$1" in
  tc)     image=$(pick drivelog-entrynav2-tc gauge-entrynav2-tc) ;;
  runner) image=$(pick drivelog-runner entrynav2-runner) ;;
  *) echo "usage: run.sh tc|runner <command...>"; exit 2 ;;
esac
shift
# Git Bash on Windows: keep /work as it is, and give docker a Windows path for the mount.
if command -v cygpath >/dev/null 2>&1; then host="$(cygpath -w "$here")"; export MSYS_NO_PATHCONV=1; else host="$here"; fi
exec docker run --rm -v "$host:/work" -w /work "$image" "$@"
