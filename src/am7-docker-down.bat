@echo off
REM ============================================================================
REM  am7-docker-down.bat - tear down the am7test Docker stack, optionally all its data.
REM
REM  Counterpart to am7-docker-up.bat. Runbook: src\aiDocs\dockerDevSetup.md
REM
REM  Usage (flags may be combined, in any order):
REM    am7-docker-down.bat                remove every am7test container + the network.
REM                                       KEEPS all data: the DB volume, the Langfuse and
REM                                       Ollama volumes, and docker-data\. The next
REM                                       am7-docker-up.bat comes back with the same data.
REM    am7-docker-down.bat --reset        ALSO delete all stack data, for a genuine first
REM                                       run on the next am7-docker-up.bat:
REM                                         - am7test_am7-test-pg (the AM7 DB + litellmdb)
REM                                         - the Langfuse volumes (trace history)
REM                                         - the containers' anonymous volumes
REM                                         - everything under docker-data\ (store,
REM                                           keystores, vault, certs, langfuse-pg, seed)
REM                                       Asks you to type RESET first.
REM    am7-docker-down.bat --with-models  with --reset: ALSO delete am7test_ollama-models
REM                                       (several GB; the next --ollama run re-pulls).
REM                                       Kept by default.
REM    am7-docker-down.bat --yes          skip the RESET confirmation prompt.
REM    am7-docker-down.bat --dry-run      print every action, perform none.
REM
REM  Why a separate script: the DB volume is `external` in docker-compose.test.yml
REM  (2026-10-23), so `docker compose down -v` and deleting the am7test group in
REM  Docker Desktop both leave it behind. Wiping docker-data\ without it produces
REM  the orphan state (fresh setup token, but /rest/setup/state says initialized
REM  and the UI goes straight to sign-in). The store and the DB are a matched pair,
REM  so --reset always removes both together.
REM
REM  Not touched: containers/volumes of other compose projects (e.g. `src_*` from
REM  docker-compose.yml, your standalone postgres/pgadmin), and images.
REM  Honours AM7_DATA_DIR the same way the compose file does.
REM ============================================================================

setlocal

set "PROJECT=am7test"
set "COMPOSE_FILE=docker-compose.test.yml"
set "SRC_DIR=%~dp0"
if defined AM7_DATA_DIR (set "DATA_DIR=%AM7_DATA_DIR%") else (set "DATA_DIR=%SRC_DIR%docker-data")
REM  Must match the `name:` of the external am7-test-pg volume in docker-compose.test.yml.
set "PG_VOLUME=am7test_am7-test-pg"
set "LANGFUSE_VOLUMES=%PROJECT%_langfuse-clickhouse-data %PROJECT%_langfuse-clickhouse-logs %PROJECT%_langfuse-minio-data"
set "MODELS_VOLUME=%PROJECT%_ollama-models"
REM  Every compose profile - compose only acts on services in an ACTIVE profile, so a
REM  missing entry would leave that profile's containers behind (same list as up.bat).
set "ALL_PROFILES=--profile llmproxy --profile ollama"

set "RESET=0"
set "WITH_MODELS=0"
set "ASSUME_YES=0"
set "DRY=0"

:parse
if "%~1"=="" goto :parsed
if /i "%~1"=="--reset"       set "RESET=1"       & shift & goto :parse
if /i "%~1"=="--with-models" set "WITH_MODELS=1" & shift & goto :parse
if /i "%~1"=="--yes"         set "ASSUME_YES=1"  & shift & goto :parse
if /i "%~1"=="--dry-run"     set "DRY=1"         & shift & goto :parse
if /i "%~1"=="-h"     goto :usage
if /i "%~1"=="--help" goto :usage
echo ERROR: unknown option %~1
goto :usage_err
:parsed

if "%WITH_MODELS%"=="1" if "%RESET%"=="0" (
    echo ERROR: --with-models only makes sense together with --reset.
    exit /b 2
)

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not reachable. Start Docker Desktop and retry.
    exit /b 1
)

pushd "%SRC_DIR%"

set "DEL_VOLUMES=%PG_VOLUME% %LANGFUSE_VOLUMES%"
if "%WITH_MODELS%"=="1" set "DEL_VOLUMES=%DEL_VOLUMES% %MODELS_VOLUME%"

echo ============================================================
if "%RESET%"=="1" (
    echo  FULL RESET of project %PROJECT%. This permanently deletes:
    echo    - every %PROJECT% container and the project network
    echo    - volumes: %DEL_VOLUMES%
    echo    - the containers' anonymous volumes
    echo    - everything under %DATA_DIR%
    if exist "%DATA_DIR%\pg" echo      INCLUDING %DATA_DIR%\pg - the pre-2026-10-10 DB backup
    if "%WITH_MODELS%"=="0" echo  Kept: %MODELS_VOLUME% ^(pass --with-models to delete it too^)
) else (
    echo  Removing every %PROJECT% container and the project network.
    echo  All data is kept: volumes and %DATA_DIR%.
)
if "%DRY%"=="1" echo  DRY RUN - nothing will be changed.
echo ============================================================

if "%RESET%"=="1" if "%DRY%"=="0" if "%ASSUME_YES%"=="0" (
    set "ANSWER="
    set /p "ANSWER=Type RESET to continue: "
    call :checkanswer || goto :aborted
)

echo.
echo [1] Stopping and removing containers ...
if "%RESET%"=="1" (
    REM  rm -v also removes each container's ANONYMOUS volumes (pg18 mount stubs,
    REM  redis data), which `down` without -v would leak.
    call :run docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% rm -s -f -v
    if errorlevel 1 goto :failed
)
REM  No -v here on purpose: it would also delete ollama-models. Named volumes are
REM  removed explicitly in step 2, so exactly the ones listed above go.
call :run docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% down --remove-orphans
if errorlevel 1 goto :failed

if "%RESET%"=="0" goto :done

echo.
echo [2] Removing volumes ...
set "VOL_FAILED=0"
for %%V in (%DEL_VOLUMES%) do call :rmvolume %%V
if "%VOL_FAILED%"=="1" goto :failed

echo.
echo [3] Deleting %DATA_DIR% ...
if not exist "%DATA_DIR%" (
    echo   ^(does not exist - nothing to delete^)
    goto :done
)
call :run rmdir /s /q "%DATA_DIR%"
if "%DRY%"=="0" if exist "%DATA_DIR%" (
    echo ERROR: %DATA_DIR% could not be fully deleted - a file may be open. Close it and re-run.
    goto :failed
)
call :run mkdir "%DATA_DIR%"

:done
echo.
echo ============================================================
if "%DRY%"=="1" (
    echo  Dry run complete. Nothing was changed.
) else if "%RESET%"=="1" (
    echo  Reset complete. Next: am7-docker-up.bat ^(with your usual flags^)
    echo  creates the DB volume, mints a setup token and prints the setup URL.
    echo  Open that URL in a NEW browser tab: an old tab may have cached
    echo  am7.setupComplete in sessionStorage and will redirect to sign-in.
) else (
    echo  Down. Data kept. Bring it back:  am7-docker-up.bat --no-build
)
echo ============================================================
popd
endlocal
exit /b 0

REM ---------------------------------------------------------------------------
:checkanswer
if "%ANSWER%"=="RESET" exit /b 0
exit /b 1

:aborted
echo Aborted. Nothing was changed.
popd
endlocal
exit /b 1

:failed
echo.
echo ERROR: a step failed - see the output above. The reset may be partial;
echo        re-running am7-docker-down.bat --reset is safe.
popd
endlocal
exit /b 1

REM  Echo a command, then run it unless --dry-run.
:run
echo   + %*
if "%DRY%"=="1" exit /b 0
%*
exit /b %errorlevel%

:rmvolume
docker volume inspect %1 >nul 2>&1
if errorlevel 1 (
    echo   %1 - not present
    exit /b 0
)
call :run docker volume rm %1
if errorlevel 1 set "VOL_FAILED=1"
exit /b 0

:usage
echo.
echo Usage: am7-docker-down.bat [--reset [--with-models] [--yes]] [--dry-run]
echo   ^(no flags^)      remove the am7test containers + network; keep all data
echo   --reset          also delete the DB + Langfuse volumes and docker-data\
echo   --with-models    with --reset, also delete the Ollama model volume
echo   --yes            do not prompt for RESET
echo   --dry-run        print the actions without performing them
exit /b 0
:usage_err
echo Usage: am7-docker-down.bat [--reset [--with-models] [--yes]] [--dry-run]
exit /b 2
