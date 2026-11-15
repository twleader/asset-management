VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：第 1/3 輪；以 `origin/main`（`0841c46b0e169befa9c3cca007706a068913f6dd`，亦為目前 HEAD）比較工作目錄的 Task 595 未提交變更。實作與測試範圍為：

- `backend/.../SrppSettingsLocalAi.java`
- `backend/.../SrppSettingsLocalAiTest.java`
- `backend/.../SrppSettingsServiceTest.java`
- `frontend/src/utils/srppSettingsCenterAi.js` 及其測試
- `scripts/srpp-local-ai-bridge.py` 及其測試

另核對操作文件、新增審查材料與任務檔；`spec/requirements.md`、`spec/design.md` 僅供辨識變更邊界，未重做規格審查。架構判準限定為本輪實際讀取的 `CLAUDE.md` 與 `spec/steering/structure.md`。

## Findings

無。本次 diff 未發現可由上述兩份規範明文支持的新增或擴大架構違規。

## 已查無發現

查證使用 `git diff origin/main -- <檔案>`、`git diff --name-status origin/main`、`rg -an` 與 `grep -ran`，搜尋限於本次變更及必要的直接相依檔案。

| 檢查項目 | 結果與證據 |
|---|---|
| 模組界線：business 直連外部行情／NAV／配息 API | 無新增違規。對修改的 Java adapter 執行指定外部行情 URL 模式搜尋，零命中。`SrppSettingsLocalAi.java:36` 的固定 host bridge 位址為既有程式碼；新增程式未加入行情來源。對照 `structure.md` §8。 |
| external-materials 反向呼叫 business、跨服務直接讀表、券商交易 | 無新增路徑。本次未修改 external-materials、券商 adapter 或資料存取程式；bridge 新增內容為費用查詢、鎖與本機退避狀態。對照 `structure.md` §8。 |
| Controller 直接注入 Repository、外部 HTTP、upsert 或領域判斷 | 無新增 Controller 變更。未將既有 Controller 技術債計入。對照 `CLAUDE.md`〈Clean Architecture 原則〉及 `structure.md` §2.2。 |
| Service 直接組 HTTP response | 對修改的 Java adapter 搜尋 `ResponseEntity`、`HttpServletResponse`，零命中。新增程式回傳既有能力 record 或拋出既有問題型別，沒有新增 response 組裝。 |
| 依賴反轉與外部系統隔離 | 既有 `SrppSettingsService.java:36` 仍依賴 `SrppSettingsAiPort`；修改的 adapter 仍實作該介面。新增快照未讓核心流程依賴 Python bridge 或具體供應商。對照 `CLAUDE.md:312` 的依賴反轉規則。 |
| Repository 業務邏輯、Entity 業務邏輯與 enum 映射 | 本次未新增或修改 Repository、Entity 或持久化 enum，不適用。 |
| DTO 不可變性 | 無 DTO 變更；新增快照使用的既有 `AiModel`、`AiTool`、`AiCapabilities` 均為 record。實際搜尋命中 `SrppSettingsDto.java:61、65、70`；其建構子分別於 `:62、67、71` 經 `:19` 的 `List.copyOf` 複製巢狀清單。符合 `structure.md` §2.2。 |
| 新頁面與專屬 BFF | 未新增頁面、router、BFF controller 或 route，不適用。 |
| 跨頁 BFF、直打 business、裸 `fetch()`／`EventSource` | 對修改的前端 utility 搜尋 `fetch(`、`new EventSource`、`/api/`，零命中。production diff 僅新增一條安全文案，未新增網路入口或主張跨頁例外。對照 `structure.md` §3.2、§5.2。 |
| 同義值使用同一 business API | 未新增第二套能力 API、金融數值來源或計算入口。前端新增內容為錯誤碼文字映射。 |
| 前端聚合、排序、過濾、衍生值與多 panel 並行 | 本次未新增上述運算或 panel 請求；沒有新增應使用 `Promise.allSettled` 的載入流程。 |
| 正規化與硬編業務分類 | 無 DB 變更、FK／字串冗餘或新增持久化衍生業務欄位。`AI_RATE_LIMITED` 為錯誤協定碼，未新增銀行、券商、市場等可管理業務分類。 |
| API 命名與資料型別 | 未新增 endpoint、Java 金額／日期欄位或資料表。未發現新增違反 kebab-case、BigDecimal、LocalDate／Instant 或資料表命名的項目。 |
| Liquibase 與 schema | 無 changeset、master 或 `db/schema.sql` 變更；本輪不涉及版號碰撞、冪等或 schema 重產斷言。 |
| 即時價、收盤價與缺價處理 | 本次未修改 Redis 價格、`stock_price_history` 或 tick 寫入流程，未新增替代價格或預設值回填。 |

另核對本次快照調整的直接呼叫鏈：

```text
SrppSettingsLocalAi.java:49   AiCapabilities current=readCapabilities();catalogSnapshot=current;return current;
SrppSettingsLocalAi.java:50   ...catalogSnapshot=null;...
SrppSettingsLocalAi.java:93   selected(catalogSnapshot(),selection);
SrppSettingsLocalAi.java:111  try {tool=selected(catalogSnapshot(),selection);}
```

host parent 在 `scripts/srpp-local-ai-bridge.py:1162–1168` 核對既有目錄與 registry；worker 在 `:1215–1216` 仍重新 probe 所選工具並驗證選擇，Claude 執行分支於 `:1087` 明確傳入 `fresh=True`。成功快取的讀取與保存都受 `not fresh` 限制（`:892、909`），未發現本次快照取代執行前費用檢查。

## 無法查證

- 本輪遵守唯讀限制，未執行測試、`spec-check.sh`、API、模型推論、瀏覽器操作或容器部署。
- Python 109、backend 70、frontend 193 與 build PASS，以及 schema parity，均屬作者提供的材料，未作本輪獨立執行結果。
- 真實 Claude usage 是否已恢復、部署後兩工具是否同時可選、畫面文案與跨層執行結果，本輪未驗證。此 VERDICT 僅表示本次靜態架構審查沒有 Critical／Major finding。

## 自我挑戰

1. **新增範圍確認：**以 `git diff origin/main` 核對變更。未將既有固定 host URL、既有 HTTP adapter 放置位置、Controller 技術債或 SSE 基準線列為本次 finding。
2. **誤報陷阱排除：**未因 DTO 外層是 class 判定違規；已確認內層 record 與各層清單複製。未將註解中的 `@Data` 或 Python 退避秒數、時間戳與 utilization 的數值運算誤認為金額型別違規。
3. **規範原文回查：**以 `grep -ran` 回查 `CLAUDE.md` 的分層、依賴反轉、業務分類與 BFF 規則，以及 `structure.md` §2.2、§3.2、§7、§8。未用 Task 595 規格自行擴張架構判準。
4. **未重報規格 Minor：**既有規格審查提出的「BFF12秒期限」措辭問題屬規格審查範圍；本次未修改 timeout，不另列實作架構 finding。
5. **未把快取視為授權繞過：**已追到 worker 的 fresh probe 與可用性驗證後才進入 `infer`；因此未將 parent 的 `check_available=False` 單獨判為繞過安全閘門。