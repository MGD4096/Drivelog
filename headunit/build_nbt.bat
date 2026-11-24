@echo off
rem Builds the NBT Evo plugin with QNX SDP 6.5.0 into build\nbt\libDrivelogBridge.so.
rem   build_nbt.bat              (QNX in C:\QNX650 or qcc already on the PATH)
rem   build_nbt.bat D:\QNX650
rem The car's libAppFramework.A.so and libCoreFramework.A.so (/opt/hmi/ID5/lib) go into lib\nbt first.
rem Packing the archive (make nbt-dist) needs tar and md5sum: run it in Git Bash after this, or copy by hand.
setlocal
cd /d "%~dp0"
set QNXDIR=%~1
if "%QNXDIR%"=="" set QNXDIR=C:\QNX650
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
exit /b 0
:nolibs
echo Copy libAppFramework.A.so and libCoreFramework.A.so of the NBT Evo (/opt/hmi/ID5/lib) into lib\nbt
exit /b 1
