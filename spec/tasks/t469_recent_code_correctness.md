# [t469] 近期 SRPP 行情與富邦回補正確性修補

**對應 Requirements:** Requirement 171／Task 467；Task 466 既有歷史回補契約
**Liquibase changeset:** 無

## 背景

2026-10-01 的已合併程式碼審查發現：SRPP market-facts 直接沿用可保留 24 小時的 Redis `LIVE` 狀態，跨日或盤前可能把舊報價標為即時；`VERIFIED_CLOSE` 快取的 `updatedAt` 是處理時間，卻被當成來源 `quoteDataAsOf`；富邦歷史日 K 已逐列獨立提交，後續列或投影失敗時 attempt 統計卻記為零；9090 OpenAPI 雷達範例來源 ID 與實作不同。本任務修正既有唯讀與稽核契約，不新增路由、交易動作或外部取價。

## Acceptance Criteria

- [x] 469.1 `market-facts` 的 `LIVE` 必須通過標的市場交易日期與可驗證的行情觀測時間檢查；台股、美股、英股分別使用 `Asia/Taipei`、`America/New_York`、`Europe/London` 的市場交易日期與交易時段；未知市場直接 `UNAVAILABLE`，不得套用預設時區。交易日只用既有 cache-only known calendar，未知日曆不得觸發 proxy/refresh。來源觀測時間（Redis `updatedAt` 為台北牆鐘）轉成 instant 後，僅 `0 <= now - observedAt <= 5 分鐘` 且當下與來源時點均在對應交易時段內才可 `LIVE`；超過 5 分鐘降 `STALE`，盤前、跨日、休市日、未來時間或時間缺失不得沿用 Redis 字面 `LIVE`。無法證明即時時降為 `STALE` 或 `UNAVAILABLE`，但不得觸發 provider、Redis refresh 或 DB 寫入。`CLOSE_FALLBACK` 僅表示已核實的收盤資料，不能因 cache processing time 誤升為 LIVE；來源種類或交易日期無法證明收盤的項目降 `STALE` 或 `UNAVAILABLE`。測試覆蓋跨日盤前、同日新鮮、過期、缺少時間、5 分鐘等號邊界、日曆未知、英股時區與台北跨日而美股仍開盤。
- [x] 469.2 `VERIFIED_CLOSE`、`CLOSE`、`CLOSE_FALLBACK`、`PREVIOUS_CLOSE` 等可核實的收盤來源僅有 `tradingDate` 時，`quoteDataAsOf` 回該 ISO 日期，不能用 cache `updatedAt` 或補造午夜／收盤時分秒。`LIVE` 有可信觀測時間才用含時區的 ISO date-time；雷達來源若僅有 `asOfDate` 亦保留 date 精度。market sourceVector 的 `dataAsOf` 保留其各項來源最粗精度並與 item 一致；source revision／body hash／`marketCaptureId` 仍按既有 JCS 規則由實際回應計算，不混入假精確時間。OpenAPI 對受影響的 **market-facts 專用** `quoteDataAsOf`、`radarDataAsOf`、sourceVector `dataAsOf` 明定 ISO date／含時區 date-time 精度聯集與 nullable 條件；BFF 僅在 market-facts 驗證路徑接受合法日期或時間，拒絕無效或無時區字串。共用 `SrppSourceRevision` 與 context／calculations 既有 date-time 契約不得放寬；必要時分出 market source revision schema／validator 分支。相關 contract tests 更新。兩份 Swagger Markdown 由 YAML 決定性重產且 byte-identical。
- [x] 469.3 富邦歷史日 K attempt 在第 N 筆 durable persist、投影或後續處理失敗時，FAILED receipt 的 `providerRows` 保留完整且已驗證的供應商 response row count（沒有完整有效 response 時為 0），`inserted`／`unchanged`／`conflicts` 保留失敗前已提交或確定處理的逐列計數與安全錯誤碼；不得把已提交列當成 rollback。重跑同一 window 時既有 immutable facts 仍走 UNCHANGED，但 campaign／attempt 報告要能區分歷次實際寫入與重跑未變；不得觸發券商下單或修改既有交易日／quota gate。測試包含第二筆失敗與已寫入後投影失敗。
- [x] 469.4 `TRADING_RADAR_SNAPSHOT` 是實作的唯一 radar source ID；OpenAPI 範例與兩份產生文件一致，跑原有 9090 契約檢查、SRPP/backend/BFF/Fubon focused tests 與 stack 實際讀取驗證。文件範例中的時間／來源須符合 schema，不引入 9090 route 變更。

## 驗收與限制

先執行 `scripts/spec-check.sh` 和獨立 spec-review，再記錄 spec-review-pass；程式修改後由獨立架構審查核對以上來源與 BFF 契約，依 run-stack 重建實際受影響的 image 並確認 9090 read-only 路由。備份 Task468 在另一 worktree 進行，本任務不得修改其尚未合併的規格或備份檔案。

2026-10-01 驗收：規格對抗式審查第二輪無 finding，`spec-check` 為 BLOCK 0／CHECK 0；獨立程式審查確認四項原問題已修，並補正雷達範例日期精度。Java 21 全套測試：backend 2,545 項（1 skipped）、BFF 589 項、external-materials 804 項，皆無失敗；富邦 Python gateway 12 項通過。9090 OpenAPI parity／範例檢查與兩份 Swagger byte-identical 檢查通過。以 feature worktree 重建並 recreate business-services、BFF、external-materials-service，三者 healthy，9090 quotes／market-index 回傳有效 JSON；SRPP 四條安全 GET 均符合 Problem Details 的 fail-closed 契約。現場缺已註冊 policy bundle／context，故無法在不寫入狀態下取得 `market-facts` 200 回應；200 內容由專項測試與 schema 驗證，runtime 成功內容仍待資料可用時再讀回。備份 Task468 未部署。
