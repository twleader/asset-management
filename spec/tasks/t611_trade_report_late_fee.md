# [t611] 成交未知費用與後到唯一證據明確補費

**對應 Requirements:** Requirement214（官方交割費用唯一對帳延伸）
**前置任務:** t601、t602
**Liquibase changeset:** v1.170.0-trade-report-fee-event.sql（測試及審查過後依授權部署執行）

## 背景
現行手動email匯入已允許orders UNAVAILABLE與NULL費用，但來源讀例外未降級、replay沒有後補入口、人工編輯無持久接管標記。官方逐筆FilledTrade沒有fee/tax/orderNo，不能自行拼證據。

## 要做什麼

### Requirement 214 延伸：Task611 待補費成交匯入與明確重新核對

使用者希望可信且已授權的成交回報不因官方訂單失敗或費用未知而無法記錄，後到可信唯一證據能補費且永不覆蓋人工意圖。

1. 維持既有 authenticated owner、來源/帳戶聲明、MIME安全及去重。現行 orders UNAVAILABLE 已允許NEW及fee/tax NULL；本次保留並測試，不宣稱未執行手動匯入是fee gate故障。僅可選費用來源讀取的資料存取/解析例外降級 FEE_SOURCE_UNAVAILABLE；身分、原匯入證據、帳本或寫入失敗不得吞掉。來源讀取隔離交易避免PostgreSQL失敗污染主匯入交易。
2. 不新增掃信、排程或券商呼叫。沿現有手動dialog preview/apply Request增加optional reconcileFees=false；UI明示已匯入但待補費，使用者勾選重新核對時才提出後補預覽及明確apply。未勾選重放只讀已保存證據。原新交易仍可立即匯入未知fee/tax，不等待orders。
3. 本版只接Task602既有官方orders+settlement唯一對帳，所有原限制原樣保留。官方FilledTrade目前無逐筆fee/tax及email orderNo，不提供client fee冒充官方入口。不將日總225分攤；不得把輸入只篩NEW/待補列而將多筆日誤當單筆，resolver始終看完整parsed rows與完整官方orders。
4. 新匯入canonical envelope schemaVersion3標示late-fee可追蹤；舊version1/2無人工歷史，一律不自動補（LEGACY_MANUAL_HISTORY_UNKNOWN）。只在原fee與tax皆NULL、交易完整hash仍匹配、無人工編輯marker、來源唯一且DERIVED時可提出FEE_UPDATE_READY。任何人工PUT更新該FUBON_EMAIL交易即append MANUAL_EDIT事件（含未改值、0、清NULL），阻止以後自動補費；不得推測舊資料無人工變更。
5. apply鎖既有owner並重讀，previewHash包含reconcileFees、完整fee候選與來源identity/version；來源/帳本/模式變更須409而非套用新值。以單一原子SQL UPDATE 精確owner/id/source且fee IS NULL AND transaction_tax IS NULL，更新數非1 rollback；和append-only FEE_ENRICHMENT事件同交易。人工update沿既有相同owner鎖。只補fee/tax，不改notes/gross/庫存/資金/成交身分。
6. 新broker_trade_report_fee_event具名不可變稽核snapshot例外：id、owner、import evidence FK、kind、canonical payload、created_at。FEE_ENRICHMENT payload含before/after ledgerHash、fee/tax、provenance/receipt IDs/evidenceHash、resolverVersion、appliedPreviewHash及完整request binding（owner、rawSha256、messageId、reconcile mode）；每個import evidence最多一筆成功補費，DB partial unique index；MANUAL_EDIT保留人工接管事實。DB禁止UPDATE/DELETE，FK/owner一致性由約束及服務核對。原receipt/import canonical永不UPDATE。
7. replay沿原immutable import加合法補費事件驗after hash；費用由已保存新事件回覆，不因官方來源消失/更動重新推導或抹掉。人工編輯後仍LEDGER_CHANGED或人工接管狀態，不偷偷恢復。重送第一次成功apply的原previewHash，只在新event完整request binding一致、來源原文未變、全批交易afterHash仍匹配且無人工接管時允許冪等回覆；其餘舊hash仍409，不重推來源。補費重放為ALREADY_IMPORTED，insertedCount=0，另feeUpdatedCount明示，不能把費用更新當新增交易。
8. UI只render BFF投影canApply/conflictRows/feeUpdatedCount與待補狀態，reconcile勾選變動清除preview；確認框須區別新成交與補費。沿既有Origin/session/header保護，不增加9090或新權限。
9. 先隔離worktree合成單元/一次性Postgres測試；使用者後續明確授權驗證好後部署及commit/merge/push。通過spec/arch與測試後依run-stack核對main/正式設定與同檔衝突，重建必要business/BFF/frontend，正式Liquibase僅新增本版事件schema並依規範pg_dump重產schema.sql，再依commit-merge-push收尾及main版本驗收。禁止正式成交匯入、補費執行、交易、寄信或重跑同步。

## 驗證
- 既有TradeReportImportServiceTest、TradeReportImportPostgresTest、TradeReportFeeResolverTest擴充合成案例：三筆不同股票未知費可匯入、相同重放不增；orders失敗與repository讀例外不阻NEW；真正duplicate/寫入錯誤仍拒絕。
- 唯一單筆Task602後補；多筆/部分成交/不同帳戶拒推導；preview/apply來源變動409；補費原子條件競態、兩次apply冪等；人工0、人工值清null、先manual與先enrichment兩順序；舊版本不補；補後來源消失重放穩定；原canonical不可變。
- BFF既有TradeReportImportBffTest擴充mode/投影及安全guard；前端純函式測試待補/補費ready/確認模式，build。
- 先scripts/spec-check.sh與獨立scripts/codex-review.sh spec及gate；之後委派implementation；最後完整diff獨立arch review及合成測試。驗證好後依新授權run-stack、schema重產、commit-merge-push；仍禁止正式成交匯入/補費/API交易寫入。

## 完成報告
2026-11-19：spec第3輪PASS；arch第2輪PASS，唯一schema minor已於正式Liquibase1.170.0後pg_dump重產（128表、drift PASS）。Backend clean183/183、arch修正affected34/34、BFF17/17、frontend221/221及build通過。business/BFF/frontend已feature驗收，兩服務healthy、safe GET通過；BFF-only origin持久local override恢復，business原空值不變。未執行正式成交匯入、補費、交易或寄信。Git兩段式收尾及main重建待release完成，原始審查與測試摘要見docs/reviews/t611_trade_report_late_fee/。
