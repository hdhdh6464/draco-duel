@echo off
rem Compile the game together with the tests and run every test suite. Uses the JDK in .\jdk.
cd /d "%~dp0"
set JDK=%~dp0jdk
if exist out-test rmdir /s /q out-test
"%JDK%\bin\javac" -encoding UTF-8 --release 8 -Xlint:-options -d out-test src\*.java test\*.java || exit /b 1
for %%t in (MechTest AimTest CounterTest StageTest SpawnTest EnemyTest ChainTest SimTest ReplayTest PlanFidelityTest DuelTest) do (
    echo == %%t
    "%JDK%\bin\java" -Djava.awt.headless=true -Dlang=zh -cp out-test %%t
)
echo == MeleeTest
"%JDK%\bin\java" -Djava.awt.headless=true -Dlang=zh -cp out-test MeleeTest out-test\melee.png
echo == PvpFlowTest
"%JDK%\bin\java" -Dlang=zh -cp out-test PvpFlowTest
echo == BossModeTest
"%JDK%\bin\java" -Dlang=zh -cp out-test BossModeTest
echo == TutorialTest
"%JDK%\bin\java" -Dlang=zh -cp out-test TutorialTest
rmdir /s /q out-test
