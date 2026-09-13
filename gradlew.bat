@echo off
setlocal EnableExtensions

set "GRADLE_VERSION=8.8"
set "ROOT=%~dp0"
set "BOOT=%ROOT%.gradle-bootstrap"
set "DIST=%BOOT%\gradle-%GRADLE_VERSION%"
set "ZIP=%BOOT%\gradle-%GRADLE_VERSION%-bin.zip"
set "GRADLE_BAT=%DIST%\bin\gradle.bat"

where java >nul 2>&1
if errorlevel 1 (
  echo [SGC] ERREUR: Java n'est pas trouve dans le PATH.
  echo [SGC] Forge 1.20.1 demande Java 17.
  echo [SGC] Installe/active Java 17 puis relance .\gradlew.bat build
  exit /b 1
)

if not exist "%GRADLE_BAT%" (
  echo [SGC] Gradle %GRADLE_VERSION% n'est pas encore present.
  echo [SGC] Telechargement automatique depuis services.gradle.org...
  if not exist "%BOOT%" mkdir "%BOOT%"

  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';" ^
    "Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP%';" ^
    "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%BOOT%' -Force"
  if errorlevel 1 (
    echo [SGC] ERREUR: impossible de telecharger ou extraire Gradle.
    echo [SGC] Verifie ta connexion Internet, antivirus/proxy, puis relance la commande.
    exit /b 1
  )
  del /q "%ZIP%" >nul 2>&1
)

call "%GRADLE_BAT%" %*
set "EXITCODE=%ERRORLEVEL%"
exit /b %EXITCODE%
