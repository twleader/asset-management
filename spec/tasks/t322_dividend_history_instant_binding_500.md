# [t322] 修正股利歷史全數 500 與 append-only evidence JDBC 時間型別失效

**對應 Requirements:** Requirement 13（股票分析 popup 的最近 10 年股利歷史與友善空狀態）、Requirement 65（交易雷達 append-only 配息 snapshot／observation 與 decision-time resolver）
**前置任務:** t307（append-only 配息事件架構已存在）
**Liquibase changeset:** 無（運行中三張表與 `TIMESTAMPTZ` 欄位均已存在，本任務只修 JDBC 邊界）

## 背景

2026-08-13 在實際 Compose stack 內重現：

```text
GET /api/market-data/dividends?code=0056&market=台股&years=10
HTTP 500
PreparedStatementCallback; bad SQL grammar [SELECT ... o.observed_at<=? ...]
```

相同端點對任意台／美股都在查到資料前失敗。SQL 以常值直接在同一顆 PostgreSQL 16 執行成功，PostgreSQL log 也沒有收到該 prepared statement；失敗點是 pgjdbc 42.7.x 無法替 `JdbcTemplate` varargs 中的 `java.time.Instant` 推斷 SQL type。運行中 schema 已確認 `stock_dividend_fetch_observation.observed_at/source_available_at`、`stock_dividend_snapshot_event.source_available_at`、`stock_dividend_fetch_attempt.observed_at` 為 `timestamp with time zone`。當下資料量為 snapshot/event/observation/attempt 全部 0 列，但既有 `stock_dividend_history event_status='ACTIVE'` 有 1005 列，證明錯誤同時造成：

1. `JdbcDividendCurrentStateRepository` 讀取投影候選時直接綁 `Instant`，使股利頁籤 500。
2. `JdbcDividendEventEvidenceRepository` 直接綁／讀成 `Instant.class`，例外被 catch 後所有 decision-time evidence 靜默變成 `MISSING`。
3. `DividendSnapshotStore` 寫 observation、event source time 與 attempt 時直接綁 `Instant`；排程逐檔 catch 後 append-only evidence 長期無法落地。

正確行為是領域 API 繼續使用 `Instant`，只在 JDBC `TIMESTAMPTZ` 邊界轉成 driver 支援且保留 epoch 的型別；有歷史資料回真實 rows，確實無資料回成功空結果。前端既有「查詢失敗」不得改成「查無股利資料」來掩蓋 HTTP 失敗。

## 要做什麼

- [x] 322.1 `backend/.../JdbcDividendCurrentStateRepository` 的 `findLatestComplete` 與 `findLatestHistorical`：所有 `decisionInstant` query 引數先轉 `java.sql.Timestamp.from(...)`；`observed_at` 不再用 `getObject(..., Instant.class)`，改用 nullable `getTimestamp(...).toInstant()`。`LocalDate` scope 引數與 public interface 不變。
- [x] 322.2 `backend/.../JdbcDividendEventEvidenceRepository.loadObservations`：`latestDecision` 綁定前轉 `Timestamp`；snapshot 的 `observed_at/source_available_at` 與 event 的 nullable `source_available_at` 皆由 JDBC timestamp 明確轉回 `Instant`。既有 fail-closed resolver、provider precedence、scope 與 known-at 規則不得改動。
- [x] 322.3 `backend/.../DividendHistoryService.findFromDb`：首次與 cold-cache sync 後的 `projectionService.projectOne` 都用同一個 fail-soft helper；投影丟 `RuntimeException` 時記錄具 market/code 的 WARN 並繼續讀既有 `stock_dividend_history`。`repo.findByStockSinceYear` 或其他真正 history 讀取錯誤不得吞掉；API 成功空結果仍沿用 `{rows:[], message:"查無資料"}`。
- [x] 322.4 `external-materials-service/.../DividendSnapshotStore`：寫入 `stock_dividend_fetch_observation.observed_at/source_available_at`、`stock_dividend_snapshot_event.source_available_at`、`stock_dividend_fetch_attempt.observed_at` 前，以 null-safe helper 轉成 `Timestamp`；canonical hash、去重、transaction、scope validation 與 append-only 語意不變。
- [x] 322.5 不修改 `StockAnalysisDialog.vue` 的錯誤文案。HTTP 失敗仍顯示「查詢失敗」；只有 HTTP 200 且 rows 空才顯示「查無股利資料」。不新增 endpoint、不改 BFF route、不改 schema。
- [x] 322.6 backend 回歸測試同時證明：(a) current-state 兩個 query 綁的是 `Timestamp` 而非 `Instant`；(b) evidence resolver query 綁的是 `Timestamp`；(c) ResultSet 的非空／null `TIMESTAMPTZ` 可轉為正確 epoch 的 `Instant`；(d) 首次投影失敗但 history 可讀時仍回既有 rows；(e) `repo.findByStockSinceYear` 丟例外時必須向外傳遞，不能被投影的 fail-soft 邊界吞掉或改成空結果；(f) 首次 history 為空、cold-cache sync 與第二次投影完成後仍為空時，才回成功空結果；(g) cold-cache 後的第二次投影失敗時也只降級回第二次 history 查詢。上述 (d)～(g) 必須是獨立案例，不能用一個寬鬆 mock 同時冒充。
- [x] 322.7 external-materials 回歸測試覆蓋新 snapshot、既有 snapshot retry、nullable/non-null source available time、invalid/null fetch attempt，逐一斷言 `jdbc.update` 沒有裸 `Instant` 且需要時間的欄位收到 `Timestamp`。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml \
  -Dtest=JdbcDividendCurrentStateRepositoryTest,JdbcDividendEvidencePipelineTest,DividendHistoryServiceTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml \
  -Dtest=DividendSnapshotAppendOnlyTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test

docker compose -p asset-management build business-services external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate business-services external-materials-service
docker compose -p asset-management restart bff

docker exec asset-business-services curl -sS -w '\nHTTP_STATUS:%{http_code}\n' \
  'http://localhost:8080/api/market-data/dividends?code=0056&market=%E5%8F%B0%E8%82%A1&years=10'
docker exec asset-business-services curl -sS -w '\nHTTP_STATUS:%{http_code}\n' \
  'http://localhost:8080/api/market-data/dividends?code=COIN&market=%E7%BE%8E%E8%82%A1&years=10'
docker exec asset-external-materials-service curl -fsS -X POST \
  'http://localhost:8080/internal/dividend/sync?code=0056&market=%E5%8F%B0%E8%82%A1'
docker exec asset-postgres psql -U assets -d assets -At -F '|' -c \
  "SELECT 'snapshot',count(*) FROM stock_dividend_snapshot
   UNION ALL SELECT 'event',count(*) FROM stock_dividend_snapshot_event
   UNION ALL SELECT 'observation',count(*) FROM stock_dividend_fetch_observation
   UNION ALL SELECT 'eligible_complete',count(*)
     FROM stock_dividend_fetch_observation
    WHERE complete=true AND status IN ('COMPLETE','EMPTY_COMPLETE')
      AND scope_from<=CURRENT_DATE AND scope_to>=CURRENT_DATE+45
   UNION ALL SELECT 'attempt',count(*) FROM stock_dividend_fetch_attempt;"

docker exec asset-postgres psql -U assets -d assets -x -c \
  "SELECT s.stock_code,s.market,s.provider,o.status,o.complete,o.scope_from,o.scope_to,
          count(e.id) AS event_count
     FROM stock_dividend_snapshot s
     JOIN stock_dividend_fetch_observation o ON o.snapshot_id=s.id
     LEFT JOIN stock_dividend_snapshot_event e ON e.snapshot_id=s.id
    WHERE o.complete=true AND o.status IN ('COMPLETE','EMPTY_COMPLETE')
      AND o.scope_from<=CURRENT_DATE AND o.scope_to>=CURRENT_DATE+45
    GROUP BY s.id,o.id ORDER BY o.observed_at DESC;"

docker exec asset-business-services curl -fsS \
  -H 'X-User-Id: 1' -H 'X-User-Role: ADMIN' -H 'X-User-Status: ACTIVE' \
  'http://localhost:8080/api/trading-radar' | python3 -c '
import json,sys
d=json.load(sys.stdin); stocks=d.get("stocks",[]); available=[]; future=[]
for stock in stocks:
    evidence=stock.get("evidence") or {}
    groups=evidence.get("evidenceGroups") or {}
    components=(groups.get("PUBLIC_EVENT") or {}).get("components") or []
    component=next((c for c in components if c.get("name")=="dividend_event"), {})
    if component.get("applicability")=="AVAILABLE": available.append(stock.get("stockCode"))
    if evidence.get("nextDistributionDate") is not None:
        assert evidence.get("nextDistributionStatus") is not None
        future.append((stock.get("stockCode"),evidence.get("nextDistributionDate"),evidence.get("nextDistributionStatus")))
assert available, "dividend_event AVAILABLE 檔數仍為 0"
print("dividend_event AVAILABLE:",len(available),available)
print("future distributions:",future)
'
```

驗收時須保存：兩支 `/dividends` 的 response body、`HTTP_STATUS:200` 與 JSON shape；0056 `rows` 非空（現有 history 有資料），COIN 為成功 rows 或成功空結果而非 500。append-only 驗收不能只靠 `attempt`：`snapshot`、`observation` 與 `eligible_complete` 必須皆大於 0；若來源回傳事件，`event` 亦須大於 0。eligible observation 必須逐列顯示 `complete=true`、`status=COMPLETE|EMPTY_COMPLETE`、`scope_from ≤ CURRENT_DATE`、`scope_to ≥ CURRENT_DATE+45`。`GET /api/trading-radar` 的 `dividend_event` AVAILABLE 檔數必須大於 0；有未來事件的標的，其 `nextDistributionDate`／`nextDistributionStatus` 必須非 null。若外部來源失敗，只能以 `attempt` 證明失敗稽核的 JDBC 寫入已恢復，**不得**據此宣稱 snapshot／observation／resolver 已恢復或完成本項驗收。另須證明兩個 upstream recreate 後 BFF 已 restart，近期無 `Connection refused`／新 500；驗證結束前再次核對 image SHA 未被其他 worktree 重建覆蓋。

## 完成報告

完成日期：2026-08-13。

- 實作：backend 的 current-state／decision-time evidence JDBC adapter 在 `TIMESTAMPTZ` 邊界明確轉換 `Instant` 與 `Timestamp`；`DividendHistoryService` 將兩次投影限制在同一個 fail-soft helper；external-materials 的 snapshot／event／observation／attempt 時間寫入全部改為 null-safe `Timestamp`。未修改 frontend、BFF route、endpoint 或 schema。
- 測試：backend targeted 14/14、完整 885/885；external-materials targeted 6/6、完整 321/321，皆為 0 failure／0 error／0 skipped。`spec-check` 為 `BLOCK: 0 / CHECK: 0`；獨立 spec 與 architecture review 最終皆為 0 critical／0 major／0 minor。
- Docker provenance：從 feature worktree 重建並 recreate business-services 與 external-materials-service，再 restart BFF。business image 由 `142767d4` 變為 `ad227c3f`，external image 由 `0ec20226` 變為 `156dc1f5`；BFF image `da545654` 已重啟。三者最終皆 healthy，驗收結束時 image SHA 未被其他 worktree 覆蓋。
- 股利歷史：直接 backend 與登入後正式 `/api/bff/stock-analysis/dividends` 均驗證成功。0056 為 `HTTP 200`、`application/json`、21 筆 rows（2016–2026，source `FinMind`）；COIN 為 `HTTP 200` 且成功空結果 `{source:null,message:"查無資料",rows:[]}`。UI 實際雙擊 0056 並切換「股利歷史（10 年）」後顯示來源與完整表格，未再出現「查詢失敗」。
- Append-only evidence：手動同步 0056 回 `HTTP 200`、`{"written":20}`。最終 row count 為 snapshot `98`、event `1036`、observation `104`、eligible complete `52`、attempt `0`、history active `1119`。0056 最新 eligible observation 為 `EMPTY_COMPLETE`／`complete=true`，scope `2026-08-13..2026-09-27`，`scope_from <= CURRENT_DATE` 與 `scope_to >= CURRENT_DATE+45` 皆成立；全體 eligible 明細的 status 與 scope checks 亦成立。
- Trading Radar：32 檔中 `PUBLIC_EVENT.dividend_event` 為 AVAILABLE `28`、MISSING `4`；7 筆未來配息事件的 `nextDistributionDate`／`nextDistributionStatus` 皆非 null。0056 為 AVAILABLE，observation status 為 `EMPTY_COMPLETE`。
- 日誌：最後一次成功同步後至驗收結束，business／external／BFF 均未出現新的 `Connection refused`、HTTP 500、`BadSqlGrammar` 或 Instant JDBC 綁定錯誤。另觀察到既有範圍外問題：COIN 冷快取同步曾因 snapshot duplicate-key race 回 500，但 history service 依設計 fail-soft，對使用者的 COIN 股利歷史仍為 HTTP 200 成功空結果；留待獨立任務處理。
