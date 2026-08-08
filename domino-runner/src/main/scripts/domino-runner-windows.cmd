@echo off
rem
rem Launches DominoRunner on Windows with a JVM configured for the local Notes/Domino install.
rem
rem Bitness must match the client: Notes 12 and earlier ship a 32-bit client, so they need a
rem 32-bit JVM. Notes 14 and later, and all Domino server installs, are 64-bit. A mismatch
rem fails when the native library is loaded, not at startup.
rem
rem Usage:
rem   domino-runner-windows.cmd [runner options] <main-class> [program arguments...]
rem
rem Environment overrides:
rem   DOMINO_RUNNER_JAVA_HOME  JVM to use (Java 21+, bitness matching the client)
rem   DOMINO_PROGRAM_DIR       Notes/Domino program directory
rem   NOTES_DATA               Notes data directory
rem   NOTES_INI                notes.ini location
rem   DOMINO_ID_PASSWORD       Notes ID password, if the ID is password protected
rem   DOMINO_RUNNER_JAVA_OPTS  Extra JVM options
rem   DOMINO_RUNNER_DEBUG      Set to 1 to print the resolved configuration and exit

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"

rem --- Locate the Notes/Domino installation ----------------------------------------

if defined DOMINO_PROGRAM_DIR (
  set "NOTES_EXEC_DIR=%DOMINO_PROGRAM_DIR%"
) else (
  set "NOTES_EXEC_DIR=C:\Program Files\HCL\Notes"
  if not exist "!NOTES_EXEC_DIR!\nnotes.dll" set "NOTES_EXEC_DIR=C:\Program Files\HCL\Domino"
  if not exist "!NOTES_EXEC_DIR!\nnotes.dll" set "NOTES_EXEC_DIR=C:\Program Files (x86)\HCL\Notes"
)

if not exist "%NOTES_EXEC_DIR%\nnotes.dll" (
  echo domino-runner: nnotes.dll not found in "%NOTES_EXEC_DIR%". Set DOMINO_PROGRAM_DIR. 1>&2
  exit /b 3
)

if not defined NOTES_DATA set "NOTES_DATA=%NOTES_EXEC_DIR%\Data"
if not defined NOTES_INI set "NOTES_INI=%NOTES_DATA%\notes.ini"

rem Notes.jar must match the installed runtime, so it comes from the installation.
set "NOTES_JAR="
if exist "%NOTES_EXEC_DIR%\jvm\lib\ext\Notes.jar" set "NOTES_JAR=%NOTES_EXEC_DIR%\jvm\lib\ext\Notes.jar"
if not defined NOTES_JAR if exist "%NOTES_EXEC_DIR%\ndext\Notes.jar" set "NOTES_JAR=%NOTES_EXEC_DIR%\ndext\Notes.jar"
if not defined NOTES_JAR if exist "%NOTES_EXEC_DIR%\Notes.jar" set "NOTES_JAR=%NOTES_EXEC_DIR%\Notes.jar"

if not defined NOTES_JAR (
  echo domino-runner: Notes.jar not found under "%NOTES_EXEC_DIR%". 1>&2
  exit /b 3
)

rem --- Locate a suitable JVM --------------------------------------------------------

set "JAVA_BIN="
if defined DOMINO_RUNNER_JAVA_HOME (
  set "JAVA_BIN=%DOMINO_RUNNER_JAVA_HOME%\bin\java.exe"
) else if exist "%SCRIPT_DIR%..\jvm\bin\java.exe" (
  set "JAVA_BIN=%SCRIPT_DIR%..\jvm\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_BIN (
  echo domino-runner: no JVM found. Set DOMINO_RUNNER_JAVA_HOME to a Java 21+ JVM. 1>&2
  exit /b 3
)
if not exist "%JAVA_BIN%" (
  echo domino-runner: java.exe not found at "%JAVA_BIN%". 1>&2
  exit /b 3
)

rem --- Build the classpath ----------------------------------------------------------

if exist "%SCRIPT_DIR%..\lib" (
  set "RUNNER_CP=%SCRIPT_DIR%..\lib\*"
) else if exist "%SCRIPT_DIR%..\..\target\dist\lib" (
  set "RUNNER_CP=%SCRIPT_DIR%..\..\target\dist\lib\*"
) else (
  echo domino-runner: runner libraries not found. Build the project first with 'mvn package'. 1>&2
  exit /b 3
)

set "CLASSPATH_ARG=%RUNNER_CP%;%NOTES_JAR%"
if defined DOMINO_RUNNER_EXTRA_CP set "CLASSPATH_ARG=%CLASSPATH_ARG%;%DOMINO_RUNNER_EXTRA_CP%"

rem --- Native environment -----------------------------------------------------------

set "Notes_ExecDirectory=%NOTES_EXEC_DIR%"
set "Directory=%NOTES_DATA%"
set "NotesINI=%NOTES_INI%"
set "PATH=%NOTES_EXEC_DIR%;%PATH%"

rem --- JVM options ------------------------------------------------------------------

set JAVA_OPTS=-Djava.library.path="%NOTES_EXEC_DIR%" -Djna.library.path="%NOTES_EXEC_DIR%"
set JAVA_OPTS=%JAVA_OPTS% --add-exports=java.base/sun.nio.ch=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.lang=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.lang.reflect=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.io=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens=java.base/java.nio=ALL-UNNAMED
if defined DOMINO_RUNNER_JAVA_OPTS set JAVA_OPTS=%JAVA_OPTS% %DOMINO_RUNNER_JAVA_OPTS%

if "%DOMINO_RUNNER_DEBUG%"=="1" (
  echo Notes_ExecDirectory: %NOTES_EXEC_DIR%
  echo Notes data       : %NOTES_DATA%
  echo notes.ini        : %NOTES_INI%
  echo Notes.jar        : %NOTES_JAR%
  echo JVM              : %JAVA_BIN%
  echo Classpath        : %CLASSPATH_ARG%
  echo JVM options      : %JAVA_OPTS%
  exit /b 0
)

"%JAVA_BIN%" %JAVA_OPTS% -cp "%CLASSPATH_ARG%" com.factory.domino.runner.DominoRunner %*
exit /b %ERRORLEVEL%
