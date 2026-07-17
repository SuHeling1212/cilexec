@echo off
setlocal

set "sound=%SystemRoot%\Media\Windows Notify System Generic.wav"
if exist "%sound%" (
    powershell -c "(New-Object Media.SoundPlayer '%sound%').PlaySync()"
) else (
    echo 
)
