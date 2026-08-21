# [t352] Docker Linux 富邦證券現股庫存同步

**對應 Requirements:** Requirement 90（在 Linux/amd64 隔離官方 SDK，唯讀同步 configured admin 的富邦台股現股至最新快照）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

系統目前只有 `broker.code='fubon'` 的券商 seed，沒有正式 Fubon API integration。repo 外舊 SDD 教學草稿不是現行架構，禁止搬用其 BFF/DTO。正式部署是 Docker；富邦 Python SDK 2.2.9 官方 Linux 包只有 x86_64，而開發主機／Docker daemon 可為 arm64，因此 SDK 必須隔離在一個 `platform: linux/amd64` 的 Python service，Spring services 不可直接 import proprietary SDK。

本任務只做唯讀帳務與庫存 persistence。官方入口為 `apikey_login(personal_id,key,cert_path,cert_pass)`；庫存為 `sdk.accounting.inventories(account)`，未實現損益為 `sdk.accounting.unrealized_gains_and_loses(account)`，帳務上限每秒 5 次。兩支資料有共同的 date/account/branch/stock/order type identity；庫存數量是整股 `today_qty + odd.today_qty`，成本價與對帳數量取自未實現損益。現股、融資、融券、借券與當沖不能混算。

富邦官方另有首次啟用先決條件：API key 第一次使用前，使用者必須在富邦官方流程以一般帳密＋憑證完成一次連線測試。本服務**永不接收、保存或使用下單帳戶登入密碼**；runtime 只接受已完成官方啟用的 read-only API key、personal id、PFX 與 PFX password。權限不足、API key 未啟用或官方先決流程未完成都必須明確 fail closed，不能要求使用者把一般登入密碼放進容器。

目前運行 DB 與 master changelog 已查至 v1.107：`asset_snapshot`、`stock_holding`、`broker` 已足夠，`stock_holding` 既有 broker FK 就是 provenance，且 `DataInitializer` 已 seed `fubon/富邦證券`。本任務不新增 schema、不落 raw account或fingerprint、不新增 Liquibase。禁止使用 `AssetService.updateSnapshot()`，因該路徑會 clear 全部 deposits/funds/stocks；同步必須只替換同快照的富邦台股 rows。

## 要做什麼

- [ ] **352.1 建立可重現的 Linux/amd64 SDK image。**新增 `fubon-broker-service/`，至少含 multi-stage `Dockerfile`、`.dockerignore`、exact-pinned Python requirements、`src/fubon_broker_service/` 與 `tests/`。base 為 Python 3.13 slim Debian、Compose 精確設定 `platform: linux/amd64`。builder 下載唯一 URL：
  `https://www.fbs.com.tw/TradeAPI_SDK/fubon_binary/fubon_neo-2.2.9-cp37-abi3-manylinux_2_17_x86_64.manylinux2014_x86_64.zip`；下載後先驗 zip SHA-256 `9592e7afb9eba2412ac4a5852df0a138850f6c9803136e7dae503d3606d3b432`，解壓後再驗 wheel SHA-256 `6cf623a601e4b42d4255e79e72d0cf60ac4d20247784bbeb8a478adfcb1864e3`。固定安裝該 wheel、`fugle-marketdata==2.5.0rc5` 與 web runtime exact versions，執行 `pip check`。官方 zip/wheel 安裝媒介不得 commit 或進 build context；final image 不保留下載 archive/wheel，但必須保留 hash 驗證後安裝出的 `fubon_neo` runtime package。不得把 SDK archive、憑證或任何 secret `COPY` 進 image。

- [ ] **352.2 Compose 安全邊界。**`docker-compose.yml` 新增 `fubon-broker-service`（container `asset-fubon-broker-service`），只連 `asset-net`、不宣告 `ports`，non-root user、`read_only:true`、`tmpfs:/tmp`、`cap_drop: ALL`、`security_opt:no-new-privileges:true`、`PYTHONDONTWRITEBYTECODE=1`。健康檢查只用 Python stdlib打容器內 `GET /internal/health`。`FUBON_ENABLED=false` 是預設；service liveness 在 disabled 時仍 healthy，business/BFF/external/postgres/redis 不得因它沒有 credentials 而 unhealthy或restart。其他 services 不得以「Fubon functional READY」作啟動必需條件。

- [ ] **352.3 secret 目錄、說明與空白模板。**新增可 commit 的 `secrets/fubon/README.md` 與必要 `.gitignore`/空目錄 marker；README 只列官方首次連線測試步驟、檔名、檔案權限與掛載關係，不放 example value。模板只列下列檔名，**不得建立帶假值的 secret file**：
  `sdk/personal-id`、`sdk/api-key`、`sdk/certificate.pfx`、`sdk/certificate-password`、可選且必須成對的 `sdk/account-branch-no`＋`sdk/account-number`、`shared/internal-service-token`。Python 唯讀掛整個 `${FUBON_SECRETS_DIR_HOST:-./secrets/fubon}` 至 `/run/secrets/fubon`；backend 與 external 最多只掛 `shared/`，不可看 `sdk/`。`.env.example` 只新增 `FUBON_ENABLED=false` 與 `FUBON_SECRETS_DIR_HOST=./secrets/fubon`，不得有 personal id/key/password/token literal。README 明文：使用者先在富邦官方工具以一般帳密＋憑證完成 API key 首次連線測試；本服務永不要求、讀取、保存或使用一般下單帳戶登入密碼。

- [ ] **352.4 config、redaction、exact routes與internal auth。**Python config只讀mounted files，不從request body接受secret。`GET /internal/health`是唯一免token endpoint；`GET /internal/config`、`POST /internal/portfolio/read`、`POST /internal/market-data/tw-quotes`驗exact header/constant-time token。production若用FastAPI必須`docs_url=None,redoc_url=None,openapi_url=None`；精確route set只有這四支，`/docs`、`/redoc`、`/openapi.json`及未列path 404，錯method 405。health只回status/configState/sdkVersion/platform；config只回presence/capability。disabled→NOT_CONFIGURED/功能503；enabled但secret/selector/權限問題→MISCONFIGURED/503且零SDK。Python/backend client皆lazy讀config；missing shared token不能讓startup fail，由typed state保證zero HTTP/SDK/DB write。中央redactor禁止personal id、raw account、key、PFX path/password、token、SDK raw response/exception repr進response/log/metric。

- [ ] **352.5 唯讀 SDK gateway、account selection與session lifecycle。**SDK gateway只能import login/accounting/marketdata所需symbols，不得import/暴露order APIs。單一`FubonSDK` instance採login-on-demand與single reconnect mutex。`apikey_login`後只選stock account：selector成對精確match，無selector只允許恰好一個。raw account只留memory，response只回HMAC fingerprint。乾淨2.2.9 constructor後無marketdata；login後在同mutex呼叫`sdk.init_realtime()`，成功才取得`sdk.marketdata.rest_client.stock`；失敗invalidate/503。timeouts bounded、帳務≤5/sec；auth invalid最多一次完整relogin/rerun，不能拼輪次。ASGI/process shutdown hook取得同mutex，以bounded timeout依序best-effort呼叫SDK`logout()`/`shutdown()`；idempotent保證已login session只cleanup一次、redact例外、不阻塞退出，disabled/從未login不呼叫。reconnect時舊session也先bounded cleanup，避免recreate累積連線。

- [ ] **352.6 normalized portfolio dry-read endpoint與raw身份先驗。**新增token-protected `POST /internal/portfolio/read`；request固定唯讀`{"dryRun":true}`，不存在commit mode。先capture `queryDate=LocalDate.now(Asia/Taipei)`，對同一selected account依序呼叫兩支帳務API，完成後要求仍同日且兩邊success/data為concrete list。對每個非空raw row，必須在HMAC fingerprint、normalized identity或DTO之前解析來源自己的`date/account/branch_no`，逐列驗`date==queryDate`，且`account/branch_no`分別與login選出的selected account raw值精確相等；不得用本地queryDate、selected account或fingerprint回填來源欄。通過後才建立`(sourceDate,accountFingerprint,branchNo,stockCode,orderType)`並驗兩側唯一／集合相等。stale date、wrong account/branch、missing raw identity任一發生即整批invalid/no-write。只接受Stock/Buy；數量守恆後正持倉shares/costPrice皆>0。response只回sanitized batch/fingerprint/positions/reason，不回raw identity。

- [ ] **352.7 空庫存語意。**只有兩支API確實以selected account呼叫、同輪成功且data都明確空list，或所有非空raw rows先通過上述source date/account/branch驗證、兩側identity匹配且數量皆明確為0，才回`emptyConfirmed=true,positions=[]`。一邊空一邊非空、null data、stale source date、wrong account/branch、日期rollover、success不明或schema不完整都回invalid batch；不得以本地欄位製造空帳戶證明。

- [ ] **352.7a decimal wire精度與range。**Python對SDK decimal-like值先做`Decimal(str(value))`並驗finite；cost、actual price與Fubon raw `previousClose/openPrice/highPrice/lowPrice/bids[].price/asks[].price`映射出的normalized欄皆用無`e/E`canonical decimal string，要求正值、precision≤20、scale 0..10；raw只有`open/high/low` alias時拒絕。raw quantity只接受`0..9,999,999,999` exact integer並用checked add；matched可持久化shares為`1..9,999,999,999`（`stock_holding NUMERIC(15,5)`），零只供matched empty proof。volume只接受`0..9,223,372,036,854,775,807` exact integer。Java DTO只從canonical regex string建BigDecimal，重驗相同precision/scale/range，拒絕JSON float/scientific/non-finite。fixture釘住`"0.1"`無binary artifact、`"9999999999.9999999999"`（precision20/scale10）與shares/Long上下界接受；`"10000000000.0000000000"`（precision21）、`"0.00000000001"`（scale11）、shares 10,000,000,000、negative/Long overflow拒絕。

- [ ] **352.8 production quote dry-read供同批估值。**新增token-protected `POST /internal/market-data/tw-quotes`，request為去重codes 1..100；逐code走已初始化`stock.intraday.quote`，不用snapshot。timeout/concurrency/cache/single-flight/240-min budget/429 circuit維持明確上限。每筆驗symbol/name、市場pair、trial/actual pair、16位microseconds及官方raw `previousClose/openPrice/highPrice/lowPrice`，再映射同名normalized欄並驗positive precision20/scale10與完整OHLC關係；只有錯誤raw `open/high/low`時整筆拒絕。actual Instant轉Asia/Taipei的source date必須精確等於portfolio captured queryDate。backend只有在 shared known calendar（TWSE primary → 完整 DGPA provisional → unknown，再 union operator closure）對該queryDate明確true時才可把quote用於inventory估值；13:35可接受同交易日actual trade，但前一交易日quote整批拒絕。partial可逐檔回，inventory仍要求每個position同批恰一success，缺一檔no-write且不以Redis/MIS/Yahoo補值。Task353只把此adapter接成external盤中LIVE producer，不是本任務可執行前置。

- [ ] **352.9 backend internal client與安全端點。**在`backend`建立單一`integration/fubon`package：typed config/DTO/WebClient/sync service/scheduler/fixed-outcome counters/internal controller。client從mounted shared token讀header；timeout/4xx/5xx/invalid JSON回typed failure，不lograw body。新增exact internal`POST /internal/brokers/fubon/inventory-sync?dryRun=true|false`，token filter保護、預設true；response僅`{outcome,dryRun,batchId,positionCount,replaceCount,snapshotId?,reason,counters?}`。不加BFF/frontend/Nginx/gateway/host route。

- [ ] **352.10 tenant、calendar與排程。**owner固定configured ACTIVE admin。scheduler cron/zone/in-flight不變，先呼叫`MarketDataService.isTwTradingDayKnown(today)`，只有`Optional.of(true)`才call adapter。manual `dryRun=false`同一owner/snapshot/calendar/today gate；dryRun=true可做帳務連線/對帳且永不寫，但calendar非明確true時不得呼叫quote或回可提交估值。任何使用quote的輪次都須驗actual source date==captured queryDate。Schedule catalog維持精確新增後56=22+34。

- [ ] **352.11 共用pessimistic lock後局部transaction replace。**transaction外先完成portfolio、quotes、owner、today snapshot、active broker與names驗證。零schema新增共用snapshot mutation lock service與repository真正`PESSIMISTIC_WRITE`的`findByIdForUpdate`／owner-latest query；Fubon commit、`AssetService.updateSnapshot`及任何直接修改同snapshot children/aggregate的既有transaction，都須在transaction第一個DB動作依snapshot id同一順序取row lock後才讀／clear／rebuild／aggregate，普通`findById`不算。Fubon透過owner-latest lock重新取得configured admin最新今日snapshot，只replace `broker.code='fubon' AND market='台股'` rows。完整update在lock內保留當下server-owned Fubon scope並排除stale request/persistence-context的舊Fubon rows，不得復活；非Fubon仍按既有完整update語意。明確empty才可刪Fubon scope。create新snapshot無row可鎖但同日期unique/gate不變。任一insert/upsert/aggregate失敗整批rollback。

- [ ] **352.12 欄位映射、afterCommit backfill與aggregate。**shares依對帳值；先以未捨入BigDecimal算`costPrice.multiply(shares)`與`actualTradePrice.multiply(shares)`，最後各`setScale(2,HALF_UP)`，再要求result precision≤20才寫`NUMERIC(20,2)`；operand合法但乘積overflow整批rollback，禁止先round operand。`originalCurrencyValue/transactionType/transactionDate/transactionExchangeRate`精確為null並由mapping test逐欄斷言。dividend沿用／重算、displayOrder與唯一aggregate calculator維持既定規則。quote name仍只走唯一`StockMasterService.upsert`；必須把其既有`scheduleBackfill` dispatch改為：active transaction時註冊transaction synchronization並在afterCommit才submit既有executor，rollback零submit、成功commit每個新code恰一次；無active transaction才可立即submit。不得新增繞過service的repository路徑。

- [ ] **352.13 零schema、零新observability dependency。**不得新增DB/Liquibase，也不新增Actuator/Micrometer依賴。broker FK為provenance、fingerprint不持久化。用固定enum outcomes `DISABLED/MISCONFIGURED/CALENDAR_UNKNOWN/ACCOUNTING_FAILED/RECONCILE_FAILED/QUOTE_FAILED/NO_OWNER/NO_TODAY_SNAPSHOT/BROKER_MISSING/DRY_RUN/SUCCESS/EMPTY_CLEARED/ROLLED_BACK`；Java以`EnumMap<Outcome,LongAdder>`、Python以固定key counter作process-local累計，每輪structured summary/manual response可讀。不得動態以account/code建key；log最多batch/fingerprint/count/snapshot/reason。

- [ ] **352.14 自動測試矩陣。**Python fake SDK除既有auth/session/route/trial/cache cases，quote fixture使用官方raw `previousClose/openPrice/highPrice/lowPrice`並驗只有`open/high/low` alias時拒絕；逐側釘住raw stale date、wrong account、wrong branch在fingerprint/normalize前拒絕。decimal含precision20/scale10、precision21/scale11、shares 1/9,999,999,999/overflow、volume 0/Long.MAX/overflow與`0.1`。backend calendar/queryDate quote gate須分別驗TWSE base與完整DGPA provisional base皆可形成known true並依交易日結果授權，雙來源皆無仍unknown且零quote／零寫入；另涵蓋partial replace/empty/dividend/aggregate，mapping精確斷言`transactionExchangeRate==null`；金額 fixture 必須精確斷言`12.345×3→37.04`、`999999999999999999.99×1→999999999999999999.99`可寫，以及兩個各自合法的operand `1000000000×9999999999→9999999999000000000.00`在scale2後precision21而整批rollback。另用真PostgreSQL、兩個獨立transaction與latch（不得mock repository/H2）分別讓Fubon sync先鎖、完整update先鎖；兩序列皆斷言非Fubon rows不遺失、stale舊Fubon不復活、totals等於final children。StockMaster測試用可控transaction證明rollback backfill call=0、commit後=1且發生在commit後、無transaction立即=1。其餘redaction、session cleanup、missing token healthy與schedule catalog不回歸。

## 驗證

```bash
set -euo pipefail

# 規格與 deterministic tests
bash scripts/spec-check.sh
docker buildx build --platform linux/amd64 --target test \
  -f fubon-broker-service/Dockerfile fubon-broker-service \
  --load -t asset-fubon-broker-service:test
docker run --rm --platform linux/amd64 asset-fubon-broker-service:test pytest -q
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 正式 image 必須從網路下載媒介並驗兩層 hash；不從 repo COPY wheel
if git ls-files fubon-broker-service secrets | rg '\.(whl|zip|pfx)$'; then
  echo 'SDK install media and certificates must not be tracked' >&2; exit 1
fi
docker compose -p asset-management build --no-cache \
  fubon-broker-service external-materials-service business-services bff

# 無 secret / disabled 是正式支援模式，不得拖垮 stack
FUBON_ENABLED=false docker compose -p asset-management up -d --no-deps --force-recreate \
  fubon-broker-service external-materials-service business-services bff
docker compose -p asset-management restart bff
docker compose -p asset-management ps

# 在任何mount inspect前，先證明本輪四個Compose service都存在、healthy且image/config provenance正確
assert_compose_provenance() {
  service="$1"; container="$2"
  cid=$(docker compose -p asset-management ps -q "$service")
  test -n "$cid"
  test "$cid" = "$(docker inspect "$container" --format '{{.Id}}')"
  test "$(docker inspect "$container" --format '{{.State.Health.Status}}')" = healthy
  test "$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.service"}}')" = "$service"
  test -n "$(docker inspect "$container" --format '{{index .Config.Labels "com.docker.compose.config-hash"}}')"
  expected_ref=$(docker compose -p asset-management images -q "$service")
  test -n "$expected_ref"
  test "$(docker inspect "$container" --format '{{.Image}}')" = \
    "$(docker image inspect "$expected_ref" --format '{{.Id}}')"
}
assert_compose_provenance fubon-broker-service asset-fubon-broker-service
assert_compose_provenance external-materials-service asset-external-materials-service
assert_compose_provenance business-services asset-business-services
assert_compose_provenance bff asset-bff

fubon_image=$(docker inspect asset-fubon-broker-service --format '{{.Image}}')
test "$(docker image inspect "$fubon_image" --format '{{.Architecture}}')" = amd64
test "$(docker exec asset-fubon-broker-service uname -m)" = x86_64
docker exec asset-fubon-broker-service python -c \
  'from fubon_neo.sdk import FubonSDK; print("FUBON_SDK_IMPORT_OK")'
test -z "$(docker port asset-fubon-broker-service)"
docker exec asset-fubon-broker-service python -c \
  'import json,urllib.request; d=json.load(urllib.request.urlopen("http://127.0.0.1:8080/internal/health")); assert d["status"]=="UP" and d["configState"]=="NOT_CONFIGURED"'

# 只查container mount Destination，不render/persist整份Compose（其中另有DB/mail等既有secrets）
python_mounts=$(docker inspect asset-fubon-broker-service \
  --format '{{range .Mounts}}{{println .Destination}}{{end}}')
business_mounts=$(docker inspect asset-business-services \
  --format '{{range .Mounts}}{{println .Destination}}{{end}}')
external_mounts=$(docker inspect asset-external-materials-service \
  --format '{{range .Mounts}}{{println .Destination}}{{end}}')
printf '%s\n' "$python_mounts" | rg -x '/run/secrets/fubon'
printf '%s\n' "$business_mounts" | rg -x '/run/secrets/fubon/shared'
printf '%s\n' "$external_mounts" | rg -x '/run/secrets/fubon/shared'
if printf '%s\n%s\n' "$business_mounts" "$external_mounts" | \
    rg -x '/run/secrets/fubon(/sdk)?'; then
  echo 'Java services must not mount the SDK credential directory' >&2; exit 1
fi

# fake-adapter Spring tests已精確驗catalog 56/22/34；runtime image本身也要含新job，不能只驗source
docker cp asset-bff:/app/app.jar /tmp/asset-bff.jar
unzip -p /tmp/asset-bff.jar \
  BOOT-INF/classes/com/steven/assets/bff/schedulelist/SchedulePublicBffController.class \
  > /tmp/fubon-schedule.class
strings /tmp/fubon-schedule.class | rg '富邦台股現股庫存同步|0 5,35 9-13 \* \* MON-FRI|Asia/Taipei'

# 不得把測試sentinel或raw credential欄位記入log；此處只查固定字串，不讀真正secret值
if docker logs asset-fubon-broker-service 2>&1 | \
    rg 'TEST_(PERSONAL_ID|API_KEY|CERT_PASSWORD|ACCOUNT)_SENTINEL|certificate-password'; then
  exit 1
fi
```

有安裝真實 secrets 時的受控驗收（不得把值印到 shell history/log）：

- [ ] 使用者已先在富邦官方流程完成「一般帳密＋憑證」首次連線測試並啟用 read-only API key；完成報告只記「先決條件已完成」，不記帳密/key/account。服務端沒有任何一般登入密碼檔名或設定。
- [ ] `FUBON_ENABLED=true` recreate後 health為`READY`；用容器內腳本讀`shared/internal-service-token`呼叫portfolio dry-read，確認雙API成功、positions/emptyConfirmed語意正確且response/log無raw identity。
- [ ] 台股交易日且有今日快照時，先跑manual dry-run並保存sanitized summary，再跑commit；DB前後diff只允許最新snapshot的Fubon+台股rows與由同一calculator重算的snapshot aggregates改變。其他broker/market/funds/deposits逐列相等。
- [ ] 若當下無credentials、非交易日、無今日snapshot或富邦權限不足，不繞過gate；完成報告明列哪個live stage未驗，不可用fixture結果宣稱已取得真實庫存。

## 完成報告

**完成日期：** 待實作後填寫
**變更檔案：** 待實作後逐檔列出
**測試結果：** 待填寫（Python/backend/BFF，tests/failures/errors/skipped）
**Docker 證據：** 待填寫（image digest/amd64/x86_64/import/health/no host port）
**真實富邦 dry-read／局部同步：** 待填寫；若缺secret、非交易日或無今日snapshot，明列限制，不得寫「已驗證」
**與規格偏差：** 待填寫
