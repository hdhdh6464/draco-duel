@echo off
rem Build the edition for sharing: the solo duel against Voldemort plus the tutorial (no two-player modes),
rem bundled with its own small Java runtime so it runs on a PC without Java. Output: dist-solo\DracoDuel-Solo.zip
cd /d "%~dp0"
set JDK=%~dp0jdk
if exist out-solo rmdir /s /q out-solo
if exist dist-solo rmdir /s /q dist-solo
"%JDK%\bin\javac" -encoding UTF-8 --release 8 -Xlint:-options -d out-solo\classes src\*.java || exit /b 1
mkdir out-solo\app
"%JDK%\bin\jar" cfe out-solo\app\DracoDuel.jar SoloMain -C out-solo\classes . || exit /b 1
"%JDK%\bin\jpackage" --type app-image --name DracoDuel --app-version 1.0 --vendor "Draco Duel" ^
    --input out-solo\app --main-jar DracoDuel.jar --main-class SoloMain --icon DracoDuel.ico ^
    --add-modules java.base,java.desktop,java.prefs ^
    --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=zip-6" ^
    --dest out-solo\image || exit /b 1
mkdir dist-solo
xcopy /e /i /q out-solo\image\DracoDuel dist-solo\DracoDuel >nul
if exist solo-readme.txt copy /y solo-readme.txt "dist-solo\DracoDuel\README.txt" >nul
tar -a -c -f dist-solo\DracoDuel-Solo.zip -C dist-solo DracoDuel || exit /b 1
if not exist dist-solo\DracoDuel-Solo.zip exit /b 1
rmdir /s /q out-solo
echo Built dist-solo\DracoDuel-Solo.zip
