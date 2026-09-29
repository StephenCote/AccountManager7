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
REM    am7-docker-up.bat --ollama        ALSO start a containerized Ollama (compose
REM                                      profile `ollama`: the `ollama` server plus a
REM                                      one-shot `ollama-init` that pulls qwen3:8b,
REM                                      goekdenizguelmez/JOSIEFIED-Qwen3:8b and
REM                                      nomic-embed-text). IMPLIES --llmproxy, so the
REM                                      `*-ctr` LiteLLM aliases route to it and every
REM                                      test call is traceable in Langfuse. Also points
REM                                      the app's embeddings at the container
REM                                      (openai_compat / nomic-embed-text) unless
REM                                      AM7_OLLAMA_EMBED=0. Host port 11435 (loopback).
REM                                      CPU-only unless AM7_OLLAMA_GPU=nvidia (Docker
REM                                      Desktop cannot pass the AMD iGPU through).
REM    am7-docker-up.bat --app-only      the reverse of --llmproxy-only: start ONLY
REM                                      the app + its database (am7 + am7-pg), and
REM                                      STOP any LiteLLM/Langfuse/Ollama sidecars that
REM                                      are running, so you get the overhead back.
REM    am7-docker-up.bat --llmproxy-only start ONLY the proxy/observability stack
REM                                      (litellm + langfuse + am7-pg, which hosts
REM                                      litellmdb). Does NOT build, does not stage
REM                                      the Olio seed, and does NOT start the app.
REM                                      Use when you want the proxy without the
REM                                      Service7/Ux752 overhead. With --ollama the
REM                                      Ollama pair is included.
REM    am7-docker-up.bat --stop          STOP every container in the am7test project
REM                                      (up to 11: app, LiteLLM/Langfuse and Ollama
REM                                      sidecars) and exit. Nothing is removed: no
REM                                      container, no volume, no data. Build and seed
REM                                      skipped. The profile flags in that call are
REM                                      mandatory - see the note at the implementation.
REM    am7-docker-up.bat --start         start those containers back up without
REM                                      recreating or rebuilding anything.
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

REM  Every optional compose profile this project defines. Lifecycle actions (--stop,
REM  --start, ps, the `down` advice) must name ALL of them: compose only acts on
REM  services in an ACTIVE profile, so a missing entry here silently orphans that
REM  profile's containers. Add to this when a profile is added to the compose file.
set "ALL_PROFILES=--profile llmproxy --profile ollama"

REM  --ollama knobs (set in the environment before running to override):
REM    AM7_OLLAMA_EMBED  1 (default) points the app's embeddings at the container:
REM                      EMBEDDING_SERVER=http://ollama:11434/v1/embeddings,
REM                      EMBEDDING_TYPE=openai_compat, EMBEDDING_MODEL=nomic-embed-text.
REM                      0 leaves the compose defaults (the LAN embedApiMini on .42:8123).
REM                      An EMBEDDING_* already set in the environment always wins.
REM    AM7_OLLAMA_GPU    nvidia layers docker-compose.ollama-gpu.yml (device reservation).
REM                      Unset = CPU only, which is all Docker Desktop offers on this
REM                      AMD iGPU host.
REM    OLLAMA_HOST_PORT  host loopback port the container publishes (default 11435, so a
REM                      host Ollama on 11434 - the `-local` aliases - can coexist).
if not defined AM7_OLLAMA_EMBED set "AM7_OLLAMA_EMBED=1"
if not defined OLLAMA_HOST_PORT set "OLLAMA_HOST_PORT=11435"
REM  Records which embedding provider the data dir was last booted with (sticky WARN below).
set "EMBED_MARKER=%SRC_DIR%docker-data\.embedding-provider"

REM CORS_ALLOWED_ORIGINS REPLACES the compose default, so list every origin -
REM appending is not a thing. localhost + 127.0.0.1 + the LAN IP, http and https.
set "CORS_ALLOWED_ORIGINS=https://localhost:%APP_PORT%,http://localhost:%APP_PORT%,https://127.0.0.1:%APP_PORT%,http://127.0.0.1:%APP_PORT%,http://localhost:8899,https://localhost:8899,https://%LAN_IP%:%APP_PORT%,http://%LAN_IP%:%APP_PORT%"

set "BUILD_FLAG=--build"
set "BUILD_ARG="
set "PROFILE_ARGS="
set "EXTRA_FILE_ARGS="
set "ENVFILE_ARGS="
set "LLMPROXY="
set "OLLAMA="
set "LLMPROXY_ONLY="
set "APP_ONLY="
set "LIFECYCLE="

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
if /i "%~1"=="--llmproxy" set "LLMPROXY=1"
if /i "%~1"=="--llmproxy-only" (
    set "LLMPROXY=1"
    set "LLMPROXY_ONLY=1"
    set "BUILD_FLAG="
)
REM  --ollama IMPLIES --llmproxy: the point of the container is that the `*-ctr`
REM  LiteLLM aliases route to it and every test call lands in Langfuse.
if /i "%~1"=="--ollama" (
    set "OLLAMA=1"
    set "LLMPROXY=1"
)
if /i "%~1"=="--app-only" set "APP_ONLY=1"
if /i "%~1"=="--stop" set "LIFECYCLE=stop"
if /i "%~1"=="--start" set "LIFECYCLE=start"
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-h" goto :usage
if /i "%~1"=="/?" goto :usage
shift
goto :parseargs
:parsedargs

REM  Profiles are ADDITIVE - assembled after the loop so flag order does not matter.
if defined LLMPROXY set "PROFILE_ARGS=--profile llmproxy"
if defined OLLAMA set "PROFILE_ARGS=%PROFILE_ARGS% --profile ollama"

if defined APP_ONLY if defined LLMPROXY_ONLY (
    echo ERROR: --app-only and --llmproxy-only are opposites; pick one.
    exit /b 1
)
if defined APP_ONLY if defined OLLAMA (
    echo ERROR: --app-only stops every sidecar, including Ollama; it cannot be combined with --ollama.
    exit /b 1
)

REM  GPU opt-in: an override file rather than an always-on device reservation,
REM  because `deploy.resources.reservations.devices` makes `up` FAIL outright on a
REM  host without the NVIDIA runtime.
if defined OLLAMA if defined AM7_OLLAMA_GPU if /i not "%AM7_OLLAMA_GPU%"=="nvidia" (
    echo ERROR: AM7_OLLAMA_GPU='%AM7_OLLAMA_GPU%' - only 'nvidia' ^(or unset^) is supported.
    exit /b 1
)
if defined OLLAMA if /i "%AM7_OLLAMA_GPU%"=="nvidia" set "EXTRA_FILE_ARGS=-f docker-compose.ollama-gpu.yml"

REM  Corporate TLS interception (Microsoft Global Secure Access on this network):
REM  with the VPN up every HTTPS site is re-signed by a CA the ollama and litellm
REM  images do not trust, so `ollama pull` and LiteLLM->Azure fail with x509
REM  errors. If a CA bundle is present, layer docker-compose.extra-ca.yml, which
REM  mounts it into both. Measured 2026-09-29: pulls that failed with
REM  "certificate signed by unknown authority" succeeded once mounted. See
REM  aiDocs\dockerDevSetup.md section 13.6 for how to produce the PEM.
set "CA_FILE_ARGS="
if defined AM7_EXTRA_CA_FILE (
    if not exist "%AM7_EXTRA_CA_FILE%" (
        echo ERROR: AM7_EXTRA_CA_FILE='%AM7_EXTRA_CA_FILE%' does not exist.
        exit /b 1
    )
    set "CA_FILE_ARGS=-f docker-compose.extra-ca.yml"
) else if exist "%SRC_DIR%volatile\extra-ca.pem" (
    set "CA_FILE_ARGS=-f docker-compose.extra-ca.yml"
)

REM  Container embeddings for the APP. Only fills a variable that is not already
REM  set, so a caller's own EMBEDDING_* wins; the compose ${VAR:-...} defaults are
REM  what these replace. The URL is the compose SERVICE name - resolvable only
REM  inside the am7-test-net network, which is where Tomcat runs.
if defined OLLAMA if not "%AM7_OLLAMA_EMBED%"=="0" (
    if not defined EMBEDDING_SERVER set "EMBEDDING_SERVER=http://ollama:11434/v1/embeddings"
    if not defined EMBEDDING_TYPE set "EMBEDDING_TYPE=openai_compat"
    if not defined EMBEDDING_MODEL set "EMBEDDING_MODEL=nomic-embed-text"
)

REM  The embedding provider this `up` hands the app, as compose will see it: the
REM  set value, else the compose default. Same "server type model" format as the
REM  .sh writes, so the two scripts share one marker file. (Values that live only
REM  in src\.env are not visible here, so a .env override makes this approximate -
REM  it is a WARN, not a gate.)
if defined EMBEDDING_SERVER (set "E_SERVER=%EMBEDDING_SERVER%") else (set "E_SERVER=http://192.168.1.42:8123")
if defined EMBEDDING_TYPE (set "E_TYPE=%EMBEDDING_TYPE%") else (set "E_TYPE=local")
if defined EMBEDDING_MODEL (set "E_MODEL=%EMBEDDING_MODEL%") else (set "E_MODEL=-")
set "EMBED_EFFECTIVE=%E_SERVER% %E_TYPE% %E_MODEL%"

if defined LIFECYCLE if defined APP_ONLY (
    echo ERROR: --%LIFECYCLE% is a whole-project lifecycle action; it cannot be
    echo        combined with --app-only, --llmproxy-only or --prebuilt.
    exit /b 1
)
if defined LIFECYCLE if defined LLMPROXY_ONLY (
    echo ERROR: --%LIFECYCLE% is a whole-project lifecycle action; it cannot be
    echo        combined with --app-only, --llmproxy-only or --prebuilt.
    exit /b 1
)
if defined LIFECYCLE if defined BUILD_ARG (
    echo ERROR: --%LIFECYCLE% is a whole-project lifecycle action; it cannot be
    echo        combined with --app-only, --llmproxy-only or --prebuilt.
    exit /b 1
)

REM  STOP_GRACE - see the long note in am7-docker-up.sh. Compose's default stop
REM  timeout is 10s, after which it SIGKILLs (an `Exited (137)` in the status
REM  table). 20s is the cheapest setting measured at which am7 (Tomcat: DB
REM  connections, vault) and am7-pg both reach Exited (0). Wall time is NOT one
REM  grace period - compose stops in dependency waves, each with its own timeout,
REM  so 20s costs about 45s in total. langfuse-web/worker ignore SIGTERM at every
REM  grace tested and are always killed.
REM    set "STOP_GRACE=10"  before running for a fast (~5s) but dirty stop
REM    set "STOP_GRACE=45"  to also get litellm + clickhouse clean (~55s)
if not defined STOP_GRACE set "STOP_GRACE=20"

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
if defined LLMPROXY echo    llmproxy     : ON  - litellm http://127.0.0.1:4000/ui/  langfuse http://127.0.0.1:3001
if not defined LLMPROXY if not defined APP_ONLY echo    llmproxy     : off ^(pass --llmproxy to start the LiteLLM/Langfuse sidecars^)
if defined APP_ONLY echo    llmproxy     : off - and any running sidecars will be STOPPED ^(--app-only^)
if defined CA_FILE_ARGS (
    if defined AM7_EXTRA_CA_FILE (echo    extra CA     : %AM7_EXTRA_CA_FILE% -^> ollama + litellm ^(docker-compose.extra-ca.yml^)) else (echo    extra CA     : .\volatile\extra-ca.pem -^> ollama + litellm ^(docker-compose.extra-ca.yml^))
)
if defined OLLAMA (
    if defined AM7_OLLAMA_GPU (echo    ollama       : ON  - http://127.0.0.1:%OLLAMA_HOST_PORT%  gpu=%AM7_OLLAMA_GPU%) else (echo    ollama       : ON  - http://127.0.0.1:%OLLAMA_HOST_PORT%  gpu=cpu-only)
    echo                   litellm aliases: qwen3:8b-ctr  qwen3:8b-jos-ctr  nomic-embed-text-ctr
    if not "%AM7_OLLAMA_EMBED%"=="0" (echo    embeddings   : %EMBEDDING_SERVER%  type=%EMBEDDING_TYPE%  model=%EMBEDDING_MODEL%) else (echo    embeddings   : compose defaults ^(AM7_OLLAMA_EMBED=0^))
) else (
    echo    ollama       : off ^(pass --ollama for a containerized Ollama; implies --llmproxy^)
)
echo ============================================================
echo.

REM  STICKY EMBEDDING PROVIDER - WARN, do not refuse. embedding.server is DB-BACKED
REM  (a /System system.connection row seeded from EMBEDDING_SERVER on the FIRST boot
REM  of a data volume and re-read from the DB afterwards), while embedding.type and
REM  embedding.model are BOOT-PINNED from the env every start. So changing provider
REM  on an existing volume gives a new type/model against the OLD stored URL, and
REM  vectors already in the fixed-width common.vectorExt.embedding column carry no
REM  provenance. (architecture.md "Config: DB-backed vs boot-pinned".)
set "EMBED_PREVIOUS="
if not defined LIFECYCLE if not defined LLMPROXY_ONLY if exist "%EMBED_MARKER%" set /p EMBED_PREVIOUS=<"%EMBED_MARKER%"
if defined EMBED_PREVIOUS if not "%EMBED_PREVIOUS%"=="%EMBED_EFFECTIVE%" (
    echo   WARNING: embedding provider differs from the one this data dir last booted with.
    echo            was : %EMBED_PREVIOUS%
    echo            now : %EMBED_EFFECTIVE%            ^(server type model^)
    echo            embedding.server is DB-backed and will NOT follow EMBEDDING_SERVER on an
    echo            existing volume; type/model WILL. Existing vectors keep their old width/
    echo            provenance. Reset the volume ^(dockerDevSetup.md section 7^) or repoint the
    echo            /System embedding connection by hand if this switch is intentional.
    echo.
)

docker version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not responding. Is Docker Desktop running?
    popd
    exit /b 1
)

REM  --stop / --start: whole-project lifecycle, no build, no seed, no data touched.
REM
REM  THE PROFILE FLAGS HERE (%ALL_PROFILES%) ARE LOAD-BEARING, not decoration.
REM  Compose only acts on services in an ACTIVE profile, and a profile is active
REM  only when named. So the obvious `docker compose -p am7test -f
REM  docker-compose.test.yml stop` reaches just the 2 non-profile containers and
REM  silently leaves the LiteLLM/Langfuse and Ollama sidecars running. Measured
REM  2026-09-17 with `stop --dry-run` against all 9 then-defined containers:
REM  without the flag it stopped only am7test-am7-1 and am7-pg. Passing profiles
REM  is additive - the non-profile services stay active - so one call naming
REM  every profile covers the whole project (up to 11 containers now).
REM
REM  `stop`/`start`, never `down`: the containers and their volumes survive, so a
REM  --start brings the stack back in seconds with no rebuild and no re-seed.
if defined LIFECYCLE (
    if "%LIFECYCLE%"=="stop" (
        echo [1/2] Stopping every container in project %PROJECT% ^(app + sidecars^) ...
        docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ALL_PROFILES% stop -t %STOP_GRACE%
    ) else (
        echo [1/2] Starting the stopped containers in project %PROJECT% ...
        docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ALL_PROFILES% start
    )
    if errorlevel 1 goto :lifecycleerr
    echo.
    echo [2/2] Status:
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ALL_PROFILES% ps -a
    echo.
    echo ============================================================
    if "%LIFECYCLE%"=="stop" (
        echo  Stopped. Nothing was removed - no container, volume or byte of data.
        echo  Bring it back:  am7-docker-up.bat --start
    ) else (
        echo  Started. No container was recreated and nothing was rebuilt.
        echo  App: https://localhost:%APP_PORT%   LAN: https://%LAN_IP%:%APP_PORT%
    )
    echo ============================================================
    popd
    endlocal
    exit /b 0
)

REM  --app-only: the reverse of --llmproxy-only. `docker compose up` would simply
REM  leave already-running profile containers alone, so "only the app" has to stop
REM  them explicitly - that is the whole point of the flag (reclaiming the RAM the
REM  sidecars hold). `stop`, not `down`: it leaves the containers and their data
REM  in place so a later --llmproxy / --ollama restarts them in seconds.
if defined APP_ONLY (
    echo [0/4] --app-only: stopping any running LiteLLM/Langfuse/Ollama sidecars ...
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ALL_PROFILES% stop litellm langfuse-web langfuse-worker langfuse-clickhouse langfuse-minio langfuse-redis langfuse-db ollama ollama-init
    echo.
)

REM  --llmproxy-only: bring up just the proxy/observability side. Naming the three
REM  services explicitly (rather than relying on the profile alone) keeps the `am7`
REM  app out of it; compose still pulls in each one's depends_on, so am7-pg
REM  (which hosts litellmdb), langfuse-db, clickhouse, minio and redis come along.
REM  langfuse-worker is listed because nothing depends_on it, yet without it the
REM  ingestion API accepts events and the UI stays permanently empty. With --ollama
REM  the Ollama pair is added to the list.
set "SIDECAR_SERVICES=litellm langfuse-web langfuse-worker"
if defined OLLAMA set "SIDECAR_SERVICES=%SIDECAR_SERVICES% ollama ollama-init"
if defined LLMPROXY_ONLY (
    echo [1/2] Starting the LiteLLM/Langfuse stack only ^(no app, no build, no seed^): %SIDECAR_SERVICES% ...
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %EXTRA_FILE_ARGS% %ENVFILE_ARGS% %PROFILE_ARGS% up -d %SIDECAR_SERVICES%
    if errorlevel 1 (
        echo ERROR: `docker compose up` failed.
        popd
        exit /b 1
    )
    echo.
    echo [2/2] Status:
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ENVFILE_ARGS% %PROFILE_ARGS% ps
    echo.
    echo ============================================================
    echo  LiteLLM admin UI : http://127.0.0.1:4000/ui/   ^(admin / your LITELLM_MASTER_KEY^)
    echo  Langfuse UI      : http://127.0.0.1:3001       ^(LANGFUSE_INIT_USER_EMAIL / _PASSWORD^)
    echo  Health           : http://127.0.0.1:4000/health/liveliness
    echo.
    echo  Point an AM7 system.connection at http://litellm:4000 with dialect=OPENAI_COMPAT
    echo  ^(http://127.0.0.1:4000 from host-side JUnit^). Runbook: aiDocs\dockerDevSetup.md section 12.
    if defined OLLAMA (
        echo  Ollama container : http://127.0.0.1:%OLLAMA_HOST_PORT%/api/tags   aliases: qwen3:8b-ctr qwen3:8b-jos-ctr nomic-embed-text-ctr
        echo  Model pull ^(multi-GB on first run; the aliases 404 until it finishes^):
        echo    docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% logs -f ollama-init
    )
    echo  Stop: docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% down
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
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% up %BUILD_FLAG% -d am7 am7-pg
) else (
    docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %EXTRA_FILE_ARGS% %ENVFILE_ARGS% %PROFILE_ARGS% up %BUILD_FLAG% -d
)
if errorlevel 1 (
    echo ERROR: `docker compose up` failed.
    popd
    exit /b 1
)

REM  Record the embedding provider this data dir was just booted with (feeds the
REM  sticky WARN above on the next run). Redirect-first so no trailing space lands
REM  in the file; best-effort, must not fail the `up`.
>"%EMBED_MARKER%" echo %EMBED_EFFECTIVE% 2>nul

echo.
echo [4/4] Status:
docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ENVFILE_ARGS% %PROFILE_ARGS% ps

if defined OLLAMA (
    echo.
    echo  Ollama container : http://127.0.0.1:%OLLAMA_HOST_PORT%/api/tags
    echo  Model pull       : ollama-init pulls nomic-embed-text, qwen3:8b, goekdenizguelmez/JOSIEFIED-Qwen3:8b
    echo                     ^(multi-GB on first run; the *-ctr LiteLLM aliases 404 until it exits 0^). Follow it:
    echo                     docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% logs -f ollama-init
    echo  Aliases          : curl -s -H "Authorization: Bearer <LITELLM_MASTER_KEY>" http://127.0.0.1:4000/v1/models ^| findstr -- -ctr
)

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
echo  Stop (keep all):  am7-docker-up.bat --stop     ^(containers kept; --start to resume^)
REM  NOTE the profile flags on the `down`: without them compose removes only the 2
REM  non-profile containers and then fails trying to delete a network the sidecars
REM  are still attached to. The earlier advice here omitted them. Verified
REM  2026-09-17 with `down --dry-run` (llmproxy); ollama added 2026-09-29.
echo  Remove ^(keeps data volumes^):
echo     docker compose -p %PROJECT% -f %COMPOSE_FILE% %ALL_PROFILES% down
echo.

popd
endlocal
REM  MUST exit here: without it execution falls straight through into :usage and
REM  every successful run prints the help block.
exit /b 0

:lifecycleerr
echo.
echo ERROR: `docker compose %LIFECYCLE%` failed. Check the daemon and the project name.
docker compose -p %PROJECT% -f %COMPOSE_FILE% %CA_FILE_ARGS% %ALL_PROFILES% ps -a
popd
endlocal
exit /b 1

:usage
echo.
echo  am7-docker-up.bat [--no-build ^| --prebuilt] [--llmproxy ^| --llmproxy-only ^| --app-only] [--ollama]
echo  am7-docker-up.bat --stop ^| --start
echo.
echo    --no-build        start the existing am7:latest image
echo    --prebuilt        build with PREBUILT=1 (host-built WAR)
echo    --llmproxy        also start the LiteLLM + Langfuse sidecars (7 containers)
echo    --ollama          also start a containerized Ollama + one-shot model pull
echo                      (2 containers; IMPLIES --llmproxy). Publishes 127.0.0.1:11435.
echo                      Points the app's embeddings at it unless AM7_OLLAMA_EMBED=0.
echo    --llmproxy-only   start ONLY the sidecars - no app, no build, no seed
echo                      (with --ollama, the Ollama pair is included)
echo    --app-only        start ONLY the app + its DB, and STOP any running sidecars
echo    --stop            STOP every container in the am7test project and exit.
echo                      Does NOT remove them or touch any data.
echo    --start           start those stopped containers back up, without
echo                      recreating or rebuilding anything.
echo.
echo  --stop and --start are exclusive - they ignore every other flag.
echo  Set STOP_GRACE (default 20) to trade stop speed against clean shutdown.
echo  Ollama knobs: AM7_OLLAMA_EMBED  AM7_OLLAMA_GPU  OLLAMA_HOST_PORT  OLLAMA_PULL_MODELS
echo.
echo  Flags may be combined, e.g.:  am7-docker-up.bat --no-build --ollama
echo  Runbook: src\aiDocs\dockerDevSetup.md sections 12-13
echo.
popd 2>nul
endlocal
exit /b 0
