@echo off
setlocal
cd /d "%~dp0"
if exist app\build rmdir /s /q app\build
if exist build rmdir /s /q build
if exist .gradle rmdir /s /q .gradle
echo Android generated build output removed. Source tree is back to pre-compile state.
endlocal
