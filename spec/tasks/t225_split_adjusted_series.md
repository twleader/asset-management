# [t225] 還原權息序列支援股票分割，並排除非正收盤列

> ## ⛔ 本任務已由 [t265](t265_split_adjusted_series.md) 取代，不得再依本檔實作
>
> 本檔從未實作，且其設計綁在 t223 的 750 根長窗需求上。t265 不擴大取數視窗，只修正既有 241 根視窗內的正確性，並加入使用者要求的週線 MA5。分割偵測的啟發式與門檻依據（±15% 以上跳空 21 筆中僅 2 筆為真分割）仍然有效，已移入 t265。

**對應 Requirements:** Requirement 43 修訂（今日交易雷達——不呼叫 AI API 的台股規則式買賣決策輔助；本任務讓還原權息序列額外處理股票分割與資料品質異常，使長窗技術因子在做過分割的標的上不再靜默算錯）
**前置任務:** 無
**Liquibase changeset:** 無（本任務不新增資料表；分割事件由序列推導，不入庫——見 225.6）

---

> ## ⚠ 本任務尚未通過 spec 審查，不得進入實作
>
> `/spec-review` 三輪分數 5 → 5 → 7（門檻 8）。以下問題**已查證屬實但尚未修正**，照現行內容實作會產生靜默失敗：
>
> **Critical-1：指定的實作位置對最重要的案例不會被執行。** `DistributionAdjustedPriceService.adjust()` 有四條 early-return（行 30／33／49／71），其中「無除權息事件」與「事件視窗過濾後為空」兩條會直接回傳原始序列。而實測 **2327 在 `stock_dividend_history` 完全沒有紀錄**（該表只有 0050 的 21 筆與 006208 的 20 筆），它正是全庫兩筆真分割之一（2025-08-25，546 → 143）。故 225.1 的偵測與 225.4 的非正收盤排除對 2327、00865B、3036、2317 等無除權息紀錄的標的**永遠不會執行**。修法：225.1／225.4 須明寫「必須先做非正收盤排除與分割偵測，再判斷有無除權息事件」，且 225.5 的「既有行為一律不變」要把這四條 early-return 列為刻意的例外。
>
> **Critical-2：唯一的迴歸守門員恆為通過。** 驗證段步驟 7 查詢 `s.get('ma20')`／`ma60`／`ma240`，但 `TradingRadarDto.StockDecision`（行 64–66）的實際欄位是 **`monthlyMa`／`quarterlyMa`／`annualMa`**。三個 key 一律印 `None`，實作前後自然相同，判準結構上不可能失敗。修法：改為正確欄位名，並加註「若印出 `None` 即代表指令寫錯，不得視為通過」。
>
> **Major-1：反向分割無法透過既有機制表達。** `adjust()` 行 61 的守衛為 `if (factor.compareTo(BigDecimal.ONE) > 0)`，反向分割的 `factor = 1/N < 1` 必被丟棄；而 225.3 又禁止另建路徑、225.7 卻要求測反向分割，三者互斥。且「分割日的 `eventFactor` 乘上比例 N」在**沒有除權息事件的分割日**（0050 與 2327 皆是）無操作可執行。修法：守衛改為 `!= 0`，並把分割合成為記憶體內的虛擬事件插入序列。
>
> **Major-2：`ratio >= 2.0` 的硬門檻讓 1:2 分割約一半機率漏抓。** 偵測門檻無容差，二次驗證卻允許 ±10%，對 candidate `2` 的可接受區間實際只有 `[2.0, 2.2]`。台股 ±10% 漲跌幅意味真實 1:2 分割當日 `ratio ∈ [1.818, 2.222]`——**分割日若上漲就漏抓**。現有兩筆真分割都是 1:4（3.966／3.818），所以「零漏抓」只在 1:4 上驗證過。修法：門檻改為 `ratio >= 1.8`／`<= 0.556`，一律交由二次驗證定案；並補一則「1:2 分割且當日上漲 1%（ratio = 1.980）」的測試。
>
> **Minor：** (a) 225.7 用 006208 當「無分割、還原後逐值相同」的測試對象不當——它是全庫零收盤列最多的標的（88／176 筆，分布於 2016-07 ~ 2018-04），該測試在兩種讀法下都不成立，應改用 0056 或 00878 並明寫視窗長度；(b) 背景段的 7556 應為 **8 筆**（非 7 筆），列舉總數才與「非分割 19 筆」相符；(c) 本任務的門檻只在台股序列驗證過，`adjust()` 目前也只服務台股路徑（`TradingRadarService.java:275`），須聲明適用範圍——實測英股 `IB01` 於 2019-02-22 有 `ratio = 0.050` 的資料異常會固定觸發 WARN。

---

## 背景

### 現行缺陷

`backend/src/main/java/com/steven/assets/service/DistributionAdjustedPriceService.java` 只處理現金股利與股票股利：它的 `validEvent()` 僅接受 `cashDividend` 或 `stockDividend` 為正的事件，而事件來源 `stock_dividend_history` 的欄位只有 `cash_dividend` 與 `stock_dividend`（`numeric(15,6)`）。

`grep -ni "split|分割|拆股"` 在該檔**零命中**——**完全不處理股票分割**。

### 這個缺陷為什麼現在才要修

實測 0050 的原始收盤序列（`stock_price_history`，`market='台股'`）：

```
trading_date | prev_close | close_price |   pct
2025-06-18   |   188.6500 |     47.5700 | -74.8%     ← 1:4 分割（188.65 / 47.57 ≈ 3.97）
```

而現行 `TradingRadarService` 取數視窗為 241 根（`findRecentN(code, market, 241)`），第 240 根落在 2025-07-23（收 50.70）——**恰好停在分割之後**。這就是這個缺陷至今沒有爆過的原因。

一旦取數視窗擴大到 750 根（3 年年化報酬所需，第 750 根為 2023-06-08、收 126.85，在分割之前），序列就會跨過分割，長窗因子全部算錯：

| 指標 | 未還原分割（錯誤） | 分割還原後（正確） |
|---|---|---|
| 0050 三年年化報酬 | `(100.15/126.85)^(1/3) − 1` = **−7.6%** | `(100.15×4/126.85)^(1/3) − 1` = **+46.7%** |
| 標準化貢獻 | `−1` | `+1` |

**方向完全相反，而且算得出數字、不會拋任何例外**——是靜默錯誤。這正是本任務必須先於長窗因子上線的原因。

### 為什麼採啟發式偵測而非外部資料源

台股的分割事件沒有現成的本地資料（`stock_dividend_history` 無此欄位），而序列本身就帶有明確且可靠的訊號。實測全部台股序列中變動幅度超過 ±15% 的跳空共 21 筆：

```
真分割（2 筆）：
  0050  2025-06-18  188.65 →  47.57   −74.8%   比例 ≈ 3.97
  2327  2025-08-25  546.00 → 143.00   −73.8%   比例 ≈ 3.82

非分割（19 筆，幅度 −22% ~ +47%）：
  2327 於 2016-08-15(+23.4%)、2017-08-18(+46.9%)、2019-08-26(−19.0%)、2022-10-31(+36.9%)、2024-08-15(−16.5%)
  7556 於 2019-12-12 ~ 2020-10-30 共 7 筆（+15.7% ~ +22.1%、−21.5%）
  00642 於 2020-03-09(−22.2%)、2026-03-09(+33.1%)、2026-03-10(−16.5%)
  2404 於 2018-12-10(+23.3%)、3034 於 2022-07-12(−22.0%)、1301 於 2026-07-03(+20.1%)
```

**真分割與非分割之間有巨大的安全間隙**：真分割都在 −73% 以下，非分割最大只到 ±47%。故門檻設在「跌幅 ≥ 50%」時，在現有 10 年資料上**零誤報、零漏抓**。

> **注意：不要用 ±15% 之類的小門檻。** 台股雖有 ±10% 漲跌幅限制，但實測序列中存在大量 15%~47% 的跳空（停牌復牌、興櫃期間、資料源缺日等），用小門檻會產生 19 次誤報，把正常價格變動當成分割而把序列改壞。

## 要做什麼

- [ ] **225.1 分割偵測**

  在 `DistributionAdjustedPriceService` 增加分割偵測，掃描傳入的原始收盤序列，逐日比對相鄰兩根完成日 K：

  ```text
  ratio = prevClose / close

  正向分割（1 股拆成 N 股，價格下跌）：ratio >= 2.0
  反向分割（N 股併成 1 股，價格上漲）：ratio <= 0.5
  其餘一律不是分割，不得調整
  ```

  對應到幅度即「跌幅 ≥ 50%」或「漲幅 ≥ 100%」。**門檻為具名常數**，不得寫死於條件式。

  偵測到的每一筆分割都須 `log.info` 記錄（股票代號、日期、前後收盤、推得的比例），使日後可稽核。

- [ ] **225.2 比例的二次驗證（避免把資料異常當成分割）**

  偵測到的 `ratio` 須進一步驗證為**接近簡單整數比**才採用：

  ```text
  candidates = {2, 3, 4, 5, 10}          （正向分割）
  candidates = {1/2, 1/3, 1/4, 1/5, 1/10}（反向分割）

  取最接近的 candidate；若 |ratio − candidate| / candidate > 0.10 則不視為分割
  ```

  容忍度 `10%` 是必要的：分割當日股價本身也會變動，故實測比例不會恰好等於整數。實例——0050 為 `188.65 / 47.57 = 3.966`（距 4 約 0.9%）、2327 為 `546.00 / 143.00 = 3.818`（距 4 約 4.5%）。兩者都在 10% 內。

  不通過二次驗證的跳空須 `log.warn` 記錄並**維持原始值不調整**（保守處理：寧可不調也不要調錯）。

- [ ] **225.3 分割因子併入既有還原計算**

  現行還原邏輯的事件因子為：

  ```text
  eventFactor = 1 + stockDividend / 10 + cashDividend / eventDayClose
  sharesAfterEvent = sharesBeforeEvent * eventFactor
  adjustedOHLC(date) = rawOHLC(date) * sharesAtDate / finalShares
  ```

  分割在數學上等同「1 股變成 N 股」，故直接以相同的 shares 機制表達：**分割日的 `eventFactor` 乘上偵測到的分割比例 N**。不得另建第二套調整路徑——兩套並存會在同一序列上重複套用而放大誤差。

  同一日同時有分割與除息時，兩個因子相乘（順序不影響結果，因為都是乘法）。

  **OHLC 四個欄位必須一致調整**，不得只調 close。既有的 KD 計算會用到 high／low。

- [ ] **225.4 排除非正收盤列（獨立的資料品質防護）**

  實測 `stock_price_history` 中有 **176 筆 `close_price <= 0` 的台股列**。這些列會造成：分割偵測除以零、3 年年化報酬除以零、52 週高低取到 0 而使相對位置恆為 1。

  取數時一律**排除 `close_price <= 0` 的列**，並：

  - 於 `log.warn` 記錄被排除的筆數與代號（使資料品質問題可見，而非靜默吞掉）
  - 排除後若有效根數不足該因子的門檻，該因子回 `null`（走既有的權重重分配），**不得**以剩餘資料硬算後假裝完整

  `open_price`／`high_price`／`low_price` 為 `NULL` 是合法的（既有註解明載「一律保留 null（無資料），不再用 0 偽裝」），僅 `close_price` 有 `NOT NULL` 約束但可能為 0。

- [ ] **225.5 不得破壞既有行為**

  本任務**只增加**分割與非正收盤的處理，既有行為一律不變：

  - 沒有分割事件的標的，還原後序列須與現行輸出**完全相同**（逐值比對，不是近似）。
  - 現行 241 根視窗的 MA20／MA60／MA240、KD、兩日確認結果不得改變——0050 的 241 根視窗本來就在分割之後，故本任務對它的**現行**分數應為零影響（差異只會出現在擴大視窗之後）。
  - 既有的「MA／KD／兩日確認／規則漲跌幅一律使用同一還原序列」硬約束不變：分割調整同樣要套用到全部指標，不得只調 MA 不調 KD。

- [ ] **225.6 分割事件不入庫（設計決策，須寫入程式碼註解）**

  分割比例由序列推導，**不新增資料表、不寫回 `stock_dividend_history`**。

  理由：(a) 推導結果是可從 `stock_price_history` 計算得出的衍生值，儲存違反本專案「禁止存入可從其他欄位計算得出的衍生值」的規範；(b) 偵測邏輯日後若調整門檻，入庫的舊結果會與新邏輯不一致而產生兩個事實來源。

  若日後取得官方分割資料源，屆時應另立 Requirement 建立正式事件表並讓偵測退為 fallback——該演進路徑須寫入類別 Javadoc。

- [ ] **225.7 測試**

  測試與實作同屬本任務，不得延後。至少覆蓋：

  - **0050 的真實分割**：以 2025-06-18 前後的真實數列（`188.65 → 47.57`）為輸入，驗證偵測成立、比例判為 `4`、還原後序列連續（分割前後的相鄰兩值比值接近 1）。
  - **零誤報**：以實測的 19 筆非分割跳空為輸入（至少涵蓋最大正跳空 `2327 +46.9%` 與最大負跳空 `00642 −22.2%`），驗證**皆不被判為分割**、序列不被調整。
  - **二次驗證擋掉異常比例**：構造一筆 `ratio = 2.6`（不接近任何 candidate）的跳空，驗證不調整且記 WARN。
  - **反向分割**：構造 `ratio = 0.5`（漲幅 100%）的輸入，驗證正確偵測並調整。
  - **分割與除息同日**：兩因子相乘，結果與分開套用一致。
  - **非正收盤排除**：序列含 `close_price = 0` 的列時，該列被排除且記 WARN；排除後根數不足門檻時該因子回 `null`。
  - **無分割標的零影響**：取一檔無分割的標的（如 006208），還原後序列與現行實作**逐值相同**。
  - **OHLC 一致性**：分割調整後 `high >= close >= low` 的關係仍成立。

  本專案在 Java 25 下跑 Mockito 需要 `-DargLine="-Dnet.bytebuddy.experimental=true"`（直接傳 `-D` 無效，surefire 會 fork 新 JVM）。

## 驗證

> **環境事實（已實測，照抄即可執行）**：容器名為 `asset-postgres`／`asset-bff`／`asset-business-services`／`asset-external-materials-service`（**不是** `asset-management-*-1`）。DB 的 user 與 database 皆為 `assets`。**只有 `asset-bff` 對 host 發佈 8080**；business-services 只在容器網路內聽 8080，**沒有 host port**。BFF 的 `/api/bff/**` 需登入，從 host 直接 curl 會回 **401**；免 OAuth 的做法是進 business 容器帶 `X-User-Id`／`X-User-Role`／`X-User-Status` header 打 business 端點（已實測回 200）。

```bash
# 1. 單元測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -Dtest=DistributionAdjustedPriceServiceTest \
  -DargLine="-Dnet.bytebuddy.experimental=true"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DargLine="-Dnet.bytebuddy.experimental=true"

# 2. 確認待處理的分割事件（實作前後都該跑，結果應一致）
docker exec -i asset-postgres psql -U assets -d assets -c \
"WITH s AS (SELECT stock_code, trading_date, close_price,
                   LAG(close_price) OVER (PARTITION BY stock_code ORDER BY trading_date) prev
            FROM stock_price_history WHERE market='台股')
 SELECT stock_code, trading_date, prev, close_price, ROUND(prev/close_price,3) AS ratio
 FROM s WHERE prev > 0 AND close_price > 0 AND (prev/close_price >= 2.0 OR prev/close_price <= 0.5)
 ORDER BY trading_date;"
# 預期：2 筆（0050 於 2025-06-18 ratio≈3.966、2327 於 2025-08-25 ratio≈3.818）

# 3. 確認非正收盤列的規模
docker exec -i asset-postgres psql -U assets -d assets -c \
  "SELECT market, COUNT(*) FROM stock_price_history WHERE close_price <= 0 GROUP BY market;"
# 預期：台股 176 筆

# 4. 重建映像並重建容器（JVM 必須 --no-cache，否則可能產出不含本次變更的 stale jar）
docker compose -p asset-management build --no-cache business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services

# 5. 重建 business 會換 IP，BFF 握舊 IP 回 500 且不自癒（Docker DNS TTL 600s）
docker compose -p asset-management restart bff

# 6. 健康檢查

# 7. 迴歸：現行 241 根視窗下 0050 的分數與指標不得改變（本任務對現行行為應零影響）
docker exec asset-business-services curl -s \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/trading-radar \
  | python3 -c "import sys,json; d=json.load(sys.stdin); [print(s.get('stockCode'), s.get('score'), s.get('action'), s.get('ma20'), s.get('ma60'), s.get('ma240')) for s in d.get('stocks',[]) if s.get('stockCode') in ('0050','006208')]"

# 8. 分割偵測的 log 確實有輸出
docker compose -p asset-management logs --since 5m business-services | grep -iE "分割|split" | head
```

驗收判準：步驟 2 恰好 2 筆；步驟 7 的 0050 分數與三條均線**與本任務實作前完全相同**（現行視窗在分割之後，本任務不應改變它）；步驟 8 可見分割偵測記錄。

**實作前請先跑一次步驟 7 並保留輸出**，作為步驟 7 的比對基準。

## 完成報告

（實作者做完後回填：實際改了哪些檔、上述驗證各步驟的真實輸出、**步驟 7 的實作前後對照**、偵測到的分割事件清單、與原計畫的偏差及原因。）
