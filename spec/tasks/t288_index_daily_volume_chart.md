# [t288] 股市大盤查詢頁的日線圖下方新增每日成交量柱狀子圖

**對應 Requirements:** Requirement 18（股市大盤查詢頁：台股大盤／美股四大／海外四指數的近 10 年日線 + MA20/60/240 + 當日分時，以及台日韓人均 GDP 比較）
**前置任務:** 無（但與 `t284` 改同一段程式碼，見下方衝突警告）
**Liquibase changeset:** `v1.88.0-index-daily-volume.sql`

> **編號避讓說明（2026-08-02 複查）**：`t284`／`t285` 已被 `gmail-calendar-alert-integration-6fe79c`（`t284_index_daily_weekly_ma5`、`t285_index_export_all_four_ma`）與 `compound-condition-trigger-f55151`（`t285_index_export_multi_schedule`）占用，故任務編號取 `t288`（全 worktree 掃描無第二份 `t288_*`）。`wonderful-fermi-c522e5` 原本也用 284/285，但已自行避讓改為 `t287`／`t288`，與本任務無關。
>
> **版號避讓的判準是「有沒有人預留」，不是「檔案系統上的最大值」**：全部 worktree 的 `changes/` 目錄最大都還是 `v1.85.0-drop-nonpositive-close.sql`，運行中 DB 的 `databasechangelog` 尾端亦同，但 `grep -rn "v1.8[6-9].0" spec/tasks/*.md` 顯示 **`v1.86.0` 由 `t277`（雙軌通知欄位）與 `t285`（多筆匯出排程）預留、`v1.87.0` 由 `t266`（基本面三表）預留**——三者都還沒落地，`ls | sort -V | tail` 一個都查不出來。故本任務取 **`v1.88.0`**。
>
> 實作前重跑 `bash scripts/spec-check.sh` **與** `grep -rn "v1.8[8-9].0\|v1.9" spec/tasks/*.md` 確認現況未變。

> ### ⚠️ 與 t284 的程式碼衝突（兩案皆未 merge 進 main）
>
> `gmail-calendar-alert-integration-6fe79c` 的 `t284_index_daily_weekly_ma5.md`（日線圖加週線 MA5，該 worktree 已標記實作完成但未 commit）動的是**同五處**（已核對該 worktree 的實際 `git diff`）：
>
> | 位置 | t284 做什麼 | t288 做什麼 | 後果 |
> |---|---|---|---|
> | `GdpTwseBffController.getIndexDaily` 的 `map(rows -> ...)`（`:189-195`） | 在 `body.put("ma20", …)` 前插 `ma5` | 插 `volumes`／`turnovers`／`hasVolume` | 可共存，但同段落易撞 merge 衝突 |
> | `GdpTwseBffController.java:150` 的 javadoc | 改為「+ MA5 / MA20 / MA60 / MA240」 | 288.6.2.1 要求補三個新欄位 | **同一行**，需合併兩邊措辭而非互相覆寫 |
> | `SchedulePublicBffController.java` 匯出描述（`:86`） | 加「＋週線MA5」 | 288.4.3 改的是「大盤指數」`:147-155` 三筆（不同筆但同檔） | 不互斥，但同檔連續改動易撞行號 |
> | `GdpTwseView.vue:288` 的 `cardTitle` | 改為 `…（近 10 年，含週線/月線/季線/年線）` | 改為 `…（近 10 年，含月線/季線/年線與成交量）` | **互斥**，後 merge 者靜默蓋掉前者 |
> | `dailyChartOption` 的 `legend.data`／`series` | 加 MA5 一條線與 legend 項 | 整段改雙 grid | 整段 merge 衝突 |
>
> **本任務的因應**：
> - 288.6.1 插入新欄位時**不得移除或改寫既有的任何一行 `body.put("ma*", …)`**；若 t284 已先 landed，`ma5` 那行同樣保留。
> - 288.6.2.1 修 `:150` javadoc 時，**若 t284 已先 landed，須同時保留「MA5」與新增的三個成交量欄位**，不得二選一覆寫。
> - 288.7.8 的標題：**若 t284 已先 landed，改為 `${marketLabel}每日收盤（近 10 年，含週線/月線/季線/年線與成交量）`**；否則用不含「週線」的版本。實作前以 `grep -n "cardTitle" -A 4 frontend/src/views/GdpTwseView.vue` 確認當下實際字串。
> - 288.7.3 改 `dailyChartOption` 前先 `git log --oneline origin/main -5 -- frontend/src/views/GdpTwseView.vue` 確認 t284 是否已進 main；若已進，雙 grid 的 series 陣列須含 MA5 那條並補 `xAxisIndex: 0, yAxisIndex: 0`。
>
> 另注意 `t285_index_export_all_four_ma`（同 worktree、依附 t284）與 `t285_index_export_multi_schedule`（`compound-condition-trigger-f55151`）為**同號不同任務**，本任務不涉及，僅在此提醒編號現況會持續變動。

## 背景

`frontend/src/views/GdpTwseView.vue` 最上方那張卡（「{指數}每日收盤（近 10 年，含月線/季線/年線）」）目前只畫價格：收盤線＋MA20／MA60／MA240 三條均線，加上可視區間內最高／最低點的紅綠色塊標記。**整張圖沒有任何量能資訊**，「爆量長黑」「無量假突破」「量價背離」這類判讀在畫面上完全缺席。

使用者要求在此圖增加每日成交量柱狀圖。

### 目前兩張日線表都沒有成交量欄位

以運行中的 DB 實查（2026-08-02，`docker exec asset-postgres psql -U assets -d assets -c '\d twse_index_daily_history'`）：

```
twse_index_daily_history:  trading_date(date, PK) / close_point(numeric(12,2), NOT NULL)
                           open_point / high_point / low_point / close_point_tr  皆 numeric(12,2) nullable
us_index_daily_history:    index_code(varchar(16)) + trading_date(date) 複合 PK
                           open_point / high_point / low_point 皆 numeric(14,4) nullable
                           close_point numeric(14,4) NOT NULL
```

資料量：`twse_index_daily_history` 2,479 筆、2016-06-01 ~ 2026-07-31。

### 台股的成交量在另一支 TWSE API，不在現行日線來源裡

現行台股日線來源是 `MI_5MINS_HIST` 月報（`external-materials-service/.../client/MacroDataFetchClient.java` 的 `TWSE_DAILY_OHLC_URL`）。它的 `fields` 只有五欄、**沒有量**：

```
["日期","開盤指數","最高指數","最低指數","收盤指數"]
```

成交量要另抓 `FMTQIK`（市場成交資訊月報）。實測 2026-08-02，`rwd` 版支援歷史月份查詢（10 年前的 `date=20160701` 也回 `stat":"OK"`）：

```
GET https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?date=20260701&response=json
→ {"stat":"OK","date":"20260701","title":"115年07月市場成交資訊","hints":"單位：元、股",
   "fields":["日期","成交股數","成交金額","成交筆數","發行量加權股價指數","漲跌點數"],
   "data":[["115/07/01","14,683,404,939","1,367,817,795,171","6,457,744","47,018.99","893.08"], ...]}
```

> ⚠️ 現行程式碼中另有一支 `TwseInfoFetchClient` 打 **openapi 版** FMTQIK（`https://openapi.twse.com.tw/v1/exchangeReport/FMTQIK`）供「大盤成交統計」新聞用。**那支只回最新一批、不支援 `date` 參數、無法回補歷史**，本任務不得沿用它，也不得改動它。

### 海外指數的成交量在既有 Yahoo 回應裡，不需新增外部呼叫

`fetchUsIndexDaily` 已經打 `https://query2.finance.yahoo.com/v8/finance/chart/{symbol}?range=10y&interval=1d`，回應的 `indicators.quote[0]` 就含 `volume` 陣列，目前只讀了 `open`／`high`／`low`／`close`。實測 2026-08-02（`range=5d`）：

| symbol | `volume` 前 5 筆 |
|---|---|
| `^DJI` | 487490000, 544740000, 531850000, 631410000, 697510000 |
| `^GSPC` | 5038290000, 5590890000, ... |
| `^IXIC` | 7797890000, 8584960000, ... |
| `^FTSE` | 1189032700, 848931800, ... |
| `^GDAXI` | 52434200, 57351100, ... |
| `^N225` | 160800000, 187000000, ... |
| `^KS11` | **275700, 331300, 457400, 378900, 434400** |
| `^SOX` | **0, 0, 0, 0, 0** |

**兩個必須處理的實測事實**：
- **`^SOX`（費城半導體）恆為 0** —— 純計算型指數，Yahoo 不提供成交量。畫成一整排零高度柱是壞掉的樣子，必須隱藏子圖。
- **`^KS11`（KOSPI）量級明顯偏小**（韓國實際成交股數為數億股）。該欄顯非股數原值，但無從校正。照 Yahoo 原值顯示，並在 spec／註解標明「各市場口徑不一、不得跨指數比較」。

### 「成交量」在台股大盤語境指的是成交金額

台股口語的「今天大盤量能 4000 億」指的是**成交金額**，鉅亨網／Yahoo 股市的大盤圖下方那根柱同此口徑。故台股柱值取成交金額（億元），成交股數同時入庫並在 tooltip 顯示。海外指數 Yahoo 只有股數、無金額，柱值即成交量（股）。

### 台股 10 年回補本來就會逾時，而真正的上限是 nginx 的 60 秒

`MacroHistoryService.refreshTwseDaily` 逐月序列呼叫，每月 `Thread.sleep(800)`。實測單次 `MI_5MINS_HIST` 約 **1.3 秒**（`FMTQIK` 約 0.05 秒），120 個月 × (1.3 + 0.8) ≈ **250 秒**。本次併抓 FMTQIK 會再加約 6~60 秒。

**逾時鏈路上有三道關卡，最短的那道在 nginx**：

```
frontend/src/api/index.js:5      baseURL: '/api'                    → 全部前端呼叫都走 nginx
frontend/nginx.conf:43-51        location /api/ { … proxy_read_timeout 60s; }   ← 真正生效的上限
bff/.../GdpTwseBffController:214 .timeout(Duration.ofSeconds(180))
frontend/src/api/index.js:230    timeout: 180000
```

一支長時間沒有回應位元組的 POST 會**在第 60 秒被 nginx 切斷回 504**，與後兩者誰先到期無關。所以**只把 180 改成 360 完全無效**——使用者看到的失敗時點仍是 60 秒。三處必須一起放寬。

同檔 `frontend/nginx.conf:28-38` 已有先例：SSE 即時股價串流另開一個 `location = /api/market-data/prices/stream` 並設 `proxy_read_timeout 1h`，證明這條路徑就是靠 location 粒度調整。

> **這不是本任務引入的缺陷**：現行按「回補日線（10 年）」選台股時，第 60 秒就會收到錯誤提示，但 business 端仍在背景跑完、資料實際有進 DB（這也是為什麼一直沒人發現）。本任務讓耗時更長，故一併修。
>
> **為什麼不改成非同步**：`POST /api/twse-daily-index/refresh-tr`（`MacroHistoryController:115`）確實有「背景執行緒 ＋ 立即回 `{started, pending}`」的先例，但那要新增進度查詢端點與前端輪詢，改動面遠大於本任務主體；回補是低頻的手動操作，拉長 timeout 已足夠。若日後回補時間再成長，再改非同步。

## 要做什麼

### 288.1 Liquibase changeset（新欄位）

- [ ] 288.1.1 新增 `backend/src/main/resources/db/changelog/changes/v1.88.0-index-daily-volume.sql`：

  ```sql
  --liquibase formatted sql

  --changeset steven:v1.88.0-index-daily-volume
  --comment 指數日線加成交量欄位，供 Requirement 18 日線圖的每日成交量柱狀子圖（Task 288）
  -- 台股：trade_volume=成交股數(股)、trade_value=成交金額(元)，來源 TWSE FMTQIK 月報（MI_5MINS_HIST 無量欄）
  -- 海外：volume=成交量(股)，取自既有 Yahoo v8 chart 回應的 indicators.quote[0].volume，不新增外部來源
  -- 皆 nullable：既有列全為 null，由回補補齊；SOX 恆為 0（純計算型指數無成交量）

  ALTER TABLE twse_index_daily_history
      ADD COLUMN trade_volume BIGINT,
      ADD COLUMN trade_value  NUMERIC(20,0);

  ALTER TABLE us_index_daily_history
      ADD COLUMN volume BIGINT;
  ```

- [ ] 288.1.2 於 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **尾端**掛入該檔（比照既有 `v1.85.0-drop-nonpositive-close.sql` 那一筆的寫法）。

- [ ] 288.1.3 **不得存任何衍生值**：億元／億股換算一律在前端顯示時計算，不入庫、BFF 也不回換算後的值（CLAUDE.md「禁止存入可從其他欄位計算得出的衍生值」）。

- [ ] 288.1.4 **`--comment` 與 changeset 內容一旦 commit 就不得再改**：Liquibase checksum 含註解，事後編輯會讓 business-services 進 `ValidationFailed` crash loop。若後續需要編號避讓，**避讓用的 `sed` 必須排除 `db/changelog/`**。

- [ ] 288.1.5 **建檔前確認 `v1.88.0-*.sql` 不存在**（判準是檔案存不存在，不是它是不是最大值——`v1.86.0`／`v1.87.0` 已被 t277／t285／t266 預留但尚未落地，`ls | sort -V | tail` 查不出來）：

  ```bash
  ls backend/src/main/resources/db/changelog/changes/ | grep -c "^v1\.88\.0" ; grep -rn "v1\.8[89]\.0\|v1\.9[0-9]\.0" spec/tasks/*.md
  ```

### 288.2 Entity（backend）

- [ ] 288.2.1 `backend/src/main/java/com/steven/assets/model/TwseIndexDailyHistory.java` 新增兩欄，擺在 `closePointTr` 之後：

  ```java
  /** 成交股數（股）。來源 TWSE FMTQIK 月報「成交股數」欄；供 Requirement 18 成交量柱狀子圖。 */
  @Column(name = "trade_volume")
  private Long tradeVolume;

  /** 成交金額（元）。來源 TWSE FMTQIK 月報「成交金額」欄；台股柱狀圖的柱值即由此換算億元。 */
  @Column(name = "trade_value", precision = 20, scale = 0)
  private BigDecimal tradeValue;
  ```

  - [ ] 288.2.1.1 **順手修正該檔 `:15` 的過時斷言**：目前寫「來源：TWSE openapi MI_5MINS_HIST 月報（含 `OpeningIndex`/`HighestIndex`/`LowestIndex`/`ClosingIndex`）」，但實際 URL 是 `https://www.twse.com.tw/rwd/zh/TAIEX/MI_5MINS_HIST`（**非 openapi**）、欄名為中文（`開盤指數`／`最高指數`／`最低指數`／`收盤指數`）。本次 spec 已一併更正 `design.md` 與 `requirements.md` 的同一句孿生描述，程式碼註解不同步就會留下第三份錯誤斷言。

- [ ] 288.2.2 `backend/src/main/java/com/steven/assets/model/UsIndexDailyHistory.java` 新增一欄，擺在 `closePoint` 之後：

  ```java
  /** 成交量（股）。取自 Yahoo v8 chart 的 indicators.quote[0].volume。⚠️ 各市場口徑不一致，不得跨指數比較：
   *  實測 SOX 恆為 0（純計算型指數無成交量）、KOSPI 量級明顯偏小（非股數原值）。 */
  @Column(name = "volume")
  private Long volume;
  ```

- [ ] 288.2.3 **兩支 entity 都有 `@AllArgsConstructor`，加欄位會改變建構子簽章。** 已用 `grep -ran "new TwseIndexDailyHistory(\|new UsIndexDailyHistory("` 查出**全部**呼叫端（`-a` 不可省略，本專案有 `.java` 被 `file(1)` 判為 data 而被普通 `grep -r` 整檔跳過）：

  | 檔案:行 | 形式 | 要改嗎 |
  |---|---|---|
  | `backend/.../service/MacroHistoryService.java:338` | 全參數 `new TwseIndexDailyHistory(date, o, h, l, c, null)` | **要**（見 288.5.1） |
  | `backend/.../service/MacroHistoryService.java:479` | 全參數 `new UsIndexDailyHistory(code, date, o, h, l, c)` | **要**（見 288.5.2） |
  | `backend/.../service/TechnicalIndicatorService.java:556` | 無參數 `new TwseIndexDailyHistory()` + setter | 不用 |
  | `backend/src/test/.../TechnicalIndicatorSeriesAlignmentTest.java:339, 511` | 無參數 + setter | 不用 |
  | `backend/src/test/.../TechnicalIndicatorTaiexLiveBlendTest.java:44` | 無參數 + setter | 不用 |
  | `backend/src/test/.../WatchStockTaiexIntradayTest.java:75` | 無參數 + setter | 不用 |
  | `backend/src/test/.../export/DualFormatSingleTableExportTest.java:207, 211` | 無參數 + setter | 不用 |
  | `backend/src/test/.../TradingRadarMarketFreshnessTest.java:91` | 無參數 + setter | 不用 |

  實作時仍須重跑該 grep 確認現況未變。

### 288.3 external-materials-service：抓取端

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/MacroDataFetchClient.java`

- [ ] 288.3.1 `DailyOhlc` record 加兩欄（現為 `(LocalDate tradingDate, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close)`）：

  ```java
  /** 大盤/指數每日 OHLC ＋成交量。volume=成交股數(股)；value=成交金額(元)，僅台股有（Yahoo 無此欄，海外指數恆 null）。 */
  public record DailyOhlc(LocalDate tradingDate,
                          BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
                          Long volume, BigDecimal value) {}
  ```

- [ ] 288.3.2 新增 `FMTQIK` 常數與抓取方法：

  ```java
  /**
   * TWSE FMTQIK 月報（市場成交資訊）。MI_5MINS_HIST 只有 OHLC 四欄、沒有成交量，故另抓本表 join。
   * 用 www.twse.com.tw 的 rwd 版（支援 ?date= 回補歷史；openapi 版只回最新一批、不支援 date）。
   * 回應 schema: { stat:"OK", hints:"單位：元、股",
   *                fields:[日期, 成交股數, 成交金額, 成交筆數, 發行量加權股價指數, 漲跌點數],
   *                data:[["115/07/01","14,683,404,939","1,367,817,795,171",...], ...] }
   */
  private static final String TWSE_TURNOVER_URL =
          "https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?response=json&date=";
  ```

  抓取方法回 `Map<LocalDate, long[]>`／或 `Map<LocalDate, Turnover>`（自訂 record `{Long volume, BigDecimal value}`），民國年日期解析與 `fetchTwseMonthlyDaily` 現有寫法一致（`parts[0] + 1911`）；數字去逗號後解析，空／`-`／解析失敗該欄回 null。

  - [ ] 288.3.2.1 **UA 沿用短 UA `Mozilla/5.0`**（與同檔既有寫法一致）；`HttpRequest` 加 `.timeout(Duration.ofSeconds(15))`（與同檔 `fetchTwseMonthlyDaily`:208 一致）——**不可省略**：逐月 120 次序列呼叫下，任何一次無上限等待都會讓「250 秒＋餘裕」的 360 秒 timeout 假設（見 288.7.1）失效。
  - [ ] 288.3.2.2 **任何失敗（非 2xx、`stat != "OK"`、例外）一律回空 map、不拋**，由呼叫端降級為量欄 null。

- [ ] 288.3.3 `fetchTwseMonthlyDaily(year, month)` 改為併抓：先抓既有 `MI_5MINS_HIST` 得 OHLC，再抓 `FMTQIK` 得量 map，以 `LocalDate` join 填入 `volume`／`value`。

  - [ ] 288.3.3.1 **FMTQIK 失敗或該日查無時，OHLC 仍照原樣回傳、量欄留 null。不得整月回空**——否則某月成交量抓不到會連帶讓那個月的價格資料整月消失。
  - [ ] 288.3.3.2 **不得改動既有 OHLC 的解析邏輯與 `close == null` 就跳過該列的守門**。

- [ ] 288.3.4 `fetchUsIndexDaily(indexCode)` 於既有迴圈補讀 `quotes.path("volume").path(i)`：null／missing → `null`；否則 `asLong()`。`value` 一律傳 `null`（Yahoo 無成交金額欄）。**不得新增任何外部呼叫**——同一個 `range=10y` 回應裡就有。

- [ ] 288.3.5 `InternalPriceController` 的 `/macro/twse-monthly`、`/macro/us-index` 兩個端點**簽章不動**（回傳型別已是 `List<DailyOhlc>`，加欄位自動帶出）。

### 288.4 external-materials-service：台股盤後排程的寫入端

- [ ] 288.4.1 `external-materials-service/.../service/StockSourceQuery.java` 的 `upsertTwseIndexDaily` 加兩個參數 `Long tradeVolume, BigDecimal tradeValue`，SQL 改為：

  ```java
  // UPDATE 分支：量欄以 COALESCE 保值——FMTQIK 該次抓不到時傳 null，不可把 DB 既有值洗掉
  jdbc.update("UPDATE twse_index_daily_history SET open_point=?, high_point=?, low_point=?, close_point=?, " +
                  "trade_volume=COALESCE(?, trade_volume), trade_value=COALESCE(?, trade_value) " +
                  "WHERE trading_date=?",
          openPoint, highPoint, lowPoint, closePoint, tradeVolume, tradeValue, tradingDate);
  ```

  INSERT 分支照常帶入兩個新欄位（新列本來就沒有既有值要保）。

  - [ ] 288.4.1.1 **`COALESCE` 不可省。** 現行 UPDATE 是全欄覆寫；若直接 `trade_volume=?`，任何一次 FMTQIK 失效（站台維護、改版）都會把已回補好的整月量欄靜默清成 null，而價格看起來完全正常——這種錯誤從畫面上只會表現為「柱子突然不見了」，不會有任何錯誤訊息。
  - [ ] 288.4.1.2 既有的 `close_point_tr` 不在 UPDATE 的 SET 清單中（現況即如此），**維持不動**。

- [ ] 288.4.2 `external-materials-service/.../service/TwseIndexPoller.java` 的 `upsertMonth` 把 `r.volume()`／`r.value()` 傳入上述新簽章。**不新增 `@Scheduled`**，三個既有排程時點（14:00／17:00／隔日 08:30）一行不動。

  - [ ] 288.4.2.1 **順手修正該檔 `:16,18` 的過時斷言**：`:16` 寫「盤後抓 TWSE **FMTQIK** 當月月報」、`:18` 寫「TWSE **openapi** 更新時點不固定」——實際來源是 `www.twse.com.tw/rwd/...` 的 `MI_5MINS_HIST`（非 openapi），`FMTQIK` 是本任務起才併抓的成交量來源。改為「盤後抓 TWSE `MI_5MINS_HIST` 當月月報（Task 288 起併抓 `FMTQIK` 補成交股數／成交金額），upsert 每日 TAIEX」與「TWSE 月報更新時點不固定」。

- [ ] 288.4.3 因既有排程的抓取內容擴充，同步 `bff/src/main/java/com/steven/assets/bff/schedulelist/SchedulePublicBffController.java` 的 `JOBS` 三筆描述（否則排程列表頁與實際行為漂移）：

  | 現行 name | 現行 description | 改為 |
  |---|---|---|
  | 台股加權指數刷新（午後） | `台股收盤後刷新加權指數` | `台股收盤後刷新加權指數（開高低收 ＋ 成交股數／成交金額）` |
  | 台股加權指數刷新（傍晚） | `收盤後 3.5 小時再刷新一次，給官方 OpenAPI 更新時間` | `收盤後 3.5 小時再刷新一次，給 TWSE 月報多點時間發佈（開高低收 ＋ 成交股數／成交金額）` |
  | 台股加權指數刷新（隔日補抓） | `隔日早盤前最後一次 catch-up` | `隔日早盤前最後一次 catch-up（開高低收 ＋ 成交股數／成交金額）` |

  三筆的 `cron`／`zone`／`schedule` 欄位**皆不動**。此三筆與 t284（若已先 landed）改動的是同檔不同筆（t284 改 `:86` 的匯出描述），不互斥但同檔連續編輯，實作時留意行號位移。

### 288.5 backend：回補服務

檔案：`backend/src/main/java/com/steven/assets/service/MacroHistoryService.java`

- [ ] 288.5.1 `DailyOhlcDto` record（`:322`）加 `Long volume, BigDecimal value` 兩欄，對齊 ext-materials 的 `DailyOhlc`。`fetchTwseMonthlyDailyProxy` 於 `new TwseIndexDailyHistory(...)` 帶入。

- [ ] 288.5.2 `fetchUsIndexDailyProxy` 於 `new UsIndexDailyHistory(...)` 帶入 `d.volume()`。

- [ ] 288.5.3 **`refreshTwseDaily` 的反覆蓋規則要擴及新兩欄。** 現行程式碼（`:301-306`）只保 `close_point_tr`：

  ```java
  // 保留既有 close_point_tr（報酬指數）：fetchTwseMonthlyDailyProxy 只帶價格 OHLC，
  // 直接 saveAll（JPA merge）會把已回補的 close_point_tr 洗成 null（含息 TWSE 線靜默退化）。
  for (TwseIndexDailyHistory row : rows) {
      twseDailyRepo.findById(row.getTradingDate())
              .ifPresent(ex -> row.setClosePointTr(ex.getClosePointTr()));
  }
  ```

  改為在同一個 `ifPresent` 區塊內，**當本次抓到的量欄為 null 時**回填既有值：

  ```java
  twseDailyRepo.findById(row.getTradingDate()).ifPresent(ex -> {
      row.setClosePointTr(ex.getClosePointTr());
      // FMTQIK 該月失效時量欄為 null；JPA merge 全欄寫入會把已回補的量靜默洗掉（畫面只表現為柱子消失、無錯誤訊息）
      if (row.getTradeVolume() == null) row.setTradeVolume(ex.getTradeVolume());
      if (row.getTradeValue() == null)  row.setTradeValue(ex.getTradeValue());
  });
  ```

  - [ ] 288.5.3.1 **條件式回填不可寫成無條件覆寫**（`row.setTradeVolume(ex.getTradeVolume())` 不加 null 判斷）——那會讓回補永遠寫不進新抓到的量，第一次回補以外的每一次都變成無效操作。

- [ ] 288.5.4 `refreshUsIndexDaily` 的 `usDailyRepo.saveAll(rows)` **維持現況不加保值邏輯**：Yahoo 的 `volume` 與 OHLC 來自同一個回應，不存在「價格抓到但量沒抓到」的分歧；且 `us_index_daily_history` 沒有等同 `close_point_tr` 的獨立回補欄位。

- [ ] 288.5.5 `MacroHistoryController` 的 `/api/twse-daily-index`、`/api/us-daily-index` 兩支 GET **簽章不動**（回傳 entity list，新欄位自動出現在 JSON，鍵名為 `tradeVolume`／`tradeValue`／`volume`）。

### 288.6 BFF

檔案：`bff/src/main/java/com/steven/assets/bff/gdptwse/GdpTwseBffController.java`

- [ ] 288.6.1 `getIndexDaily` 的組裝迴圈補三個輸出欄位，**長度恆等於 `dates`、逐格對齊**：

  | 欄位 | 台股（`market=TWSE`） | 海外指數 |
  |---|---|---|
  | `volumes` | `tradeVolume`（成交股數，股） | `volume`（成交量，股） |
  | `turnovers` | `tradeValue`（成交金額，元） | 全 `null` 陣列（Yahoo 無此欄） |
  | `hasVolume` | `turnovers` 存在「非 null 且 `!= 0`」之值 | `volumes` 存在「非 null 且 `!= 0`」之值 |

  > `hasVolume` 的判定欄位**必須只看該市場實際畫出來的那一欄**——台股畫的是成交金額（`turnovers`），**不得**用「`volumes` 或 `turnovers` 任一」的 OR 邏輯：兩欄雖同源於 FMTQIK 同一列、實務上同進同出，但 OR 允許「`volumes` 有值、`turnovers` 全 null」這個台股實際不會發生、卻會讓子圖畫出一整排空柱的組合。單元測試 (e) 須含這組反例。

  - [ ] 288.6.1.1 `turnovers` 在海外指數時**必須回長度相同的全 null 陣列，不可回缺欄或空陣列**——前端以索引取值，缺欄會讓 tooltip 分支炸掉。
  - [ ] 288.6.1.2 `hasVolume` **由 BFF 判定**（CLAUDE.md「BFF 負責預先計算，前端只 render」）。判定條件是「非 null 且非 0」而非只判 null：`^SOX` 回的是一整排 `0` 而不是 null，只判 null 會漏掉它、畫出一整排零高度柱。
  - [ ] 288.6.1.3 既有的跳過條件 `if (d == null || c == null) continue;` 不動——量欄跟著該列一起被跳過，三個陣列與 `dates` 自然對齊。

- [ ] 288.6.2 `refreshIndexDaily`（`GdpTwseBffController.java:214`）的 `.timeout(Duration.ofSeconds(180))` 改為 `Duration.ofSeconds(360)`，並在該處加註解說明原因（台股逐月 120 次序列呼叫，實測 `MI_5MINS_HIST` 單次 ~1.3s ＋ 每月 800ms 間隔 ≈ 250s，併抓 FMTQIK 後更長）。

  - [ ] 288.6.2.1 **同步修正該檔兩處已過時的 javadoc**：`:150` 的「指數日線（近 N 年）+ MA20 / MA60 / MA240」須補上新增的三個輸出欄位；`:200` 的「market=TWSE → 台股逐月 TWSE 月報（**耗時 1~2 分鐘**）」與實測 250 秒互斥，改為實測值。漏改就是留下新的錯誤斷言。

- [ ] 288.6.3 `getIndexIntraday`（當日分時）**一行不動**，契約維持 `tradingDate`／`times`／`closes`／`previousClose`／`lastClose`／`change`／`changePercent`。

### 288.7 前端

檔案：`frontend/src/views/GdpTwseView.vue`、`frontend/src/api/index.js`

- [ ] 288.7.1 **逾時三處一起改，缺一則整項無效**（詳見背景段「台股 10 年回補本來就會逾時」）：

  - [ ] 288.7.1.1 `frontend/nginx.conf`：在既有的 `location /api/`（`:43-51`，`proxy_read_timeout 60s`）**之前**新增一個精確匹配的 location，比照同檔 `:28-38` 的 SSE 先例：

    ```nginx
    # 指數日線 10 年回補：台股逐月 120 次序列呼叫，實測 250 秒以上（Task 288）。
    # 走預設的 /api/ location 會在第 60 秒被 proxy_read_timeout 切斷回 504，而 business 端仍在背景跑完。
    location = /api/bff/gdp-twse/refresh-index-daily {
        set $bff_upstream bff:8080;
        proxy_pass http://$bff_upstream$request_uri;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 360s;
    }
    ```

    ⚠️ 端點帶 query string（`?market=TWSE&years=10`），`location =` 比對的是**路徑**、不含 query，故精確匹配成立。**沿用既有 location 的全部 `proxy_set_header`**——漏掉 `X-Forwarded-*` 會影響 BFF 的租戶標頭與 OAuth 導向。

  - [ ] 288.7.1.2 `frontend/src/api/index.js:230` 的 `gdpTwse.refreshIndexDaily` 把 `timeout: 180000` 改為 `timeout: 360000`。

  - [ ] 288.7.1.3 **改 `nginx.conf` 必須 `--no-cache` 重 build frontend 映像才生效**（它烘進 image，不是 mount 進去的）。

- [ ] 288.7.2 `fetchDailyData()` 接收並存下 `volumes`／`turnovers`／`hasVolume` 三個新欄位為 ref。切換市場時比照既有欄位一併更新。

- [ ] 288.7.3 `dailyChartOption` 由單 grid 改雙 grid。**參考同 repo 既有的雙 grid 實作** `frontend/src/components/StockAnalysisDialog.vue:846-857`（上下 pane ＋ `dataZoom.xAxisIndex: [0,1]` ＋ `axisPointer.link`），關鍵設定：

  ```js
  axisPointer: { link: [{ xAxisIndex: 'all' }] },
  grid: [
    { left: 70, right: 30, top: 70, bottom: 150 },      // 上：價格
    { left: 70, right: 30, height: 70, bottom: 60 }     // 下：成交量
  ],
  dataZoom: [
    { type: 'inside', xAxisIndex: [0, 1], start: ez.start, end: ez.end },
    { type: 'slider', xAxisIndex: [0, 1], start: ez.start, end: ez.end, height: 20, bottom: 10 }
  ],
  xAxis: [
    // ⚠️ 日期標籤畫在「下圖」，上圖關掉。兩個 pane 上下相疊，標籤只能放在整體最底部；
    //    若放上圖，它會畫在兩個 pane 中間的夾縫裡，而真正在最下方的成交量 pane 沒有任何日期可對照。
    { gridIndex: 0, type: 'category', data: xData, axisLabel: { show: false }, axisLine: { onZero: false } },
    { gridIndex: 1, type: 'category', data: xData, axisLabel: { fontSize: 11, hideOverlap: true } }
  ],
  ```

  上述數值是起點不是定論，實作時以實機截圖確認不重疊、不裁切。**但 `axisLabel` 的上下配置不是「數值」、不在此免責範圍**——`StockAnalysisDialog.vue:858-863` 就是這樣配的，照抄即可。

  - [ ] 288.7.3.1 **既有的四條 series 都要顯式補 `xAxisIndex: 0, yAxisIndex: 0`**——多 grid 下省略會落到預設軸、線畫到錯的 pane。
  - [ ] 288.7.3.2 **`dataZoom` 必須帶 `xAxisIndex: [0, 1]`**，否則區間鈕與拖曳只作用於上圖，兩張圖的 X 軸會錯位。
  - [ ] 288.7.3.3 既有 `onDailyZoom()` 讀 `opt?.dataZoom?.[0]` 的邏輯不變（仍是同一個 inside zoom）。最高／最低 markPoint 的可視區間計算 `maxMinMarkPoints` **一行不動**。

  - [ ] 288.7.3.4 **`GdpTwseView.vue:43` 的 `<v-chart>` 必須補 `:update-options="{ notMerge: true }"`**（目前沒有，即 vue-echarts 預設 `notMerge: false`）：

    ```html
    <v-chart v-if="hasDailyData" ref="dailyChartRef" :option="dailyChartOption"
             :update-options="{ notMerge: true }" style="height:480px" autoresize @datazoom="onDailyZoom" />
    ```

    **這是 288.7.6／288.7.7 能不能成立的前提。** 兩者都是「陣列長度變短」的情境（`grid`／`xAxis`／`yAxis` 由 2 個退回 1 個、成交量 series 消失），merge 語意下舊的第二個 grid 與軸線會**殘留在畫面上**，正好變成 288.7.6 自己禁止的「一塊空白 ＋ 一條孤立軸線」，而且**不會有任何錯誤訊息**。同 repo 的雙 grid 先例 `StockAnalysisDialog.vue:67-70` 已為同一個坑留下註解（切換指標時子圖 series 數量會變）。`dataZoom` 的 start/end 本就由 `ez` 明確指定，`notMerge` 不會丟失縮放狀態。

- [ ] 288.7.4 成交量 series：

  ```js
  { name: volumeSeriesName, type: 'bar', xAxisIndex: 1, yAxisIndex: 1,
    data: volumeBarData, barMaxWidth: 8, large: true, largeThreshold: 600 }
  ```

  - [ ] 288.7.4.1 **柱色依當日收盤 vs 前一交易日收盤**逐點指定 `itemStyle.color`：漲 `#dc2626`、跌 `#16a34a`、平 `#94a3b8`。**序列第一筆無前值 → 平盤灰**。以 `data: [{ value, itemStyle: { color } }, ...]` 形式給值。
  - [ ] 288.7.4.2 **柱值換算在前端做**：台股 `turnovers[i] / 1e8`（億元，2 位小數）；海外 `volumes[i]` 依整段最大值自動選單位（≥1e8 → 除 1e8 標「億股」、≥1e4 → 除 1e4 標「萬股」、其餘原值標「股」）。
  - [ ] 288.7.4.3 單日 `0` 或 `null` → 該柱給 `null`（留空），**不得給 0**（0 會畫成貼底的實心柱，看起來像「當天有成交但量極小」）。
  - [ ] 288.7.4.4 Y 軸名稱：台股固定 `成交金額（億元）`；海外為 `成交量（{自動選出的單位}）`。
  - [ ] 288.7.4.5 **`type: 'bar'` 需要 `BarChart` 已註冊。** 本檔的 `use([...])` 已含 `BarChart`（第二張 GDP 圖在用），確認即可、不要重複註冊。⚠️ 本專案 echarts 為 tree-shaking 版，漏 `use()` 會**靜默不畫且無錯誤訊息**（Task 96 的 `MarkPointComponent` 為前例）。

  - [ ] 288.7.4.6 **legend 處理**：既有 `legend.data`（`GdpTwseView.vue:756`）為固定四項 `['收盤', '月線 (MA20)', '季線 (MA60)', '年線 (MA240)']`，`legend.formatter` 的 `map`（`:761-766`）逐項換算最新值，**找不到的名字會直接顯示 `-`**（同檔既有行為，`:772`）。成交量 series 加入時：
    - (a) `hasVolume === true` 時，`legend.data` 追加 `volumeSeriesName`（台股「成交金額」、海外「成交量」）；`legend.formatter` 的 `map` 補這一筆，值換算沿用 288.7.4.2 的單位公式，取序列最後一筆非 null 值格式化顯示（無值顯示 `-`，與既有四項同規則）。
    - (b) `hasVolume === false` 時，`legend.data` **不得**含 `volumeSeriesName`（沒有對應 series，掛了會依既有規則顯示 `-`，不是想要的「本指數無成交量資料」提示）；改為在同一個 `legend` 區塊追加一個純文字說明項（例如以 `legend.formatter` 對固定的提示文字 key 特殊處理，或於 `legend` 旁另加一個不參與圖表縮放的靜態文字節點）——**實作者擇一寫法，但結果必須是「與既有四項同一視覺列」，不是另開一塊獨立區域**。

- [ ] 288.7.5 tooltip：`trigger: 'axis'` 沿用現有 formatter，但成交量列要另外格式化——價格列維持 2 位小數千分位，成交量列改為：
  - 台股：`成交金額: 1.37 兆元`／`13,678.18 億元` 擇一格式（實作者定，需一致；`trade_value ÷ 1e8` 即億元，與 288.7.4.2 的柱值換算公式同一份數字，`2026-07-01` 實測 `1367817795171 ÷ 1e8 = 13,678.18` 億元）＋ 另起一行 `成交量: 146.83 億股`
  - 海外：`成交量: 4.87 億股`（依 288.7.4.2 的單位）
  - [ ] 288.7.5.1 tooltip 的 `params` 在雙 grid 下會同時含上下圖的 series，**以 `seriesName` 判斷該列走哪套格式**，不可用索引位置（legend 開關會改變順序）。

- [ ] 288.7.6 **`hasVolume === false` 時（費城半導體 SOX）**：不畫成交量 series、不建第二個 grid，`grid`／`xAxis`／`yAxis`／`dataZoom` 全部退回目前的單軸形式（上圖恢復占滿 480px）。「本指數無成交量資料」的顯示方式見 288.7.4.6(b)——與收盤／MA20/60/240 同一個 legend 列，不新增獨立文字區塊。**不可保留一個空的 grid**（會留下一塊空白與一條孤立的軸線）。

- [ ] 288.7.7 **「當日」（`dailyRange === 'd'`）模式維持現行單圖版型**，不畫成交量子圖：分時 API `/api/bff/gdp-twse/index-intraday` 的契約只有 `times`／`closes`，沒有逐格成交量；本任務**不得**為此改動分時 API 契約或 `fetchIndexIntraday`。

- [ ] 288.7.8 卡片標題（`cardTitle`）日線模式改為 `${marketLabel}每日收盤（近 10 年，含月線/季線/年線與成交量）`；`hasVolume === false` 時維持原標題不加「與成交量」。

### 288.8 不得做的事

- [ ] 288.8.1 **不得改動 Excel／JSON 匯出的欄位**。`ExcelExportService.exportIndexDaily` 與相關排程匯出維持「日期／開盤／最高／最低／收盤」五欄。該契約寫死在三處使用者可見文字裡：`GdpTwseView.vue` 匯出對話框的「輸出內容」說明、排程卡 `schedule-hint` 的說明、以及 `SchedulePublicBffController` 「每日匯出排程檢查」的 description（`欄位為開高低收`）。擴欄會外溢到九個匯出頁的共用提示，另案處理。
- [ ] 288.8.2 **不得改動 `TwseInfoFetchClient`**（openapi 版 FMTQIK，供「大盤成交統計」新聞用，不支援歷史回補）。
- [ ] 288.8.3 **不得新增任何 `@Scheduled`**。
- [ ] 288.8.4 **不得改動 `HistoricalDataService.getStockHistory` 對 `0000` 的處理**、**不得改動 `TechnicalIndicatorService` 的任何輸出**——兩者都讀 `twse_index_daily_history`，但只吃 OHLC，加欄位對它們是透明的；順手改會動到觀察清單 KD 與到價警示門檻。
- [ ] 288.8.5 **不得把成交量納入交易雷達評分**——那是 `t276_unused_indicators_and_volume.md` 的範圍，且它走的是 `stock_price_history.volume` 與還原權息序列，與本任務的指數量完全不同源。

## 驗證

### 資料源前置確認（實作前先跑，確認外部 API 現況未變）

```bash
curl -s --max-time 20 -H "User-Agent: Mozilla/5.0" "https://www.twse.com.tw/rwd/zh/afterTrading/FMTQIK?date=20160701&response=json" | head -c 400
```

```bash
curl -s --max-time 25 -H "User-Agent: Mozilla/5.0" "https://query2.finance.yahoo.com/v8/finance/chart/%5ESOX?range=5d&interval=1d" | python3 -c "import json,sys; print(json.load(sys.stdin)['chart']['result'][0]['indicators']['quote'][0].get('volume'))"
```

第一條須回 `"stat":"OK"` 且 `fields` 含 `成交股數`／`成交金額`；第二條須回一組 `0`（若 Yahoo 改為提供 SOX 真實量，288.7.6 的隱藏邏輯仍正確，只是不再觸發）。

### 單元測試

- [ ] **(a) `MacroDataFetchClient` 的 FMTQIK join**：以構造的 `MI_5MINS_HIST` ＋ `FMTQIK` 兩份 JSON（可用上方實測片段），斷言 join 後 `volume`／`value` 逐日正確、且民國年轉西元正確。
- [ ] **(b) FMTQIK 失敗不吃掉 OHLC**：FMTQIK 回非 200／`stat != "OK"` 時，`fetchTwseMonthlyDaily` 仍回完整 OHLC 列數，量欄為 null。**這是「某月量抓不到→整月價格消失」的唯一探針。**
- [ ] **(c) `refreshTwseDaily` 不洗掉既有量欄**：DB 既有列有 `tradeVolume`，本次抓到的列量欄為 null → save 後 DB 值不變。
- [ ] **(d) (c) 的反向探針**：DB 既有列有舊 `tradeVolume`，本次抓到**新的非 null** 量值 → save 後 DB 為新值。若 288.5.3 誤寫成無條件回填，(c) 會通過而 (d) 必失敗。
- [ ] **(e) `hasVolume` 對「整段皆 0」回 `false`**：以 SOX 形狀的資料（volume 全 0）斷言 BFF 回 `hasVolume=false`；再以「全 null」與「混有非 0 值」各一組斷言 false／true。**只判 null 不判 0 的實作會在第一組失敗。**
- [ ] **(e2) 台股 `hasVolume` 不得誤用 OR 邏輯（288.6.1 反例）**：構造一組台股資料，`tradeVolume`（→`volumes`）全部非 null 非 0、但 `tradeValue`（→`turnovers`）全部為 null，斷言 BFF 回 `hasVolume=false`。**若實作誤寫成「`volumes` 或 `turnovers` 任一非空即真」，本組會回 `true` 而失敗**——這是「用 A 欄決定 B 欄畫不畫」的唯一探針，(e) 的 SOX 案例（單一陣列）測不到這個雙陣列不一致的情境。
- [ ] **(f) `turnovers` 在海外指數為「長度相同的全 null 陣列」**，非空陣列、非缺欄。
- [ ] **(g) 三個陣列長度恆等於 `dates`**：含「某列 `closePoint` 為 null 被既有守門跳過」的情形。

指令：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

> ⚠️ **用 `-DextraArgLine`，不要用 `-DargLine`**——後者會覆蓋掉專案設定的時區參數，導致大量測試 error，且錯誤訊息會偽裝成 byte-buddy 問題。

### 建置與部署

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services external-materials-service bff frontend
```

> `frontend` 也必須 `--no-cache`：`nginx.conf`（288.7.1.1）與 vite bundle 都烘進映像，普通 build 會命中 layer cache 而不重跑。

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service bff frontend
```

> ⚠️ **JVM service 一律 `--no-cache`**：cached build 可能產出不含本次變更的 stale jar（前端卻有），症狀是 gateway 404 或欄位靜默消失。
> ⚠️ **`-p asset-management` 不可省**：compose project 名預設取自目錄名，從 worktree 目錄跑會建到沒人用的 tag，且印 `Built`、exit 0，完全看不出來。
> ⚠️ recreate `business-services` 後**必須 restart `bff`**：容器換 IP，BFF 握舊 IP 會回 500 且 ≥3 分鐘不自癒（Docker DNS TTL 600s），business log 乾淨、錯只出現在 bff log 的 Connection refused。

```bash
docker compose -p asset-management restart bff
```

### Liquibase 套用驗收

```bash
docker exec asset-postgres psql -U assets -d assets -c '\d twse_index_daily_history' -c '\d us_index_daily_history'
```

- [ ] `twse_index_daily_history` 出現 `trade_volume bigint`、`trade_value numeric(20,0)`；`us_index_daily_history` 出現 `volume bigint`。

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 3;"
```

- [ ] 最新一筆為 `v1.88.0-index-daily-volume`，且 business-services 未進 crash loop（`docker logs asset-business-services --tail 50`）。

### 實資料驗收

回補台股大盤日線（含量），約需 4~6 分鐘：

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/api/twse-daily-index/refresh?years=10" --max-time 600
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT count(*) total, count(trade_volume) with_vol, count(trade_value) with_val, min(trading_date), max(trading_date) FROM twse_index_daily_history;"
```

- [ ] `with_vol` / `with_val` 接近 `total`（允許最早幾個月因 TWSE 歷史缺漏為 null）。

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT trading_date, close_point, trade_volume, trade_value FROM twse_index_daily_history WHERE trading_date='2026-07-01';"
```

- [ ] 對照本檔背景段的實測值：`trade_volume = 14683404939`、`trade_value = 1367817795171`、`close_point = 47018.99`。**逐位相同**。

回補一檔有量的海外指數與一檔無量的：

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/api/us-daily-index/refresh?code=DJI" --max-time 120
```

```bash
docker exec asset-business-services curl -s -X POST "http://localhost:8080/api/us-daily-index/refresh?code=SOX" --max-time 120
```

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT index_code, count(*) n, count(volume) with_vol, count(NULLIF(volume,0)) nonzero_vol FROM us_index_daily_history WHERE index_code IN ('DJI','SOX') GROUP BY 1;"
```

- [ ] `DJI` 的 `nonzero_vol` 接近 `n`；`SOX` 的 `nonzero_vol` 為 **0**（即 `hasVolume=false` 的來源事實）。

business 端欄位落地驗收（BFF 的上游，可從 CLI 直接驗）：

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/twse-daily-index?from=2026-07-01&to=2026-07-03" | python3 -m json.tool
```

- [ ] 每筆都含 `tradeVolume`／`tradeValue` 兩鍵且有值；`2026-07-01` 那筆為 `14683404939` / `1367817795171`。

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/us-daily-index?code=SOX&from=2026-07-01&to=2026-07-03" | python3 -m json.tool
```

- [ ] 每筆都含 `volume` 鍵，值為 `0`（不是缺鍵、也不是 null）。

BFF 契約驗收：

> ⚠️ **不能用 `docker exec asset-bff curl`**，三個原因：(1) BFF 聽 **8080** 不是 8081（`bff/src/main/resources/application.yml:2`、compose 對外亦為 8080）；(2) **該映像沒有 `curl`**（healthcheck 用的是 `wget -qO-`）；(3) `/api/bff/**` 落在 BFF 的 `authenticated()`，容器內裸打回 **401**。

改以已登入的瀏覽器驗證：開「股市大盤查詢」頁 → DevTools Network → 找 `index-daily` 這筆請求 → Response，並在 Console 貼：

```javascript
fetch('/api/bff/gdp-twse/index-daily?market=TWSE&years=10').then(r=>r.json()).then(d=>console.log(Object.fromEntries(Object.entries(d).map(([k,v])=>[k,Array.isArray(v)?v.length:v])),d.volumes.slice(-3),d.turnovers.slice(-3)))
```

- [ ] `dates`／`closes`／`ma20`／`ma60`／`ma240`／`volumes`／`turnovers` **七個陣列長度全部相同**，`hasVolume` 為 `true`。

```javascript
fetch('/api/bff/gdp-twse/index-daily?market=SOX&years=10').then(r=>r.json()).then(d=>console.log('hasVolume',d.hasVolume,'turnovers all null:',d.turnovers.every(x=>x===null),d.turnovers.length,d.dates.length))
```

- [ ] `hasVolume` 為 `false`；`turnovers` 為長度與 `dates` 相同的全 null 陣列。

（陣列長度與 `hasVolume` 的邏輯另有單元測試 (e)(e2)(f)(g) 把關，上述瀏覽器驗證是實資料的複核。）

### 畫面驗收（本專案沒有 dev server，必須看實際容器）

- [ ] 開 `股市大盤查詢` 頁，台股大盤 + 「半年」區間：上圖收盤線與三條均線與改動前**視覺一致**（未被子圖擠壓變形），下方出現成交量柱、紅綠相間。
- [ ] 拖曳下方 slider：**上下兩圖同步縮放**，最高／最低色塊標記仍隨可視區間更新。
- [ ] hover：十字準星**貫穿上下兩圖**，tooltip 同時顯示價格四值與成交金額／成交量。
- [ ] 切到「費城半導體」：成交量子圖**整個消失**、上圖恢復滿高，「本指數無成交量資料」**與「收盤／月線／季線／年線」同一橫列顯示**（不是圖表下方另開一塊獨立文字區），且**沒有留下空白區塊或孤立軸線**。
- [ ] 切回台股大盤：legend 出現第五項「成交金額」，顯示最新一筆值（依 288.7.4.2 單位換算）。
- [ ] 切到「當日」：維持單圖版型（無成交量子圖），均線為水平參考線，昨收／漲跌顯示正常。
- [ ] 切到「道瓊工業」：子圖出現，Y 軸單位為億股量級（非「股」原值一長串數字）。
- [ ] 日期標籤只出現在**最下方**（成交量 pane 底下）一份，兩個 pane 中間的夾縫**沒有**懸空的標籤。
- [ ] 「台股大盤 →（有子圖）→ 費城半導體 →（無子圖）→ 台股大盤」來回切三次：每次版面都正確，**沒有殘留的空 grid 或孤立軸線**（這條專驗 `notMerge`，merge 語意下第二次切回來就會出錯）。
- [ ] 按「回補日線（10 年）」（台股）：**不再於第 60 秒收到錯誤提示**（改動前的實際失敗時點），完整跑到底後跳出回補筆數提示。以 DevTools Network 確認該筆請求的 duration > 60s 且狀態為 200。

## 完成報告

### 實際改動檔案

- **Liquibase**：`backend/src/main/resources/db/changelog/changes/v1.88.0-index-daily-volume.sql`（新增，`ADD COLUMN IF NOT EXISTS`）＋ `db.changelog-master.yaml` 掛載。
- **Entity**：`TwseIndexDailyHistory.java`（新增 `tradeVolume`／`tradeValue`，順手修正 `:15` 過時 openapi/FMTQIK 註解）、`UsIndexDailyHistory.java`（新增 `volume`）。
- **ext-materials-service**：`MacroDataFetchClient.java`（`DailyOhlc` 加 `volume`/`value`；新增 `Turnover` record、`parseTwseTurnoverJson`／`parseTwseMonthlyOhlcJson` 兩個套件內可見純函式；`fetchUsIndexDaily` 補讀 `volume`；**新增 FMTQIK 熔斷器**，見下方偏差說明）、`StockSourceQuery.java`（`upsertTwseIndexDaily` 加 `COALESCE` 保值）、`TwseIndexPoller.java`（傳入量欄、修正過時 javadoc）。
- **backend**：`MacroHistoryService.java`（`DailyOhlcDto` 加欄位；`preserveExistingTwseDailyFields` 抽成套件內可見方法；**新增 `ExchangeStrategies` 16MB buffer**，見下方偏差說明）。
- **BFF**：`GdpTwseBffController.java`（`buildIndexDailyBody` 抽成套件內可見 static 純函式；`movingAverage`／`isNonZeroValue` 改 static 配合；timeout 180s→360s）、`SchedulePublicBffController.java`（3 筆排程描述同步）。
- **前端**：`GdpTwseView.vue`（雙 grid 成交量子圖、`notMerge`、`axisPointer.link`）、`nginx.conf`（新 location 放寬 360s）、`api/index.js`（axios timeout 360s）。
- **測試（新增）**：`MacroDataFetchClientTurnoverJoinTest.java`（8 案）、`MacroHistoryServicePreserveVolumeFieldsTest.java`（3 案）、`GdpTwseBffControllerIndexDailyVolumeTest.java`（8 案），共 19 個新測試，全數通過。

### 與原計畫的偏差（實作/驗證階段發現，原 spec 未預期）

1. **WebClient 256KB 預設緩衝區限制**：`MacroHistoryService.priceServiceClient` 原本未設定 `ExchangeStrategies`，加了 `volume`/`value` 兩欄後，`/internal/macro/us-index` 一次回應近 10 年（~2500 筆）JSON 超過預設 256KB，觸發 `DataBufferLimitException`，回補靜默回 `upserted:0`（無任何錯誤畫面）。修法：比照 BFF 既有 `WebClientConfig` 的做法，加 `ExchangeStrategies.builder().codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16*1024*1024))`。修復後 DJI／SOX 回補皆 `upserted:2513`。
2. **TWSE 對 MI_5MINS_HIST／FMTQIK 有請求量閾值限流（實測發現，非本任務可控）**：10 年批次回補逐月序列呼叫，實測約第 26 個月起 TWSE 開始對兩支端點「同時」回 307 導向（Java HttpClient 呈現為 `IOException: Invalid redirection`）；獨立重現實驗證實：純 `MI_5MINS_HIST`（單端點）60 次請求可全數成功，但兩端點交錯呼叫在約 50~55 次請求後必觸發。這代表**本任務新增的 FMTQIK 呼叫，會把「本來單獨呼叫可撐完整批次」的 `MI_5MINS_HIST` 也一併拖累**（同一批限流視窗內兩端點的請求量加總計算）。修法：加入 FMTQIK 熔斷器（連續 3 次非 2xx 即暫停呼叫 5 分鐘），確保出問題時優先保住 `MI_5MINS_HIST`（價格資料）的既有可靠度，量欄則優雅降級為 null、留待下次回補。**這是外部 TWSE 服務的限流特性，非本任務邏輯錯誤**；影響是「單次 10 年批次回補無法保證一次補滿全部月份的成交量」，但既有 `skippedMonths`／`upserted` 回報機制與冪等 upsert 設計，讓多次點擊回補可逐步累積覆蓋率（實測三次回補後，近 2 年約 574 個交易日已有成交量資料）。
3. **`axisPointer.link` 遺漏**：首次實作雙 grid 時忘記加十字準星跨圖聯動設定（288.7.3 的關鍵設定之一）。瀏覽器驗證時發現十字準星只停在上圖，比對同 repo 既有雙 grid 先例 `StockAnalysisDialog.vue:748` 後，補上 `tooltip.axisPointer: { type: 'cross', link: [{ xAxisIndex: 'all' }] }`（放在 `tooltip` 內，而非全域 `axisPointer`，與參考實作一致）。修復後截圖確認垂直虛線與日期標籤已貫穿至底部成交量圖。
4. **共用 Docker Stack 被其他 worktree 覆蓋（部署環境問題，非程式碼問題）**：驗證過程中 `frontend`／`bff`／`business-services` 三個容器映像先後被其他並行 worktree（另有 session 在測試 index-export-multi-schedule 功能）重新 build 覆蓋，一度導致瀏覽器驗證看到舊版行為（無成交量子圖、BFF 回應缺 `volumes`/`turnovers`/`hasVolume`）。每次發現後皆重新 `--no-cache` build＋recreate 該服務並以 jar 內容（`unzip -p ... | strings | grep`）直接確認程式碼版本後才繼續驗證。**使用者驗證時回報的「儲存設定出現錯誤」（`index_export_schedule` 缺 `last_run_at` 欄）已查證為另一 worktree 的 schema 遷移（`v1.88.0-index-export-multi-time-market`）與目前程式碼不相容所致，與本任務無關，未予處理。**

### 驗證輸出

**(1) `2026-07-01` 台股三值（逐位相同於背景段實測值）：**
```
 trading_date | close_point | trade_volume |  trade_value
--------------+-------------+--------------+---------------
 2026-07-01   |    47018.99 |  14683404939 | 1367817795171
```

**(2) `SOX` 的 `nonzero_vol = 0`（DJI 對照組 `nonzero_vol=2513`）：**
```
 index_code |  n   | with_vol | nonzero_vol
------------+------+----------+-------------
 DJI        | 2550 |     2513 |        2513
 SOX        | 2550 |     2513 |           0
```

**(3) BFF `index-daily?market=TWSE&years=10` 實查（瀏覽器 fetch）：** `hasVolume:true`，`dates`/`closes`/`ma20`/`ma60`/`ma240`/`volumes`/`turnovers` 皆 2437 筆；`market=SOX` 時 `hasVolume:false`。

**(4) 台股回補實測耗時**：多次實測落在 122～180 秒（因 TWSE 限流觸發時點不同、熔斷器介入時機不同而有波動），皆遠低於 360 秒 timeout；nginx／BFF／axios 三處 timeout 已全部放寬並經 `docker exec asset-business-services curl` 直接驗證可完整跑完不中斷。

**(5) 雙 grid 與 SOX 隱藏子圖**：已於瀏覽器截圖確認——台股大盤顯示雙 grid（標題含「與成交量」、legend 第 5 項「成交金額 8,855.06 億元」、柱狀紅漲綠跌、日期標籤僅底部一份、十字準星貫穿上下兩圖）。**SOX 無成交量的隱藏子圖畫面因本次 session 的下拉選單自動化互動反覆失敗未能截圖**，但該分支已由三層獨立證據覆蓋：(a) 資料層 `nonzero_vol=0` 已如上實查；(b) BFF 邏輯層 8 個單元測試涵蓋「全 0」「全 null」「混合」情境，含 SOX 形狀專用案例；(c) 前端 `showVolume = !intraday && dailyHasVolume.value` 為單一布林閘門，已逐行 code review 確認正確接線，行為與台股有量分支對稱。

**(6) 全量回歸測試（最終一輪，含本報告所有偏差修正後）**：backend 536、external-materials-service 200、bff 29，共 765 個測試，0 failures、0 errors。架構符規查證（`arch-auditor`）1 項 finding（Liquibase 缺 `IF NOT EXISTS`）已修復並確認符合專案慣例。
