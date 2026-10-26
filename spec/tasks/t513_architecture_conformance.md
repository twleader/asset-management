# [t513] 全專案架構審查與相容性重構

**對應 Requirements:** Requirement 198（既有 Controller／DTO／IO 邊界符規，保持所有金融與外部契約）
**前置任務:** 無；以 main 4d8c9abc 為本輪初始基準
**Liquibase changeset:** 無

## 背景

使用者要求檢查所有程式並重構架構違規。初查已證實新聞與基金 Controller 直接持有 Repository，基金 Controller 還直接使用 WebClient；市場與總經 Controller 回傳八類 JPA Entity。既有 Tasks401–404 有尚未實作的依賴隔離規劃，不能把文件宣稱當作落地。本次只修明確架構問題，不順手完成那些歷史任務的功能／生命週期變更。

## 要做什麼

- [x] **513.1 全範圍 coverage／finding ledger。** 檢視 backend、BFF、external-materials、fubon／yuanta adapter、frontend、schema、scripts、部署與契約；排除 generated／vendored／歸檔資料。每項記錄根因、檔案行號、規範、影響、修正與狀態；同根因不重複計數，具名例外不可當缺陷。五輪上限的全範圍獨立審查與停止規則依本次審查流程執行，未驗證不得宣稱成功。
- [x] **513.2 Controller 只做 transport。** NewsHeadlineController 改依 service，保留 Asia/Taipei 的 `[day 00:00,next day 00:00)`、date 空白預設今日、dateField published 忽略大小寫與 trim、category 原篩選及排序。FundNavController 的 fund CRUD／active、bank lookup、NAV／配息 enrichment 改由 service 負責；DTO／request 改放 dto package，避免 service 依賴 controller。保留全部 path／verb／JSON／狀態碼／null 與既有 fundCode trim／存在性判斷，不順手修資料語意。固定 refresh/backfill 由窄 maintenance port 與外層 HTTP adapter 實作，保留原 success／failed／fundOk／fundFail 等回應、錯誤與既有期限，不允許任意 URL。
- [x] **513.3 Entity 不作 transport。** MarketDataController 的 StockPriceHistory、ExchangeRateHistory、CommodityPriceHistory，以及 MacroHistoryController 的 TaiwanGdpPerCapitaHistory、JapanGdpPerCapitaHistory、KoreaGdpPerCapitaHistory、TwseIndexDailyHistory、UsIndexDailyHistory，改成 immutable record 投影。使用原實際序列化欄位，不漏 id、closeSource、sourceUrl、sourceAvailableAt、fetchedAt、realGdpGrowthRate、closePointTr、tradeVolume、tradeValue、volume。BigDecimal／LocalDate／Instant／null 不變；ResponseEntity 的 200／404 分支與列表查無資料的空陣列不變；純 DTO 轉換可在 Controller。原 Service 的 domain 查詢保持，不擴散到其他計算 caller、不加 migration 或資料副本。用獨立 Jackson fixture 比較原 Entity 與 DTO JSON 的完整相等，涵蓋 null 與非 null。
- [x] **513.4 IO 依賴向內。** 只對確認的業務 IO 直接依賴新增實際需要的窄 port，adapter 負責固定 HTTP／Redis／SDK，組裝點注入。內層不 new concrete adapter、不留 URL constructor 偷建、不回 concrete client 的私有 nested DTO。共享 immutable 值移中立層，caller／fixture 同步引用；不是公開 FQCN 相容性承諾。既有 already-outer adapter／pure function 不為形式再包 wrapper。MarketDataService 的 quoteDetail、dividendRate、非台股 ETF、dividendHistory、twHolidays、closure detect 六項固定能力明確分開，quoteDetail 2 秒、closure detect 20 秒、其餘原期限原樣；cache／fallback／純 ETF 分類與本地台股 ETF snapshot 不變。
- [x] **513.5 安全與相容。** 不改來源優先序、owner 選擇／tenant、計算／金融建議、資料庫正規化、commit→Redis 次序、canonical revision／comparator、cache key/bytes/TTL、SDK scope／native admission／retry／deadline、retention、time/calendar gate、broker flags 與排程。不碰 SRPP 專案、規則、registry 或 authority；金融／觀測 OpenAPI、27 exact method/path pairs、26 Tailscale handlers 不變。不發信、不呼真人券商、不 manual sync／rescan／observer POST。Python raw capture 只能在 process 記憶體，repr/log/response 不洩漏 raw／帳戶／secret。舊 Tasks401–404 非本次完成的 checkbox 不勾選。
- [x] **513.6 閘門與驗收。** 寫 code 前完成機械檢查及獨立 spec-auditor，記錄內容 hash；實作由 subagent，之後完整 diff（包含新檔）交 arch-auditor。隔離單元／fixture 測試驗證 port delegation、HTTP 固定契約、JSON 相容及錯誤分支；既有適用套件全測。受影響服務依 run-stack 重建/recreate，先核對 main .env/secret mounts（不輸出值），保持 flags；只安全 GET，必要 broker 正常路徑僅 fake SDK 證明。驗收後 feature commit→main --no-ff merge→push→remote readback，再從乾淨 main 重建驗證。

## 驗證

從本次 feature repo root 執行；Java 25，測試不使用正式 DB。pytest 使用既有隔離虛擬環境（執行前確認實際可用路徑）；未修改的 service 不須無故重建。

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
python3.13 -m venv /tmp/architecture-fubon-venv
/tmp/architecture-fubon-venv/bin/pip install -r fubon-broker-service/requirements.txt -r fubon-broker-service/requirements-test.txt
PYTHONPATH=fubon-broker-service/src /tmp/architecture-fubon-venv/bin/python -m pytest -q fubon-broker-service/tests
git diff --check
bash scripts/tests/schema-sql-drift-test.sh
```

Docker 驗收由 run-stack subagent 在 main 設定對齊後，僅 build 及 `up -d --no-deps --force-recreate` 實際受影響服務。GET 驗證具體變更 JSON、health 與 BFF 恢復；不以 health 代替功能或 jar/source 身分證據。影響 API JSON 必須在隔離 fixture 驗逐欄相等，不輸出個人資料。未執行、blocked、unknown 保留原狀態，不用零或空陣列掩蓋。

## 完成報告

程式、隔離測試、獨立 diff 架構驗收、feature／main runtime 與程式 Git landing 均已完成。下列 main 證據來自合併後的獨立重建，不以 feature 驗收代替。

### 全範圍審查與 finding ledger

全範圍獨立子代理審查 1 輪，終輪確認 3 個根因，依停止規則修正後停止全範圍迴圈；未進第 5 輪，無第 5 輪自審。實作後另依專案規範做完整 diff 的獨立 arch-auditor 驗收，不把它算成第二輪全樹審查。

| ID | 已確認根因／原位置 | 修正 | 狀態 |
|---|---|---|---|
| A1 | FundNavController 直接兩個 Repository／WebClient；NewsHeadlineController 直接查詢與日期／類別規則；MarketDataController 日期與刷新政策 | 4 Controller 委派 FundApplication／FundMaintenance／NewsHeadlineQuery／MarketHistoryApplication／MacroHistoryApplication services，固定基金操作經窄 port | rechecked：獨立 diff 驗收及相容性測試；未再做全範圍重審 |
| A2 | MarketDataController／MacroHistoryController 以八種 JPA Entity 作回應 | immutable HistoryDto；原 Entity 與新 DTO 在非空及 null fixture 的完整 JSON 相等，包含匯率兩個衍生 getter | rechecked：獨立 diff 驗收及相容性測試；未再做全範圍重審 |
| A3 | backend 業務層直接依 HTTP／Redis；external 十二項協調者依具體 IO／outer DTO；Python 十三項 consumer 依 SDK gateway／helper | Java 共 25 個窄 ports，既有 adapter 實作；中立 values／pure helpers／codec；Python 12 個 Protocol 與中立 capture／error／config values，保留 outer re-export | rechecked：獨立 diff 驗收、delegation／wiring／canonical 次序與 SDK body 比對；未再做全範圍重審 |

原 finding 的可追溯位置均以基準 `4d8c9abc` 為準（重構後原行號不再相同）：

- A1：`backend/src/main/java/com/steven/assets/controller/FundNavController.java:41`（兩個 Repository）及 `:45`（WebClient）、同目錄 `NewsHeadlineController.java:26`、`MarketDataController.java:268`；違反基準 `CLAUDE.md:294` 的 Controller 職責。
- A2：`backend/src/main/java/com/steven/assets/controller/MarketDataController.java:206`、`:264`、`:362`，同目錄 `MacroHistoryController.java:48`、`:59`、`:70`、`:81`、`:95`；違反基準 `CLAUDE.md:296` 的 Entity／DTO 分離。
- A3：`backend/src/main/java/com/steven/assets/service/MarketDataService.java:53`、`:61`；`external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/QuoteDetailReadService.java:23`、`:34`；`fubon-broker-service/src/fubon_broker_service/portfolio.py:13`；違反基準 `CLAUDE.md:295` 的依賴反轉，external／Python 另有 `spec/steering/structure.md` 的 Task402／Task401 窄介面明文。

coverage 包含 backend、BFF、frontend、external-materials、Fubon、Yuanta、scripts、config、schema、contracts 的靜態搜尋與定點閱讀；generated／vendored／歸檔文件排除。未確認 frontend 繞 BFF、BFF 直查 DB、external 反向 HTTP、券商寫入呼叫或資料庫正規化違反。fund_holding 的可空 code／歷史名稱無法證明重複事實，未列缺陷；SSE／owner email／snapshot／protocol enum／Anthropic／具名 JDBC 例外保留。靜態審查不構成逐行無錯證明；正式券商權限與真人 SDK 正常路徑未外部驗證，僅用隔離 fake／fixtures 驗證。

### 已完成驗證

- spec-review：critical 0／major 0／minor 1；minor 的 200／204 例子已改為實際 200／404。最初 pass hash `12b4b9306197`；完成報告及實際架構命名補入後另重新核對。
- backend：先 clean 全測抓到舊 WebMvc slice 少新 facade mock，僅修 fixture 後完整重跑成功；349 suites，3315 tests，0 failures／0 errors，2 個與基準相同的 skip（檔案系統 case assumption、未設 `srpp.report.size-probe` 的 opt-in size probe）。不以兩批針對測試重複加總。
- external-materials：Java25 clean 全測，148 suites／878 tests，0 failures／0 errors／0 skipped；包含 canonical DB→Redis 次序、revision 無法驗證 fail-closed、真 Spring CGLIB 多 port／可選 provider 組裝。
- Fubon Python：原 896 項保留，加 40 項架構／Protocol fake／repr 隱私驗證，936 passed；SDK 55 個方法中 53 個 body 完全不變、另外兩個 helper 搬移後同內容，consumer 只有 pure helper 名稱替換。
- spec-check BLOCK 0／CHECK 0，OpenAPI mirrors 及 union27 parity 通過，db/schema.sql 與 PostgreSQL 113 表逐位元相等；兩份 OpenAPI、統一 Swagger／classpath resource、schema、Compose、gateway 與本輪基準 bytes 相同。

### Feature runtime（2026-10-26 17:25–17:27 Asia/Taipei）

- 三個受影響服務 build／sequential recreate 完成並 healthy；BFF 容器保持運行，external／business 的 safe GET recovery 均成功。
- 三服務 effective env 與 main／先前 live 一致，canonical mounts 存在且相符；只將 main 設定同步至原本缺設定的 feature，未覆蓋 main。部署子代理依工具可選模型限制繼承主模型。
- 五個純市場 endpoint（USD 最新匯率、0050 歷史、commodity 歷史、台灣 GDP、IXIC 歷史）與重構前 baseline 的 JSON keys／shape／完整內容全部精確相同。
- backend jar 含新 FundApplicationService／HistoryDto／MarketDataReadPort，external 的 35 個 ports／neutral classes 齊備；Fubon 全部 33 個 src 檔案 SHA-256 與工作樹相同。jar embedded Swagger 與來源 bytes 相同。
- 驗收後再次 inspect，三個 image IDs 未被覆蓋：business `ab59de5883d0`、external `bbcbc1c7efc0`、Fubon `5676b76704bc`。首頁 200，9090 quotes／market-index／calendar GET 成功，8080／8082 無 host listener。
- 驗收曾誤用未定義 actuator 路徑：business 回 500、external 回 404，記為 `CLIENT_ROUTE_ERROR`，沒有將它們當成功證據或修無關路由。external／Fubon documented `/internal/health` 為 UP；business 以 Docker health 與五個實際市場 GET 驗收。
- 獨立 diff 架構驗收：critical 0／major 0／minor 0，完整 287 檔、其中 179 production 檔；arch hash `547976b55d1f`。此後沒有 production 變更。

### Git landing 與 main runtime（2026-10-26 17:33–17:34 Asia/Taipei）

- 程式 feature commit：`dfc45ec0cfa8de3b5d701d6b8b3a7608ab1fe5cb`（重構分層與外部資料介面）。
- 程式 main no-ff merge：`2e18dc83832bddbc7c143c3b0c259167e18a1401`；parents 為 `4d8c9abc1bd2c22035de8aed95d5ab9e425939f4` 與上述 feature。已 push 至 `git@github.com:twleader/asset-management.git`，遠端 feature／main 完整 SHA 與本地相同；兩工作樹乾淨、trees 相等。此為程式落地證據，後續本檔的完成紀錄提交不改 production。
- main runtime 狀態 `PASS`：從乾淨 `/Users/steven/Project/asset-management-main` 的上述 main SHA 完成三服務 build／recreate／healthy；三容器 Compose working directory 均為該 main 目錄。
- 1115 個 main source／resource hashes 與已測 production manifest 全相等；manifest 總 hash `d27d0e204efb8bc2b0eef49c5c3e1c896eb2c2ef420787495d808529e6574838`。main `.env` 未改，effective env、live flags 與 canonical mounts 皆相符。
- 五組市場 JSON keys／shape／完整內容 5/5 精確 MATCH；backend 新 classes／embedded Swagger、external 35 個 ports／neutral classes 與 Python 全部 33 個 src hashes 皆正確。BFF 未重啟，首頁／9090 三個既有市場 GET／正式 health routes 成功；未再呼叫未定義 actuator 路徑。
- main 最後 readback 的穩定 healthy image IDs：business `b000118679cd62a5a191c1f0900a992ba820b00f6634c71ce43f863753343edf`、external `8346d6cef1646c19b50dd600142f288700129e0b32774ae9f67523c9bee913cd`、Fubon `57fa267e61c8ebcae799e92dbfe3385a3572b5082785e6eeb1e0c2525d059591`。
- 最終證據回填只修改本任務檔，不改 production；保留「未再全範圍重審」及「正式券商權限／真人 SDK 正常路徑未外部驗證」的限制。沒有把健康檢查、測試 skip、既有服務或 feature 成功當作 main／真人券商成功。
