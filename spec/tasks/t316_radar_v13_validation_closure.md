# [t316] 交易雷達 Treasury freshness、完整回歸與真實 holdout 結案

**對應 Requirements:** Requirement 65（資料時效與樣本外 gate 決定是否發布，拒絕亦須可解釋）
**前置任務:** t314（joint-fold/cost integrity）、t315（valuation provenance surfaces）
**Liquibase changeset:** 無

## 背景

Treasury production constructor 已注入 `MarketDataService` 並以美股 completed sessions 判 freshness，但現有測試主要只驗完整 batch 與單 tenor manifest，沒有直接證明長假、UNKNOWN calendar、超過三個 session 與 V12 production fallback。V13 停止點也只跑過 backend 127 個、external 20 個定向測試，尚未完成全模組回歸、frontend build、由本 feature image recreate 的實機服務、台美 API、逐分量估值輸出或真實 70/30＋walk-forward holdout。

本任務的完成判準是「取得可信結論」，不是「一定發布 V13」。真實 report 若 `REJECTED` 或 `INSUFFICIENT`，production 保留 `TW_RULES_V12` 就是正確結案；不得修改資料、縮小 holdout、降低 n/codes/fold gate 或改 candidate grid 來湊 promotion。

## 要做什麼

- [ ] **316.1 Treasury 權威日曆 freshness 測試。** 以 package constructor 注入 mock `MarketDataService`，直接驗證：
  - decision 在美東收盤前時，latest completed session 從前一日往回找；收盤後可含當日。
  - 週末與連續休市日回 `Optional.of(false)`（已知休市），不增加 session lag；跨長假但 curve 仍是最新 completed session 時 fresh。
  - curve 到 expected session 之間恰 3 個 completed sessions 仍 fresh，第 4 個為 stale。
  - `curveDate > expected completed session` 必須 fail closed 為 stale，reason 含穩定 code `FUTURE_CURVE_DATE`；不得用 session lag 0 冒充 fresh，且仍保留原 batch/provider/manifest provenance。
  - 尋找 expected session 或計算 lag 途中任一 `Optional.empty()` 立即回 stale reason，字串含穩定 code `UNKNOWN_CALENDAR`；production 路徑不得使用 weekday fallback。
  - complete batch 的四 tenor manifest/batch provenance 即使 stale 仍保留，stale 不得抹成「查無 batch」。

- [ ] **316.2 Treasury → evidence → V12 fallback 整合測試。** 對 strict BOND profile 構造 fresh、stale、UNKNOWN 與 missing batch：fresh complete context 可為 AVAILABLE，但未 promoted beta 的 riskUnit 仍 null；stale/UNKNOWN 映成 `ASSET_SPECIFIC/bond_rate=STALE` 並保留 reason；missing batch 為 MISSING。各情境的 V12 **opportunity score、`RuleParameters` 與 ruleVersion** 不得被 Treasury beta 改寫；但最終 action 仍須套 Requirement 65／308.6 的不可變 evidence safety layer，stale/UNKNOWN/missing 使 BUY／ADD／TRIAL_BUY 類動作只向 WATCH/HOLD 保守降級，不得把「V12 fallback」當成繞過 safety gate。`TradingRadarRuleEngine.RULE_VERSION`、DTO fallback 與通知 baseline 仍是 `TW_RULES_V12`。registry 缺 key、任一 required horizon rejected/insufficient 時也必須 resolve `V12_DEFAULT`。

- [ ] **316.3 全部本地回歸。** 在同一 commit 前依序執行 backend（含 ByteBuddy flag）、external-materials-service、BFF 全測試與 frontend `npm test`＋production build。若失敗，先判斷是否由本變更造成；本變更造成者修到通過，既有／環境失敗則保存完整命令、首個根因與受影響範圍，不得用定向測試冒充 full regression。

- [ ] **316.4 由本 worktree 重建實機 stack。** 不開 dev server；從本 feature worktree rebuild 並 force-recreate `business-services` 與 `frontend`，若 external/BFF source 未改可不 build。重建前、重建後及最終結論前都記錄 `asset-business-services`／`asset-frontend` 的 running image SHA、container start time、Compose project，並以 `docker compose -p asset-management images -q <service>` 比對當下 tag 指向；任何一次不一致都不得沿用先前證據。business recreate 後必須 restart BFF，待 healthy，再檢查該次 restart 以後的 BFF log 無 `Connection refused|500 Server Error`。另以 jar class/list 或前端中文字面值證明容器產物確實含本輪變更，不能只憑 health 下結論。驗證期間若 image ID 被另一 worktree 改寫，重新 build/recreate 受影響服務後再驗證。

- [ ] **316.5 實機資料面抽查。** 優先使用 in-app browser 已登入 session 開啟 `/trading-radar`，並在同一 session 呼叫 `GET /api/bff/trading-radar`；若環境沒有可用 session，才由 business container 呼叫 `GET /api/trading-radar`，顯式帶入由本機既有使用者解析出的 `X-User-Id`／`X-User-Role`／`X-User-Status`，不得硬編或輸出個資，且完成報告須標示未涵蓋 BFF session filter 的限制。抽查台股與美股交易雷達：
  - `ruleVersion=TW_RULES_V12`，除非 316.7 的全部 gate 實際通過且已另行核准發布；
  - 至少一檔適用個股的 PE/PB/殖利率逐 component status/provider/asOf/source URL 不串線，缺漏時有 reason；
  - 至少一檔台灣掛牌外幣債與一檔美股債的 Treasury batch/provider/curveDate/staleReason/riskUnit；
  - xlsx 與 JSON 同一快照的 18 個 valuation provenance 欄一致；
  - frontend 首頁與交易雷達頁可載入，瀏覽器 console 無本變更造成的 error。

- [ ] **316.6 真實 70/30＋5-fold holdout。** 先在 DB 執行只讀 preflight，逐市場輸出 `min(trading_date)`、`max(trading_date)`、distinct codes 與每檔日期涵蓋；共同期間固定為兩市場 market-min 的較晚者至 market-max 的較早者，若起訖不存在或反轉即 `INSUFFICIENT`。在執行前把固定 `from/to`、universe 模式與 request SHA-256 寫入完成報告；不得看結果後移動。從 business container 呼叫 `POST /internal/backtest/rules`，request 必須實際帶入 `from`、`to`、`markets=[台股,美股]`、`horizons=[5,20,60,120]`、`calibrationRatio=0.70`、`walkForwardFolds=5`、`includeCloseFallbackSensitivity=false`。第一選擇是省略 `codes`，使用服務按市場解析的完整 universe；不得只挑有利標的。若資源限制必須 bounded，須先以 deterministic sorted list 固定 codes，並用同一個 preflight 分類每個 code 的 `(market,instrumentKind,productionProfile,track)`；每個欲判 promotion 的 exact production key 都須逐 required horizon 達一般 8 codes 或 BOND/profile-specific 3 codes，否則該 key 在執行前即標 `INSUFFICIENT`。完成報告明示 bounded request 不是全市場結論，且保存 exact request JSON，不得以混合「8 codes/market」冒充任一 exact key 已達 gate。
  - 保存每個 production key 的 required horizons、holdout n/codes、valid/passing folds、catastrophic fold、candidate ID、parameter snapshot、joint train/sigma/purge、status/reason。
  - close sensitivity、全期間統計、單一 horizon 或另一 profile 不得補 promotion 證據。
  - timeout/OOM/資料不足是 `INSUFFICIENT`，不可縮短 horizon、降低 gate 或刪除失敗市場後重稱通過。

- [ ] **316.7 發布判定與完成狀態。** 依 report 原樣分三類：
  - `REJECTED`：樣本足夠但 practical/consistency gate 未通過；保留 V12。
  - `INSUFFICIENT`：任一 required horizon、joint fold、sigma、n/codes 或 runtime evidence 不足；保留 V12。
  - `PROMOTED_CANDIDATE`：所有 required horizons/holdout/folds 通過；本任務仍不自動寫 production registry或改 `RULE_VERSION`，先輸出 exact key＋完整 parameter snapshot，因 production 發布會改動每日建議與通知，必須以另一次明確核准執行。

  最終完成報告逐項列出已完成與無法完成工作。不得把 focused tests、report-local registry count 或 `productionPromoted=false` 說成 production V13 已發布。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test)
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)

docker inspect asset-business-services --format '{{index .Config.Labels "com.docker.compose.project"}} {{.Image}} {{.State.StartedAt}}'
docker inspect asset-frontend --format '{{index .Config.Labels "com.docker.compose.project"}} {{.Image}} {{.State.StartedAt}}'
docker compose -p asset-management images -q business-services frontend
docker compose -p asset-management build business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff
docker compose -p asset-management ps
# business-services 未啟用 actuator；以真實交易雷達 read path 驗活，OWNER_ID 只在本機解析且不寫入報告。
docker compose -p asset-management exec -T business-services curl -fsS \
  -H 'X-User-Id: <OWNER_ID>' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  http://localhost:8080/api/trading-radar
# host 8080 是 BFF，只有這裡的 actuator 是有效 health probe。
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:80/

# 記錄 BFF restart 的 UTC 起點後再查該時點以後的 log；任何命中都必須先釐清並重驗。
docker logs asset-bff --since '<BFF_RESTART_UTC>' 2>&1 | grep -E 'Connection refused|500 Server Error'

# DB preflight：先固定共同 from/to 與完整 universe coverage，完成報告保留輸出摘要。
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"SELECT market, min(trading_date), max(trading_date), count(DISTINCT stock_code) FROM stock_price_history WHERE market IN ('台股','美股') GROUP BY market ORDER BY market;\""
docker compose -p asset-management exec -T postgres sh -c \
  "psql -U \"\$POSTGRES_USER\" -d \"\$POSTGRES_DB\" -v ON_ERROR_STOP=1 -c \"SELECT market, stock_code, min(trading_date), max(trading_date), count(*) FROM stock_price_history WHERE market IN ('台股','美股') GROUP BY market, stock_code ORDER BY market, stock_code;\""

docker compose -p asset-management exec -T business-services sh -c \
  'wget -qO- --header="Content-Type: application/json" --post-data="{\"from\":\"<PREFLIGHT_FROM>\",\"to\":\"<PREFLIGHT_TO>\",\"markets\":[\"台股\",\"美股\"],\"horizons\":[5,20,60,120],\"calibrationRatio\":0.70,\"walkForwardFolds\":5,\"includeCloseFallbackSensitivity\":false}" http://localhost:8080/internal/backtest/rules'
```

## 完成報告

（回填 full regression、image/container/health、台美 API／頁面／匯出抽查、真實 request 期間與 universe、逐 production key 結論，以及所有未完成項與原因。若保留 V12，明確寫這是 gate 的正確結果。）
