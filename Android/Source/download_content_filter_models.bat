@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0download_content_filter_models.ps1"
if errorlevel 1 (
  echo.
  echo Model download failed.
  exit /b 1
)
