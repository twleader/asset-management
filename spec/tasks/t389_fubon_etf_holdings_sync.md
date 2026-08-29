# [t389] 富邦 ETF 成分股持股明細——抓取與落地基礎設施

**對應 Requirements:** Requirement 123（富邦「ETF 成分股持股明細查詢」排程串接，取代既有 MoneyDJ／Yahoo 爬蟲來源；本任務只做抓取與落地，欄位解析與讀取端切換是 Task 390）
**前置任務:** 無
**Liquibase changeset:** `v1.120.0-fubon-etf-holdings-snapshot.sql`（實際版號以建檔當下 `bash scripts/spec-check.sh` 核對過的最新版號為準，撞號時依既有編號避讓慣例調整）

## 背景

富邦官方 SDK（`fubon_neo` 2.2.9）的 `marketdata.rest_client.stock.ownership.etf_holdings` 是一支查詢 ETF 成分股持股明細的唯讀方法，已在 `spec/tasks/t386_fubon_api_documentation_view.md` 盤點過但**尚未串接**。這支方法完全沒有 docstring、`fugle_marketdata` 套件（獨立 PyPI 套件，`fubon_neo.sdk` 內 `from fugle_marketdata import FugleAPIError` 引用）也沒有任何文件說明回應形狀——`fugle_marketdata/rest/stock/ownership.py` 原始碼只有：

```python
class Ownership(BaseRest):
    def etf_holdings(self, **params):
        symbol = params.pop('symbol')
        return self.request(f"ownership/etf-holdings/{symbol}", **params)
```

`BaseRest.request()` 是純 HTTP GET 轉發（`requests.get(url, headers=...)`），回傳 `response.json()`，沒有任何欄位驗證或轉換。也就是說，**唯一能知道確切回應欄位名稱的方法，是在具備真實富邦憑證、`FUBON_ENABLED=true` 的環境對一支已知 ETF 做一次真實呼叫**。本次撰寫規格時的開發環境 `FUBON_ENABLED=false`，無法核實。

因此本任務**刻意只做「與欄位無關」的部分**：把富邦回應原封不動存成 JSON，不解析、不重新命名任何欄位。排程、feature flag、交易日判斷、雷達範圍查詢、資料落地全部可以在不知道確切欄位名稱的情況下完成並獨立驗收（下 SQL 查詢就能確認資料落地了、排程確實有在跑）。把原始 JSON 轉成結構化 `EtfHolding` 清單、切換既有 `MarketDataService.getEtfHoldings()` 的讀取路徑、清理舊爬蟲，是 Task 390 的範圍（依賴真實環境核實欄位，本任務不做）。

系統既有「今日交易雷達」的**官方跨使用者定義**（Requirement 115／Task 380）目前只在 `external-materials-service` 的 `StockSourceQuery.collectTwRadarCodes(Set<String> twCodes)` 有實作：

```java
// external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java:147
public void collectTwRadarCodes(Set<String> twCodes) {
    jdbc.query("SELECT h.stock_code FROM stock_holding h WHERE h.market = '台股' "
                    + "AND h.snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot "
                    + "ORDER BY owner_user_id, snapshot_date DESC, id DESC)",
            (java.sql.ResultSet rs) -> { twCodes.add(rs.getString("stock_code")); });
    jdbc.query("SELECT DISTINCT stock_code FROM stock_alert WHERE market = '台股'",
            (java.sql.ResultSet rs) -> { twCodes.add(rs.getString("stock_code")); });
    twCodes.remove("0000");
}
```

本任務新排程放在 `backend`（比照既有 `com.steven.assets.integration.fubon` package 底下的 `FubonInventorySyncScheduler`／`FubonTradeSyncScheduler`——Fubon HTTP client 與 DB 寫入邏輯都在 backend）。`stock_holding`／`asset_snapshot`／`stock_alert` 三張表的 JPA Entity 本來就在 backend，backend 是這些表的權威擁有者，因此**在 backend 端用等價 SQL 重新實作**這段查詢，不新增 backend → external-materials-service 的內部 HTTP 呼叫（這會建立方向奇怪的新依賴：Fubon 整合模組為了拿雷達清單反過來依賴一個原本只做行情抓取的服務）。代價是與 `collectTwRadarCodes` 邏輯重複、有漂移風險，可接受原因是範圍極窄（固定 SQL + 一個 `startsWith("00")` 過濾）且本專案已有「兩份平行雷達實作」的既定格局（`TradingRadarService` per-owner／`StockSourceQuery` 跨 owner）。

台股 ETF 判定沿用既有 `MarketDataService.isEtf(code, market)`（`backend/src/main/java/com/steven/assets/service/MarketDataService.java:557`）對台股採用的規則：`code.startsWith("00")`。這不是資料庫權威欄位，是既有程式碼裡最常用的 heuristic，本任務不新增 `is_etf` 欄位。

`fubon-broker-service` 既有的 marketdata 相關唯讀呼叫（`quote()`，供台股即時報價使用）示範了如何存取 `marketdata.rest_client.stock`：

```python
# fubon-broker-service/src/fubon_broker_service/sdk_gateway.py:211（節錄既有 quote() 方法）
def quote(self, code: str) -> object:
    for attempt in range(2):
        config = self._require_config()
        self._ensure_session(config)
        with self._session_lock:
            client = self._stock_client
        if client is None:
            raise SdkCallError("REALTIME_NOT_INITIALIZED", misconfigured=True)
        try:
            intraday = raw_field(client, "intraday")
            quote_method = raw_field(intraday, "quote") if intraday is not None else None
            if not callable(quote_method):
                self._mark_misconfigured()
                raise SdkCallError("QUOTE_CLIENT_UNAVAILABLE", misconfigured=True)
            response = self._run_bounded(
                lambda: quote_method(symbol=code), self.QUOTE_CALL_TIMEOUT_SECONDS, "QUOTE_TIMEOUT"
            )
            if self._response_auth_invalid(response):
                raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
            return response
        except SdkCallError as exc:
            if exc.auth_invalid and attempt == 0:
                with self._session_lock:
                    self._invalidate_locked()
                continue
            raise
        except Exception as exc:
            if self._exception_is_rate_limited(exc):
                raise SdkCallError("RATE_LIMITED", retry_after_seconds=self._exception_retry_after(exc)) from None
            if self._exception_is_auth_invalid(exc) and attempt == 0:
                with self._session_lock:
                    self._invalidate_locked()
                continue
            raise SdkCallError("QUOTE_TRANSPORT_FAILED") from None
    raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
```

`self._stock_client` 在 `_ensure_session()`（`sdk_gateway.py:354-356`）已經快取好 `sdk.marketdata.rest_client.stock`，`quote()` 不經過 `_accounting_lock`／`_wait_for_accounting_budget()`（那是帳務命名空間 `sdk.accounting`／`sdk.stock`（交易帳號）專用的序列化與限速，marketdata REST 這條線完全獨立）。本任務新增的 `read_etf_holdings()` 要複製這個結構，不要複製 `read_filled_trades()`／`_accounting_call()` 的結構（那是帳務查詢，有 raw identity 驗證與 `_accounting_lock`，跟 ETF 成分股查詢無關）。

**與 `FUBON_TW_LIVE_QUOTES_ENABLED` 不互斥的理由**：Requirement 120（成交同步）與 LIVE 報價互斥的根因是兩者都要佔用同一個富邦 SDK session 的**帳務／委託序列化資源**（`_accounting_lock`）。本功能與 `quote()` 同屬 `marketdata.rest_client.stock`，這條線本身不經過 `_accounting_lock`，只共用 `_run_bounded` 的 `_blocking_slots`（`MAX_BLOCKING_CALLS=4`）併發限流——本來就設計成可併發。本排程一天只跑兩次、每次僅查少量 ETF 代碼，資源峰值遠低於每 10 秒一輪的 LIVE 報價，故不設互斥旗標。

## 要做什麼

- [ ] **389.1 `fubon-broker-service` 新增 `SdkGateway.read_etf_holdings()`。** 在 `fubon-broker-service/src/fubon_broker_service/sdk_gateway.py` 新增方法，結構比照上方節錄的既有 `quote()`（複製其 session 取得、`_stock_client` 使用、`_run_bounded` 逾時包裝、`auth_invalid`／`rate_limited` 重試邏輯），但改為：
  ```python
  def read_etf_holdings(self, symbol: str) -> object:
      for attempt in range(2):
          config = self._require_config()
          self._ensure_session(config)
          with self._session_lock:
              client = self._stock_client
          if client is None:
              raise SdkCallError("REALTIME_NOT_INITIALIZED", misconfigured=True)
          try:
              ownership = raw_field(client, "ownership")
              etf_holdings_method = raw_field(ownership, "etf_holdings") if ownership is not None else None
              if not callable(etf_holdings_method):
                  self._mark_misconfigured()
                  raise SdkCallError("ETF_HOLDINGS_CLIENT_UNAVAILABLE", misconfigured=True)
              response = self._run_bounded(
                  lambda: etf_holdings_method(symbol=symbol),
                  self.QUOTE_CALL_TIMEOUT_SECONDS, "ETF_HOLDINGS_TIMEOUT",
              )
              if self._response_auth_invalid(response):
                  raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
              return response
          except SdkCallError as exc:
              if exc.auth_invalid and attempt == 0:
                  with self._session_lock:
                      self._invalidate_locked()
                  continue
              raise
          except Exception as exc:
              if self._exception_is_rate_limited(exc):
                  raise SdkCallError("RATE_LIMITED", retry_after_seconds=self._exception_retry_after(exc)) from None
              if self._exception_is_auth_invalid(exc) and attempt == 0:
                  with self._session_lock:
                      self._invalidate_locked()
                  continue
              raise SdkCallError("ETF_HOLDINGS_TRANSPORT_FAILED") from None
      raise SdkCallError("AUTH_SESSION_INVALID", auth_invalid=True)
  ```
  **不得**經過 `_accounting_lock`／`_wait_for_accounting_budget()`；`QUOTE_CALL_TIMEOUT_SECONDS` 沿用既有常數，不新增獨立 timeout 常數（除非既有常數語意明顯不合用，若不合用需在完成報告說明原因並改用新常數）。

- [ ] **389.2 新增 `POST /internal/market-data/etf-holdings` route。** 在 `fubon-broker-service/src/fubon_broker_service/app.py` 新增（比照既有 `/internal/market-data/tw-quotes` 的 Pydantic model／`Depends(authorize)`／outcome counters／`sanitized_exception_boundary` 慣例）：request body Pydantic model `{"codes": list[str]}`（1..50 檔，`ConfigDict(extra="forbid", strict=True)`）；對每一檔呼叫 `gateway.read_etf_holdings(code)`，成功時把回應**原封不動**（不重新命名、不篩選、不轉型欄位）序列化為 JSON 字串放進該檔的 `rawResponseJson`；失敗（含 timeout／auth invalid／transport failure）該檔 `status="FAILURE"`、`reason=<消毒後的錯誤代碼>`。回應形狀：`{"batchId": "...", "holdings": [{"stockCode": "0050", "status": "SUCCESS"|"FAILURE", "reason": "...", "rawResponseJson": "..."}], "counters": {...}}`。既有三態語意（`DISABLED`／`MISCONFIGURED`／token 缺漏或錯誤）與既有 `/internal/market-data/tw-quotes` 完全一致（本路由沿用既有 `Depends(authorize)` 的三態語意，不新增獨立驗證機制）。

- [ ] **389.3 `fubon-broker-service` 測試。** 新增測試（比照既有 `tests/test_quotes.py` 的結構，非 `tests/test_trades.py`——本次無帳務 raw identity 驗證）：mock `gateway.read_etf_holdings` 驗證成功／失敗兩種路徑正確映射到 response 的 `status`／`reason`／`rawResponseJson`；驗證 request body 校驗（`codes` 為空、超過 50 檔、非法欄位皆回 400）；驗證未帶 token 回 401／403、`DISABLED`／`MISCONFIGURED` 狀態回 503。`tests/test_app_routes.py` 的 `test_exact_six_routes_auth_and_methods` 需同步更新為 7 條 route（新增這一條），測試名稱視需要調整（如改為 `test_exact_seven_routes_auth_and_methods`）。並同步更新 `spec/tasks/t386_fubon_api_documentation_view.md` **`## 背景` 小節**（第 9、20 行附近，各自提到「目前對外暴露 6 支 HTTP endpoint」「不只列出已串接的 6 支 HTTP endpoint」）的「6 支」字樣改為「7 支」——**這是 Task 389 對 t386 的唯一必要更新**（把「已串接」狀態改成 `true` 是 Task 390 的範圍，因為要等欄位解析完成才算真正串接完；但 route 數從 6 條變 7 條是 Task 389 造成的事實，必須同步，否則 t386 的「## 背景」小節會立刻與程式碼不符）。t386.md 全文並未出現 `test_exact_six_routes_auth_and_methods` 這個 Python 測試名稱本身，不需要在 t386.md 裡另外更新它。

- [ ] **389.4 backend 新增 `FubonBrokerClient.readEtfHoldings()` 與 `FubonDtos` 型別。** `FubonBrokerClient` 介面新增：
  ```java
  FubonDtos.CallResult<FubonDtos.EtfHoldingsBatchResponse> readEtfHoldings(List<String> codes);
  ```
  `FubonHttpClient` 實作，比照既有 `readTwQuotes` 的 gate／timeout／`WebClientResponseException`／通用 Exception 轉換模式，呼叫 `POST /internal/market-data/etf-holdings`。`FubonDtos` 新增：
  ```java
  public record EtfHoldingsReadRequest(List<String> codes) {}

  public record EtfHoldingsBatchResponse(
          String batchId, List<EtfHoldingsItem> holdings, Map<String, Long> counters) {
      public EtfHoldingsBatchResponse(String batchId, List<EtfHoldingsItem> holdings) {
          this(batchId, holdings, Map.of());
      }
  }

  public record EtfHoldingsItem(String stockCode, String status, String reason, String rawResponseJson) {}
  ```
  `rawResponseJson` 為不可變 `String`，**不得**用 `Map`／`JsonNode` 代替；只需能被 Jackson 反序列化成字串（若 Python 端該欄位本身就是字串化的 JSON，`String` 欄位直接對應即可，不需自訂 deserializer）。

- [ ] **389.5 新增 Liquibase changeset 與資料表。** 新增 `backend/src/main/resources/db/changelog/changes/v1.120.0-fubon-etf-holdings-snapshot.sql`（版號先跑 `bash scripts/spec-check.sh` 核對是否已被佔用，若已被佔用依既有編號避讓慣例往後遞補並更新本任務檔與所有引用處）：
  ```sql
  CREATE TABLE fubon_etf_holdings_snapshot (
      etf_stock_code    VARCHAR(20) NOT NULL,
      market             VARCHAR(10) NOT NULL DEFAULT '台股',
      fetched_at         TIMESTAMP NOT NULL,
      success             BOOLEAN NOT NULL,
      reason              VARCHAR(50),
      raw_response_json  JSONB,
      updated_at          TIMESTAMP NOT NULL DEFAULT now(),
      PRIMARY KEY (etf_stock_code)
  );
  ```
  同 key 覆寫（比照既有 `fubon_taiex_index_latest`——`PRIMARY KEY (index_code)`，不含日期欄位——的「同 key 覆寫、只留最新一筆」慣例；**不是** `etf_nav_history`，該表用 `UNIQUE (stock_code, market, nav_date)` 逐日各留一筆，語意恰恰相反，是刻意保留歷史的表，不適用於本次設計）——本表只保存「目前最新一次抓取結果」，不做時間序列。新增對應 JPA Entity（`FubonEtfHoldingsSnapshot`，`@Id private String etfStockCode`）與 `FubonEtfHoldingsSnapshotRepository`（`JpaRepository<FubonEtfHoldingsSnapshot, String>`，或視需要加 `findByEtfStockCode`，`@Id` 查詢本身即可用 `findById`）。完成後依 `db/schema.sql` 檔頭「重新產生」段重產該檔，並以 `bash scripts/tests/schema-sql-drift-test.sh` 確認回 0。此表不透過任何 API／DTO 對外暴露。

- [ ] **389.6 新增 `FubonEtfHoldingsSyncScheduler` 與 `FubonEtfHoldingsSyncService`（`com.steven.assets.integration.fubon` package）。** Scheduler 比照既有 `FubonTradeSyncScheduler` 的骨架：獨立 process-local `AtomicBoolean inFlight`（`compareAndSet` single-flight，`finally` 釋放），兩個獨立 `@Scheduled` 方法：
  ```java
  @Scheduled(cron = "0 50 8 * * MON-FRI", zone = "Asia/Taipei")
  public void syncMorning() { runIfNotInFlight(); }

  @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Taipei")
  public void syncAfternoon() { runIfNotInFlight(); }
  ```
  兩者共用同一個 `inFlight` guard（同一支排程的兩個時間點不該互相搶跑，但也不會真的同時觸發，純粹是保險）。`Service.syncScheduled()` 執行順序：
  1. 讀新增獨立 `@Value("${fubon.etf-holdings-sync-enabled:false}")` feature flag；`false` 直接 no-op（不呼叫 configState、calendar 或 adapter）。
  2. `FubonConfigState.snapshot().state()` 必須為 `READY`，否則 no-op。
  3. 呼叫既有 `MarketDataService.isTwTradingDayKnown(today)`（`Asia/Taipei` 今日），只有明確 `Optional.of(true)` 才繼續，`Optional.empty()`／`Optional.of(false)` 一律 no-op（`RuntimeException` 視為 `Optional.empty()`，比照既有 `FubonTradeSyncScheduler`／`FubonInventorySyncScheduler` 用法）。
  4. 呼叫 389.7 的雷達 ETF 代碼查詢；空集合直接 no-op（不算錯誤）。
  5. 超過 50 檔時分批呼叫 `readEtfHoldings`（每批 ≤50），逐批合併結果；50 檔以內單批呼叫即可。
  6. 逐檔依 `status` upsert 進 `fubon_etf_holdings_snapshot`：`SUCCESS` 寫 `success=true, raw_response_json=<rawResponseJson>, reason=NULL, fetched_at=now(), updated_at=now()`；`FAILURE` 寫 `success=false, raw_response_json=NULL, reason=<reason>, fetched_at=now(), updated_at=now()`。單檔失敗不影響其他檔，也不中止本輪其餘代碼。
  `docker-compose.yml` 的 `business-services` 服務 `environment` 區塊新增一行 `FUBON_ETF_HOLDINGS_SYNC_ENABLED: ${FUBON_ETF_HOLDINGS_SYNC_ENABLED:-false}`（緊鄰既有 `FUBON_TRADE_SYNC_ENABLED` passthrough，第 170 行附近）；`.env`／`.env.example` 新增 `FUBON_ETF_HOLDINGS_SYNC_ENABLED=false`（緊鄰既有 `FUBON_TRADE_SYNC_ENABLED=false`）。

- [ ] **389.7 雷達 ETF 代碼查詢。** 在 `FubonEtfHoldingsSyncService`（或獨立小型 helper，視程式碼整潔度自行判斷，不強制分檔）新增方法，SQL 語意與 `external-materials-service` 的 `StockSourceQuery.collectTwRadarCodes` 完全等價（可用 `JdbcTemplate` 或等價 JPA native query）：
  ```sql
  SELECT stock_code FROM stock_holding WHERE market = '台股'
    AND snapshot_id IN (SELECT DISTINCT ON (owner_user_id) id FROM asset_snapshot
      ORDER BY owner_user_id, snapshot_date DESC, id DESC)
  UNION
  SELECT DISTINCT stock_code FROM stock_alert WHERE market = '台股'
  ```
  排除 `0000`；取得候選集合後，只保留 `MarketDataService.isEtf(code, "台股")` 為 `true`（`code.startsWith("00")`）的代碼。方法簽章建議 `Set<String> collectTwRadarEtfCodes()`，回傳空集合視為合法（雷達目前沒有任何台股 ETF）。

- [ ] **389.8 登錄排程列表與測試。** `SchedulePublicBffController.JOBS` 新增一筆：
  ```java
  new ScheduledJobDto(BUSINESS, "券商庫存", "富邦 ETF 成分股持股同步",
          "以隔離的富邦官方 Linux SDK 唯讀查詢今日交易雷達範圍內的台股 ETF 成分股持股明細，"
                  + "原始回應落地保存供後續解析使用，不影響券商端任何狀態",
          "交易日 08:50、15:30", "0 50 8 * * MON-FRI；0 30 15 * * MON-FRI", TPE),
  ```
  `SchedulePublicBffControllerTest` 的「項目數正確()」測試：**先跑現行測試取得當下真實基準值**（不得假設是某個寫死的數字），總數與 `BUSINESS` 分類數各加一。為 `FubonEtfHoldingsSyncService` 新增單元測試（檔名比照既有慣例：Service 名稱後綴加 Test；mock `FubonBrokerClient`／`FubonConfigState`／`MarketDataService`／repository）：驗證 feature flag off 時 no-op（零呼叫）；`configState` 非 READY 時 no-op；交易日判斷回 `empty`／`false` 時 no-op；雷達代碼為空集合時 no-op；成功／失敗混合回應正確 upsert 對應列且互不影響；超過 50 檔時正確分批。

- [ ] **389.9 不在本次範圍。** 不解析 `raw_response_json` 內容（欄位名稱未核實，不得臆測）；不修改 `MarketDataService.getEtfHoldings()` 既有行為；不刪除 `external-materials-service` 既有 MoneyDJ／Yahoo 爬蟲程式碼；不新增手動觸發端點；不新增使用者可調整的排程時間或觸發按鈕；不做任何下單、改單、圈存或轉帳類 API 呼叫；不影響既有富邦庫存同步／成交同步／TW LIVE 報價／大盤指數串流的邏輯或 feature flag。

## 驗證

```bash
bash scripts/spec-check.sh
cd fubon-broker-service && python -m pytest tests/ -q
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
docker compose -p asset-management build --no-cache fubon-broker-service business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate fubon-broker-service business-services bff
docker compose -p asset-management ps fubon-broker-service business-services bff
curl -s http://localhost:8080/actuator/health
```

任務動到 `backend/src/main/resources/db/changelog/**`，驗證／收尾步驟必須包含重產 `db/schema.sql`：

```bash
bash scripts/tests/schema-sql-drift-test.sh   # 0=同步 1=漂移 2=無法查證
```

實機驗收：`FUBON_ENABLED=true` 但 `FUBON_ETF_HOLDINGS_SYNC_ENABLED` 維持預設 `false` 的情況下，排程不得觸發任何呼叫（可從 `business-services` log 確認零 `ETF_HOLDINGS` 相關訊息）。無法在本次驗收環境模擬真實交易日 08:50／15:30 觸發時，可用既有「免 OAuth 端到端測試」手法或直接呼叫 service 方法（單元測試／臨時本機呼叫）驗證邏輯正確，不強求等到真實時間點觸發；但至少要確認 `fubon_etf_holdings_snapshot` 表結構已建立、`db/schema.sql` 已同步、`SchedulePublicBffController` 的排程列表頁能看到新項目。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
