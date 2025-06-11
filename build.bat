@echo off
if not defined MAVEN_HOME (
    mvn -version >nul 2>&1
    if %ERRORLEVEL% neq 0 (
        echo Maven is not installed.
        exit /b 1
    )
)

mvn clean package
if %ERRORLEVEL% neq 0 (
    echo Build failed.
    exit /b %ERRORLEVEL%
)

if not exist build (
    mkdir build
)

for /f "delims=" %%i in ('dir /b target^|findstr /i "\.jar$"') do set JAR=%%i
move /Y target\%JAR% build\%JAR%

echo Build completed. Jar moved to build\%JAR%
