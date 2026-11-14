VERDICT: CHANGES_REQUESTED

## 判定

critical: 0   major: 1   minor: 0

## Findings

### [Major] 過往起領的勞保缺少 CPI 初始狀態契約，直接沿用既有初始化會永久停止指數化

位置：`spec/design.md:13861`；`spec/tasks/t586_srpp_life_cashflow_fields.md:15`

證據：

- 設計明定「月額是起領時名目額」，允許起領日早於目前日期，並要求「仍沿既有……CPI 指數化規約」。
- `../SRPP/scripts/budget_accounting.py:127` 初始化 `self.anchor = None`。
- 同檔第 170–173 行，只有觀察到**完整起領年度**，且 `cursor.year == self.claim_year`，才建立 anchor。
- 同檔第 180 行，調整給付的必要條件是 `self.anchor is not None`。
- `../SRPP/scripts/scenario_cashflow.py:140` 的網格只保留 `asof<=d<=end`，第 158 行也只觀察此網格期間。

因此，例如起領年度為 2020、本輪估值日在 2026，依目前指定的初始化及網格，永遠不會觀察到起領年度，後續即使累積足夠 CPI 成長也不會調整月額。同年度起領、但估值日已過年初，也有完整年度無法建立的問題。

被違反的出處：本輪 `spec/design.md:13861` 對已起領勞保持續沿 CPI 規約給付的要求。這是新增支援範圍所需的初始化契約缺口，不能只靠沿用現有 helper 完成。

修法：補明情境專用的過往起領初始化規約，包括起領年度 CPI 基準、截至估值日已調整月額及最後調整基準的來源、假設與缺值處理；不得以未來情境通膨重寫歷史。明定如何將該狀態提供給年金計算，並在 586.7 加入「跨年度已起領」與「起領年度僅剩部分年度」的確定性測試，驗證後續仍可指數化且不重播過往給付。

## 已查無發現

- **機械證據**：採用呼叫端提供的 `BLOCK: 0／CHECK: 0`，未重跑。
- **可查證性**：以 `rg`、檔案內容及 diff 核對 canonical catalog、`scenario_cashflow.py`、`labor_insurance_claim_terms`、`labor_pension_projection`、`authorityValues`、`SrppSettingsCenter.vue` 與 export 工具，均存在。既有五筆事件、300 萬餘額基準及 8/31 情境排程均與來源相符。
- **內部一致性**：除上述 finding，八個新增欄位、35 欄總數、成對 null、事件格式、金額字串、舊資料唯讀及來源發布要求，三份文件無其他發現。
- **架構鐵則逐條查核**：
  1. Controller 直接讀 Repository／執行領域或外部 HTTP 邏輯：否。
  2. DTO 使用可變 setter／`@Data`：否；兩側既有 DTO 為 record。
  3. 新增頁面但缺專屬 BFF：不適用，擴充既有頁面元件。
  4. 跨頁 BFF、直打 resource 或裸 fetch／EventSource：否。
  5. 自創共用例外：否。
  6. 同義值改走不同 business API：否。
  7. 前端直連 business／business 外部行情／external 反向呼叫：否。
  8. 新增冗餘或衍生欄位：否。
  9. 新業務分類硬編 enum：否；AUTO／MANUAL 為協定模式。
  10. 新 endpoint 違反命名：不適用，未新增 endpoint。
- **資料來源**：以 `db/schema.sql:2681`、`:2723`、`:2724` 核對既有設定與請求／結果 JSON 儲存欄位；無本輪必須新增 DB 欄位的發現。未用 changelog 推斷現況。

## 自我挑戰

- 保留唯一 Major：不是因為新功能尚未實作，而是規格允許過往起領，卻指定沿用無法從該時間網格建立 CPI 基準的初始化方式；來源程式的必要條件可直接驗證。
- 未將 Java 現況拒絕 null、DTO 尚無新增欄位列為缺陷，因本輪已明確要求修改。
- 未將 8/31 與其他文件的 6/30 差異列為缺陷，本輪已明確保留情境 baseline 並要求揭露。
- 未將 V2 識別字尚不存在、checkbox 未勾選或尚無部署證據列為缺陷；本輪為實作前唯讀審查。