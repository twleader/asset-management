# [t512] 台幣兌美元與油價金價改為多筆獨立排程

**對應 Requirements:** Requirements41／42／72／145（兩頁匯出排程獨立化與範圍）
**前置任務:** t511（同session合併驗收；本檔契約自足）
**Liquibase changeset:** v1.158.0-independent-market-export-schedules.sql

## 背景

使用者在歷年資產/已實現損益改造進行中，另外明確要求台幣兌美元、油價金價也改成多筆獨立排程。原每owner唯一parent與多child時間共用range/目錄/Drive；改造後各row各自設定。

## 要做什麼

- [x] 512.1 依以下完整契約改造兩頁後端/BFF/DB及既有測試。
- [x] 512.2 兩頁Vue/API client完整多筆CRUD與range獨立設定。
- [x] 512.3 獨立審查、完整測試、四頁runtime驗收、schema重產、no-ff合併推送並由main部署。


## Task 512 修訂：台幣兌美元與油價金價多筆獨立排程（Requirements 41／42／72／145）

**User Story:** 作為使用者，我希望台幣兌美元與油價金價也比照交易紀錄，能新增多筆獨立匯出排程，每筆分別設定名稱、時間、啟用、資料範圍、目錄、Google Drive，並各自編輯、刪除與立即匯出。

**Acceptance Criteria:**

- [ ] 每頁每 owner 最多10筆，一列一個時分與各自guard/status、name、outputSubpath、rangeMonths、Drive設定；GET無列回空清單、不寫DB。新增先開enabled=false草稿，儲存才POST，取消零寫入、失敗留草稿；清單以server完整排序回應取代、操作防重入；載入錯誤與空清單分開，提供重試。每列清楚顯示名稱／時間／停用與否／資料範圍／本機和Drive狀態，資料夾picker只改隔離draft。
- [ ] API：business base為 `/api/exchange-rate-export/schedules` 與 `/api/commodity-export/schedules`；BFF base各為 `/api/bff/exchange-rate/export/schedules` 與 `/api/bff/commodity-price/export/schedules`。GET/POST base、PUT/DELETE `/{id}`、POST `/{id}/run-now`。舊GET/PUT `/schedule`及無id `/run-now`回410提示重新整理、零副作用；browse與手動下載端點維持。無認證401、Drive無權403、他人或未知id404、非法欄位或第11筆400。
- [ ] request為 `{name,enabled,runHour,runMinute,outputSubpath,rangeMonths,gdriveEnabled,gdriveSubpath}`，時分必填整數且0–23/0–59，enabled缺省false。name trim、空白/null→null，其餘最多20個中英數／底線／連字號／空白；rangeMonths為null（全部十年）或整數1..120，UI保留原選項1/3/6/12/36/60/120、null顯示全部十年，120與null匯出範圍相同。Drive欄null保留原值、create原值false/null，只有configured-admin能啟用、啟用自檢警告不入庫；輸出子路徑空白仍input。GET/CRUD回 `{baseDir,gdriveRemote,schedules:[{id,name,enabled,runHour,runMinute,outputSubpath,rangeMonths,lastRunAt,lastRunStatus,gdriveEnabled,gdriveSubpath,gdriveLastRunAt,gdriveLastStatus}],gdriveSelfCheckWarning}`，按時分/id排序，時間字串台北yyyy-MM-dd HH:mm:ss、未執行null；run-now回原七欄path/sizeBytes/gdrivePath/gdriveStatus/jsonPath/jsonSizeBytes/jsonGdrivePath。
- [ ] 遷移每個舊child各一row、首個按時分/id沿用parent id、其餘新id，enabled=原parent AND child，child時分/guard/status與parent目錄/rangeMonths/Drive設定完整保留；無child原parent保留其原時分/enabled/guard/status。全部name=null，舊共享Drive執行結果清null、不假冒逐筆結果。先檢查split後每owner最多10筆，超限整筆transaction rollback、不截斷；移除owner unique、兩張children與JPA mapping，parent時分/guard成唯一事實。新migration可冪等重跑，不再split或清新狀態；舊image不相容，回退需受控DB rollback。
- [ ] 既有每分鐘tick與startup共用AtomicBoolean，按時分/id逐row補跑、完成不論成功失敗只設該row當日guard，一列失敗不阻斷其他row/owner。run-now忽略enabled但不消耗guard、不建立未知id；編輯設定保留guard/status。owner namespace advisory transaction lock涵蓋首筆建立與10筆上限/CRUD；id+owner fresh row lock capture不可變設定，交易外doc/render/local/Drive；complete只寫仍存在且captured name/enabled/time/path/range/Drive全部相同的row、不復活刪除、晚結果不覆蓋編輯。
- [ ] 匯率仍以 `ExcelExportService.exchangeRatesDoc("USD",start,end)`、油金仍 `commodityPricesDoc(start,end)`，都是既有全域公開歷史來源、不加owner行情過濾或request-time外部抓取；各row依台北執行日end與rangeMonths算start，null=end.minusYears(10)，其餘end.minusMonths(N)。一次doc render雙格式，沿用local-first/Drive best-effort，保存時及產檔前重驗基底與symlink containment。無name沿用 `台幣兌美元_{ownerId}_yyyyMMdd`、`油價金價_{ownerId}_yyyyMMdd`，有name在owner後插`_{name}`，副檔名xlsx/json。同日同目錄同name合法覆寫，UI說明用不同name或目錄保留不同版本。
- [ ] 與Task511同session驗收四頁；commodity/exchange舊singleton/children測試改成多row的對等實質覆蓋，保留雙格式/Drive/資料來源/租戶隔離及其他功能測試。新增isolated PostgreSQL migration/超限rollback/冪等、首筆併發上限、stale completion、guard、range獨立性、名稱檔名與symlink測試；BFF新路由/410；前端測試/build與四頁互動。重建三服務、遷移前後receipt比對、重產schema與drift=0、no-ff合併推送、乾淨main重建/readback。不得改9090/SRPP或交易。

## Task 512：匯率及油價金價獨立匯出排程設計

此修訂取代Requirements41/42/72/145舊singleton+children模型，並取代Task511「不更改commodity/exchange-rate行為」的範圍限制；Task511兩頁契約不變。兩功能各自保留現有table/service/controller/BFF，parent新增nullable name varchar(20)、owner index，移除owner UNIQUE與children；run_hour/run_minute/last_run_date成唯一事實，range_months與其他設定各row獨立。DTO為record；各自immutable capture與short-transaction execution store比對完整設定（含range），不改其他匯出業務表。

business路由base為 `/api/exchange-rate-export/schedules`、`/api/commodity-export/schedules`，BFF為 `/api/bff/exchange-rate/export/schedules`、`/api/bff/commodity-price/export/schedules`；兩頁API client各走自身BFF。GET清單、POST新增、PUT/DELETE id、POST id/run-now；舊singleton與無id run-now410，無副作用；共用browse仍走同一business能力、各頁自身BFF。完整request/response/error/range/Drive語義與上述Task512需求一致。

Liquibase `changes/v1.158.0-independent-market-export-schedules.sql` 單一PostgreSQL DO transaction、splitStatements:false，接在v1.157之後註冊master。每張child存在才split，修改parent前先保留完整原parent資料供其他child複製，首child按(hour,minute,id)保留parent id，其餘nextval；AND原兩級enabled、child guard/status、parent range/path/Drive設定完整保留，無child原row不動guard。清空無法歸屬的舊共享Drive結果、name初始null；preflight兩功能每owner split後>10就RAISE整體rollback，owner unique移除、加index，成功split才drop children。child已不在時重跑不再清空新狀態；欄位/constraint/index採冪等保護，保留原CHECK/NOT NULL/owner FK現狀。無舊image rollback相容保證。

四頁共同UI操作規範可共用純草稿helper，但不跨頁BFF或複製business資料計算；匯率/油金額外保留range選單，任意整數1..120的既有值不可靜默改成120，null可顯示全部十年。每筆執行單次讀原歷史資料源、兩render/dual writer，baseName空name保持舊字節、有name只加suffix；同名覆寫在UI明示。owner lock覆蓋無row併發與CRUD，short transaction捕捉/寫回，IO在交易外；失敗隔離/guard/run-now/stale completion行為與需求逐項一致。排程列表維持原兩個poll，文案改為多筆獨立排程（時點依DB），不加annotation、不寫死執行時間。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
cd frontend
npm test
npm run build
```

隔離PostgreSQL測試證明多child split、parent停用、單child停用、無child、range與guard繼承、冪等、超限整體rollback。service實測每row不同range輸出start/end、owner 404、併發上限、stale complete、單row failure隔離與run-now guard不變；保留雙格式/Drive/site既有測試。BFF新route與410零副作用，前端取消/重複操作/錯誤/範圍/多目錄驗證。只新增disabled/Drive=false/隔離路徑驗收row，測後刪除並清理檔案，不能更動原排程或guard。

```bash
docker compose -p asset-management build business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
bash scripts/tests/schema-sql-drift-test.sh
```

部署前main .env及mount一致（僅比是否相同不印secret）；停止舊business再由新image Liquibase遷移，receipt前後逐欄比對。按db/schema.sql檔頭pg_dump重產與更新表數：原117減Task511兩表再減本Task兩表為113（若有其他合法schema進展依實際dump）。drift須0，main no-ff push/remote-ref讀回後從乾淨main重建三服務再次驗證。正式登入UI若無工具可用明示未驗，隔離mock互動不可宣稱正式登入驗收。

## 完成報告

四頁實作與feature驗收完成，紀錄如下。


### 2026-10-26 驗收紀錄

- 已完成四頁獨立排程；各頁自己的 BFF/API 支援 CRUD、指定 id run-now，草稿與目錄隔離、每頁 owner 上限10筆、Drive 權限與雙格式匯出均保留。匯率／油金每筆 rangeMonths 獨立，合法自訂7個月已驗證。
- spec 兩輪獨立審查均0 findings；arch最終 critical/major/minor 均0，已記錄內容雜湊。機械檢查 BLOCK0/CHECK1，該 CHECK 是排程程式重排觸發的人工確認：原四個每分鐘poll及startup數量不變、registry只更新文案，兩次獨立審查均已核實，無新增排程。
- backend 完整3274 tests，0 failures/0 errors，2個既有具名條件略過：IndexExportScheduleServiceTest Linux檔案系統案例在mac不適用；SrppReportRepositoryPostgresTest size-probe未設啟用property。最後PG增量108 tests通過；BFF完整915 tests通過；frontend143 tests通過且production build成功。
- 完整測試另外修正3個既有test fixture：Fubon結清案例加入同bank足額MANUAL現金，驗到期後總值守恆；DailyDecisionCalculatorTest由embedded manifest canonical bytes與model原始bytes獨立驗hash取代過時硬碼；TradingRadar route fixture補Method predicate bean。沒有修改相關金融production規則、SRPP政策、Swagger或authority資源。
- feature已重建/recreate business-services、bff、frontend；main與feature有效環境、bind mounts皆MATCH。停止舊business後由新image套v1.157/v1.158，原BFF等upstream healthy後safe GET首次即恢復，再因本次BFF有改才正常recreate；三服務健康/HTTP200，公開安全GET正常。
- 遷移8表baseline逐欄比對PASS：資產/損益/匯率/油金各4/4/4/3筆，首id、時分、AND enabled、guard/status、目錄/range/Drive設定保留；無法歸屬逐筆的共享Drive執行歷史依契約清null，4張child移除。db/schema.sql已從正式PostgreSQL完整重產為113表，schema-sql-drift-test逐位元PASS。
- 真實容器API四頁CRUD、401/404/400/410、重新整理提示、有效XLSX workbook與可解析同名JSON、run-now不動guard均PASS；disabled/Drive=false測試列與隔離產物已全清，原15筆設定讀回未改。
- 真實編譯容器UI搭配全API/mock auth fixtures驗證四頁載入錯誤重試、取消零寫入、目錄草稿隔離、失敗保留、busy防重、CRUD/run皆PASS，匯率/油金range7選項和值保留PASS。正式登入UI為NOT_VERIFIED，未取cookie或建立假正式session；不得將mock互動當正式登入證據。
- 日誌與receipt：/tmp/asset-gain-511-backend-full-final.log、/tmp/asset-gain-511-pg-final.log、/tmp/asset-gain-511-bff-full-final2.log、/tmp/asset-gain-512-spec-final.txt、/tmp/asset-gain-512-before.json、/tmp/asset-export-512-ui/results.json。Git收尾採feature中文commit、main no-ff merge、push及remote-ref讀回；合併後由乾淨main重建三服務並重新驗收，最終SHA與部署證據由本session最終回報確認。
