---
name: build-scripts
description: Changing Drivelog's build and helper scripts - Gradle files, headunit Makefile, Bash scripts in headunit/toolchain, Windows .bat/.ps1 wrappers, the Python generator make_db.py, Docker toolchain images and the GitHub workflow.
---

# Build files and scripts

Environment: Windows, PowerShell, Git Bash, Docker Desktop. **No Python, g++ or Java on the PATH**: Python and the
ARM compilers run in Docker images (`headunit/toolchain/run.sh`), Java is the JBR 21 under `%USERPROFILE%\.jdks`.

## Rules for every script

- Line endings from `.gitattributes`: `*.sh`, `*.py`, `Makefile` LF; `*.bat`, `*.ps1` CRLF. Check after editing on
  Windows. A CR in a shell script breaks it in Docker and on the head unit.
- Scripts are re-runnable: same input, same output, no leftovers from the last run deciding the result.
- Fail loudly: a failed step stops the script with a non-zero exit code and one clear line saying what is missing
  (e.g. "copy the car's libs to headunit/lib/entrynav2/").
- No secrets, personal paths or user names in committed scripts; use `$HOME`/`%USERPROFILE%` and relative paths.
- Never add the car maker's binaries (`*.A.so`), `build/`, `.gradle/`, `.idea/`, `local.properties` or APKs.

## SOLID for scripts (only the parts that apply)

- **Single responsibility:** one script or target does one thing (build, pack, test, generate); wrappers like
  `build_entryNav2.bat` only call them in order. Inside a script, one function per step.
- **Open/closed:** lists that grow (languages in `make_db.py`, files of a package, targets) are data at the top, so
  adding an item does not change the logic.
- **Dependency inversion:** tools and paths come in as variables with defaults (`EN2_CXX ?=`, `QCC ?=`,
  parameters of `.ps1`), not hard-coded in the middle, so another machine or a Docker image can supply them.

## Bash (`*.sh`)

- Start with `#!/usr/bin/env bash` and `set -euo pipefail`.
- Quote every expansion (`"$var"`, `"$@"`); use `[[ ]]`, `$(...)`, `local` in functions.
- Temporary files with `mktemp` and a `trap 'rm -rf "$tmp"' EXIT`.
- Keep it working under Git Bash (MSYS path conversion: `MSYS_NO_PATHCONV=1` for Docker paths like `/work`).
- `shellcheck`-clean where possible.

## PowerShell (`*.ps1`) and batch (`*.bat`)

- PowerShell: `$ErrorActionPreference = 'Stop'`, `Set-StrictMode -Version Latest`, `param()` with typed parameters,
  `Join-Path` for paths, `-LiteralPath` for paths from outside. Windows PowerShell 5.1 compatible (no `&&`, `??`, `?.`).
- Check `$LASTEXITCODE` after every native command.
- Batch: `setlocal`, quote paths, `if errorlevel 1 exit /b 1` after each step, `%~dp0` for the script's folder.

## Python (`headunit/make_db.py`)

- Python 3 standard library only, run in the Docker runner (`cd headunit && bash toolchain/run.sh runner make db`).
- `if __name__ == "__main__": main()`; constants at the top in UPPER_CASE; functions with type hints and docstrings.
- Deterministic output (fixed zip timestamps and order) so regenerated zips only change when the content does.
  The zips are committed.
- Texts for the HMI stay ASCII; ids must match `src/drivelog_bridge.cpp` (screen) and `src/bridge_values.hpp` (states) (documented in the module docstring).
- `PROPS` equals the number of CDS properties in `kProperties`.

## Makefile

- `.PHONY` for targets that are not files; variables with `?=` for tools that may be overridden (`EN2_CXX`, `QCC`).
- Keep the checks: `-Wall -Wextra` clean, the `GLIBC_2.4` symbol check, the property count.
- Recipes use tabs; one target per output; `$@`/`$<` instead of repeating names.

## Gradle

- Groovy DSL as in the repo; versions in one place. No new dependencies beyond the Android framework and JUnit.
- Kotlin 1.9.25 needs JDK 21: `$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jbr-21.0.11"` before `gradlew.bat`.
- Both apps are signed with the debug key, also for `release`.

## Verify

```powershell
.\gradlew.bat :car-api:test :core:test :plugins:id6:testDebugUnitTest :app:assembleDebug :plugins:id6:assembleDebug
```

```bash
bash headunit/toolchain/check.sh
```
