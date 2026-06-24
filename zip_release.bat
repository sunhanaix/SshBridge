@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  zip_release.bat — Package APK + EXE into apk_and_exe.zip
REM  Usage: zip_release.bat [apk_path] [exe_path]
REM  If no args given, auto-detect latest builds.
REM ============================================================

set "APK_SRC=%~1"
set "EXE_SRC=%~2"
set "OUT_DIR=%~dp0dist"
set "ZIP_NAME=apk_and_exe.zip"

REM --- Resolve script-relative paths ---------------------------------
set "SCRIPT_DIR=%~dp0"
set "APK_BUILD_DIR=%SCRIPT_DIR%..\ssh_apk\app\build\outputs\apk\release"
set "EXE_BUILD_DIR=%SCRIPT_DIR%dist"

REM --- Auto-detect APK if not specified -------------------------------
if "%APK_SRC%"=="" (
    if exist "%APK_BUILD_DIR%\app-release-signed.apk" (
        set "APK_SRC=%APK_BUILD_DIR%\app-release-signed.apk"
    ) else if exist "%APK_BUILD_DIR%\app-release-unsigned.apk" (
        set "APK_SRC=%APK_BUILD_DIR%\app-release-unsigned.apk"
    )
)

REM --- Auto-detect EXE if not specified -------------------------------
if "%EXE_SRC%"=="" (
    if exist "%EXE_BUILD_DIR%\scrt_gui.exe" (
        set "EXE_SRC=%EXE_BUILD_DIR%\scrt_gui.exe"
    )
)

REM --- Validation -----------------------------------------------------
if "%APK_SRC%"=="" (
    echo [ERROR] APK not found. Build it first or pass path as arg.
    echo   Looked in: %APK_BUILD_DIR%
    goto :end
)
if "%EXE_SRC%"=="" (
    echo [ERROR] scrt_gui.exe not found. Build it first or pass path as arg.
    echo   Looked in: %EXE_BUILD_DIR%
    goto :end
)
if not exist "%APK_SRC%" (
    echo [ERROR] APK not found: %APK_SRC%
    goto :end
)
if not exist "%EXE_SRC%" (
    echo [ERROR] EXE not found: %EXE_SRC%
    goto :end
)

REM --- Create output directory ----------------------------------------
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

REM --- Clean old zip --------------------------------------------------
if exist "%OUT_DIR%\%ZIP_NAME%" del "%OUT_DIR%\%ZIP_NAME%"

REM --- Pack -----------------------------------------------------------
echo [*] Packing ...
echo   APK: %APK_SRC%
echo   EXE: %EXE_SRC%
echo   OUT: %OUT_DIR%\%ZIP_NAME%

REM Use PowerShell for zip (built-in on Windows 10+)
powershell -NoProfile -Command ^
  "$out='%OUT_DIR%\%ZIP_NAME%';" ^
  "$apk='%APK_SRC%';" ^
  "$exe='%EXE_SRC%';" ^
  "Compress-Archive -LiteralPath @($apk,$exe) -DestinationPath $out -Force;" ^
  "if ($?) { Write-Host '[OK] Created: ' $out } else { Write-Host '[FAIL] Zip failed' }"

:end
endlocal
