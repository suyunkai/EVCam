@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

REM 设置 JAVA_HOME
if "%JAVA_HOME%"=="" (
    for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-21*") do (
        set "JAVA_HOME=%%d"
    )
    if "!JAVA_HOME!"=="" for /d %%d in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do (
        set "JAVA_HOME=%%d"
    )
    if "!JAVA_HOME!"=="" if exist "C:\Program Files\Java\jdk-21" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
    if "!JAVA_HOME!"=="" if exist "C:\Program Files\Java\jdk-17" set "JAVA_HOME=C:\Program Files\Java\jdk-17"
    if "!JAVA_HOME!"=="" if exist "C:\Program Files\Java\jdk-25.0.2" set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.2"
    
    if "!JAVA_HOME!"=="" (
        echo 错误: 未设置 JAVA_HOME 且未找到 JDK!
        echo 请安装 JDK 17+ 或手动设置 JAVA_HOME
        pause
        exit /b 1
    )
    echo [信息] 使用 JAVA_HOME: !JAVA_HOME!
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo ====================================================
echo   EVCam 发布助手
echo ====================================================
echo.
echo 此脚本将执行:
echo   1. 构建 Release APK
echo   2. 创建 Git Tag 并推送
echo   3. 创建 GitHub Release 并上传 APK
echo.
echo 注意: 请在运行此脚本前手动提交并推送代码
echo.

REM 检查未提交的更改（仅提示，不阻止）
git diff --quiet
set HAS_CHANGES=%ERRORLEVEL%
git diff --cached --quiet
set HAS_STAGED=%ERRORLEVEL%

if !HAS_CHANGES! NEQ 0 (
    echo [提示] 存在未提交的更改
)
if !HAS_STAGED! NEQ 0 (
    echo [提示] 存在已暂存但未提交的更改
)

REM 从 build.gradle.kts 读取当前版本号
set GRADLE_FILE=app\build.gradle.kts
if not exist "%GRADLE_FILE%" (
    echo 错误: 未找到 %GRADLE_FILE%!
    pause
    exit /b 1
)

set CURRENT_VERSION_NAME=unknown
for /f "tokens=*" %%a in ('findstr /R "versionName" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set "TEMP=%%~b"
)
set CURRENT_VERSION_NAME=!TEMP:"=!
echo [信息] 当前版本号: !CURRENT_VERSION_NAME!

REM 从版本号生成 Git Tag
set VERSION=v!CURRENT_VERSION_NAME!

echo.
echo ====================================================
echo   发布确认
echo ====================================================
echo   版本号:    !CURRENT_VERSION_NAME!
echo   Git Tag:   !VERSION!
echo ====================================================
echo.
set /p CONFIRM="是否继续? (Y/N): "
if /i not "!CONFIRM!"=="Y" (
    echo [取消] 用户取消操作
    pause
    exit /b 0
)

REM 步骤 1: 清理
echo.
echo [1/5] 清理旧构建...
call gradlew.bat clean
if errorlevel 1 goto build_error
echo [完成] 清理完成
echo.

REM 步骤 2: 构建 Release APK
echo [2/5] 构建 Release APK...
call gradlew.bat assembleRelease
if errorlevel 1 goto build_error
echo [完成] 构建成功
echo.

REM 检查 APK
set APK_PATH=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_PATH%" goto apk_not_found

REM 重命名 APK
set RENAMED_APK=app\build\outputs\apk\release\EVCam-!VERSION!-release.apk
copy "%APK_PATH%" "!RENAMED_APK!" > nul
echo [完成] APK 已重命名为: EVCam-!VERSION!-release.apk
echo.

REM 步骤 3: 创建 Git Tag
echo [3/5] 创建 Git Tag...

set "TAG_EXISTS="
for /f "delims=" %%t in ('git tag -l !VERSION!') do set "TAG_EXISTS=1"

if not defined TAG_EXISTS goto create_new_tag

echo [提示] Tag !VERSION! 已存在
set /p RETAG="是否删除并重建? (Y/N): "
if /i not "!RETAG!"=="Y" goto push_tag

git tag -d !VERSION!
if errorlevel 1 goto tag_create_error
echo [完成] 本地 Tag 已删除

:create_new_tag
git tag -a !VERSION! -m "Release !VERSION!"
if errorlevel 1 goto tag_create_error

:push_tag
echo [推送] 推送 Tag...
git push origin !VERSION!
if not errorlevel 1 goto tag_pushed

echo [提示] 远程 Tag 可能已存在, 强制推送...
git push origin !VERSION! --force
if errorlevel 1 goto tag_push_error

:tag_pushed
echo [完成] Tag 已推送
echo.

REM 步骤 4: 检查 GitHub CLI
echo [4/5] 检查 GitHub CLI...
where gh > nul 2>&1
if errorlevel 1 goto no_gh

echo [完成] GitHub CLI 可用
echo.

REM 步骤 5: 发布说明
echo [5/5] 准备发布说明...
echo.
echo [输入] 请输入发布说明 (直接回车跳过)
set /p RELEASE_NOTES="说明: "
echo.

REM 创建 GitHub Release
echo 正在创建 GitHub Release...
if "!RELEASE_NOTES!"=="" (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes ""
) else (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes "!RELEASE_NOTES!"
)
if errorlevel 1 goto release_error

echo.
echo ====================================================
echo [成功] 发布完成!
echo ====================================================
echo.
echo 版本: !VERSION!
echo APK: !RENAMED_APK!
echo.
echo 查看: gh release view !VERSION! --web
echo.
pause
exit /b 0

REM 错误处理
:build_error
echo [错误] 构建失败
pause
exit /b 1

:apk_not_found
echo [错误] 未找到 APK: %APK_PATH%
pause
exit /b 1

:tag_create_error
echo [错误] 创建 Tag 失败
echo   - 运行 git tag -d !VERSION! 删除本地 tag
pause
exit /b 1

:tag_push_error
echo [错误] 推送 Tag 失败
echo   - Tag 可能已存在于远程
echo   - 网络问题
echo   - 权限不足
pause
exit /b 1

:no_gh
echo [警告] 未找到 GitHub CLI
echo.
echo 请手动创建 Release:
echo   1. https://github.com/suyunkai/EVCam/releases/new
echo   2. Tag: !VERSION!
echo   3. 上传: !RENAMED_APK!
echo.
echo APK 位置: !RENAMED_APK!
echo.
pause
exit /b 0

:release_error
echo [错误] 创建 Release 失败
echo   - 运行 gh auth login 登录
echo   - 检查权限
echo   - Tag 可能已有 Release
pause
exit /b 1
