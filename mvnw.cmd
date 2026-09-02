@echo off
setlocal
set "WRAPPER_DIR=%~dp0.mvn\wrapper"
set "MAVEN_HOME=%WRAPPER_DIR%\apache-maven-3.9.11"
set "ARCHIVE=%WRAPPER_DIR%\apache-maven-3.9.11-bin.zip"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  where powershell.exe >nul 2>&1 || (
    echo PowerShell is required to download Maven. 1>&2
    exit /b 1
  )
  if not exist "%WRAPPER_DIR%" mkdir "%WRAPPER_DIR%"
  echo Downloading Apache Maven 3.9.11...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip' -OutFile '%ARCHIVE%'; Expand-Archive -LiteralPath '%ARCHIVE%' -DestinationPath '%WRAPPER_DIR%' -Force; Remove-Item -LiteralPath '%ARCHIVE%'"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
