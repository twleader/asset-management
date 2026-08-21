# [t354] TWSE 未公布時以 DGPA 完整行事曆暫行推導台股交易日

**對應 Requirements:** Requirement 37（交易日曆固定查詢去年／今年／明年，並以 TWSE 優先、DGPA 暫行 authority 支援尚未公布年度）
**前置任務:** t351
**Liquibase changeset:** 無

## 背景

2026-08-21 查詢 TWSE `holidaySchedule?date=2027` 時 `data=[]`，既有 fail-closed 行為因此把 2027 台股標為 `UNAVAILABLE`，今年＋明年的匯出也只成功產出 2026。行政院人事行政總處已發布 116 年（2027）辦公日曆，且 data.gov.tw 資料集 14718 提供該年度 UTF-8 CSV；正確行為是在 TWSE 尚未公布時，使用這份完整官方日曆作暫行 authority。

台股不能直接把 DGPA 全部辦公日都當交易日：使用者明訂「春節前兩個工作日台股沒有開盤，其餘工作日都有開盤」。算法必須由每年度資料推導，不寫死特定日期：找到最早一筆備註含「春節」的 row，再往前找兩筆 DGPA 工作日。依 2027 官方資料，春節錨點前的兩個工作日為 2/2、2/3；依 2026 資料則為 2/12、2/13，且與 TWSE 官方年度表的「市場無交易」相符。

DGPA 是暫行來源，不可永久遮蔽後來發布的 TWSE 年度表；TWSE 一旦有效，必須在 cache 重新驗證時自動升級並維持第一順位。

## 要做什麼

- [ ] 354.1 在 `external-materials-service` 定義可 mock 的 `DgpaCalendarAuthority` port，`MarketDataFetchService` 只依賴此抽象；在 `client` 外層新增 `DgpaCalendarClient` 實作。不得把 HTTP、metadata 或 CSV 解析塞入 controller，也不得讓 backend／BFF 直接連 DGPA。
- [ ] 354.2 `DgpaCalendarClient` 先以 15 秒 request timeout GET `https://data.gov.tw/api/v2/rest/dataset/14718`。從 `result.distribution` 選一般 `CSV`：`resourceDescription` 只能是「{year-1911}年中華民國政府行政機關辦公日曆表」或其單一括號 revision，含 `_Google行事曆專用` 排除；encoding 只接受 `UTF-8`／`BIG5`，但不得先按 encoding 過濾。版本以 description 語意排序：任何 revision 勝過原版；只有一筆 revision 就選它；多筆 revision 必須全部含可解析且屬 requested ROC year 的 `{ROC}MMdd更新` 才按 revision date 取最新，否則 unavailable。`resourceQualityCheckTime` 只可作相同 description／同 revision 重複 resource 的次要 tie-break，絕不可作版本發布時間；仍 tie 就 unavailable。
- [ ] 354.3 metadata 的 `resourceDownloadUrl` 視為不可信輸入：scheme 恰為 `https`、host 恰為 `www.dgpa.gov.tw`、port 只能 `-1` 或 `443`、path 恰為 `/FileConversion`、user-info／fragment 都為空，且 query 恰有一個 `filename` 參數、其值 path 以 `.csv` 結尾；duplicate filename、alternate port、其他 host／scheme／path、非 CSV 都拒絕。HTTP client 不得自動跟隨未重新驗證的跨 host redirect。合格 URI 以 15 秒 timeout 抓取，只有 HTTP 200 body 才進 parser。
- [ ] 354.4 抽出同一 package 可共用的 quoted CSV row parser，讓 `DgpaCalendarClient` 與既有 `TreasuryYieldFetchClient` 共用；必須維持雙引號 escaping 與逗號包在 quoted field 內的行為，禁止複製第二套易漂移 parser。DGPA body 依 metadata 宣告的 UTF-8／BIG5 建立 `CharsetDecoder`，malformed／unmappable 都用 `CodingErrorAction.REPORT` 嚴格拒絕，不可用 replacement character 吞錯；解碼後移除第一欄可選 BOM，header 必須精確為 `西元日期`、`星期`、`是否放假`、`備註` 四欄。
- [ ] 354.5 DGPA data 必須完整 fail closed：資料列數恰為 `Year.of(requestedYear).length()`；`西元日期` 以 `yyyyMMdd` 解析且全部屬 requested year；日期唯一並完整涵蓋 1/1～12/31；中文星期 `日一二三四五六` 與日期實際星期吻合；`是否放假` 只允許字串 `0` 或 `2`；每列恰四欄。任一重複、缺日、錯年、錯星期、未知 flag、malformed quoted row 或額外 schema 都拒絕整年，不回部分 map。
- [ ] 354.6 由驗證後 rows 推導 base：DGPA `是否放假=2` 且為週一至週五的日期加入休市 map，名稱用非空 `備註`，空白則用 `行政院人事行政總處放假日`。依日期升冪找第一筆 `備註` 含「春節」的 row，向前掃描並挑出兩筆 `是否放假=0` 的工作日，跳過星期六／日與 flag `2`；兩日加上 `農曆春節前市場無交易`。找不到錨點或不足兩個工作日就拒絕整年。除此之外 DGPA `0` 的平日不加入 holiday map，讓既有 `weekday && !holidays.containsKey(date)` 判定為交易日。不得維護 2026／2027 特例清單。
- [ ] 354.7 `MarketDataFetchService` 以 shared per-year loader 固定依序執行：先 `fetchTwHolidaysFromTwse(year)`；非空就回 TWSE 且 DGPA 零互動；TWSE 空／錯誤才呼叫 `DgpaCalendarAuthority`。兩者都空時 base unknown。`getTwHolidays` 與 `getTwHolidaysKnown` 必須共用這個 loader，operator closure 仍只在 read-time union，不污染 authority cache；closure calendar unknown，或 base unknown 但 closure-only 非空，都不可升格為 known。backend `MarketDataService.getTwHolidays` 對非當年度移除永久 `twHolidayCache` 短路，每次都 proxy `/internal/tw-holidays`，讓 external 的 source-aware cache 成為非當年度唯一 authority cache；當年度既有 10 分鐘抗瞬斷／operator closure 傳播 cache 保留。
- [ ] 354.8 external 年度 cache entry 改存 immutable `{holidays, source, expiresAt}`。`TWSE` entry 可永久沿用；`DGPA_PROVISIONAL` entry TTL 最多 6 小時，到期時必須先重試 TWSE，若成功就於同一次讀取原子覆蓋 DGPA entry，後續不再呼叫 DGPA。若到期重抓時 TWSE 與 DGPA 都暫時失敗，保留最後一份完整驗證過的 DGPA map，最多 10 分鐘後再重試；從未成功的空值不進永久 cache。時間來源須可注入／控制。同一 year 的更新固定採 immutable observed-entry 的 source-aware optimistic CAS，順位為 `TWSE > DGPA_PROVISIONAL`：CAS／`putIfAbsent` 失敗後重讀 incumbent；candidate 是 TWSE、incumbent 是 DGPA 時須用新 incumbent 持續重試，直到 TWSE 安裝成功或已有另一個 TWSE，不能丟掉有效 TWSE；candidate 是 DGPA 而 incumbent 已是 TWSE 時才丟棄 candidate。首次空 slot 若 DGPA 先 `putIfAbsent` 成功，較晚 TWSE 仍必須依同規則升級。禁止用全域鎖或同年度序列化替代，因下一條測試必須允許兩個同年 refresh 同時在途；不同 year 也不可互相阻擋。backend 必須另有測試證明兩次非當年度讀取都會碰 external，第二次可看見由 DGPA 升級後的 TWSE map。
- [ ] 354.9 `TradingCalendarExportService` 每年度只建立一次 immutable `CalendarAuthoritySnapshot`（或等價 record）：台／美／英假日各查一次並 defensive copy，台股空 map 即該年 fail closed。`exportYearPairToDir` 不另做會重複查詢的 preflight，改把 snapshot 傳給 private `exportToDir` overload；既有 public `exportToDir(year, subpath)` 仍保留並自行建立一次 snapshot。`buildDays` 逐日以 `非週末 && !snapshot.containsKey(date)` 算三市旗標，禁止再呼叫 `MarketDataService.isXxxTradingDay`；`buildJson` holidays 與 Excel days 使用同一 snapshot。每年度每市場 authority query 精確一次，DGPA→TWSE TTL 邊界或後續 transport failure 都不得讓單一 JSON／Excel 混版。
- [ ] 354.10 保留既有 `GET /internal/tw-holidays?year=`、backend `/api/market-data/holidays`、BFF response、前端 `availability` 與匯出 API shape，不新增第二支假日 API、不新增 DB／Liquibase。2027 DGPA base 非空後，既有 BFF 自然回 `availability.tw=AVAILABLE`，既有雙年度匯出自然產 2026／2027 JSON＋XLSX 四檔；TWSE／DGPA 都不可用時仍維持原有 unknown 與逐年度失敗語意。同步更新 `MarketCalendar`、`MarketDataController` 與其他受影響 current-state Javadoc／log，把 TWSE-only 文字改為 TWSE primary／完整 DGPA provisional／unknown，不得留下與 shared loader 相衝突的來源宣稱。
- [ ] 354.11 新增／更新單元測試，至少覆蓋：metadata／Google 排除、revision ranking（原版 QC time 刻意較晚仍選 `1141020更新`；多筆無日期 revision 與同 revision tie unavailable）、alternate port／duplicate filename、UTF-8／BIG5 strict decode、BOM、quoted comma／escaped quote、365／366 日、缺日／重日／錯年／錯星期／未知 flag／錯 header、缺春節錨點／不足兩工作日；2025 選 BIG5 revision 並含 9/29、10/24、12/25，2026 加 2/12＋2/13，2027 加 2/2＋2/3。TWSE 有效時 DGPA 零互動；TWSE 空 fallback；首次雙空可恢復；closure-only／unknown closure fail closed；6 小時前沿用 DGPA、到期升級 TWSE、到期雙失敗保留 DGPA 並退避。cache race 對 expired DGPA entry 與首次空 slot 各驗兩種完成順序：TWSE 先安裝／DGPA 後到時 DGPA 採 incumbent TWSE；DGPA 先安裝／TWSE 後到時 TWSE 用新 incumbent 重試並永久升級。四種情境最終都是 TWSE且後續 DGPA 零互動；另驗跨年獨立。backend 非當年度依序讀兩份 map，證明第二次不被永久 cache。匯出測試驗每年度每市場精確一次；mock 後續擲例外或換 map，JSON／Excel仍只用第一份 snapshot。既有 TWSE report、Treasury CSV、backend、BFF、frontend 全回歸通過。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/bin/npm --prefix frontend run build
bash scripts/spec-check.sh
docker compose -p asset-management build external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate bff
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
docker compose -p asset-management up -d --no-deps --force-recreate bff
docker inspect asset-external-materials-service asset-business-services asset-bff --format '{{.Name}} image={{.Image}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}'
docker exec asset-external-materials-service curl -fsS 'http://127.0.0.1:8080/internal/tw-holidays?year=2027'
docker exec asset-business-services curl -fsS -X POST 'http://127.0.0.1:8080/api/trading-calendar-export/run?subpath=.tmp/codex-t354-20260821'
```

實機驗收以本輪 image 確認 `external-materials-service`、`business-services` 與 BFF 都 healthy，且三者無新增 error／exception log。查詢 2027 的 internal 台股假日 map 必須非空並含 `2027-02-02`、`2027-02-03`；登入交易日曆頁查看 2027 時台股為 AVAILABLE，2/2、2/3 顯示休市，其餘 DGPA 平日正常顯示交易。

雙年度匯出只能使用上列**從 business container 對 loopback 發出的 identity-less POST**：不得帶任何 `X-User-*` header，固定安全子路徑 `.tmp/codex-t354-20260821`，使 `CurrentUserContext.hasUser()==false`、owner 設定列為 null，從控制流程上不可能進入 Drive 上傳。只呼叫一次並保存／檢查該次 response：`years` 恰為 `[2026,2027]`，四個本機 path 都存在；batch 與每年度的 Drive paths／`gdriveStatus` 均為 null。host `/Users/steven/.tmp/codex-t354-20260821/交易日曆_2027.json` 必須為 365 天，2/2、2/3 的台股交易旗標為 false；本輪 business log 必須沒有 rclone／Drive sync。不得為了驗證而使用已登入 BFF 匯出、不得打排程 run-now，也不得上傳任何測試檔到 Drive。

## 完成報告

（實作者完成後回填：實際修改檔案、單元／全模組測試、architecture audit、Docker image／health／log、2027 internal map 與四檔匯出證據，以及任何偏差與原因。）
