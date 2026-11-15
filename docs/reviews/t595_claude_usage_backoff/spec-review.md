VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 1

## Findings

### [Minor] 「BFF12秒期限」將 Java→host client 的期限誤標為 BFF 期限

位置：`spec/requirements.md:7053`、`spec/design.md:14079`、`spec/tasks/t595_claude_usage_backoff.md:18`。

證據：

- 三處第 4 點均寫「7.5秒metadata總deadline／BFF12秒期限」。
- `backend/src/main/java/com/steven/assets/service/srpp/settings/SrppSettingsLocalAi.java:50` 實際設定：
  ```java
  raw=call("/capabilities",null,Duration.ofSeconds(12));
  ```
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppSettingsBffController.java:34` 實際設定：
  ```java
  })).timeout(Duration.ofSeconds(20));
  ```

被違反的出處：本輪判準的「可查證性」要求既有事實與程式碼相符。12 秒確實存在，但屬於 business 端呼叫 host 的 client，BFF controller 的期限是 20 秒。

修法：同步將三處改為「遵守 host 7.5 秒 metadata 總 deadline、Java→host capabilities client 12 秒及 BFF 20 秒期限」。這是期限歸屬的措辭修正，無須修改現有 timeout。

## 已查無發現

- **前輪 Major 1：已解決。** 已直接讀取並比對三份 Task 595 的七點契約，內容一致。`spec/requirements.md:7050`、`spec/design.md:14076`、任務檔第 15 行均明定 `capabilities()` 保存不可變快照、讀取失敗清除快照，`validateSelection()`／`interpret()` 不再 GET；三份第 7 點均加入跨 TTL、選 Codex 時 Claude usage 零次、選 Claude 時僅 worker 一次 fresh usage 的驗證要求。
- **可查證性：除上述 Minor 外無發現。** `SrppSettingsLocalAi`、`capabilities()`、`readCapabilities()`、`validateSelection()`、`interpret()` 均存在。host 的 `capabilities(fresh=True)` 位於 `scripts/srpp-local-ai-bridge.py:951`，worker 的選定工具探測位於第 996 行，20 秒目錄快取位於第 924 行，符合任務背景所述的重複探測來源。
- **內部一致性：無發現。** 三份新契約一致區分 metadata 正向快取與 worker 即時授權，亦一致規定冷卻跨 process／重啟保存、有效 Retry-After 不受本機上限截短、拒絕時零推論。最新第 1 點已區分同步驗證回 409，以及背景解讀保留錯誤碼與數值；符合 `SrppSettingsService.java:124`、`:130`、`:136` 的既有背景執行與結果保存流程。
- **資料來源：無發現。** 本次不修改行情來源、金融公式、DB schema 或 9090 契約；未用 changelog 推定 schema 現況。採用提供的機械證據：`BLOCK: 0`、`CHECK: 0`，包含 schema 與 OpenAPI parity 通過。

架構鐵則逐條核對如下；依據為 `CLAUDE.md`、`spec/steering/structure.md` §2.2／§3.2，以及本次三份規格變更。

| 檢查項 | 查證結果 |
|---|---|
| Controller 直接讀 Repository、外部 HTTP、upsert 或領域判斷 | 否；本次未提出這類 Controller 變更。 |
| DTO 使用 `@Data`／setter | 否；規格不新增 DTO，既有 `AiCapabilities`／`AiTool`／`AiModel` 是 record，清單經 `List.copyOf` 保存。 |
| 新增頁面卻缺少專屬 BFF | 否；本次沒有新增頁面。 |
| 前端跨頁 BFF、直打 business 或繞過 `api/index.js` | 否；沿既有 wire 與頁面入口，未新增此類路徑。 |
| 自創跨頁共用例外 | 否；本次未主張例外。 |
| 同義值走不同 business API | 否；未新增平行來源或另一支能力 API。 |
| 違反服務相依方向 | 否；費用查詢仍由 host adapter 處理，未提出前端直連或 business 直連外部行情。 |
| FK／字串冗餘或保存衍生業務值 | 否；無 DB 變更，負向 state 是本次明定的退避控制狀態。 |
| 新業務分類硬編 enum | 否；新增項目是閉合錯誤協定碼，沒有新增可管理的投資業務分類。 |
| 新 endpoint 違反命名規則 | 否；本次不新增 endpoint。 |

## 自我挑戰

- **未將現有程式仍呼叫 `readCapabilities()` 列為缺陷。** 這是 Task 595 明確要修改的行為；實作前尚未完成不構成規格錯誤。
- **撤下背景 `interpret()` 必須回 HTTP 409 的疑慮。** 最新三份第 1 點已明確修正：同步驗證回 409，背景解讀保存 `AI_CATALOG_STALE` 與已算數值，不改已送出的非同步 HTTP 回應。
- **保留期限歸屬問題，但只列 Minor。** 12 秒確實是現有鏈路限制，要求遵守它的方向正確；問題是將其稱為 BFF 期限，屬措辭精確度，不會改變本次實作方向。
- 未把未來新增的 `AI_RATE_LIMITED`、協調鎖與持久化負向 state 當成不存在的既有功能；也未將 requirements 的未勾選 checkbox 當作未實作證據。
- 本輪僅執行唯讀檔案與 diff 查證，未執行測試、API、部署或任何寫入操作；真服務目前是否仍回 429，未作獨立確認。