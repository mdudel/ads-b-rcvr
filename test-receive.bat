@echo off
REM ============================================================
REM  test-receive.bat — Quick test listeners (no rtl_adsb needed)
REM
REM  Requires: netcat for Windows (ncat from nmap, or similar)
REM    https://nmap.org/ncat/
REM
REM  Usage:
REM    test-receive.bat tcp [port]        Listen on TCP port (default 30003)
REM    test-receive.bat udp [port]        Listen on UDP port (default 30003)
REM
REM  The forwarder must already be running in another CMD window.
REM ============================================================

set MODE=%1
set PORT=%2
if "%PORT%"=="" set PORT=30003

if "%MODE%"=="tcp" (
    echo [TEST] Listening for TCP frames on port %PORT%...
    echo        Press Ctrl+C to stop.
    ncat -l %PORT%
    goto :eof
)

if "%MODE%"=="udp" (
    echo [TEST] Listening for UDP frames on port %PORT%...
    echo        Press Ctrl+C to stop.
    ncat -ul %PORT%
    goto :eof
)

echo Usage: test-receive.bat tcp [port]
echo        test-receive.bat udp [port]
echo.
echo Requires ncat (from nmap): https://nmap.org/ncat/
