@echo off
REM ============================================================================
REM  am7-docker-up.bat - build and start the self-contained AM7 Docker stack
REM  (Service7 + Ux752 + a dedicated pgvector), then report first-run setup.
REM
REM  Runbook this automates: src\aiDocs\dockerDevSetup.md
REM
REM  Usage:
REM    am7-docker-up.bat                 build (online Maven) and start
REM    am7-docker-up.bat --no-build      start the existing am7:latest image
REM    am7-docker-up.bat --prebuilt      build with PREBUILT=1 (host-built WAR;
REM                                      use when a TLS proxy blocks Maven Central.
REM                                      Run these on the host FIRST:
REM                                        mvn -o -pl AccountManagerObjects7,AccountManagerISO42001 install -DskipTests
REM                                        mvn -o -pl AccountManagerService7 package -DskipTests)
REM
REM  NOTE: deliberately no `setlocal enabledelayedexpansion` - the setup URL
REM  contains "#!/setup" and delayed expansion would eat the "!".
REM ============================================================================

setlocal

REM ---- Edit these two if your host IP or ports change -------------------------
REM  LAN_ORIGIN must be the EXACT origin your browser shows (scheme+host+port).
REM  Tomcat's CorsFilter 403s any POST whose Origin is not listed - which looks
REM  like "login returns a null response" with nothing in the server log,
REM  because the filter rejects the request before the app ever runs.
set "LAN_IP=192.168.1.39"
set "APP_PORT=9443"

REM  Tomcat JVM options. CATALINA_OPTS (not JAVA_OPTS) is the right place for
REM  memory/GC flags: catalina.sh applies it only to start/run, while JAVA_OPTS
REM  is also passed to the `stop` command. docker/setenv.sh APPENDS to JAVA_OPTS
REM  ("$JAVA_OPTS -Djava.security.auth.login.config=..."), so neither var is
REM  clobbered - but keep the JAAS setting out of here and let setenv.sh own it.
REM
REM  With no -Xmx, the JVM uses ergonomics: max heap = 1/4 of the memory the
REM  CONTAINER can see, which on this host measured ~7.8 GiB. Set it explicitly
REM  if you want a predictable ceiling. Blank = leave the default alone.
REM
REM  docker-compose.test.yml forwards this via `CATALINA_OPTS: ${CATALINA_OPTS:--Xms1g -Xmx8g}`,
REM  so setting it here OVERRIDES that compose default. Keep the two in step, or
REM  clear it here (set "CATALINA_OPTS=") to just take the compose default.
set "CATALINA_OPTS=-Xms1g -Xmx8g"

REM  Args passed to assemble-seed.bat in step [2/4]. --with-location also stages the
REM  large location grids (needed for the geo/map corpus); clear this to base corpus only.
set "SEED_ARGS=--with-location"
REM ----------------------------------------------------------------------------

set "PROJECT=am7test"
set "COMPOSE_FILE=docker-compose.test.yml"
set "SRC_DIR=%~dp0"
set "STORE_DIR=%SRC_DIR%docker-data\am7\store"

REM CORS_ALLOWED_ORIGINS REPLACES the compose default, so list every origin -
REM appending is not a thing. localhost + 127.0.0.1 + the LAN IP, http and https.
set "CORS_ALLOWED_ORIGINS=https://localhost:%APP_PORT%,http://localhost:%APP_PORT%,https://127.0.0.1:%APP_PORT%,http://127.0.0.1:%APP_PORT%,http://localhost:8899,https://localhost:8899,https://%LAN_IP%:%APP_PORT%,http://%LAN_IP%:%APP_PORT%"

set "BUILD_FLAG=--build"
set "BUILD_ARG="
if /i "%~1"=="--no-build" set "BUILD_FLAG="
if /i "%~1"=="--prebuilt" (
    set "BUILD_FLAG="
    set "BUILD_ARG=1"
)

pushd "%SRC_DIR%" || (
    echo ERROR: cannot cd to "%SRC_DIR%"
    exit /b 1
)

if not exist "%COMPOSE_FILE%" (
    echo ERROR: %COMPOSE_FILE% not found in "%CD%".
    echo        This script must sit in the src\ directory next to the compose files.
    popd
    exit /b 1
)

echo ============================================================
echo  AM7 Docker stack
echo    project      : %PROJECT%
echo    compose file : %COMPOSE_FILE%
echo    app URL      : https://localhost:%APP_PORT%
echo    LAN URL      : https://%LAN_IP%:%APP_PORT%
echo    postgres     : localhost:15433 (inspect only)
echo    CATALINA_OPTS: %CATALINA_OPTS%
echo ============================================================
echo.

docker version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not responding. Is Docker Desktop running?
    popd
    exit /b 1
)

if defined BUILD_ARG (
    echo [1/4] Building image with PREBUILT=1 ...
    docker compose -p %PROJECT% -f %COMPOSE_FILE% build --build-arg PREBUILT=1 am7
    if errorlevel 1 (
        echo ERROR: image build failed.
        popd
        exit /b 1
    )
) else (
    echo [1/4] Skipping explicit build step.
)

echo.
echo [2/4] Staging Olio seed corpus ...
REM  assemble-seed.bat mirrors your SEED_STAGING corpus into docker-data\am7\datagen
REM  (the /data/am7 -> /data/am7/datagen bind mount) so Olio character generation has
REM  its reference data. Without this, a fresh stack ships datagen EMPTY and character
REM  creation fails (KI-70) - see dockerDevSetup.md section 2e. It is idempotent
REM  (robocopy skips unchanged files, ~instant after the first run) and self-skips with
REM  a warning when SEED_STAGING is unset, so it is safe to run on every `up`. SEED_ARGS
REM  (set above) controls scope - defaults to --with-location so the grids are staged too.
REM  MUST use `call`: without it, control transfers to assemble-seed.bat and never
REM  returns here, so the containers would never start.
call "%SRC_DIR%assemble-seed.bat" %SEED_ARGS%
if errorlevel 1 (
    echo.
    echo   WARNING: seed staging did not complete ^(SEED_STAGING unset, or a robocopy error^).
    echo            The stack will still start, but Olio character generation may fail until
    echo            the corpus is staged. Set SEED_STAGING in src\.env and re-run, or run
    echo            assemble-seed.bat by hand. See dockerDevSetup.md section 2e.
)

echo.
echo [3/4] Starting containers ...
docker compose -p %PROJECT% -f %COMPOSE_FILE% up %BUILD_FLAG% -d
if errorlevel 1 (
    echo ERROR: `docker compose up` failed.
    popd
    exit /b 1
)

echo.
echo [4/4] Status:
docker compose -p %PROJECT% -f %COMPOSE_FILE% ps

REM Give the entrypoint a moment to render web.xml and mint the setup token.
ping -n 4 127.0.0.1 >nul 2>&1

echo.
echo ============================================================
if exist "%STORE_DIR%\.setup.done" goto :configured
if exist "%STORE_DIR%\.setup.token" goto :firstrun

echo  No setup token and no completion sentinel yet.
echo  The app may still be booting. Re-check in a few seconds:
echo    type "%STORE_DIR%\.setup.token"
echo  Or watch the log for the FIRST-RUN banner:
echo    docker compose -p %PROJECT% -f %COMPOSE_FILE% logs am7 ^| findstr FIRST-RUN
goto :done

:firstrun
set "TOKEN="
set /p TOKEN=<"%STORE_DIR%\.setup.token"
echo  ***** FIRST-RUN SETUP REQUIRED *****
echo.
echo  Open this URL and complete the form:
echo.
echo    https://localhost:%APP_PORT%/#!/setup?token=%TOKEN%
echo.
echo  From another machine on the LAN, use:
echo.
echo    https://%LAN_IP%:%APP_PORT%/#!/setup?token=%TOKEN%
echo.
echo  Accept the self-signed certificate warning.
echo  Admin password: use `password` on this stack - every Playwright helper
echo  defaults to it and needs an admin session to provision the test users.
echo  Hashes cannot be recovered; losing it means a full reset.
goto :done

:configured
echo  Setup already completed on this store (.setup.done present).
echo  Sign in at https://localhost:%APP_PORT%  (admin / your password, org /Development)
goto :done

:done
echo ============================================================
echo.
echo  Follow the log:  docker compose -p %PROJECT% -f %COMPOSE_FILE% logs -f am7
echo  Stop (keep data): docker compose -p %PROJECT% -f %COMPOSE_FILE% down
echo.

popd
endlocal
