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

### Hard constraint: Docker cannot reach the LAN on this host

Docker Desktop on Windows bridges to `172.20.x.x` and **cannot route to `192.168.1.x`** — 100% packet
loss to the SD server (`192.168.1.39`) and the Ollama LLM (`192.168.1.42`) from inside any container.
So:

- **Docker is for UI / REST / E2E / schema work.** It works fully for those.
- **Anything that actually calls SD or the LLM must run on the host Tomcat**, which can reach them.
- SD/LLM features in the Docker stack fail *silently* — no connection, no images, no visible error.
  That is the network, not a bug in the stack.

(This reconciles the guidance conflict logged as audit item 7 in `DockerComposeDesign.md`.)

---

## 1. Prerequisites

1. **Docker Desktop running.** Verified against Docker Engine 29.4.2 / Compose v5.1.3.
2. **Disk:** the build pulls `maven:3.9-eclipse-temurin-26`, `node:24-alpine`,
   `eclipse-temurin:26-jre-alpine`, `pgvector/pgvector:pg17` and downloads Tomcat 11.0.25 — budget
   several GB and 10–20 min for a cold first build.
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
  pgvector/pgvector:pg17

Set-Location C:\Projects\GitHub\AccountManager7\src
$env:DB_HOST = "host.docker.internal"
$env:DB_PORT = "15433"
docker compose up --build -d
```

(Backtick is PowerShell's line-continuation character, not `\`.)

Then §3–§5 with `8443` in place of `9443` and container name `src-am7-1`.

Differences from Path A that will bite:

- **State lives in named volumes** (`am7-data`, `am7-certs`), not a host directory. `docker compose
  down -v` destroys them. Reset is `docker volume rm`, not `rm -rf`.
- **`8443` collides with a local Eclipse Tomcat.** Stop one or the other.
- **`SD_SERVER` default is wrong in this file.** `docker-compose.yml:21` says
  `http://192.168.1.42:7801`, but `.42` is the Ollama LLM host; the SD/Swarm host is
  `192.168.1.39:7801` (correct in `entrypoint.sh:20` and `docker-compose.test.yml:71`). Logged as
  audit item 1 in `DockerComposeDesign.md`; **not fixed here** — this file documents, it doesn't
  change config. Override `SD_SERVER` explicitly if you use Path B for SD work (and re-read the LAN
  constraint in §0 first).
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
| Long SD render aborts around 15 min | `nginx.conf:58` caps `/AccountManagerService7/` at **900s** while `HTTP_READ_TIMEOUT` defaults to **1200s** — a FLUX.2 composite (~638s, can exceed 900s) can be cut off by nginx | known ceiling (audit item 2); raise the nginx cap if you need it. Note §0 first — Docker can't reach the SD host here anyway |
| SD/LLM calls silently do nothing | Docker cannot route to `192.168.1.x` | use the host Tomcat (§0) |
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
