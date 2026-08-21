# [t351] 交易日曆三年查詢窗口與今年明年雙年度匯出

**對應 Requirements:** Requirement 37（交易日曆固定查詢去年／今年／明年，手動與排程固定匯出今年／明年）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前 `TradingCalendarView` 可用上月／下月無限跨年，BFF 的 `GET /api/bff/trading-calendar?year=` 也接受任意年份；匯出對話框則讓使用者輸入任意單一年份，`POST /api/trading-calendar-export/run` 與每日排程一次只產該年／當年的 JSON＋Excel。正確行為是：畫面可查範圍固定為台北時區當下的去年、今年、明年共三年；任何匯出都不跟查看年份走，而固定嘗試今年與明年。兩年 authority 都可用且雙格式皆成功時，共有四個本機檔；已啟用 Drive 時再依「同年度兩份皆成功」守門上傳最多四份，authority 不可用時必須明確失敗而非製造假日曆。

年度基準必須是 `Asia/Taipei`。例如台北時間為 2026 年時，畫面只可查 2025／2026／2027，匯出只可產 2026／2027；即使畫面正在看 2025，也不得匯出 2025。

## 要做什麼

- [ ] 351.1 在 BFF 的 `tradingcalendar` 頁面範圍內新增可注入 fixed `Clock` 測試的年份窗口元件；production clock 固定 `Asia/Taipei`。窗口固定回 `availableYears=[Y-1,Y,Y+1]`、`minYear=Y-1`、`maxYear=Y+1`，並只接受這三年。
- [ ] 351.2 `GET /api/bff/trading-calendar` 省略 `year` 時查今年；指定年份超出三年窗口時回 400，且不得呼叫 `/api/market-data/holidays` 或 `/market-status`。成功回應保留 `holidays`／`marketStatus`／`year` 並新增 `availableYears`／`minYear`／`maxYear`／`availability`；`availability.tw/us/uk` 只在對應假日 map 非空時為 `AVAILABLE`，否則為 `UNAVAILABLE`。通用 business `/api/market-data/holidays` 不加三年限制。`MarketDataFetchService.fetchTwHolidaysFromTwse(year)` 改用 TWSE 官方歷年端點 `https://www.twse.com.tw/holidaySchedule/holidaySchedule?response=json&date={year}`；只有 HTTP／`stat` 成功、頂層 `date` 與 ROC 年 `title` 都吻合 requested year、`data` 非空且每筆日期同年才可當完整 base，錯年／空列／解析失敗回空。既有 external-materials `GET /internal/tw-holidays` 再改走 `getTwHolidaysKnown(year).orElse(Map.of())` 的同等語意，不新增另一支 API：只有 TWSE base 非空且 closure calendar 已知才回資料；首次空後要能重新 fetch，base 空但 closure-only 非空與 closure calendar unknown 都必須回空。
- [ ] 351.3 `TradingCalendarView.vue` 首次查詢不帶年份，以 BFF 回傳年份與範圍初始化；在日曆工具列新增只有三項的年度選擇器。`prevMonth`／`nextMonth` 必須在函式內阻擋越界，且去年 1 月的「上月」與明年 12 月的「下月」按鈕 disabled；「今天」回到 BFF 的今年與台北當月。既有頁面專屬 BFF 與 holiday cache 繼續使用，但 cache entry 必須逐年同時保存 `{holidays, availability}`（或等價的兩份 year-keyed cache）；日格與警示只讀目前 `calendarYear` 的 entry，AVAILABLE 年 → UNAVAILABLE 年 → cached AVAILABLE 年與反向切換都不得沿用別年的 availability。某市場 `UNAVAILABLE` 時該市場日格值設 `null`、不畫交易圖示並顯示具市場與年份的警示，其他市場照常；不得把空 map 推導成所有平日皆交易。`dayClass` 只有在 `tw/us/uk` 三值全是 boolean 時才可判 `both`／`holiday`；全 unknown 或部分 unknown 都維持 neutral，不得把 `null` 畫成「假日／休市」。
- [ ] 351.4 在 `TradingCalendarExportDto` 新增不可變 `RangeRunResponse`，欄位固定為 `List<Integer> years`、`List<RunResponse> results`、`String localStatus`、`String gdriveStatus`。兩個 list 依年份升冪且恰兩筆，`results[i].year == years[i]`；`RunResponse` 仍是一個年度的雙格式結果，不合併兩年 payload。
- [ ] 351.5 在 `TradingCalendarExportService` 新增 `exportYearPairToDir(int anchorYear, String subpath)`，固定依序嘗試 `anchorYear` 與 `anchorYear+1`。進入逐年 `try/catch` 前先呼叫既有 `requireValidSubpath`；非法跳脫直接保留 HTTP 400，且零 authority query、零檔案，不得轉為年度失敗 200。合法路徑下，每年先要求 `MarketDataService.getTwHolidays(year)` 非空；空值代表 TWSE authority unavailable，該年不得呼叫會把空 map 當交易日的 primitive、不得建立檔案。每年各自窄 `try/catch`，空 map 或 authority／產檔執行期 RuntimeException 都轉成 null paths、`totalDays=0`、含年份的失敗 `localStatus`，並繼續另一年；batch 即使兩年皆失敗仍回兩筆結果。成功路徑只組合既有 `exportToDir`，不得複製逐日、JSON 或 Excel builder；每年仍使用 `交易日曆_{year}.json/.xlsx`、tmp＋atomic move、365／366 天與既有部分格式失敗語意。
- [ ] 351.6 business `POST /api/trading-calendar-export/run?subpath=`、BFF `POST /api/bff/trading-calendar/export?subpath=`、`frontend/src/api/index.js` 與畫面呼叫都移除 `year` 契約。手動路徑以 `LocalDate.now(Asia/Taipei).getYear()` 呼叫雙年度 service；查看中的年份不得傳入或影響匯出。
- [ ] 351.7 `TradingCalendarExportScheduleService.runScheduled` 改用 `today.getYear()` 呼叫相同雙年度 service；一次命中必須處理今年與明年，不可維持只產當年的舊路徑。`last_run_status` 聚合兩年的 `localStatus`，固定保留年份標籤並控制在既有 500 字元欄位內。任一年失敗仍只消耗本次既有當日 guard，不得每分鐘重試到午夜。
- [ ] 351.8 Drive 未啟用時不呼叫 `GdriveOutputSupport`。啟用時以年度為守門單位：該年 xlsx/json path 兩者都非空才成對 best-effort 上傳，完整成功共四次且目標子路徑相同；缺任一份時該年兩份都略過且絕不撿舊檔，另一年完整時仍上傳兩次。每年 `RunResponse.gdriveStatus` 維持 `xlsx …／json …` 供共用前端判斷，batch 與 `gdrive_last_status` 另以年份標籤聚合並控制在 512 字元。
- [ ] 351.9 匯出對話框移除可編輯年度輸入，唯讀列出 BFF 定義的今年、明年與四個預期檔名。完成區逐年列出每筆 `localStatus` 與實際存在的本機落點；至少一個 path 存在才可顯示「已匯出」成功標記。對 `results` 每筆呼叫既有 `showDualExportResult`，以「{year} 年」作 prefix，並為 helper 新增向後相容的 optional `localStatus`：雙 path 為空時顯示後端年度／authority 原因，不得誤報磁碟或權限；其他頁面及既有單格式／Drive 部分成功判斷不變。排程提示同步改為每天產今年與明年四個檔。
- [ ] 351.10 因四次串行 Drive copy 每次最多 45 秒，`frontend/nginx.conf` 在通用 `/api/` 前新增 exact `/api/bff/trading-calendar/export` 代理區塊，沿用相同 upstream 與 proxy headers、只把 `proxy_read_timeout` 設 240 秒；`bffApi.tradingCalendar.exportToDir` timeout 設 250000 ms。其餘 API 仍 60 秒，不採未驗證安全性的並行 rclone。
- [ ] 351.11 更新 `SchedulePublicBffController.JOBS` 的交易日曆說明為「命中執行時間即為今年與明年各自同時產出 JSON 與 Excel 兩份（主檔名相同），共四檔」，移除「當前年度交易日曆」舊說法；cron、friendly schedule、service 與 job 總數皆不變。測試須同時釘住「今年與明年」、「同時產出 JSON 與 Excel 兩份」、「共四檔」及拒絕舊措辭，不得放寬既有 Requirement 55 對所有 Excel 描述的雙格式守門。
- [ ] 351.12 補自動測試：fixed clock 驗三年列表與窗口外拒絕且 downstream 零互動；external-materials 以歷年端點 fixture 驗去年／今年解析、錯年 payload 與明年空 rows 回 unknown、首次空後第二次有資料能重新 fetch、TWSE base 空但 closure-only 非空仍回 unknown、closure calendar unknown 仍 fail closed；空 TWSE map 驗 BFF unavailable、前端不得產假 `tw:true`、匯出該年 null paths；非法 `subpath=../...` 驗 400、zero interactions／零檔，合法路徑某年失敗仍回兩筆 200；anchor year 驗兩筆排序、平年／閏年天數及四個檔名；第一年例外／第二年成功與反向都回兩筆；手動與排程驗相同雙年度呼叫；Drive 驗完整四次、某年部分失敗時該年零次且另一年兩次、狀態年份＋格式標籤。前端新增測試必須驗 `[null,null,null]` 與部分 unknown 均不回 `holiday`／`both`、available → unavailable → cached available 與反向切換讀正確逐年狀態、authority 失敗訊息含年度與原因且不含「已匯出」／磁碟／權限誤診、非台北 browser timezone 下「今天」仍使用伺服器年度、Nginx exact 240 秒、Axios 250 秒與排程列表措辭；同步更新 `frontend/package.json` 的 `test` script，確保這些新增測試檔真的會被 `npm test` 執行。既有單年度 JSON／Excel 契約測試維持通過。不得新增 DB schema、Liquibase、另一支 business 假日 API 或另一套前端訊息判斷。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
docker compose -p asset-management build external-materials-service business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service business-services bff frontend
docker compose -p asset-management restart bff
```

實機驗收需登入 `http://localhost/trading-calendar`：年度選擇器只有去年／今年／明年；去年 1 月不能再往前、明年 12 月不能再往後；去年與今年台股 authority 必須為 AVAILABLE，明年官方尚未公告時才可為 UNAVAILABLE；不可用市場有明確警示且不畫假交易／假休市狀態；來回切換三年後各年狀態仍正確。匯出對話框沒有年度輸入並列出今年與明年四個預期檔名。以測試子路徑觸發 `POST /api/bff/trading-calendar/export?subpath=...` 後，回應 `years` 恰為今年與明年、`results` 恰兩筆；authority 可用的年度存在 `.json`／`.xlsx`，JSON 的 `year` 與 `days.length` 正確；authority 不可用年度必須是 null paths 與明確失敗狀態，不得產假檔、不得出現成功標記或磁碟／權限誤診。Drive enabled 時須在 250 秒 client 預算內讀到最終回應並核對四個（或依年度守門略過後的）落點。四個受影響容器須為本輪 image、healthy，且 BFF 重啟後無 upstream connection refused。

## 完成報告

（實作者做完後回填：實際修改檔案、測試與 Docker／瀏覽器驗收輸出、四個檔案落點，以及任何偏差與原因。）
