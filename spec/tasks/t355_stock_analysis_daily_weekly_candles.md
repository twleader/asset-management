# [t355] 股票分析價格圖新增日 K／週 K

**對應 Requirements:** Requirement 92（股票分析價格圖新增日 K／週 K）
**前置任務:** 無（可與 t348 同批交付，但資料契約互不依賴）
**Liquibase changeset:** 無

## 背景

跨頁共用 `StockAnalysisDialog` 的上方價格圖目前只有收盤折線（另有「當日」分鐘線），無法直接看每日或每週開、高、低、收。business `/api/market-data/history/stock` 已回 `StockPriceHistory` 的 O/H/L/C，但 BFF `PricePointDto` 只接 `tradingDate/closePrice`，前端因此拿不到 candle 所需欄位。

本任務擴充既有 `chart-series`，由 BFF 驗證每日 OHLC 並聚合 ISO 週 K；frontend 只切換 ECharts series，不打第二支 API、不自行算週 K。週 K 下方的技術指標仍是既有**日線公式**，由 BFF 取每週最後日線值，不冒充 weekly 重算指標。

Requirement 89／Task 350 已由主線「台股 Redis 最新價快取新鮮度」功能使用；本功能併入主線時固定續編為 Requirement 92／Task 355。

## 要做什麼

### BFF：日 OHLC 與週 frame

- [ ] 355.1 `PricePointDto` 擴為 `(tradingDate, openPrice, highPrice, lowPrice, closePrice)`，欄名逐字對應 business `StockPriceHistory` JSON。`StockAnalysisChartBffController` 仍只並行抓既有 history＋indicator 兩支 business API，不新增 roundtrip。
- [ ] 355.2 `ChartSeriesDto` 保留既有 top-level `dates/prices/ma5...wr9/latest` 逐欄相容（line consumer 不變），新增自足的 `DailyFrame daily` 與 `WeeklyFrame weekly`。兩個 frame 都包含：
  - `dates, opens, highs, lows, closes`
  - `ma5, ma20, ma60, ma240`
  - `k, d, j9, k3d2, rsv`
  - `ema12, ema26, dif, macd, osc`
  - `rsi5, rsi10, bias10, bias20, b10b20, wr9`
  - `currentClose, previousClose`
  - `latest`（沿用既有 `Latest` 實際欄位：MA 只有 current；K/D/J9/K3D2/RSV、EMA12/26、DIF、MACD、RSI5/10、BIAS10/20、B10B20、WR9 有 current＋既有 prev；OSC 只有 current且不進 legend）。
- [ ] 355.3 `ChartSeriesAligner.align(prices,indicators,requestedStart)` 保留 top-level line 聯集語意，另以 `validCandle` 建 `daily`：O/H/L/C 全非 null且 >0、`H≥max(O,C)`、`L≤min(O,C)`、`H≥L` 才合法。invalid 時 daily O/H/L/C 全 null；BFF 找最後 valid index，daily 的 dates/OHLC/MA/所有 indicator 全截到該 index，中段 invalid 可留 null，尾端 close-only／indicator-only 不進 daily。`daily.currentClose/previousClose` 取最後兩根 valid candle；daily latest current 取 cutoff 同欄，既有 prev 欄各自向前找最後 non-null。若無任何 valid candle，daily/weekly 仍回非 null 空 record（全部 lists=[]、close/prev/latest=null）。top-level prices 保留 close，禁止補造 OHLC。
- [ ] 355.4 Controller 將 request `start` 解析為 `requestedStart` 傳給 aligner。週聚合先只留 date≥requestedStart 的 price rows，再以 ISO `weekBasedYear/weekOfWeekBasedYear` 分組。requestedStart 非星期一時，與它同 ISO key 的第一組整週省略；start 星期一但休市時，週二起合法短週可保留。其餘每週只有所有 price rows valid 才輸出；合法週 open=首日、high=max、low=min、close=末日、date=實際末日。1–4 日假期短週合法，跨年週不得用 calendar year 錯拆。
- [ ] 355.5 對每個合法週與每一個 indicator／MA 欄，只從 `[firstPriceDate, weekly.date]` 取最後 non-null 日線值；同週但晚於 weekly.date 的 indicator-only 值排除，不重算 weekly 指標。`weekly.currentClose/previousClose` 取最後兩根 weekly candle；weekly latest current/prev 取最後／前一 weekly row 的實際 `Latest` 欄位，MA 無 prev、OSC 無 prev且不進 legend。
- [ ] 355.6 top-level `latest` 既有語意不變。daily／weekly active-frame 分支只讀對應 frame 的 `currentClose/previousClose/latest`，不得從 candle-frame arrays 計算 cutoff、date join、last-non-null 或 prev；這不禁止既有 intraday 昨收搜尋、line legend `lastNonNull`、可視 Y 軸 `.slice(lo,hi+1)`。

### frontend：三圖型切換

- [ ] 355.7 `StockAnalysisDialog.vue` 控制列新增「圖型」按鈕組：`走勢線(line)`、`日 K(daily-candle)`、`週 K(weekly-candle)`，預設 line。適用台股、美股、英股與 `0000`；不新增 tab／popup。
- [ ] 355.8 `rangeOptions` 在 K 模式排除 `當日`。從 `months=0` 切 K 時設 months=12；使用者選「當日」時模式為 line。切 mode 或 months 都清 `zoomPct`。切 K 回 line 保留目前非零期間，不強制跳當日。
- [ ] 355.9 tree-shaking import/use 加 `CandlestickChart`。active frame：line 非當日用完整 top-level、daily K 直接用 `series.daily`、weekly K 直接用 `series.weekly`、line 當日維持 intraday。candlestick value 精確 `[open,close,low,high]`；frontend 只做 ECharts 呈現映射。
- [ ] 355.10 daily／weekly K 上圖用 candlestick 取代股價 line/area；MA、成本水平線、下方 pane、axisPointer、dataZoom 全保留。只禁止 K active-frame 分支從 candle-frame arrays 推導 cutoff/valid index/join/prev；tooltip/endLabel/x 軸直接以 BFF frame 為準。既有 intraday/line/可視 Y 軸邏輯保留。空 frame 顯示局部「無完整 OHLC 資料」、截止空白，不影響 line。weekly 顯示週末取樣提示。
- [ ] 355.11 candle 配色：close≥open 的 `color/borderColor=#dc2626`，close<open 的 `color0/borderColor0=#16a34a`。tooltip 對股價 candle 拆成「開／高／低／收」四行；MA、成本、下方指標維持 scalar 列。不得直接顯示 `[O,C,L,H]` array。
- [ ] 355.12 `xAxis.boundaryGap` candle=true、line=false。default zoom：line／daily K `want=max(20,round(months×21))`；weekly K `want=max(4,ceil(months×52/12))`，都依 active frame 總筆數轉 start%。slider／inside 同步上下 pane。
- [ ] 355.13 極值與截止：line 以 visible close 找 max/min；K 模式分別以 visible high/low 找 marker。daily K 資料截止為最後 valid daily candle date，weekly K 為 weekly 最後 date，不能沿用 close-only live 或 indicator-only 尾日。marker 日期亦取 candle date。
- [ ] 355.14 legend：line 沿用 top-level latest；daily／weekly 股價讀 frame `currentClose/previousClose`，MA／indicator 讀 frame `latest`。只對 record 實際存在 prev 的指標畫箭頭，MA 與 OSC 不造 prev。不得從 top-level 或 frame arrays 尾端自行推導替代值。

### 測試與邊界

- [ ] 355.15 擴充 `ChartSeriesAlignerTest`：top-level line 相容；daily/weekly 等長；valid、中段 invalid、尾端 close-only/indicator-only；daily 全 frame cutoff、currentClose/previousClose/Latest；一般週、短週、ISO 跨年、壞週、D＋同週 D+1 indicator-only；requestedStart 週三省略首週且次週正常、週一休市短週；以及 empty prices、全 close-only、全 invalid 都回兩個非 null 空 frame。既有 union-date/top-level latest 不回歸。
- [ ] 355.16 `stockAnalysisDialog.contract.test.js` 加入 `npm test`，source assert K 分支直接使用 series.daily/weekly/currentClose/previousClose/latest、空 frame 局部訊息、三標籤、Candlestick/value order、紅綠、當日互斥、提示與 tooltip。禁止檢查只限 K 分支，測試明文 allowlist 既有 intraday 昨收搜尋、line lastNonNull、可視 Y 軸 slice；不引入 runner，production build須通過。
- [ ] 355.17 不新增 backend/external endpoint、DB／Liquibase、月 K、分鐘 K、Heikin-Ashi、成交量副圖或 weekly 技術指標公式；不改 history來源、MA/KD 算法或 line 模式既有輸出。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm test
/Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build

# 回 repo root；若與 t348 同批交付，照共同變更一次完成四服務重建即可
docker compose -p asset-management build --no-cache bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
curl -sI http://localhost/ | head -1
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

瀏覽器驗收：

- [ ] `00697B` 與一檔一般股票可在走勢線／日 K／週 K 間切換；抽一根日 K 對照 business history O/H/L/C，抽一週手算週開高低收，tooltip 一致。
- [ ] 週 K 畫面顯示日線指標週末取樣提示，MA／下方指標／成本線仍在；期間按鈕與 dataZoom 合理，1 月至少約 4 根。
- [ ] 從當日切 K 自動到 1 年，K 模式沒有當日；切回 line 再選當日仍是原分時折線。
- [ ] `0000` 可切日／週 K；美股／英股無錯誤。frontend root 200、BFF actuator UP、容器 healthy，驗收 image SHA 未被其他 worktree 覆蓋。

## 完成報告

**完成日期：** 待實作後填寫
**變更檔案：** 待實作後逐檔列出
**測試結果：** 待填寫（BFF tests/failures/errors/skipped、frontend build）
**Docker／瀏覽器驗收：** 待填寫（00697B／一般股／0000、日 K／週 K 手算、容器與 image SHA）
**與原規格偏差：** 待填寫；無則寫「無」
