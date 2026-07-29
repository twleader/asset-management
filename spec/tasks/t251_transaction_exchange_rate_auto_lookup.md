# [t251] 交易紀錄的匯率改為依交易日期自動帶出（唯讀，不再手填）

**對應 Requirements:** Requirement 49（資產交易紀錄——手動買賣流水帳與 Excel 手動／每日排程匯出）
**前置任務:** 無（t250 已 merge，本任務與其互不相干）
**Liquibase changeset:** 無（本任務不動資料庫，欄位沿用既有 `asset_transaction.exchange_rate numeric(10,4)`）

## 背景

**現在的行為：** `frontend/src/views/TransactionView.vue` 的「匯率」欄是純手打 `el-input`（`txForm.exchangeRateStr`），只在 `txForm.currency === 'USD'` 時顯示，blur 時格式化為 4 位小數。business 端 `AssetTransactionService.create/update` 把 `req.exchangeRate()` **原封寫入**，完全沒有查詢。這是**全庫唯一**靠使用者手打匯率的地方。

**使用者要的行為：** 匯率不要自己填，由交易日期自動查出當天匯率。

**同語意的其他三處早就自動化了**（本任務要對齊的既有事實）：
- `asset_snapshot.usd_exchange_rate`：`SnapshotFormView` 在快照日期變更時打 `GET /api/bff/snapshot-form/exchange-rate?date=`，取 `midRate` 填入 **disabled** 欄。
- `stock_holding.transaction_exchange_rate`：同一支 BFF 端點，在美股券商列的「交易日期」變更時查（`SnapshotFormView.vue` 的 `<el-date-picker ... @change="(d) => onUsTransactionDateChange(br, d)">`），唯讀顯示。
- `realized_gain.exchange_rate`：由 business 端 `AssetService.lookupExchangeRate(tradeDate)`＝`findClosestRate("USD", tradeDate).getMidRate()` 自動查，`RealizedGainView` 表單根本沒有匯率輸入欄。

**已經存在、不需要新造的東西：**
- business 端點 `GET /api/market-data/exchange-rate/on-date?currency=USD&date=YYYY-MM-DD`（`MarketDataController.java:249-256`），內部走 `HistoricalDataService.getExchangeRateOnDate` → `ExchangeRateHistoryRepository.findClosestRate`（`:32`，`WHERE e.currency = ?1 AND e.rateDate <= ?2 ORDER BY e.rateDate DESC LIMIT 1`）＝**該幣別在該日或之前最近一筆**（closest-on-or-before，per-currency）。查無任何列時 `.orElse(ResponseEntity.notFound().build())` 回 **404**（空 body）。
- 回應 JSON 形狀（實測）：`{"id":6186,"currency":"USD","rateDate":"2026-07-24","buyRate":32.3450,"sellRate":32.3450,"fundValuationRate":32.3450,"midRate":32.3450}`。`midRate` 與 `fundValuationRate` 是 `ExchangeRateHistory` 的 `@Transient` getter 被 Jackson 序列化出來的，**不是 DB 欄位**（DB 只有 `buy_rate`／`sell_rate`）；`getMidRate()` 為 `divide(2, 4, HALF_UP)`。

**必須知道的資料現況**（決定 fallback 與 UI 文案，皆為運行中 DB 實測）：
- `exchange_rate_history` 只有 `USD`（**2488 筆，2016-07-29 ～ 2026-07-29**）與 `ZAR`（1772 筆，2016-07-28 ～ 2026-07-27）兩種幣別，無 `owner_user_id`、無 `@Filter`，是**全域共用**資料。**2016-07-28 是 ZAR 的起點，USD 沒有那一天**——`findClosestRate` 是 per-currency 的，所以 2016-07-28 的 USD 交易一樣會 404。（起點會隨時間浮動：每次 `POST /api/market-data/exchange-rate/refresh` 都會 `purgeOldExchangeRates(currency, 10)` 砍掉十年前的列。）
- **近 365 個日曆日只有 263 天（72.1%）有 USD 列**。週末一律沒有；國定假日的平日也沒有（近一年 14 個平日缺，含農曆年 02-16～02-19、清明 04-03／04-06、2025-10-10 等）。**最長要往前退 9 天**（2026-02-13 之後下一筆是 02-23）。所以「查當天精確日期」約 28% 會落空，closest-on-or-before 是必需的、不是保險。
- 未來日期 → 回今天那筆（不會出錯）。
- 近日的列多為 `buy_rate = sell_rate`（台銀被 Akamai WAF 擋時以 Yahoo 中間價暫定寫入），全表僅 9 筆符合，**皆為 2026-07-04 起**：07-04、07-11、07-12、07-18、07-19、07-24、07-27、07-28、07-29。隔日 17:00 由 FinMind 以真實買賣價覆寫同一 `(currency, rate_date)` 列。

## 要做什麼

### 251.1 BFF：`TransactionBffController` 新增匯率 passthrough

檔案 `bff/src/main/java/com/steven/assets/bff/transaction/TransactionBffController.java`。該類別已有 `private final WebClient businessServicesClient;`（`:34`）與 `private static final ParameterizedTypeReference<Map<String, Object>> MAP`（`:38-39`）。在既有 `/lookup-name`（`:68`）之後新增：

```java
/**
 * 交易日期對應的匯率（Task 251）。轉呼「同一支」business API
 * {@code /api/market-data/exchange-rate/on-date}（與快照表單、美股持股交易日匯率、已實現損益同源，
 * 底層同為 ExchangeRateHistoryRepository.findClosestRate），不在 business 端新增第二份實作。
 *
 * <p>降級**只針對 4xx**：business 對「該日之前沒有任何該幣別的列」回 404，此處轉為 200 空物件，
 * 讓前端把欄位留白並提示「查無牌告」。5xx／逾時／連線中斷**必須讓它浮上去**——那是「取不到」
 * 而非「沒有」，前端要據以解除匯率欄的唯讀狀態讓使用者手動輸入（Requirement 49）。
 * 兩者若共用同一條降級路徑，使用者會在系統故障時看到「查無匯率」並存下 exchangeRate=null 的
 * USD 交易，其台幣金額會等於美元金額（少算約 32 倍）且直接計入年度彙總。
 */
@GetMapping("/exchange-rate")
public Mono<ResponseEntity<Map<String, Object>>> exchangeRate(@RequestParam String date) {
    return businessServicesClient.get()
            .uri(uriBuilder -> uriBuilder.path("/api/market-data/exchange-rate/on-date")
                    .queryParam("currency", "USD")
                    .queryParam("date", date)
                    .build())
            .retrieve()
            .onStatus(org.springframework.http.HttpStatusCode::is4xxClientError,
                    resp -> Mono.empty())          // 4xx（含 404）不視為錯誤
            .bodyToMono(MAP)
            .defaultIfEmpty(java.util.Collections.emptyMap())   // 404 無 body → {}
            .map(ResponseEntity::ok);
}
```

約束：
- **`onStatus(...4xx..., resp -> Mono.empty())` 與 `defaultIfEmpty` 必須成對**。`onStatus` 的 handler 回 `Mono.empty()` 表示「不產生錯誤訊號」，此時 `bodyToMono` 會得到空序列，若不接 `defaultIfEmpty`，`ResponseEntity.ok()` 那步不會執行，前端會收到空回應而非 `{}`。
- **不得**改寫成 `.onErrorReturn(Collections.emptyMap())`（`SnapshotFormBffController.java:121` 的既有寫法）。那會把 5xx、逾時、連線中斷一併吞成 `200 {}`，正是上面 javadoc 要避免的事。本頁刻意與該既有先例不同，**這是有意的分歧，不是漏抄**。
- **`currency` 寫死 `USD`，不開放參數**。本頁的匯率欄只在 `currency === 'USD'` 時存在；`exchange_rate_history` 目前也只有 USD／ZAR 兩種幣別，開放參數只會製造查不到的路徑。
- **不得**改為呼叫別頁的 `GET /api/bff/snapshot-form/exchange-rate`（違反「一個前端頁面一個 BFF」）。
- **不得**讓前端改走 BFF 既有的 `/api/market-data/**` gateway passthrough route（同上；該 route 的服務對象是 TradingCalendarView／ExchangeRateView／AssetHistoryView）。
- **不得**比照 `SnapshotFormBffController.java:106-114` 加「date 等於今天就先 `POST /api/market-data/exchange-rate/refresh`」那段。理由：該 refresh 一次做三件事——FinMind 回補近 10 天、台銀/Yahoo 抓今日、**`purgeOldExchangeRates(currency, 10)` 刪除十年前的資料**（`MarketDataController.java:277-280`），對「填表時順手查匯率」而言副作用過大；而盤中匯率排程本來就每 5 分鐘更新一次（`ExchangeRatePoller.java:40` 的 `@Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")`，寫死於程式碼、非 DB 可設定）。
  > **不要拿「容器時區」當理由。** Task 252 起全 stack 以啟動參數統一為 `Asia/Taipei`（實測 host／business／bff 三者同秒同時區、`TZ=Asia/Taipei`），`LocalDate.now()` 是可靠的。
- **不得**在 business 端新增或修改任何東西（`MarketDataController`／`HistoricalDataService`／`AssetTransactionService` 一行都不改）。

### 251.2 前端 API wrapper

`frontend/src/api/index.js` 的 `transaction` 命名空間（`:156-169`）新增一支。**不可**帶 `skipErrorToast`——本頁需要區分查無與失敗，失敗時前端自己會顯示提示並解除唯讀，但攔截器的錯誤仍須以 rejection 形式傳到 `catch`：

```js
// Task 251：依交易日期查匯率（轉呼同一支 business /api/market-data/exchange-rate/on-date）
// 查無牌告時 BFF 回 200 {}；5xx／連線失敗才會走到呼叫端的 catch
exchangeRate: (date) => api.get('/bff/transaction/exchange-rate', { params: { date }, skipErrorToast: true }),
```

（`skipErrorToast: true` 仍要帶——它只抑制全域錯誤 toast，不影響 promise rejection；本頁對失敗有自己的欄位內提示，不需要再彈一次全域 toast。攔截器判斷處為 `frontend/src/api/index.js:32` 的 `err.config?.skipErrorToast`，回應攔截器為 `:12-13` 的 `res => res.data`，故呼叫端拿到的 `res` 就是 body，可直接讀 `res.midRate`——與 `SnapshotFormView.vue` 既有的 `Number(res.midRate)` 同款。）

### 251.3 前端 view：匯率改唯讀 ＋ 自動查詢

檔案 `frontend/src/views/TransactionView.vue`。

**(a) 新增 state 與查詢函式**（放在既有 `computedAmountTwd` computed 之前）：

```js
// ===== Task 251：匯率依交易日期自動帶出（唯讀） =====
const fxRateDate = ref('')      // 實際採用的匯率日期（可能早於交易日期：假日往前退）
const fxNotFound = ref(false)   // 查無（BFF 對 404 回 200 {}）：該日之前沒有任何 USD 列
const fxError = ref(false)      // 查詢失敗（5xx／逾時／連線中斷）：解除唯讀讓使用者手填
const originalFxStr = ref('')   // 編輯時該筆自己已存的匯率，用於還原（見 (d)）
const fxDateDirty = ref(false)  // 使用者是否「實際改動過」交易日期
let fxSeq = 0                   // 競態序號：只認最後一次

const clearFx = () => {
  txForm.exchangeRateStr = ''
  fxRateDate.value = ''; fxNotFound.value = false; fxError.value = false
}

const refreshExchangeRate = async () => {
  const date = txForm.tradeDate
  const seq = ++fxSeq                        // 必須在 early-return 之前遞增，見下方說明
  if (txForm.currency !== 'USD' || !date) { clearFx(); return }
  try {
    const res = await bffApi.transaction.exchangeRate(date)
    if (seq !== fxSeq) return                // 已有更新的查詢，丟棄本次結果
    const rate = res?.midRate
    if (rate != null) {
      txForm.exchangeRateStr = fmtNum(Number(rate), 4)
      fxRateDate.value = res.rateDate || ''
      fxNotFound.value = false; fxError.value = false
    } else {                                 // BFF 對 404 降級成 200 {}
      clearFx(); fxNotFound.value = true
    }
  } catch (e) {                              // 非 4xx：取不到，不是沒有
    if (seq !== fxSeq) return
    clearFx(); fxError.value = true
  }
}
```

- **`fxSeq` 必須在 early-return 之前遞增**，且**每一條會使前次查詢作廢的路徑都要遞增它**——包含 (b)-2 那條「幣別切離 USD 只清空、不發新請求」的路徑（那裡要顯式 `fxSeq++`）。少了這點，「USD 查詢送出 → 回應前使用者切成台幣」的 in-flight 回應到達時 `seq === fxSeq` 仍成立，會把匯率寫回一個已經不是 USD 的表單；雖然 `submit()` 有 `currency === 'USD'` 的二次把關、髒值不會進 DB，但殘留值會讓下次切回 USD 時被誤判成「已有值」而略過查詢。
- 取的是 **`midRate`**（中間價），不是 `buyRate`／`fundValuationRate`。理由：`asset_snapshot.usd_exchange_rate`、`stock_holding.transaction_exchange_rate`、`realized_gain.exchange_rate` 三處同義欄位皆取 `midRate`；只有基金估值刻意用 `buyRate`（`getFundValuationRate()`，其註解明寫「不可用中間價（會高估）」），本欄語意屬前者。
- `fmtNum(x, 4)` 是同檔既有函式（`:403-411`，只對整數部分插千分位），與 `parseNum`（`:399-402`，剝除逗號）往返無損。

**(b) 觸發點共三個，一個都不能多**：

1. **使用者改交易日期** — 模板 `:275` 的 `el-date-picker` 改為：
   ```html
   <el-date-picker v-model="txForm.tradeDate" type="date" value-format="YYYY-MM-DD" style="width:100%"
     :clearable="false" @change="onTradeDateChange" />
   ```
   ```js
   const onTradeDateChange = () => { fxDateDirty.value = true; refreshExchangeRate() }
   ```
   - **`:clearable="false"` 不可省**。Element Plus 的 date-picker 預設 clearable，而清除鈕走的 `emitChange(valueOnClear, true)` 會**無條件**發 `change` 並重設 `valueOnOpen`，於是「按 × 清空 → 重選原本那一天」會連發兩次 `change`、第二次日期其實沒變卻仍會重查並覆寫已存匯率。`tradeDate` 本來就是必填（`:382` 的 `rules`），允許清空沒有意義。
   - > **絕對不得改用 `watch(() => txForm.tradeDate, ...)`。** `el-date-picker` 的 `@change` 只在**使用者互動**時觸發（element-plus 內部 `emit(CHANGE_EVENT, ...)` 僅三個呼叫端：面板關閉且值與 `valueOnOpen` 不同、點清除鈕、手打輸入解析），程式指派 `txForm.tradeDate = row.tradeDate`（`openEditDialog`，`:547`）不會觸發它——這正是「編輯既有紀錄不得覆寫已存匯率」得以成立的機制之一。改成 watch 會讓每一次開啟編輯對話框都重查並覆寫該筆的歷史匯率，那是本任務**明文禁止**的行為（理由見 (d)）。同專案既有先例：`SnapshotFormView.vue` 的美股持股交易日匯率就是用 `@change` 掛的。

2. **幣別變動** — 新增一支 watch（放在既有 `watch(() => txForm.market, ...)`（`:394-396`）之後）：
   ```js
   // Task 251：幣別切到 USD 時補匯率；切離 USD 時清掉，避免殘值
   watch(() => txForm.currency, (c) => {
     if (c !== 'USD') { fxSeq++; clearFx(); return }          // fxSeq++ 使 in-flight 查詢作廢
     if (originalFxStr.value && !fxDateDirty.value) {          // 編輯中且日期未被改動 → 還原原值，不重查
       txForm.exchangeRateStr = originalFxStr.value
       return
     }
     if (!String(txForm.exchangeRateStr || '').trim()) refreshExchangeRate()
   })
   ```
   **`originalFxStr && !fxDateDirty` 這一段是核心保護，不可省略。** 只憑「欄位是否為空」判斷會有一條實際會走到的破口：編輯一筆美股交易 → 誤把「市場」改成台股（既有的 market watch 把 `currency` 轉 TWD → 匯率被清空）→ 再改回美股（`currency` 轉回 USD → 看到空值 → 查詢）→ 該筆的歷史匯率被當日中間價覆寫，而交易日期一次都沒被碰過、欄位又是唯讀的，使用者沒有任何手段改回來。

3. **新增對話框開啟** — `openCreateDialog()`（`:532` 附近）在設完 `tradeDate` 之後補一行：
   ```js
   const openCreateDialog = () => {
     resetForm()
     txForm.tradeDate = new Date().toISOString().slice(0, 10)
     refreshExchangeRate()          // Task 251：預設日期（今天）的匯率
     dialogVisible.value = true
   }
   ```
   必要性：`resetForm()` 內設 `currency` 時 `tradeDate` 還是空字串，(b)-2 那支 watch 查不到東西；且程式設 `tradeDate` 不會觸發 (b)-1。

**(c) 模板：匯率欄改唯讀並揭露實際匯率日期**。`:311-314` 改為：

```html
<el-form-item label="匯率" v-if="txForm.currency === 'USD'">
  <el-input v-model="txForm.exchangeRateStr" :disabled="!fxError"
    :input-style="{ textAlign: 'right' }" @blur="onBlurField('exchangeRate', 4)" />
  <span class="fx-hint fx-hint-warn" v-if="fxError">匯率服務暫時無法連線，可自行輸入或稍後再試</span>
  <span class="fx-hint" v-else-if="fxNotFound">查無 {{ txForm.tradeDate }} 或之前的匯率，可留空儲存</span>
  <span class="fx-hint" v-else-if="fxRateDate && fxRateDate !== txForm.tradeDate">
    採用 {{ fxRateDate }} 匯率（交易日無牌告）
  </span>
</el-form-item>
```

- `:disabled="!fxError"`：正常情況唯讀（使用者不必填、也不能填），**查詢失敗時自動解鎖**讓使用者手動輸入。用 `disabled` 而非 `readonly`，比照 `SnapshotFormView` 既有的「自動帶入唯讀欄」慣例。
- **`@blur="onBlurField('exchangeRate', 4)"` 與 `fieldMap`（`:414-419`）的 `exchangeRate: 'exchangeRateStr'` 兩者都要保留**——解鎖後使用者手打的值仍需要 blur 格式化。`fieldMap` 其餘三項（shares／price／amount）不准動。
- `.fx-hint` 為小字灰色（≤12px），`.fx-hint-warn` 用 Element Plus 的警告色（`var(--el-color-warning)`），加在同檔既有的 `<style scoped>` 內。
- **揭露日期這件事不可省**：近一年有 28% 的日曆日沒有匯率列，回退是常態；不顯示實際採用日期，使用者會把「2026-02-13 的匯率」誤讀成「2026-02-23 成交當天的牌告價」。

**(d) 編輯既有紀錄的保護**（本任務最容易做錯的地方，逐條照做）：
- `resetForm()`（`:522` 附近）內補：`originalFxStr.value = ''`、`fxDateDirty.value = false`、`clearFx()`。
- `openEditDialog()`（`:538-555`）**只准加一行**，加在既有的 `txForm.exchangeRateStr = row.exchangeRate != null ? fmtNum(row.exchangeRate, 4) : ''`（`:553`）之後：
  ```js
  originalFxStr.value = txForm.exchangeRateStr   // Task 251：保留該筆自己的匯率，供切換幣別後還原
  ```
  該函式其餘每一行（含 `:547` 的 `txForm.tradeDate = row.tradeDate || ''`）**都不准改**。`fxDateDirty` 由 `resetForm()` 重置為 false，而 dialog 的 `@closed="resetForm"`（`:226`）保證每次開啟前都已重置。
- 只有三種情況會在編輯時查詢：使用者**手動改動交易日期**（(b)-1，會設 `fxDateDirty`）、該筆原本 `exchangeRate` 為 null（`originalFxStr` 為空 → (b)-2 的空值條件）、或幣別由非 USD 切成 USD 且日期已被改過。
- **為什麼不能無條件重查**（兩條都有實據，不是假想）：
  1. 既有資料裡有使用者刻意填的、與當日中間價不同的值——`asset_transaction` id=63（MSFT，2026-01-29）存 `31.5000`，而該日 `exchange_rate_history` 是 buy 30.9000 / sell 31.5700 / **mid 31.2350**；手填值貼近**賣出價**，很可能是券商實際扣款匯率。重查會把它改成 31.2350。
  2. `exchange_rate_history` 的當日列**會被隔日覆寫**：台銀被 WAF 擋時當日先寫 Yahoo 中間價（`buy_rate = sell_rate`），隔日 17:00 由 FinMind 用真實買賣價覆寫同一列。`realized_gain` 已有 4 組「存檔值 ≠ 事後重算值」的實例（2026-04-21 存 31.4850／現算 31.4200、04-30 存 31.6500／現算 31.5950、05-06 存 31.4800／現算 31.4150、05-29 三筆存 31.3500／現算 31.2900）。無條件重查＝用事後修訂過的數字改寫歷史交易。
- 交易紀錄的匯率是**成交當下凍結的事實**，比照同表「券商／通路記錄成交當下名稱字串」的既有 denormalization 例外。

**(e) 不得產生的副作用（自查清單）**：
- `submit()`（`:557`）**不改**：仍是 `currency === 'USD' && exchangeRateStr 非空 → parseNum(...)`，否則 `null`。
- `computedAmountTwd`（`:443-448`，台幣成交金額即時預覽）**不改**：它讀 `exchangeRateStr`，自動填值後會自動更新。
- 列表「台幣成交金額」欄的 tooltip（`:77-79`，顯示 `匯率：x（tradeDate）`）**不改**。
- 後端零變更：`AssetTransactionService`、`AssetTransactionDto`、`AssetTransaction` entity、`ExcelExportService.assetTxAmountTwd`、`MarketDataController`、`HistoricalDataService` 全部**一行都不准改**；**不新增 Liquibase changeset**；**不需異動** `SchedulePublicBffController.JOBS`（未新增 `@Scheduled`）。
- 既有測試 `AssetTransactionServiceTest`（`amountTwd_USD幣別等於amount乘匯率`:101／`amountTwd_USD但匯率為null時退回amount`:122）與 `AssetTransactionExcelExportTest`（匯率為匯出第 13 欄）**必須維持通過且不需修改**——若你發現得改它們，代表改到了不該改的後端。
- **不新增前端測試框架**：`frontend/package.json` 的 scripts 只有 `dev`／`build`／`preview`，全庫沒有 vitest／jest 與任何前端測試檔；BFF 側現有測試僅 2 支（`TenantWebFilterTest`、`LiveAssetsOverlayTest`），無 controller 測試慣例。驗收以 business 端到端 curl ＋ 實機操作為準（見下）。

## 驗證

BFF 建置：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml package -DskipTests
```

後端全套測試（本任務不改後端，這是**迴歸**確認）：

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DargLine="-Dnet.bytebuddy.experimental=true" test
```

前端建置。**worktree 內沒有 `node_modules`**（在主 repo 且被 gitignore），先連結過去，建完移除。`root` 必須用位置參數，本專案 vite 5 的 `build` 子命令不吃 `--root`：

```bash
ln -s /Users/steven/Project/asset-management/frontend/node_modules frontend/node_modules && /Users/steven/.nvm/versions/node/v22.21.0/bin/node frontend/node_modules/.bin/vite build frontend; rm frontend/node_modules
```

spec 機械檢查：

```bash
bash scripts/spec-check.sh
```

部署。**worktree 內沒有 `.env`**，compose 會在 interpolate 整份 `docker-compose.yml` 時就以 `required variable ADMIN_EMAIL is missing a value` 中止（`--env-file` 救不了，`env_file` 是相對 compose 檔解析）；且全機只有一套 `asset-management-*:latest` 映像、多 worktree 並行，**merge 後一律從 main 的 worktree（`/Users/steven/Project/asset-management-main`）重建**：

```bash
docker compose -p asset-management build --no-cache bff frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
```

驗證運行中的 bff jar 真的含本次變更（JVM service 若吃到 cached layer 會產出不含變更的 stale jar）：

```bash
docker exec asset-bff sh -c 'unzip -l /app/app.jar | grep -i TransactionBffController'
```

**下游 business 端點的四種情境**（`asset-business-services` 容器內有 curl、port 8080）。**注意：BFF 的 `/api/bff/**` 沒有免 OAuth 的打法**——`SecurityConfig` 只 permitAll `/oauth2/**`、`/login/**`、`/actuator/health|info`，而 `TenantWebFilter` 會**剝除 client 自帶的 `X-User-*` 偽造標頭**，實測帶 header 直打 BFF 一律回 401（從容器內或從 host 經 gateway 都一樣）。故這四條驗的是 BFF 轉呼的下游，BFF 自己那層（4xx→`{}`、5xx 不吞）只能靠後面的實機操作驗。

平日有牌告 → `rateDate` 等於查詢日、`midRate` 為當日中間價：

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/market-data/exchange-rate/on-date?currency=USD&date=2026-07-28"
```

預期含 `"rateDate":"2026-07-28"` 與 `"midRate":32.3680`（與使用者截圖那筆 VT 交易的手填值一致）。

週末 → 回退到前一個有牌告日：

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/market-data/exchange-rate/on-date?currency=USD&date=2026-07-26"
```

預期 `"rateDate":"2026-07-24"`（退 2 天）。

農曆年長假 → 退 5 天以上仍要拿得到：

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/market-data/exchange-rate/on-date?currency=USD&date=2026-02-18"
```

預期 `"rateDate":"2026-02-13"`。

早於 USD 主檔起點（2016-07-29）→ business 回 404（BFF 會把它轉成 `200 {}`）：

```bash
docker exec asset-business-services curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/market-data/exchange-rate/on-date?currency=USD&date=2000-01-01"
```

預期輸出 `404`。

實機操作驗證（瀏覽器開 http://localhost → 交易紀錄頁，共 8 條）：

1. 市場 tab 點「美股」→ 新增 → **匯率欄已自動帶值且呈灰色不可編輯**，台幣成交金額隨之計算。
2. 把交易日期改成上一個**週六** → 匯率更新，欄位下方出現「採用 YYYY-MM-DD 匯率（交易日無牌告）」且該日期早於所選日期。
3. 把交易日期改成 **2026-02-18**（農曆年）→ 顯示採用 2026-02-13。
4. 把交易日期改成 **2010-01-01** → 匯率欄留空、顯示「查無…可留空儲存」，欄位仍唯讀，且**儲存按鈕仍可按**。
5. 把市場改成台股（幣別轉 TWD）→ 匯率欄整列消失；再改回美股 → 重新帶值。
6. **編輯保護（核心）**：對既有那筆 MSFT（2026-01-29，匯率 31.5000）按編輯 → **匯率仍顯示 31.5000**（不是 31.2350）；不動任何欄位直接儲存 → 重新開啟編輯，值仍為 31.5000。
7. **編輯保護之切換市場往返**：同一筆編輯中，把市場改成台股再改回美股 → **匯率必須仍是 31.5000**（這條專門釘 (b)-2 的 `originalFxStr` 還原；缺了它會變成 31.2350）。
8. **改日期才重查**：同一筆編輯時把交易日期改成 2026-07-28 → 匯率變為 32.3680；按取消（不儲存）→ 重新編輯 → 仍是 31.5000（未被寫入）。

## 完成報告

**實作日期：** 2026-07-29

**修改檔案（3；business 與 DB 零變更、無 Liquibase changeset）**
- `bff/.../transaction/TransactionBffController.java`：新增 `GET /exchange-rate?date=`（`onStatus(4xx → Mono.empty())` ＋ `defaultIfEmpty(emptyMap())`，5xx 不吞）；`import HttpStatusCode`、`import java.util.Collections`
- `frontend/src/api/index.js`：`transaction` 命名空間新增 `exchangeRate(date)`
- `frontend/src/views/TransactionView.vue`：匯率欄改 `:disabled="!fxError"`、新增 `refreshExchangeRate()`／`onTradeDateChange()`／currency watch／`fxRateDate`／`fxNotFound`／`fxError`／`originalFxStr`／`fxDateDirty`／`fxSeq`、date-picker 加 `:clearable="false"` 與 `@change`、`resetForm`／`openCreateDialog`／`openEditDialog` 各補匯率狀態處理、新增 `.fx-hint` 樣式

**與原計畫的偏差**：無。

**驗證輸出**
- `bff` package：BUILD SUCCESS
- `backend` 全套迴歸（本任務不改後端）：`Tests run: 276, Failures: 0, Errors: 0, Skipped: 0`
- 前端 `vite build`：`✓ built in 4.42s`
- `scripts/spec-check.sh`：0 BLOCK / 0 CHECK
- **下游 business 端點四情境實測全中**：
  - `2026-07-28` → `{"rateDate":"2026-07-28","midRate":32.3680}`（與使用者截圖那筆 VT 的手填值一致）
  - `2026-07-26`（週日）→ `{"rateDate":"2026-07-24","midRate":32.3450}`（回退 2 天）
  - `2026-02-18`（農曆年）→ `{"rateDate":"2026-02-13","buyRate":31.1050,"sellRate":31.7750,"midRate":31.4400}`（回退 5 天，且為有真實買賣價差的列）
  - `2000-01-01` → HTTP `404`（BFF 會轉成 `200 {}`）

**spec 對抗式審查（獨立 subagent）：critical 1 / major 5 / minor 3，9 條全數修正**
1. **[critical] 「容器跑 UTC」是錯的斷言** — 兩個探索者在不同時點得到相反的實測結果，實查後確認：**main 在本次工作期間被別的 worktree 推進**，Task 252（`e96901f3`，系統時區基準統一為台北）已 merge 並部署，現在 host／business／bff 三者同秒同時區、`TZ=Asia/Taipei`。原本拿「時區不可靠」當「不做 refresh」的理由已失效，改以 refresh 的副作用（含 `purgeOldExchangeRates` 刪十年前資料）為唯一理由，並在 design 加註不得再引用該錯誤斷言。
2. **[major] 編輯時來回切市場會覆寫已存匯率** — 原設計只憑「匯率欄是否為空」判斷是否補查，但「編輯美股交易 → 誤改市場為台股（幣別轉 TWD、匯率被清空）→ 改回美股」會讓空值條件成立而觸發查詢，交易日期一次都沒被碰過卻覆寫了歷史匯率，且欄位唯讀、使用者無法改回。改為「`originalFxStr` 原值備份 ＋ `fxDateDirty` 顯式旗標」，並加驗收第 7 條專門釘它。
3. **[major] `fxSeq` 早遞增的守衛對它自己舉例的情境空轉** — 幣別切離 USD 的分支是行內清空、不進 `refreshExchangeRate()`，`fxSeq` 不會遞增。已在該分支顯式 `fxSeq++`。
4. **[major] 驗證段四條 BFF curl 全跑不動** — `asset-bff` 容器沒有 curl、port 是 8080 不是 8081，且 `TenantWebFilter` 會剝除 client 自帶的 `X-User-*`，帶 header 直打 BFF 一律 401（容器內與經 gateway 都試過）。改為打 `asset-business-services` 驗下游，並寫明 BFF 那一層只能靠實機操作驗證。
5. **[major] USD 匯率主檔起點寫錯** — 實際是 **2016-07-29／2488 筆**；2016-07-28 是 ZAR 的起點，而 `findClosestRate` 是 per-currency，故該日的 USD 交易一樣 404。requirements 與任務檔都已更正。
6. **[major] 「查無牌告」與「查詢失敗」被混為一談** — 原設計用 `onErrorReturn` 把 5xx／逾時一併吞成 `200 {}`，前端顯示同一句「查無匯率」；而本任務把手填欄改唯讀，等於拿掉了使用者唯一的輸入手段，故障時將完全無法記下金額正確的 USD 交易（`exchangeRate=null` 的 USD 交易台幣金額會等於美元金額、少算約 32 倍並計入年度彙總）。改為 BFF 只降級 4xx，前端對非 4xx 失敗顯示不同文案並**暫時解除唯讀**讓使用者手填。
7. **[minor] 行號偏差**（`openEditDialog` 538–555、`submit` 557）已更正。
8. **[minor] `el-date-picker` 預設 clearable** — 清空再選回同一天會連發兩次 `change`，第二次日期其實沒變卻仍重查。已加 `:clearable="false"`（`tradeDate` 本為必填）。
9. **[minor] 「2026-07-04 之後」** → 該日本身就在 9 筆 `buy=sell` 之列，已改為「起」。

**與 main 的併行衝突（實作期間發生）**：本任務進行中，main 被另一個 worktree 推進了 Task 252（系統時區統一台北），其中**同樣改了 `TransactionView.vue` 的 `openCreateDialog`**（`new Date().toISOString().slice(0,10)` → `todayLocal()`，理由是 `toISOString()` 為 UTC，台北 00:00–08:00 會取到昨天）。該行正是本任務要插入 `refreshExchangeRate()` 的位置，merge 時衝突。已在 feature 分支先 `merge origin/main` 解衝突（兩者都保留：`todayLocal()` ＋ `refreshExchangeRate()`），再併入 main，故本頁的預設交易日期沿用 Task 252 的本地日期慣例、不會在清晨查到前一天的匯率。

**部署（已執行）**：merge 進 main 後依「共用 stack 一律從 main 的 worktree 重建」規則，於 `/Users/steven/Project/asset-management-main` 執行 `build --no-cache bff frontend` ＋ `up -d --no-deps --force-recreate bff frontend`。驗證：
- `asset-bff` 健康檢查通過；jar 內 `TransactionBffController.class` 含常數 `/api/market-data/exchange-rate/on-date` 與 `/exchange-rate`（證明不是 stale jar）
- frontend chunk hash 已變動：`TransactionView-B4c1iOC6.js` → `TransactionView-CFooehwP.js`
- 運行中的 bundle `index-B-KUzJpt.js` 含 `bff/transaction/exchange-rate`
- `curl -s -o /dev/null -w "%{http_code}" http://localhost/` → `200`

**尚未執行**：8 條實機操作驗證需以 Google 帳號登入 UI，無法在無人值守下代跑，留給使用者確認（重點為第 6／7 條的編輯保護）。
