@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ---- locate git (portable copy first) ----
set "GIT=D:\HOScrcpy\tools\git\cmd\git.exe"
if not exist "%GIT%" set "GIT=git"
"%GIT%" --version >nul 2>nul
if errorlevel 1 (
  echo [ERROR] git not found. Install Git for Windows or unzip portable git to D:\HOScrcpy\tools\git
  pause & exit /b 1
)

set "OWNER=Mangoman2008"
set "REPO=HOScrcpy-Sun"

echo === 1/4 init and commit ===
"%GIT%" init >nul 2>nul
"%GIT%" config user.name "%OWNER%"
"%GIT%" config user.email "%OWNER%@users.noreply.github.com"
"%GIT%" add .
"%GIT%" commit -m "HOScrcpy-Sun 0.136.46-navfix" >nul 2>nul
"%GIT%" branch -M main

echo === 2/4 token ===
set "TOKEN="
if not "%~1"=="" (
  set "TOKEN=%~1"
) else (
  set /p TOKEN=Paste GitHub PAT then Enter (empty = use saved credentials): 
)

if defined TOKEN (
  echo === 3/4 ensure repo exists ===
  curl -s -o nul -w "check: %%{http_code}\n" -H "Authorization: Bearer %TOKEN%" "https://api.github.com/repos/%OWNER%/%REPO%"
  curl -s -o nul -w "create: %%{http_code}\n" -X POST -H "Authorization: Bearer %TOKEN%" -H "Accept: application/vnd.github+json" -d "{\"name\":\"%REPO%\",\"private\":false}" "https://api.github.com/user/repos"
  echo === 4/4 push ===
  "%GIT%" push "https://%TOKEN%@github.com/%OWNER%/%REPO%.git" main
) else (
  echo === 3/4 default remote ===
  "%GIT%" remote remove origin >nul 2>nul
  "%GIT%" remote add origin "https://github.com/%OWNER%/%REPO%.git"
  echo === 4/4 push (enter username and PAT as password) ===
  "%GIT%" push -u origin main
)

echo.
if errorlevel 1 (
  echo [FAILED] push failed, see messages above
) else (
  echo [OK] published: https://github.com/%OWNER%/%REPO%
)
pause
