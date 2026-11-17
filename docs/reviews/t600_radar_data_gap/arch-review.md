VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：`HEAD ef8e281de181deb6fbe7ba041a21e8c375501704` → 本輪未提交工作目錄。已核對提供的完整 diff：四支 backend service、三支 Java 測試、Python 分鐘 adapter 與測試、requirements／design，以及未追蹤的 `spec/tasks/t600_radar_data_gap_diagnostics.md`。以 `CLAUDE.md`、`spec/steering/structure.md` 為架構判準，Task600 文件僅用於核對實作契約；未擴大審查既有技術債。

## Findings

無。本輪未發現可由新增 diff 與明文規範共同成立的架構違規。

## 已查無發現

查證使用 `git status --short`、`git diff --stat`、`git diff --cached --stat`、`git diff -- backend/src/main/java fubon-broker-service/src`、`git diff --unified=0 ... | rg '^\+[^+]'`，並以 `rg -n` 讀取必要上下文及呼叫端。

1. **模組界線：未新增違反相依方向的行為。**  
   新增行只有來源型別驗證、純缺項列舉、字串組裝及既有診斷 list 追加；沒有外部行情 HTTP、反向 service 呼叫、跨層 SQL、DB／Redis 寫入或券商交易呼叫。Python 既有 `_gateway.read_intraday_candles(symbol)` 位於 `market_data_v1.py:130`，呼叫次數與位置未變。符合 `structure.md` §8 與 `CLAUDE.md`〈券商 API 只能查詢，不得交易〉。

2. **分層職責：未新增越層實作。**  
   本輪沒有 Controller、Repository、Entity 或 DTO 變更；四支 Java 實作均在 service 層，新增內容沒有 `ResponseEntity`／`HttpServletResponse`。符合 `structure.md` §2.2。未新增 Entity enum 映射問題，也沒有新增可變 DTO。

3. **BFF 與前端：本輪無適用變更。**  
   diff 沒有新增頁面、BFF、前端 API 呼叫、跨頁取值、裸 `fetch()`／`EventSource`、前端聚合或多 panel 載入流程，因此沒有擴大既有 SSE 例外，亦無新增一頁一支 BFF、同義 business API 或 `Promise.allSettled` 違規。依據為 `CLAUDE.md`〈BFF 與資料來源規範〉及 `structure.md` §3.2。

4. **資料模型、命名與資料來源：未新增契約或持久化變更。**  
   diff 沒有 schema、Liquibase changeset、金額／日期欄位、業務分類、endpoint、cache key 或 TTL 變更；沒有 FK／字串冗餘或衍生值持久化。即時價與收盤價的取用流程未變，也沒有以預設值、`o`／`y` 回寫價格。分鐘 reason 的 switch 是既有協定碼的顯示分類，並非新增可管理的投資業務分類。

5. **缺項診斷與既有完整性條件一致。**  
   `TradingRadarRuleEngine.java:3806` 的 `requiredInputGaps()` 逐項對應 `:3826` 的 `complete(StockInput)`、`:3840` 的指標完整性與 `:3849` 的 confirmation 可用性，包括債券不要求有效 equity market regime 的既有條件。  
   `rg -n 'requiredInputGaps'` 在 production 僅找到定義與 `TradingRadarService.java:1860` 的文案組裝呼叫，沒有接入選股、評分或動作判斷。

6. **日 K 根數與三軌風險接線符合 Task600。**  
   `TradingRadarService.java:1855` 在 full detail 且 `result.score() == null` 時追加診斷；根數取自 `acceptedPrice.indicatorSeriesRows().size()`，分母引用既有 `INDICATOR_SERIES_MAX_ROWS`。  
   `RadarObservationResolver.java:235` 起先由完成日歷史建立 `indicatorRows`，`:251` 的 live 接納分支仍將 live 與該清單分開保存，故新增計數沒有把 live 加進完成列。文案明示「本次完成日 K 輸入」，未宣稱原始列已具可用技術基礎，也未推測上市日期或下載故障。  
   新文案只追加至 `risks`／`shortRisks`／`swingRisks`（`:1861`–`:1863`）；原通用風險句及支持 lists 的既有內容與順序保留。

7. **配息與分鐘揭露維持風險語意。**  
   `TradingRadarEvidenceGate.java:185` 直接讀同一份 `evidence.dividendEvent()`，追加 status、非 null knownAt 與非空 missingReason；保留原共同揭露，swing 仍受 `swingAction != null` 約束，沒有重新查來源或改寫 resolution。`Evidence` 建構式在 `TradingRadarEvidenceConfidenceResolver.java:348` 已將 null resolution 正規化為 `MISSING`，因此此處沒有新增 null 解參照路徑。  
   `RadarIntradayCandlePolicy.java:144` 僅在 `UNAVAILABLE` 追加分類與原碼，未知碼使用通用說明；WAIT／CONFIRMED 分支與來源時間組裝未改。既有 `apply()` 在 `:124` 將該訊息加入 short diagnostics，符合 `CLAUDE.md` 的 Requirement117 風險診斷規範。

8. **金融規則、候選、分數與 DTO：未發現變更。**  
   `StockInput` 建構參數僅從內嵌運算式抽成同一局部變數，仍傳給同一個 `evaluateStock()`；`complete()`、評分、權重、閾值、候選及 action gate 邏輯均未改。`RULE_VERSION` 維持 `TW_RULES_V28`；DTO／StockInput 形狀、Swagger、通知邏輯與政策檔案均無 diff。  
   Python 唯一行為修正位於 `market_data_v1.py:140`：精確接受 int `1` 或 str `"1"`，拒絕 bool／float 等其他值；`:160` 的 normalized wire 仍固定整數 `1`，其餘 identity、row、排序、去重與 271 根限制未變。來源恢復後可能自然產生不同分鐘確認結果，這不代表金融規則被修改。

## 無法查證

- 本輪只讀檢視測試程式，未執行 Maven、pytest 或 `spec-check.sh`，因此不宣稱測試通過。新增測試可見 71／241、240／241、live 排除、缺欄、債券大盤例外、STALE／空 reason、未知分鐘 reason，以及 timeframe 正反例的斷言。
- 尚未部署；未重建容器、查驗背景 capture 或讀取 9090 回應。真 SDK 修復效果、自然背景同步與部署後資料揭露仍待作者驗收。
- 本輪沒有 schema 變更，未對運行中 DB 形貌或 `db/schema.sql` 同步狀態作斷言，亦未以 changelog 推測現況。

## 自我挑戰

- 已以實際 `git diff` 確認審查內容均屬本輪新增或修改；未將既有 Controller 直讀 Repository、SSE 路徑等基準線列為 finding。
- 未踩 DTO 外層 class／巢狀 record、註解中的 `@Data`、service 區域 double 三種誤報陷阱。本輪沒有 DTO 或持久化金額型別變更；上下文中的既有 confidence double 閾值不屬新增違規。
- 曾核對新增配息解參照是否可能為 null，以及 helper 是否改變完整性要求；前者由既有建構式保證非 null，後者與既有 `complete()` 條件一致，均未列為 finding。
- 已重新以 `rg -n` 核對 `CLAUDE.md` 的分層、BFF、風險診斷規範及 `structure.md` §2.2／§3.2／§8；沒有以風格偏好或未明文規定的要求補出 finding。