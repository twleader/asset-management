# [t226] 新增「分批試單」動作，解決長線佳＋短線超賣無法產生買進建議的結構互斥

**對應 Requirements:** Requirement 43 修訂（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本任務新增與 `BUY_CANDIDATE` 區隔的 `TRIAL_BUY` 動作，讓「長線趨勢佳、短線超賣轉強」的標的能產生可執行的分批試單建議）
**前置任務:** t223（分數飽和修正、長期趨勢組與動作門檻分層——本任務的觸發條件直接讀取 t223 定義的「長期趨勢組分數」與「KD 位置／KD 動能」兩個子因子，t223 未完成前無此輸入）
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

- [ ] **226.1 新增 `Action.TRIAL_BUY`（分批試單候選）**

  於 `TradingRadarRuleEngine` 的 `Action` enum 新增值，中文標籤「分批試單」（標籤映射位於 `TradingRadarService.actionLabel()`，約 439–452 行）。

  **不得沿用或改寫既有的 `BUY_CANDIDATE`／`ADD_CANDIDATE`**——兩者的風險結構不同（順勢 vs 接刀），必須是獨立的 enum 值，才能在 UI、通知訂閱、以及日後回測中分開統計。

- [ ] **226.2 觸發條件（六項全部滿足才輸出）**

  | # | 條件 | 資料來源 |
  |---|---|---|
  | 1 | 長期趨勢組分數 `> +0.5` | t223 定義的長期趨勢組（MA240 位置／確認、年線斜率、52 週位置、3 年報酬的加權平均） |
  | 2 | KD 位置子因子 `> +0.5`（等價於 `avg(K,D) < 25`） | t223 定義的 `clamp(−(avg(K,D) − 50)/50, −1, +1)` |
  | 3 | KD 動能子因子 `> 0`（即 `K > D`） | t223 定義的 `clamp((K − D)/10, −1, +1)` |
  | 4 | 前一期 `K ≤ D` | 由排除本期後的同一還原權息 KD9 序列計算 |
  | 5 | 最近一根**完成日 K** 的漲跌幅 `≥ 0` | `completedChangePercent`（既有欄位） |
  | 6 | `EQUITY` 須大盤非 `RISK_OFF` 且非 stale | 既有 `marketRegime`／`marketStale`；`BOND` 沿用既有豁免 |

  **條件 4 不可省略**：少了它，「持續強勢的低檔股」也會被當成交叉。前期 K/D 不足以計算時**不得假裝交叉**，一律不觸發。

  **條件 5 必須用完成日 K**，不可用盤中漲跌幅——盤中在 0% 附近的零交叉會讓狀態逐 tick 翻轉（這是既有逆勢軌已經修過的問題，不要重蹈）。

  **條件 1 的長期趨勢組分數必須來自 t223**，不得自行用「price > MA240」代替。實測現行的 `price > MA240 且 MA240 確認 ABOVE` 在使用者的投組上 **100% 成立**，等於沒有篩選。

- [ ] **226.3 不得放寬既有買進閘門**

  `BUY_CANDIDATE`／`ADD_CANDIDATE` 的產生條件**完全不變**：`score >= 門檻` 且 MA20／MA60 雙 `ABOVE` 且大盤非 `RISK_OFF` 非 stale。

  `TRIAL_BUY` 是**平行**路徑，不是 buyGate 的例外分支。實作上須確保兩條路徑互斥：同一次評估不得同時滿足兩者（條件 2 要求 `avg(K,D) < 25`、而買進閘門要求站上均線，實測十年零共存，但仍須以斷言或測試保障）。

- [ ] **226.4 與既有逆勢抄底軌的關係**

  既有 `evaluateCounterTrend()` 的 `OVERSOLD_WATCH`／`TRIAL_CANDIDATE` **降為診斷欄位**，不再是與主建議並列的第二軌。

  須明訂並實作：**`TRIAL_BUY` 觸發時，主 `action` 即為 `TRIAL_BUY`**，不得再出現「主建議＝減碼候選、逆勢狀態＝逆勢試單候選」這種同一列互相矛盾的組合。

  **注意：既有單元測試已把該矛盾組合寫死為預期行為**（`TradingRadarRuleEngineTest` 中的 counterTrend 相關測試）。本任務須一併修正那些測試的預期值，不得為了讓舊測試通過而保留矛盾。

- [ ] **226.5 風險揭露與部位語意**

  `TRIAL_BUY` 的本質是接刀，前端**不得**與 `BUY_CANDIDATE` 共用標籤樣式或並列排序。須固定顯示：

  > 僅適合小額分批、非全額進場；長線判斷失準時虧損可能持續擴大。

  API 須回傳觸發此動作的**六項條件各自的實際值**（長期趨勢組分數、KD 位置、KD 動能、前期 K/D、完成日漲跌幅、大盤狀態），使使用者能自行判斷而非只看一個標籤。

  前端要改的檔案：`frontend/src/views/TradingRadarView.vue`。**BFF 不需改動**——`bff/src/main/java/com/steven/assets/bff/tradingradar/TradingRadarBffRoutes.java` 是純 Spring Cloud Gateway rewrite passthrough，沒有 DTO 定義，新增的 JSON 欄位會自動穿透。

- [ ] **226.6 通知訂閱須納入新動作**

  交易雷達的 Email 通知（`trading_radar_notification_state`）以 action 值為訂閱單位。新增 `TRIAL_BUY` 後，通知設定對話框須列出此選項。

  規則版本變更會使既有通知基準失效，故上線後首輪評估一律**只建基準不寄信**（沿用既有語意）。

- [ ] **226.7 移除已證實的死程式碼**

  V4 的「`K>D` 且 K、D 皆 <20 再加 3 分」bonus 在 t223 的 KD 拆分後已無對應（拆分後由 KD 位置子因子承擔超賣語意）。確認 t223 已移除該加成後，本任務不得再引入等價邏輯。

- [ ] **226.8 測試**

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

（實作者做完後回填：實際改了哪些檔、上述驗證各步驟的真實輸出、226.4 修正了哪些既有測試的預期值、與原計畫的偏差及原因。）
