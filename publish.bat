@echo off
chcp 65001 >nul
cd /d "%~dp0"
where git >nul 2>nul || (echo [ERROR] 未找到 git，请先安装 Git for Windows：https://git-scm.com/download/win & pause & exit /b 1)
git init
git add .
git commit -m "HOScrcpy-Sun 0.136.46-navfix"
git branch -M main
git remote remove origin 2>nul
git remote add origin https://github.com/Mangoman2008/HOScrcpy-Sun.git
git push -u origin main
pause