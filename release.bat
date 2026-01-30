@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
REM ====================================================
REM EVCam Release Script
REM ====================================================

set GRADLE_FILE=app\build.gradle.kts

echo.
echo ====================================================
echo   EVCam 发布助手
echo ====================================================
echo.

REM ====================================================
REM Read version info
REM ====================================================
echo [信息] 读取当前版本信息...

REM Read versionCode
set CURRENT_VERSION_CODE=0
for /f "tokens=*" %%a in ('findstr /R "versionCode" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set CURRENT_VERSION_CODE=%%b
)
echo [信息] 当前 versionCode: !CURRENT_VERSION_CODE!

REM Read versionName
set CURRENT_VERSION_NAME=unknown
for /f "tokens=*" %%a in ('findstr /R "versionName" "%GRADLE_FILE%"') do (
    set "LINE=%%a"
)
for /f "tokens=3 delims= " %%b in ("!LINE!") do (
    set "TEMP=%%~b"
)
REM 去除引号
set CURRENT_VERSION_NAME=!TEMP:"=!
echo [信息] 当前 versionName: !CURRENT_VERSION_NAME!

REM 检查是否包含 -test- 后缀，提取基础版本号
set BASE_VERSION_NAME=!CURRENT_VERSION_NAME!
set IS_TEST_VERSION=0
echo !CURRENT_VERSION_NAME! | findstr /C:"-test-" > nul
if !ERRORLEVEL! EQU 0 (
    set IS_TEST_VERSION=1
    REM 提取 -test- 之前的部分作为基础版本号
    for /f "tokens=1 delims=-" %%b in ("!CURRENT_VERSION_NAME!") do (
        set BASE_VERSION_NAME=%%b
    )
    echo [信息] 检测到测试版本，基础版本号: !BASE_VERSION_NAME!
)
echo.

REM ====================================================
REM 自动递增 versionCode
REM ====================================================
set /a NEW_VERSION_CODE=!CURRENT_VERSION_CODE!+1
echo [自动] 新 versionCode: !NEW_VERSION_CODE! (自动递增)

REM ====================================================
REM 用户输入 versionName
REM ====================================================
echo.
echo [提示] 请输入 versionName（例如: 1.0.3）
echo        直接按回车将使用: !BASE_VERSION_NAME!
set /p NEW_VERSION_NAME="versionName: "

if "!NEW_VERSION_NAME!"=="" (
    set NEW_VERSION_NAME=!BASE_VERSION_NAME!
    echo [信息] 使用版本名: !NEW_VERSION_NAME!
)

REM 设置 Git Tag 版本号（添加 v 前缀）
set VERSION=v!NEW_VERSION_NAME!

echo.
echo ====================================================
echo   版本确认
echo ====================================================
echo   versionCode: !CURRENT_VERSION_CODE! -^> !NEW_VERSION_CODE!
echo   versionName: !CURRENT_VERSION_NAME! -^> !NEW_VERSION_NAME!
echo   Git Tag:     !VERSION!
echo ====================================================
echo.
set /p CONFIRM="确认继续？(Y/N): "
if /i not "!CONFIRM!"=="Y" (
    echo.
    echo [取消] 用户取消操作
    echo.
    pause
    exit /b 0
)

REM ====================================================
REM 更新 build.gradle.kts 文件
REM ====================================================
echo.
echo [更新] 正在更新 %GRADLE_FILE%...

REM 使用 PowerShell 更新文件内容
powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionCode = %CURRENT_VERSION_CODE%', 'versionCode = %NEW_VERSION_CODE%' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 (
    echo [错误] 更新 versionCode 失败！
    pause
    exit /b 1
)

powershell -Command "(Get-Content '%GRADLE_FILE%') -replace 'versionName = \"%CURRENT_VERSION_NAME%\"', 'versionName = \"%NEW_VERSION_NAME%\"' | Set-Content '%GRADLE_FILE%' -Encoding UTF8"
if errorlevel 1 (
    echo [错误] 更新 versionName 失败！
    pause
    exit /b 1
)

echo [完成] build.gradle.kts 已更新
echo.
echo [信息] 版本号: !VERSION!

REM 步骤0: 检查是否有未提交的更改
echo [0/6] 检查 Git 状态...
git diff --quiet
set HAS_CHANGES=%ERRORLEVEL%
git diff --cached --quiet
set HAS_STAGED=%ERRORLEVEL%

if !HAS_CHANGES! NEQ 0 (
    echo [提示] 检测到未暂存的更改
) else if !HAS_STAGED! NEQ 0 (
    echo [提示] 检测到已暂存的更改
) else (
    echo [信息] 工作区干净，无需提交
    goto skip_commit
)

echo.
set /p DO_COMMIT="是否提交这些更改？(Y/N): "
if /i not "!DO_COMMIT!"=="Y" (
    echo [跳过] 跳过提交步骤
    goto skip_commit
)

echo.
echo [提示] 请输入提交信息（例如: 修复摄像头预览问题）
set /p COMMIT_MSG="提交信息: "

if "!COMMIT_MSG!"=="" (
    set COMMIT_MSG=Release !VERSION!
    echo [信息] 使用默认提交信息: !COMMIT_MSG!
)

echo [提交] 正在提交更改...
git add .
git commit -m "!COMMIT_MSG!"
if errorlevel 1 (
    echo [错误] 提交失败！
    pause
    exit /b 1
)

echo [推送] 推送到远程仓库...
REM 获取当前分支名
for /f "tokens=*" %%i in ('git branch --show-current') do set CURRENT_BRANCH=%%i
git push origin !CURRENT_BRANCH!
if errorlevel 1 (
    echo [警告] 推送失败，但继续发布流程...
)

echo [完成] 代码已提交并推送
echo.

:skip_commit

REM 步骤1: 清理旧的构建
echo [1/6] 清理旧的构建文件...
call gradlew.bat clean
if errorlevel 1 (
    echo [错误] 清理失败！
    exit /b 1
)
echo [完成] 清理完成
echo.

REM 步骤2: 构建 Release APK
echo [2/6] 构建签名的 Release APK...
call gradlew.bat assembleRelease
if errorlevel 1 (
    echo [错误] 构建失败！
    exit /b 1
)
echo [完成] 构建成功
echo.

REM 检查 APK 是否生成
set APK_PATH=app\build\outputs\apk\release\app-release.apk
if not exist "%APK_PATH%" (
    echo [错误] 找不到生成的 APK 文件: %APK_PATH%
    exit /b 1
)

REM 重命名 APK
set RENAMED_APK=app\build\outputs\apk\release\EVCam-!VERSION!-release.apk
copy "%APK_PATH%" "!RENAMED_APK!" > nul
echo [完成] APK 已重命名为: EVCam-!VERSION!-release.apk
echo.

REM 步骤3: 创建并推送 Git Tag
echo [3/6] 创建 Git Tag...
git tag -a !VERSION! -m "Release !VERSION!"
if errorlevel 1 (
    echo [警告] Tag 可能已存在，继续...
)

echo [推送] 推送 Tag 到远程仓库...
git push origin !VERSION!
if errorlevel 1 (
    echo [错误] 推送 Tag 失败！
    echo 可能的原因：
    echo   1. Tag 已存在于远程仓库
    echo   2. 网络连接问题
    echo   3. 没有权限
    exit /b 1
)
echo [完成] Tag 推送成功
echo.

REM 步骤4: 检查 GitHub CLI
echo [4/6] 检查 GitHub CLI...
where gh > nul 2>&1
if errorlevel 1 (
    echo [警告] 未找到 GitHub CLI (gh^)
    echo.
    echo 请手动创建 Release：
    echo   1. 访问: https://github.com/suyunkai/EVCam/releases/new
    echo   2. 选择 Tag: !VERSION!
    echo   3. 上传文件: !RENAMED_APK!
    echo.
    echo 或者安装 GitHub CLI: https://cli.github.com/
    echo.
    echo APK 文件位置:
    echo !RENAMED_APK!
    echo.
    pause
    exit /b 0
)
echo [完成] GitHub CLI 可用
echo.

REM 步骤5: 准备 Release Notes
echo [5/6] 准备发布说明...
echo.
echo [提示] 请输入发布说明（直接按回车则留空）
set /p RELEASE_NOTES="发布说明: "

if "!RELEASE_NOTES!"=="" (
    set "RELEASE_NOTES="
    echo [信息] 发布说明为空
) else (
    echo [信息] 发布说明: !RELEASE_NOTES!
)
echo.

REM 步骤6: 创建 GitHub Release
echo [6/6] 创建 GitHub Release...
if "!RELEASE_NOTES!"=="" (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes ""
) else (
    gh release create !VERSION! "!RENAMED_APK!" --title "EVCam !VERSION!" --notes "!RELEASE_NOTES!"
)

if errorlevel 1 (
    echo [错误] 创建 Release 失败！
    echo 请检查：
    echo   1. 是否已登录 GitHub CLI (运行: gh auth login)
    echo   2. 是否有仓库权限
    echo   3. Tag 是否已经有 Release
    exit /b 1
)

echo.
echo ====================================================
echo [成功] Release 发布完成！
echo ====================================================
echo.
echo 版本: !VERSION!
echo APK: !RENAMED_APK!
echo.
echo 查看 Release: gh release view !VERSION! --web
echo.

exit /b 0
