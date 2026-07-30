# [t259] ETF 折溢價來源標記（`pct_origin`）＋ 台股停止自行反推

**對應 Requirements:** Requirement 34（ETF 淨值與折溢價每日入庫留存——本任務新增其中「折溢價的取得方式必須可從資料本身分辨，且台股不得反推」一條驗收項）
**前置任務:** 無（原記於 `spec/tasks.md` Task 219.2／219.3，動工前查證發現原文兩個前提有誤，已拆分為本任務，見 `tasks.md` Task 219 節的更正說明）
**Liquibase changeset:** `v1.82.0-etf-nav-pct-origin.sql`

## 背景

### 現在的錯誤行為

`spec/requirements.md` 既有 AC 明文「折溢價原樣保存、不由淨值反推」，但實作有兩處破口：

1. **`EtfNavFetchClient.fetchTwAll()` 的 skip 條件放太寬**：
   ```java
   BigDecimal nav = parseDecimal(item.path("f").asText(""));
   BigDecimal pct = parseDecimal(item.path("g").asText(""));
   if (nav == null && pct == null) continue;
   out.put(code, new EtfNav(code, "台股", nav, pct, asOf, "TWSE"));
   ```
   條件是 **AND**：淨值 `f` 有值、折溢價 `g` 留白時（`pct == null`），這筆仍會被送進下游——這本身沒錯（淨值有意義，值得保留），但下游沒有守住「折溢價缺就是缺」這件事。

2. **`EtfNavPoller.resolvePct()` 對所有市場一視同仁地反推**：
   ```java
   private java.math.BigDecimal resolvePct(EtfNav nav, LocalDate navDate) {
       if (nav.premiumDiscountPct() != null) return nav.premiumDiscountPct();
       java.math.BigDecimal close = source.findCloseOn(nav.stockCode(), nav.market(), navDate).orElse(null);
       if (close == null || nav.nav().compareTo(java.math.BigDecimal.ZERO) == 0) return null;
       return close.subtract(nav.nav())
               .multiply(java.math.BigDecimal.valueOf(100))
               .divide(nav.nav(), 4, java.math.RoundingMode.HALF_UP);
   }
   ```
   台股 `g` 欄留白時同樣落入這條反推路徑，用 `stock_price_history` 同日收盤價算出一個值——這正是 AC 明文禁止的「自行以 (市價−淨值)/淨值 反推」，理由是台股淨值欄在股票型 ETF 已四捨五入至小數 2 位，反推誤差達 0.07 個百分點，與證交所公告值對不上。

3. **`upsertEtfNav` 寫入的 `source` 欄無法分辨這兩種情況**：不論是證交所直接給的折溢價、還是本系統反推出來的，`source` 欄都只記錄「淨值是哪個站台給的」（`TWSE`／`Yahoo Finance`），DB 上完全看不出某一列的折溢價是權威值還是猜的。

實測（2026-07-30，`asset-postgres`，全部唯讀）：`etf_nav_history` 現有 186 列（台股 15 檔 ×10 天、美股 4 檔 ×9 天），`premium_discount_pct` 無一為 null——即目前每一筆折溢價，不論台股或美股，都必然來自上述某條路徑；而美股因 Yahoo 完全不提供折溢價欄，186 列中的美股那 36 列（4 檔 ×9 天）**100% 是反推值**，卻同樣標記 `source='Yahoo Finance'`，與台股的權威值在 DB 上無法區分「這欄可不可信」。

### 為什麼原本以為要新增 `source` 欄位是錯的

`spec/tasks.md` 原 Task 219.2 寫「`etf_nav_history` 增加 `source` 欄位區分 OFFICIAL／RECONSTRUCTED」。動工前查證發現：**`source` 欄位在建表 changeset（`v1.65.0-etf-nav-history.sql`，spec 舊文誤記為 `v1.63.0`）就已存在**，且從第一天就被寫入為「提供淨值的站台名稱」（`TWSE`／`Yahoo Finance`）。這是兩個獨立的軸：

- `source`＝**淨值從哪個站台抓來的**（既有語意，186 列已有值，不回填不改寫）
- `pct_origin`（本任務新增）＝**折溢價這個數字是誰給的**：來源直接公告（`OFFICIAL`）還是本系統反推（`RECONSTRUCTED`）

兩者不可合併成一欄——例如「SITCA 淨值配 TWSE 收盤價重建」這種未來可能出現的組合，`source` 答不出「這個折溢價可不可信」，`pct_origin` 才答得出。

### 為什麼美股仍要保留反推（不能兩個市場一起關掉）

Yahoo `quoteSummary` 完全不提供折溢價欄位，反推是美股「股票（即時）ETF 淨值與折溢價欄」（Requirement 34／Task 214）目前**唯一**的取得方式。若連美股也關閉反推，該功能對美股會整個消失，這超出本任務要修的範圍（本任務只補「無法分辨、可能覆寫權威值」這個誠實性缺口，不是重新設計折溢價取得策略）。故美股續行反推，但必須誠實標記為 `RECONSTRUCTED`。

## 要做什麼

### 259.1 Liquibase：新增 `pct_origin` 欄位

- [x] 259.1 新增 `backend/src/main/resources/db/changelog/changes/v1.82.0-etf-nav-pct-origin.sql`：
  ```sql
  --liquibase formatted sql

  --changeset steven:v1.82.0-etf-nav-pct-origin
  -- Requirement 34 / Task 259：折溢價來源標記，區分「來源直接公告」與「本系統反推」。
  -- 冪等（IF NOT EXISTS）：全機共用一套運行中 DB、多 worktree 並行，本 changeset 可能已被別的分支套用；
  -- 非冪等即 already exists → business-services / external-materials-service crash loop 整站掛（Task 207 教訓）。
  -- 不回填既有 186 列：這些列抓取當時未記錄「怎麼算出來的」，無法回溯判斷，維持 NULL 是誠實狀態。
  ALTER TABLE etf_nav_history ADD COLUMN IF NOT EXISTS pct_origin VARCHAR(20);
  ```
  - [x] 259.1.1 **不加 CHECK 約束**：值域（`OFFICIAL`／`RECONSTRUCTED`／`NULL`）由程式碼決定，比照本表既有 `source` 欄（同樣是無約束的 `VARCHAR`）與其他「來源標記」欄位（如 `realized_gain.broker`）的既有風格——這是資料來源標記，不是使用者可自訂的業務分類，不入 `/api/settings/*`、不做管理端點、不違反「禁止 Enum 寫死」規範（那條規範管的是業務分類，不是抓取管線內部記帳）。
  - [x] 259.1.2 在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端加一個 `include`（沿用既有格式，`relativeToChangelogFile: false`）。
  - [x] 259.1.3 建檔前務必跑 `bash scripts/spec-check.sh` 確認 `v1.82.0` 未被其他 worktree 搶號；若已被占用則依專案慣例避讓至下一個可用版號並在此任務檔與 `spec/design.md` 同步更正。

### 259.2 `EtfNavPoller`：折溢價依 market 分流，且拆出可測試的接縫

- [x] 259.2 修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/EtfNavPoller.java`：
  - [x] 259.2.1 新增 **package-private static** record：`record PremiumResult(java.math.BigDecimal pct, String origin) {}`。
  - [x] 259.2.2 把現行 `private java.math.BigDecimal resolvePct(EtfNav nav, LocalDate navDate)` 改為 **package-private static** 方法 `resolvePremium`，簽名：
    ```java
    static PremiumResult resolvePremium(
            EtfNav nav, LocalDate navDate,
            java.util.function.BiFunction<String, LocalDate, java.util.Optional<java.math.BigDecimal>> closeLookup)
    ```
    以函式參數取代直接呼叫 `source.findCloseOn(...)`，使其可脫離 Spring context 單元測試（比照 `ClosePersister.shouldDumpPayload` 的既有接縫模式：兩者都是「守門邏輯抽成 static 純函式、外部依賴改用函式參數注入」）。
  - [x] 259.2.3 判定邏輯（**三個分支，依序判斷**）：
    1. `nav.premiumDiscountPct() != null` → `new PremiumResult(該值, "OFFICIAL")`。實測現況下僅台股（`g` 欄有值時）會落在此分支——美股抓取端（`MarketDataFetchService.getUsEtfNav`）本就固定回傳 `null` 折溢價。
    2. 否則，`"台股".equals(nav.market())` → `new PremiumResult(null, null)`。**不得呼叫 `closeLookup`**——這是本任務要關閉的反推路徑。
    3. 否則（美股，`premiumDiscountPct() == null`）→ 沿用現行反推公式（`closeLookup.apply(nav.stockCode(), navDate)`，查無收盤價或 `nav.nav()` 為 0 時回 `new PremiumResult(null, null)`），有值則回 `new PremiumResult(反推值, "RECONSTRUCTED")`。
    - **`closeLookup` 的簽名只帶「代號」「日期」兩個維度，`market` 不在其中**——但 `StockSourceQuery.findCloseOn(String stockCode, String market, LocalDate tradingDate)` 是三參數方法。呼叫處**不得**直接寫方法參考 `source::findCloseOn`（arity 不符會編譯失敗），必須用 lambda 綁定當下的 `nav.market()`（見 259.2.4）。
  - [x] 259.2.4 `persist()` 呼叫處改為：
    ```java
    PremiumResult premium = resolvePremium(nav, navDate,
            (code, date) -> source.findCloseOn(code, nav.market(), date));
    source.upsertEtfNav(nav.stockCode(), nav.market(), navDate,
            nav.nav(), premium.pct(), premium.origin(), nav.source());
    ```
  - [x] 259.2.5 既有 javadoc（原「入庫用的折溢價（Task 215）」段）改寫以反映新行為：明確寫出「台股不反推、美股反推並標記 RECONSTRUCTED」，並保留原文對「為何不用即時價／不用前一日淨值」的既有說明（那兩條理由依然成立，未被本任務推翻）。

### 259.3 `StockSourceQuery.upsertEtfNav`：新增 `pct_origin` 參數與覆寫守門

- [x] 259.3 修改 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/StockSourceQuery.java` 的 `upsertEtfNav`：
  - [x] 259.3.1 簽名新增 `String pctOrigin` 參數（緊接在 `premiumDiscountPct` 之後）：
    ```java
    public void upsertEtfNav(String stockCode, String market, LocalDate navDate,
                             BigDecimal nav, BigDecimal premiumDiscountPct, String pctOrigin, String source)
    ```
  - [x] 259.3.2 **覆寫守門**：寫入前先查既有列的 `pct_origin`：
    ```java
    String existingOrigin = jdbc.query(
            "SELECT pct_origin FROM etf_nav_history WHERE stock_code=? AND market=? AND nav_date=?",
            ps -> { ps.setString(1, stockCode); ps.setString(2, market); ps.setObject(3, navDate); },
            rs -> rs.next() ? rs.getString(1) : null);
    boolean blockOverwrite = "OFFICIAL".equals(existingOrigin) && "RECONSTRUCTED".equals(pctOrigin);
    ```
    `blockOverwrite` 為真時，UPDATE 語句**不含** `premium_discount_pct`／`pct_origin` 兩欄（維持既有值），`nav`／`source` 兩欄仍照常更新——`source` 描述的是「這次抓到淨值的站台」，與折溢價來源無關，此分支不需要一併凍結它。`blockOverwrite` 為假時（含 `existing == null` 全新列的情況）四欄皆正常寫入。以 `blockOverwrite` 的布林值分支寫兩條不同的 `jdbc.update(...)` SQL（比照現行 `existing != null` vs `else` 已經是兩條不同 SQL 的既有寫法，非新手法）。
  - [x] 259.3.3 INSERT 分支新增 `pct_origin` 欄（無需守門判斷，新列必為首次寫入）。
  - [x] 259.3.4 此情境在正常抓取流程下**不會發生**（台股不再反推、美股從未產生 `OFFICIAL`）——守門是防禦未來新增資料來源（如 219.1 的 SITCA）時的誤用，不是本任務要處理的當前 bug。

### 259.4 `ExcelExportService`：匯出端折溢價同步關閉台股反推

- [x] 259.4 修改 `backend/src/main/java/com/steven/assets/service/ExcelExportService.java` 的 `premiumDiscountPct(...)` 方法：
  - [x] 259.4.1 現行簽名 `private static BigDecimal premiumDiscountPct(PriceQueryService.EtfNav nav, BigDecimal livePrice)` 改為 **package-private static**（原為 `private static`），以利單元測試。
  - [x] 259.4.2 新增市場分流：`nav.premiumDiscountPct() != null` 時直接回該值（既有行為不變）；否則若 `"台股".equals(nav.market())` 回 `null`（**不反推**）；否則（美股）沿用現行以 `livePrice` 反推的公式（`scale=2`，與 `resolvePremium` 的 `scale=4` 刻意不同——前者是「盤中折溢價」用列上即時價、後者是「收盤折溢價」用同交易日收盤價，兩個不同事實，不統一）。
  - [x] 259.4.3 **不需要**在此處輸出 `pct_origin`（Excel 匯出的資料來源是 Redis 的 `EtfNav`，不是 `etf_nav_history`，本任務不擴充匯出欄位；`pct_origin` 只落在入庫路徑）。

### 259.5 單元測試

- [x] 259.5 在 `external-materials-service/src/test/java/com/steven/assets/externalmaterials/service/` 下新增一支測試類（ext 目前對 `EtfNav` 全鏈路**零測試覆蓋**，本任務是第一支；實際檔名由實作者依 ext 既有測試檔命名慣例決定，完成後回填本段與完成報告）：
  - [x] 259.5.1 **台股、來源已有折溢價** → `resolvePremium` 回 `PremiumResult(該值, "OFFICIAL")`，且 `closeLookup` 完全不被呼叫（`verify(closeLookup, never()).apply(any(), any())`，用 mock 的 `BiFunction`）。
  - [x] 259.5.2 **台股、折溢價欄留白** → 回 `PremiumResult(null, null)`，`closeLookup` 完全不被呼叫。**這是本任務的核心回歸錨點**：沒有它，未來有人把台股分支拿掉不會被任何測試發現。
  - [x] 259.5.3 **美股、折溢價欄留白、`closeLookup` 回有效收盤價** → 回 `PremiumResult(反推值, "RECONSTRUCTED")`，反推值以既有公式手算驗證（`(close-nav)/nav*100`，scale 4，HALF_UP）。
  - [x] 259.5.4 **美股、`closeLookup` 回 empty** → 回 `PremiumResult(null, null)`。
  - [x] 259.5.5 **美股、`nav.nav()` 為 0** → 回 `PremiumResult(null, null)`，不擲除以零例外。
  - [x] 259.5.6 **`upsertEtfNav` 覆寫守門**：以 `JdbcTemplate` mock 驅動，既有列 `pct_origin='OFFICIAL'`、本次呼叫傳入 `pctOrigin='RECONSTRUCTED'` → 斷言 UPDATE 語句的 SQL 字串**不含** `premium_discount_pct`／`pct_origin`（或斷言傳入 `jdbc.update` 的參數不含這兩個新值，依實作手法擇一）。
  - [x] 259.5.7 **`upsertEtfNav` 正常寫入**：既有列 `pct_origin=null` 或全新列，本次傳入任意 `pctOrigin` → 斷言三欄皆正常寫入。
  - [x] 259.5.8 **`ExcelExportService.premiumDiscountPct` 台股分支**：`nav.premiumDiscountPct()==null` 且 `market=="台股"` → 回 `null`，不使用 `livePrice`。
  - [x] 259.5.9 **`ExcelExportService.premiumDiscountPct` 既有行為不回歸**：美股分支與「來源已有值」分支維持原有計算結果（可直接沿用既有若有的手算案例，或依現行公式重新手算）。

## 驗證

```bash
# 1) 單元測試（本機 JVM 為 Java 25，pom 的 java.version 21 只是編譯目標，
#    Mockito 需 byte-buddy experimental；絕不可用 -DargLine，會覆蓋 pom 既有的時區設定）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2) 只跑本任務新增的測試（實作時填入 259.5 實際建立的類名）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f external-materials-service/pom.xml \
  test -Dtest=<259.5 實際類名> -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 3) 重建並 recreate（JVM service 必須 --no-cache，否則 layer cache 會出 stale jar）
#    從 worktree 跑 compose 前先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service

# 4) 確認容器健康且 schema 已套用
docker ps --filter name=asset-external-materials-service --format '{{.Names}} {{.Status}}'
docker exec asset-postgres psql -U assets -d assets -c '\d etf_nav_history'
docker exec asset-postgres psql -U assets -d assets -t -c \
  "SELECT id FROM databasechangelog WHERE id LIKE 'v1.82.0%'"

# 5) 跑起來真的有這個功能：手動觸發一次刷新，觀察新列的 pct_origin
docker exec -it asset-external-materials-service curl -s -X POST http://localhost:8081/internal/etf-nav/refresh
sleep 3
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT stock_code, market, nav_date, premium_discount_pct, pct_origin, source
     FROM etf_nav_history WHERE nav_date = CURRENT_DATE ORDER BY market, stock_code"
# 預期：台股列若 g 欄留白，premium_discount_pct 與 pct_origin 皆為 NULL（不得是反推值）；
#       美股列 pct_origin 一律為 RECONSTRUCTED（除非 Yahoo 哪天開始提供折溢價欄，屬未來變化）。

# 6) 覆寫守門的實機驗證：找一筆既有的台股 OFFICIAL 列，確認手動觸發不會把它變成 RECONSTRUCTED
docker exec asset-postgres psql -U assets -d assets -t -c \
  "SELECT stock_code, nav_date, pct_origin FROM etf_nav_history
     WHERE market='台股' AND pct_origin='OFFICIAL' ORDER BY nav_date DESC LIMIT 3"
# 重跑第 5 步後上述查詢的 pct_origin 應仍為 OFFICIAL、值不變。
```

## 完成報告

**狀態：已實作、單元測試全綠、架構符規查證通過（0 critical/major/minor）。部署驗證待 merge 進 main 後從 main 的 worktree 執行**（依既有教訓：全機共用一套 `asset-*` 容器，在 feature worktree 建置有被其他並行 worktree 洗掉的風險，見 `feedback_shared_stack_tag_clobber`）。

**實際改的檔（7 個）**

| 檔 | 改動 |
|---|---|
| `backend/.../db/changelog/changes/v1.82.0-etf-nav-pct-origin.sql` | 新增，`etf_nav_history` 加 `pct_origin VARCHAR(20)`（冪等 `IF NOT EXISTS`） |
| `backend/.../db/changelog/db.changelog-master.yaml` | 尾端加一個 `include` |
| `external-materials-service/.../service/EtfNavPoller.java` | `resolvePct`（private instance）改名 `resolvePremium`（package-private static），新增 `record PremiumResult(BigDecimal pct, String origin)`；依 market 分流（台股不反推）；`source.findCloseOn` 呼叫改函式參數注入；`persist()` 呼叫處同步更新 |
| `external-materials-service/.../service/StockSourceQuery.java` | `upsertEtfNav` 簽名新增 `pctOrigin` 參數；新增覆寫守門（既有 `OFFICIAL` 不被 `RECONSTRUCTED` 覆寫，`nav`／`source` 不受影響）；INSERT 分支帶入新欄 |
| `backend/.../service/ExcelExportService.java` | `premiumDiscountPct` 改 package-private static；新增台股分支（折溢價欄留白時回 `null`，不用即時價反推） |
| `external-materials-service/.../service/EtfNavPremiumOriginTest.java` | 新增，8 個 `@Test`：`resolvePremium` 三分支（台股 OFFICIAL／台股留白不反推／美股反推 RECONSTRUCTED／美股查無收盤價／美股淨值為零）、`upsertEtfNav` 覆寫守門（既有 OFFICIAL 不被覆寫／既有列無 pct_origin 正常寫入／全新列 INSERT） |
| `backend/.../service/ExcelExportPremiumOriginTest.java` | 新增，4 個 `@Test`：台股留白回 null、來源已有值直接回傳、美股仍反推、淨值為零不擲例外 |

**驗證輸出**

- `mvn -f external-materials-service/pom.xml compile`：成功。
- `mvn -f external-materials-service/pom.xml test`（`-DextraArgLine=-Dnet.bytebuddy.experimental=true`）：**148/148 通過**，含新增 `EtfNavPremiumOriginTest` 8/8；既有 147 個測試無回歸。
- `mvn -f backend/pom.xml test`（同旗標）：**346/346 通過**，含新增 `ExcelExportPremiumOriginTest` 4/4；既有 342 個測試無回歸。
- `arch-auditor` 唯讀架構符規查證：0 critical / 0 major / 0 minor，記錄通過（`.claude/hooks/arch-review-pass.sh`）。查證重點包括 `pct_origin` 冗餘性（結論：與既有同表 `source` 欄同一正當化邏輯，非新增違規）、`upsertEtfNav` 的 SELECT-then-UPDATE 模式（結論：既有六支姊妹方法皆同構，非新增風險）、覆寫守門逐分支驗證（`existing==null`／`existingOrigin==null`／`existingOrigin=="RECONSTRUCTED"`／`existingOrigin=="OFFICIAL"&&pctOrigin=="RECONSTRUCTED"` 四種邊界皆與 spec 意圖相符）。
- `v1.82.0` 版號未撞號：`ls backend/.../changes/` 最大為 `v1.81.0`；`SELECT id FROM databasechangelog WHERE id LIKE 'v1.82%'` 於運行中 DB 回 0 rows（尚未套用）。
- `resolvePct`／舊 6 參數 `upsertEtfNav`：全樹（`backend/`／`external-materials-service/`／`bff/`）`grep -ran` 零殘留呼叫。

**部署驗證（2026-07-30，從 main 的 worktree 執行，皆已完成）**

1. `docker compose -p asset-management build --no-cache business-services external-materials-service` ＋ `up -d --no-deps --force-recreate` 兩者；`docker compose restart bff`（Task 208 教訓）。三個容器（`asset-business-services`／`asset-external-materials-service`／`asset-bff`）皆為 `Up ... (healthy)`。
2. `SELECT id FROM databasechangelog WHERE id LIKE 'v1.82%'` → 回一筆 `v1.82.0-etf-nav-pct-origin`。
3. `\d etf_nav_history` → 確認新增 `pct_origin | character varying(20)` 欄。
4. 手動觸發 `POST /internal/etf-nav/refresh` 後查當日（2026-07-30）新列：**台股 15 檔全為 `OFFICIAL`**（當日證交所 `g` 欄皆有值，故未實測到「留白不反推」這條路徑——單元測試 259.5.2 已覆蓋該分支，非本次部署驗證缺口）；**美股 4 檔全為 `RECONSTRUCTED`**。既有 186 列（2026-07-17～07-29）`pct_origin` 皆維持 `NULL`（未回填，符合設計）；全表列數仍為 186（今日列為既有排程 upsert 更新，非新增列），無資料損毀。
5. 覆寫守門在本次部署未被自然觸發（首輪部署前所有既有列 `pct_origin` 皆為 `NULL`，非 `OFFICIAL`，故 `blockOverwrite` 條件不成立）；該分支已由單元測試 259.5.6 以 mock 精確覆蓋，不需在生產資料中人為造出 `OFFICIAL→RECONSTRUCTED` 的衝突情境來驗證。

**已知的實作缺口（Post-commit 補記）**：初次 `git commit` 時因未在編輯完成報告後重新 `git add -A` 就直接 `git commit`，導致該次提交（`add59ad0`／merge `50e0b08d`）只含程式碼與 spec 主體變更，**本任務檔的 checkbox 勾選與本段完成報告內容未被帶入**；已於本次補提交修正（commit 訊息另見 git log）。

**與原計畫的偏差**

1. **`upsertEtfNav` 的實作細節與任務檔描述略有出入**：任務檔 259.3.2 的示意程式碼寫「單一查詢」，實作為與既有 `upsertHistory` 等姊妹方法一致的兩段式（先 `SELECT id` 判斷存在、再依需要 `SELECT pct_origin`），沿用檔案既有慣例而非另創新模式。效果與 spec 意圖一致，經 arch-auditor 確認非新增風險。
2. **測試分成兩個模組兩支檔案**：任務檔 259.5 原文只提到 `external-materials-service` 一支新測試類，因 `ExcelExportService` 實際在 `backend` 模組（259.4 已同步更正該路徑），故 259.4 對應的測試（259.5.8／259.5.9）另立於 `backend/src/test/.../ExcelExportPremiumOriginTest.java`，未違反任何鐵則、只是照模組邊界拆檔。
3. **259.1 的 `spec-check.sh` 建檔前查證**：實際查證顯示 `v1.82.0` 全程未被撞號，未觸發避讓流程。
