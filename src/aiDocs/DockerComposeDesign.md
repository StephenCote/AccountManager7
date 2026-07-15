# Single-container Docker Compose (Service7 + Ux752) — Design & Status

**Status: verified working end-to-end.** Last updated 2026-07-15.

The image builds, the container boots all three processes (Tomcat + nginx + vite preview),
Tomcat deploys the webapp, the schema is created on a fresh Postgres, and nginx `:8443`
reverse-proxies both the REST API and the Ux752 UI. Verified against a disposable
`pgvector/pgvector:pg17` container (see "Verification 2026-07-15" below).

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
