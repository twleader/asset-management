# [t291] 今日交易雷達改為一週至六個月的獲利機會雙軌評分

**對應 Requirements:** Requirement 43／59／60（今日交易雷達以短期與中期獲利機會為主，納入完整技術面、量能、前一美股科技交易日、台美公開資訊及債券 ETF 匯率）
**前置任務:** t273（既有規則回測框架，已完成）
**Liquibase changeset:** 無

## 背景

使用者於 2026-08-08 重新定義「今日交易雷達」：評量範圍只限使用者持股與觀察股票，以約一週至六個月內的獲利機會為目標；週線、月線、季線、年線與 KD／J、MACD、RSI、乖離率、威廉指標都要考慮，台股大盤成交量、前一交易日美股科技股、台灣與美國的公開財經資訊也要進入分析；債券 ETF 必須考慮匯率；股票不可追高殺低，逢低承接與逢高獲利了結要成為明確規則。評分不考慮可用資金、成本價、資產配置、風險承受度或其他個人理財條件，純粹比較市場上的獲利機會。

現況有四個缺口：

1. `TW_RULES_V9` 只有一組分數，原目標曾寫成「數周至兩年」，與本次一週至六個月不符。
2. `TechnicalIndicatorService.FullIndicators` 已輸出 MA5 與 14 個擴充指標，但 active Javadoc 與 DTO 註解仍明寫「純揭露、不參與評分」，畫面也只顯示 MA 與 KD。
3. `twse_index_daily_history` 已有完成日的 `trade_volume`／`trade_value`，`us_index_daily_history` 已有 `IXIC`／`SOX`，`news_headline` 已有台灣與美國近期公開資訊；雷達目前都沒有使用。
4. t273 的十年回測已否定 V9 的兩個賣出假設：`極端超買＋KD 高檔死叉` 的 5／20／60／240 日前瞻報酬相對基準為 `+0.87／+6.11／+12.38／+18.56`，不能再把 KD 死叉單獨當作獲利了結依據；極端超賣阻擋出場的差額為 `+3.81～+48.34`，連原本排除保護的「長期結構破壞」組也為 `+3.60～+50.02`，故本次移除該排除條件，避免在低點殺出。

本任務以 `TW_RULES_V10` 一次取代尚未實作的 t276 與 t277。t276／t277 任務檔只保留歷史診斷，索引須標示「已由 t291 取代」。

## 要做什麼

- [ ] **291.1 評分範圍與持有期契約**

  - 標的集合維持 `目前持股 ∪ 觀察清單`，只評估 `market=台股`；同一標的去重，`0000` 只作大盤、不出現在個股決策列，非台股標的只計入 `skippedNonTwStocks`。
  - 每檔同時輸出兩組彼此獨立的分數與動作：
    - **短期**：`5` 個交易日，約一週。
    - **中期**：以 `20／60／120` 個交易日檢視，約一至六個月。
  - 既有 `score`／`action`／`actionLabel`／`reasons`／`risks` 保留原名並改明確定義為**中期**，避免快照、匯出與通知既有消費端變成另一種語意；新增 `shortScore`／`shortAction`／`shortActionLabel`／`shortReasons`／`shortRisks`／`horizonConflict`。
  - `held` 只用於建立標的集合，以及把同一方向翻成「買進／加碼」或「觀察／持有／減碼」文字；**不得進入任何因子、權重、分數或買賣閘門**。不得讀取或推導成本價、未實現損益、可用資金、配置比例、個人目標、風險承受度。排序依 `max(shortScore, score)` 由高到低，缺值排最後，再以股票代碼穩定排序。
  - 分數代表「目前位置的相對獲利機會」，不是獲利機率或報酬預測。任何 UI、Javadoc、理由、風險、匯出說明均不得出現「保證獲利」「五日內會漲」「六個月必賺」等語句。

- [ ] **291.2 兩軌都必須考慮完整指標，且使用兩組獨立權重**

  `TradingRadarRuleEngine.StockInput` 新增 `weeklyMa`、`ExtendedIndicators`、`volumeRatio`。兩軌不得共用一個分數再切不同門檻；必須各自建立 `Accumulator`，缺值時沿用既有語意：該因子不進 `sumW`，其權重由可用因子重新正規化，**不得以 0 冒充缺值**。

  每一列的兩軌均須使用下列 14 組因子；權重是本任務唯一契約，全部須為具名常數，短期與中期各自加總 `1.00`：

  | 因子組 | 短期 | 中期 | 貢獻定義 |
  |---|---:|---:|---|
  | 週線 MA5 | 0.08 | 0.03 | 現價相對 MA5 的位置；短期權重較高 |
  | 月線 MA20 | 0.07 | 0.07 | 現價位置與完成日兩日確認的可用值平均 |
  | 季線 MA60 | 0.05 | 0.12 | 現價位置與完成日兩日確認的可用值平均 |
  | 年線 MA240 | 0.03 | 0.10 | 現價位置與完成日兩日確認的可用值平均 |
  | KD／J | 0.11 | 0.07 | `K-D` 方向、`avg(K,D)` 位置、`J9` 位置三者可用值平均；窄幅 KD 時整組缺值 |
  | MACD | 0.10 | 0.08 | `OSC=DIF-MACD` 正負；EMA12／EMA26 透過 DIF、DIF／MACD 透過 OSC 同源考慮，不重複灌權重 |
  | RSI | 0.08 | 0.06 | RSI5／RSI10 的可用值平均後，以 50 為中性；低檔為正、高檔為負 |
  | 威廉指標 | 0.05 | 0.04 | `W%R9` 以 50 為中性；本系統值域 0＝高檔、100＝低檔 |
  | 乖離率 | 0.09 | 0.12 | `BIAS10`、`BIAS20`、`BIAS10-BIAS20` 正規化後的可用值平均；正乖離為追高成本、負乖離為低接位置 |
  | 個股相對量 | 0.10 | 0.08 | 依 291.3 的量價確認 |
  | 市場環境 | 0.10 | 0.09 | 股票用 291.4 的台股 regime；債券 ETF 為缺值，不拿台股風險方向評債券 |
  | 完成日漲跌 | 0.05 | 0.04 | `clamp(-completedChangePercent / 5)`；上漲延伸為負、下跌回落為正，買進仍受 291.6 止跌閘門 |
  | 匯率 | 0.05 | 0.06 | 外幣底層資產的五年期匯率分位；低分位正、高分位負；台幣資產缺值 |
  | ETF 折溢價 | 0.04 | 0.04 | 該 ETF 自身歷史分位；低分位正、高分位負；非 ETF 或樣本不足缺值 |
  | **合計** | **1.00** | **1.00** | |

  所有 contribution clamp 在 `[-1,1]`；`score = round(50 + 50 × weightedMean)` 並 clamp 在 `[0,100]`。精確定義：

  - MA 位置：`price > ma => +1`、`price < ma => -1`、相等 `0`；MA20／60／240 再與 `Confirmation` 的 `ABOVE=+1`、`BELOW=-1`、`MIXED=0` 平均。
  - KD／J：`K>D => +1`、`K<D => -1`；位置各為 `clamp((50-value)/50)`，使低檔有利於承接、高檔不鼓勵追價。`kdBandWidthPercent < 2%` 時 KD、J、W%R 均缺值，避免窄幅債券 ETF 的指標飽和誤導。
  - MACD：`OSC>0 => +1`、`OSC<0 => -1`、`OSC=0 => 0`。不得把 `DIF`、`MACD`、`OSC` 三個代數相依值重複當三份獨立權重。
  - RSI：`clamp((50-avg(RSI5,RSI10))/50)`；W%R：`clamp((W%R9-50)/50)`。
  - 乖離率：平均 `clamp(-BIAS10/10)`、`clamp(-BIAS20/20)`、`clamp(-(BIAS10-BIAS20)/8)`。
  - 匯率／折溢價分位：`clamp((50-percentile)/50)`。匯率分位的可用日期與 fallback 排除另受 291.3a 約束。
  - `TechnicalIndicatorService` 的公式、Wilder RSI、DI 價基 MACD、EMA seed 與精度均不改；只更新 active Javadoc／程式註解，使「純揭露、不進評分」改成 V10 的真實語意。歷史 spec 與已執行 Liquibase changeset 不改。

- [ ] **291.3 個股成交量以股份數量事件還原後的相對量納入**

  - `RadarInputAssembler` 以最新完成日成交量除以**之前 20 個正成交量交易日**的中位數，得到 `volumeRatio`；最新日不得同時進入分母。正樣本少於 10 筆、最新量為 null／0、或中位數非正時回 `null`。
  - 量價 contribution：完成日上漲且 `ratio>=1.2 => +1`；完成日下跌且 `ratio>=1.2 => -1`；完成日上漲且 `ratio<=0.7 => -0.4`；完成日下跌且 `ratio<=0.7 => +0.4`；其餘 `0`。這讓帶量上漲成為需求確認、爆量下跌成為籌碼風險、下跌縮量代表賣壓收斂。
  - `DistributionAdjustedPriceService` 在既有混合價格因子旁新增 **share-only** 累積因子。事件值統一定義為 `shareGrowth = postShares / preShares`：1:4 正向分割為 `4`，股票股利 1 元為 `1 + 1/10 = 1.1`，現金股利恆為 `1`。逐列保存 `historicalShareScale = cumulativeShareGrowthAtRow / finalShareGrowth`，成交量公式唯一為 `adjustedVolume = round(sourceVolume / historicalShareScale)`；不得把 `shareGrowth` 本身誤當分母。數值例：1:4 分割前的 scale=`1/4`，故價格乘 `1/4`、volume 除 `1/4`＝放大四倍，分割日後 scale=`1`；股票股利 1 元事件前 scale=`1/1.1`，故量放大 `1.1`；只有現金股利時 share scale 前後皆為 `1`、volume 完全不變。價格仍用現金股利＋股票股利＋分割的 price scale；不得用含現金股利的價格 scale 改 volume。所有相關 Javadoc 與行內註解同步更新。

- [ ] **291.3a 匯率分位必須是 as-of 完成日資料，不得沿用 stale／fallback 最後值**

  - `TradingRadarMarketContextService`（或同一個純讀 context resolver）接收顯式 `decisionInstant`。在 `MarketDataService` 新增 fail-closed 的 `Optional<Boolean> isTwTradingDayKnown(LocalDate)`：週末可直接回 `Optional.of(false)`；平日必須先取得該年度**非空**的既有 external-materials 假日 proxy／快取，成功才回是否交易日，空表／失敗回 `Optional.empty()`。既有給其他流程使用的 `isTradingDay()` 語意不改。resolver 只用這個新入口找出「17:00 已早於或等於 decisionInstant」的最近交易日作 `fxTargetDate`，且不得觸發行情、新聞或匯率抓取；任一候選日得到 empty 就立即 fail closed：`fxTargetDate=null`，不得改以週一至週五或 `twse_index_daily_history` 缺列自行猜測。production 傳目前 instant，backtest 傳該歷史訊號日台北 14:00，因此回測日的當日 17:00 匯率尚不可用。
  - 外幣資產只接受 `exchange_rate_history.rate_date == fxTargetDate` 的列；`buy_rate`／`sell_rate` 任一缺值或非正，或 `buy_rate == sell_rate`（Yahoo fallback 中間價假裝買賣價），均視為無效。無有效精確日列時 `fxPercentile=null`，不得沿用最近一筆。
  - 五年分位視窗只含 `rate_date <= fxTargetDate`、真實買賣價差且正值的完成日列；有效樣本少於 600 筆回 null。backtest 的每個 signal date 只可見其當時視窗，不得用今日最新分位。
  - `StockDecision` 新增 `fxAsOfDate`；無效時為 null，畫面／匯出留白並在 risks 揭露「精確完成日匯率不可得」。測試至少覆蓋：過期最後值不得沿用、`buy=sell` fallback 不進分位、週末取最近已完成交易日、歷史訊號後的匯率列不會被讀取。

- [ ] **291.4 台股大盤成交量與前一美股科技交易日進入市場分析**

  新增純讀的 `TradingRadarMarketContextService`。市場量能、美股指數、匯率與新聞內容只讀本地 PostgreSQL；唯一允許的跨服務依賴是既有 `MarketDataService` 的 fail-closed 假日日曆 proxy／快取入口 `isTwTradingDayKnown()`，不得觸發行情、新聞、匯率抓取，不注入 `MarketAnalysisService`、crawler 或任何 LLM client。所有 current／historical 方法都必須接收顯式 `decisionInstant`；不得在 resolver 內呼叫 `LocalDate.now()`，避免回測前視。

  - **台股完成日量能**：取 `twse_index_daily_history` 最新兩個完成日；最新日 `trade_volume` 與 `trade_value` 分別除以其前 20 個正值交易日的中位數，得到 `marketVolumeRatio`／`marketTurnoverRatio`，樣本少於 10 或非正則個別為 null。價格漲跌用最新與前一收盤計算，嚴禁用盤中價搭配完成日成交量。
  - `evaluateMarket()` 在既有均線／KD 基礎上加入量價分數：完成日上漲且兩個可用量比平均 `>=1.1` 加 8；完成日下跌且平均 `>=1.1` 減 10；完成日上漲但平均 `<=0.8` 減 3；完成日下跌且平均 `<=0.8` 加 3；量資料不足計 0 並揭露。
  - **前一個在決策時點已完成的美股科技交易日**：美股 session close 定義為該交易日 `16:00 America/New_York`（由 zone rules 自動處理 DST）。只可從 `us_index_daily_history` 選 session close `<= decisionInstant` 的列，再找 `IXIC` 與 `SOX` 最新共同交易日及各自嚴格更早的前一筆，計算一日報酬；`usTechCompositePercent = 0.4×IXIC + 0.6×SOX`。共同日距 `decisionInstant` 的台北日期超過 5 個日曆日或任一必要收盤缺值時全部 unavailable，不得拿不同日期硬湊。
  - 美股科技分數：composite `>=+1.0%` 加 6、`>0` 加 3、`<=-1.5%` 減 6、`<0` 減 3；Unavailable 計 0 並揭露。production 傳請求開始時的真實 instant；backtest 對每個台股 `signalDate` 固定傳該日 `14:00 Asia/Taipei`。資料庫即使已含 signalDate 之後的美股列也不得讀取。測試須含台股盤中對應的未完成美股 session、週末、跨美國假日與 DST 切換日。
  - `MarketSummary` 新增 `marketVolumeRatio`、`marketTurnoverRatio`、`marketVolumeAsOfDate`、`nasdaqChangePercent`、`soxChangePercent`、`usTechCompositePercent`、`usTechAsOfDate`、`usTechAvailable`。市場理由／風險須寫出量價方向與美股科技方向；不得把資料缺失寫成中性市場結論。
  - `StockInput.marketRegime` 會把上述量能與美股科技影響帶入股票的兩軌市場因子。債券 ETF 不吃台股 regime，其匯率與折溢價仍照常評分。

- [ ] **291.5 台灣／美國公開財經資訊須在頁面與快照中可見**

  - 只讀 `news_headline` 近 72 小時且 `category='news'` 的資料，分別取 `region='TW'` 與 `region='US'` 最新最多 3 筆，依 `publishedAt desc, id desc` 穩定排序；回傳 `region`／`title`／`source`／`url`／`publishedAt`。同一 `dedupe_key` 只留最新一筆。
  - `TradingRadarDto.Response` 新增 `publicInformation: List<PublicInformationItem>`；快照 JSON 自動保存。前端在大盤卡片下方新增「台美公開財經資訊」區塊，台灣／美國分組，標題可點原文並顯示來源與發布時間；無資料時誠實顯示「近 72 小時無可用資訊」。
  - 新聞文字**不做正負情緒打分**。理由須寫在 `TradingRadarMarketContextService` Javadoc：現有資料沒有可信的結構化方向欄位，關鍵字情緒會把「利空出盡」「跌幅收斂」等語句誤判。公開資訊的重要性由醒目揭露承接；可量化的台股量能與美股科技日報酬才進分數。不得為滿足「重要」而臆造新聞數字。

- [ ] **291.6 不追高、逢低承接與逢高獲利了結**

  - 兩軌先依分數使用既有門檻映射：`>=75` 買進／加碼候選，`55–74` 持有／觀察，`40–54` 謹慎持有／等待，`25–39` 減碼候選／迴避，`<25` 出場候選／迴避。
  - **禁止追高與仍在下跌時買進**：`TimingState` 為 `OVERBOUGHT`／`EXTREME_OVERBOUGHT`、`KdHeat=OVERHEATED`、完成日漲幅 `>=5%`、完成日漲跌缺值或 `<0`、匯率分位 `>=90`、ETF 溢價 `>=3%`、大盤 stale，任一成立即關閉一般買進／加碼閘門。換言之，任何 `BUY_CANDIDATE`／`ADD_CANDIDATE` 都必須有 `completedChangePercent>=0`；股票在 `RISK_OFF` 關閉一般順勢買進，債券不套用股票大盤閘門。
  - **逢低承接**：沿用 `TRIAL_BUY` 的長期結構＋短期超賣轉強條件，但同時要求最近完成日 `completedChangePercent>=0`，不得在仍續跌時接刀；`RISK_OFF` 不禁止小額試單，stale 仍禁止。這只是市場時機分類，不讀取或建議個人投入金額。
  - **極端超賣不殺低**：兩軌的基礎動作若為減碼／出場／迴避，而 `TimingState=EXTREME_OVERSOLD`，一律改為已持有 `HOLD_CAUTION`、未持有 `WAIT`。移除 V9 的 `longTermBroken` 排除條件；可保留「長期結構弱」風險文字，但不得藉此覆寫保護。此決策直接依 t273 的回測數字。
  - **逢高獲利了結不能只看 KD 死叉**：`profitTakingConfirmed` 必須同時位於 `EXTREME_OVERBOUGHT`，且下列三項獨立轉弱證據至少兩項成立：`kdDeadCross`、`OSC<0`、`completedChangePercent<0 && volumeRatio>=1.2`。成立時，已持有兩軌皆可覆寫為 `REDUCE_CANDIDATE`，未持有為 `AVOID`；理由須明示是「高檔且多項轉弱確認」，不得宣稱能預測下跌。V9 的「極端超買＋KD 死叉」單一覆寫刪除或改成上述合取。
  - `horizonConflict=true` 定義為短期與中期的動作方向分組不同：買進組（BUY／ADD／TRIAL）、中性組（HOLD／WATCH／HOLD_CAUTION／WAIT）、賣出組（REDUCE／EXIT／AVOID）、無法判定組（NO_TRADE）。畫面收合列須可直接看見「短中期分歧」。

- [ ] **291.7 API、畫面、快照、匯出與通知邊界**

  - `TradingRadarDto` 與 active Javadoc 更新為 `TW_RULES_V10`；所有新增 BigDecimal 顯示／匯出 2 位小數，缺值為 null／留白，不得顯示 0。
  - 個股表收合列新增「短期（約一週）」與「中期（1–6 月）」兩組動作／分數；展開列分開顯示兩軌 reasons／risks，並顯示 `J9`、`MACD/DIF/OSC`、`RSI5/10`、`BIAS10/20`、`W%R9`、`volumeRatio`，不得再把 `extendedIndicators` 註解或畫面當成匯出專用。
  - 大盤卡片顯示完成日成交量比、成交金額比、NASDAQ／SOX 前一交易日漲跌與日期；公開資訊區塊依 291.5 顯示。
  - 免責文字改為：評分只看市場獲利機會，不納入成本價、可用資金、配置或個人理財需求；系統不保證獲利、不自動下單，資料不足為今日不交易。不得再要求使用者把個人資金條件當成本評分的一部分。
  - `TradingRadarSnapshotStore` 無自訂 schema，須以 DTO round-trip 測試證明新欄位保存；舊快照缺少新欄位時反序列化為 null／false，不得讀檔失敗。
  - `TradingRadarExportService` 的大盤總覽新增台股量能與美股科技欄，個股決策新增短期動作／分數、分歧與個股量比；新增「台美公開資訊」工作表（地區、發布時間、來源、標題、網址）。Excel 與 JSON 走同一份 `ExportDoc`，舊快照欄位留白。
  - 既有 `trading_radar_notification_setting.last_action` 與通知選單維持**中期 action** 語意；本任務不新增短期通知、不新增 DB 欄位。通知設定 UI 的「主規則建議」改為「中期建議（1–6 月）」，避免使用者誤認短期變化會寄信。
  - `RULE_VERSION` 由 `TW_RULES_V9` 升為 `TW_RULES_V10`。同步 active Java／Vue／測試；**禁止修改**已執行的 `v1.83.0-radar-notification-rule-version.sql` 內容或註解。版本不符時沿用既有機制只重建中期通知基準、首輪不寄信。

- [ ] **291.8 回測、註解與不得做的事**

  - `BacktestService` 與 production 必須共用 `RadarInputAssembler`／`TradingRadarRuleEngine`。回測 horizon 改為或額外執行 `5／20／60／120`；新增短期買進、中期買進、短期獲利了結、中期獲利了結、極端超賣保護述詞。大盤量能、美股科技與匯率的歷史對齊一律傳入該訊號日 `14:00 Asia/Taipei` 的 instant，只可使用當時已完成資料，不得把未來 session／匯率列帶回去。
  - 完成報告列出各述詞四個 horizon 的樣本數、平均／中位報酬、勝率、跌逾 10% 比例及對同標的同期間基準差額。回測是稽核，不得因單次結果不好而隱藏；若要改本任務的固定權重或門檻，必須先同步改規格再重審。
  - 以 `rg` 檢查 active code 中所有「MA5／擴充指標純揭露、不參與評分」「數周至兩年」「V9」「長期結構破壞者不受保護」等註解與 Javadoc；凡實際行為已改者一併修改。歷史任務檔、歷史版本敘述與 Liquibase changeset 不做批次取代。
  - 不新增排程、不新增資料表、不直接呼叫外部行情／新聞／匯率 API（既有 `MarketDataService.isTwTradingDayKnown()` 日曆 proxy 例外）、不觸發新聞爬蟲、不使用 AI／LLM、不擴大至美股個股或非台股標的、不自動下單。

## 驗證

先跑規格守門與完整單元測試：

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
```

測試至少包含：

- 短期／中期權重各自精確為 `1.00`，分數恆在 `[0,100]`；任一 optional 因子 null 時確實重分配而不是當 0。
- `held=true/false` 的同一市場輸入產生相同兩軌分數，只有 action label 類別不同。
- MA5／20／60／240、KD/J、MACD、RSI、W%R、BIAS、volume、market、day move、FX、premium 每組都有直接方向測試；反射測試改為斷言 `StockInput` 確實含 `weeklyMa`／`extendedIndicators`／`volumeRatio`。
- 股數事件量還原：無事件 volume 不變、只有現金股利 volume 不變、股票股利依配股比例調整、1:4 分割前 volume ×4 且後段不變；跨事件的相對量不出現假 4–6 倍爆量。
- 台股市場量比排除最新日分母並使用中位數；前一美股科技日必須是 IXIC／SOX 最新共同完成日，缺一、錯日或超過 5 日即 unavailable。
- 新聞只取 72 小時內 TW／US 各最多 3 筆並穩定排序；新聞標題不改變任何分數。
- 追高閘門、`completedChangePercent<0` 即使高分也不得 BUY／ADD、止跌後低接、極端超賣無條件阻擋殺低、高檔至少兩項轉弱才獲利了結；只有 KD 死叉不得觸發減碼。
- API／快照 round-trip／Excel／JSON 同時保留中期舊欄位與短期新欄位；舊快照缺值可讀；通知 transition 只看中期 action。
- active code 註解與畫面不再宣稱擴充指標不評分、MA5 不評分、持有期至兩年或崩壞股不受低檔保護。

回測：

```bash
docker exec asset-business-services curl -fsS -X POST 'http://localhost:8080/internal/backtest/rules' \
  -H 'Content-Type: application/json' \
  -d '{"horizons":[5,20,60,120]}' > /tmp/t291-backtest.json
```

實際 Docker stack 驗收（本專案沒有 dev server）：

```bash
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff
curl -fsS http://localhost:8080/actuator/health
docker exec asset-business-services curl -fsS 'http://localhost:8080/api/trading-radar' \
  -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' > /tmp/t291-radar.json
```

實資料回應須確認：

- `ruleVersion=TW_RULES_V10`；每檔同時有 `shortScore/shortAction` 與中期 `score/action`。
- 標的集合等於該使用者持股與觀察清單的台股聯集，不含其他全庫標的。
- 大盤量能日期與完成日 K 一致；美股日期為 IXIC／SOX 共同完成日；公開資訊只有近 72 小時 TW／US。
- 匯率先以 SQL 證明該使用者 universe 至少一檔外幣債券 ETF 的幣別，在本次 `fxTargetDate` 有精確有效列且五年內有效樣本至少 600 筆；前提成立才要求該檔 `fxPercentile`／`fxAsOfDate` 非空。前提不成立時，合規結果必須是兩欄皆 null 且 risks 揭露「精確完成日匯率不可得」。台幣資產一律為 null；個股展開列可見全部技術指標與相對量。
- 瀏覽器開啟 `/trading-radar` 可在收合列辨識短／中期建議與分歧，並可見大盤量能、美股科技與台美公開資訊。

## 完成報告

（實作者完成後回填：實際修改檔案、單元測試／前端 build／容器驗收輸出、四個 horizon 的回測摘要、實資料抽查、active 註解掃描結果，以及與本計畫的偏差及原因。）
