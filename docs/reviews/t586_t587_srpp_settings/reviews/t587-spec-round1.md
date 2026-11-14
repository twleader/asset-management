VERDICT: CHANGES_REQUESTED

## 判定
critical: 1   major: 0   minor: 0

## Findings

### [Critical] 現有 getStockDetail() 並非規格宣稱的純讀來源，快取缺漏時會刷新交易日曆

位置：`spec/design.md:13984`、`spec/tasks/t587_srpp_short_opportunities.md:28`。

證據：

- 規格要求「只重用 TradingRadarService.getList() 與懶載入 getStockDetail() 相同 owner 純讀來源」，同時禁止 refresh／來源寫入。
- `backend/src/main/java/com/steven/assets/service/TradingRadarService.java:846`：detail 呼叫雙參數 `buildMarket(context.market(), decisionInstant)`。
- 同檔 `:1185`：該 overload 固定傳入 `cacheOnlyCalendar=false`；`:1205–1206` 因此選用 `marketDataService::isTwTradingDayKnown`。
- `backend/src/main/java/com/steven/assets/service/MarketDataService.java:441`：上述方法呼叫 `getTwHolidays()`；`:256` 在當年度快取缺漏／過期時呼叫 `fetchTwHolidaysFromExt()`，`:261` 更新快取；`:339` 實際委派 `external.twHolidays(year)`。
- external 端 `external-materials-service/src/main/java/com/steven/assets/externalmaterials/service/MarketDataFetchService.java:1009` 會抓取來源，`:1034–1039` 可呼叫 TWSE／DGPA，`:1017` 安裝更新後的快取。這不是單純讀取既有證據。

被違反的出處：Requirement 208 AC25（`spec/requirements.md:6977`）及本增量自身的禁止 refresh／來源寫入契約。依審查判準，這是會使實作者直接沿用有副作用路徑的既有事實錯誤斷言。

修法：同步修訂 design 與 t587，明列 detail 純讀化為本任務工作：提供共用的 cache-only detail 路徑，涵蓋大盤、decision clocks、未來交易日及其下游日曆查詢；快取未知即等待，不落入刷新分支。新增冷快取、過期快取及跨年度案例，驗證零 holiday proxy／vendor／writer 呼叫，並保留單檔一次懶載入。

## 已查無發現

- **機械證據**：採用本輪提供的 `BLOCK: 0 / CHECK: 0`，未重跑。
- **可查證性**：以 `rg -an` 核對 `CurrentUserContext`、雷達 list/detail、SHORT action 碼、價格參考欄位及既有單筆 simulation GET；除上述純讀斷言外，無發現。
- **內部一致性**：核對 requirements／design／t587 的 16 欄、76 欄總數、來源 pointers、7／3 檔白名單、跨欄限制與歷史摘要契約，無發現。
- **架構檢查表**：
  1. Controller 直接讀 Repository／執行領域邏輯：否，規格僅允許委派 service。
  2. 新 DTO 使用可變 setter／`@Data`：否，明定 immutable records。
  3. 新頁缺專屬 BFF：否，為既有頁 child，新增本頁 exact BFF。
  4. 跨頁 BFF／直打 business／裸 fetch：否。
  5. 自創跨頁共用例外：否。
  6. 同義來源分岔：否，明定重用既有雷達 service。
  7. 禁止的跨服務相依：未見新增；純讀呼叫鏈問題已列 finding。
  8. FK／字串冗餘或持久化衍生值：否，未新增資料模型。
  9. 新增需管理的業務分類卻硬編 enum：否，三組為既有來源政策的回應分組。
  10. Endpoint 命名／method 違規：否。
- **資料來源**：16 欄 pointers 與預設來源可於 `srpp-simulator/runtime/assumptions/model_inputs.json` 查得；`PriceReference` 欄位與既有 DTO 相符。未新增 DB schema 斷言或行情來源，無其他發現。

## 自我挑戰

- **保留上述 finding**：`@Transactional(readOnly=true)` 不能禁止 HTTP 或快取更新；已追到實際日曆刷新與快取安裝路徑。平常快取命中不能證明此入口純讀。
- **撤下「歷史 root 目前仍回完整結果」疑點**：metadata-only 是本輪待實作行為，不能用現況尚未修改當作規格缺陷。
- **未將新 records／端點／測試尚不存在列為問題**：它們均明確屬未來工作；Task 586 已審範圍未重新列入缺陷。