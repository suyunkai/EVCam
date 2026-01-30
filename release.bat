@echo off
echo Script starting...
setlocal enabledelayedexpansion

set GRADLE_FILE=app\build.gradle.kts
if not exist "%GRADLE_FILE%" (
    echo ERROR: %GRADLE_FILE% not found!
    pause
    exit /b 1
)

echo.
echo ====================================================
echo   EVCam Release Helper
echo ====================================================
echo.

echo [Info] Reading version info...

REM Read versionCode
set CURRENT_VERSION_CODE=0
for /f "tokens=*" %%a in ('findstr /R "versionCode" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set CURRENT_VERSION_CODE=%%b
)
echo [Info] Current versionCode: !CURRENT_VERSION_CODE!

REM Read versionName
set CURRENT_VERSION_NAME=unknown
for /f "tokens=*" %%a in ('findstr /R "versionName" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set "TEMP=%%~b"
)
set CURRENT_VERSION_NAME=!TEMP:"=!
echo [Info] Current versionName: !CURRENT_VERSION_NAME!

REM Check for -test- suffix
set BASE_VERSION_NAME=!CURRENT_VERSION_NAME!
set IS_TEST_VERSION=0
echo !CURRENT_VERSION_NAME! | findstr /C:"-test-" > nul
if !ERRORLEVEL! EQU 0 (
    set IS_TEST_VERSION=1
    for /f "tokens=1 delims=-" %%b in ("!CURRENT_VERSION_NAME!") do set BASE_VERSION_NAME=%%b
    echo [Info] Test version detected, base version: !BASE_VERSION_NAME!
)
echo.

REM Auto increment versionCode
set /a NEW_VERSION_CODE=!CURRENT_VERSION_CODE!+1
echo [Auto] New versionCode: !NEW_VERSION_CODE!

REM User input versionName
echo.
echo [Input] Enter versionName (e.g. 1.0.3), press Enter for: !BASE_VERSION_NAME!
set /p NEW_VERSION_NAME="versionName: "
if "!NEW_VERSION_NAME!"=="" set NEW_VERSION_NAME=!BASE_VERSION_NAME!
echo [Info] Using version: !NEW_VERSION_NAME!

REM Set Git Tag
set VERSION=v!NEW_VERSION_NAME!

echo.
echo ====================================================
echo   Version Confirmation
echo ====================================================
echo   versionCode: !CURRENT_VERSION_CODE! -- !NEW_VERSION_CODE!
echo   versionName: !CURRENT_VERSION_NAME! -- !NEW_VERSION_NAME!
echo   Git Tag:     !VERSION!
echo ====================================================
echo.
set /p CONFIRM="Continue? (Y/N): "
if /i not "!CONFIRM!"=="Y" (
    echo [Cancel] User cancelled
    pause
    exit /b 0
)

REM Update build.gradle.kts
echo.
echo [Update] Updating %GRADLE_FILE%...
powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionCode = %CURRENT_VERSION_CODE%', 'versionCode = %NEW_VERSION_CODE%' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 goto update_error
powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionName = \"%CURRENT_VERSION_NAME%\"', 'versionName = \"%NEW_VERSION_NAME%\"' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 goto update_error
echo [Done] build.gradle.kts updated
echo.

REM Step 0: Check uncommitted changes
echo [0/6] Checking Git status...
git diff --quiet
set HAS_CHANGES=%ERRORLEVEL%
git diff --cached --quiet
set HAS_STAGED=%ERRORLEVEL%

set NEED_COMMIT=0
if !HAS_CHANGES! NEQ 0 set NEED_COMMIT=1
if !HAS_STAGED! NEQ 0 set NEED_COMMIT=1

if !NEED_COMMIT! EQU 0 (
    echo [Info] Working directory clean
    goto skip_commit
)
echo [Notice] Uncommitted changes detected

echo.
set /p DO_COMMIT="Commit changes? (Y/N): "
if /i not "!DO_COMMIT!"=="Y" goto skip_commit

echo.
echo [Input] Enter commit message (press Enter for default: Release !VERSION!)
set /p COMMIT_MSG="Message: "
if "!COMMIT_MSG!"=="" set COMMIT_MSG=Release !VERSION!

echo [Commit] Committing...
git add .
git commit -m "!COMMIT_MSG!"
if errorlevel 1 goto commit_error

echo [Push] Pushing to remote...
for /f "tokens=*" %%i in ('git branch --show-current') do set CURRENT_BRANCH=%%i
git push origin !CURRENT_BRANCH!
echo [Done] Code pushed
echo.

:skip_commit

REM Step 1: Clean
echo [1/6] Cleaning old build...
call gradlew.bat clean
if errorlevel 1 goto build_error
echo [Done] Clean complete
echo.

REM Step 2: Build Release APK
echo [2/6] Building Release APK...
call gradlew.bat assembleRelease
if errorlevel 1 goto build_error
echo [Done] Build successful
echo.

REM Check APK
set APK_PATH=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_PATH%" goto apk_not_found

REM Rename APK
set RENAMED_APK=app\build\outputs\apk\release\EVCam-!VERSION!-release.apk
copy "%APK_PATH%" "!RENAMED_APK!" > nul
echo [Done] APK renamed to: EVCam-!VERSION!-release.apk
echo.

REM Step 3: Create Git Tag
echo [3/6] Creating Git Tag...
git tag -a !VERSION! -m "Release !VERSION!"
echo [Push] Pushing Tag...
git push origin !VERSION!
if errorlevel 1 goto tag_error
echo [Done] Tag pushed
echo.

REM Step 4: Check GitHub CLI
echo [4/6] Checking GitHub CLI...
where gh > nul 2>&1
if errorlevel 1 goto no_gh

echo [Done] GitHub CLI available
echo.

REM Step 5: Release Notes
echo [5/6] Preparing release notes...
echo.
echo [Input] Enter release notes (press Enter to skip)
set /p RELEASE_NOTES="Notes: "
echo.

REM Step 6: Create GitHub Release
echo [6/6] Creating GitHub Release...
if "!RELEASE_NOTES!"=="" (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes ""
) else (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes "!RELEASE_NOTES!"
)
if errorlevel 1 goto release_error

echo.
echo ====================================================
echo [Success] Release published!
echo ====================================================
echo.
echo Version: !VERSION!
echo APK: !RENAMED_APK!
echo.
echo View: gh release view !VERSION! --web
echo.
exit /b 0

REM Error handlers
:update_error
echo [Error] Failed to update build.gradle.kts
pause
exit /b 1

:commit_error
echo [Error] Commit failed
pause
exit /b 1

:build_error
echo [Error] Build failed
exit /b 1

:apk_not_found
echo [Error] APK not found: %APK_PATH%
exit /b 1

:tag_error
echo [Error] Failed to push tag
echo   - Tag may already exist
echo   - Network issue
echo   - Permission denied
exit /b 1

:no_gh
echo [Warning] GitHub CLI not found
echo.
echo Create release manually:
echo   1. https://github.com/suyunkai/EVCam/releases/new
echo   2. Tag: !VERSION!
echo   3. Upload: !RENAMED_APK!
echo.
echo APK location: !RENAMED_APK!
echo.
pause
exit /b 0

:release_error
echo [Error] Failed to create release
echo   - Run: gh auth login
echo   - Check permissions
echo   - Tag may have release
exit /b 1
