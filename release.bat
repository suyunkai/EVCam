@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul
REM ====================================================
REM EVCam 自动化发布脚本
REM 用途：构建签名的 Release APK 并发布到 GitHub Releases
REM ====================================================

echo.
echo ====================================================
echo   EVCam 发布助手
echo ====================================================
echo.

REM 检查版本号参数
if "%1"=="" (
    echo [提示] 请输入版本号（例如: v1.0.0）
    echo.
    set /p VERSION="请输入版本号: "
    
    REM 检查用户是否输入了版本号
    if "!VERSION!"=="" (
        echo.
        echo [错误] 未输入版本号，退出。
        echo.
        pause
        exit /b 1
    )
    
    REM 如果用户没有输入 v 前缀，自动添加
    echo !VERSION! | findstr /b "v" >nul
    if errorlevel 1 (
        set VERSION=v!VERSION!
    )
) else (
    set VERSION=%1
)

REM 确认版本号
echo.
echo [确认] 将要发布版本: !VERSION!
echo.
set /p CONFIRM="确认继续？(Y/N): "
if /i not "!CONFIRM!"=="Y" (
    echo.
    echo [取消] 用户取消操作
    echo.
    pause
    exit /b 0
)
echo.
echo [信息] 版本号: !VERSION!
echo.

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

echo [完成] 推送 Tag 到远程仓库...
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
echo [提示] 请输入发布说明（按回车使用默认说明）
set /p RELEASE_NOTES="发布说明: "

if "!RELEASE_NOTES!"=="" (
    set "RELEASE_NOTES=## EVCam !VERSION! Release%nl%%nl%### 更新内容%nl%%nl%- 版本更新至 !VERSION!%nl%%nl%### 下载%nl%%nl%- [EVCam-!VERSION!-release.apk]%nl%%nl%### 签名信息%nl%%nl%- 使用 AOSP 公共测试签名%nl%- 可以覆盖安装之前的测试版本%nl%%nl%### 安装说明%nl%%nl%1. 下载 APK 文件%nl%2. 在 Android 设备上启用「未知来源」安装%nl%3. 安装并授予必要权限（相机、麦克风、存储）"
    echo [信息] 使用默认发布说明
) else (
    set "RELEASE_NOTES=## EVCam !VERSION! Release%nl%%nl%### 更新内容%nl%%nl%!RELEASE_NOTES!%nl%%nl%### 下载%nl%%nl%- [EVCam-!VERSION!-release.apk]%nl%%nl%### 签名信息%nl%%nl%- 使用 AOSP 公共测试签名%nl%"
)

echo [完成] 发布说明准备完成
echo.

REM 步骤6: 创建 GitHub Release
echo [6/6] 创建 GitHub Release...
gh release create !VERSION! ^
  "!RENAMED_APK!" ^
  --title "EVCam !VERSION!" ^
  --notes "!RELEASE_NOTES!"

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
