@echo off
REM ============================================================
REM  run.bat -- Launch the ADS-B RTL-SDR Forwarder on Windows
REM
REM  Usage:
REM    run.bat [options]
REM
REM  User interface:
REM    --ui                        Open the Swing UI
REM                                (Tracks / Connectors / Settings / About).
REM                                Persistent connectors live in
REM                                %USERPROFILE%\.adsb-rcvr\adsb-rcvr.properties
REM
REM  One-shot output (attached as transient in-memory connectors):
REM    --udp <host:port>           UDP unicast destination
REM    --multicast <group:port>    UDP multicast group (e.g. 239.1.1.1:30003)
REM    --tcp-port <port>           TCP server port (clients connect to receive)
REM
REM  Payload format for the CLI-provided sinks:
REM    --payload <avr|json|cot>    Wire format (default: json)
REM                                avr  = raw hex frames from rtl_adsb
REM                                json = decoded JSON
REM                                cot  = CoT XML (WinTAK / ATAK / GCCS-J COP)
REM
REM  CoT initial settings (also editable live in the Settings dock):
REM    --cot-affiliation <friendly|neutral|hostile|unknown|pending>
REM    --cot-category    <civilian|military>
REM    --cot-stale-air    <seconds>
REM    --cot-stale-ground <seconds>
REM
REM  RTL-SDR options:
REM    --rtl-device <index>        RTL-SDR device index (default: 0)
REM    --rtl-path <dir>            Folder containing rtl_adsb.exe
REM                                (default: current directory)
REM    --gain <value>              Gain e.g. 40, or omit for auto
REM    --format <avr|raw>          rtl_adsb frame format (default: avr)
REM
REM  Other:
REM    --verbose                   Print frames to console
REM    -h, --help                  Show full help
REM
REM  Examples:
REM    run.bat --ui
REM    run.bat --payload cot --udp 127.0.0.1:6969
REM    run.bat --ui --payload cot --multicast 239.2.3.1:6969
REM    run.bat --rtl-path "C:\rtl-sdr\bin" --tcp-port 30003 --verbose
REM ============================================================

set JAR=target\adsb-forwarder-1.0.0.jar

if not exist "%JAR%" (
    echo [ERROR] JAR not found: %JAR%
    echo         Run build.bat first.
    exit /b 1
)

java -jar "%JAR%" %*
