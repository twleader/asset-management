VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 0

審查範圍：`HEAD 854914b6` 至目前工作樹，無 staged diff；已核對全部 8 個 tracked 變更檔與新增的 `spec/tasks/t584_srpp_core_tactical_separation.md`：

- 三支 `SrppReport{Module,ShortUs,State}Calculator.java`
- 兩支 `SrppReport{ShortUs,StateTopup}CalculatorTest.java`
- `scripts/generate-srpp-state-topup-golden.py`
- `spec/design.md`、`spec/requirements.md`、Task 584 任務檔

規範來源為 `CLAUDE.md` 與 `spec/steering/structure.md`；規格文件僅作變更脈絡核對，未重做 spec 審查。

## Findings

無。本次 diff 未發現違反兩份架構規範的新增或擴大項目。

## 已查無發現

- **模組界線（structure.md §8）**：對三支修改的計算器執行 `grep -ran -iE` 查找外部 URL、HTTP client、Repository、JDBC、Redis client、券商委託方法與排程標註；唯一命中為 `SrppReportModuleCalculator.java:15` 的「No … repository, HTTP …」註解。新增行再次篩查無命中，未新增外部行情直連、跨層讀表或券商寫入。
- **分層職責（§2.2）**：業務計算維持在 Service 層，沒有新增 Controller／Repository／Entity／DTO，也沒有 `ResponseEntity` 或 `HttpServletResponse`。未新增 Controller 業務判斷、Repository 業務方法、Entity 邏輯或可變 DTO。
- **BFF（§3.2）**：`git diff --name-only HEAD` 與 `git status --short` 確認沒有前端頁面、BFF、API client 或 route 變更；新增頁面配套、跨頁呼叫、裸 `fetch`／SSE、前端衍生計算與多 panel 並行要求，本輪均不涉及。
- **共用計算**：`grep -ran` 確認 `combinedSymbolCap` 同時用於短線 screen（`SrppReportShortUsCalculator.java:145`）與最終五檔重定價（`:169`）。`bullSaleCapacity` 由 state 計算（`SrppReportStateCalculator.java:61、65`）及 module 兩個入口（`SrppReportModuleCalculator.java:93、97`）共用，沒有另建第二份 Java 公式。
- **資料模型與命名（§2.3、§7）**：新增金額／容量運算使用 `BigDecimal`。沒有新增持久化欄位、冗餘 FK／字串、衍生值儲存、業務分類、endpoint 或 Liquibase changeset，因此沒有新增 enum 儲存、設定頁、URL 命名或 migration 註冊問題。
- **資料來源（CLAUDE.md「Clean Architecture」「BFF 與資料來源規範」）**：新增邏輯僅消費傳入的 policy、model 與凍結事實；沒有更換即時價／收盤價來源、修改 Redis TTL，或新增缺價補值回寫。D-156 module 維持 aggregate facts／receipts，未新增 executable SELL leg。
- **Golden 產生器**：修改以 `copy.deepcopy(dyn)` 建立日頻 fixture 的設定副本；未修改正式月頻 model。原有 authority bytes 驗證與輸出機制未被新增邏輯繞過。

## 無法查證

本輪未執行會產生檔案的 Maven、spec-check 或 golden 產生程序，因此未獨立重跑作者所述的 48 項測試。

Embedded authority 與既有 golden 尚未含新增 D-216 設定；本報告不認定最終 bundle、跨語言 parity、registry、容器部署或正式 API 採用已完成。這些是材料明示的後續發布工作，本輪沒有將其列為架構違規。

## 自我挑戰

1. 以 `git diff --unified=0` 限定新增／修改行，未將既有 Controller、SSE 或其他技術債納入 findings。
2. 本次沒有新增 DTO；未把外層容器、註解中的 `@Data` 或既有 Python fixture 浮點運算誤報為傳輸／持久化金額型別違規。
3. 已用 `grep -ran` 回查 Service 職責、模組禁令、金額型別與具名 report-only 五檔例外的原文。
4. 未把政策模式字串當成可管理的業務分類；未把待同步 authority／golden 當成已發布，也未把尚待 SRPP 完成的逐檔 final guard 當成本次新增架構缺陷。無保留或撤下的 finding。