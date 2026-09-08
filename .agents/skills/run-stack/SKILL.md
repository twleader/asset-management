---
name: run-stack
description: Launch and drive the asset-management Docker Compose stack — start it if down, rebuild + recreate the specific service whose source you changed, verify it serves. Use whenever asked to run, start, restart, redeploy, or screenshot the app, or to confirm a code change works in the actually-running stack (this project never uses a dev server — "改好" 必須跑到 image rebuild + container recreate)。
---

# Run / redeploy the asset-management stack

This project is **never** "done" until the change is built into the image and the container is recreated. There is no `npm run dev` / `mvn spring-boot:run` workflow — everything runs via Docker Compose.

## Model — run this skill on `gpt-5.6-terra` / effort `high`

Per AGENTS.md → CLAUDE.md, this skill pins its own model instead of inheriting the main
agent's. Dispatch the work with `spawn_agent`, passing `model: "gpt-5.6-terra"` and reasoning
effort `high`, then report the result back up. Do not run the steps below inline on the main
agent's model, and do not substitute a different model — if `gpt-5.6-terra` is unavailable,
stop and say so rather than silently falling back.

> Codex's skill loader has **no `model` frontmatter field** (it only reads `name`,
> `description`, `metadata`, `interface`, `dependencies`, `policy`, `agents`, `assets`), so
> this instruction is the only place the pin can live. The Claude Code copy of this skill at
> `.claude/skills/run-stack/SKILL.md` uses `model:` / `effort:` frontmatter instead, and pins
> `sonnet 5` / `high` — the two harnesses use different model identifiers by necessity (Codex
> has no Sonnet), but the effort level matches. Keep both in sync when either changes.

## Stack shape (5 services + 2 datastores)

`docker-compose.yml` at repo root:

| Service | Source dir | Host port | Internal | Notes |
|---|---|---|---|---|
| `postgres` | — | 5432 | — | data in `asset-postgres-data` volume |
| `redis` | — | (none) | 6379 | live prices, technical-indicator cache |
| `business-services` | `backend/` | (none) | 8080 | Spring Boot, internal only |
| `external-materials-service` | `external-materials-service/` | (none) | 8080 | scrapers + Redis writer |
| `bff` | `bff/` | (none) | 8080 | Spring Cloud Gateway — browser application API entry |
| `api-gateway` | `api-gateway/` | **127.0.0.1:9090** | 9090 | Twelve exact routes (11 GET + 1 POST); Tailscale mounts the same twelve |
| `frontend` | `frontend/` | **80** | 80 | Nginx serving Vite build |

Browser entry: `http://localhost/` (frontend) → authenticated application APIs at `bff:8080`.
Docker-external API entry: `http://127.0.0.1:9090` → twelve exact routes. Tailscale Serve
exposes the same twelve exact paths.

- Local/Tailscale GET routes: `/api/quotes`, `/api/quotes/one`, `/api/public/market-index`,
  `/api/assets/latest`, `/api/public/exchange-rate/usd-twd`, `/api/public/market-analysis/today`,
  `/api/public/portfolio-advice/latest`, `/api/public/trading-radar/today`,
  `/api/public/trading-radar/stock`, `/api/public/transactions`, `/api/public/trading-calendar`.
- Local/Tailscale POST route: `/api/public/crawler-data/rescan` (the only route with an external-fetch side effect).
- Tailscale mounts the same twelve exact paths only. Never mount `/`, `/api/`, or any extra handler;
  never use Funnel, self-signed certificates, another OAuth proxy, or a public host port.
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

### ⚠ Recreated `business-services` or `external-materials-service`? Keep `bff` running and verify recovery first

Do **not** routinely restart `bff` when either upstream is force-recreated. The BFF's
`DnsCacheConfig.MAX_TTL` is deliberately **30 seconds** (Task 208); retain that design and
do not change the TTL as a workaround. Keep BFF available while the new upstream becomes
healthy, then retry the affected safe GET through `api-gateway`. This lets the resolver
refresh without introducing a BFF outage of our own.

First wait for the just-recreated upstream to report `(healthy)`. Check its Docker health
status once per second for at most **120 seconds**; if it never becomes healthy, print the
last status and fail. Do not begin the 30-second BFF retry window before this succeeds:

```bash
upstream_service=business-services  # Or: external-materials-service
upstream_container="asset-$upstream_service"
upstream_deadline=$(( $(date +%s) + 120 ))
upstream_status=unknown
while [ "$(date +%s)" -lt "$upstream_deadline" ]; do
  upstream_status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}no-healthcheck{{end}}' \
      "$upstream_container" 2>&1)
  if [ "$upstream_status" = healthy ]; then
    break
  fi
  sleep 1
done
if [ "$upstream_status" != healthy ]; then
  echo "FAILED: $upstream_container did not become healthy within 120 seconds; last Docker health status: $upstream_status" >&2
  exit 1
fi
```

After `business-services` is healthy, retry its concrete safe endpoint once per second for
at most **30 seconds**. Keep the error output: a timeout must remain a failing, diagnosable
result rather than being hidden by the retry loop.

```bash
deadline=$(( $(date +%s) + 30 ))
bff_recovered=false
while [ "$(date +%s)" -lt "$deadline" ]; do
  if bff_response=$(curl -fsS --connect-timeout 1 --max-time 1 \
      'http://127.0.0.1:9090/api/public/market-index?market=TWSE&range=1m') && \
      printf '%s' "$bff_response" | \
      python3 -c 'import json,sys; json.load(sys.stdin); print("market-index JSON received")'; then
    bff_recovered=true
    break
  fi
  sleep 1
done
if [ "$bff_recovered" != true ]; then
  echo 'FAILED: BFF → business-services did not recover within 30 seconds.' >&2
  exit 1
fi
```

After `external-materials-service` is healthy, use the same 30-second, one-second retry
window for `/api/quotes`. Its only success condition is a JSON array: an empty array (`[]`)
is a valid cache state and must **not** be treated as a failure.

```bash
deadline=$(( $(date +%s) + 30 ))
bff_recovered=false
while [ "$(date +%s)" -lt "$deadline" ]; do
  if bff_response=$(curl -fsS --connect-timeout 1 --max-time 1 \
      http://127.0.0.1:9090/api/quotes) && \
      printf '%s' "$bff_response" | \
      python3 -c 'import json,sys; value=json.load(sys.stdin); assert isinstance(value, list), type(value); print("quotes JSON array received")'; then
    bff_recovered=true
    break
  fi
  sleep 1
done
if [ "$bff_recovered" != true ]; then
  echo 'FAILED: BFF → external-materials-service did not recover within 30 seconds.' >&2
  exit 1
fi
```

Only use the following fallback when the relevant loop has actually expired, the upstream is
still `(healthy)`, **and** `docker logs asset-bff --since <upstream-recreate-start-time>`
shows a `Connection refused` that names that exact upstream. A generic `502`, any other HTTP
failure, malformed payload, or an unrelated BFF log line is not enough evidence to restart
BFF. The fallback itself creates a short BFF outage while its single replica stops and starts;
it is not a normal deployment step.

```bash
docker compose -p asset-management restart bff
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

After that exceptional restart, repeat the same relevant 30-second endpoint verification
above; do not report recovery merely because BFF's actuator is `UP`.

Keep api-gateway's existing 10-second `wget` healthcheck and the Nginx log tailer enabled.
They may emit a transient `502` while a single BFF replica itself is being rebuilt or
restarted, and that interruption cannot be eliminated by this upstream-recovery change.
Do not delete or silence either signal to hide that noise: it records a real availability gap.

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
