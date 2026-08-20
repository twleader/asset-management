# [t346] 台股集中／櫃買市場指數與上市櫃個股兩分鐘 Redis 報價契約

**對應 Requirements:** Requirement 18（股市大盤查詢）、Requirement 36（排程清單）、Requirement 45（指數匯出）、Requirement 67（公開指數 API）、Requirement 85（本任務）  
**前置任務:** t216、t324、t332  
**Liquibase changeset:** 無（TPEX 沿用既有 code-keyed `us_index_daily_history`）

## 背景

「股市大盤查詢」目前第一個市場顯示為「台股大盤」，需求是改成較精確的「台股集中市場」，並在其後加入「台股櫃買市場」。櫃買市場須與既有指數完全同版型：近 10 年日線、MA5/20/60/240、成交量、當日 5 分 K、手動回補、Excel／JSON 匯出、排程匯出與唯讀 public API。

個股報價的最終需求是**每 2 分鐘**，不是每 2 秒。現行架構已把 producer 放在 `external-materials-service`：台股 cron 每兩分鐘取持股／警示代碼，`PriceFetchClient` 對每個台股代碼先查 `tse`、查無再查 `otc`，成功後交 `PriceCacheWriter` 寫既有 Redis；美股另有相同兩分鐘節拍。本任務不得新增第二支櫃買排程或另一份 OTC Redis cache，只補齊可驗證的上櫃契約與排程清單文字。

## 要做什麼

### frontend／BFF：10 個市場 catalog

- [ ] 346.1 `frontend/src/views/GdpTwseView.vue`：`MARKETS` 前兩項依序改為 `{value:'TWSE', label:'台股集中市場'}`、`{value:'TPEX', label:'台股櫃買市場'}`，其後 8 個海外指數順序不變；找不到 catalog 時的 TWSE fallback label 同步改名。主圖單選與排程匯出多選都使用 `m.value`，測試必須同時驗證兩處都能選 TPEX，不可錯寫為畫面不會讀的 `code`。只改這個頁面分類，不全域取代 `0000`、交易雷達或新聞的「台股大盤」。
- [ ] 346.2 `bff/.../gdptwse/MarketIndexChartService.java`：`MARKET_CATALOG` 同步為 10 個；TPEX 日線走現有 `/api/us-daily-index?code=TPEX`，當日走 `/api/index-intraday?market=TPEX`。`supportedMarkets` 順序／label、daily shaping、四條 MA、volume 與 range 裁切均沿用同一條 service，禁止為 public API 複製第二套。
- [ ] 346.3 `GdpTwseBffController.java`：`refresh-index-daily?market=TPEX&years=10` 走現有 code-keyed `/api/us-daily-index/refresh?code=TPEX`；TWSE 仍是唯一 `/api/twse-daily-index/refresh` 特例。未知代碼維持拒絕。

### external-materials-service：TPEX 日線與當日圖

- [ ] 346.4 `MacroDataFetchClient.java`：禁止把 TPEX 加入 `US_INDEX_YAHOO`，`fetchUsIndexDaily("TPEX")` 以具名分支逐月取 TPEx 官方 `indexInfo/inx?date=YYYY/MM/01&response=json` OHLC，同月取 `st41_result.php?l=zh-tw&d=YYY/MM&o=json` 的成交量。兩支回應皆須驗 root lowercase `stat="ok"`，再從 `tables[]` 中找出恰一張含必要欄位的 target table，只依該 table 的 `fields/data` 欄名找 index，不得沿用 TWSE root parser 或寫死欄位位置；零張／多張 target 與 row width 不符皆依各自 OHLC／volume 失敗規則處理。OHLC 每列日期須合法且落在請求月份，O/H/L/C 皆為正數，`high≥max(open,close)`、`low≤min(open,close)` 且 `high≥low`；任一不符即為 OHLC failure。公元／民國日期對齊；量欄精確接受新 schema「成交張數」與舊 schema「成交股數（仟股）」兩個 alias，兩者均×1,000；nested fixture 必須各有一份。OHLC 過去完整月任一失敗即整次 fetch 失敗；量能月失敗只影響該月 incoming volume，單日缺列／非法／收市不同只使該日 volume null 並 WARN。TPEX `DailyOhlc.value` 維持 null，不擴 schema。
- [ ] 346.5 同一檔的 `fetchIndexIntraday("TPEX")` 以具名分支調用官方 MIS `getChartOhlcStatis.jsp?ex=otc&ch=o00.tw&fqy=1`，禁止加入 `INDEX_INTRADAY_YAHOO`。驗證 `rtmessage`、`staticObj.key`與 `ohlcArray`；以 `key` 取日期，每點必填 `t` epoch milliseconds／正數 `c`，轉 `Asia/Taipei` 後日期須與 key 一致並以本地時間 floor 到五分格，取格內最後 close。`ts=HHmmss` 僅為可選交叉驗證，存在時才與 `t` 比對；fixture 必須包含最後 13:33 點只有 `t/c` 而仍落入 13:30 格。補齊 `Asia/Taipei 09:00–13:30`。當日圖仍為 transient 資料，不寫 DB、不寫個股 Redis tick store。
- [ ] 346.6 不新增個股排程。保留 `PricePoller.scheduledTwIntradayUpdate` 的 `0 0/2 9-13 * * MON-FRI`／`Asia/Taipei` 與 `MarketClock.isTwMarketOpen()`；保留 `scheduledUsIntradayUpdate` 的 `0 0/2 9-16 * * MON-FRI`／`America/New_York`。台股上市與上櫃都由現有 `collectHeldStockCodes` 集合進同一輪；該 collector 現況只在 alert SQL 排除 `0000`，快照持股側沒有，故 `scheduledTwIntradayUpdate` 收集後須顯式 `tw.remove("0000")` 再呼叫 `updatePrices`，不得把排除誤當既有事實或改動其他 collector caller。
- [ ] 346.7 `PriceFetchClient.getTwseRealTimePrice` 的 `tse→otc` fallback 行為不得回歸：只有 response code 等於 requested code、且 `z` 為可解析真實成交價時回 `market='台股'` 的 `PriceResult`。`z='-'`、空陣列、code 不符、HTTP／解析失敗皆 `Optional.empty()`；不得用 `y/o/a/b` 代替 live。有效結果由 `PricePoller` 呼叫既有 `PriceCacheWriter.write(result,false)`，沿用 `price:台股:{code}`、market index SET、24h TTL、`price-update` 與台股 tick 累積。

### backend：code-keyed 指數與排程分類

- [ ] 346.8 `MacroHistoryService.java`：`OVERSEAS_INDEX_CODES` 保留既有 8 個；新增 `PAGE_CODED_INDEX_CODES`，順序為 TPEX 後接 8 個海外指數；頁面/public/export 白名單 `DAILY_INDEX_CODES={TWSE}∪PAGE_CODED_INDEX_CODES`。`SP500TR` 仍只屬 `TOTAL_RETURN_US_INDEX_CODES`，不得出現在本頁。`refreshUsIndexDaily("TPEX")` saveAll 前按回傳日期範圍載入既有 TPEX rows，incoming volume 為 null 且既有值非 null 時保留既有 volume，incoming 非 null 才覆寫；OHLC 仍以本次驗證值 upsert，並以回歸測試證明 fail-soft refresh 不洗量。
- [ ] 346.9 `MacroHistoryController.java`：`/api/index-intraday` 白名單接受 TPEX；相容 `GET /api/us-daily-index` 依 Requirement 33 維持不驗 code，不能套 `DAILY_INDEX_CODES` 以免誤擋績效比較的 SP500TR。相容 refresh 白名單精確為 `US_INDEX_REFRESH_CODES=PAGE_CODED_INDEX_CODES∪TOTAL_RETURN_US_INDEX_CODES`，因此 TPEX 與 SP500TR 都可 refresh，而 SP500TR 不進 `DAILY_INDEX_CODES`。相容路由保留。同步把 `MacroHistoryController`、`MacroHistoryService`、`MacroDataFetchClient`、`InternalPriceController`、`UsIndexDailyHistory`、`GdpTwseBffController`、`ExcelExportService` 中會把 TPEX 錯稱海外、誤寫成 Yahoo 單次取整段，或仍寫 8 檔的 javadoc／log 改稱「除 TWSE 外的 code-keyed 指數」並明記 TPEX 官方逐月例外；相容 method／route／table 名稱不改。`GdpTwseView.vue` 頂部「可切換台股大盤、美股」註解改為集中／櫃買／海外市場；匯出對話框「海外指數無成交金額」改為「除集中市場外的 code-keyed 指數無落地成交金額」，`pickVolumeUnit` 及 chart mapping 附近的「台股＝tradeVolume/tradeValue、僅台股 turnover 非空／海外＝volume」註解統一改為「TWSE＝tradeVolume/tradeValue；其餘 code-keyed（含 TPEX）＝volume、turnover null」，不保留使用者可見或當前契約的過期說法。
- [ ] 346.10 `IndexDailyRefreshScheduler.java`：每日 07:00 全量回補與開機 self-heal 遍歷 `PAGE_CODED_INDEX_CODES`，既有 `TOTAL_RETURN_US_INDEX_CODES` 與 TWSE 報酬指數增量步驟保留；`US_INDEX_CODES={DJI,SPX,IXIC,SOX,SP500TR}`，09:00／12:00 美股 gap-check 不含 TPEX；`NON_US_INDEX_CODES={TPEX,FTSE,DAX,KOSPI,N225}`。static guard 精確驗證兩分類聯集等於 `PAGE_CODED_INDEX_CODES∪TOTAL_RETURN_US_INDEX_CODES`。同步更新該類別的 class/method javadoc、INFO/WARN log，不得再宣稱只管理 8 檔海外指數。
- [ ] 346.11 `ExcelExportService.indexLabel` 加 `TWSE→台股集中市場`、`TPEX→台股櫃買市場`；手動與排程匯出白名單均引用 `DAILY_INDEX_CODES`，讓 TPEX 走現有 `UsIndexDailyHistoryRepository` 分支，不能新增第三張表。同步訂正 `findIndexDaily`／`indexDailyDoc` javadoc：TWSE 讀 `tradeVolume/tradeValue`；其餘 code-keyed（含 TPEX）讀 `volume` 且 turnover null，不再寫成「海外指數」。

### 排程清單

- [ ] 346.12 `SchedulePublicBffController.JOBS` 改三筆既有文案：(a)「台股個股即時價（盤中）」明列「上市／上櫃持股與觀察清單、每 2 分鐘查 TWSE MIS、寫 Redis」，friendly schedule 從不完整的「09:00–13:00」訂正為實際守門「交易日 09:00–13:30 每 2 分鐘」；(b) 每日 07:00 job 改稱「櫃買／海外 code-keyed 指數日線回補」，描述明列「TPEX＋8 檔海外指數＋SP500TR＋TWSE 報酬指數增量」；(c) 09:00／12:00 job 改稱「美股指數日線落後補救檢查」，避免暗示會逐盤檢查 TPEX。所有 cron 不變，美股個股項仍每 2 分鐘。不得新增 `@Scheduled`，清單仍 55 筆＝business 21＋external 34。

### 自動化測試

- [ ] 346.13 external client 測試用可控 `HttpClient`／fixture 或抽出的 package-private parser 驗證：第一次 `tse` 查無後會查 `otc`；合法 OTC 的 code/name/z/y/o/h/l/volume 正確映成 `PriceResult`；`z='-'` 回 empty。禁止新增測試專用 HTTP endpoint。
- [ ] 346.14 `PricePoller` 測試驗證 OTC fixture 結果會交現有 writer、empty 不寫、source 模擬快照持股帶入 `0000` 時不呼叫 client／writer；reflection 驗證 TW／US 兩支 `@Scheduled` 的 cron 與 zone 精確為每 2 分鐘。
- [ ] 346.15 external module 測試 `MacroDataFetchClient` 的 root `{stat:"ok",tables:[{fields,data}]}` TPEx OHLC 月 fixture、「成交張數」新 schema 與「成交股數（仟股）」舊 schema 量能 fixture、民國年轉換、兩個 alias 均×1,000、日期 join、量能失敗不丟 OHLC、過去完整月 OHLC 失敗整批失敗，以及 MIS `staticObj.key/ohlcArray[].t/c` 一分 K→五分格、最後點缺 `ts` 仍收錄、`Asia/Taipei 09:00–13:30` 補齊。另斷言 `US_INDEX_YAHOO`與 `INDEX_INTRADAY_YAHOO` 都不含 TPEX；這些常數／parser 不在 backend，禁止把測試放錯 module。
- [ ] 346.16 backend 測試涵蓋 TPEX 可經相容 GET query、refresh／export 白名單、相容 GET 仍可查 SP500TR、SP500TR 仍可 refresh 但不進頁面白名單、TPEX incoming null volume 保留同日既有非 null volume、每日全量管理 TPEX、self-heal 分類含 TPEX 且 09:00／12:00 美股 gap-check 不含 TPEX。
- [ ] 346.17 BFF 測試把 catalog 擴為 10 個，釘住順序與中文 label；public API 測試覆蓋 10 markets × 8 ranges＝80 組，TPEX daily/intraday 回應的 market/range echo、label、固定等長陣列與昨收規則。既有 TWSE 與 8 海外市場不可回歸。
- [ ] 346.18 排程清單測試釘住台股個股文案、friendly schedule「交易日 09:00–13:30 每 2 分鐘」與兩分鐘 cron、每日全量 job 與美股 gap-check job 的名稱／描述；總數仍 55、business 21、external 34。

## 驗證

```bash
# 三個 JVM module 測試（Java 25 的 Mockito 需 extraArgLine）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 實機無快取重建／recreate；每個上游換容器後 restart BFF
docker compose -p asset-management build --no-cache external-materials-service business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff
docker compose -p asset-management build --no-cache frontend
docker compose -p asset-management up -d --no-deps --force-recreate frontend
docker compose -p asset-management restart bff

# public API（host 僅走 127.0.0.1:9090）
curl -fsS 'http://127.0.0.1:9090/api/public/market-index?market=TPEX&range=10y'
```

實機驗收：

- [ ] 畫面下拉前兩項為「台股集中市場」「台股櫃買市場」，切 TPEX 後 10 年日線非空，日期約覆蓋 10 年，OHLC 非空，資料陣列等長，成交量子圖可見。
- [ ] authenticated legacy page API 回非空且等長的 `dates/closes/ma5/ma20/ma60/ma240/volumes/turnovers`，並查證起訖日、筆數、`hasVolume=true`、至少一筆 `volume>0`、`turnovers` 全 null；它的契約不含 market/range/label，不得虛構。host public API 另回 `market=TPEX`、`range=10y`、`marketLabel=台股櫃買市場`，同樣 `hasVolume=true`、至少一筆正 volume、turnovers 全 null且所有陣列等長；兩者都不能只看 HTTP 200。
- [ ] 查 DB `us_index_daily_history WHERE index_code='TPEX'`，起訖日、筆數與正 OHLC 合法，且至少一列 `volume>0`；沒有新增資料表或 Liquibase changeset。
- [ ] Docker logs 無 ERROR／持續 restart；BFF 在所有上游 recreate 後已 restart，瀏覽器實際操作與截圖無視覺回歸。
- [ ] 若驗收時休市，不繞過 `MarketClock` 強寫 LIVE。唯讀驗證 TWSE MIS 的上市 `tse` 與上櫃 `otc` 路由可回正確 code；盤中兩分鐘 Redis 寫入以 fixture、cron reflection 與 writer 測試為主要證據，完成報告明列時段限制。

## 完成報告

**完成日期：** 待實作後填寫  
**變更檔案：** 待實作後逐檔列出  
**測試結果：** 待填寫（每個 module 的 tests/failures/errors/skipped）  
**Docker／畫面／API／DB 驗證：** 待填寫（含 TPEX 起訖日、筆數、陣列等長、容器狀態與休市限制）
