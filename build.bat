@echo off

set "MAVEN_CMD=mvn"
if defined MAVEN_HOME (
    set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn"
)

%MAVEN_CMD% -version >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo Maven is not installed or not found in PATH.
    exit /b 1
)

%MAVEN_CMD% clean package
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)

if not exist build (
    mkdir build
)

for /f "delims=" %%i in ('dir /b target^|findstr /i "\.jar$"') do set JAR=%%i
move /Y "target\%JAR%" "build\%JAR%"

echo Build completed. Jar moved to build\%JAR%
