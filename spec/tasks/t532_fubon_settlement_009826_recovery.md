# [t532] 補正009826成本並啟用可核對的富邦交割款

**對應 Requirements:** Requirement 154（完整帳本成本）、Requirement 129（富邦未來交割款同步）
**前置任務:** 無
**Liquibase changeset:** v1.159.0-009826-ledger-cost-recovery.sql（data-only）

## 背景

2026-11-02 owner1 最新 snapshot15 的009826為1000股、成本0；manual ledger208為買入1000股、10.08、amount10080、fee20、tax0、channel富邦證券。正確成本10100。使用者明確要求啟用交割同步；main .env已獲授權啟用既有flag與必要的已驗證owner設定。富邦 filled_history API KEY未授權，但 query_settlement成功／明確帳戶綁定：當日到期列48168含null稅欄、未來2026-11-04應付56586。舊parser以partial-null及到期nonzero拒絕整批。當日富邦manual ledger207（00881含費46486）+208（009826含費10100）精確等於56586；原00881手動待付款46486誤列其他銀行，使用者已於本次工作手動更正為台北富邦銀行，不能再疊加完整56586。

## 要做什麼

- [x] 532.1 先鎖owner1最新snapshot；data-only migration僅在snapshotDate=2026-11-02、唯一富邦台股TWD009826持股1000股成本0、完整同scope帳本恰id208並精確符合上述日期／數量／價格／費稅／source MANUAL且無該股券商projection時，按既有公式更正cost10100。不可動其他持股欄／交易／歷史／存款；獨立SQLstatement按逐筆換匯再round到整數規則重算total_stock_cost，重播／guard不符no-op，註冊master。重產db/schema.sql及drift查核，即使結構相同。
- [x] 532.2 Python SettlementService先驗account/optional row identity、所有原required欄位存在、合法source date不晚於queryDate、合法settlement date不早於source date與TWD，排除settlementDate<=queryDate列，允許被排除列的optional amount為null但不把它們投影成NO_DATA_OBSERVED。不把未知日期／幣別列當已到期略過。future partial-null／integer／符號／總額／重複等原防線全部保留。保留真正全null空佔位。內部DTOshape／公開API／Swagger／cron不變。
- [x] 532.3 backend新增明示owner native ledger query及pure reconciliation helper。future payable只在sourceQueryDate=queryDate時讀同owner/day、股票／台股／TWD／精確channel富邦證券的完整買入ledger，source只能MANUAL/FUBON_SYNC。每列合法、純BigDecimal含費公式與總額精確吻合SDK才可按code群組證明手動款。只移除processingDate同SDK、TRANSIT_TWD、買股待付款、notes.trim精確等於單一code、amount負且精確等於該code成本、bank精確為fubon、source MANUAL/null且每code唯一的候選；其他銀行列完全不動。duplicate／wrongamount等該code候選不移除，fubon target仍走原unmanaged/ambiguous failclosed。其他類型、日期、owner、未匹配notes保留。缺完整ledger／總額不等不刪任何手動列。移除及source-owned總額建立／更新與aggregates皆同一鎖／transaction，commit failure全rollback，重跑不新增。已驗證ledger提供sorted distinct代號notes，沿既有formatter長度限制，備註與手動銜接分離：備註可用SDK sourceQueryDate查同源ledger，但手動款銜接仍限當日。相同處理日／類型的既有source-owned列在沒有新的完整同源代號證據時保留原非空notes，不可被本地queryDate的泛用備註覆寫；只有無證據的新列才fallback。
- [x] 532.4 測試Python到期partial-null+future、到期錯identity/date/currency、futurepartialnull、空placeholder與duplicates；Java pure helper及PostgreSQL writer/migration tests涵蓋上述guard、sum56586、同銀行00881手動款移除後唯一fubon56586、未完整證據保留、duplicate candidate、重播、rollback、aggregate讀新cost、跨日無新ledger時兩代號與processingDate保留。既有FubonSettlement測試均回歸。不fakeSDK至正式DB。
- [x] 532.5 只rebuild/recreatebusiness-services與fubon-broker-service。main到feature部署設定同步、secret/TLS路徑與effectiveenv保持一致，不輸出秘密、不重啟BFF（除skill限定fallback）。先dryrun驗合法，再用使用者已授權的既有settlement-sync入帳；所有券商呼叫都唯讀，不下單／改撤／匯款。唯讀canonical assets/DB驗cost10100、11/04唯一富邦待付56586、舊00881款不重複。驗收後按兩段式commit/noffmerge/push，從乾淨main重建最終版本。

## 驗證

```bash
PYTHONPATH=fubon-broker-service/src python3 -m pytest fubon-broker-service/tests/test_settlement.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*FubonSettlement*,*009826*' test
bash scripts/spec-check.sh
bash scripts/tests/schema-sql-drift-test.sh
```

Docker依權威main設定及run-stack，只對business-services/fubon-broker-service build與force-recreate。先取得healthy與BFF安全GET成功，再驗本功能實際JSON／唯讀DB。新的測試識別字為待實作，非現況宣稱。

## 完成報告

- Python 到期列解析及 backend 完整 ledger 銜接已實作，維持同銀行精確核對、原交易鎖、來源保護與失敗 rollback。備註為「富邦證券交割款：00881、009826」，processingDate 固定來自 SDK 2026-11-04；跨日無新證據保留既有代號。
- v1.159.0 只修已驗證事件的 009826 零成本，正式回讀為 10,100.00；total_stock_cost=7,181,901 與逐筆換匯後 round 的明細加總相符。建置前後主程式內容相同，沒有假造券商成交或成本 projection。
- Python 交割 123 項、完整富邦 Python suite 965 項皆通過（隔離 Docker、禁網路與唯讀合成 fixtures）；backend 專注 43 項及相應 accounting/settlement/cost 回歸共 210 項通過，0 failures/errors/skipped，包含真 PostgreSQL migration、12 種 guard、重播、deferred commit rollback、跨日備註／日期。
- spec 機械 BLOCK 0/CHECK 0；兩輪獨立 spec 審查，首輪的跨日備註與測試路徑問題已修，第二輪無 findings；完整 code diff 架構審查無 findings。schema 依檔頭重產後位元組相同，113 表 drift PASS。
- 使用者授權啟用的 settlement flag 與必要 ACTIVE ADMIN owner 設定已保存於權威 main .env，無秘密進入 Git。feature 的 main-source .env、有效環境與正式 secrets 掛載均核對；business-services 與 fubon-broker-service rebuild/recreate 並 healthy，BFF 持續運行且安全 GET 200。
- 先 dry-run 再正式本機同步成功；舊 MANUAL 00881 -46,486 銜接為唯一 FUBON_SYNC 台北富邦銀行 -56,586，備註含兩代號、處理日11/04，canonical assets API 回 updateMode=AUTO。其他 deposit 逐欄完全不變，totalDeposit 7,630,614→7,620,514，ledger/realized gain 數量不變；重跑後所有 deposit 的 ID／數量／金額／備註／日期不變。持股有背景庫存刷新造成 row ID、行情與相關預估配息正常變動，未誤當同步差額。
- 成本 migration 前完整 JSON capture 未成功；不宣稱不存在的逐欄前態差值。migration 後／sync 前與同步後完整證據保留 /tmp/t532-before-sync.json、after-sync.json、after-repeat.json、assets-latest.json（權限0600）；最終 feature images 為 business a8b6a763、fubon ad10e92b，結論前再次核對未被覆蓋。
- 驗收完成後依專案兩段式 commit／no-ff merge／push 收尾，最終執行版本從乾淨 main 重建核對。
