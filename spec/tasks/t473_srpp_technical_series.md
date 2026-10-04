# Task 473：SRPP 逐日價量與技術序列 API

**對應 Requirements:** Requirement 175（資產系統計算並回傳 OBV 所需逐日價量及其他完成日技術指標）
**前置任務:** t472
**Liquibase changeset:** v1.142.0-srpp-technical-series-error-log-catalog.sql（僅 insert-only operation catalog，不改 schema）

## 背景

既有 `GET /api/public/srpp/completed-technicals` 可按 1–40 檔取得截至 asOf 的完成日技術摘要，但未回傳逐日收盤與成交量，因此 SRPP 無法從回應獨立重播 OBV20，也不能觀察其近期量能變化。每日 quote 的 `volume` 只是單日值。已保存 `stock_price_history` 有逐日 OHLCV；權息／分割還原後的 volume 才與摘要 OBV20 的 close 同價量基準。

## 要做什麼

- [ ] 473.1 新增 global no-tenant、exact、唯讀 `GET /api/public/srpp/technical-series`。只接受唯一的 `market=台股|美股`、`stockCode`（1–12 英數字、點或連字號）、嚴格 ISO `asOf`（早於市場本地今日）和可選整數 `bars=21..250`（預設 60）；拒絕未知／重複 query、body 與其他 method。BFF 只用 no-tenant business client 轉發至 `/api/market-data/srpp-technical-series`，不得讀 owner、外部行情、券商或寫入。
- [ ] 473.2 business 將原完成日單檔摘要的兩年有界原始 OHLCV 查詢、active 權息事件讀取與整段一次還原封裝為同一載入結果；批次摘要語意與公式版本不變。缺精確 asOf 時，`summary.status=UNAVAILABLE`、`additionalIndicators=null`、`dailyBars=[]`。存在 asOf 時，回 `formulaVersion=SRPP_TECHNICAL_SERIES_V1`、請求身分、`requestedBars`、`volumeUnit=SOURCE_UNIT_UNVERIFIED`、原 `SRPP_DAILY_OHLCV_V1` 的 `summary`、及最後 `min(bars, available)` 筆升冪 `dailyBars`，每列明列日期、調整後 OHLC、原始成交量 `rawVolume`、實際用來計算 OBV 的調整後 `volume`、原始 `closeSource`、資產端算好的滾動 `obv20Change`。逐列 OBV20 用最近 21 筆有效 close 與後 20 筆有效 volume，不足或無效時為 null；尾列與 summary 值相等。成交量沿用來源保存單位且未驗證跨標的一致，不得跨標的比較絕對量；不得用 quote volume 或縮短 20 期，null 不補零。
- [ ] 473.3 用同一份調整後完成日序列呼叫既有 `TechnicalIndicatorService.computeFromSeries` 純核心，輸出 additionalIndicators：MA10/20/60/240、K/D/前一期 K/D、J9/K3D2/RSV、EMA12/26、DIF/MACD/OSC、RSI5/10、BIAS10/20/B10B20、W%R9。保留 null 暖機與既有精度，文件明示這不是含盤中價的交易雷達行動資格；摘要仍包含 MA5、RSI14、MACD(12,26,9)、布林20、ADX14±DI、OBV20、量比20、近一年位置。
- [ ] 473.4 BFF 驗證上游結構、身分、asOf 尾列、長度、升冪日期、非負成交量與 nullable 指標型別，矛盾資料回 502。將 route manifest 更新為 19 路（18 GET、1 POST）：Nginx 9090、Tailscale Serve 同名 exact path、Frontend deny、BFF security、API log Java/DB catalog、OpenAPI 完整 schema/response/examples、生成 Markdown 與 SRPP 鏡像、對應路由測試。沒有任意路徑、交易指令、vendor I/O 或 owner 讀取。
- [ ] 473.5 SRPP 保存回應並驗證身分、日期、逐日量與最後 21 筆重播 OBV20；新端點只在批次摘要之後、需要逐日證據的個股按需呼叫。報告記錄是否支持／反證買賣時點，與原硬閘門及相依指標去重複；輸入摘要納入報告快照雜湊。短歷史只顯示可算欄位。

## 驗證

`bash scripts/spec-check.sh`、backend/BFF 單元與全套測試、OpenAPI renderer/check、gateway/Serve/frontend route parity；合成資料驗證 OBV20 的 21 筆方向與量、短歷史、缺量、缺 asOf、還原價量基準及不變的批次摘要。由 feature worktree 重建並 recreate business、BFF、gateway、frontend，9090 以唯讀請求檢查實際短歷史和長歷史股票、錯誤參數、無方法旁路，SRPP validator 對真實回應重播 OBV。合併後從 main 重建同四個服務，核對來源與 Tailnet HTTPS，才可報部署完成。此 changeset 只新增 catalog row；確認 `db/schema.sql` 與運行中 schema 逐位元一致。

## 完成報告

待驗收後填入測試、部署及主分支提交證據。
