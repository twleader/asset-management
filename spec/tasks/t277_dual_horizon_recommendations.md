# [t277] 交易雷達的短線與中長線雙軌建議

> **⛔ 本任務已由 t291 取代，不得依本檔實作。** t291 將持有期收斂為短期約 5 個交易日、中期約 20／60／120 個交易日（最長約 6 個月），並明訂純獲利評分、不以個人資金安排仲裁，也不擴張既有通知資料庫；本檔只保留歷史規劃。

**對應 Requirements:** Requirement 60（交易雷達的短線與中長線雙軌建議——同一檔標的同時輸出短線軌與中長線軌各自的 score 與 action，兩軌衝突時並列呈現不自動仲裁）
**前置任務:** t273（回測框架——短線軌的因子必須以其 `+5` 日 horizon 量測結果挑選）、t274（波動度正規化——短線軌的極端態門檻依賴它）、t276（既有技術指標與量能——短線軌的候選因子由它提供）

> **t275（債券利率因子）刻意不列為前置**：它與短線軌無因子依賴，強加前置只會不必要地串行化。但 t275 會**重配全部權重**，故若它在本任務之後落地，**中長線軌的回歸基準（驗證 (b)）須於 t275 內重新建立**——這一點必須寫進 t275 的完成報告，不得讓兩份任務互相假設對方先做。
**Liquibase changeset:** `v1.86.0-radar-short-horizon-notification.sql`（**預留版號**；內容不只加欄位，見 277.7.1 的 `ck_trn_state_type` 決策）

> **⚠ 複驗的判準是「該版號檔是否已存在」，不是「它是不是最大值」。** 本路線圖同時預留 `v1.85.0`（t275）、`v1.86.0`（本任務）、`v1.87.0`（t266）。t266 最可能先落地，故 `ls … | sort -V | tail` 很可能回 `v1.87.0`——**那不代表 `v1.86.0` 被佔用**。實作前直接檢查 `v1.86.0-*.sql` 是否存在。

- [ ] **changeset 必須註冊進 `db.changelog-master.yaml` 的檔尾**：該 master **全部是顯式 `- include:`**（實測 `grep -c includeAll` ＝ **0**），最後一筆現為 `v1.84.0-asset-transaction-fee-tax.sql`。**漏註冊時 Liquibase 不報錯、服務照常啟動**，直到第一次寫入才炸。寫法：

  ```yaml
  - include:
      file: db/changelog/changes/v1.86.0-radar-short-horizon-notification.sql
      relativeToChangelogFile: false
  ```

  順序須排在現有最後一筆之後。

## 背景

### 使用者需求

使用者原話：「**我希望今日交易雷達可建議短線，也可建議中長線，5 個交易日後可獲利 5%，這也是不錯的建議。**」

現行 `TW_RULES_V9` 只輸出單一持有期的建議，且刻意偏向中長線——`spec/requirements.md` 的 Requirement 43 修訂（`TW_RULES_V9`）記載 V9 的尺度佔比為「短期 0.23／中期 0.29／長期 0.27／環境 0.15／估值 0.06」，中期（數周至數月）為最大組，對應「獲利期間數周至兩年」。該串數字可由 `TradingRadarRuleEngine.java` 的權重常數加總複驗（`0.06+0.05+0.05+0.07 = 0.23`、`0.10+0.07+0.12 = 0.29`、`0.11+0.09+0.07 = 0.27`、`0.06+0.04+0.05 = 0.15`、`0.06`，合計 `1.00`）。

> **不要去 `spec/design.md` 找這串數字。** 該檔明文「14 個因子的完整權重表以 Requirement 43 修訂（`TW_RULES_V9`）Acceptance Criteria 中的權重表為唯一契約，**本文件刻意不複寫**」——設計文件不複寫權重數字是該檔的既有慣例，避免兩處數字漂移。

### 本任務推翻一個既有的刻意決定

`TechnicalIndicatorService.FullIndicators.weeklyMa` 的 Javadoc 現載：

> 週線（MA5，台股慣例的 5 個交易日；Task 265）。**刻意不進入 `TradingRadarRuleEngine.StockInput`、不參與評分與買進閘門**——5 個交易日的尺度與「獲利期間數周至兩年」的需求直接衝突，也與 Task 264 降低短線權重的方向相反。它只供畫面顯示與查證。

**該決定在「雷達只輸出單一持有期建議」這個前提下是正確的**——在單一分數裡摻入 5 日尺度的因子，等於用短線雜訊污染中長線判斷。

本任務改變的正是**那個前提**：改為雙軌輸出，短線軌有自己的因子集合與權重，**不與中長線軌爭奪同一組權重**。故 MA5 等短線指標得以進入短線軌，而中長線軌維持 V9 以來「中期為最大權重組」的方向不變。

### 這是需求方向，不是回測結論

若 t273 的量測顯示短線軌的訊號品質不佳，處理方式是**如實揭露其量測數字**，而非取消該軌。使用者已明確要求此功能。

### 使用者已理解的界線

使用者原話：「**我知道我一直說「預測」，但規則式系統做不到預測隔日漲跌……請不要為了滿足我的字面要求而在文案裡寫預測性語句。我真正要的是：在極端位置上，讓建議的方向對我有利。**」

短線軌的價值在於「在極端位置上讓建議方向對使用者有利」，**不在於預測 5 日後的漲跌**。文案紀律見 277.6。

## 要做什麼

### 277.1 雙軌各自獨立評分，不得由單一分數推導

- [ ] 277.1 每檔標的同時輸出「短線軌」與「中長線軌」兩組 `score` 與 `action`，**各自有獨立的因子集合與權重**。

  > **不得以「同一個分數套兩組門檻」實作。** 那只是把一個數字切兩刀，無法表達「短線超買但長期結構完好」這種兩軌相反的狀態——而能表達這種狀態**正是本任務的目的**。此為本任務最重要的架構約束，並由驗證段 (a) 的測試守門。

- [ ] 277.1.1 引擎的 `StockResult` 擴充為含兩軌結果（或新增一個 `shortHorizon` 巢狀 record）。既有的 `score`／`action`／`timingState`／`kdHeat`／`counterTrend` 欄位**語意明確定義為「中長線軌」並保留原名**，避免既有消費端靜默改變語意。

- [ ] 277.1.2 兩軌的權重各自合計 `1.00`，各以獨立的 `WEIGHT_SUM` 常數供測試斷言釘住（不得靠人工加總）。

### 277.2 中長線軌行為不得改變

- [ ] 277.2 中長線軌即現行規則（含 t274／t275／t276 的修訂）。**除該三者帶來的改動外，中長線軌的分數與動作不得因為新增短線軌而改變**——須有回歸測試以固定輸入斷言此點（見驗證段 (b)）。

### 277.3 短線軌的因子須以量測結果挑選

- [ ] 277.3 短線軌的因子**必須以 t273 的 `+5` 日 horizon 量測結果挑選，不得憑直覺**。候選清單：

  | 候選 | 資料來源 |
  |---|---|
  | 價格與 `MA5` 的關係 | `FullIndicators.weeklyMa`（已存在） |
  | `RSI5` | t276 擴充的輸出 |
  | `K`／`D`／`J9` | `K`／`D` 已存在；`J9` 由 t276 擴充 |
  | `W%R9` | t276 擴充 |
  | `MACD` 的 `OSC` 轉向 | t276 擴充 |
  | `volumeRatio` | t276 新增 |
  | 單日漲跌幅 | `StockInput.changePercent` 已存在 |

- [ ] 277.3.1 挑選依據（各候選在 `+5` 日 horizon 相對基準的報酬分布與下檔風險）須列於完成報告。**未納入者亦須列出其量測數字與理由。**

- [ ] 277.3.2 **短線軌同樣受 t274 的波動度正規化保護**：短線軌若使用任何「固定百分比」形式的門檻，一律改用 `σ` 倍數。理由與 t274 相同——固定百分比的 `|bias|>12%` 在 00697B 上十年觸發率 0.00%、在 2327 上 40.89%，短線軌不會自動免疫這個問題。

- [ ] 277.3.3 **窄幅 KD 失效（`KD_BAND_MIN_PERCENT = 2.0`）在短線軌同樣適用**。短線軌大量依賴 KD 系指標，而 00719B 實測近 60 個交易日的 9 日高低帶平均寬度僅 `1.011%`（其 `K=90.76` 實質只代表「比 9 日低點高 0.37 元」）。不套用此防護會讓短線軌對債券 ETF **大量誤發訊號**——那比現況更糟。

### 277.4 「不追高殺低」在兩軌各自成立

- [ ] 277.4 短線軌須有其對應的極端態動作覆寫，門檻依 t274 的波動度正規化與 t273 的量測決定。

  > **對稱性論證**（Requirement 43 修訂／`TW_RULES_V9` 建立，同樣適用於短線軌）：買方既有「超買否決買進」，賣方就必須有「超賣否決賣出」，否則系統只在單一方向上保守，等於在低點建議殺低。

- [ ] 277.4.1 **極端超買的覆寫必須要求「轉弱確認」，不得只憑超買就賣。** 中長線軌的既有實作為 `EXTREME_OVERBOUGHT && kdDeadCross()`，其 Javadoc 記載理由：「只要超買就出場會在主升段初期砍掉部位」。短線軌須有等價的轉弱確認條件。

- [ ] 277.4.2 **極端超賣的保護必須有「長期結構破壞」安全閥。** 中長線軌的既有實作為 `longTermBroken()`（`ma240Confirmation == BELOW && week52Position <= 0.10`），其 Javadoc 記載理由：「沒有它，一檔持續崩壞的標的會因為 KD 永遠釘在低檔而**永遠拿不到出場訊號**」。短線軌須有等價的安全閥。

### 277.5 兩軌衝突一律並列，不自動仲裁

- [ ] 277.5 短線軌與中長線軌給出相反建議（如短線減碼、中長線加碼）時，兩者**一律並列顯示並揭露其分歧**。

  > **不得由系統自行合成單一建議、也不得隱藏其中一軌。** 兩軌衡量的是不同持有期的問題，「哪一軌該聽」取決於使用者當下的資金安排，系統沒有該資訊。

- [ ] 277.5.1 前端須在**收合列**即可看出兩軌是否分歧（不需展開）。沿用 `TradingRadarView.vue` 既有的收合列標記慣例（`kdHeat`／`timingState` 已是同一模式）。

- [ ] 277.5.2 **兩軌的持有期須明示於畫面**：短線軌約 5 個交易日、中長線軌數周至兩年。使用者不得需要猜測某個建議是哪個尺度的。

### 277.6 文案紀律

- [ ] 277.6 兩軌的 `reasons`／`risks` 文案一律描述「**當前位置與狀態**」，**不得**出現「5 日內可獲利」「將上漲」「機率為」「能降低風險」等預測性語句。

  此約束沿用 `KD_OVERHEAT_K` Javadoc 已建立的紀律（「**不得於任何文案宣稱本門檻能降低回檔風險**」）。既有實作的正確範例可參照：`"本訊號描述的是當前位置與轉弱狀態，不預測隔日漲跌；分批減碼優於一次出清。"`

- [ ] 277.6.1 **不得把門檻數字寫進句子**。既有 Javadoc 記載理由：條件為嚴格大於，而 `K=85.02` 顯示為 `85.0`，「已高於 85」會變成自我否定的句子。

### 277.7 三個既有消費端都要處理

- [ ] 277.7.0 **⚠ 訂閱面的 CHECK 約束必須一併決策（只加欄位會靜默改變既有訂閱語意）**：是否寄信不只看 `last_action`，還取決於使用者訂閱了哪些狀態——`TradingRadarNotificationService` 讀 `stateRepo.findStateCodes(id, TYPE_ACTION)`，而 DB 實測 `trading_radar_notification_state` 帶有

  ```
  ck_trn_state_type CHECK (state_type IN ('ACTION','COUNTER_TREND'))
  ```

  兩條路徑擇一並寫進 changeset 說明：

  | 方案 | 後果 | 須一併做的事 |
  |---|---|---|
  | **(A) 沿用 `TYPE_ACTION`** | 既有「只訂閱中長線減碼」的使用者會**靜默擴及短線軌**，開始收到短線軌的同名動作通知 | 須**明文記載**此語意變更；驗證段須加一條斷言（既有只訂閱中長線動作的設定，在短線軌同名動作變化時的**預期行為**） |
  | **(B) 新增獨立的 `state_type`（如 `SHORT_ACTION`）** | 語意乾淨，既有訂閱不受影響 | `v1.86.0` 內須一併 `DROP/ADD CONSTRAINT ck_trn_state_type`、新增對應常數、**補設定 API 與 UI**（否則使用者無從訂閱短線軌） |

  > **驗證 (g)（「僅短線軌變化須觸發通知」）在方案 (A) 下會通過，抓不到這個語意漂移**——故本項必須獨立決策並各自補測試，不能靠 (g) 帶過。

- [ ] 277.7.1 **通知（Requirement 44）**：`TradingRadarNotificationService` 與 `TradingRadarNotificationTransition` 現行只比對 `last_action` 與 `last_counter_trend_state` 兩欄（**DB 實測欄位**：`trading_radar_notification_setting` 含 `id`／`owner_user_id`／`stock_code`／`market`／`active`／`initialized`／`last_action`／`last_counter_trend_state`／`created_at`／`updated_at`／`rule_version`）。

  須新增短線軌的追蹤欄位（changeset `v1.86.0`，`ADD COLUMN IF NOT EXISTS` 冪等），使「僅短線軌變化」也能觸發通知。**不得只追蹤中長線軌而讓短線軌的變化靜默**。

  > 既有列的新欄位一律填 `NULL`，與任何動作值皆不相等，故首輪自動重建基準——這正是所需行為，不需另外 `UPDATE`。
  >
  > **changeset 的 `--comment` 一旦寫定就不要再改**：Liquibase 的 checksum **包含註解**，改註解會造成 `ValidationFailed` 而讓 business-services 進入 crash loop。編號避讓用的 `sed` 必須排除 `db/changelog/`。

- [ ] 277.7.2 **快照與匯出（Requirement 48）**：`TradingRadarSnapshotStore`（Redis 落地）與 `TradingRadarExportService`（Excel 區間匯出）須含兩軌欄位。既有欄位維持「中長線軌」語意不變，避免歷史快照的語意漂移。

- [ ] 277.7.3 **`TradingRadarRefreshService`**（Task 249 的手動重新整理路徑）須一併回傳兩軌結果。

### 277.8 API 與 `RULE_VERSION`

- [ ] 277.8.1 `/api/trading-radar` 與 `/api/bff/trading-radar` 新增雙軌欄位。**既有的單軌欄位保留原名、語意明確定義為「中長線軌」**。前端只呼叫 BFF（本專案「一頁一 BFF」規範）。

  > **⚠ 交易雷達頁的 BFF 是 gateway passthrough，不是聚合層**：`TradingRadarBffRoutes` 為純 `rewritePath`，其 class Javadoc 明文「不重算技術指標」；`TradingRadarBffController` 只處理 `/export/browse` 兩支。故 `/api/bff/trading-radar` 的雙軌欄位**隨 business 端 DTO 自動帶過**，**不得**為此在 BFF 新增第二份 payload 或排序邏輯（那會造出第二份 `TradingRadarDto` 而漂移）。**排序與計算維持在 `TradingRadarService`。**

- [ ] 277.8.2 `RULE_VERSION` 升版一級。**同步點共五處**，以 `grep -ran "<現行版本字串>"` 取得（`-a` 不可省略：本專案有 `.java` 檔被 `file(1)` 誤判為 data，普通 `grep -r` 會整檔跳過）：

  | # | 檔案 | 位置 |
  |---|---|---|
  | 1 | `backend/src/main/java/com/steven/assets/service/TradingRadarRuleEngine.java` | `RULE_VERSION` 常數本體 |
  | 2 | `frontend/src/views/TradingRadarView.vue` | `radar` ref 的初始值 |
  | 3 | `frontend/src/views/TradingRadarView.vue` | 顯示 fallback |
  | 4 | `backend/src/main/java/com/steven/assets/dto/TradingRadarDto.java` | class Javadoc |
  | 5 | `backend/src/test/java/com/steven/assets/service/TradingRadarRuleEngineTest.java` | 版本斷言，**連同其測試方法名一併改** |

  > **⚠ grep 的命中數多於同步點數。** 以 `TW_RULES_V9` 實測為例，`grep -ran` 回 **7 個命中、分布在 5 個檔案**，但只有上表 5 處該改。另外兩個是 `TradingRadarRuleEngine.java` 權重表上方的說明註解（歷史敘述，改不改皆可），以及 **`backend/src/main/resources/db/changelog/changes/v1.83.0-radar-notification-rule-version.sql` 的 `--comment`——絕對不可改**：Liquibase 的 checksum **包含註解**，改了會 `ValidationFailed`、讓 business-services 進入 crash loop。**做批次取代的 `sed` 必須排除 `db/changelog/`。**

  > **實作前必須先 `grep -ran "RULE_VERSION"` 讀取當下的實際值**：t274／t275／t276 各會升版一級，本任務的起始版本取決於落地順序，不得依本檔假設。

- [ ] 277.8.3 本次為**結構性改變**（新增一整軌輸出），須明白揭露 V(n) 與 V(n−1) 的動作不可直接比較。

## 驗證

### 單元測試

- [ ] **(a) 本任務的核心行為（最重要的一條）**：構造一組「短線超買、長期結構完好」的輸入（例如 `K`／`D` 高檔、`MA5` 之上、但 `MA60`／`MA240` 皆 `ABOVE` 且 `week52Position` 高），斷言**兩軌動作確實不同**。**若實作退化為「單一分數切兩刀」，此測試必失敗。**
- [ ] **(b) 中長線軌回歸**：以固定輸入斷言中長線軌的 `score` 與 `action` 與新增短線軌之前完全相同。
- [ ] **(c) 兩軌衝突時 API 同時回傳兩者且不合成**（斷言回應中兩個 action 欄位皆存在且值不同）。
- [ ] **(d) 短線軌的極端態覆寫在兩個方向皆成立**：極端超買＋轉弱確認 → 減碼方向；極端超賣且長期結構未破壞 → 阻擋出場；極端超賣但長期結構已破壞 → 照常出場。
- [ ] **(e) 短線軌的窄幅 KD 防護**：`kdBandWidthPercent < 2.0` 時短線軌不以 KD 判定（以 00719B 的實測值 `1.011%` 作為構造輸入的依據）。
- [ ] **(f) 兩軌的 `WEIGHT_SUM` 各自為 `1.00`**（由常數加總斷言）。
- [ ] **(g) 通知鏈能偵測「僅短線軌變化」**：中長線軌動作不變、短線軌動作改變時，通知須被觸發。
- [ ] **(h) 文案紀律**：斷言兩軌的 `reasons`／`risks` 不含「預測」「將」「機率」等字串（以字串比對守門）。
- [ ] **(i) `RULE_VERSION` 已升版**，測試方法名已改。

### 建置與部署

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
```

```bash
cp /Users/steven/Project/asset-management/.env .
```

```bash
docker compose -p asset-management build --no-cache business-services bff frontend
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
```

```bash
docker compose -p asset-management restart bff
```

### 端點驗收

```bash
curl -s http://localhost:8080/actuator/health
```

> **這行檢查的是 bff，不是 business。** `docker ps` 顯示 `asset-bff` 對外 `0.0.0.0:8080->8080`，而 `asset-business-services` 只有內部 `8080/tcp`。實測**只有 bff 有 actuator**——在 business 容器內打 `/actuator/health` 回 **500** `No static resource actuator/health.`。business 是否活著改以下面那條帶 `X-User-*` header 的實際呼叫判斷。

免 OAuth 的端到端驗證（在 business 容器內以 header 模擬租戶）：

```bash
docker exec asset-business-services curl -s "http://localhost:8080/api/trading-radar" -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" | head -c 3000
```

- [ ] 回應含兩軌欄位，且既有單軌欄位仍存在（語意為中長線軌）。

### 通知基準重建驗收

```bash
docker exec asset-postgres psql -U assets -d assets -c "SELECT rule_version, count(*) FROM trading_radar_notification_setting GROUP BY rule_version;"
```

- [ ] 升版後首輪評估只建基準、未寄信（須實查 log 確認無該輪寄信紀錄）。**這一項在本任務尤其重要**：新增一整軌會讓大量標的的短線軌動作與 `NULL` 不相等，基準未重建會造成對每筆訂閱狂發假通知。

### 畫面驗收

- [ ] 開啟 `http://localhost/trading-radar`，確認：(1) 每檔標的可同時看到兩軌建議；(2) 兩軌的持有期有明示（短線約 5 個交易日／中長線數周至兩年）；(3) 兩軌分歧的標的在**收合列**即可辨識；(4) 文案中沒有預測性語句。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。**必須包含**：(1) 短線軌各候選因子的 t273 `+5` 日 horizon 量測數字與納入／不納入的決定；(2) 短線軌極端態門檻所依據的量測數字；(3) 中長線軌回歸測試的比對結果；(4) 若量測顯示短線軌訊號品質不佳，如實列出數字——**不得為了讓功能看起來有效而隱瞞**。）
