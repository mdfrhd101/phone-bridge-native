@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo        PhoneBridge 1-Click Push ^& Update Deployer
echo ========================================================
echo.

set MSG=%*
if "%MSG%"=="" (
    set /p MSG="Enter commit message (or press ENTER for auto): "
)
if "%MSG%"=="" (
    set MSG=Update PhoneBridge %date% %time%
)

echo [1/3] Staging changes...
git add .

echo [2/3] Committing changes...
git commit -m "%MSG%"

echo [3/3] Pushing to GitHub...
git push origin main

if %ERRORLEVEL% equ 0 (
    echo.
    echo ========================================================
    echo  SUCCESS: Code pushed to GitHub!
    echo  GitHub Actions is now compiling the release APK.
    echo  Both Realme and Xperia will receive update notifications!
    echo ========================================================
) else (
    echo.
    echo [!] Push failed. Please check your internet connection or git status.
)

echo.
pause
