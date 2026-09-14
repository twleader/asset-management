# [t430] 交易雷達批次讀取失敗不得清空持股與觀察清單

**對應 Requirements:** Requirement 148（交易雷達 browser list 的 owner target 集合在唯讀批次失敗時仍完整回傳）
**前置任務:** t426、t427、t428 已落地 list/detail 分流與 request-scoped batch context。
**Liquibase changeset:** 無；不得新增或修改 table、column、index、migration 或 `db/schema.sql`。

## 背景與根因

現場已確認同一 owner 的 legacy full trading-radar response 能回傳完整決策資料，9090 public read 也有股票；但 browser 的 `GET /api/bff/trading-radar/list` 經 business list path 回 500，前端 catch 後保留初始空陣列，畫面就同時顯示台股、美股 0 檔。這不是持股或股票觀察資料消失。

list path 的 technical preload、list-input batch 與 compact projection 有未被列級 fail-soft 邊界保護的例外出口。任何 optional Redis/DB batch reader 的例外、null 結果或不合法 projection 因而可逃到 generic exception handler，讓一個 input 問題清空整個 owner list。修正必保留 Task 428 的零 N+1、唯讀、exact-pair 和 no-external-I/O 契約；不得用 legacy full、9090/public 或逐檔 fallback 迴避。

## 要做什麼

- [ ] **430.1 owner 集合不可被 batch 縮小。** `getList()` 以既有 owner-scoped positive holding 與 watch-list，依既有 code/market normalize、台／美 supported-market 與台股 `0000` 指數排除規則過濾後的 exact `(market, stockCode)` eligible 聯集作為唯一 response cardinality；pair 重複時持有旗標為 true。每個 eligible pair 都必有一個 list row；只有該 eligible 聯集原本為空時才回空陣列。不可藉由調整 owner filter、把 watch list 排除、改讀 public universe 或從別的 owner 借資料處理故障。

- [ ] **430.2 technical batch phase isolation。** `RadarTechnicalResolver.preload(...)` 對台灣 pair cache、台灣 complete facts 與 non-TW market-local cache 分別隔離 runtime failure/null result，並回傳 immutable、已載入的 unavailable batch snapshot。逐列 `resolveReadOnly` 在此狀態只可使用 local/unavailable technical input；不得觸發 single Redis/DB read、technical cache write、外部／Fubon I/O 或跨市場快取讀取。

- [ ] **430.3 list-input phase isolation。** `TradingRadarListBatchPreloader` 的價格、live quote、NAV、主檔、adjustment、dividend、fundamental、bond/rate、FX、market feature、ETF history 等每個 optional batch phase 都須將 exception/null/缺值轉成該 phase 的既有 unavailable value，並仍為每個 requested exact pair materialize `Entry`。不可用 null context 讓 core 略過 target，不可補 0、不得由同 code 不同 market 或其他 target 取值，也不得回退到 single-target reader。

- [ ] **430.4 row-level terminal boundary 與可觀測性。** `getList()` 的 batch boundary 及 compact `toListStock` projection 不能讓一列的壞 input 終止 stream。若 compact core 或 projection 無法安全產生正常 row，改回同一 target identity、`NO_TRADE`、unavailable 的 list row；其他列完整保留。每個 fallback 以 sanitized warning 記 phase、exception class 和已清理訊息，禁止輸出帳戶、owner id、cookie、authorization header、token、原始 cache/quote payload。既有 generic exception handler、BFF/9090/public route、wire DTO、SSE mapping、full/snapshot/export、規則版本和交易行為均不得改動。

- [ ] **430.5 回歸。** 以至少一台股持股、一美股觀察股與一個兩來源重疊 pair 證明 union、held、exact identity 與非空 list。逐一讓 technical cache/fact/local batch、list-input batch phase 與 `toListStock` projection throw 或 return null，斷言 HTTP/service list 仍包含所有 eligible pairs，受影響 row 為 unavailable/`NO_TRADE`，其餘 row 可照常決策；並以 spy/mock 證明零 per-target repository/Redis fallback、零 Redis write、零外部 I/O。保留 Task 428 的 list/full visible scalar/sort regression、legacy full path 和 9090 public contract regression。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml -Dtest=TradingRadarServiceOwnerScopeTest,RadarTechnicalResolverNonTwLocalTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
bash .claude/hooks/arch-review-pass.sh
```

完成 feature 驗收與合併後，依 `run-stack` 從乾淨且追平的 main worktree 重建/recreate **only** `business-services`。以現有 authenticated browser session 驗證初始 BFF list HTTP 200、台／美 list row pairs 等於登入 owner 經既有 normalize、supported-market 與台股 `0000` 排除後的持股＋股票觀察 eligible 聯集，並確認 legacy full 與 9090 public read contract 不變。若沒有可用 browser session，應以既有 owner-header internal path 作部署診斷，並將 browser acceptance 列為 pending，不得以 9090 取代。

## 完成報告

（實作者完成後回填：根因 phase、程式檔、focused/full test、feature/main Docker image、owner-scoped list response evidence、legacy/public regression，以及 authenticated browser acceptance 或 pending 原因。）
