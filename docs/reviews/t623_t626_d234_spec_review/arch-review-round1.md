VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 1
審查範圍：repo /private/tmp/asset-d234-calc-migration 的工作樹相對 HEAD（git status／git diff --stat／逐檔 git diff 與未追蹤新檔 cat）；後端 backend/src/main（Repository、decision、report 套件共約 18 個變更檔＋7 個新檔）、bff/src/main/publicsrpp（3 檔）、OpenAPI／Swagger／manifest 產生物（抽查）、scripts 與測試（抽查）。判準：CLAUDE.md 與 spec/steering/structure.md。本輪為 Claude Sonnet 備援審查（Codex 額度用盡），非 Codex。

## Findings
### [Minor] order-plan v2 改變了 structure.md 仍寫著「禁止新計算、外呼」的路由語意，但 steering 未同步修訂
位置：spec/steering/structure.md:676（Requirement 191／Task 497 段落）對照 backend/src/main/java/com/steven/assets/service/srpp/report/SrppProjectionService.java:40-41（order 分流與 orderV2）、SrppProjectionService.java:52（MarketReads.taiwan 讀 prices.getLiveBatch／getEtfNav／calendar.getQuoteDetail）、SrppOrderPlanV2.java:389、:422（c.reads().taiwan／c.reads().fubon）
證據：structure.md:676 原文「兩GET精確綁…只讀既有immutable schemaVersion1 FINAL package；禁止新計算、外呼、refresh、…」且「v1無capacity evidence所以零capacity reflow/遞補」「P2/revision、client risk、SRPP adoption均不在本輪」。本 diff 在同一 exact route 上新增 v2：請求當下做補價重算與共用容量縮量（新計算）、讀 Redis 即時價／ETF NAV、經 MarketDataService 讀 external-materials 持久化五檔、讀已持久化 Fubon 帳戶快照，並接受 consumer 宣告的 riskMode。`git status --short spec/steering CLAUDE.md` 無輸出，即 steering 與 CLAUDE.md 本 diff 均未修改；新規格只寫在 spec/requirements.md:7321（Requirement 223）與 spec/design.md:14338。
違反：structure.md §8 後的「Requirement 191／Task 497」段（第 676 行）仍是現行規範敘述；它與本次實作互相矛盾。須說明降級原因：§6.1 表只要求「長期技術選型／目錄結構」才更新 steering，且 v2 的 IO 皆落在既有允許路徑（Redis 讀、business 只 proxy external-materials 的 pure-read 五檔、DB 讀）、未碰 §8 禁止清單，所以只算文件現況漂移，不算架構違規。
修法：在 structure.md 的 Requirement 191 段後補一段「Requirement 223／Task 626 修訂（優先於上段）」，明文限縮例外：同一 exact `GET /api/public/srpp/order-plan` 在 `projectionVersion=SRPP_ORDER_PLAN_PROJECTION_V2` 時可於請求當下做機械重算，IO 僅限 `PriceQueryService.getLiveBatch／getEtfNav`、`MarketDataService.getQuoteDetail`（external-materials 持久化快照 pure read）、`SrppReportFubonCapturePort`（DB 唯讀），仍禁止 vendor I/O、任何 DB／Redis 寫入、producer、通知、預約與券商／交易；v1 缺省位元組不變。CLAUDE.md:516 的 pair 計數敘述不必改。

## 已查無發現
- 券商唯讀鐵則（CLAUDE.md「券商 API 只能查詢，不得交易」、structure.md §8）：對新增行與全部新檔 grep `placeOrder|place_order|modify_order|cancel_order|submitOrder|WebClient|RestTemplate|HttpClient|@PostMapping|@GetMapping|@RestController|\.save\(|INSERT|UPDATE|DELETE` 在新檔零命中；變更行只命中 `SrppProjectionService.order(` 方法名。v2 回應固定 `executionScope=REPORT_RECOMMENDATION_ONLY`、`tradeAuthorization=false`、`placesOrders=false`（SrppOrderPlanV2.java:232-234），OpenAPI 與 BFF 驗證也以 const false／`!executable` 閉合；VT／009826 建倉腿僅輸出 ACTIONABLE_FOR_REPORT 上限（腿 `executable:false`、`settlement:null`），無下單路徑。Fubon 讀取僅經 PersistedSrppReportFubonCapture（類別註解自述無 vendor／Redis／sync／broker client 依賴，實作只讀 FubonSrppSourceStore）。
- 模組界線（structure.md §8）：`git diff -U0` 新增行與新檔 grep `https?://` 無任何外部行情／NAV／配息 URL；external-materials、frontend、fubon-broker-service、api-gateway 本 diff 零變更；gateway／BFF／frontend 路由集合未動（OpenAPI 只加 query 參數與 oneOf 回應，無新 path）。
- Controller→Repository：InternalSrppProjectionController 本 diff 零變更，仍只注入 SrppProjectionService；所有新增 Repository 使用都在 service 層類別（SrppSatelliteBuildCapture、SrppReportExtraCaptureAdapter）。
- Repository 層：AssetTransactionRepository.findSatelliteBuildRows 是純查詢 native query，明確帶 owner_user_id 條件；與同檔既有 existsByOwnerUserIdAndBrokerFilledNo 同模式（TenantFilterAspect 在背景執行緒不啟用、native query 本就繞過 @Filter）。欄位 owner_user_id、asset_code、trade_date 以 db/schema.sql:621-642 核對皆存在。
- 純計算器無 IO：對 SrppSatelliteBuildCalculator、SrppReportD215FxPreference、SrppPooledFunding、SrppReportModuleCalculator、SrppReportMediumDipCalculator、SrppReportShortUsCalculator、SrppReportTopupSizing、SrppReportEnvelopeValidator、DecisionQuoteMapping grep `Repository|Redis|WebClient|RestTemplate|Instant.now|LocalDate.now|Clock.|@Autowired|@Service|@Component|@Transactional|java.net|java.io|Files.` 皆零程式命中（僅類別註解自述「No Spring, clock…」）。SrppOrderPlanV2 的時間只用注入的 Clock，IO 全經 MarketReads 介面（依賴反轉，由 SrppProjectionService 在最外層實作）。
- Transaction／readOnly：SrppProjectionService.order 為 `@Transactional(readOnly=true)`，orderV2 在其內以私有方法呼叫；v2 路徑 grep 無 save／insert／delete；PriceQueryService.getLiveBatch 傳入空 history map，只做 Redis MGET，不回落 DB 也不寫（PriceQueryService.java:121-153）；getEtfNav 只做 Redis GET；MarketDataService.getQuoteDetail 只做 external.quoteDetail 讀取並 fail-soft。SrppSatelliteBuildCapture.capture 為 `@Transactional(readOnly=true)` 且只讀 DB、無寫入。Frozen 記錄有 defensive deepCopy（DecisionInputCapturePort.java:6），refreshFrame 的就地修改不會污染 package 原始 input。
- BFF 只驗證不計算：bff 變更僅在 publicsrpp 的 PublicSrppProjectionService（validateOrderV2／validateLegV2）、SrppProjectionQuery（八個 v2 鍵的封閉文法並原樣轉送）、SrppReportResponseValidator（satellite／d215Receipt 閉合驗證）。皆為封閉欄位、false 旗標、hash 與不變量重驗，回傳位元組仍是 business 原回應，BFF 不產生任何新值；沒有新 Controller、新 route 或前端頁面。
- v1 相容：SrppProjectionRequest.order 的封閉鍵集合未放寬（V2_KEYS 只在 orderV2 的 union 內），BFF 的 keys 集合只在 `containsKey("projectionVersion")` 時才加入 V2_KEYS，無 projectionVersion 卻帶 v2 專用鍵會被擋成 INVALID_REQUEST。
- 資料模型與命名：本 diff 無 Entity、無 Liquibase changeset、無 db/changelog 與 db/schema.sql 變更；新增程式無 double／float 金額欄位（唯一命中是既有 DailyDecisionCalculator.inflation 的 StrictMath，僅把 p.inflation()／p.baseDate() 抽成參數，非持久化金額）；日期用 LocalDate／Instant；新 DTO 類型皆為 record（OrderV2、Config、Read、Capture 等）。
- 資料來源（CLAUDE.md）：即時價只由 `price:{market}:{code}` Redis 讀取；收盤價列用 StockPriceHistory 持久化列（priceOnly 以 history.findBy… 讀 canonical rows）；匯率中價讀 exchange_rate_history（SrppSatelliteBuildCapture.midsSource）；沒有 request-time vendor 呼叫。
- 產生物一致性（抽查）：backend/src/main/resources/srpp/9090-api-swagger.md 與 docs/openapi/9090-api-swagger.md `cmp` 位元相同；bff/src/main/resources/srpp/formula_manifest_v3.json 與 spec/fixtures/srpp_formula_manifest_v3.json `cmp` 相同；SrppFormulaCatalog.MANIFEST_JSON_V3 去跳脫後 JSON 等於 fixture；satelliteBuild.sourceSha256 內五個 asset-management 來源檔雜湊與工作樹檔案 sha256 逐一相符。

## 無法查證
- 未執行 mvn 測試、未啟動 Docker／PostgreSQL 容器，也未讀 SRPP 專案端（`--srpp-root`）；golden 逐欄比對、SRPP 參考實作等價性、registry 發布鏈屬功能／發布驗證，不在本架構審查範圍，也無法在唯讀環境重跑。
- 以 db/schema.sql 核對 asset_transaction 欄位；未用運行中 DB 複驗 B10 漂移（本 diff 未動 db/changelog，B10 非此輪觸發條件）。
- 生成的 Swagger Markdown（各約 887 行）與 golden／wire fixture JSON 僅做 cmp 與抽查，未逐行審。

## 自我挑戰
- 唯一保留的 Minor：先考慮過升為 Major，因 structure.md:676 的「禁止新計算、外呼」是明文。降級理由：§6.1 僅要求長期技術選型／目錄結構才動 steering，v2 的 IO 全是既有允許路徑，未踩 §8 禁止清單，且 requirements.md Requirement 223 與 design.md 已完整記載；同類漂移（Task599 在 requirements 補充 Requirement 191，structure.md 未跟進）在 main 上已存在，所以只報 Minor，並限定在「本 diff 讓矛盾擴大」。若 Steven 認為 steering 不需對每個任務同步，可直接駁回此條。
- 撤下：SrppSatelliteBuildCapture.capture 在 scheduler 的讀寫 TransactionTemplate 內被呼叫，`@Transactional(readOnly=true)` 實際只是加入既有交易（無效果），且在內層 catch RuntimeException 後繼續，PG 交易被中止或標記 rollback-only 時其「fail-soft 為 UNAVAILABLE」不會如預期（會讓整個 owner 本輪被 scheduler 外層 catch 跳過）。兩份判準都沒有對應明文，且 SrppReportExtraCaptureAdapter 既有同樣寫法，屬 robustness 而非架構符規，故不列 finding。
- 撤下：v2 在 `@Transactional(readOnly=true)` 內最多讀 25 檔、每檔含一次 external-materials HTTP（getQuoteDetail），BFF 對 business 的逾時為 5 秒（PublicSrppProjectionService.java:36）。這是延遲／連線佔用風險，判準無明文，不列；僅提示作者實測 4 檔債券 universe 時的 P99 是否貼近 5 秒，超時會回 CONTEXT_NOT_READY（可重試）。
- 撤下：新程式硬編「富邦證券／國泰證券／CORE_D143／CORE_D234」。CLAUDE.md「禁止 Enum 寫死」針對業務分類管理，但這些是 SRPP 規則包封閉 wire 協議碼，HEAD 的 SrppReportMediumDipCalculator.java:144、DailyDecisionCalculator.java:77 已有同模式，屬既有債而非本 diff 新增種類。
- 撤下：BFF 重算 `bond`（SGOV／B 結尾）、風險模式分類與 amount＝units×limit。這是封閉驗證器對 business 回應做不變量重驗，與 v1 既有 limitPrice×quantity==amount 檢查同型；BFF 為獨立 Maven artifact，無法共用 backend 程式碼，且未產生任何回傳值，不構成「BFF 計算」。
- 未踩三個誤報陷阱：DTO 皆為 record 且未用 `grep 'public class .*Dto'`；未命中行首 `@Data`；金額 double 判定限縮在持久化／傳輸欄位。
- 引用出處已核對：CLAUDE.md「券商 API 只能查詢，不得交易」位於第 276 行；structure.md §8 於 626-656 行；Requirement 191 段於 structure.md:672-676；§6.1 表於 595-606 行，並以 grep 確認 `禁止新計算、外呼` 只出現在 structure.md:676。
