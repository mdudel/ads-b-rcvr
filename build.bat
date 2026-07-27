@echo off
REM ============================================================
REM  build.bat — Build adsb-forwarder fat JAR with Maven
REM
REM  Prerequisites:
REM    - JDK 17+ on PATH  (java, javac)
REM    - Maven 3.8+       (mvn)  — https://maven.apache.org/download.cgi
REM
REM  Output: target\adsb-forwarder-1.0.0.jar
REM ============================================================

echo [BUILD] Checking Java...
java -version 2>&1 | findstr /i "version" >nul
if errorlevel 1 (
    echo [ERROR] Java not found. Install JDK 17+ and add it to PATH.
    echo         https://adoptium.net/
    exit /b 1
)

echo [BUILD] Checking Maven...
mvn -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven not found. Install Maven 3.8+ and add it to PATH.
    echo         https://maven.apache.org/download.cgi
    exit /b 1
)

echo [BUILD] Building project...
mvn package -q -DskipTests

if errorlevel 1 (
    echo [ERROR] Build failed. Run "mvn package" for details.
    exit /b 1
)

echo.
echo [BUILD] Success!
echo         JAR: target\adsb-forwarder-1.0.0.jar
echo.
echo To run:
echo   run.bat --tcp-port 30003 --verbose
