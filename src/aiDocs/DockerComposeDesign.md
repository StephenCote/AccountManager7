# Single-container Docker Compose (Service7 + Ux752) — Design & Status

**Status: in progress, not yet verified working.** Last updated 2026-07-14.

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

## Current status / next steps

- Image builds successfully (`docker build -t am7:test .` — Maven package + `vite build` both
  succeed).
- First `docker run` smoke test failed at the Tomcat startup step due to the `server.xml` comment
  bug above (just fixed, not yet rebuilt/re-run).
- **Not yet done**: rebuild with the `server.xml` fix, re-run the container against the existing dev
  Postgres container (`postgres`, port 15432) or a disposable instance, confirm Tomcat actually
  deploys the webapp (JAAS/`setenv.sh` wiring is unverified), curl the REST API and the Ux752 UI
  through nginx's `:8443`, and get final `verifier` + `architect` sign-off.
- Known non-blocking follow-ups for Stephen to decide on later: regenerate
  `AccountManagerUx752/package-lock.json`; consider running the container as non-root; consider
  escaping `envsubst` inputs.
