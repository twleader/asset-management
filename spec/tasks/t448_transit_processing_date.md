# [t448] 在途款項處理日期與到期移除

**對應 Requirements:** Requirement 150 修訂（顯示處理日並清除到期在途款）
**前置任務:** t394、t432（已落地主線）
**Liquibase changeset:** v1.135.0-transit-processing-date.sql（新增）

## 背景

使用者指出富邦 AUTO 買股待付款已支付卻無法刪除。現行 source-owned 保護禁止刪除，但 bank_deposit 沒有處理日期且 writer 只加總每方向未來款項，所以款项可永久殘留。本次保留來源金額保護、改用有證據的日期驅動生命週期；不以空查詢推定支付。

## 要做什麼

- [x] 448.1 新增 bank_deposit.processing_date DATE NULL（v1.135.0-transit-processing-date.sql，冪等並註冊），BankDeposit 使用 LocalDate。只適用 TRANSIT_TWD／TRANSIT_USD；其他存款 processingDate 必為 null。既有列保持 null，不由金額、快照日、備註或 T+2 猜測日期。
- [x] 448.2 既有 AssetSnapshotDto.DepositResponse 增加 nullable processingDate；DepositRequest 增加 optional id、processingDate，保留既有 Java 呼叫的相容建構子。source／updateMode 仍不可寫入。既有 snapshot-form BFF 只轉送既有 CRUD。9090 latest assets 沿用相同 SnapshotDetailResponse，必同步 DepositSnapshot 的 processingDate 與既有 readonly updateMode 至 OpenAPI、適用 validator、fixture、產生的兩份 Swagger（含 SRPP），不得增加公開寫入或改 gateway 路由。
- [x] 448.3 兩張在途款表各新增「款項處理日」日期欄位（YYYY-MM-DD），並明示「儲存後，款項會於處理日自動移除」。MANUAL 可填／清除；AUTO 已有日期時唯讀，AUTO 舊列日期為 null 時可補填。編輯表單保留 server id，送 id＋processingDate；新增／複製快照不攜帶舊 id。AUTO 原銀行／類型／金額／備註／拖曳／刪除保護仍有效，不能在前端自行隱藏到期列充當刪除。
- [x] 448.4 完整 PUT 必先以既有 snapshot lock 鎖定並驗 owner；在移除任何 children 前，所有 supplied 非 null deposit id 必須唯一且屬該 locked snapshot，未知／重複／跨快照／跨 owner id 一律拒絕整次寫入，防止到期後舊畫面把已刪 AUTO 轉回 MANUAL。既有 AUTO 保護 identity 仍以 bankId＋depositType 跨 currency 防碰撞，保留同 identity 不同 processingDate 的全部 AUTO 列。僅對 AUTO 原 processingDate=null 且 request exact id、bankId、depositType、TRANSIT_TWD identity 相符者，接受使用者補填日期；已有日期不得修改／清除。AUTO 金額、notes、source 及其餘欄位全部保留。legacy client 缺 id 只沿用原保護，不得猜哪列的日期。Excel 同日覆蓋仍走 locked preservation，未提供日期必保留 AUTO 原值。
- [x] 448.5 FubonSettlementWriter 對通過既有 envelope／owner／freshness／currency／signed amount 驗證的 future nonzero 來源，改以 bank＋depositType＋settlementDate 投影多列，各列 processingDate=官方 settlementDate；不得把不同處理日合併再以最大／最小日刪整筆。每日期方向寫入精確原值，既有 summary payableAmount／receivableAmount 仍為合計。相同資料 no-op；missing／zero directions 不清除未到期資料。manual 同 bank/type collision 仍 UNMANAGED_TARGET，錯幣別／同日重复 AUTO 為 AMBIGUOUS_TARGET。遇同 bank/type 的 AUTO null-date 舊列，不能用等額猜同一事件或覆寫／重複投影，必 fail closed 等使用者補填／到期移除。既有 adapter 對 same-day/past nonzero、malformed／stale／帳戶不明的拒絕不變，清理到期資料不得依賴本次券商查詢成功。
- [x] 448.6 到期定義為 processingDate <= Asia/Taipei 的今天。只清理每 owner 當前 latest snapshot、且 snapshotDate <= today 的 TRANSIT_TWD／TRANSIT_USD 有日期列；手動、自動皆適用。null 日期、未到期、普通存款、歷史快照及未來快照保持不變。沿用 SnapshotDateRollScheduler 每日 00:05 Asia/Taipei 與開機 self-heal，清理與 roll 在同一個 per-owner transaction、同一個既有 snapshot row lock 下完成；即使 snapshotDate 已是今天也必清理。服務停機跨日後下次啟動補跑；逐 owner 失敗隔離、idempotent、不因 Fubon feature flag 或來源 503 停止清理。更新既有 JOBS 此工作的描述，不新增排程或變更節拍。
- [x] 448.7 使用者建立／儲存當前最新且非未來快照時，已到期列在同次 transaction 立即移除；先取得 AUTO 保護 identity 再清理，確保該 payload 不會重建已刪 AUTO。到期移除用 orphan removal 並重算該 snapshot totalDeposit／totalAssets 等既有 aggregates；不改活存金額、不新增付款／交易／已實現損益，不修改券商、銀行端或歷史快照。GET 全程 pure read，不在畫面或公開 API 讀取時觸發刪除。
- [x] 448.8 驗證涵蓋台北跨日、已是今天仍清理、停機補跑、過期／today／future／null、兩幣別、多 owner／歷史／future snapshot、aggregate、重跑零寫、writer 多日期與 missing/zero 保留、legacy null-date fail closed、手動來源保護、AUTO 日期補填及不可改既有日期、stale/跨 owner/重複 id 的全交易拒絕與 Excel round-trip。採 Java 21 focused tests＋真 PostgreSQL lifecycle/rollback 驗證、前端既有測試及 build、Docker affected images rebuild/recreate 與實際 GET。重產 db/schema.sql、schema drift/spec-check 零 BLOCK、架構審查後依專案流程 commit/no-ff merge/push，再驗 main runtime。使用者指出的舊款項，只在取得實際處理日後透過受 owner 保護的應用程式寫入路徑補填並驗證消失；不得把猜測日期或測試資料寫入實帳。

## 驗證

已新增日期規則與真 PostgreSQL lifecycle regression，並擴充既有富邦 accounting writer、AssetService 與 SnapshotDateRollScheduler 相關測試。檢查同方向兩日，其中一日到期後另一筆仍保留，總額精確差額；驗證保存舊表單不能復活已清除列。

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='FubonSettlement*Test,AssetServiceTest,SnapshotDateRollSchedulerTest,*Transit*Test' test
node --test frontend/src/utils/snapshotFormUpdateMode.contract.test.js
npm --prefix frontend run build
# 由 run-stack subagent 依 skill，先確認 main env parity 再於 feature 驗收；merge 後從 main 重建。
docker compose -p asset-management build business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
bash scripts/tests/schema-sql-drift-test.sh
bash scripts/spec-check.sh
```

migration 後依 db/schema.sql 檔頭重產 schema-only 基準；不得向實帳植入 fixture。actual readback 驗證日期與移除後 aggregate，未提供舊款項實際日期前先完成所有不依賴該日期的功能與測試。source/DB/image/API/UI 的證據分開回報。

## 完成報告

2026-09-22 功能與驗收完成；主線 commit／no-ff merge／push 與 merge 後 main runtime 的最終識別值，記錄於本次交付回報。

- 資料模型／API：nullable `processingDate`、request optional `id`、來源保護與 stale ID 全交易拒絕已實作；OpenAPI 1.13.0 與本專案／SRPP 兩份 Swagger 同步。
- 生命週期：官方不同日期分列，既有排程與啟動補跑可清理今天已到期列；保存 latest 非未來快照時立即清理，保留歷史／未來／null 舊資料，重算 aggregates 而不修改活存與交易。
- Java 21 focused reports：backend 113 tests、BFF 34 tests，皆 0 failures／errors／skips。backend 包含 accounting 真 PostgreSQL 23 項、lifecycle 真 PostgreSQL 8 項，涵蓋到期 orphan removal、強制資料庫錯誤 rollback、跨 owner／重複／已刪 ID 拒絕、多日款項與 Excel 保存、migration 冪等及 CHECK。
- 前端：全套 59 tests 與 production build 通過。Playwright 使用隔離 fixtures 驗證兩幣別日期欄、AUTO 原日期唯讀、legacy 補日重選、跨快照 copy 清 ID、保存後移除到期列及更新 MANUAL ID。另注入明細／清單 503：已成功 PUT 不誤稱儲存失敗、清除 loading、明細失敗阻止舊草稿重送並可重讀恢復；真後端寫入 0 次。
- Docker feature 驗收：business-services／BFF／frontend 已 rebuild＋recreate，實際 9090 GET 的每筆 deposit 均含 `processingDate`；BFF jar 封裝 OpenAPI 1.13.0。Liquibase v1.135.0 已套用，重產 `db/schema.sql` 僅新增 DATE 與 transit CHECK；schema drift PASS、spec-check 0 BLOCK。
- 獨立審查：spec 初審 0 findings；architecture 審查發現的前端保存後讀回失敗已修正，再審 critical／major／minor 皆 0。僅此完成報告回填，未調整已審 business contract。
- 實帳界線：snapshot 15／deposit 14530 的 -31,004 AUTO 款仍為 null 日期，實際 GET 已確認原值保留。使用者尚未提供實際處理日，未猜測、未直接 SQL 補值、未以測試資料寫入；可於新欄位補填並儲存，日期已到則由同一筆應用交易立即移除。
