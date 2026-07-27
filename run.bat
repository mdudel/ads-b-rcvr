@echo off
REM ============================================================
REM  run.bat — Launch the ADS-B RTL-SDR Forwarder on Windows
REM
REM  Usage:
REM    run.bat [options]
REM
REM  Options:
REM    --udp <host:port>           UDP unicast destination
REM    --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
REM    --tcp-port <port>           TCP server port
REM    --rtl-device <index>        RTL-SDR device index (default: 0)
REM    --rtl-path <dir>            Folder containing rtl_adsb.exe (if not on PATH)
REM    --gain <value>              Gain e.g. 40, or omit for auto
REM    --format <avr|raw>          Frame format (default: avr)
REM    --verbose                   Print frames to console
REM
REM  Examples:
REM    run.bat --tcp-port 30003 --verbose
REM    run.bat --udp 127.0.0.1:30003
REM    run.bat --multicast 239.1.1.1:30003 --tcp-port 30003
REM    run.bat --rtl-path "C:\rtl-sdr\bin" --tcp-port 30003 --gain 40 --verbose
REM ============================================================

set JAR=target\adsb-forwarder-1.0.0.jar

if not exist "%JAR%" (
    echo [ERROR] JAR not found: %JAR%
    echo         Run build.bat first.
    exit /b 1
)

java -jar "%JAR%" %*
