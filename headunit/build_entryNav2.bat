@echo off
rem Builds the EntryNav2 package of the head-unit plugin in Docker: dist\drivelog-bridge-entrynav2.tar
rem (libDrivelogBridge.so, manifest, XML, text and image zips, install.sh, remove.sh, MD5SUMS).
rem   build_entryNav2.bat            build the package
rem   build_entryNav2.bat check      also run the host test under qemu first (toolchain\check.sh needs Git Bash)
rem Needs Docker Desktop running, and the car's libAppFramework.A.so and libCoreFramework.A.so (/opt/hmi/lib on the
rem head unit) in lib\entrynav2. The build image is made from toolchain\Dockerfile the first time (needs internet once);
rem an existing gauge-entrynav2-tc image from the other EntryNav2 projects is used when there.
setlocal
cd /d "%~dp0"

where docker >nul 2>nul
if errorlevel 1 (
    echo [FAILED] docker not found - install Docker Desktop and start it
    exit /b 1
)
docker info >nul 2>nul
if errorlevel 1 (
    echo [FAILED] Docker is not running - start Docker Desktop
    exit /b 1
)
if not exist lib\entrynav2\libAppFramework.A.so goto nolibs
if not exist lib\entrynav2\libCoreFramework.A.so goto nolibs

set IMAGE=
docker image inspect drivelog-entrynav2-tc >nul 2>nul
if not errorlevel 1 set IMAGE=drivelog-entrynav2-tc
if "%IMAGE%"=="" (
    docker image inspect gauge-entrynav2-tc >nul 2>nul
    if not errorlevel 1 set IMAGE=gauge-entrynav2-tc
)
if "%IMAGE%"=="" (
    echo Build image not found: building drivelog-entrynav2-tc from toolchain\Dockerfile ...
    docker build -t drivelog-entrynav2-tc toolchain
    if errorlevel 1 (
        echo [FAILED] docker build
        exit /b 1
    )
    set IMAGE=drivelog-entrynav2-tc
)

rem Git Bash, not the bash of WSL (C:\Windows\System32\bash.exe), which has no Docker here
set GITBASH=%ProgramFiles%\Git\bin\bash.exe
if /i "%~1"=="check" (
    if not exist "%GITBASH%" (
        echo [FAILED] %GITBASH% not found - the host test needs Git for Windows
        exit /b 1
    )
    "%GITBASH%" toolchain/check.sh
    if errorlevel 1 (
        echo [FAILED] host test
        exit /b 1
    )
)

echo Building with %IMAGE% ...
docker run --rm -v "%CD%:/work" -w /work %IMAGE% make entrynav2-dist
if errorlevel 1 (
    echo [FAILED] make entrynav2-dist
    exit /b 1
)
if not exist dist\drivelog-bridge-entrynav2.tar (
    echo [FAILED] dist\drivelog-bridge-entrynav2.tar was not written
    exit /b 1
)
echo.
echo [DONE] %CD%\dist\drivelog-bridge-entrynav2.tar
echo Install: scp it to root@169.254.1.0:/tmp/, unpack, sh install.sh, reboot  (README.md, "Install")
exit /b 0

:nolibs
echo [FAILED] Copy libAppFramework.A.so and libCoreFramework.A.so of the EntryNav2 (/opt/hmi/lib) into lib\entrynav2
exit /b 1
