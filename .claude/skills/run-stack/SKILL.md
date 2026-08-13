---
name: run-stack
description: Launch and drive the asset-management Docker Compose stack — start it if down, rebuild + recreate the specific service whose source you changed, verify it serves. Use whenever asked to run, start, restart, redeploy, or screenshot the app, or to confirm a code change works in the actually-running stack (this project never uses a dev server — "改好" 必須跑到 image rebuild + container recreate)。
model: sonnet
effort: high
---

# Run / redeploy the asset-management stack

This project is **never** "done" until the change is built into the image and the container is recreated. There is no `npm run dev` / `mvn spring-boot:run` workflow — everything runs via Docker Compose.

## Stack shape (5 services + 2 datastores)

`docker-compose.yml` at repo root:

| Service | Source dir | Host port | Internal | Notes |
|---|---|---|---|---|
| `postgres` | — | 5432 | — | data in `asset-postgres-data` volume |
| `redis` | — | (none) | 6379 | live prices, technical-indicator cache |
| `business-services` | `backend/` | (none) | 8080 | Spring Boot, internal only |
| `external-materials-service` | `external-materials-service/` | (none) | 8080 | scrapers + Redis writer |
| `bff` | `bff/` | (none) | 8080 | Spring Cloud Gateway — browser application API entry |
| `api-gateway` | `api-gateway/` | **127.0.0.1:9090** | 9090 | Five exact read-only GET routes; Tailscale mounts only four |
| `frontend` | `frontend/` | **80** | 80 | Nginx serving Vite build |

Browser entry: `http://localhost/` (frontend) → authenticated application APIs at `bff:8080`.
Docker-external API entry: `http://127.0.0.1:9090` → five exact GET routes. Tailscale Serve
exposes only quotes, quotes/one, market-index, and assets/latest; USD/TWD stays local-only.

- Local five: `/api/quotes`, `/api/quotes/one`, `/api/public/market-index`, `/api/assets/latest`,
  `/api/public/exchange-rate/usd-twd`.
- Tailscale four: the first four paths only. Never mount `/`, `/api/`, or USD/TWD; never use Funnel.
- Host 8080/8082 must have no listener. Check BFF health from its container and quote/BFF behavior through 9090.

## Step 1 — Find the real Compose project name (critical)

Multiple worktrees of this repo exist under `.claude/worktrees/`. **The running stack might have been started from a different worktree than the one you're in.** Containers are `container_name:`-pinned (e.g. `asset-frontend`), so running `docker compose up` from a different worktree with the wrong project name **silently creates a second stack and fails on the container-name clash**.

Always look up the project name from a running container first:

```bash
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}}'
```

If the answer is `asset-management` (the convention), pass `-p asset-management` to every `docker compose` call so your worktree's compose file targets the same stack. If no container is running, the next `up` from this worktree's directory will start a new stack — that's fine; just be consistent thereafter.

```bash
# Set once per session:
DC="docker compose -p asset-management"
```

> ⚠ **zsh 不會對 `$DC` 做 word splitting**：`$DC build frontend` 會把整串當成**一個指令名** → `command not found`。
> 在本專案一律**寫完整指令** `docker compose -p asset-management ...`，不要用 `$DC`。

## Step 1b — 從哪個目錄 build？（多 session 並行時最關鍵的決定）

全機只有一套 image tag（`asset-management-<service>:latest`）與一組 `container_name`。**誰最後 build，誰的版本就在跑**——所以「從哪個目錄 build」直接決定線上跑的是誰的程式碼。

| 情境 | 從哪裡 build |
|---|---|
| 變更還在 feature 分支、**尚未** merge 進 main | 該變更所在的 worktree |
| 變更**已經** merge 進 main | **main 的 worktree**（先追平 `origin/main`） |

**已 merge 就必須從 main build，不要再從自己的 feature worktree build。** 理由：feature worktree 的內容是「main ∪ 我的變更」，缺別人剛 merge 的工作；從那裡 build 會把別人的功能洗掉，對方發現後從他的 worktree build 回來又洗掉你的——實測會無限乒乓（2026-07-18 一小時內來回三次）。**main 是所有人已提交工作的聯集，是唯一的收斂點。**

找出 main 在哪（**位置會中途改變**，別假設在主 repo 目錄）：

```bash
MW=$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p}')
echo "$MW"
```

從 main build 前逐項確認：

```bash
git -C "$MW" branch --show-current          # 必須回 main
git -C "$MW" status --porcelain             # 必須是空的（非空＝別人正在那裡工作，停下來問）
git -C "$MW" fetch origin && git -C "$MW" merge --ff-only origin/main
cp /Users/steven/Project/asset-management/.env "$MW/.env"   # worktree 沒有 .env；env_file 相對 compose 檔解析，--env-file 救不了
```

### 被洗掉時怎麼認出來

症狀是**站沒掛、只是跑舊版**：容器全 healthy、頁面回 200，但功能不見了。**DB 不會跟著回退**，所以典型組合是「資料表還在、seed 還在，但 API 回 `No static resource api/xxx`、前端卡片消失」——看到這組合就是映像被覆蓋，不是 migration 沒跑。

```bash
# 釘住基準線，事後比對是否又被覆蓋
docker inspect asset-<svc> --format '{{.Image}}'
# 運行中的產物是否真的含你的變更（別 grep minify 後的變數名，要 grep API 路徑或中文字面值）
docker exec asset-frontend sh -c 'grep -l "<你的新文案>" /usr/share/nginx/html/assets/*.js'
docker exec asset-business-services sh -c 'unzip -l /app/app.jar | grep -i <YourNewClass>'
```

驗證期間也可能被洗掉。**下結論前再 `inspect` 一次**確認 image SHA 與釘住的基準線相同，否則你的證據是對一顆已經不存在的映像取得的。

## Step 2 — Match the change to the service

Edit-target → what to rebuild. **Only rebuild what changed** (don't `docker compose up --build` the whole stack — that needlessly rebuilds 3 JVM images and takes minutes):

| You edited… | Service to rebuild | Notes |
|---|---|---|
| `frontend/src/**`, `frontend/package.json`, `frontend/vite.config.js`, `frontend/nginx.conf` | `frontend` | |
| `backend/**` (Java / pom.xml / resources / liquibase changelog) | `business-services` | Liquibase auto-runs on startup — schema migrations apply during recreate |
| `external-materials-service/**` | `external-materials-service` | |
| `bff/**` | `bff` | |
| `api-gateway/**` | `api-gateway` | Rebuild and recreate; verify exact allowlist and deny matrix |
| `db/init/**` | — | Only runs on **fresh** postgres volume; needs full down + volume rm to take effect (rarely the right move — usually do a Liquibase changeset in `backend/` instead) |
| `docker-compose.yml` env / volume / network changes | affected services | Recreate every service whose container config changed; a new service must be built and created |

## Step 3 — Rebuild + recreate just that service

Stack already up (the normal case — confirm with `docker ps`):

```bash
$DC build <service>
$DC up -d --no-deps --force-recreate <service>
```

`--no-deps` keeps unrelated services untouched. `--force-recreate` is required because the image tag stays `asset-management-<service>:latest`; without it Compose sees "same image tag" and skips.

### ⚠ Recreated `business-services` or `external-materials-service`? Restart `bff` too

Recreating a container gives it a **new IP** on the compose network (observed: `172.19.0.4` → `172.19.0.7`). The BFF's JVM
resolver keeps the **old** IP and every `/api/**` then fails with `Connection refused: business-services/<old-ip>:8080`
→ **500 on every page**. It does *not* self-heal in 30s (measured: still broken 3 minutes later — Docker's embedded DNS
hands out TTL 600).

```bash
docker compose -p asset-management restart bff
```

Do this **whenever** you recreated an upstream service, even if the BFF itself was untouched. Then confirm no residual failures:

```bash
docker logs asset-bff --since <bff-start-time>Z 2>&1 | grep -cE "Connection refused|500 Server Error"   # → 0
```

**Diagnosing it later (don't misread it as a broken feature):** `business-services` logs are *clean*, calling the endpoint
from inside the business container works, and the errors appear **only** in `docker logs asset-bff`. `docker exec asset-bff
getent hosts business-services` resolves *correctly* — the stale copy lives inside the JVM, not the container's resolver.
Compare `docker inspect asset-business-services` current IP against the refused IP in the log to confirm.

Note: `asset-bff` has **no curl**, and every BFF route except `/actuator/health|info` needs a login session — you cannot
prove the upstream hop with an unauthenticated curl. Verify via the log check above, then have the user reload a page.

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

# BFF container health + Docker-external API gateway
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health  # → {"status":"UP"}
curl -fsS http://127.0.0.1:9090/api/quotes           # → JSON array through gateway

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

1. Code edited + spec/ updated (CLAUDE.md SDD rule + commit-msg hook)
2. Affected service image rebuilt **from the right directory**（已 merge → 從 main 的 worktree；見 Step 1b）
3. Container `(healthy)` and serving expected response
4. The actual changed behaviour was driven (curl the new endpoint / open the changed page)

A green build with the old container still running is **not done**.
