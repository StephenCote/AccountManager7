@echo off
REM ============================================================================
REM  am7-docker-up.bat - build and start the self-contained AM7 Docker stack
REM  (Service7 + Ux752 + a dedicated pgvector), then report first-run setup.
REM
REM  Runbook this automates: src\aiDocs\dockerDevSetup.md
REM
REM  Usage (flags may be combined, in any order):
REM    am7-docker-up.bat                 build (online Maven) and start
REM    am7-docker-up.bat --no-build      start the existing am7:latest image
REM    am7-docker-up.bat --llmproxy      ALSO start the LiteLLM + Langfuse sidecars
REM                                      (compose profile `llmproxy`: 7 extra
REM                                      containers). Off by default - they are
REM                                      real overhead and most work does not
REM                                      need them.
REM    am7-docker-up.bat --app-only      the reverse of --llmproxy-only: start ONLY
REM                                      the app + its database (am7 + am7-pg), and
REM                                      STOP any LiteLLM/Langfuse sidecars that are
REM                                      running, so you get the overhead back.
REM    am7-docker-up.bat --llmproxy-only start ONLY the proxy/observability stack
REM                                      (litellm + langfuse + am7-pg, which hosts
REM                                      litellmdb). Does NOT build, does not stage
REM                                      the Olio seed, and does NOT start the app.
REM                                      Use when you want the proxy without the
REM                                      Service7/Ux752 overhead.
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

REM  Postgres tuning for the am7-pg container. Same pattern as CATALINA_OPTS:
REM  docker-compose.test.yml declares each as a ${VAR:-default} build ARG, so
REM  setting one here OVERRIDES the compose default and leaving it unset takes
REM  the compose default.
REM
REM  MOST are BAKED AT BUILD TIME (docker/postgres/Dockerfile), so changing one
REM  only takes effect on a build - i.e. NOT with --no-build. To retune a
REM  RUNNING stack without a rebuild, drop a .conf into docker\postgres\conf.d
REM  and run `select pg_reload_conf();` - see docker\postgres\conf.d\README.md.
REM
REM  EXCEPTION - PG_SHM_SIZE is NOT a build ARG. It maps to the service's
REM  `shm_size:`, a container-CREATE setting: compose applies it by recreating
REM  the container, with no build, and it works with --no-build. Do not rebuild
REM  chasing an shm change.
REM
REM  BUDGET THESE TOGETHER WITH CATALINA_OPTS ABOVE: both draw on the same
REM  ~31 GiB Docker VM, which also hosts the separate dev `postgres` container.
REM  shared_buffers is pinned, non-reclaimable RAM. work_mem is charged PER
REM  memory node PER backend, then multiplied by hash_mem_multiplier (2) and by
REM  the parallel worker count (leader + 4) - so it is the dangerous one.
REM  PG_SHM_SIZE must stay above work_mem x 2 x 5, or large parallel hash joins
REM  die with "could not resize shared memory segment ... No space left".
REM
REM  Blank on purpose - the compose defaults (2GB / 128MB / 2gb) ARE the tuned
REM  values. Uncomment to dial down for a smaller machine.
REM set "PG_SHARED_BUFFERS=512MB"
REM set "PG_WORK_MEM=16MB"
REM set "PG_EFFECTIVE_CACHE_SIZE=2GB"
REM set "PG_SHM_SIZE=512m"
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
set "PROFILE_ARGS="
set "ENVFILE_ARGS="
set "LLMPROXY_ONLY="
set "APP_ONLY="

REM  A shift loop, not a row of `if "%~1"==...` tests: flags are combinable
REM  (e.g. --no-build --llmproxy) and the old single-arg form silently ignored
REM  everything after the first. No `enabledelayedexpansion` here on purpose
REM  (see the note at the top) - nothing below READS a variable inside the same
REM  parenthesised block it is set in, so plain expansion is correct.
:parseargs
if "%~1"=="" goto :parsedargs
if /i "%~1"=="--no-build" set "BUILD_FLAG="
if /i "%~1"=="--prebuilt" (
    set "BUILD_FLAG="
    set "BUILD_ARG=1"
)
if /i "%~1"=="--llmproxy" set "PROFILE_ARGS=--profile llmproxy"
if /i "%~1"=="--llmproxy-only" (
    set "PROFILE_ARGS=--profile llmproxy"
    set "LLMPROXY_ONLY=1"
    set "BUILD_FLAG="
)
if /i "%~1"=="--app-only" set "APP_ONLY=1"
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="/?" goto :usage
shift
goto :parseargs
:parsedargs

if defined APP_ONLY if defined LLMPROXY_ONLY (
    echo ERROR: --app-only and --llmproxy-only are opposites; pick one.
    exit /b 1
)

REM  The profile's secrets/overrides live in a git-ignored env file. --env-file
REM  feeds docker-compose ${VAR} INTERPOLATION only; anything litellm must see in
REM  its own process is additionally declared in the service `environment:` block.
REM  Absent file = compose defaults, which are the throwaway test values.
if defined PROFILE_ARGS (
    if exist "%SRC_DIR%volatile\llmproxy.env" set "ENVFILE_ARGS=--env-file .\volatile\llmproxy.env"
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
if defined PROFILE_ARGS echo    llmproxy     : ON  - litellm http://127.0.0.1:4000/ui/  langfuse http://127.0.0.1:3001
if not defined PROFILE_ARGS if not defined APP_ONLY echo    llmproxy     : off ^(pass --llmproxy to start the LiteLLM/Langfuse sidecars^)
if defined APP_ONLY echo    llmproxy     : off - and any running sidecars will be STOPPED ^(--app-only^)
echo ============================================================
echo.

docker version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not responding. Is Docker Desktop running?
    popd
    exit /b 1
)

REM  --app-only: the reverse of --llmproxy-only. `docker compose up` would simply
REM  leave already-running profile containers alone, so "only the app" has to stop
REM  them explicitly - that is the whole point of the flag (reclaiming the RAM the
REM  7 sidecars hold). `stop`, not `down`: it leaves the containers and their data
REM  in place so a later --llmproxy restarts them in seconds.
if defined APP_ONLY (
    echo [0/4] --app-only: stopping any running LiteLLM/Langfuse sidecars ...
    docker compose -p %PROJECT% -f %COMPOSE_FILE% --profile llmproxy stop litellm langfuse-web langfuse-worker langfuse-clickhouse langfuse-minio langfuse-redis langfuse-db
    echo.
)

REM  --llmproxy-only: bring up just the proxy/observability side. Naming the three
REM  services explicitly (rather than relying on the profile alone) keeps the `am7`
REM  app out of it; compose still pulls in each one's depends_on, so am7-pg
REM  (which hosts litellmdb), langfuse-db, clickhouse, minio and redis come along.
REM  langfuse-worker is listed because nothing depends_on it, yet without it the
REM  ingestion API accepts events and the UI stays permanently empty.
if defined LLMPROXY_ONLY (
    echo [1/2] Starting the LiteLLM/Langfuse stack only ^(no app, no build, no seed^) ...
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %ENVFILE_ARGS% %PROFILE_ARGS% up -d litellm langfuse-web langfuse-worker
    if errorlevel 1 (
        echo ERROR: `docker compose up` failed.
        popd
        exit /b 1
    )
    echo.
    echo [2/2] Status:
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %ENVFILE_ARGS% %PROFILE_ARGS% ps
    echo.
    echo ============================================================
    echo  LiteLLM admin UI : http://127.0.0.1:4000/ui/   ^(admin / your LITELLM_MASTER_KEY^)
    echo  Langfuse UI      : http://127.0.0.1:3001       ^(LANGFUSE_INIT_USER_EMAIL / _PASSWORD^)
    echo  Health           : http://127.0.0.1:4000/health/liveliness
    echo.
    echo  Point an AM7 system.connection at http://litellm:4000 with dialect=OPENAI_COMPAT
    echo  ^(http://127.0.0.1:4000 from host-side JUnit^). Runbook: aiDocs\dockerDevSetup.md section 12.
    echo  Stop: docker compose -p %PROJECT% -f %COMPOSE_FILE% %PROFILE_ARGS% down
    echo ============================================================
    popd
    endlocal
    exit /b 0
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
REM  --app-only names the two services explicitly; the default (no flag) brings up
REM  whatever the file defines outside a profile, which today is exactly these two
REM  plus nothing else. Naming them keeps --app-only honest if a non-profile service
REM  is ever added.
if defined APP_ONLY (
    docker compose -p %PROJECT% -f %COMPOSE_FILE% up %BUILD_FLAG% -d am7 am7-pg
) else (
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %ENVFILE_ARGS% %PROFILE_ARGS% up %BUILD_FLAG% -d
)
if errorlevel 1 (
    echo ERROR: `docker compose up` failed.
    popd
    exit /b 1
)

echo.
echo [4/4] Status:
docker compose -p %PROJECT% -f %COMPOSE_FILE% %ENVFILE_ARGS% %PROFILE_ARGS% ps

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
REM  MUST exit here: without it execution falls straight through into :usage and
REM  every successful run prints the help block.
exit /b 0

:usage
echo.
echo  am7-docker-up.bat [--no-build ^| --prebuilt] [--llmproxy ^| --llmproxy-only ^| --app-only]
echo.
echo    --no-build        start the existing am7:latest image
echo    --prebuilt        build with PREBUILT=1 (host-built WAR)
echo    --llmproxy        also start the LiteLLM + Langfuse sidecars (7 containers)
echo    --llmproxy-only   start ONLY those sidecars - no app, no build, no seed
echo    --app-only        start ONLY the app + its DB, and STOP any running sidecars
echo.
echo  Flags may be combined, e.g.:  am7-docker-up.bat --no-build --llmproxy
echo  Runbook: src\aiDocs\dockerDevSetup.md section 12
echo.
popd 2>nul
endlocal
exit /b 0
