@echo off
:: Azora Language Installer for Windows
:: This is a convenience wrapper that invokes install.ps1
powershell -ExecutionPolicy Bypass -File "%~dp0install.ps1"
