@echo off
setlocal EnableExtensions EnableDelayedExpansion

if "%REPO_URL%"=="" set "REPO_URL=https://github.com/tapdata/ai-groupchat.git"
if "%BRANCH%"=="" set "BRANCH=main"
if "%INSTALL_DIR%"=="" set "INSTALL_DIR=%USERPROFILE%\ai-groupchat"
if "%APP_PORT%"=="" set "APP_PORT=7860"
if "%TOOLS_DIR%"=="" set "TOOLS_DIR=%USERPROFILE%\.ai-groupchat-tools"
if "%MAVEN_VERSION%"=="" set "MAVEN_VERSION=3.9.11"
if "%MAVEN_HOME%"=="" set "MAVEN_HOME=%TOOLS_DIR%\apache-maven-%MAVEN_VERSION%"

echo.
echo ==^> Checking prerequisites
echo     Checking Git.
call :ensure_git || exit /b 1
echo     Checking Java 17+.
call :ensure_java || exit /b 1
echo     Checking Maven.
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
if not errorlevel 1 (
  for /f "delims=" %%v in ('git --version') do echo     Git found: %%v
  exit /b 0
)
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
if %JAVA_MAJOR% GEQ 17 (
  for /f "delims=" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do echo     Java found: %%v
  exit /b 0
)
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
if not errorlevel 1 (
  for /f "delims=" %%v in ('mvn -version 2^>nul ^| findstr /b /c:"Apache Maven"') do echo     Maven found: %%v
  exit /b 0
)
if exist "%MAVEN_HOME%\bin\mvn.cmd" (
  set "PATH=%MAVEN_HOME%\bin;%PATH%"
  for /f "delims=" %%v in ('mvn -version 2^>nul ^| findstr /b /c:"Apache Maven"') do echo     Maven found: %%v
  exit /b 0
)
echo Maven was not found. Downloading Apache Maven %MAVEN_VERSION%.
echo This avoids package-manager Maven dependencies that may install another JDK.
if "%APACHE_MAVEN_URL%"=="" set "APACHE_MAVEN_URL=https://dlcdn.apache.org/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%" || exit /b 1
set "MAVEN_ZIP=%TEMP%\apache-maven-%MAVEN_VERSION%-%RANDOM%.zip"
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri '%APACHE_MAVEN_URL%' -OutFile '%MAVEN_ZIP%' -UseBasicParsing } catch { exit 1 }"
if errorlevel 1 (
  set "APACHE_MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "try { Invoke-WebRequest -Uri '%APACHE_MAVEN_URL%' -OutFile '%MAVEN_ZIP%' -UseBasicParsing } catch { exit 1 }" || exit /b 1
)
if exist "%MAVEN_HOME%" rmdir /s /q "%MAVEN_HOME%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%TOOLS_DIR%' -Force" || exit /b 1
del "%MAVEN_ZIP%" >nul 2>nul
set "PATH=%MAVEN_HOME%\bin;%PATH%"
where mvn >nul 2>nul
if errorlevel 1 (
  echo ERROR: Maven installation failed. Install Maven manually from https://maven.apache.org/download.cgi
  exit /b 1
)
for /f "delims=" %%v in ('mvn -version 2^>nul ^| findstr /b /c:"Apache Maven"') do echo     Maven found: %%v
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
