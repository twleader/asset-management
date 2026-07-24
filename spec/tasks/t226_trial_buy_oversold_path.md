# [t226] 新增「分批試單」動作，解決長線佳＋短線超賣無法產生買進建議的結構互斥

**對應 Requirements:** Requirement 43 修訂（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本任務新增與 `BUY_CANDIDATE` 區隔的 `TRIAL_BUY` 動作，讓「長線趨勢佳、短線超賣轉強」的標的能產生可執行的分批試單建議）
**前置任務:** 無（原設計依賴 t223 的「長期趨勢組分數」，但那需要把取數視窗拉到 750 根、進而需要 t225 的分割還原。實作改用**年線乖離率**判定長線結構，只需現有的 241 根視窗，故本任務可獨立完成。）
**實作狀態:** ✅ 已實作並上線（`TW_RULES_V5`，2026-07-19）
**Liquibase changeset:** 無（本任務不改資料庫結構）

## 背景

### 使用者的問題

「對於長線不錯的標的，當 KD 值都低於 20，且 K > D，是否應該建議買進？」

答案是：**現行系統在數學上不可能產生這個建議**，而且原因不是分數不夠，是硬閘門。

### 結構互斥的證明

買進動作由 `TradingRadarRuleEngine.actionFor()`（約 280–294 行）產生：

```java
private Action actionFor(StockInput input, int score) {
    boolean marketAllowsBuy = !equityMarketApplies(input)
            || (input.marketRegime() != MarketRegime.RISK_OFF && !input.marketStale());
    boolean buyGate = marketAllowsBuy
            && input.ma20Confirmation() == Confirmation.ABOVE
            && input.ma60Confirmation() == Confirmation.ABOVE;
    if (score >= 75 && buyGate) {
        return input.held() ? Action.ADD_CANDIDATE : Action.BUY_CANDIDATE;
    }
    ...
}
```

`buyGate` 要求 MA20 與 MA60 的兩日確認**皆為 `ABOVE`**。而 `evaluateCounterTrend()`（約 222–278 行）判定超賣的前提是 MA20 與 MA60 兩日確認**皆為 `BELOW`**。兩組條件對同一組 enum 變數互為否定。

實測十年全樣本（`stock_price_history` 台股全部標的，以真實 KD9 遞迴 `k = k×2/3 + rsv/3`、`d = d×2/3 + k/3` 與兩日確認重算，排除 `close_price <= 0`）：

```
總交易日              103,040
K<20 且 D<20            6,552   (6.36%)
買進閘門技術條件成立    41,808   (40.57%)
兩者共存                     0
其中低檔黃金交叉且閘門成立   0    ← 使用者問的情境
```

**十年、70 檔標的，一次都沒發生過。**

因此 V4 中那條「`K>D` 且 K、D 皆 <20 再加 3 分」的 bonus（`TradingRadarRuleEngine` 約 177–180 行）**十年來從未促成任何一次買進建議**，是死程式碼。

### 既有逆勢軌為什麼不夠

`evaluateCounterTrend()` 已經在偵測這個情境並輸出 `OVERSOLD_WATCH`／`TRIAL_CANDIDATE`，但它是**純顯示的第二軌，不影響主 `action`**，且三年僅觸發 26 次 `TRIAL_CANDIDATE`。使用者看到的主建議仍可能是「減碼候選」。

### 為什麼不能直接放寬 buyGate

放寬雙 `ABOVE` 條件會讓**所有處於下跌趨勢的標的**一併變成可買（現行 41,808 次成立以外的全部情境），風險失控。正確做法是新增一條**平行的獨立路徑**，用不同的動作標籤承載不同的風險結構。

## 要做什麼

- [x] **226.1 新增 `Action.TRIAL_BUY`（分批試單候選）**

  於 `TradingRadarRuleEngine` 的 `Action` enum 新增值，中文標籤「分批試單」（標籤映射位於 `TradingRadarService.actionLabel()`，約 439–452 行）。

  **不得沿用或改寫既有的 `BUY_CANDIDATE`／`ADD_CANDIDATE`**——兩者的風險結構不同（順勢 vs 接刀），必須是獨立的 enum 值，才能在 UI、通知訂閱、以及日後回測中分開統計。

- [x] **226.2 觸發條件（六項全部滿足才輸出）**

  | # | 條件 | 資料來源 |
  |---|---|---|
  | 1 | **年線乖離 `(price − MA240) / MA240 ≥ 5%`** | 現有 241 根視窗即可計算。**不可只用 `price > MA240`**——實測使用者投組中該條件 100% 成立（乖離 +17%～+47%），完全沒有篩選力；5% 門檻能排除貼著年線者（如 00882 +1.3%）與 KD 近乎雜訊的低波動債券 ETF（+1.3%～+5.1%） |
  | 1b | MA240 兩日確認為 `ABOVE` | 排除剛上穿年線的假突破 |
  | 2 | `K < 20` 且 `D < 20`（深度超賣） | 還原權息序列的 KD9 |
  | 3 | `K > D`（已轉強） | 同上 |
  | 4 | 前一期 `K ≤ D` | 由排除本期後的同一還原權息 KD9 序列計算 |
  | 5 | 最近一根**完成日 K** 的漲跌幅 `≥ 0` | `completedChangePercent`（既有欄位） |
  | 6 | `EQUITY` 須大盤非 `RISK_OFF` 且非 stale | 既有 `marketRegime`／`marketStale`；`BOND` 沿用既有豁免 |

  **條件 4 不可省略**：少了它，「持續強勢的低檔股」也會被當成交叉。前期 K/D 不足以計算時**不得假裝交叉**，一律不觸發。

  **條件 5 必須用完成日 K**，不可用盤中漲跌幅——盤中在 0% 附近的零交叉會讓狀態逐 tick 翻轉（這是既有逆勢軌已經修過的問題，不要重蹈）。

  **條件 1 不可退化為單純的 `price > MA240`**。實測使用者投組中該條件 100% 成立（年線乖離 +17%～+47%），完全沒有篩選力。5% 乖離門檻的實際效果有二：排除貼著年線、長線結構不明確者（如 00882 +1.3%）；排除低波動的債券 ETF（+1.3%～+5.1%）——後者的 9 日高低帶寬度僅約 1%，KD 在其上近乎雜訊，本就不該用「跌深反彈」的邏輯操作。

- [x] **226.3 不得放寬既有買進閘門**

  `BUY_CANDIDATE`／`ADD_CANDIDATE` 的產生條件**完全不變**：`score >= 門檻` 且 MA20／MA60 雙 `ABOVE` 且大盤非 `RISK_OFF` 非 stale。

  `TRIAL_BUY` 是**平行**路徑，不是 buyGate 的例外分支。實作上須確保兩條路徑互斥：同一次評估不得同時滿足兩者（條件 2 要求 `avg(K,D) < 25`、而買進閘門要求站上均線，實測十年零共存，但仍須以斷言或測試保障）。

- [x] **226.4 與既有逆勢抄底軌的關係**

  既有 `evaluateCounterTrend()` 的 `OVERSOLD_WATCH`／`TRIAL_CANDIDATE` **降為診斷欄位**，不再是與主建議並列的第二軌。

  須明訂並實作：**`TRIAL_BUY` 觸發時，主 `action` 即為 `TRIAL_BUY`**，不得再出現「主建議＝減碼候選、逆勢狀態＝逆勢試單候選」這種同一列互相矛盾的組合。

  **注意：既有單元測試已把該矛盾組合寫死為預期行為**（`TradingRadarRuleEngineTest` 中的 counterTrend 相關測試）。本任務須一併修正那些測試的預期值，不得為了讓舊測試通過而保留矛盾。

- [x] **226.5 風險揭露與部位語意**

  `TRIAL_BUY` 的本質是接刀，前端**不得**與 `BUY_CANDIDATE` 共用標籤樣式或並列排序。須固定顯示：

  > 僅適合小額分批、非全額進場；長線判斷失準時虧損可能持續擴大。

  前端於動作標籤加 tooltip 說明觸發條件與接刀風險（已實作）。

  前端要改的檔案：`frontend/src/views/TradingRadarView.vue`。**BFF 不需改動**——`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是純 Spring Cloud Gateway rewrite passthrough，沒有 DTO 定義，新增的 JSON 欄位會自動穿透。

- [x] **226.6 通知訂閱須納入新動作**

  交易雷達的 Email 通知（`trading_radar_notification_state`）以 action 值為訂閱單位。新增 `TRIAL_BUY` 後，通知設定對話框須列出此選項。

  規則版本變更會使既有通知基準失效，故上線後首輪評估一律**只建基準不寄信**（沿用既有語意）。

- [x] **226.7 移除已證實的死程式碼**

  V4 的「`K>D` 且 K、D 皆 <20 再加 3 分」bonus 在 t223 的 KD 拆分後已無對應（拆分後由 KD 位置子因子承擔超賣語意）。確認 t223 已移除該加成後，本任務不得再引入等價邏輯。

- [x] **226.8 測試**

  測試與實作同屬本任務，不得延後。至少覆蓋：

  - **六項條件各自的邊界**：每項單獨不滿足時皆不得輸出 `TRIAL_BUY`（六個 case）。
  - **條件 4 的必要性**：前期 `K > D`（持續強勢而非交叉）時不觸發。
  - **前期 K/D 不足**：資料不足以判定交叉時不觸發，不得假裝。
  - **條件 5 用完成日 K**：盤中漲跌幅為負但完成日為正時仍可觸發；反之不可。
  - **與 buyGate 互斥**：構造同時滿足兩者的輸入（若可能），斷言不會同時輸出兩個動作。
  - **矛盾組合已消除**：`TRIAL_BUY` 觸發時 `action` 不得為 `REDUCE_CANDIDATE`／`EXIT_CANDIDATE`。
  - **大盤閘門**：`EQUITY` 在 `RISK_OFF` 或 stale 時不觸發；`BOND` 不受此限。
  - **迴歸**：既有 `BUY_CANDIDATE`／`ADD_CANDIDATE` 的產生條件未被放寬——分數再高但 MA20 確認為 `MIXED` 時仍不得產生買進候選。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080，沒有 host port。BFF 的 `/api/bff/**` 需登入（回 401），免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header。**本專案沒有 root pom**，三個模組各自獨立，測試要用 `-f <module>/pom.xml`。business 與 external **未啟用 actuator**（`/actuator/health` 分別回 500／404），只有 bff 有。

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest=TradingRadarRuleEngineTest \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 前端建置
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build && cd ..

# 3. 重建映像並重建容器（JVM 必須 --no-cache，否則可能產出不含本次變更的 stale jar）
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend

# 4. 重建 business 會換 IP，BFF 握舊 IP 回 500 且不自癒（Docker DNS TTL 600s）
docker compose -p asset-management restart bff

# 5. 健康檢查（只有 bff 有 actuator）
curl -s http://localhost:8080/actuator/health

# 6. 確認新動作已進入 API 契約，且既有標的的動作沒有異常翻轉
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(s.get('stockCode'), s.get('score'), s.get('action'), s.get('counterTrendState')) for s in d.get('stocks',[])]"

# 7. 矛盾組合已消除：不得出現 action 為減碼／出場候選、同時 counterTrendState 為 TRIAL_CANDIDATE
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar \
  | python3 -c "
import sys,json
d=json.load(sys.stdin)
bad=[s for s in d.get('stocks',[]) if s.get('action') in ('REDUCE_CANDIDATE','EXIT_CANDIDATE') and s.get('counterTrendState')=='TRIAL_CANDIDATE']
print('矛盾組合:', len(bad), bad if bad else '（無，符合預期）')"
```

驗收判準：步驟 1 全綠；步驟 7 矛盾組合為 0。

**注意**：`TRIAL_BUY` 是稀有事件（既有逆勢軌三年僅觸發 26 次），端到端驗證**很可能看不到任何一檔觸發**——這是預期的，不代表實作失敗。真正的驗收是步驟 1 的單元測試（226.8 列出的邊界案例）。請勿為了「在畫面上看到 TRIAL_BUY」而放寬條件。

## 完成報告

已實作並上線（`TW_RULES_V5`，2026-07-19；並延續於後續 `TW_RULES_V7`）。

**實際改動的檔案：**
- `backend/.../service/TradingRadarRuleEngine.java`：`Action` enum 新增 `TRIAL_BUY`；新增 `qualifiesForTrialBuy()`（226.2 六項條件全滿足才輸出）與年線乖離門檻具名常數 `TRIAL_BUY_MIN_ANNUAL_PREMIUM = 0.05`（226.2 條件 1，取代無篩選力的 `price > MA240`）；`actionFor()` 併入平行的 `TRIAL_BUY` 路徑，未放寬既有 `buyGate` 雙 `ABOVE` 硬閘門（226.3）。
- `backend/.../service/TradingRadarService.java`：`actionLabel()` 對應中文標籤「分批試單」。
- `frontend/src/views/TradingRadarView.vue`：`TRIAL_BUY` 動作標籤獨立樣式與 tooltip（`trialBuyHint`，接刀風險與觸發條件揭露，226.5）。
- 通知訂閱納入 `TRIAL_BUY`（226.6）；既有 `evaluateCounterTrend()` 的 `OVERSOLD_WATCH`／`TRIAL_CANDIDATE` 降為診斷欄位、消除同列矛盾組合（226.4，一併修正 `TradingRadarRuleEngineTest` 中把矛盾寫死為預期的舊測試）。
- 226.7：確認 t223 KD 拆分後 V4 的低檔加成死程式碼已無等價邏輯殘留。

**與原計畫的偏差：** 原設計依賴 t223 的「長期趨勢組分數」（需 750 視窗＋t225 分割還原）；實作改以**年線乖離率 ≥ 5%**（現有 241 視窗即可算）判定長線結構，故本任務得以獨立於 t225／t223 長期組先行上線。此偏差已記於本檔前置任務說明（第 4 行）。

**驗證：** 226.8 單元測試（六項條件邊界、條件 4 交叉必要性、完成日 K、與 buyGate 互斥、矛盾組合消除、大盤閘門、買進閘門未放寬迴歸）全綠；端到端因 `TRIAL_BUY` 為稀有事件（三年僅約 26 次）未必當場可見，屬預期。
