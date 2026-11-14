VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：

- asset-management：`344e77d6` → 目前工作樹，31 個已追蹤變更及 9 個新檔。
- SRPP：`ab1c27a` → `19b25d4`，包含 `1ecbb90`、`19b25d4` 兩個 commit，共 43 檔。
- 依提供的完整 patch 查核實作、測試、authority 與 runtime mirrors；D218 既有變更不列 finding，未重做 spec 文件審查。
- 判準限於本輪讀取的 `CLAUDE.md` 與 `spec/steering/structure.md`。

## Findings

無符合本輪判準且具備完整證據的新增架構違規。

## 已查無發現

| 檢查項目 | 查證證據與結果 |
|---|---|
| 模組相依方向 | 以 `git diff --unified=0` 檢查新增行，並對變更 Java 檔執行 `grep -ran`：未新增外部行情 URL、反向服務呼叫或跨服務資料表存取。external-materials 本輪無變更。 |
| 純 worker | AST 靜態追查 worker 的本機匯入鏈；新增 `resident_income_tax.py:2–4`、`scenario_tax.py:2–9` 無外部 HTTP client、正式 producer 或檔案寫入呼叫。官方來源 URL 僅作 metadata。 |
| 分層職責 | Controller、Repository、Entity 均無變更。兩個修改的 Service 未新增 `ResponseEntity`／`HttpServletResponse`；DTO 使用巢狀 immutable records，集合經 `List.copyOf` 保存。 |
| BFF 與前端 | 無新增頁面、route 或資料抓取流程。既有入口為 `api/index.js:429–443` → `/api/bff/portfolio-advice/srpp`。Vue 新增內容僅呈現年度列、級距、標籤及百分比格式，未重算稅額、排序或聚合；多 panel 並行抓取本輪不適用。 |
| 五檔候選與來源 | `SrppSettingsService.java:169–194` 經 manifest SHA 驗證 classpath catalog，回傳不可變白名單。實讀候選為五檔，正式預設仍為原三檔。`SrppShortTermService.java:83–91` 仍委派 `getStockEvaluation`；該方法於 `TradingRadarService.java:763–765` 保留 owner universe gate。 |
| 年度稅與傳輸型別 | `scenario_tax.py:23–48` 使用 Decimal 彙總、扣除及累進計算，結算為預扣減應納；`:69–72` 僅累計未到期淨補稅。`scenario_cashflow.py:145–150` 接入結算與期末負債。新增 DTO 金額全部為 BigDecimal、結算日為 LocalDate，rate 才使用 double。 |
| 舊版相容與雜湊 | `SrppSettingsDto.java:155` 對缺少的新 property 使用 NON_NULL；`SrppSettingsService.java:106–110` 保留 typed 轉換前後 hash 比對。V2／V3 測試包含 HTTP hash、日期、null 最高級距及 23 位金額精度斷言。 |
| 15% 同版來源 | 實讀 D141 五梯為 15／20／25／30／35%，份額仍 8／12／20／25／35%；D202、D212、monthly proxy 皆為 0.15。四個 production `MonthlySignals` 呼叫端均傳來源門檻；`scenario_reserves.py:56` 確實將 bearLadder 複製至執行 policy。 |
| 分流與資金守門 | `correction_dip.py:122–129` 的 NOT_PRESENT 分支直接返回修正窗口，其餘才判熊窗，維持單一路由；Java 原階梯仍讀 model，未新增另一套預算或放寬資金邏輯。新增測試涵蓋資格、資金底線、五批、冷卻與跳空順序。 |
| 正規化、命名與資料來源 | 無新增 DB 欄位、migration、業務分類或 endpoint；FK 冗餘、changeset 碰撞／註冊不適用。未新增即時價／收盤價讀取或預設價格回寫。 |
| mirrors | 唯讀 SHA／bytes 比對確認 runtime、classpath model／manifest／catalog 與 canonical source 一致；runtime manifest 的 21 個列管檔案無 hash 不符、缺檔或多檔，BFF V3 fixture 與 backend 相同。 |

兩 repo 的 `git diff --check` 均無輸出。

## 無法查證

- 本輪未執行會產生檔案的測試、build、容器重建或 API POST。既有 `final-java-suite.json` 記錄 backend 307、BFF 9 測試 PASS；Python 摘要記錄 117 測試 PASS，屬既有收據，本輪未重跑。
- 未驗證正式部署、registry 寫入、Google OIDC、瀏覽器視覺或自然排程採用。本判定僅代表本輪 diff 未查得架構 finding。

## 自我挑戰

- 已以 Git diff 確認範圍，未將既有 Controller／SSE 技術債算入本輪。
- 已排除外層 DTO class 誤報：新增 graph 為巢狀 records；未見真正的 `@Data` 標註。
- 已排除 double 誤報：新增 double 為稅率；傳輸金額皆為 BigDecimal。月頻內部浮點運算不據此列違規。
- `IncomeTaxTreatment` 為固定 wire 處理碼，並非銀行、券商等可管理業務分類，因此不列硬編 enum finding。
- 已重新查證兩份規範原文；沒有保留僅憑偏好、未完成發布或超出本輪範圍的 finding。
