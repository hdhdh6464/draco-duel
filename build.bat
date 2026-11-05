@echo off
rem Rebuild DracoDuel.jar from src. Needs a JDK (javac); targets Java 8 so any Java 8+ runtime can play it.
cd /d "%~dp0"
set JDK=%~dp0jdk
if exist out rmdir /s /q out
"%JDK%\bin\javac" -encoding UTF-8 --release 8 -Xlint:-options -d out src\*.java || exit /b 1
"%JDK%\bin\jar" cfe DracoDuel.jar Main -C out .
rmdir /s /q out
echo Built DracoDuel.jar
