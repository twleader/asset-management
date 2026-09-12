# [t426] 交易雷達 BFF 列表／單檔明細與 API error log 去重

**對應 Requirements:** Requirement 148
**前置:** Task 425 已使交易雷達行情／日 K 僅讀既有 Redis／PostgreSQL；Requirement 143 已建立 `api_error_log.dedupe_key` partial unique index 與 Nginx whole-file tailer

## 目標與根因

目前交易雷達 browser 首次載入 `GET /api/bff/trading-radar`，導致 business 對每一個雷達標的組裝完整 `StockDecision`，包含只會在 expand panel 使用的 evidence、fundamental tree、reasons 與 risks。實測 38 檔的 9090 同源 full projection 約 1.6 秒、54 KB；9090 port 是外部系統介面，不能當作瀏覽器改善捷徑。

另一個噪音根因不是 tailer 重複掃描：`NginxGatewayFailureLogTailer` 無 cursor、刻意從整個 log 重新讀取，讓 BFF restart 後仍能補到停機期間 Nginx 寫下的行。重複 raw line 具有相同 SHA-256 `dedupeKey`，但 `ApiErrorLogRecorder` 使用普通 JPA insert，觸發已有 partial unique index，造成每個 tick 都記錄可預期的 `DataIntegrityViolationException`。

## 實作清單

- [ ] **Browser 路由。** 新增交易雷達自身的 BFF `GET /api/bff/trading-radar/list` 和 `GET /api/bff/trading-radar/stock?market=&stockCode=`，透過 `TradingRadarBffRoutes` 路由至 authenticated business 的同名 `/api/trading-radar/list`、`/api/trading-radar/stock`。不可 proxy 到 `:9090`、`/api/public/**`、public controller/projection，BFF 不可直連儲存或行情來源。
- [ ] **Business list。** 建立清楚命名的 list response／stock DTO 與 service entrypoint；它只組首屏 table、tab/card 必需資料，不建 `StockDecision` detail tree，也不可 `get()`／`getCurrent()`／`buildStock()` 後 mapping，或構造 `StockDecision`、`RadarEvidence` 等 detail DTO 後再投影。抽出可重用的 compact decision core，再由 single-target detail assembler 增補展開資料；table 可見的決策／分數／行情值及排序（best score 降冪、stockCode 升冪）必與相同時間的 full response 一致。scalar 欄位固定有 `priceUpdatedAt`、`dailyCandleAsOfDate`，前端不可依賴完整 `dailyCandle`。不得 snapshot save、cache refresh 或對外部／broker 做 request-time I/O。
- [ ] **Business detail。** 對 `market + stockCode` 嚴格驗證與 owner target scope 後，僅組裝該一檔完整 detail；不得先把全數標的 full assemble。非法 pair、格式錯誤與越權不可洩漏資料。既有 full business/BFF endpoint 不改。
- [ ] **Vue、SSE 與手動刷新。** 初始 `load` 與手動刷新 outcome 後都改用 list；refresh response 不得再帶 full radar tree。SSE `price-update` 必依 Requirement 148／design 的 authoritative mapping table：identity 只比對、不覆寫；`price + changePercent` 為不可拆的 atomic tuple（僅 canonical 缺席才採 `changePct` fallback），`updatedAt` 只隨 accepted tuple 更新，quote status 遵守既有 close gate，其他 event field 一律忽略。零 list/stock/refresh HTTP、零 recalculate、零 root replacement、零 action/score/detail/其他列變動；`0000` 或不匹配事件零 mutation。table expand 事件改為真正的 lazy detail load。detail dispatch 捕捉 generation、pair 與 expanded state，只有回應時仍完全相符才可寫入；manual-refresh list 先回、detail 後回時必丟棄舊結果，SSE 不影響 generation。介面顯示 detail loading/error，不預抓未展開列。通知、market tabs、full export/snapshot 不得回歸。
- [ ] **錯誤日誌。** catalog validation 與 message/stack trace sanitize 必須在分支前執行一次；對 non-null `dedupeKey` 的已清理資料使用 parameterized native insert with `ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`，affected row 0 即安靜 no-op；不得 UPDATE、加 cursor、移除 unique index 或吞掉不相關 database failure。null key 繼續 ordinary append insert。

## 固定界線

- 9090 保持 external-only，既有 `/api/public/trading-radar/today`、`/stock` path、OpenAPI、payload 和匿名行為完全不變。
- 純 read path：只讀既有 DB/Redis，零 Fubon SDK/broker HTTP、外部行情、下單、改單、撤單、匯款或 `.env`/secret 變更。
- list 排除 detail-only `reasons`、`risks`、full evidence／fundamental／factor／weekly collection；detail 僅含 requested pair 的 full audit payload。
- full endpoint 是相容接口，不能改名、刪除或把其 export/snapshot behavior 偷換成 list。

## 測試與驗收

- [ ] backend/BFF tests：owner scope、exact rewrite、list/full separation、one-target detail、public/full contract regression。
- [ ] frontend tests：initial list only、expand one stock only、matching SSE 只 patch 行情欄位且零 HTTP／零 root replacement、manual-refresh list replacement 才會丟棄舊 detail。
- [ ] PostgreSQL：same non-null dedupe key record twice = one row + no exception；null key twice = two rows；tailer continues calling ingest twice for a repeated line。
- [ ] Docker：從 feature worktree 重建/recreate business-services、bff、frontend，確認 health；登入交易雷達後 network trace 為初始 list、展開才 stock；9090 public read shape 不變；重複 tailer tick 不再有 `DataIntegrityViolationException` 或 `API error log recorder failed`。
- [ ] 效能：固定 38 targets、warm Docker/data/Redis、同一已登入 owner 的 list/full 各連續 7 次、各捨棄第一次。list 六次 TTFB 全部 <=800ms、body <70KiB，full median 至少為 list median 的兩倍；報告每次 TTFB/bytes/median。沒有可用登入 session 時，此 browser 驗收必列 pending。

## 驗證命令

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -DextraArgLine=-Dnet.bytebuddy.experimental=true -f bff/pom.xml test
cd frontend && npm test -- --run
bash .claude/hooks/arch-review-pass.sh
```

Docker 驗收依 `run-stack` skill 執行，僅重建本任務已改的 business-services、bff、frontend；不得改動其他 container 或設定。完成後回填實際 endpoint、測試、健康檢查、browser trace 和 duplicate warning readback。
