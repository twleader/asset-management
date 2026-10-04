# [t475] 雷達核對已存富邦 SMA20 並顯示買賣參考區間

**對應 Requirements:** Requirement 177（雷達以已存官方 SMA 核對判斷並顯示參考價格帶）  
**前置任務:** t474  
**Liquibase changeset:** 無

## 背景

雷達目前 23 個因子由完成日 `stock_price_history` 本地序列計算。富邦指標即使已保存在 `stock_technical_indicator`，17-profile capture 超過 100 秒、或 `FubonRadarCompatibilityManifest` 沒有核准語意時，不會進入評分。使用者需要知道實際連結，並要求重複指標不重複計算及買賣價格參考區間。真實回補仍在進行，不可為部署中斷 `external-materials-service`。

## 要做什麼

- [ ] 475.1 在唯讀 port/repository 依每檔精確完成日查 `FUBON_SDK`、台股、`D`、`sma_d_20` immutable 事實；清單先從批次價格／日曆選出每檔完成日，再以每批最多 30 檔分批查全部台股標的，不可截斷；單股一次。固定參數 `{"timeframe":"D","period":20}`、來源日、有限正數與 identity，DB 故障返回不可用。不得打富邦 SDK、寫資料、Redis 或快照。以官方原始日 K fixture 固定 0050／0056 各 241 列、006208 968 列 SMA20 1e-8 內重播證據，不能外推至還原價或其他指標。
- [ ] 475.2 純 verifier 僅於台股、完成價非盤中、最近 20 根完成日 raw `indicatorSeriesRows` 與 `Prepared.adjustedRowsDesc` 日期/close 逐筆相同、來源日精確相同、官方及本地值均為有限正數時可比較；整體 `distributionAdjusted=true` 但最近 20 根相同仍可比較。先用新到舊 double 累加並 setScale(2, HALF_UP) 驗證 local MA20，不符優先 `NOT_COMPARABLE`，不得觸發 gate；通過後才以這 20 根 raw close 的 DECIMAL128 未捨入平均和官方 SMA20 比，絕對差 >0.00000001 為 `CONFLICT`，其餘為 `CONFIRMED`。測試兩項同時不符仍為 `NOT_COMPARABLE`。其他缺口為 `UNAVAILABLE`／`NOT_COMPARABLE` 並給固定原因，不直接以 1e-8 比已捨入 MA20。相符只記來源／日期/差值，不增分、不提高 confidence；衝突只在既有 evidence gate 之後把三軌買進／加碼／試單、減碼／出場候選各降為持有者 HOLD／非持有者 WATCH，保留原分數、candidate action、每軌診斷。相同 MA20 不得另建 factor；KD/W%R、BIAS/B10B20、BB/MA20 同源組同理不重複加權。缺值不能當零或產生衝突。將 `ACTION_POLICY_VERSION` 升至 `EVIDENCE_GATE_V2`，並測試通知首輪只建立新 baseline。
- [ ] 475.3 純價格帶計算只取同一完成日有效本地 BB20/2，且最近 20 根 raw/adjusted 日價逐筆相同：買 `[lower,middle]`、賣 `[middle,upper]`；價格僅供展示，正數下界 setScale(2, DOWN)、上界 setScale(2, UP)，不宣稱符合委託 tick。原始 `lower>0`，且捨入後四個展示邊界均須 >0，否則整個 child=null；加入負下界及小於 0.01 被捨入成零的測試。BUY 對應 BUY_CANDIDATE／ADD_CANDIDATE／TRIAL_BUY；SELL 對應 REDUCE_CANDIDATE／EXIT_CANDIDATE；其餘含 AVOID 對應 NONE。非正價、日期不符、無序軌道、NO_TRADE 回 null。不把區間當停損、預測、委託價格或第二因子。
- [ ] 475.4 `StockDecision` 追加官方 SMA20 核對及 nullable `priceReference` typed detail；同步 BFF strict decoder、9090 OpenAPI schema/example 與重產的兩份 Swagger Markdown，將 info.version 1.18.0 升至 1.19.0 並更新釘版契約測試、前端展開明細。舊 snapshot 缺新欄應能讀，清單保持輕量且可見最終動作；不增 9090 route，不接任何下單路徑。
- [ ] 475.5 後端／BFF／前端測試涵蓋相符、衝突、缺值、調整基準、盤中、異日、三軌單次降級、價格帶有效／無效、舊 snapshot、strict JSON；最後在 feature worktree 依 `run-stack` 僅重建 `business-services`、`bff`、`frontend`（不動進行中的 `external-materials-service`），安全 GET 真實單股並核對輸出；通過後再依 `commit-merge-push` no-ff 落 main。未做持出樣本回測前不得宣稱建議準確率提高。

## 驗證

```bash
bash scripts/spec-check.sh
git diff --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*Radar*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*Radar*' test
cd frontend && npm run build
```

依 `.agents/skills/run-stack/SKILL.md` 在實際 Compose project 重建、強制重建受影響容器並等待 healthy；以 `GET http://127.0.0.1:9090/api/public/trading-radar/stock?stockCode=006208&market=台股` 查 typed child 與舊欄位，以唯讀 SQL 核對同日富邦事實，確認未中斷回補工作。不得呼叫券商交易 API。

## 完成報告

2026-10-04 完成：`JdbcRadarTechnicalFactRepository`／`RadarTechnicalFactPort` 增加精確完成日、每批最多 30 檔的唯讀官方 SMA20 查詢；`RadarOfficialSma20Verifier` 先核本地 20 根投影再核官方 raw SMA，`RadarOfficialSma20Gate` 只在可比較的衝突保守降級三軌最終動作，不新增分數因子；`RadarPriceReference` 以同價基完成日 BB20/2 輸出有界正值區間。`TradingRadarService` 五入口共用該投影；DTO、BFF strict decoder、OpenAPI 1.19.0、兩份 Swagger Markdown、前端展開明細及通知 action-policy V2 同步。

固定 CSV 夾具與 `scripts/verify-radar-sma20-fixture.py` 重播已存富邦原始日 K／SMA20：0050、0056、006208 分別 241、241、968 列，最大絕對差分別為 9e-14、4.5e-14、1.5e-13。Java 21 後端非 Redis 雷達測試 565 項、BFF 雷達測試 146 項、前端測試 95 項與展開流程 9 項、前端建置、十九路 OpenAPI 契約、`spec-check`、`git diff --check` 均通過。完整後端雷達測試另有 3 項既有 Redis Testcontainers 測試在 localhost 動態埠收到 HTTP 首位 `H` 而發生 `RedisProtocolException`；排除該環境測試後其餘雷達測試全綠。

依 `run-stack` 從 feature 重建並 force-recreate business-services、bff、frontend；business/BFF healthy、frontend HTTP 200，三服務 effective 環境／掛載／port 與 main 設定相同。9090 真實單股：006208 與 0050 的富邦 SMA20 均 `CONFIRMED`、006208 有同日價格參考區間；2308 無官方同日事實時 `UNAVAILABLE` 且 `gateApplied=false`。富邦回補工作終態 `PARTIAL / UPSTREAM_UNAVAILABLE`，24 檔中完成 17 檔、23 視窗中 22 視窗可用、已寫或核對 51,773 筆；剩餘日期不稱補齊。external-materials-service／fubon-broker-service 的 image、startedAt 未變；單股安全 GET 前後事實表均 51,773 筆，Redis 監看未見該 GET 寫入。未做持出樣本回測，不主張預測準確率提升。
