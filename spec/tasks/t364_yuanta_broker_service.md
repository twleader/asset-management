# [t364] 元大證券 SPARK API 唯讀查詢服務（Docker Linux adapter scaffold）

**對應 Requirements:** Requirement 100（新增獨立的元大證券唯讀查詢微服務，比照既有 `fubon-broker-service` 的隔離／fail-closed 模式；目前尚無官方帳號，本次只做可獨立驗收的骨架與查詢契約）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

系統目前只有 `broker.code='yuanta'` 的券商 seed（`DataInitializer.java:166` `new BrokerSeed("yuanta", "元大證券", "元大,Yuanta")`），沒有正式元大 API 整合。

元大證券公開兩套客戶端 API：

1. **元大 OneAPI（`YuantaOneAPI`／`YuantaOneCom`）**：COM 元件 ＋ .NET Framework 4.5.2，官方範例與元件僅支援 Windows（32/64 位元），無 Linux／macOS 版本。**本任務禁止使用此套 API**——本專案所有服務跑在 Docker Linux 容器，OneAPI 無 Linux 部署路徑。
2. **元大 SPARK API**：以 `pythonnet` 透過 **.NET SDK 8.0**（CoreCLR）載入 `YuantaSparkAPI.dll`；官方文件明確列出 Linux 版本登入函式簽章 `Login(PfxPath, PfxPass, Account, Pass)`（Windows 版為 `Login(Account, Pass)`），代表官方將 Linux 視為受支援平台。**本任務只採用 SPARK API。**

> **這個結論是根據元大官方文件之描述所做的推論，本專案未持有官方帳號、未實測驗證。** 本專案目前無法申請元大證券帳號（需為元大證券客戶），因此無法實際下載 `YuantaSparkAPI.dll`、無法驗證「文件記載 Linux 登入簽章」是否等於「官方正式支援生產環境的 Linux 容器部署」、也無法驗證是否僅支援特定 glibc／發行版版本。**若日後取得官方帳號後發現 Linux 容器內 `pythonnet` 無法成功呼叫 `Login` 或載入 DLL，這是需要重新評估本次架構決策（是否只能退回 Windows-based 部署或改用其他隔離方式）的前提性風險，不得視為單純 bug 逐一修補。**

與既有 `fubon-broker-service`（見 `fubon-broker-service/`、`spec/steering/structure.md §4.5`）的關鍵差異：

- **富邦官方 Linux wheel 有公開直鏈與可驗證 SHA-256**，`fubon-broker-service` 的 Dockerfile 在 build stage 直接下載並雜湊安裝。**元大 `YuantaSparkAPI.dll` 沒有公開直鏈**——只開放給已完成官方申請（簽署風險預告書＋下載測試軟體完成測試）的元大證券客戶下載。因此本服務的 image **不下載、不打包**任何元大專有元件；`YuantaSparkAPI.dll` 與其相依原生函式庫由使用者自行取得後，以唯讀 volume 掛入容器。
- **本任務不建立任何 Java 呼叫端。** 富邦的 Java client（`backend/src/main/java/com/steven/assets/integration/fubon/`）是為了把庫存寫進 `asset_snapshot`（Task 352）與把報價接成 Redis LIVE producer（Task 353）而存在，兩者都有具體消費者。元大這次任務單純是把官方提供的查詢功能做成可呼叫的 normalized internal API，**尚無具體功能要消費它**——不預先蓋一層沒人呼叫的 Java proxy（YAGNI）。日後若有功能（例如帳戶總覽頁）要用這些查詢，由該功能的任務新增 Java client 並複製 `integration/fubon` 的既有慣例。
- **不寫任何既有 DB 資料表，不影響既有即時報價 pipeline。** `external-materials-service` 既有 `PricePoller`／`PriceCacheWriter`／Redis LIVE quote 完全不變；`asset_snapshot`／`stock_holding` 不被本任務寫入。
- **目前無官方帳號／憑證可供測試。** 本任務只能做到「無真實帳號也能驗收」的部分：服務骨架、fail-closed 狀態機、normalized API 契約、fake gateway 測試。真實查詢驗證留待使用者取得官方 SPARK API 帳號後另行驗收。

## 要做什麼

- [x] **364.1 建立 `yuanta-broker-service/` 服務骨架。** 新增 `yuanta-broker-service/`，結構比照 `fubon-broker-service/`：
  - `Dockerfile`（multi-stage：`test`／`runtime`）：base image 精確釘選 `python:3.13.7-slim-bookworm`（與 `fubon-broker-service/Dockerfile` 相同 tag，保持一致）；**不下載任何元大專有元件**（沒有可雜湊釘選的公開直鏈）；安裝 `pythonnet`、FastAPI、uvicorn 與其他 exact-pinned runtime 依賴（`requirements.txt`／`requirements-test.txt`）；安裝 .NET 8 runtime（`dotnet-runtime-8.0`，經 Debian/Microsoft 官方 apt 來源，非本專案自行雜湊）。
  - `.dockerignore`：排除 `secrets/`、任何 `.dll`／`.so`／`.pfx`。
  - `src/yuanta_broker_service/`：`app.py`、`config.py`、`security.py`、`sdk_gateway.py`、`models.py`。
  - `tests/`：`FakeYuantaSparkGateway` 與路由測試，不 import `pythonnet`／`clr`。
  - `platform: linux/amd64` 於 Compose 宣告（見 364.3）。

- [x] **364.2 secrets 目錄與說明。** 新增可 commit 的 `secrets/yuanta/README.md` 與必要 `.gitignore`/空目錄 marker（`secrets/yuanta/sdk/dll/.gitkeep` 等），列出下列檔名，**不得建立帶假值的檔案**：
  - `sdk/dll/`（目錄）：使用者取得 `YuantaSparkAPI.dll` 與相依原生函式庫後放入此處。
  - `sdk/account`（登入帳號，Linux 版仍需搭配憑證）
  - `sdk/password`
  - `sdk/certificate.pfx`
  - `sdk/certificate-password`
  - `sdk/stock-account-selector`（可選；格式 `分公司代號:帳號`）
  - `sdk/futures-account-selector`（可選；格式同上）
  - `internal-service-token`（**flat 檔案，不放在 `sdk/` 底下**）：本服務唯一的 internal API 認證密鑰。因為本任務不建立 Java 消費端，不需要比照 `fubon-broker-service` 拆分 `sdk/`／`shared/` 兩層；`config.py` 直接從 `/run/secrets/yuanta/internal-service-token` 讀取這個檔案的內容作為比對值。日後若新增 Java 消費端，該任務再決定是否需要拆層。
  README 內容：條列上述檔名／權限／掛載路徑；說明使用者須先完成元大官方申請流程（簽署風險預告書 → 下載測試軟體並完成測試 → 取得正式或 UAT 環境的 API 元件與憑證）；不放 example value。`.env.example` 只新增兩行：
  ```
  YUANTA_ENABLED=false
  YUANTA_SECRETS_DIR_HOST=./secrets/yuanta
  ```
  不得出現帳密／憑證路徑以外的 literal。上述 host 目錄與秘密檔案（`.pfx`／`.dll`／`.so`／帳密檔）必須被 Git 忽略；`yuanta-broker-service` 的 build context 亦須排除。

- [x] **364.3 Compose 安全邊界。** `docker-compose.yml` 新增 `yuanta-broker-service`（container `asset-yuanta-broker-service`）：
  - `platform: linux/amd64`；只連 `asset-net`，不宣告 `ports`。
  - `user`：non-root（比照 fubon-broker-service 的 UID/GID 慣例）。
  - `read_only: true`、`tmpfs: [/tmp:rw,noexec,nosuid,size=16m]`、`cap_drop: [ALL]`、`security_opt: [no-new-privileges:true]`。
  - `environment`：`YUANTA_ENABLED: ${YUANTA_ENABLED:-false}`、`PYTHONDONTWRITEBYTECODE: "1"`。
  - `volumes`：`${YUANTA_SECRETS_DIR_HOST:-./secrets/yuanta}:/run/secrets/yuanta:ro`。
  - `healthcheck`：只用 Python stdlib 打容器內 `GET /internal/health`（比照 fubon-broker-service 寫法）。
  - `YUANTA_ENABLED=false`（預設）時 service liveness 仍 healthy；`business-services`／`bff`／`external-materials-service`／`postgres`／`redis` 不得因它沒有 credentials 而 unhealthy 或 restart，也不得有任何 `depends_on` 引用本服務。

- [x] **364.4 config、redaction、exact routes 與 internal auth。** `config.py` 只讀 mounted files（`/run/secrets/yuanta/...`），不從 request body 接受任何憑證欄位；internal API 認證 token 讀自 `/run/secrets/yuanta/internal-service-token`（見 364.2）。`GET /internal/health` 是唯一免 token endpoint，只回 `{status, configState, sdkComponentVersion, platform}`（`sdkComponentVersion` 讀不到時為 `null`，不得讀 secret 內容）；其餘 12 支 functional route（見 364.5）與 `GET /internal/config` 皆驗 exact header `X-Internal-Service-Token`，`security.py` 用 `secrets.compare_digest` 常數時間比較。production FastAPI app 必須 `docs_url=None, redoc_url=None, openapi_url=None`；精確 route allowlist 為本任務定義的 14 支（health + config + 6 帳務 + 5 行情 + 1 回報），其餘 path 一律 404、允許 path 上的錯誤 method 一律 405。
  - **`configState` 只代表「元件與憑證檔案是否齊備、DLL 是否能被 `pythonnet` 成功載入」，不代表「是否已成功登入」或「行情連線是否已建立」——這兩者是各自獨立、各自 lazy 觸發的能力，見下方說明。** `YUANTA_ENABLED=false` → `NOT_CONFIGURED`（health 200）；`YUANTA_ENABLED=true` 但 `sdk/dll/` 內找不到 `YuantaSparkAPI.dll`、或憑證/帳密/`internal-service-token` 任一檔缺漏、或 `pythonnet`/`clr` 載入 DLL 失敗（僅載入元件本身，不含呼叫 `Login`）→ `MISCONFIGURED`（health 仍 200，但下方所有 functional endpoint 固定 503）；元件與憑證齊備且 DLL 載入成功 → `READY`。
  - **登入（`Login()`）與行情連線是各自獨立的 lazy capability，`configState=READY` 只是呼叫它們的前提，不保證它們會成功：** 帳務／回報類 endpoint（364.7／364.9）第一次被呼叫時才 lazy 觸發 `Login()`，成功後快取「已登入」旗標（process 內存活，供 364.11 的 shutdown 清理判斷用，且供之後同 process 內的請求重用同一 session，不必每次都重新登入）；失敗則該次請求回 503（`reason=LOGIN_FAILED`），**不降級全域 `configState`**（因為 `configState=READY` 只承諾元件可用，不承諾帳密正確或連線可達），下一次請求可重試。行情類 endpoint（364.8）第一次被呼叫時才 lazy 建立行情連線，成功後同樣快取；失敗則該次請求回 503（`reason=MARKET_DATA_UNAVAILABLE`），同樣不降級 `configState`。換句話說：`configState != READY` 時全部 12 支 functional endpoint 一律 503（連嘗試都不嘗試）；`configState == READY` 時，個別 endpoint 是否成功取決於其對應能力（登入或行情連線）當下是否可用，用各自獨立的 503 reason 表達，不是同一個布林值。
  - config 讀取全程 lazy：`config.py` 不得在 module import 或 FastAPI app 啟動階段主動嘗試載入 DLL、登入或建立行情連線；只在第一次收到 functional 請求時才 lazy 初始化並快取 `configState`（避免每次請求都重新嘗試載入失敗的 DLL）。`YUANTA_ENABLED=true` 但秘密缺漏不得讓 container 啟動失敗或 healthcheck 變 unhealthy。
  - 中央 redactor（比照 `fubon-broker-service/src/fubon_broker_service/redaction.py` 的 `redact()`／`redact_mapping()`／`RedactingLogFilter` 寫法）套用在本服務的所有 logger 與 response body：帳號、身分證字號、密碼、憑證路徑、憑證密碼、SDK raw exception repr 不得出現在 response／log／metric。

- [x] **364.5 唯讀 SDK gateway 與 import 邊界。** `sdk_gateway.py` 定義一個 gateway 介面（可用 `Protocol` 或抽象基底類別），至少涵蓋：
  - `login() -> LoginResult`（內部呼叫 SPARK API Linux 版 `Login(PfxPath, PfxPass, Account, Pass)`）
  - `logout() -> None`
  - 帳務查詢（各自對應一支）：`get_stock_inventory`、`get_futures_inventory`、`get_unrealized_pnl`、`get_realized_pnl`、`get_settlement`、`get_futures_margin`
  - 行情查詢：`get_quote`、`get_five_best`、`get_intraday_ticks`、`get_kline`、`get_instrument_info`
  - 回報查詢：`get_order_execution_report`

  真實實作（呼叫 SPARK API 的版本）只能 `import` 上述查詢／登入／登出對應的 SPARK API 符號與 `Subscribe*`／`Unsubscribe*`；**禁止 import 或呼叫任何官方下單／改單／刪單／複式單／保證金操作符號**，包含但不限於名稱以 `Send` 開頭者（下單、刪改單、`SendFutureCombined`）與 `GetFutDepositOptimum`（保證金最佳化查詢語意但與下單流程並列，本任務刻意不納入以避免邊界模糊）。此邊界對應 `CLAUDE.md`〈券商 API 只能查詢，不得交易〉全專案最高優先鐵則；程式碼 review 與後續 `arch-auditor` 稽核須逐一核對本檔案的 import 清單，確認沒有出現任何交易類符號。

  raw→normalized 欄位映射全部集中在 `sdk_gateway.py`（或其匯入的單一子模組），不得散落在 `app.py`／`models.py` 各處——因為 SPARK API 實際回應欄位名稱與精度尚未經真實帳號驗證，日後取得官方帳號後需要能集中調整而不影響對外 endpoint 契約。

- [x] **364.6 帳號選擇。** `login()` 成功後從回傳帳號清單中，依查詢類別（證券／期貨）各自選出對應帳號：
  - 若對應的 `sdk/stock-account-selector`（或 `futures-account-selector`）檔存在，必須精確 match 該分公司代號＋帳號的候選；不 match 視為 `MISCONFIGURED`。
  - 若未設定該 selector，該類型帳號必須「恰好一個」候選才可繼續；零個或多個候選都視為 `MISCONFIGURED`，不得任選其一。
  - raw 帳號（分公司代號＋帳號＋身分證字號＋營業員代碼）只留在 Python process 記憶體；normalized 回應與 log 只可用 `HMAC-SHA256(internal-token, branch_no + ":" + account)` 的截短 hex fingerprint，不得出現明碼。

- [x] **364.7 帳務查詢 endpoint（6 支，皆需登入＋選定證券或期貨帳號）。**
  - `POST /internal/accounting/inventory-stock`：股票庫存查詢。
  - `POST /internal/accounting/inventory-futures`：期貨庫存查詢。
  - `POST /internal/accounting/unrealized-pnl`：未實現損益查詢。
  - `POST /internal/accounting/realized-pnl`：已實現損益查詢；request body 至少接受 `startDate`／`endDate`（`YYYY-MM-DD`），缺少任一即 400。
  - `POST /internal/accounting/settlement`：交割款查詢。
  - `POST /internal/accounting/futures-margin`：期貨權益數查詢。

  每支 endpoint 未帶 `X-Internal-Service-Token` 回 401；token 錯誤回 403；`configState != READY` 回 503（`reason=NOT_CONFIGURED` 或 `MISCONFIGURED`）；`configState == READY` 但 lazy `Login()` 尚未成功過或本次重試失敗回 503（`reason=LOGIN_FAILED`）；成功回應為本服務自有 normalized schema，不得序列化 SDK raw object。

- [x] **364.8 行情查詢 endpoint（5 支，僅需已建立行情連線，不需登入特定帳戶）。**
  - `POST /internal/market-data/quote`：報價表查詢；request body `{"codes": ["2330", ...]}`，`codes` 為 1..100 個去重後代碼，超限或空陣列回 400。
  - `POST /internal/market-data/five-best`：最佳五檔查詢；同上 `codes` 限制。
  - `POST /internal/market-data/intraday-ticks`：分時明細查詢；request body 至少接受單一 `code`。
  - `POST /internal/market-data/kline`：K線查詢；request body 至少接受 `code`、`interval`（例如 `1D`／`1W`）、`count` 或 `startDate`/`endDate` 其中一種分頁方式。
  - `POST /internal/market-data/instrument-info`：標的資訊查詢；request body 至少接受單一 `code`。

  行情查詢無帳戶概念，不需要 `Login()`。`configState != READY` 一律 503（`reason=NOT_CONFIGURED` 或 `MISCONFIGURED`）；`configState == READY` 但 lazy 行情連線尚未成功過或本次重試失敗回 503（`reason=MARKET_DATA_UNAVAILABLE`）。

- [x] **364.9 委託成交回報查詢 endpoint（1 支，需登入）。**
  - `POST /internal/reports/order-execution`：委託成交綜合回報查詢；request body 至少接受可選的 `startDate`／`endDate` 篩選（缺省時查詢當日）。

- [x] **364.10 decimal／數量 wire 格式。** 沿用 `fubon-broker-service` 已驗證的慣例：所有金額／價格類欄位以不含 `e/E` 的 canonical decimal string 輸出（先 `Decimal(str(value))` 驗 finite 再序列化），要求 precision ≤ 20、scale 0–10；股數／口數／量類欄位為 `0..9,223,372,036,854,775,807` 的非負 exact integer。任何欄位型別轉換失敗（Python float 直接序列化、SDK 回傳 NaN/Infinity 等）視為該筆資料錯誤，不得靜默轉為 0 或省略欄位——回應中該筆標記錯誤原因，不偽造數值。

- [x] **364.11 ASGI shutdown 與 session 清理。** 維護一個獨立於 `configState` 的 process 內旗標（例如 `_login_succeeded_at_least_once: bool`，見 364.4——`configState` 只代表元件/憑證齊備，不代表已登入，兩者不可混用）。ASGI shutdown hook 以 bounded timeout best-effort 呼叫 `LogOut()`／SDK 提供的清理函式；用 idempotent flag 保證每個已建立 session 只清理一次；只有該旗標為 true（即本 process 生命週期內至少成功登入過一次）才呼叫清理，`configState` 停留在 `NOT_CONFIGURED`／`MISCONFIGURED`，或雖 `READY` 但從未成功呼叫過 `Login()`，皆不呼叫清理。清理過程的例外經 redactor 處理後記錄，不得阻塞容器退出。

- [x] **364.12 自動測試矩陣（`tests/`，全程不需真實憑證）。** 至少涵蓋：
  - `configState` 三態：`NOT_CONFIGURED`（`YUANTA_ENABLED=false`）、`MISCONFIGURED`（`YUANTA_ENABLED=true` 但 fake gateway 回報 DLL/憑證/`internal-service-token` 缺漏或載入失敗）、`READY`（fake gateway 回報元件載入成功）。
  - 14 支 route 的 allowlist：非白名單 path 404、允許 path 錯誤 method 405；`docs_url`/`redoc_url`/`openapi_url` 皆不可存取。
  - **13 支需 token 的 route（12 functional + `GET /internal/config`）**：缺 token 401、token 錯誤 403。
  - 12 支 functional endpoint（6 帳務 + 5 行情 + 1 回報）：`configState != READY` 時 503（`reason=NOT_CONFIGURED`/`MISCONFIGURED`）；`configState == READY` 但 fake gateway 對應能力（登入或行情連線）回報失敗時 503（`reason=LOGIN_FAILED`/`MARKET_DATA_UNAVAILABLE`，且不降級全域 `configState`——之後同一 fake gateway 改回成功，下次請求即成功，不需重啟服務）；兩者皆成功時回傳 normalized schema。
  - 帳號 selector：selector 設定精確 match 成功；selector 未設定且候選帳號「恰好一個」成功；候選為零個或多個時皆 `MISCONFIGURED`。
  - decimal／量欄位邊界：合法 canonical decimal string 通過；float／scientific notation／NaN／Infinity／負值量欄位皆標記該筆錯誤而非靜默省略或轉 0。
  - health endpoint 免 token 即可存取，且回應不含任何 secret 內容。
  - redaction：構造一筆帶 raw 帳號／密碼／憑證路徑的 log 呼叫，斷言最終輸出已被 redact（比照 fubon 的
    `test_log_pipeline_redacts_a_careless_raw_value_not_just_the_redact_function` 寫法，不只測 `redact()` 函式本身）。
  - `sdk_gateway.py` 的 import 檢查：以靜態方式（例如讀取模組原始碼字串或 `ast` 解析）斷言檔案內容不含任何禁止的交易類符號名稱（`SendFutureCombined`、`GetFutDepositOptimum`，以及任何以常見下單函式命名慣例出現的字串），作為防止未來有人不慎加入下單呼叫的回歸測試。

## 驗證

```bash
set -euo pipefail

bash scripts/spec-check.sh

docker buildx build --platform linux/amd64 --target test \
  -f yuanta-broker-service/Dockerfile yuanta-broker-service \
  --load -t asset-yuanta-broker-service:test
docker run --rm --platform linux/amd64 asset-yuanta-broker-service:test pytest -q

# 官方元件不得出現在 repo 或 build context
if git ls-files yuanta-broker-service secrets | rg '\.(dll|so|pfx)$'; then
  echo 'Yuanta SDK component files and certificates must not be tracked' >&2; exit 1
fi

docker compose -p asset-management build --no-cache yuanta-broker-service

# 無 secret / disabled 是正式支援模式，不得拖垮既有 stack
YUANTA_ENABLED=false docker compose -p asset-management up -d --no-deps --force-recreate \
  yuanta-broker-service
docker compose -p asset-management ps

cid=$(docker compose -p asset-management ps -q yuanta-broker-service)
test -n "$cid"
test "$(docker inspect asset-yuanta-broker-service --format '{{.State.Health.Status}}')" = healthy
test -z "$(docker port asset-yuanta-broker-service)"

docker exec asset-yuanta-broker-service python -c \
  'import json,urllib.request; d=json.load(urllib.request.urlopen("http://127.0.0.1:8080/internal/health")); assert d["status"]=="UP" and d["configState"]=="NOT_CONFIGURED"'

# 既有 stack 不得因本服務而 unhealthy
docker compose -p asset-management ps business-services bff external-materials-service postgres redis
```

有安裝真實 secrets 時的受控驗收（不得把值印到 shell history/log；**本次任務不要求完成此段**，留待使用者取得官方帳號後另行驗收）：

- [ ] 使用者已完成元大官方申請流程（簽署風險預告書、下載測試軟體並完成測試），取得 SPARK API 元件與憑證，並依 364.2 放入 `secrets/yuanta/`。
- [ ] `YUANTA_ENABLED=true` recreate 後 health 為 `READY`。
- [ ] 逐一呼叫 14 支 functional endpoint，確認回應為合理的查詢結果（庫存/損益/交割款/期貨權益數/報價/五檔/分時/K線/標的資訊/委託成交回報），且 response/log 無 raw 帳號／密碼／憑證路徑。
- [ ] 若當下無 credentials，完成報告明列「真實元大帳號查詢」為未執行，不得以 fake gateway 測試結果宣稱已完成真實查詢驗證。

## 完成報告

### 實際變更檔案

新增：

- `yuanta-broker-service/Dockerfile`、`.dockerignore`、`requirements.txt`、`requirements-test.txt`
- `yuanta-broker-service/src/yuanta_broker_service/{__init__,app,config,security,sdk_gateway,models}.py`
- `yuanta-broker-service/tests/{helpers,test_config,test_app_routes,test_sdk_gateway,test_sdk_gateway_import_boundary}.py`
- `secrets/yuanta/README.md`、`secrets/yuanta/.gitignore`、`secrets/yuanta/sdk/dll/.gitkeep`

修改：

- `docker-compose.yml`：新增 `yuanta-broker-service` service（container `asset-yuanta-broker-service`，比照 `fubon-broker-service` 的安全邊界設定）
- `.env.example`：新增 `YUANTA_ENABLED=false`、`YUANTA_SECRETS_DIR_HOST=./secrets/yuanta` 兩行

（`CLAUDE.md`／`spec/design.md`／`spec/requirements.md`／`spec/steering/structure.md`／`spec/tasks.md`／`spec/tasks/README.md` 為本任務規格作者在先前階段所寫，非本次實作變更，本次實作未再修改。）

`backend/`／`bff/`／`external-materials-service/`／`db/changelog/`／前端一律未動，符合任務排除範圍。

### 檔案結構偏差說明

任務檔 364.4 提到的「中央 redactor（比照 `redaction.py`）」與 364.1 列出的檔案清單（`app.py`／`config.py`／`security.py`／`sdk_gateway.py`／`models.py`，未含 `redaction.py`）字面上有一處需要取捨：本實作選擇把 redaction 相關函式（`redact`／`redact_mapping`／`RedactingLogFilter`／`install_log_redaction`）與 internal token 常數時間比較一起放進 `security.py`，不再另開 `redaction.py`，以嚴格符合 364.1 的五檔案清單。行為與 fubon 的 `redaction.py` 完全一致（同一組函式簽章與正則）。

### 帳號類別分派的簡化假設（無真實文件可查證）

SPARK API 沒有公開文件可查證實際欄位名稱，以下為本服務**自訂**的正規化契約與帳號類別假設，待日後取得真實帳號後可能需要調整（集中在 `sdk_gateway.py`，不影響對外 route 契約）：

- `inventory-stock`／`unrealized-pnl`／`realized-pnl`／`settlement` 使用「證券」帳號；`inventory-futures`／`futures-margin` 使用「期貨」帳號。
- `order-execution-report`（委託成交回報）不綁定特定帳號類別，只要求 `Login()` 曾經成功，理由：多數券商的回報查詢是登入後的整戶查詢，不是單一子帳戶查詢；任務檔未指定，此為合理簡化。

### 測試結果

Docker test image（`python:3.13.7-slim-bookworm`，與 runtime 相同 base）：

```
62 passed in 0.72s
```

涵蓋：configState 三態、14 支 route allowlist（含 docs/redoc/openapi 404、錯誤 method 405）、13 支 token 閘門（401/403）、12 支 functional endpoint 的三層 503 語意（NOT_CONFIGURED/MISCONFIGURED → LOGIN_FAILED/MARKET_DATA_UNAVAILABLE 且不降級 configState、重試可恢復 → 成功回傳 normalized schema）、帳號 selector 三種情境（精確 match／恰好一個／零個或多個）、decimal／整數邊界（合法值、科學記號、NaN/Infinity、負值量欄位、precision/scale 超限）、redaction pipeline（含「粗心呼叫」情境，不只測 `redact()` 本身）、health 不含 secret、`sdk_gateway.py` 的下單符號靜態掃描（`SendFutureCombined`／`GetFutDepositOptimum`／`Send[A-Z]*`／`PlaceOrder*` 等樣式）、shutdown 冪等與例外吞噬。

### Docker 驗證輸出摘要

```
$ bash scripts/spec-check.sh
BLOCK: 0   CHECK: 0 → 機械檢查通過

$ docker buildx build --platform linux/amd64 --target test ... --load -t asset-yuanta-broker-service:test
（成功，pythonnet==3.1.0／clr-loader==0.3.1 為純 wheel，安裝不需 .NET）

$ docker run --rm --platform linux/amd64 asset-yuanta-broker-service:test pytest -q
62 passed in 0.72s

$ git ls-files yuanta-broker-service secrets | rg '\.(dll|so|pfx)$'
（無輸出，確認未追蹤任何專有元件／憑證）

$ docker compose -p asset-management build --no-cache yuanta-broker-service
（成功；runtime stage 經 packages.microsoft.com 官方 apt 來源安裝 dotnet-runtime-8.0，安裝後移除 gnupg/wget）

$ YUANTA_ENABLED=false docker compose -p asset-management up -d --no-deps --force-recreate yuanta-broker-service
Container asset-yuanta-broker-service  Started

$ docker inspect asset-yuanta-broker-service --format '{{.State.Health.Status}}'
healthy

$ docker port asset-yuanta-broker-service
（無輸出，未發布任何 port）

$ docker exec asset-yuanta-broker-service python -c '...GET /internal/health...'
{'status': 'UP', 'configState': 'NOT_CONFIGURED', 'sdkComponentVersion': None, 'platform': 'x86_64'}

$ docker compose -p asset-management ps business-services bff external-materials-service postgres redis
全部 healthy，未受本服務影響
```

### 與原計畫的偏差

除上述「redaction 併入 `security.py`」外，無其他偏差；Dockerfile 因元大 SDK 無需雜湊下載（pythonnet 是公開 PyPI 套件），比 fubon 的三階段（`test`/`sdk-builder`/`runtime`）少一個 build stage，直接兩階段（`test`/`runtime`），這是任務檔背景章節已預期的差異，非偏離。

### 有安裝真實 secrets 時的受控驗收

**未執行**（任務檔明確標注「本次任務不要求完成此段」）。上方「有安裝真實 secrets 時的受控驗收」勾選項全部保持未勾選；本次僅以 `FakeYuantaSparkGateway` 驗證服務自身的路由／狀態機／認證／redaction／decimal 邊界行為，**未對任何真實元大帳號或 SPARK API 進行查詢驗證**。
