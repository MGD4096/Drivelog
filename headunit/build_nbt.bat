@echo off
rem Builds the NBT Evo plugin with QNX SDP 6.5.0 into build\nbt\libDrivelogBridge.so, and optionally packs it.
rem   build_nbt.bat                  build only (QNX in C:\QNX650 or qcc already on the PATH)
rem   build_nbt.bat D:\QNX650        build with QNX from another folder
rem   build_nbt.bat dist [D:\QNX650] build, then pack into dist\:
rem                                    dist\drivelog-bridge-nbt\     copy its contents to the root of a USB stick
rem                                    dist\drivelog-bridge-nbt.tar  the same as one archive
rem   build_nbt.bat pack             pack an existing build\nbt\libDrivelogBridge.so without compiling (no QNX needed)
rem The car's libAppFramework.A.so and libCoreFramework.A.so (/opt/hmi/ID5/lib) go into lib\nbt first.
rem Installing: README.md, "Install" (NBT Evo: from the stick, sh install.sh in the unpacked folder, then restart).
setlocal
rem the script's own folder, kept before "shift" below moves %0 away
set "HERE=%~dp0"
cd /d "%HERE%"

set QNXDIR=
set MODE=build
:args
if "%~1"=="" goto argsdone
if /i "%~1"=="dist" (set MODE=dist) else if /i "%~1"=="pack" (set MODE=pack) else set QNXDIR=%~1
shift
goto args
:argsdone
if "%QNXDIR%"=="" set QNXDIR=C:\QNX650

if "%MODE%"=="pack" goto pack

echo [1/2] Compiling for NBT Evo (QNX)
where qcc >nul 2>nul
if errorlevel 1 (
    if exist "%QNXDIR%\qnx650-env.bat" (
        rem the QNX environment script may change the folder (or end with "exit"): run it, then come back
        call "%QNXDIR%\qnx650-env.bat"
        cd /d "%HERE%"
    ) else (
        echo [FAILED] qcc not found and no %QNXDIR%\qnx650-env.bat: QNX SDP 6.5 is needed to compile.
        echo          Pass its folder: build_nbt.bat dist D:\QNX650 - or pack a ready .so: build_nbt.bat pack
        exit /b 1
    )
)
where qcc >nul 2>nul
if errorlevel 1 (
    echo [FAILED] qcc is still not on the PATH after %QNXDIR%\qnx650-env.bat
    exit /b 1
)
if not exist lib\nbt\libAppFramework.A.so goto nolibs
if not exist lib\nbt\libCoreFramework.A.so goto nolibs
if not exist build\nbt mkdir build\nbt
if exist build\nbt\libDrivelogBridge.so del build\nbt\libDrivelogBridge.so
rem "call": qcc may be a batch wrapper, which would otherwise never come back to this script
call qcc -Vgcc_ntoarmv7le_gpp -O2 -Wall -D_QNX_SOURCE -fPIC -I src -shared -Wl,-soname,libDrivelogBridge.so ^
    -o build\nbt\libDrivelogBridge.so src\drivelog_bridge.cpp ^
    lib\nbt\libAppFramework.A.so lib\nbt\libCoreFramework.A.so -Wl,--allow-shlib-undefined -lsocket -lang-c++
if errorlevel 1 (
    echo [FAILED] qcc reported an error
    exit /b 1
)
if not exist build\nbt\libDrivelogBridge.so (
    echo [FAILED] qcc ended without making build\nbt\libDrivelogBridge.so
    exit /b 1
)
echo [DONE] build\nbt\libDrivelogBridge.so
if not "%MODE%"=="dist" (
    echo Only compiled. To make the package for the USB stick as well: build_nbt.bat dist
    exit /b 0
)

:pack
echo [2/2] Packing into dist\
cd /d "%HERE%"
if not exist build\nbt\libDrivelogBridge.so (
    echo [FAILED] build\nbt\libDrivelogBridge.so does not exist - build it first: build_nbt.bat dist
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%HERE%toolchain\pack_nbt.ps1" -Root "%HERE%."
if errorlevel 1 (
    echo [FAILED] packing
    exit /b 1
)
exit /b 0

:nolibs
echo Copy libAppFramework.A.so and libCoreFramework.A.so of the NBT Evo (/opt/hmi/ID5/lib) into lib\nbt
exit /b 1
