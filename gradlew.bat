@rem Photo Fix Windows build bootstrap
@echo off
setlocal

where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)

echo.
echo Gradle is not installed and this repository does not yet contain the binary wrapper JAR.
echo Open this project in Android Studio once, or install Gradle and run:
echo   gradle wrapper --gradle-version 9.0.0
echo Then commit gradle\wrapper and the generated wrapper scripts.
echo.
exit /b 1
