# Docker dev setup — build & start a FRESH AM7 install

**Purpose:** the step-by-step procedure to get a brand-new AccountManager7 deployment (Service7 +
Ux752 + Postgres/pgvector) running in Docker from a clean checkout, through first-run setup, to a
verified login.

This is the **operational runbook**. The design rationale, the history of what broke and why, and the
per-decision notes live in [`DockerComposeDesign.md`](DockerComposeDesign.md) — read that when you
need to know *why*, read this when you need to *do it*. Where the two disagree, this file is the one
written directly off the current source (see "Provenance" at the end).

Facts below were read out of `docker-compose.test.yml`, `docker-compose.yml`, `Dockerfile`,
`docker/entrypoint.sh`, `docker/nginx.conf`, `docker/supervisord.conf`, `docker/web.xml.template`,
`.dockerignore`, `src/.gitignore`, `Setup.java`, `ServerConfigUtil.java`, `core/config.js`,
`core/setupSupport.js`, `views/setup.js` and `e2e/helpers/api.js`.

---

## 0. Shell conventions, and which stack you want

### Shell: PowerShell, backslashes

**Every command block in this file is PowerShell** (the repo's primary shell on this Windows host),
using Windows backslash paths. Blocks that require bash are labelled `bash` and say so in prose. On
this Windows host you need bash for exactly two of them, both under §4b and both meant to run in
**WSL**, not Git Bash. (The one other `bash` block, the `./assemble-seed.sh` form under §2e, is only
for Linux/macOS Docker hosts — a Windows user runs the native `assemble-seed.bat` instead and can
ignore it.)

This matters more than it looks. Bash idioms silently break or fail confusingly here:

| Bash idiom | In PowerShell / cmd | Use instead |
|---|---|---|
| `cat "C:/path/to/file"` | `cat` is an alias for `Get-Content` in PowerShell (forward slashes OK there) but **does not exist in cmd**, where `type "C:/..."` also fails — cmd reads `/` as a switch prefix | `Get-Content C:\path\to\file` |
| `VAR=value docker compose ...` | not valid syntax — PowerShell has no inline env-var prefix | `$env:VAR = "value"` on its own line first |
| `TOKEN=$(cat file)` | `$(...)` is command substitution in bash; PowerShell wants different syntax | `$Token = (Get-Content file).Trim()` |
| `cmd1 && cmd2` | **parser error** in Windows PowerShell 5.1 | `cmd1; if ($?) { cmd2 }` |
| `mv`, `rm -rf` | `mv` is an alias for `Move-Item`; `rm -rf` is not a valid flag set | `Move-Item`, `Remove-Item -Recurse -Force` |

Mixing these is what cost time on 2026-09-07: the token-read command in an earlier draft of §3 was
`cat "C:/Projects/.../.setup.token"`, which reads as a quoted POSIX path and does not work in cmd —
making a correctly-minted token look absent.

### Working directory

Everything runs from **`src\`** (where both compose files and the `Dockerfile` live) — *not* the git
root. Getting these backwards is the single most common mistake in this repo. `Set-Location` persists
in an interactive shell, so the blocks below assume you are already in `src\`; the first one sets it.

| | **Path A — self-contained** | **Path B — canonical** |
|---|---|---|
| File | `docker-compose.test.yml` | `docker-compose.yml` |
| Postgres/pgvector | **bundled** (`am7-pg` service) | **external** — you run it yourself |
| App URL | `https://localhost:9443` | `https://localhost:8443` |
| Postgres on host | `15433` (inspect only) | whatever you publish |
| Persistence | host bind mounts under `./docker-data` | named volumes `am7-data`, `am7-certs` |
| Project name | `-p am7test` (required) | default |
| Use it for | **a fresh install, dev, Playwright E2E** | deployments with a real/shared Postgres |

**For a fresh new install, use Path A.** It is the only one that brings up its own database, it cannot
collide with a local Tomcat on `8443` or a dev Postgres on `15432`, and its whole state is one host
directory you can delete. Path B is documented in §6.

### Docker CAN reach the LAN on this host (the old "hard constraint" was wrong)

This section used to state as a hard constraint that Docker Desktop bridges to `172.20.x.x` and
cannot route to `192.168.1.x`, so SD/LLM work had to run on the host Tomcat. **That is false and has
been re-measured twice:**

- 2026-09-13, from inside `am7test-am7-1`: Ollama `http://192.168.1.42:11434/api/tags` → **200**,
  SwarmUI `http://192.168.1.39:7801/` → **302** (recorded in `.claude/rules/troubleshooting.md`).
- 2026-09-14, from a container on `am7test_am7-test-net`: `http://192.168.1.42:11434/api/tags` →
  **HTTP 200 in 34ms**; from inside the running `litellm` container specifically → **200 in 24ms**.

So: **use Docker for everything, including SD- and LLM-touching work.** Long chunked extractions and
ChapBook creation have been run end-to-end against the real LLM through Docker Tomcat. The host
Eclipse-managed Tomcat remains a valid target, not a required one.

If you *do* hit packet loss to `192.168.1.x`, treat it as a host networking problem to diagnose —
not a standing property of the setup. Check from inside the container first:

```powershell
docker exec am7test-am7-1 curl -s -o /dev/null -w '%{http_code}' http://192.168.1.42:11434/api/tags
```

(This retires the guidance conflict logged as audit item 7 in `DockerComposeDesign.md`, which is now
marked resolved there.)

---

## 1. Prerequisites

1. **Docker Desktop running.** Verified against Docker Engine 29.4.2 / Compose v5.1.3.
2. **Disk:** the build pulls `maven:3.9-eclipse-temurin-26`, `node:24-alpine`,
   `eclipse-temurin:26-jre-alpine` and `pgvector/pgvector:0.8.6-pg18-trixie`, and downloads Tomcat
   11.0.25 — budget several GB and 10–20 min for a cold first build. Note that the database is
   **built, not pulled stock**: `am7-pg` has a `build:` stanza (`docker/postgres/Dockerfile`) that
   bakes the AM7 performance tuning into a local `am7-pg:latest` image on top of that pinned
   pgvector base, so `up --build` includes a (fast) image build for it too.
3. **Free host ports:** `9443` and `15433` (Path A). Nothing else is published.
4. **Line endings.** `src/.gitattributes` pins `*.sh` and `docker/**` to LF, and the Dockerfile
   defensively strips `\r` (`Dockerfile:122-126`). You do not need to do anything — but if you ever
   see the container exit with `exec /usr/local/bin/entrypoint.sh: no such file or directory`, that
   is a CRLF shebang, not a missing file.
5. **Decide the admin password before you start.** See the warning in §4.

### Will the in-image Maven build work here?

The Docker build stage deliberately uses **online** Maven Central (a fresh stage has no `.m2`
cache), unlike the repo's usual offline `mvn -o` convention. If a corporate TLS proxy intercepts
HTTPS, that stage fails — use the `PREBUILT=1` escape hatch in §2b.

---

## 2. Path A — build and start the self-contained stack

### 2a. Normal build (Maven Central reachable)

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src
docker compose -p am7test -f docker-compose.test.yml up --build -d
```

`src\am7-docker-up.bat` wraps this (plus `--no-build` / `--prebuilt`, and the `--llmproxy` /
`--llmproxy-only` / `--app-only` options for the optional LLM-proxy sidecars — see §12.2).

To keep persistent state somewhere other than `.\docker-data` — note PowerShell needs the env var set
on its own line, there is no inline `VAR=value cmd` prefix:

```powershell
$env:AM7_DATA_DIR = "D:\am7-data"
docker compose -p am7test -f docker-compose.test.yml up --build -d
```

### 2b. Build with a pre-built WAR (`PREBUILT=1`) — when the TLS proxy blocks Maven

Build the WAR on the host first, then tell the image to reuse it. `.dockerignore` excludes
`**/target/` but un-excludes exactly this one WAR (`.dockerignore:3-4`), and
`docker/bcprov-jdk18on-1.76.jar` is already staged in the repo for this path.

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src
mvn -o -pl AccountManagerObjects7,AccountManagerISO42001 install -DskipTests
mvn -o -pl AccountManagerService7 package -DskipTests
# `up --build` cannot pass build args, so build explicitly first, then start without --build:
docker compose -p am7test -f docker-compose.test.yml build --build-arg PREBUILT=1 am7
docker compose -p am7test -f docker-compose.test.yml up -d
```

### 2c. What the image contains

One container running **three supervised processes** (`docker/supervisord.conf`):

| Process | Bind | Role |
|---|---|---|
| nginx | `:8443` (published to host `9443`) | TLS terminator + reverse proxy — the only exposed port |
| Tomcat 11 | `127.0.0.1:8444` (HTTPS, internal only) | Service7 WAR at context `/AccountManagerService7` |
| `npx vite preview` | `127.0.0.1:8899` | serves the baked Ux752 `dist` |

nginx routing (`docker/nginx.conf`):

- `/AccountManagerService7/wss` → Tomcat (WebSocket upgrade, 3600s)
- `/AccountManagerService7/` → Tomcat (`proxy_read_timeout 900s`)
- `/` → Vite preview

**So every REST call through Docker is prefixed `/AccountManagerService7/rest/...`.** A URL like
`https://localhost:9443/rest/setup/` hits Vite and 404s. (`DockerComposeDesign.md` has several curl
examples missing this prefix — audit item 4.)

TLS is double-hop by design: nginx terminates with a self-signed pair generated on first boot into
`/etc/am7/certs`, then re-encrypts to Tomcat's own self-signed connector with `proxy_ssl_verify off`.
Expect certificate warnings in the browser; accept them.

### 2d. What happens on the very first boot

In order:

1. `entrypoint.sh` renders `META-INF/context.xml` and `WEB-INF/web.xml` from the `.template` files
   via `envsubst`. **This happens on *every* boot** — anything edited into a deployed `web.xml` is
   silently discarded on restart. Change `docker/web.xml.template` instead.
2. It refuses to start if `CORS_ALLOWED_ORIGINS` contains `*` (`cors.support.credentials=true` makes
   that the classic dangerous misconfiguration).
3. It generates the self-signed TLS pair if `/etc/am7/certs` has none.
4. It mints a **one-shot setup token** (`openssl rand -hex 24`) into
   `$STORE_PATH/.setup.token` = `/data/am7/store/.setup.token`, mode 600, and logs a
   `***** FIRST-RUN SETUP REQUIRED *****` banner — **the token value itself is deliberately never
   logged.**
5. supervisord starts Tomcat/nginx/vite. Tomcat's `IOSystem.open()` creates the schema
   (~132 `a7_*` tables) on the empty database, creates the three default organizations
   `/System`, `/Development`, `/Public`, and writes the org keystores.

At this point the deployment is **running but not configured**: no user credential exists.

> **`up -d` shows you none of step 4.** Detached mode prints only the `[+] up 3/3` container summary,
> so the `FIRST-RUN SETUP REQUIRED` banner goes to the container log and never reaches your terminal —
> and the token value is deliberately never printed *anywhere*, so there is nothing to scroll back to.
> **This is not a failure to mint a token.** Confirm with `logs` below, then just read the file (§3).

Watch it come up:

```powershell
docker compose -p am7test -f docker-compose.test.yml logs -f am7

# or just confirm the banner fired, without tailing:
docker compose -p am7test -f docker-compose.test.yml logs am7 | Select-String FIRST-RUN
```

Wait for the Tomcat startup line and the `FIRST-RUN SETUP REQUIRED` banner. `am7` does not start
until `am7-pg` passes its `pg_isready` healthcheck, so a slow database start is handled for you.

### 2e. Seed the Olio corpus (before generating characters)

Olio character generation reads a reference corpus — names, surnames, occupations, traits, a WordNet
dictionary, optionally location grids — from `datagen.path` = `/data/am7/datagen`. **A fresh image
ships this empty** (the container `mkdir -p`s the dir but bundles no corpus), so the
character-creation wizard fails on a brand-new stack until it is populated. This is KI-70.

The seed script (in `src\` — **`assemble-seed.bat`** on a Windows host, **`assemble-seed.sh`** on a
Linux/macOS Docker host) populates it by mirroring **your own** staging dir into the `docker-data`
bind mount. You build the staging dir yourself: unzip `seedData.7z` somewhere and add whatever extra
corpus you want (location grids, the WordNet dict). The script does **not** touch `src\seedData` or
the `.7z`. Because `docker-data/am7` is a live bind mount, files land inside the container
immediately with no rebuild, so you can run this before `up` or any time before the first
character-generation call.

Point it at the staging dir with **`SEED_STAGING`**, set (uncommented) in `src\.env`. The seeding
script reads `SEED_STAGING` from that same `.env` file — it reads `.env` itself, since (unlike
compose) a shell does not auto-load it. So the simplest path is to set it once in `.env` and run the
script with no arguments; an inline/exported `SEED_STAGING` overrides the `.env` value.

**cmd / PowerShell — run `assemble-seed.bat`, from `src\`.** The `.bat` is a **native Windows cmd
script** — it copies with `robocopy` and has **no bash, Git Bash, or WSL dependency at all**, so no
bash prompt is involved. It accepts a `c:/projects/data`-style forward-slash staging path (it
converts to backslashes internally) as well as a normal `c:\projects\data` one.

```bat
cd C:\Projects\GitHub\AccountManager7\src

REM Simplest: SEED_STAGING=c:/projects/data set (uncommented) in src\.env, then:
assemble-seed.bat                  REM base corpus
assemble-seed.bat --with-location  REM + large location grids
assemble-seed.bat --dry-run        REM list what would copy, write nothing

REM Or override .env inline for a one-off:
set "SEED_STAGING=c:\projects\data"
assemble-seed.bat
```

**Linux / macOS Docker hosts — run `./assemble-seed.sh`, from `src/`** (the bash equivalent; not
needed on Windows, where the `.bat` above is the native path). It mirrors the same `SEED_STAGING` →
`docker-data/am7/datagen` copy with the same exclusions:

```bash
cd /path/to/AccountManager7/src
./assemble-seed.sh                 # base corpus (SEED_STAGING from src/.env)
./assemble-seed.sh --with-location # + large location grids
./assemble-seed.sh --dry-run       # list what would copy, write nothing
SEED_STAGING=/data/corpus ./assemble-seed.sh       # override .env inline
```

- `SEED_STAGING` is **required** (in `src\.env`, or inline/exported) — point it at your populated
  staging dir (example: `c:/projects/data`). Windows backslashes are normalised; a trailing slash is
  stripped. If it is unset everywhere, the script exits with a message instead of copying nothing
  silently.
- The corpus files must sit at their expected sub-paths **directly under** the staging root —
  `names/yob2022.txt`, `surnames/Names_2010Census.csv`, `occupations/noc_2021_..._elements.csv`,
  `patterns/patterns.csv`, `traits.json`, and `wn3.1.dict/dict/data.noun` (the WordNet dict is a
  separate download, **not** in `seedData.7z`). The copy preserves each file's path relative to the
  staging root, so `--dry-run` is the quick way to confirm the layout before a real run.
- The target defaults to `$AM7_DATA_DIR/am7/datagen` (`.\docker-data\am7\datagen`), matching the
  compose bind mount. If you moved persistent state with `AM7_DATA_DIR` (see §2a), export the same
  value here so the corpus lands where the container reads it.
- **Top-level `am7/` and `vault/` in the staging dir are pruned and never copied** — so it is safe to
  point `SEED_STAGING` at a dir that also holds a live database (`am7/`) or secrets (`vault/`).
- Colors are **not** seeded here: `ColorUtil` loads `olio/colors.json` from the Objects7 jar classpath.

The design rationale (why volume-copy and not a Dockerfile `COPY`, why the pruning) is in
[`DockerComposeDesign.md`](DockerComposeDesign.md) under "Seeding the Olio corpus".

---

## 3. Read the setup token

The store is bind-mounted, so the host path works directly. **Best form — print the ready-to-paste
setup URL and never handle the bare token:**

```powershell
"https://localhost:9443/#!/setup?token=" + (Get-Content C:\Projects\GitHub\AccountManager7\src\docker-data\am7\store\.setup.token).Trim()
```

Just the token, either from the host or out of the container:

```powershell
Get-Content C:\Projects\GitHub\AccountManager7\src\docker-data\am7\store\.setup.token
docker exec am7test-am7-1 cat /data/am7/store/.setup.token
```

> **Do not write this as `cat "C:/Projects/.../.setup.token"`.** That is a bash idiom with a POSIX
> path: `cat` does not exist in cmd, and cmd's `type` rejects the forward slashes as switch prefixes.
> The command fails and a correctly-minted token looks like it was never created. This actually
> happened on 2026-09-07. `Get-Content` with backslashes works in both PowerShell and (as `type`) cmd.
> The `docker exec` line is the exception — `cat` there runs *inside* the Alpine container, so the
> POSIX path and command are correct.

**If the file is not there**, exactly one of these is true — check in this order:

1. **You used `up -d` and expected to see it in the terminal.** You won't; see the note in §2d. The
   file is on disk regardless.
2. **`.setup.done` exists next to it** → setup already ran on this store, or `entrypoint.sh` *adopted*
   the store as provisioned because it found keystores in `store/.jks` with no token present
   (`entrypoint.sh:124-130`). Either way the DB latch is closed and setup is over; go to sign-in. To
   genuinely start fresh, reset (§7).
3. **`AM7_DATA_DIR` points somewhere else** → the file is under *that* directory, not `./docker-data`.
4. **The container exited during boot** → `docker compose ... ps` / `logs`; a short-token or
   `CORS_ALLOWED_ORIGINS=*` refusal exits *before* supervisord starts.

**Windows note:** `entrypoint.sh` chmods the token `600`, but a Docker Desktop bind mount does not
carry that through — the host copy shows `-rw-r--r--` and is readable by any local user. It is
git-ignored by name (`src/.gitignore:13-16`), so there is no commit risk, but treat the host file as
readable-by-anyone-on-this-box until setup retires it.

Container name is `am7test-am7-1` **because of `-p am7test`**. Under Path B's default project name it
is `src-am7-1`. `am7-pg` has a fixed `container_name`, so it is always `am7-pg`.

The token is 192 bits of entropy and is admin-equivalent until setup completes. It is git-ignored by
name wherever it lands (`src/.gitignore:13-16`). Do not paste it into a commit, an issue, or a log.

**Supplying your own token instead:** set `AM7_SETUP_TOKEN_FILE` to a Docker/K8s **secret path** (not
the value — env *values* are readable via `docker inspect`, `/proc/<pid>/environ` and
`docker compose config`). Tokens shorter than 32 characters are refused at boot.

---

## 4. Initial setup

### ⚠ Choose the admin password deliberately, and write it down

- The admin password is set on **all three** organizations at once.
- Credentials are salted hashes and **cannot be recovered**. If it is lost, the only remedy on a
  disposable stack is a full reset (§7).
- **On the `am7test` stack, use `password`.** Every Playwright helper defaults to it —
  `e2e/helpers/api.js:46`, `:204`, `SHARED_PASSWORD` `:318`, `NOROLE_PASSWORD` `:363`,
  `ADMIN_ROLE_PASSWORD` `:534`. Those helpers need an admin session to *provision* the test users, so
  a different admin password breaks the entire E2E suite, not just admin tests. This already cost a
  full reset once (2026-08-06 → 08-07).
- Minimum length is 8 characters, enforced both client- and server-side.
- If you must deviate, write the value into `src/volatile/` (git-ignored) **before** running setup.

### 4a. Browser path (recommended)

Open, with the token in the URL so the form pre-fills and the current server URLs are released for
prefill:

```
https://localhost:9443/#!/setup?token=<TOKEN>
```

Accept the self-signed cert warning. (Plain `https://localhost:9443` also works — the router probes
`GET /rest/setup/state`, sees `initialized:false` and routes itself to `/setup` — but then you must
paste the token into the **Setup Token** field by hand; it is a required field.)

The form collects, in one step:

| Section | Notes |
|---|---|
| Administrator Password + confirm | required, ≥8 chars, applied to `/System`, `/Development`, `/Public` |
| Setup Token | required; pre-filled from `?token=` |
| Initial user (name, password, organization) | optional; org is **`/Public` or `/Development` only** — `/System` is hard-rejected at two layers, because a `/System` user inherits `AccountUsers` CRU on every shared library, including the `/Library/Connections` records that hold global API keys |
| Six media/AI server URLs | optional (`sd`, `face`, `tag`, `voice.tts`, `voice.stt`, `embedding`); blank leaves the boot value alone |

Submit → toast `Setup complete` → redirected to `/sig` (sign-in). Log in as `admin` against
`/Development`.

### 4b. curl path (scripted / headless)

**Windows caveat:** `curl` on Windows uses the schannel TLS backend and loops on renegotiation
against nginx's self-signed cert, returning `HTTP 000`. So the `curl` form below is **for WSL**, not
Git Bash and not cmd. Use the IPv4 literal `127.0.0.1` — Docker publishes IPv4-only and `localhost`
resolves to `::1` first.

**bash — run in WSL:**

```bash
TOKEN=$(cat /mnt/c/Projects/GitHub/AccountManager7/src/docker-data/am7/store/.setup.token)

curl -k -X POST https://127.0.0.1:9443/AccountManagerService7/rest/setup/ \
  -H 'Content-Type: application/json' \
  -H "X-AM7-Setup-Token: $TOKEN" \
  -d '{"credential":"cGFzc3dvcmQ="}'        # base64 of `password`
```

**PowerShell equivalent**, if you would rather not leave Windows. `Invoke-RestMethod` has no
`-SkipCertificateCheck` in Windows PowerShell 5.1, so the self-signed cert has to be waved through on
the `ServicePointManager` — which is process-wide, so use a throwaway shell for it:

```powershell
Add-Type "using System.Net;using System.Security.Cryptography.X509Certificates;public class TrustAll:ICertificatePolicy{public bool CheckValidationResult(ServicePoint s,X509Certificate c,WebRequest r,int p){return true;}}"
[System.Net.ServicePointManager]::CertificatePolicy = New-Object TrustAll

$Token = (Get-Content C:\Projects\GitHub\AccountManager7\src\docker-data\am7\store\.setup.token).Trim()
$Body  = '{"credential":"cGFzc3dvcmQ="}'      # base64 of `password`
Invoke-RestMethod -Method Post -Uri https://127.0.0.1:9443/AccountManagerService7/rest/setup/ `
  -ContentType 'application/json' -Headers @{ 'X-AM7-Setup-Token' = $Token } -Body $Body
```

Full wire contract (`Setup.java:145-159`) — everything except `credential` is optional:

```json
{
  "credential": "<base64 admin password>",
  "initialUser": { "name": "...", "credential": "<base64>", "organization": "/Public" },
  "servers": { "sd": "...", "face": "...", "tag": "...",
               "voice.tts": "...", "voice.stt": "...", "embedding": "..." }
}
```

- `200` → `{"ok":true,"initialUser":"name"|null,"warnings":[...]}`
- `400` → malformed body / bad base64 / password too short / bad user name / bad organization
- `404` → **`{"ok":false}` and nothing else.** Already-latched, wrong token and missing token all
  return a byte-identical 404 *by design*, so the endpoint is not an oracle. They are logged
  distinguishably server-side — `docker logs` is where you find out which one it was.

The legacy record-shaped body
(`{"schema":"auth.credential","credential":"<base64>","type":"hashed_password"}`) still bootstraps;
only `credential` is read from it.

Base64 here is **transport encoding, not encryption** — same convention as `/rest/login`. TLS is the
only protection.

### 4c. Why setup is gated this way

`POST /rest/setup/` carries no `@RolesAllowed` and cannot: it exists to create the very first
credential, so at the moment it must work there is no user and no role to authorize against. It is
gated by two independent mechanisms instead (`Setup.java:39-64`):

1. **A DB-resident latch** (`SetupUtil.isSetupComplete()`) — the real security boundary. It is
   marker-first, so an already-configured deployment stays closed even in the "orphan state" where
   `/data/am7` was lost but the database is intact.
2. **The one-shot filesystem token**, constant-time compared (both sides SHA-256'd, so length does
   not leak).

There is deliberately **no hard lockout**: a valid token is always accepted immediately, whatever the
failure count. Repeated bad tokens only escalate a log-suppression window, and rotating the token
file resets even that. A lockout would be a remote, unauthenticated denial-of-provisioning.

### 4d. What setup actually did

On success: real `HASHED_PASSWORD` admin credentials in all three orgs; the optional initial user
(holding only `AccountUsers` + `Requesters`, unable to authenticate to `/System`); any supplied
`system.connection` records; org vault + ISO 42001 role provisioning via the same method the boot
listener uses, so no restart is needed; `.setup.token` **deleted**; `.setup.done` written.

That sentinel is what stops `entrypoint.sh` minting and advertising a token on later boots. Verified
2026-08-05: after a container restart the latch held closed (`/state` → `initialized:true`,
`POST` → identical 404s) with zero `FIRST-RUN` log lines.

---

## 5. Verify the install

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src

# 1. all three processes up, container not restart-looping
docker compose -p am7test -f docker-compose.test.yml ps

# 2. setup is latched closed -- expect {"initialized":true}
#    (needs the TrustAll policy from 4b; or just open the URL in a browser)
Invoke-RestMethod https://127.0.0.1:9443/AccountManagerService7/rest/setup/state

# 3. admin login returns `true` (NOT `false`)
Invoke-RestMethod -Method Post -Uri https://127.0.0.1:9443/AccountManagerService7/rest/login `
  -ContentType 'application/json' `
  -Body '{"schema":"auth.credential","organizationPath":"/Development","name":"admin","credential":"cGFzc3dvcmQ=","type":"hashed_password"}'

# 4. schema really got created -- expect ~132
docker exec am7-pg psql -U am7user -d am72db -c "select count(*) from information_schema.tables where table_name like 'a7\_%';"

# 5. UI loads
#    browser: https://localhost:9443  -> sign-in page
```

Steps 2 and 3 are the only ones needing the self-signed-cert workaround. If you would rather skip it,
step 2 is just a URL you can open in the browser, and step 3 is proven by actually signing in at
step 5 — which is the stronger test anyway.

A `false` from step 3 with a `200` means the credential is not what you think it is — re-check the
base64. A `404` from step 3 means you dropped the `/AccountManagerService7` prefix.

---

## 6. Path B — canonical stack (external Postgres, port 8443)

`docker-compose.yml` defines **no** database. Run pgvector yourself, then point the app at it via
`host.docker.internal` (the compose file adds an `extra_hosts` mapping so this resolves on Linux
hosts too).

```powershell
docker run -d --name am7-pg-ext -p 15433:5432 `
  -e POSTGRES_DB=am72db -e POSTGRES_USER=am7user -e POSTGRES_PASSWORD=password `
  pgvector/pgvector:0.8.6-pg18-trixie

Set-Location C:\Projects\GitHub\AccountManager7\src
$env:DB_HOST = "host.docker.internal"
$env:DB_PORT = "15433"
docker compose up --build -d
```

(Backtick is PowerShell's line-continuation character, not `\`.)

Then §3–§5 with `8443` in place of `9443` and container name `src-am7-1`.

Differences from Path A that will bite:

- **This Postgres is stock — it has none of the AM7 tuning.** Path A *builds* `am7-pg` from
  `docker/postgres/Dockerfile` with the tuning baked in; the `docker run` above is the plain pgvector
  base image on server defaults (notably `max_connections=100`, below the JDBC pool's
  `maxActive="150"`). Add `-c` flags if you need the tuning — `setup/dockerNotes.txt` §1 carries the
  current flag list and the `--shm-size` rationale. Keep the tag pinned: an untagged
  `pgvector/pgvector` floats across PG majors, and a data directory initialized by one major will not
  start under another. Also note the pg18 base's own `PGDATA` is `/var/lib/postgresql/18/docker`, not
  the pg17-era `/var/lib/postgresql/data` — irrelevant for the anonymous volume above, but it decides
  where the data lands the moment you bind-mount it.
- **State lives in named volumes** (`am7-data`, `am7-certs`), not a host directory. `docker compose
  down -v` destroys them. Reset is `docker volume rm`, not `rm -rf`.
- **`8443` collides with a local Eclipse Tomcat.** Stop one or the other.
- **`SD_SERVER` default is wrong in this file.** `docker-compose.yml:21` says
  `http://192.168.1.42:7801`, but `.42` is the Ollama LLM host; the SD/Swarm host is
  `192.168.1.39:7801` (correct in `entrypoint.sh:20` and `docker-compose.test.yml:71`). Logged as
  audit item 1 in `DockerComposeDesign.md`; **not fixed here** — this file documents, it doesn't
  change config. Override `SD_SERVER` explicitly if you use Path B for SD work — the container can
  reach the SD host (§0), so a wrong value is the only thing in the way.
- The comment at `docker-compose.yml:12-13` suggests Postgres on `15432`; the command above uses
  `15433` to stay clear of a dev Postgres.

---

## 7. Day-2 operations

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src

# A PowerShell function, not a bash string variable -- `$C logs -f am7` does NOT work in PowerShell
# (a string variable in command position is not re-parsed as a command).
function dc { docker compose -p am7test -f docker-compose.test.yml @args }

dc logs -f am7            # app logs (log4j console appender -> docker logs)
dc ps                     # process/health status
dc restart am7            # bounce the app (re-renders web.xml from the template!)
dc down                   # stop; data KEPT on the host in .\docker-data
dc up -d                  # start again

docker exec -it am7test-am7-1 bash
psql -h localhost -p 15433 -U am7user -d am72db      # password: password
```

### Full reset (fresh install again)

Disposable stack only. **This is not a schema `-Dreset`** — that is forbidden project-wide. This
destroys a throwaway container plus its bind-mounted directory.

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src
docker compose -p am7test -f docker-compose.test.yml down

# keep the old state rather than deleting it; docker-data* is git-ignored
Move-Item docker-data ("docker-data.reset-" + (Get-Date -Format yyyyMMdd))

docker compose -p am7test -f docker-compose.test.yml up -d
# then §3 and §4 again
```

To discard instead of keeping: `Remove-Item docker-data -Recurse -Force` (not `rm -rf`).

Omit `--build` on the restart if the source tree is mid-change and you want the current image.

**Never delete `docker-data/am7` while keeping `docker-data/pg`.** The org keystores under
`store/.jks/{orgId}` and `store/.vault/{orgId}` and the DB org records are a **matched pair**. Keeping
one without the other produces the orphan state: `Organization already exists` +
`Failed to initialize key stores` + `Organizations are not configured`. Both live under one host dir
precisely so they share a lifecycle.

### Update the UI without rebuilding the image

The image bakes a `vite build` snapshot, so frontend edits made afterward are not served. `vite
preview` serves from disk, so a copy is picked up immediately with no restart:

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src\AccountManagerUx752
npx vite build
docker cp .\dist\. am7test-am7-1:/opt/ux752/dist/
```

The `docker cp` destination stays POSIX — it is a path *inside* the container.

### Rebuild after a backend change

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src
docker compose -p am7test -f docker-compose.test.yml up --build -d
```

Editing an ISO model JSON or the facade additionally requires `mvn -o -pl AccountManagerISO42001
install -DskipTests` first if you are on the `PREBUILT=1` path.

---

## 8. Configuration: what is runtime-changeable and what is boot-pinned

**DB-backed / runtime-configurable** — the six media/AI server URLs. They are `/System`-global
`system.connection` records (deployment-global, *not* per-org) resolved through `ServerConfigUtil`.
After setup, edit them at `#!/list/system.connection` in the UI, or with Console7:

```
-serverConfig -list
-serverConfig -serverConfigName sd -serverConfigUrl http://host:7801
```

Names are exactly `sd`, `face`, `tag`, `voice.tts`, `voice.stt`, `embedding`
(`ServerConfigUtil.java:49-57`). A DB record beats the `web.xml` boot value — proven 2026-08-05, when
a regenerated `web.xml` still carried `http://192.168.1.42:8123` while the running WAR resolved the
DB-configured value.

**Boot-pinned — never DB-backed.** Anything consumed before `IOSystem.open()`: `store.path`,
`database.*`, CORS origins; plus values that must stay consistent with persisted data, notably
`embedding.type` / `embedding.dimensions` (the vector column is one fixed width and stored vectors
carry no model provenance, so repointing the embedding server invalidates existing vectors).

**Env vars → `web.xml` context-params** (all substituted by `entrypoint.sh`, defaults in
`docker-compose.test.yml` / `entrypoint.sh`):

| Env | Param | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `context.xml` datasource | Path A hardwires these to the `am7-pg` service |
| `CORS_ALLOWED_ORIGINS` | `cors.allowed.origins` | must include the exact origin the browser uses; `*` refused at boot |
| `SD_SERVER`, `FACE_SERVER`, `TAG_SERVER`, `VOICE_TTS_SERVER`, `VOICE_STT_SERVER`, `EMBEDDING_SERVER` | `*.server` | boot **defaults** only — the DB records win once set |
| `SD_DEFAULT_MODEL` | `sd.default.model` | fallback checkpoint; a wrong name returns an *empty image list*, not an error, so leave blank rather than guess |
| `HTTP_READ_TIMEOUT` | `http.read.timeout` | seconds; blank = 1200s code default |
| `TASK_SERVER`, `TASK_API_KEY` | `task.server`, `task.api.key` | `task.poll.remote=false`, feature dormant |
| `STORE_PATH`, `DATAGEN_PATH`, `VAULT_PATH`, `VAULT_CREDENTIAL_PATH`, `SESSION_STORE_PATH` | paths | all default under `/data/am7`; don't move them off the mount |

Params with **no** env placeholder (change `docker/web.xml.template` and rebuild): `picturebook.v2`,
`llm.ollama.unload`, `embedding.dimensions`, `vector.enabled`, `database.dropColumns`,
`database.repairColumnTypes`.

**LLM connections are not env-configured at all.** There is no LLM server context-param; chat/LLM
endpoints are `olio.llm.connection` records created in the app.

### Storage map — what must persist

| Path A host path | Container path | Holds |
|---|---|---|
| `docker-data/pg` | `/var/lib/postgresql/data` | the database |
| `docker-data/am7` | `/data/am7` | **org keystores** (`store/.jks/{orgId}`, `store/.vault/{orgId}`), `store/.streams`, `datagen/`, `sessions/`, setup sentinels |
| `docker-data/certs` | `/etc/am7/certs` | the self-signed TLS pair nginx + Tomcat share |

Gotcha: the keystores live under **`STORE_PATH`**, *not* under the `VAULT_PATH` dir
(`/data/am7/vault`, which stays empty despite its name).

`datagen/` is the one entry the app does **not** create for you — it is the external Olio corpus,
staged host-side by `assemble-seed.sh` before you generate characters (§2e).

---

## 9. Playwright E2E against this stack

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src\AccountManagerUx752
$env:PLAYWRIGHT_BASE_URL = "https://127.0.0.1:9443"
npx playwright test --workers=1 --project=chromium
```

`$env:PLAYWRIGHT_BASE_URL` persists for the rest of that shell session — clear it with
`Remove-Item Env:\PLAYWRIGHT_BASE_URL` before running against a local Vite dev server on `8899`, or
the `webServer` block stays suppressed and the run silently targets Docker.

Setting `PLAYWRIGHT_BASE_URL` omits the `webServer` block, so no Vite dev server starts on `8899`.
Four things must all be true or tests fail in ways that look like application bugs:

1. **`127.0.0.1`, not `localhost`.** Docker publishes IPv4-only; Chromium tries `::1` first and
   `page.goto` times out after 30s with a blank screenshot. Alternatively map it in launch args:
   `args: ['--host-resolver-rules=MAP localhost 127.0.0.1']`.
2. **CORS must list that exact origin.** Chrome 103+ sends `Origin` on same-origin POSTs and Tomcat's
   `CorsFilter` 403s anything unlisted — which empties every list view while GETs still work.
   `https://127.0.0.1:9443` is already in the Path A default. After adding an origin you must
   `up -d` again — **`restart` does not re-read the compose file.**
3. **Stub the WebSocket.** nginx doesn't forward the session cookie on the WS upgrade, so Tomcat
   closes it; 1000ms later `pageClient.js reconnect()` calls `forceLogin()` and redirects to
   `#!/sig`. Call `page.addInitScript()` **before** `page.goto()` with a `window.WebSocket` stub that
   fires `onopen` and never `onclose` — canonical pattern in `loginAsSharedUser()`.
4. **`dist` must be current** — see §7.

Port matters for `applicationPath` (`core/config.js:18-21`): port `8899` maps to the absolute
`https://localhost:8443`; **any other port, including 9443, uses the relative
`/AccountManagerService7`** — which is what nginx wants.

**Never test as `admin`.** Use `ensureSharedTestUser()` / `ensureIso42001TestUser()` from
`e2e/helpers/api.js`. Admin is only ever used to *provision* those users.

---

## 10. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `exec /usr/local/bin/entrypoint.sh: no such file or directory` | CRLF shebang — the kernel is reporting interpreter `/bin/bash\r` missing | rebuild (Dockerfile strips CR); check `core.autocrlf` |
| Container restart-loops with `SAXParseException` | malformed rendered XML — an env value containing `"`/`<`/`>`/`&` corrupts `envsubst` output | quote/clean the env value (escaping is a known unaddressed follow-up) |
| `entrypoint.sh: refusing CORS_ALLOWED_ORIGINS containing '*'` | wildcard origin + `cors.support.credentials=true` | list explicit origins |
| Every REST request returns 500 | `NoClassDefFoundError: BouncyCastleProvider` during `IOSystem.open` — it's an `Error`, so it slips past `catch(Exception)` and only appears in Tomcat's JULI `localhost.<date>.log` | the Dockerfile already supplies `bcprov` into `WEB-INF/lib`; if you changed that, restore it |
| "No setup token" after `up --build -d` | `-d` detaches, so the `FIRST-RUN SETUP REQUIRED` banner only reaches the container log — and the token value is never printed anywhere by design | the file is there: read `docker-data/am7/store/.setup.token` (§3). Confirm with `logs am7 \| grep FIRST-RUN` |
| `POST /rest/setup/` → 404 | latched **or** bad token **or** no token — identical by design | `docker logs` distinguishes them |
| Setup page never appears; UI goes to sign-in | latch already closed, or `/rest/setup/state` unreachable | check `GET .../rest/setup/state`; a `true` answer is cached in `sessionStorage` under `am7.setupComplete` |
| `HTTP 000` from Windows `curl` | schannel TLS renegotiation loop vs the self-signed cert | use a browser or WSL |
| `Blocked request. This host is not allowed.` | Vite preview rejecting a real domain name in `Host` | already handled — nginx pins upstream `Host` to `localhost:8899` |
| `Organization already exists` + `Failed to initialize key stores` | orphan state: `/data/am7` lost, DB intact | restore the keystores or reset both together (§7) |
| Long SD render aborts around 15 min | `nginx.conf:58` caps `/AccountManagerService7/` at **900s** while `HTTP_READ_TIMEOUT` defaults to **1200s** — a FLUX.2 composite (~638s, can exceed 900s) can be cut off by nginx | known ceiling (audit item 2); raise the nginx cap if you need it. Docker does reach the SD host (§0), so this ceiling is real, not theoretical |
| SD/LLM calls silently do nothing | **Not** a LAN-routing problem — Docker reaches `192.168.1.x` (§0). Check the `*_SERVER` values actually configured, and `SD_SERVER` in `docker-compose.yml:21` which defaults to the wrong host | fix the config; verify reachability with the `docker exec ... curl` one-liner in §0 |
| Tomcat download 404s during build | `dlcdn.apache.org` only serves the current patch release; `TOMCAT_VERSION=11.0.25` (`Dockerfile:71`) will eventually be superseded | bump `TOMCAT_VERSION`. Do **not** add `curl -k` — that would MITM-expose the Tomcat binary |
| Junk `c:/projects/logs/` dir inside the container | `log4j2.xml` hardcodes a Windows `log-path` | cosmetic; app logs still reach `docker logs` via the console appender |
| `npm ci` / `EUSAGE` if you edit the Dockerfile | committed `package-lock.json` is out of sync with `package.json` | the Dockerfile uses `npm install` on purpose; regenerating the lock file is an open follow-up |

---

## 11. Provenance — what is verified how

- **Read directly from current source** (this session): every path, port, env var, container name,
  URL prefix, file location, token mechanic, setup wire contract and password rule above.
- **Verified by actual runs** recorded in `DockerComposeDesign.md`: Path B end-to-end 2026-07-15
  (132 tables created, nginx GET/POST/JAAS through the double-TLS hop, key persistence across
  container recreation, and the orphan-state failure mode reproduced deliberately); Path A
  end-to-end 2026-08-05 (first-run setup from a genuine 3-org/0-credential state, admin login
  confirmed with a wrong-password negative control, initial user restricted out of `/System`, all six
  `system.connection` records written, latch confirmed held across a restart).
- **Not re-run for this document.** No container was built or started while writing it. The two
  shipped-config defects called out inline (`SD_SERVER` host in `docker-compose.yml`, nginx 900s vs
  the 1200s app default) are documented, **not fixed** — fixing config was outside this task.
- **§12 (`llmproxy` profile) is different:** that profile was **actually run for the first time on
  2026-09-14**, and §12 plus the measurements in `LiteLLMLangfuseIntegrationDesign.md` §6 come from
  that run — including the LAN reachability numbers that retire the old §0 "hard constraint".
  Measured in that same run and reported in §12: with `langfuse-minio` and `langfuse-redis` stopped a
  traced call did **not** appear in Langfuse, and after restarting both the same call landed on poll
  attempt 2 (so neither is padding); and after LiteLLM was pointed at `litellmdb`, `POST /login`
  returned `303 → /ui?login=success` with a `proxy_admin` token while a wrong password 401'd. The
  AM7-side configuration values in §12.4 (`dialect`, `serverUrl` forms, timeout ordering, alias
  constraints, the `name`-in-PATCH rule) were read from current source — `ChatUtil.resolveServiceType`,
  `Chat.getServiceUrl` (`Chat.java:4509-4522`), `connectionModel.json`, `litellm/config.yaml`, `docker-compose.test.yml`,
  `core/model.js`.
- **The `am7-docker-up.bat` flag behaviour in §12.2** (`--llmproxy`, `--llmproxy-only`, `--app-only`,
  the rejected combination, the automatic `--env-file`) was implemented and **run** by the author of
  that change, not re-tested while writing this section: `--llmproxy-only` measured 8 containers up
  with `am7test-am7-1` **not** running and a proxied chat completion traced end-to-end; `--app-only`
  measured all sidecars exited with `am7` + `am7-pg` still running, exit 0.

---

## 12. Optional LLM-proxy stack — the `llmproxy` profile (LiteLLM + Langfuse)

Opt-in sidecars in the **same** `docker-compose.test.yml`, behind compose profile **`llmproxy`**:
`litellm` (an OpenAI-compatible proxy in front of the LAN Ollama / Azure) plus **Langfuse v4**
(tracing). Two things it buys you: a **single queue** in front of the one physical GPU, and a
**trace of every LLM call** with prompt, completion, latency and token counts.

**Nothing in this profile starts with the default stack**, and the `am7` service deliberately has no
`depends_on` on it — so a `chatConfig` pointing at `http://litellm:4000` **fails closed** when the
profile is down. That is intended: it never silently half-works.

This section is the **how**. The **why** — why the concurrency cap is the whole point, why the image
is digest-pinned, why the timeout ladder is ordered the way it is — lives in
[`LiteLLMLangfuseIntegrationDesign.md`](LiteLLMLangfuseIntegrationDesign.md) §6, along with the
measurements behind it.

First time through, read in order: **12.1** what you are starting → **12.2** start it → **12.3**
prove it works → **12.4** point AM7 at it → **12.5** read the metrics → **12.6** when it misbehaves.

### 12.1 What the seven containers are for — and the eighth database

`--profile llmproxy` adds **seven** containers. None replaces anything in the default stack; they all
sit alongside it, and all are named `am7test-<service>-1`.

| Service | Role | Why it is there |
|---|---|---|
| `litellm` | **The proxy itself.** Single ingress for chat completions; enforces the per-model concurrency cap; emits Langfuse traces. | This is the actual feature — the other six exist to support tracing. |
| `langfuse-web` | Langfuse UI + public API (`/api/public/*`). | Where you read traces (host port 3001). |
| `langfuse-worker` | Drains the ingestion queue into Clickhouse. | **Not optional.** Without it the API accepts events (HTTP 202) and the UI stays **permanently empty** — which looks exactly like "tracing is broken". |
| `langfuse-clickhouse` | Columnar store holding the traces/observations themselves. | Langfuse v3+ moved trace storage off Postgres. `langfuse-web` **refuses to start** if its Clickhouse migrations fail. |
| `langfuse-db` (Postgres) | Langfuse **metadata only** — orgs, projects, API keys, users. | Not where traces live. Separate from AM7's `am7-pg`. |
| `langfuse-redis` | Queue between the ingestion API and the worker. | Measured required — see below. |
| `langfuse-minio` | S3-compatible blob store for raw ingestion events and media. | Measured required — see below. Publishes no host port. |

**All seven are OFF by default.** They start only when you ask for the profile — `--profile llmproxy`
(12.2) or `am7-docker-up.bat --llmproxy`. Nothing in the default stack pulls them in. If you have
been running with them and want the resources back, **`am7-docker-up.bat --app-only`** stops them
while leaving the app and `am7-pg` up; because it uses `stop` rather than `down`, the containers and
their data stay in place and a later `--llmproxy` restarts them in seconds.

**Six of these are simply what Langfuse v4 *is*.** v4 is a six-service deployment; that weight is the
direct cost of running the current supported major ("latest stable", chosen deliberately). There is
no supported way to run v4 with fewer parts.

**None of them is padding — measured 2026-09-14.** With `langfuse-minio` and `langfuse-redis`
**stopped**, a traced call did **not** appear in Langfuse at all. After restarting both, the same
call landed on **poll attempt 2**. The blob store and the queue are on the live ingestion path, not
decoration.

**The lighter alternative, stated honestly.** **Langfuse v2 was two containers** (web + Postgres) and
is sufficient for the Tier-A tracing this stack actually uses; it would also let LiteLLM drop the
`LANGFUSE_MIGRATION_V4_WRITE_MODE=dual` workaround (12.6). The trade is running a superseded major.
This is a **standing option, not a recommendation** — raise it if the seven-container footprint
becomes a problem on this workstation.

**The eighth thing is a database, not a container.** `litellm` also uses **`litellmdb`**, created
inside the **existing `am7-pg` container** — no new container, and deliberately its own database
rather than a schema inside `am72db`, so LiteLLM's Prisma migrations can never touch the AM7 schema.
It backs the **LiteLLM admin UI, virtual keys and spend tracking** only. Without `DATABASE_URL` the
proxy path (completions, model list, the queue) still works perfectly, but **every admin-UI request
500s with `Not connected to DB!`** — that was the state until 2026-09-14 and is the answer to "I
can't log in to the proxy web page" (12.5).

`litellmdb` is created by `docker/postgres/initdb.d/10-litellm-db.sh`, which the Postgres entrypoint
runs **only on a fresh PGDATA**. On an already-provisioned cluster create it once by hand —
non-destructive, and it touches nothing in `am72db`:

```powershell
docker exec am7-pg psql -U am7user -d postgres -c "CREATE DATABASE litellmdb"
```

### 12.2 Bring up and tear down

```powershell
Set-Location C:\Projects\GitHub\AccountManager7\src

# Same helper as §7, plus the profile and env file. A PowerShell FUNCTION, not a string variable.
function dcp { docker compose -p am7test -f docker-compose.test.yml --env-file .\volatile\llmproxy.env --profile llmproxy @args }

dcp up -d                    # start the default stack AND the seven proxy containers
dcp ps                       # health of all of them
dcp logs -f litellm          # proxy log: upstream errors, Langfuse callback errors
dcp logs -f langfuse-worker  # ingestion log: why traces are or aren't landing
```

Git Bash equivalent:

```bash
cd C:\Projects\GitHub\AccountManager7\src
docker compose -p am7test -f docker-compose.test.yml \
  --env-file ./volatile/llmproxy.env --profile llmproxy up -d
```

`src/volatile/llmproxy.env` is git-ignored (root `.gitignore` `volatile*`) and carries the runtime
secrets/values. `src/litellm/config.yaml` is committed and secret-free — every credential is an
`os.environ/<VAR>` reference.

> **`--env-file` feeds docker-compose *interpolation only*.** It puts nothing inside a container. A
> variable litellm's own process must see has to **also** appear in the service's `environment:`
> block. That is why `OLLAMA_API_BASE` is declared there; without it litellm silently fell back to
> `localhost:11434` on the first-ever run of this profile.

> **Omitting `--env-file` does not fail.** Compose starts cleanly on the committed throwaway defaults
> with no warning. See 12.5 for what those are and why both UI ports are loopback-bound.

#### Bring-up via the dev script

`src/am7-docker-up.bat` (introduced in §2a) knows about the profile, so you do not have to remember
the compose incantation. Flags are **combinable and order-independent**; `--help` / `-h` / `/?`
prints usage.

| Command | Starts | Sidecars |
|---|---|---|
| `am7-docker-up.bat` | app + `am7-pg` | **not started** (default) |
| `am7-docker-up.bat --llmproxy` | app + `am7-pg` + all seven sidecars | started |
| `am7-docker-up.bat --llmproxy-only` | **only** the proxy/observability stack — no app, no build, no Olio seed staging | started |
| `am7-docker-up.bat --app-only` | **only** app + `am7-pg` | **stopped** (reclaims the overhead) |

- **`--llmproxy-only`** names `litellm langfuse-web langfuse-worker` explicitly and lets compose pull
  in each one's `depends_on`, which drags along `am7-pg` (it hosts `litellmdb`), `langfuse-db`,
  `langfuse-clickhouse`, `langfuse-minio` and `langfuse-redis`. `langfuse-worker` has to be named
  because **nothing `depends_on` it** — the same reason it is the first thing to check in 12.6. On
  completion it prints the two UI URLs and the health endpoint. Measured: 8 containers up,
  `am7test-am7-1` **not** running, and a chat completion through the proxy returned correctly with
  the trace landing in Langfuse.
- **`--app-only`** is the reverse, and it **stops** running sidecars rather than merely not starting
  them — that is the point of the flag. It uses `stop`, not `down`, so containers and data stay put
  and a later `--llmproxy` brings them back in seconds. Measured: sidecars all exited, `am7` +
  `am7-pg` running, exit code 0.
- `--app-only` together with `--llmproxy-only` is **rejected with an error** — they are opposites.
- Combines with the build flags: `am7-docker-up.bat --no-build --llmproxy`.
- If `src/volatile/llmproxy.env` exists, the script passes `--env-file .\volatile\llmproxy.env`
  automatically whenever a profile flag is used. If it is absent you silently get the committed
  throwaway defaults (12.5).
- The startup banner reports **`llmproxy : ON/off`**, so you can see which mode you launched.

#### Tear down

```powershell
dcp down        # stop; Langfuse trace data KEPT
dcp down -v     # stop AND wipe the Langfuse named volumes (traces gone)
```

**This profile is the one place in this compose file that uses NAMED volumes** —
`langfuse-clickhouse-data`, `langfuse-clickhouse-logs`, `langfuse-minio-data`. So the §7 full-reset
recipe ("stop, then delete `.\docker-data`") does **not** clear trace data; **`down -v` is required**
for that. They are named rather than bind-mounted because Clickhouse and MinIO commit by atomic
rename, which fails on a Docker Desktop for Windows bind mount (`filesystem error: in rename:
Permission denied`) — surfacing as `langfuse-web` crash-looping with the misleading "Applying
clickhouse migrations failed / the database is unavailable". Hit on the first real run, 2026-09-14.

`langfuse-db` (Langfuse's Postgres) is an ordinary bind mount under `${AM7_DATA_DIR}/langfuse-pg` and
holds only metadata. `litellmdb` lives inside `am7-pg` and survives everything short of a full
`docker-data` reset.

### 12.3 Smoke-test it before blaming AM7

| Check | Command / URL | Expect |
|---|---|---|
| All seven up, none restart-looping | `dcp ps` | `running` / `healthy` |
| Proxy alive | `curl.exe -s -o NUL -w "%{http_code}" http://127.0.0.1:4000/health/liveliness` | `200` |
| Proxy sees its models | `curl.exe -H "Authorization: Bearer sk-am7-litellm-test" http://127.0.0.1:4000/v1/models` | `qwen3:8b`, `gpt-5.6-terra` |
| Upstream reachable from inside the proxy | `docker exec am7test-litellm-1 python -c "import urllib.request;print(urllib.request.urlopen('http://192.168.1.42:11434/api/tags',timeout=5).status)"` | `200` (measured 24ms, 2026-09-14) |
| Langfuse API answering | `curl.exe -u pk-lf-am7-test:sk-lf-am7-test http://127.0.0.1:3001/api/public/traces` | JSON with a `data` array |

In **PowerShell use `curl.exe`**, not bare `curl` — that is an alias for `Invoke-WebRequest` and does
not take `-u` / `-H` / `-w`. These endpoints are plain HTTP on loopback, so the self-signed-TLS
`HTTP 000` problem from §10 does not apply. There is **no `curl` or `wget` inside the litellm image**
— use its bundled `python`, as the container healthcheck does.

### 12.4 Point AM7 at the proxy — `system.connection` + `olio.llm.chatConfig`

AM7 reaches an LLM through a **`system.connection`** record (URL, key, timeout, **dialect**) that an
**`olio.llm.chatConfig`** references. To use the proxy you create/edit a connection and point a
chatConfig at it. Nothing else changes.

| Record | Field | Value for the proxy |
|---|---|---|
| `system.connection` | **`dialect`** | **`OPENAI_COMPAT`** |
| `system.connection` | `serverUrl` | `http://litellm:4000` (caller inside the compose network) **or** `http://127.0.0.1:4000` (caller on the Windows host) |
| `system.connection` | `apiKey` | the LiteLLM **master key** — `LITELLM_MASTER_KEY`, test default `sk-am7-litellm-test` |
| `system.connection` | `requestTimeout` | **300** |
| `olio.llm.chatConfig` | `connection` | picker → the connection above |
| `olio.llm.chatConfig` | `model` | a `model_name` **alias declared in `src/litellm/config.yaml`** — `qwen3:8b` or `gpt-5.6-terra` |
| `olio.llm.chatConfig` | `serviceType` | **leave it alone** — deprecated, fallback only |

In the UI: connections live at **`#!/list/system.connection`**; the chatConfig form has a
**Connection** picker.

**The dialect lives on the connection, not on the chatConfig.** `system.connection.dialect` is
authoritative: `ChatUtil.resolveServiceType` maps a non-`UNKNOWN` dialect by name onto the transport,
and **only** falls back to the deprecated `chatConfig.serviceType` when `dialect` is `UNKNOWN` (or
the connection is absent). Setting `serviceType = OPENAI_COMPAT` while leaving `dialect = UNKNOWN`
appears to work but is exercising the legacy path.

**`serverUrl` — do not mix the two forms.**
- **`http://litellm:4000`** when the caller is inside the compose network — the Service7 container,
  i.e. normal app use. This is the one you want for chatting in the UI.
- **`http://127.0.0.1:4000`** when the caller is on the Windows host — Objects7 JUnit
  (`TestLiteLLMRoundTrip`, `TestLiteLLMOllamaProxy`).
- The compose service name does not resolve on the host, and `127.0.0.1` inside the Service7
  container is the container itself. Either mistake looks like the proxy being down.
- **Base URL only** — no trailing slash, no `/v1`. AM7 appends `/v1/chat/completions` itself for the
  `OPENAI_COMPAT` dialect (`Chat.getServiceUrl`, `Chat.java:4509-4522`).

**`apiKey`** is the proxy's own client-facing key, distinct from any upstream Azure key. It is stored
**vault-encrypted** via `EncryptFieldProvider` like every other connection secret, and nothing logs
it (`Chat.java:4058` prints `authToken=present(<length>)` only).

**`requestTimeout: 300` — and it must stay ABOVE the LiteLLM per-model `timeout` (240s).** Two
measured facts fix that ordering:
- **A client disconnect does NOT release LiteLLM's queue slot.** When AM7's latch expires and
  `Chat.cancelOutbound()` fires, the slot stays held for the remainder of that generation — with the
  cap at 1, one timed-out AM7 caller blocks the whole queue, and **nothing in AM7 can free it**.
- **LiteLLM's own timeout DOES release it, promptly.** So LiteLLM has to be the layer that gives up
  first. If AM7 gives up first you get exactly the wedge the proxy exists to prevent.

Also: with a queue in front, `requestTimeout` now has to cover **queue wait + generation**, not just
generation — LiteLLM's `timeout` clock starts *after* the slot is acquired. With cap `N=1` and queue
depth `D`, the last caller needs roughly `D × timeout` of AM7 budget. **Raise `requestTimeout` rather
than lowering LiteLLM's `timeout`.** Full ladder: design doc §6.5.

**`chatConfig.model` must be an alias declared in `src/litellm/config.yaml`** — currently `qwen3:8b`
(proxied to the LAN Ollama) or `gpt-5.6-terra` (Azure; needs `AZURE_API_KEY` / `AZURE_API_BASE`,
which are **not** supplied by default, so that route fails until you add them). **The alias name is
load-bearing:** AM7 branches on the **model-string prefix before it looks at the dialect**, so an
alias starting with `o` or `gpt-5` is misread as an o-series reasoning model — sampling params get
stripped and the max-token field is swapped. `qwen3:8b` trips neither, and matching the upstream
model name verbatim is what lets a chatConfig be repointed by swapping only its connection.

**Editing an existing connection over raw REST/curl:** a `PATCH /rest/model` must include **`name`**
plus identity, or it **fails validation silently** and the update is discarded — `system.connection`
inherits `common.nameId`, whose `\S` rule is validated against the *patch record itself*, not the
merged result. The Ux752 form already carries `name` on every patch for exactly this reason
(`core/model.js`); hand-rolled scripts must do it themselves.

> ⚠️ **Do NOT repoint an existing `OLLAMA` chatConfig at the proxy** until **KI-72** is fixed. AM7
> gates its entire Ollama-extension block on `serviceType == OLLAMA`, so the `OPENAI_COMPAT` path
> silently drops `num_ctx`, `think`, `top_k`, `repeat_penalty`, `typical_p`, `min_p`, `repeat_last_n`
> and `num_gpu` — a config that set `think:false` gets thinking back **ON**. `num_ctx` is pinned on
> the LiteLLM model entry as a partial mitigation; the rest cannot be fixed proxy-side. **Creating a
> new `OPENAI_COMPAT` chatConfig is fine.** See [`KnownIssues.md`](KnownIssues.md) **KI-72** and
> design doc §6.6.

**Fail-closed, by design.** Because `am7` has no `depends_on` on the profile, a chatConfig pointing at
`http://litellm:4000` simply fails when the profile is down — it does not silently fall back to
talking to Ollama directly. If chats break the moment you stop the profile, that is the mechanism
working.

### 12.5 Reviewing the metrics — two separate UIs

#### LiteLLM admin UI — `http://127.0.0.1:4000/ui/`

**Login: `admin` / the master key** (`sk-am7-litellm-test` by default). Overridable via
`LITELLM_UI_USERNAME` / `LITELLM_UI_PASSWORD` (seen inside the container as `UI_USERNAME` /
`UI_PASSWORD`).

**Why this used to fail, and what changed.** The admin UI is Postgres-backed. With no `DATABASE_URL`
set, `/login` and `/get/ui_settings` returned **500 `Not connected to DB!`** while the proxy path
itself was perfectly healthy — chats worked, the UI was unusable. Fixed 2026-09-14 by pointing
LiteLLM at **`litellmdb`** on the existing `am7-pg` (12.1). Verified after the fix: `POST /login`
returns **`303` → `/ui?login=success`** with a `proxy_admin` token, and a wrong password correctly
**401s**.

What it gives you: virtual keys, per-key spend, the model list and model health, and request logs.

#### Langfuse UI — `http://127.0.0.1:3001`

**Login: `LANGFUSE_INIT_USER_EMAIL` / `LANGFUSE_INIT_USER_PASSWORD`** — defaults `am7@example.com` /
`am7-langfuse-pw-test`. The org (`AM7`), the project (**`AM7 Test`**) and the API key pair are seeded
**headlessly** through the `LANGFUSE_INIT_*` variables, so there is no manual bootstrap wizard.

What it gives you: **per-call traces** — full input and output text, latency, token counts and cost.
This is the one to open when you want to see what AM7 actually sent.

#### API access (scripting / CI)

```bash
curl -u pk-lf-am7-test:sk-lf-am7-test http://127.0.0.1:3001/api/public/traces
curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:4000/health/liveliness
curl -H "Authorization: Bearer sk-am7-litellm-test" http://127.0.0.1:4000/v1/models
```

(PowerShell: `curl.exe`, and `-o NUL` instead of `-o /dev/null`.) The ISO 42001 metrics client reads
`/api/public/observations` over this same API for `promptTokens` / `completionTokens`.

#### Both UI ports are bound to `127.0.0.1` only — deliberately

`litellm` (4000) and `langfuse-web` (3001) publish to loopback only; `langfuse-minio` publishes
nothing. Two reasons, both real: the fallback credentials above are **committed and well-known**, and
they apply silently if `--env-file` is omitted or mistyped; and **traces contain full prompt and
completion text** — on AM7 paths that means conversation content, character/narrative material, and
text extracted from user-uploaded documents. **These credentials are dev-workstation-only.** Never
run this profile on a shared or LAN-reachable host without overriding every value in design doc §6.9.
If you want metrics without the bodies, LiteLLM's `turn_off_message_logging: true` keeps token counts
and latency while suppressing content.

### 12.6 When it misbehaves

**"Traces aren't appearing in Langfuse."** Work down this list — the first three are the ones that
have actually happened:

1. **Is `langfuse-worker` running?** (`dcp ps`). Without it the API returns 202 and the UI stays
   empty forever. Not optional in v4.
2. **Is `LANGFUSE_MIGRATION_V4_WRITE_MODE=dual` set?** A stock `langfuse:4` runs in `events_only`
   mode and **silently rejects** the legacy `/api/public/ingestion` endpoint that LiteLLM's bundled
   SDK v2 writes to; `GET /api/public/traces` also refuses to answer in that mode. **LiteLLM logs
   only a generic `API errors occurred: Bad request`, and chats keep returning 200** — nothing
   visibly fails.
3. **Did the call actually go through the proxy?** A chatConfig still pointed at Ollama directly
   produces no trace by definition. Check `dcp logs litellm` for the request, and re-check the
   connection's `serverUrl` / `dialect` (12.4).
4. Are `langfuse-redis` and `langfuse-minio` up? With both stopped, a traced call did not appear at
   all (measured — 12.1).
5. Re-poll: ingestion is asynchronous. In the measured run the call landed on poll attempt 2.

**Other gotchas that cost time on the first run:**

- **Changing `LITELLM_LOCAL_MAX_PARALLEL` needs a container RECREATE, not a `restart`.**
  `docker-compose restart` does not re-read the compose file, and the value is `sed`-rendered into
  the LiteLLM config by the container entrypoint at start. Use `dcp up -d --force-recreate litellm`.
- **`ENCRYPTION_KEY` must be exactly 64 hex characters** or `langfuse-web` refuses to boot.
- **`langfuse-web` crash-looping on "Applying clickhouse migrations failed"** is usually the named
  volumes having been replaced by bind mounts (12.2), not a genuinely unavailable database.
- **Proxy healthy but every admin-UI request 500s** → `Not connected to DB!` → `litellmdb` missing
  (12.1).
- **On any litellm image bump:** re-run the streaming concurrency-cap test before trusting the queue,
  and re-check the bundled Langfuse SDK major. The image is **digest-pinned to 1.102.0** because the
  cap is **inert for streaming** on `main-stable` (1.100.1) and AM7 always streams. Design doc §6.3.
