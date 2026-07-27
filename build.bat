@echo off
REM ============================================================
REM  build.bat -- Build adsb-forwarder fat JAR with Maven
REM
REM  Prerequisites:
REM    - JDK 17+ on PATH  (java, javac)
REM    - Maven 3.8+       (mvn)  -- https://maven.apache.org/download.cgi
REM
REM  Output: target\adsb-forwarder-1.0.0.jar
REM
REM  Notes on the Windows-batch shape:
REM   * mvn.cmd is itself a batch script; invoking it WITHOUT `call`
REM     transfers control to it and never returns to this script.
REM     That symptom is what killed the old build.bat right after the
REM     Maven check -- fixed here by using `call mvn ...` everywhere.
REM   * `@echo off` at the top so operators see our [BUILD] lines and
REM     Maven's own output, not every REM line + expanded command.
REM     Set to `@echo on` if you want the pre-fix debugging noise back.
REM ============================================================

echo [BUILD] Checking Java...
java -version 1>nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Install JDK 17+ and add it to PATH.
    echo         https://adoptium.net/
    exit /b 1
)

echo [BUILD] Checking Maven...
call mvn -version 1>nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven not found. Install Maven 3.8+ and add it to PATH.
    echo         https://maven.apache.org/download.cgi
    exit /b 1
)

echo [BUILD] Running mvn clean package...
call mvn clean package
if errorlevel 1 (
    echo [ERROR] Build failed. Re-run 'call mvn clean package' for full output.
    exit /b 1
)

echo.
echo [BUILD] Success!
echo         JAR: target\adsb-forwarder-1.0.0.jar
echo.
echo To run:
echo   run.bat --ui
echo   run.bat --payload cot --udp 127.0.0.1:6969
