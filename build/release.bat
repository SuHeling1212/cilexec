@echo off
setlocal
py -3 "%~dp0release.py" %*
exit /b %errorlevel%
