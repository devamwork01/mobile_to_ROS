@echo off
setlocal enabledelayedexpansion
rem Build the debug APK (JBR 21) and install it on every connected Android device.
rem Usage: double-click this file, or run  install-phone.bat  from a terminal.
rem Override tool paths by setting JAVA_HOME / ADB before running, if yours differ.

if not defined JAVA_HOME set "JAVA_HOME=%USERPROFILE%\.jdks\jbr-21.0.11"
if not defined ADB set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "ANDROID_DIR=%~dp0android"
set "APK=%ANDROID_DIR%\app\build\outputs\apk\debug\app-debug.apk"

pushd "%ANDROID_DIR%" || (echo Cannot find %ANDROID_DIR% & exit /b 1)

echo(
echo === Building debug APK (JBR 21) ===
call gradlew.bat assembleDebug
if errorlevel 1 (echo BUILD FAILED & popd & exit /b 1)

echo(
echo === Waiting for a device (plug in phone + accept "Allow USB debugging") ===
"%ADB%" wait-for-device

set FOUND=0
for /f "skip=1 tokens=1,2" %%a in ('"%ADB%" devices') do (
  if "%%b"=="device" (
    set FOUND=1
    echo   installing on %%a ...
    "%ADB%" -s %%a install -r "%APK%"
  )
  if "%%b"=="unauthorized" echo   %%a is UNAUTHORIZED - accept the prompt on the phone.
)
if "!FOUND!"=="0" echo No authorized device found. Check USB mode (File Transfer) and the debugging prompt.

popd
echo(
echo === Done ===
endlocal
