@echo off
setlocal EnableExtensions EnableDelayedExpansion

if "%REPO_URL%"=="" set "REPO_URL=https://github.com/tapdata/ai-groupchat.git"
if "%BRANCH%"=="" set "BRANCH=main"
if "%INSTALL_DIR%"=="" set "INSTALL_DIR=%USERPROFILE%\ai-groupchat"
if "%APP_PORT%"=="" set "APP_PORT=7860"

echo.
echo ==^> Checking prerequisites
call :ensure_git || exit /b 1
call :ensure_java || exit /b 1
call :ensure_maven || exit /b 1

echo.
echo ==^> Preparing source checkout
if exist "%INSTALL_DIR%\.git" (
  git -C "%INSTALL_DIR%" fetch origin "%BRANCH%" || exit /b 1
  git -C "%INSTALL_DIR%" checkout "%BRANCH%" || exit /b 1
  git -C "%INSTALL_DIR%" pull --ff-only origin "%BRANCH%" || exit /b 1
) else (
  if exist "%INSTALL_DIR%" (
    dir /b "%INSTALL_DIR%" 2>nul | findstr . >nul
    if not errorlevel 1 (
      echo ERROR: %INSTALL_DIR% exists and is not an empty Git checkout.
      echo Set INSTALL_DIR to another path and rerun this script.
      exit /b 1
    )
  )
  git clone --branch "%BRANCH%" "%REPO_URL%" "%INSTALL_DIR%" || exit /b 1
)

cd /d "%INSTALL_DIR%" || exit /b 1

echo.
echo ==^> Building ai-groupchat
call mvn -q -DskipTests package || exit /b 1

echo.
echo ==^> Starting ai-groupchat
echo Open http://localhost:%APP_PORT% after the server starts.
java -jar target\ai-groupchat.jar
exit /b %ERRORLEVEL%

:ensure_git
where git >nul 2>nul
if not errorlevel 1 exit /b 0
echo Git was not found. Attempting installation.
call :install_package Git.Git git
call :refresh_path
where git >nul 2>nul
if errorlevel 1 (
  echo ERROR: Git installation failed. Install Git manually from https://git-scm.com/download/win
  exit /b 1
)
exit /b 0

:ensure_java
for /f %%v in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$v=(java -version 2^>^&1 | Select-String 'version').ToString(); if ($v -match '\"(\d+)(?:\.(\d+))?') { if ($Matches[1] -eq '1') { $Matches[2] } else { $Matches[1] } } else { 0 }" 2^>nul') do set "JAVA_MAJOR=%%v"
if not defined JAVA_MAJOR set "JAVA_MAJOR=0"
if %JAVA_MAJOR% GEQ 17 exit /b 0
echo Java 17+ was not found. Attempting installation.
call :install_package EclipseAdoptium.Temurin.17.JDK openjdk17
call :refresh_path
for /f %%v in ('powershell -NoProfile -ExecutionPolicy Bypass -Command "$v=(java -version 2^>^&1 | Select-String 'version').ToString(); if ($v -match '\"(\d+)(?:\.(\d+))?') { if ($Matches[1] -eq '1') { $Matches[2] } else { $Matches[1] } } else { 0 }" 2^>nul') do set "JAVA_MAJOR=%%v"
if not defined JAVA_MAJOR set "JAVA_MAJOR=0"
if %JAVA_MAJOR% LSS 17 (
  echo ERROR: Java installation failed. Install JDK 17 or newer, then rerun this script.
  exit /b 1
)
exit /b 0

:ensure_maven
where mvn >nul 2>nul
if not errorlevel 1 exit /b 0
echo Maven was not found. Attempting installation.
call :install_package Apache.Maven maven
call :refresh_path
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven installation failed. Install Maven manually from https://maven.apache.org/download.cgi
  exit /b 1
)
exit /b 0

:install_package
set "WINGET_ID=%~1"
set "CHOCO_ID=%~2"
where winget >nul 2>nul
if not errorlevel 1 (
  winget install --id "%WINGET_ID%" --exact --accept-package-agreements --accept-source-agreements
  exit /b 0
)
where choco >nul 2>nul
if not errorlevel 1 (
  choco install -y "%CHOCO_ID%"
  exit /b 0
)
where scoop >nul 2>nul
if not errorlevel 1 (
  scoop install "%CHOCO_ID%"
  exit /b 0
)
echo ERROR: No supported Windows package manager found.
echo Install winget, Chocolatey, or Scoop, then rerun this script.
exit /b 1

:refresh_path
for /f "usebackq delims=" %%p in (`powershell -NoProfile -ExecutionPolicy Bypass -Command "[Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')" 2^>nul`) do set "PATH=%%p"
exit /b 0
