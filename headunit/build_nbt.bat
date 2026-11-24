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
cd /d "%~dp0"

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

where qcc >nul 2>nul
if errorlevel 1 (
    if exist "%QNXDIR%\qnx650-env.bat" (
        call "%QNXDIR%\qnx650-env.bat"
    ) else (
        echo qcc not found and no %QNXDIR%\qnx650-env.bat - pass the QNX folder: build_nbt.bat D:\QNX650
        exit /b 1
    )
)
if not exist lib\nbt\libAppFramework.A.so goto nolibs
if not exist lib\nbt\libCoreFramework.A.so goto nolibs
if not exist build\nbt mkdir build\nbt
qcc -Vgcc_ntoarmv7le_gpp -O2 -Wall -D_QNX_SOURCE -fPIC -I src -shared -Wl,-soname,libDrivelogBridge.so ^
    -o build\nbt\libDrivelogBridge.so src\drivelog_bridge.cpp ^
    lib\nbt\libAppFramework.A.so lib\nbt\libCoreFramework.A.so -Wl,--allow-shlib-undefined -lsocket -lang-c++
if errorlevel 1 (
    echo [FAILED] qcc reported an error
    exit /b 1
)
echo [DONE] build\nbt\libDrivelogBridge.so
if not "%MODE%"=="dist" exit /b 0

:pack
if not exist build\nbt\libDrivelogBridge.so (
    echo [FAILED] build\nbt\libDrivelogBridge.so does not exist - build it first: build_nbt.bat dist
    exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File toolchain\pack_nbt.ps1 -Root "%CD%"
if errorlevel 1 (
    echo [FAILED] packing
    exit /b 1
)
exit /b 0

:nolibs
echo Copy libAppFramework.A.so and libCoreFramework.A.so of the NBT Evo (/opt/hmi/ID5/lib) into lib\nbt
exit /b 1
