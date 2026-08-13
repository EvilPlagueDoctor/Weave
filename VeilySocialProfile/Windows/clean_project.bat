@echo off
setlocal
cd /d "%~dp0"
if exist build rmdir /s /q build
if exist network_bridge\target rmdir /s /q network_bridge\target
echo Cleaned Windows VeilySocial Profile build outputs.
endlocal
