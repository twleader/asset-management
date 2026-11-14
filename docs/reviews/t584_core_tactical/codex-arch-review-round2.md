VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：`HEAD 854914b6c8ded8bf6453ecded0bc262034aebfd8` 至目前工作樹，無 staged 變更。已核對全部 15 個 tracked 變更檔、`docs/reviews/t584_core_tactical/` 五個新增檔及 Task 584 任務檔；另辨識未追蹤的 Python 快取檔，僅檢查其檔頭與檔案資訊。

完整讀取 `CLAUDE.md` 與 `spec/steering/structure.md`，僅以兩者作架構判準；未修改任何檔案。

## Findings

無。本輪未發現新增或擴大的架構違規。

## 已查無發現

- **模組界線（structure.md §8）**：對三支修改的 calculator 執行 `grep -ran -iE`，查找外部 URL、HTTP client、Repository、JDBC、Redis、排程與券商委託方法。唯一命中是 `SrppReportModuleCalculator.java:15` 的禁止 I/O 註解。沒有新增外部行情直連、反向服務相依、跨層讀表或券商寫入。

- **分層職責（§2.2）**：`git diff --name-only HEAD` 確認沒有 Controller、Repository、Entity、DTO 變更。新增計算維持在 Service，未組裝 `ResponseEntity`／`HttpServletResponse`，沒有新增 Entity 業務邏輯或可變 DTO。

- **BFF（§3.2）**：沒有 frontend、BFF、route 或 API client 變更。因此本輪沒有新增頁面缺專屬 BFF、跨頁呼叫、裸 `fetch`／SSE、前端衍生計算、序列載入多 panel 或自創共用例外。

- **共用計算**：實際 `grep -ran -oE` 命中 `SrppReportShortUsCalculator.java:145、169`，確認 screen 與最終五檔重定價都使用 `combinedSymbolCap`。helper 位於 `:41`，從 policy／model 取得目標，未新增第二份 Java 公式。

- **第 1 輪後的歷史政策修正**：`SrppReportModuleCalculator.java:93、97` 均查得 `dynamic.has("daily_core_retention_d216")` 條件；沒有設定時保留原 rotation capacity。`SrppReportStateTopupCalculatorTest.java:129–137` 明確移除設定、使用 `.4` 過渡下限及 `4900000` 股票價值，斷言原容量仍為 `80000`。這是本輪重新查證的結果。

- **日頻狀態與月頻分離**：`SrppReportStateCalculator.java:33–54` 共用 retention helper；`:65` 在 clamp 後計算 `growingFill`。generator 的 `:70–76` 使用 `copy.deepcopy(dyn)` 調整日頻 fixture 副本，沒有改寫正式月頻參數。

- **資料模型、分類與命名（§2.3、§7）**：新增金額計算使用 `BigDecimal`。沒有新增持久化欄位、FK／字串冗餘、衍生值儲存、業務分類、endpoint 或 Liquibase changeset；表名、enum 儲存、日期型別及 migration 註冊要求本輪不涉及。新增模式字串屬政策協定。

- **資料來源（CLAUDE.md「Clean Architecture」「BFF 與資料來源規範」）**：新增邏輯只消費傳入 policy、model 與凍結事實，沒有修改即時價／收盤價來源、TTL 或缺價回寫。五檔重定價沿既有 report-only 例外；D-156 仍只輸出 aggregate facts／receipts，未新增可執行 SELL leg。

- **最終 authority 與 metadata**：以無寫入的 `python3 -B` 查證，重算 bundle 為 `a9d05b6ab58685ca5cee088b05996fa9db868e3e4e4a6d3bf6a272b52ecb6175`；manifest 的 192 個來源檔雜湊全數相符，embedded model／manifest 與 SRPP 原檔逐位元一致。四組 golden 的 calculator hashes 均相符；有 bundle 欄位的兩組均綁定新 hash。registration SQL 相較 HEAD 僅替換 bundle hash，保留 `INSERT … ON CONFLICT … DO NOTHING`。

- **變更完整性**：`git diff --check HEAD` 無輸出。新增審查紀錄與測試 JSON 均標示其階段，沒有把前置驗證冒稱正式發布完成。

## 無法查證

依唯讀限制，未重跑 Maven、測試套件、spec-check 或 golden 產生程序。因此作者所述 Java 65 項及其餘測試通過，並非本輪獨立執行的結果；上述雜湊與逐位元比對是本輪實際查證。

未執行 registry SQL、容器重建或正式 API 採用驗收。本判定不代表發布、運行中 DB／JAR 一致性或合法時窗採用已完成。

## 自我挑戰

1. 以 `git diff --unified=0 HEAD` 限定變更行，未將既有 Controller 或 SSE 技術債列入。
2. 未將 DTO 容器、註解中的 `@Data`，或既有 Python fixture 浮點運算誤報為金額傳輸型別違規。
3. 已用 `grep -ran` 回查分層、模組界線、金額型別及 report-only 五檔例外原文。
4. 排除「兩個 regime 入口仍無條件套用 D216 cap」的疑慮：本輪實際程式碼已有存在條件及歷史過渡容量回歸斷言。
5. 未將政策協定當成可管理業務分類，亦未將 aggregate PASS 解讀為逐檔賣出授權。沒有保留的 finding。

