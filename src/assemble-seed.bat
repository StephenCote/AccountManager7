@echo off
REM ============================================================================
REM  assemble-seed.bat - stage the Olio seed corpus for the Docker test stack.
REM  NATIVE Windows cmd script (uses robocopy). No bash, no Git Bash, no WSL.
REM
REM  Mirrors your populated corpus staging dir into docker-data\am7\datagen
REM  (the docker-compose.test.yml bind mount) BEFORE you generate characters.
REM  Runbook: src\aiDocs\dockerDevSetup.md section 2e.
REM
REM  Where the staging dir comes from (in order):
REM    1. an inline value:   set "SEED_STAGING=c:\projects\data"  then run this
REM    2. src\.env:          SEED_STAGING=c:/projects/data        (uncommented)
REM  Forward slashes are accepted and converted to backslashes.
REM
REM  Never copied: top-level am7\ (a live DB) and vault\ (secrets) are excluded,
REM  so SEED_STAGING may safely point at a dir that also holds those. README
REM  files are skipped. location\ is skipped unless --with-location.
REM
REM  Usage (from cmd, any directory):
REM    assemble-seed.bat                  base corpus
REM    assemble-seed.bat --with-location  also copy the large location grids
REM    assemble-seed.bat --dry-run        list what would copy, write nothing
REM ============================================================================

setlocal

set "SRC_DIR=%~dp0"
set "ENV_FILE=%SRC_DIR%.env"

REM ---- Parse args ------------------------------------------------------------
set "WITH_LOCATION=0"
set "DRY_RUN=0"
:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--with-location" ( set "WITH_LOCATION=1" & shift & goto :parse )
if /i "%~1"=="--dry-run"       ( set "DRY_RUN=1"       & shift & goto :parse )
if /i "%~1"=="-h"     goto :help
if /i "%~1"=="--help" goto :help
if /i "%~1"=="/?"     goto :help
echo ERROR: unknown argument: %~1
exit /b 2
:parsed

REM ---- Resolve SEED_STAGING: inline value wins, else read from src\.env ------
set "SEED_STAGING_SRC="
if defined SEED_STAGING (
  set "SEED_STAGING_SRC=environment"
) else (
  if exist "%ENV_FILE%" (
    for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /r /c:"^ *SEED_STAGING=" "%ENV_FILE%"`) do set "SEED_STAGING=%%B"
    if defined SEED_STAGING set "SEED_STAGING_SRC=src\.env"
  )
)
if defined SEED_STAGING set SEED_STAGING=%SEED_STAGING:"=%
if defined SEED_STAGING set "SEED_STAGING=%SEED_STAGING:/=\%"
if defined SEED_STAGING if "%SEED_STAGING:~-1%"=="\" set "SEED_STAGING=%SEED_STAGING:~0,-1%"

if not defined SEED_STAGING (
  echo ERROR: SEED_STAGING is not set - point it at your populated corpus staging dir.
  echo   in src\.env:  SEED_STAGING=c:/projects/data     [then just run: assemble-seed.bat]
  echo   or inline:    set "SEED_STAGING=c:\projects\data"   then run: assemble-seed.bat
  if not exist "%ENV_FILE%" echo   note: %ENV_FILE% does not exist - copy .env.example to .env
  exit /b 1
)
if not exist "%SEED_STAGING%\" (
  echo ERROR: SEED_STAGING is not a directory: %SEED_STAGING%
  exit /b 1
)

REM ---- Resolve target: SEED_TARGET, else AM7_DATA_DIR\am7\datagen -----------
if not defined AM7_DATA_DIR (
  if exist "%ENV_FILE%" for /f "usebackq tokens=1,* delims==" %%A in (`findstr /b /r /c:"^ *AM7_DATA_DIR=" "%ENV_FILE%" 2^>nul`) do set "AM7_DATA_DIR=%%B"
)
if defined AM7_DATA_DIR set AM7_DATA_DIR=%AM7_DATA_DIR:"=%
if defined AM7_DATA_DIR set "AM7_DATA_DIR=%AM7_DATA_DIR:/=\%"
if not defined AM7_DATA_DIR set "AM7_DATA_DIR=%SRC_DIR%docker-data"
if "%AM7_DATA_DIR:~-1%"=="\" set "AM7_DATA_DIR=%AM7_DATA_DIR:~0,-1%"

if defined SEED_TARGET (
  set SEED_TARGET=%SEED_TARGET:"=%
  set "SEED_TARGET=%SEED_TARGET:/=\%"
) else (
  set "SEED_TARGET=%AM7_DATA_DIR%\am7\datagen"
)

echo assemble-seed: staging = %SEED_STAGING%   [source: %SEED_STAGING_SRC%]
echo assemble-seed: target  = %SEED_TARGET%
if "%DRY_RUN%"=="1" echo assemble-seed: DRY RUN - listing only, nothing will be written.
echo.

REM ---- Copy via robocopy -----------------------------------------------------
REM  /E recurse, /R:2 /W:2 fail fast (never the 1,000,000-retry default hang),
REM  /XF skip READMEs, /XD exclude top-level am7 vault [and location unless asked],
REM  /L when dry-run. Exit >=8 is a real robocopy failure.
pushd "%SRC_DIR%" || ( echo ERROR: cannot cd to "%SRC_DIR%" & exit /b 1 )

set "DRY_FLAG="
if "%DRY_RUN%"=="1" set "DRY_FLAG=/L"

if "%WITH_LOCATION%"=="1" (
  robocopy "%SEED_STAGING%" "%SEED_TARGET%" /E /R:2 /W:2 /NP /NDL /XF README.md readme.txt /XD "%SEED_STAGING%\am7" "%SEED_STAGING%\vault" %DRY_FLAG%
) else (
  robocopy "%SEED_STAGING%" "%SEED_TARGET%" /E /R:2 /W:2 /NP /NDL /XF README.md readme.txt /XD "%SEED_STAGING%\am7" "%SEED_STAGING%\vault" "%SEED_STAGING%\location" %DRY_FLAG%
)
set "RC=%errorlevel%"
popd

echo.
if %RC% GEQ 8 (
  echo ERROR: robocopy reported failures. exit code %RC% - see messages above. Nothing reliable was staged.
  exit /b 1
)
echo assemble-seed: done. robocopy exit code %RC% [0=nothing new, 1=copied, under 8 = ok].
if "%WITH_LOCATION%"=="1" ( echo assemble-seed: location grids INCLUDED. ) else ( echo assemble-seed: location grids excluded [use --with-location to include]. )
echo assemble-seed: top-level am7\ and vault\ were excluded [never copied].
exit /b 0

:help
echo assemble-seed.bat - stage the Olio seed corpus into docker-data\am7\datagen
echo.
echo   assemble-seed.bat                  base corpus
echo   assemble-seed.bat --with-location  also copy the large location grids
echo   assemble-seed.bat --dry-run        list what would copy, write nothing
echo.
echo Set SEED_STAGING in src\.env [SEED_STAGING=c:/projects/data] or inline
echo   [set "SEED_STAGING=c:\projects\data"] before running. Forward slashes ok.
echo Excludes top-level am7\ [live DB] and vault\ [secrets], and README files.
exit /b 0
