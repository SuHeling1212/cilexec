@echo off
setlocal
py -3 "%~dp0release_all.py" %*
exit /b %errorlevel%
