---
name: run-stack
description: Launch and drive the asset-management Docker Compose stack — start it if down, rebuild + recreate the specific service whose source you changed, verify it serves. Use whenever asked to run, start, restart, redeploy, or screenshot the app, or to confirm a code change works in the actually-running stack (this project never uses a dev server — "改好" 必須跑到 image rebuild + container recreate)。
---

# Run / redeploy the asset-management stack

This project is **never** "done" until the change is built into the image and the container is recreated. There is no `npm run dev` / `mvn spring-boot:run` workflow — everything runs via Docker Compose.

## Stack shape (5 + 1 services)

`docker-compose.yml` at repo root:

| Service | Source dir | Host port | Internal | Notes |
|---|---|---|---|---|
| `postgres` | — | 5432 | — | data in `asset-postgres-data` volume |
| `redis` | — | (none) | 6379 | live prices, technical-indicator cache |
| `business-services` | `backend/` | (none) | 8080 | Spring Boot, internal only |
| `external-materials-service` | `external-materials-service/` | (none) | 8080 | scrapers + Redis writer |
| `bff` | `bff/` | **8080** | 8080 | Spring Cloud Gateway — sole API entry |
| `frontend` | `frontend/` | **80** | 80 | Nginx serving Vite build |

User-facing entry: `http://localhost/` (frontend) → proxies `/api/*` to `bff:8080`.

## Step 1 — Find the real Compose project name (critical)

Multiple worktrees of this repo exist under `.Codex/worktrees/`. **The running stack might have been started from a different worktree than the one you're in.** Containers are `container_name:`-pinned (e.g. `asset-frontend`), so running `docker compose up` from a different worktree with the wrong project name **silently creates a second stack and fails on the container-name clash**.

Always look up the project name from a running container first:

```bash
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
```

If the answer is `asset-management` (the convention), pass `-p asset-management` to every `docker compose` call so your worktree's compose file targets the same stack. If no container is running, the next `up` from this worktree's directory will start a new stack — that's fine; just be consistent thereafter.

```bash
# Set once per session:
DC="docker compose -p asset-management"
```

## Step 2 — Match the change to the service

Edit-target → what to rebuild. **Only rebuild what changed** (don't `docker compose up --build` the whole stack — that needlessly rebuilds 3 JVM images and takes minutes):

| You edited… | Service to rebuild | Notes |
|---|---|---|
| `frontend/src/**`, `frontend/package.json`, `frontend/vite.config.js`, `frontend/nginx.conf` | `frontend` | |
| `backend/**` (Java / pom.xml / resources / liquibase changelog) | `business-services` | Liquibase auto-runs on startup — schema migrations apply during recreate |
| `external-materials-service/**` | `external-materials-service` | |
| `bff/**` | `bff` | |
| `db/init/**` | — | Only runs on **fresh** postgres volume; needs full down + volume rm to take effect (rarely the right move — usually do a Liquibase changeset in `backend/` instead) |
| `docker-compose.yml` env / volume / network changes | — | Full `$DC down && $DC up -d --build` |

## Step 3 — Rebuild + recreate just that service

Stack already up (the normal case — confirm with `docker ps`):

```bash
$DC build <service>
$DC up -d --no-deps --force-recreate <service>
```

`--no-deps` keeps unrelated services untouched. `--force-recreate` is required because the image tag stays `asset-management-<service>:latest`; without it Compose sees "same image tag" and skips.

Stack down (no containers running):

```bash
$DC up -d --build           # builds everything that needs it, brings up in dep order
```

## Step 4 — Wait for healthy + smoke-test

`bff` and `business-services` have `depends_on: condition: service_healthy` chains. After recreate:

```bash
# Quick: is it serving?
sleep 2
docker ps --filter name=asset-<service> --format "{{.Names}}\t{{.Status}}"

# Frontend health
curl -sI http://localhost/ | head -1                 # → HTTP/1.1 200 OK

# BFF health (sole external API entry)
curl -s http://localhost:8080/actuator/health        # → {"status":"UP"}

# End-to-end: BFF → business-services → postgres
curl -s http://localhost/api/bff/dashboard/summary | python3 -c \
  "import json,sys; d=json.load(sys.stdin); print('history points:', len(d.get('history',[])))"
```

For backend / BFF rebuilds, healthcheck can take 30s+ (Spring Boot warmup). Poll `docker ps` until status shows `(healthy)` before smoke-testing.

## Step 5 — Drive it (don't just launch it)

Launching with `curl /` only proves Nginx serves. Drive the changed feature:

- **Backend / BFF change** → curl the specific endpoint and read the JSON, not just the status code:
  ```bash
  curl -s http://localhost/api/bff/<your-endpoint> | python3 -m json.tool | head -30
  ```
- **Frontend change** → opening a browser is usually required.
  - Verify the build picked up your change: chunk hash in `index.html` changed, or
    ```bash
    docker exec asset-frontend ls /usr/share/nginx/html/assets/ | grep <ChangedView>
    ```
  - **Don't grep minified JS for variable names** — Vite/Rollup mangles them. Grep for API path strings (`/api/...`) or method names exposed at module boundaries (`bffApi.dashboard.xxx`) — those survive minify.
  - For ECharts hover / Sortable drag / CSS visual changes: ask the user to verify in the browser. State explicitly what to click/hover and the expected behaviour.

## When you actually need full down/up

- New service in compose, network / volume change, env-var rename
- Stuck postgres or redis (rare; `restart: unless-stopped` recovers most issues)
- `db/init/*.sql` change you genuinely need re-run (this means `docker compose down -v` — **destroys postgres data**; only do this with explicit user OK)

```bash
$DC down && $DC up -d --build
```

## Logs / debugging

```bash
docker logs -f --tail 100 asset-<service>           # follow logs
docker logs asset-bff 2>&1 | grep -i error | tail   # error scan
docker exec -it asset-postgres psql -U $POSTGRES_USER -d $POSTGRES_DB  # DB shell
docker exec -it asset-redis redis-cli                # redis shell
```

## What "done" looks like

A change is shipped when **all** are true:

1. Code edited + spec/ updated (AGENTS.md SDD rule + pre-commit hook)
2. Affected service image rebuilt
3. Container `(healthy)` and serving expected response
4. The actual changed behaviour was driven (curl the new endpoint / open the changed page)

A green build with the old container still running is **not done**.
