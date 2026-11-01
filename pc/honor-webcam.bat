@echo off
rem Honor webcam over USB: forwards the phone's webcam port to this PC, starts the webcam,
rem and opens the preview. In OBS use Media Source -> http://127.0.0.1:8084/video
setlocal
set PORT=8084
where adb >nul 2>nul && (set ADB=adb) || (set ADB=%~dp0adb.exe)
if not exist "%ADB%" if not "%ADB%"=="adb" (
  for /d %%d in ("%USERPROFILE%\Downloads\scrcpy-win64-*") do for /r "%%d" %%f in (adb.exe) do if exist "%%f" set ADB=%%f
)
"%ADB%" wait-for-device
"%ADB%" forward tcp:%PORT% tcp:%PORT%
rem start the webcam service (needs root on Android 7; otherwise tap START WEBCAM in the app)
"%ADB%" shell su -c "am startservice -n com.partha.wirelessmic/.CamService" >nul 2>nul
"%ADB%" shell am start -n com.partha.wirelessmic/.MainActivity >nul
echo Webcam on http://127.0.0.1:%PORT%/video
start "" "http://127.0.0.1:%PORT%/?controls"
