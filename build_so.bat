@echo off
setlocal enabledelayedexpansion

echo === Building Rust proxy library for Android (cdylib) ===
echo === Architectures: arm64-v8a, armeabi-v7a, x86_64 (min SDK 26) ===
echo.

set "ROOT_DIR=%~dp0"

if defined ANDROID_SDK_ROOT set "SDK_PATH=%ANDROID_SDK_ROOT%"
if not defined SDK_PATH if defined ANDROID_HOME set "SDK_PATH=%ANDROID_HOME%"
if not defined SDK_PATH set "SDK_PATH=%LOCALAPPDATA%\Android\Sdk"

if defined ANDROID_NDK_HOME (
    set "ANDROID_NDK_HOME=%ANDROID_NDK_HOME%"
    set "NDK_HOME=%ANDROID_NDK_HOME%"
    goto :NdkReady
)

set "NDK_ROOT=%SDK_PATH%\ndk"

if not exist "%NDK_ROOT%" (
    echo Error: NDK folder not found at %NDK_ROOT%
    exit /b 1
)

for /f "delims=" %%D in ('dir /b /ad /o-n "%NDK_ROOT%"') do (
    set "NDK_VER=%%D"
    goto :FoundNDK
)
:FoundNDK
echo Using NDK: %NDK_VER%
set "ANDROID_NDK_HOME=%NDK_ROOT%\%NDK_VER%"
set "NDK_HOME=%ANDROID_NDK_HOME%"
:NdkReady

where cargo >nul 2>nul
if %errorlevel% neq 0 (
    echo Error: cargo not found in PATH
    exit /b 1
)
where cargo-ndk >nul 2>nul
if %errorlevel% neq 0 (
    echo Installing cargo-ndk...
    cargo install cargo-ndk
    if %errorlevel% neq 0 (
        echo Error: failed to install cargo-ndk
        exit /b 1
    )
)

echo Ensuring Rust targets are installed...
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android >nul 2>nul

if not exist "%ROOT_DIR%app\src\main\jniLibs\arm64-v8a"   mkdir "%ROOT_DIR%app\src\main\jniLibs\arm64-v8a"
if not exist "%ROOT_DIR%app\src\main\jniLibs\armeabi-v7a" mkdir "%ROOT_DIR%app\src\main\jniLibs\armeabi-v7a"
if not exist "%ROOT_DIR%app\src\main\jniLibs\x86_64" mkdir "%ROOT_DIR%app\src\main\jniLibs\x86_64"

cd /d "%ROOT_DIR%"

echo.
echo [1/3] Building arm64-v8a (release, API 26)...
cargo ndk ^
  -t arm64-v8a ^
  --platform 26 ^
  -o "%ROOT_DIR%app\src\main\jniLibs" ^
  build --release

if %errorlevel% neq 0 (
    echo BUILD FAILED for arm64-v8a!
    exit /b 1
)

echo.
echo [2/3] Building armeabi-v7a (release, API 26)...
cargo ndk ^
  -t armeabi-v7a ^
  --platform 26 ^
  -o "%ROOT_DIR%app\src\main\jniLibs" ^
  build --release

if %errorlevel% neq 0 (
    echo BUILD FAILED for armeabi-v7a!
    exit /b 1
)

echo.
echo [3/3] Building x86_64 (release, API 26)...
cargo ndk ^
  -t x86_64 ^
  --platform 26 ^
  -o "%ROOT_DIR%app\src\main\jniLibs" ^
  build --release

if %errorlevel% neq 0 (
    echo BUILD FAILED for x86_64!
    exit /b 1
)

for %%F in ("%ROOT_DIR%app\src\main\jniLibs\arm64-v8a\libtgwsproxy.so") do (
    echo arm64-v8a:   OK [%%~zF bytes]
)
for %%F in ("%ROOT_DIR%app\src\main\jniLibs\armeabi-v7a\libtgwsproxy.so") do (
    echo armeabi-v7a: OK [%%~zF bytes]
)
for %%F in ("%ROOT_DIR%app\src\main\jniLibs\x86_64\libtgwsproxy.so") do (
    echo x86_64:       OK [%%~zF bytes]
)

echo.
echo === BUILD SUCCESS ===
echo   arm64-v8a:   app\src\main\jniLibs\arm64-v8a\libtgwsproxy.so
echo   armeabi-v7a: app\src\main\jniLibs\armeabi-v7a\libtgwsproxy.so
echo   x86_64:       app\src\main\jniLibs\x86_64\libtgwsproxy.so
echo.
exit /b 0
