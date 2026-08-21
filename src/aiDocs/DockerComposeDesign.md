# Single-container Docker Compose (Service7 + Ux752) — Design & Status

**Status: verified working end-to-end.** Last updated 2026-07-15.

The image builds, the container boots all three processes (Tomcat + nginx + vite preview),
Tomcat deploys the webapp, the schema is created on a fresh Postgres, and nginx `:8443`
reverse-proxies both the REST API and the Ux752 UI. Verified against a disposable
`pgvector/pgvector:pg17` container (see "Verification 2026-07-15" below).

## Build & run

Two compose files exist at the repo root:

| File | Postgres/pgvector | App host port | Use |
|------|-------------------|---------------|-----|
| `docker-compose.yml` | **external** — you run it yourself | `8443` | the canonical single-container stack |
| `docker-compose.test.yml` | **bundled** dedicated `am7-pg` service | `9443` | isolated local test stack — no collision with a local Tomcat (`8443`) or a dev Postgres (`15432`) |

Prereqs: Docker Desktop running. The SD/face/voice/embedding features reach LAN servers
(`192.168.1.42:*`) via the `*_SERVER` env defaults — override or ignore those if you only need the
REST API + UI. Both stacks create the DB schema on first boot but leave the admin/org **setup** as a
deliberate step (below).

### Option A — isolated test stack (dedicated `am7-pg` pgvector) — `docker-compose.test.yml`

Brings up the app **and** a dedicated `am7-pg` pgvector container on their own network, on
non-colliding host ports (`9443` app, `15433` pg). Runs with an explicit project name so its network
stays separate from the main compose project.

**Persistence:** all durable state is **bind-mounted to a concrete host directory** —
`AM7_DATA_DIR`, default `./docker-data` (next to the compose file) — so nothing is ephemeral:
`docker-data/pg` (Postgres data), `docker-data/am7` (app keystores/vault/datagen/streams), and
`docker-data/certs` (the shared TLS pair). Without such a mapping this state would live only in the
container's writable layer and be lost on every recreate. The dir is git-ignored (`src/.gitignore`).

```bash
# build images + start the app and the dedicated am7-pg pgvector container
docker compose -p am7test -f docker-compose.test.yml up --build
#   (persist elsewhere: AM7_DATA_DIR=/abs/path docker compose -p am7test -f docker-compose.test.yml up --build)

# one-time DB setup (creates the admin credential for /System, /Development, /Public).
# Run from WSL/Linux or a browser — Windows curl returns HTTP 000 against nginx's self-signed cert.
#
# EASIEST PATH: just open https://localhost:9443 in a browser. On an unconfigured deployment the
# UI routes itself to the setup page, where you set the admin password, the six media/AI server
# URLs, and an initial user in one step.
#
# BREAKING CHANGE (setup hardening): POST /rest/setup/ now REQUIRES the one-shot setup token.
# Without a valid X-AM7-Setup-Token header it returns 404 — the same 404 it returns when setup is
# already complete or the token is wrong, deliberately, so the endpoint is not an oracle. The token
# is NOT printed to the log; read it out of the container:
SETUP_TOKEN=$(docker exec am7test-am7-1 cat /data/am7/store/.setup.token)

curl -k -X POST https://localhost:9443/rest/setup/ \
  -H 'Content-Type: application/json' \
  -H "X-AM7-Setup-Token: $SETUP_TOKEN" \
  -d '{"credential":"'"$(printf 'password' | base64)"'"}'

# The legacy record-shaped body ({"schema":"auth.credential",...,"type":"hashed_password"}) still
# works — only `credential` is read from it. The richer body additionally accepts:
#   "initialUser": {"name":"...","credential":"<base64>","organization":"/Public"|"/Development"}
#   "servers":     {"sd":"...","face":"...","tag":"...","voice.tts":"...","voice.stt":"...","embedding":"..."}
# Passwords must be >= 8 characters. "/System" is rejected for the initial user.
#
# Once setup completes, the token file is deleted and .setup.done is written, so the endpoint stays
# 404 and no token is advertised on later boots. Server URLs are then edited at
# #!/list/system.connection, or via AccountManagerConsole7 `-serverConfig`.

# app (UI + REST behind nginx): https://localhost:9443
# inspect the dedicated DB if desired: psql -h localhost -p 15433 -U am7user -d am72db  (password: password)

# ── CREDENTIAL OF RECORD — do not deviate ───────────────────────────────────────────────────────
# The am7test admin password is `password`, on all three orgs. That is what the curl above sets and
# what EVERY e2e helper already assumes as its default: e2e/helpers/auth.js:15, e2e/helpers/api.js:46,
# SHARED_PASSWORD (:318) and ADMIN_ROLE_PASSWORD (:367). Setting anything else silently breaks the
# whole Playwright suite, because those helpers need one admin session to PROVISION the test users —
# so a wrong admin password blocks not just admin tests but every test.
#
# Credentials are salted hashes and CANNOT be recovered. If the admin password is ever lost, the only
# remedy is a full reset (below) — there is no recovery path.
#
# This happened: the stack set up 2026-08-06 used an unrecorded password, which cost a full reset on
# 2026-08-07. If you must deviate, write the value into ./volatile/ (git-ignored) BEFORE running setup.
#
# RESET PROCEDURE (disposable stack only — NEVER confuse this with a schema -Dreset, which is
# forbidden; this destroys a dedicated throwaway container + its bind-mounted dir, not a real DB):
#   docker compose -p am7test -f docker-compose.test.yml down
#   mv docker-data docker-data.reset-<YYYYMMDD>      # keeps the old state; docker-data* is git-ignored
#   docker compose -p am7test -f docker-compose.test.yml up -d      # omit --build to keep the current
#                                                                   # image if source is mid-change
#   TOKEN=$(cat docker-data/am7/store/.setup.token)
#   curl -k -X POST https://127.0.0.1:9443/AccountManagerService7/rest/setup/ \
#     -H 'Content-Type: application/json' -H "X-AM7-Setup-Token: $TOKEN" \
#     -d '{"credential":"cGFzc3dvcmQ="}'            # base64 of `password`
#   # verify (must return `true`, not `false`):
#   curl -k -X POST https://127.0.0.1:9443/AccountManagerService7/rest/login \
#     -H 'Content-Type: application/json' \
#     -d '{"schema":"auth.credential","organizationPath":"/Development","name":"admin","credential":"cGFzc3dvcmQ=","type":"hashed_password"}'
#
# NOTE the REST path prefix: /AccountManagerService7/rest/... through nginx. The curl earlier in this
# file omits it (`https://localhost:9443/rest/setup/`) and will 404.
#
# NOTE 127.0.0.1, not localhost: Docker publishes IPv4-only and Chromium resolves localhost to ::1
# first, failing with net::ERR_CONNECTION_ABORTED. Playwright must use the IPv4 literal, and
# CORS_ALLOWED_ORIGINS must then include https://127.0.0.1:9443 or the login POST is blocked.
# ────────────────────────────────────────────────────────────────────────────────────────────────

docker compose -p am7test -f docker-compose.test.yml down     # stop; data KEPT on the host (./docker-data)
docker compose -p am7test -f docker-compose.test.yml down; rm -rf ./docker-data   # full reset (wipe host data)
```

The bundled `am7-pg` seeds `am72db`/`am7user`/`password` on first boot (from `POSTGRES_*`), which is
exactly what the app connects to over the private network — no manual DB/user creation. The app waits
on `am7-pg`'s healthcheck before starting. Because everything is a host bind mount there are **no
named volumes** — `down` keeps the data, and a full reset is just deleting `./docker-data` (the DB and
the keystores share one lifecycle, so they can't desync into the orphaned-org state). **Windows note:**
if Postgres fails to initialize on the host bind mount (rare on Docker Desktop/WSL2), swap the `am7-pg`
data mount for a named volume — see the inline comment in `docker-compose.test.yml`.

### Option B — canonical stack (external Postgres) — `docker-compose.yml`

Run pgvector yourself (per `setup/dockerNotes.txt`), then bring the app up on `8443`:

```bash
docker run -d --name am7-pg -p 15433:5432 \
  -e POSTGRES_DB=am72db -e POSTGRES_USER=am7user -e POSTGRES_PASSWORD=password \
  pgvector/pgvector:pg17
DB_HOST=host.docker.internal DB_PORT=15433 docker compose up --build
# then the same POST /rest/setup/ as above, against https://localhost:8443
```

> **Verification status:** the canonical `docker-compose.yml` path was verified end-to-end 2026-07-15
> (log below).
>
> `docker-compose.test.yml` is now **verified booted end-to-end (2026-08-05)** — the earlier
> "compose-validated but never run" caveat is resolved. On `:9443`, from a genuine first-run state
> (3 orgs, 0 user credentials, no marker, no connections), a token-authenticated
> `POST /rest/setup/` returned `{"ok":true}` and was confirmed to: create a real `HASHED_PASSWORD`
> admin credential in all three default orgs **and log in successfully with it** (wrong-password
> negative control failed as expected); create an initial user in `/Public` that logs in, holds only
> `AccountUsers`+`Requesters`, and **cannot** authenticate to `/System`; write all six
> `system.connection` records; write `.setupState`, delete `.setup.token`, and create `.setup.done`.
> Afterwards the latch held closed (`/state` → `initialized:true` with no `servers` key even WITH a
> token; `POST` → byte-identical 404s), and **all of it survived a container restart with zero
> "FIRST-RUN" log lines**.
>
> The sharpest result: after that restart the regenerated `WEB-INF/web.xml` still contained
> `http://192.168.1.42:8123` while the running WAR resolved the DB-configured value — confirming the
> core design premise that DB-backed configuration beats the `envsubst` template on every boot.

## Goal

Package `AccountManagerService7` (Tomcat 11) and `AccountManagerUx752` together in one Docker
container, fronted by nginx, orchestrated via `docker-compose.yml` at the repo root. Postgres/pgvector
stays external (matches `setup/dockerNotes.txt` precedent of running it as its own container).

## Decisions made (with Stephen)

1. **TLS**: nginx terminates the external `:8443` connection with a self-signed cert generated at
   container start, then re-encrypts to Tomcat's own HTTPS connector on `127.0.0.1:8444` (internal
   only) with `proxy_ssl_verify off`. Double-TLS by design — mirrors the existing dev setup where
   Vite's dev-server proxy already trusts Tomcat's self-signed cert (`secure:false`).
2. **Maven build**: the Docker build stage uses normal network access to Maven Central (not the
   repo's usual offline `-o` convention) — a fresh build stage has no populated `.m2` cache. Confined
   to the image build; local `mvn -o` workflow is untouched.
3. **DB scope**: `docker-compose.yml` does **not** define a Postgres service. The container connects
   to an external, already-running Postgres via `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`.
4. **Ux752 serving mode**: runs at runtime as a node/npm process (`npx vite preview`) behind nginx —
   per Stephen's literal wording ("Ux752 under node/npm w/ ngx as a proxy") — rather than nginx
   serving the static `vite build` output directly.
5. **`task.api.key` JWT** (stale am6-era token already committed in `web.xml`): confirmed not a live
   credential; kept as the default value in the template, not scrubbed.

## What was found and had to be worked around

- **`web.xml`/`context.xml` hold Windows-only paths, LAN server addresses, and hardcoded DB
  creds** — templated into `docker/web.xml.template` / `docker/context.xml.template` with
  `${VAR}` placeholders, rendered via `envsubst` in `docker/entrypoint.sh` at container start (never
  baked into the image — the originals are deleted from the image in the same `RUN` step that
  explodes the WAR, per security review below).
- **JAAS login config is not wired anywhere in tracked files.** `AccountManagerContextListener`/
  `RestServiceEventListener` never call `System.setProperty("java.security.auth.login.config", ...)`;
  this is presumably an untracked Eclipse VM-argument on Stephen's machine. Added
  `docker/setenv.sh` (sourced by `catalina.sh`) to set it explicitly, pointing at the `jaas.conf`
  bundled into `WEB-INF/classes` by the WAR build.
- **Ux752's committed `package-lock.json` is out of sync with `package.json`** (lock has
  `vitest@2.1.9`, package.json wants `^4.1.9`), so `npm ci` fails with `EUSAGE`. Worked around by
  using `npm install` in the Dockerfile's Ux752 build stage instead. **The lock file itself should
  still be regenerated in the repo** — not done here, out of this task's scope.
- **Security review (`security-reviewer` agent) flagged and fixed**: original `web.xml`/`context.xml`
  were leaking into the image layer despite being "templated" (fixed — `rm` them at explode time);
  generated TLS private key had no restrictive permissions (fixed — `umask 077` + `chmod 600`);
  `CORS_ALLOWED_ORIGINS` had no guard against a wildcard override combined with
  `cors.support.credentials=true` (fixed — `entrypoint.sh` now refuses `*`).
  **Not yet addressed** (non-blocking, flagged as follow-up): the whole stack runs as root inside
  the container (no non-root user for supervisord's children); `envsubst` substitutes env vars into
  XML attributes without escaping (`"`, `<`, `>`, `&` in a value could corrupt the XML).
- **Tomcat's HTTPS connector moved off `:8443` to `127.0.0.1:8444`** (internal-only) since nginx now
  owns the external `:8443`; `docker/server.xml` and `docker/nginx.conf` were written from scratch
  (no `server.xml` was previously tracked in the repo — Stephen's local Tomcat config isn't in git).
- **Bug caught during first real container run**: `docker/server.xml`'s own explanatory XML comment
  contained a literal `--`, which is illegal inside an XML comment — this crash-looped Tomcat
  (`SAXParseException: The string "--" is not permitted within comments`) under supervisord's
  autorestart. Fixed by rewording the comment. Not yet re-verified after the fix (see Status).

## Files created

`Dockerfile`, `docker-compose.yml`, `.dockerignore`, `docker/nginx.conf`, `docker/entrypoint.sh`,
`docker/server.xml`, `docker/setenv.sh`, `docker/supervisord.conf`, `docker/context.xml.template`,
`docker/web.xml.template`.

## Verification 2026-07-15 (bugs found & fixed while getting it running)

Rebuilt with the `server.xml` fix and ran against a disposable `pgvector/pgvector:pg17`
(`-p 15432:5432`, DB `am72db`). Two real bugs surfaced and were fixed:

1. **CRLF line endings crash-looped the container.** On a Windows checkout every `docker/*` file
   had CRLF endings. `exec /usr/local/bin/entrypoint.sh` failed with `no such file or directory`
   (a `\r` in the shebang makes the kernel look for interpreter `/bin/bash\r`); a trailing `\r` on
   a supervisord `command=` line would likewise corrupt the launched process's args. Fixed by adding
   `src/.gitattributes` forcing `eol=lf` on `*.sh` and everything under `docker/**`, and normalizing
   the working copies. **This is the reason the "first run" on the dev box worked but a fresh
   Windows clone did not.**

2. **`NoClassDefFoundError: org/bouncycastle/jce/provider/BouncyCastleProvider`** at
   `RestServiceEventListener.initializeAccountManager` line 219 (`IOSystem.open`). `bcprov-jdk18on`
   is declared `<scope>provided</scope>` in `AccountManagerService7/pom.xml` (on the dev box BC is a
   container/JVM-level security provider) so it is deliberately excluded from the WAR — but the
   image had no such provider. Because it is an `Error` (not an `Exception`) it slipped past the
   `catch (Exception e)` in `initializeAccountManager`, so nothing was logged to the console; it only
   showed up in Tomcat's JULI `localhost.<date>.log` as a `SEVERE Servlet.init()` failure, which then
   made **every** REST request re-run servlet init and return HTTP 500. Fixed in the Dockerfile:
   `mvn dependency:copy-dependencies -DincludeArtifactIds=bcprov-jdk18on` resolves the jar from
   Objects7's own dependency graph in the java-build stage (so the version can never drift from the
   `bcpkix`/`bcutil` the WAR actually ships) and it is copied into `WEB-INF/lib` via a version-glob —
   kept beside the other `bc*` jars in the same classloader, since BC jars are signed/sealed and
   splitting them across classloaders trips JCE signature verification.

**What was verified (all green):**
- Image builds; container runs all three supervised processes (Tomcat, nginx, `vite preview`).
- Tomcat deploys the webapp; `setenv.sh` puts `-Djava.security.auth.login.config=.../jaas.conf` on
  the JVM (confirmed on the live command line).
- On a fresh DB, `IOSystem.open` created **132 `a7_*` tables** and initialized the org vault.
- **DB init is a deliberate step, not automatic at startup** — run it via `AccountManagerConsole7`
  or the REST setup endpoint. `POST /rest/setup/` takes a credential like the Ux login payload
  (`{"schema":"auth.credential","credential":"<base64(password)>","type":"hashed_password"}`); it
  created the admin credential for `/System`, `/Development`, `/Public` and returned `true`.
- Through nginx `:8443` (tested from a real Linux curl client against the container to bypass a
  Windows-curl-only TLS quirk, below): UI `/` → 200 `text/html`; `GET /rest/setup/` → `true`;
  `GET /rest/schema` → 200 `application/json`; `POST /rest/setup/` and `POST /rest/login`
  (admin/System, container-init smoke check) → 200 `true`. Confirms the double-TLS nginx→Tomcat
  passthrough handles GET, POST-with-body, and JAAS auth.
- **UI works behind a real domain name.** A request with `Host: am7.example.com` returns the app
  (200, real `index.html`) rather than Vite 6 preview's "Blocked request. This host is not allowed."
  page — because `nginx.conf`'s `location /` pins the upstream `Host` to `localhost:8899` (see the
  fix below), which is always in Vite's allowed set.
- **Key/state persistence survives container recreation.** With the `am7-data` volume mounted,
  recreating the container against the same DB + volume re-initializes cleanly ("Working with
  existing organization /System, /Development, /Public", **no** "Failed to initialize key stores").
  Verified the failure mode too: running **without** the volume against a DB that already has orgs
  gives "Organization already exists" + "Failed to initialize key stores" + "Organizations are not
  configured" — i.e. the on-disk keystores and the DB org records are a **matched pair**; you cannot
  keep one without the other. See the storage map below.
- **GraalJS runs on the Truffle interpreter in the container, deliberately — do not "fix" this with
  JVMCI flags.** `ScriptUtil` uses `org.graalvm.polyglot` (GraalJS 23.0.1 as a plain classpath
  dependency), and the runtime stage is `eclipse-temurin:26-jre-alpine`, a stock JDK with no Graal
  compiler. That combination runs JS interpreted, and `ScriptUtil.getJavaScriptEngine` already sets
  `engine.WarnInterpreterOnly=false` (`ScriptUtil.java:35`) precisely so the absence is graceful and
  silent rather than an error. Graal has never been installed on the dev node either. Getting a
  Graal-compiled JS runtime here would mean switching the runtime base image to a GraalVM JDK — a
  deliberate base-image decision, **not** something to bolt on via `setenv.sh` `JAVA_OPTS`. Context:
  on 2026-08-07 the module poms were found passing `-XX:+EnableJVMCI` plus an
  `upgrade-module-path` built from `${compiler.dir}`, a property no pom defines, so every forked test
  JVM received the literal string `${compiler.dir}` as a module path. Those flags were removed (see
  `AccountManagerObjects7/pom.xml`); the container never carried them and needs no change.
- **The surefire 2.22.2 → 3.2.5 bump does not affect this image.** The Java build stage runs
  `mvn … package -DskipTests`, so surefire never executes during `docker build`; and the wmic-based
  `PpidChecker` failure that motivated the bump is Windows-only (`wmic` was removed in Windows 11
  build 26200), so a Linux build container was never exposed to it. Noted only so the two don't get
  conflated later.

## Storage map (what MUST persist)

All mutable state lives under two mounts (already declared in `docker-compose.yml`):

| Volume | Container path | Holds |
|--------|----------------|-------|
| `am7-data` | `/data/am7` | keystores, streams, seed/datagen, sessions, file store |
| `am7-certs` | `/etc/am7/certs` | the self-signed TLS cert/key pair nginx + Tomcat share |

Inside `/data/am7` (all defaulted by `entrypoint.sh`; every path param in the templates resolves
here — nothing writes state outside it):

- `store/.jks/{orgId}` and `store/.vault/{orgId}` — **the org key material.** Note the gotcha: the
  keystores live under **`STORE_PATH`**, *not* under the `VAULT_PATH` env dir (`/data/am7/vault`,
  which stays empty despite its name). Losing these while keeping the DB orphans every org
  (see the matched-pair failure above), so they are the single most important thing to persist.
- `store/.streams` — stream/media byte storage (`IOFactory` permitted path; created on first write).
- `datagen/` — seed / data-generator files (`DATAGEN_PATH`).
- `sessions/` — Tomcat `PersistentManager` `FileStore` (`SESSION_STORE_PATH`).

Because keystores, streams, and seed data all sit under `/data/am7`, the single `am7-data` mount
covers them. If key material needs independent backup/rotation from bulk data later, it can be split
onto its own volume via sub-path mounts (`am7-keys:/data/am7/store/.jks`, `.../store/.vault`).

**Windows host note (not a container defect):** from the Windows host, `curl https://localhost:8443`
returns `HTTP 000` because curl's schannel TLS backend loops on renegotiation against nginx's
self-signed cert, and because a local dev Tomcat may also be bound to `[::1]:8443` (IPv6). The
container's `:8443` is correct — proven from an external Linux curl client hitting the container IP.
Browsers are unaffected.

## Playwright E2E against the Docker stack

Playwright tests run against Docker with:
```bash
cd src/AccountManagerUx752
PLAYWRIGHT_BASE_URL=https://127.0.0.1:9443 npx playwright test --workers=1 --project=chromium
```

**`127.0.0.1` not `localhost`:** Docker publishes IPv4-only. Chromium resolves `localhost` to `::1` first, which fails with `ERR_CONNECTION_ABORTED`. Always use `127.0.0.1` and ensure `CORS_ALLOWED_ORIGINS` includes `https://127.0.0.1:9443`.

**Port matters for `applicationPath`:** `config.js` maps port 8899 → absolute `https://localhost:8443`; any other port (including 9443) → relative `/AccountManagerService7`. With PLAYWRIGHT_BASE_URL at 9443, all REST calls become `/AccountManagerService7/...` relative to origin — correct for nginx.

**API login in tests (not form login):** Use `page.request.post('/AccountManagerService7/rest/login', ...)` to set the JSESSIONID cookie directly. This avoids Mithril login-form races. The session cookie is then available for subsequent `page.goto('/')`.

**WebSocket reconnect vs. forceLogin — MUST stub WS in Playwright tests:**
The app opens a WebSocket to `wss://127.0.0.1:9443/AccountManagerService7/wss`. nginx proxies this to Tomcat with `Upgrade`/`Connection` headers. However, nginx does not forward the session cookie on the upgrade request, so Tomcat rejects the handshake and closes the socket. After 1000ms, `pageClient.js reconnect()` fires: if `page.token` is null, it calls `forceLogin()` immediately (→ `#!/sig`); if `page.token` is non-null, it tries `loginWithPassword("${jwt}", token)` which fails (no user named `"${jwt}"`) and also calls `forceLogin()`.

The only reliable fix for E2E tests is to stub `window.WebSocket` via `page.addInitScript()` before `page.goto()`. The stub fires `onopen` (so the app thinks the socket is open) and never fires `onclose`. Example (from `loginAsSharedUser` in `e2e/pictureBookWorkflow.spec.js`):

```javascript
await page.addInitScript(() => {
    window.WebSocket = class StubWS {
        constructor(url) {
            this.url = url; this.readyState = 0;
            this.onopen = null; this.onclose = null; this.onmessage = null; this.onerror = null;
            this.bufferedAmount = 0; this.extensions = ''; this.protocol = '';
            setTimeout(() => { this.readyState = 1; if (this.onopen) this.onopen({ type: 'open', target: this }); }, 50);
        }
        send() {} close() { this.readyState = 3; }
        addEventListener() {} removeEventListener() {} dispatchEvent() { return true; }
    };
    window.WebSocket.CONNECTING = 0; window.WebSocket.OPEN = 1;
    window.WebSocket.CLOSING = 2; window.WebSocket.CLOSED = 3;
});
```

**Keeping the Docker dist current:** The Docker image bakes a `vite build` snapshot at image-build time. When frontend source changes, the running container's dist is stale. To update without rebuilding the full image:
```bash
cd src/AccountManagerUx752
npx vite build
docker cp ./dist/. am7test-am7-1:/opt/ux752/dist/
```
`vite preview` serves static files from disk — the new files are picked up immediately without restarting the container. To fully rebuild the image (e.g. after backend changes):
```bash
docker compose -p am7test -f src/docker-compose.test.yml up --build -d
```

## Known non-blocking follow-ups

- **`log4j2.xml` hardcodes `<Property name="log-path">c:/projects/logs</Property>`** (a Windows path).
  In the Linux container the `RollingFile` appenders create a junk relative `c:/projects/logs/`
  directory under Tomcat's CWD. Not breaking — the `console-log`/`SYSTEM_OUT` appender is wired into
  Root, so app logs still reach `docker logs`. Left as-is here to avoid changing shared Service7
  source; a safe fix is to make the property `${sys:log-path:-c:/projects/logs}` and set
  `-Dlog-path=/data/am7/logs` from `setenv.sh` (backwards-compatible — default unchanged off the dev box).
- Regenerate `AccountManagerUx752/package-lock.json` (out of sync; Dockerfile uses `npm install`).
- **Pinned Tomcat download will eventually 404.** `dlcdn.apache.org` only serves the current patch
  release; once `TOMCAT_VERSION` (11.0.24) is superseded, the runtime-stage `curl` breaks with no
  code change. An `|| curl … archive.apache.org …` fallback was drafted but reverted — it forces a
  fresh download to re-verify, which can't complete while a VPN/corporate TLS proxy is intercepting
  HTTPS (curl can't verify the substituted cert; do **not** add `curl -k`, that would MITM-expose the
  Tomcat binary). Re-add the archive fallback on the next legitimate `TOMCAT_VERSION` bump (which
  re-downloads anyway), and/or stage the tarball via a local download cache/mirror.
- Consider running the container as non-root (supervisord children run as root).
- Consider escaping `envsubst` inputs (`"`, `<`, `>`, `&` in an env value could corrupt the XML).
