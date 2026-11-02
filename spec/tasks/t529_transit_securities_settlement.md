# [t529] 買賣股票在途款到期對應證券戶

**對應 Requirements:** Requirement 150（在途款項到期轉入同銀行同幣別對應存款）
**前置任務:** t510
**Liquibase changeset:** 無

## 背景

2026-11-02 正式排程有執行，snapshot=15 的富邦買股待付款 48,168 元仍保留，日誌為 MISSING_TARGET。當下同銀行 TWD 有手動證券戶 63,454 元、無活存；舊 TransitBankSettlement 只找活存。應依股票款用途對應證券戶。本次明確取代 t510 的全類型限活存／禁止證券戶規則，仍只做本機資產記帳，不呼叫銀行或券商寫入。

## 要做什麼

- [x] 529.1 純 Service helper 以既有在途類型 code「買股待付款」「賣股待收款」選同快照、同非 null bankId、同對應幣別的「證券戶」；TRANSIT_TWD→TWD、TRANSIT_USD→USD。其他類型選 TWD／活存或 USD／美元活存。只使用既有 code，不加分類、schema、DTO、API、查詢或排程；不依名稱猜測、不建存款列、不動定存、不跨銀行或幣別、不在缺目標時 fallback 到另一類存款。
- [x] 529.2 Batch key 加入目標產品類型，以 bankId＋對應幣別＋目標產品類型＋processingDate 淨額入帳，日期由舊到新；買賣股票款可同日互抵，但不可與信用卡等一般款互抵。各批次獨立驗證後才修改。目標類型內只能唯一、source 必須 MANUAL 或 legacy null；缺目標／重複／來源管理／不足／無效金額等全部沿既有保留整批與具名日誌。USD 沿既有原幣及正匯率檢查與 HALF_UP 到整數規則。
- [x] 529.3 來源管理目標保護涵蓋 TWD／活存、USD／美元活存以及 TWD 或 USD／證券戶：完整 PUT 在 locked collection replacement 前保留非 MANUAL/null 原列，payload 省略、exact id 或 bankId＋depositType 碰撞均不能刪除、覆寫或重建成 MANUAL；保持原 id/source/notes/interest。不得修改來源管理證券戶造成券商餘額二次加扣。同銀行同產品 code 的證券戶若已有另一幣別的 MANUAL/null 列，正常完整 PUT 必須保留該列的合法更新，不可因廣義 bankId＋depositType 碰撞誤刪。唯一例外放行條件為 payload id 已通過既有歸屬驗證、精確指向該原手動列，bankId／depositType／TWD或USD currency 均與原列相同，且其幣別不同於該 bankId＋depositType 下所有受保護來源列；無 id、新造或更改身分的 payload 維持碰撞拒絕。
- [x] 529.4 create／完整 PUT／daily roll／啟動補跑共用 helper，沿用最新非未來／owner／parent row lock 與原子 transaction。null／未到期／歷史／未來與一般取消列不結清。重跑零重複扣款，失敗 rollback 存款／在途／aggregates，舊 id 拒絕不變。GET pure read。
- [x] 529.5 SnapshotFormView 在途說明明列股票款對應證券戶、其他款對應活存，均同銀行同幣別、無唯一目標或不足時保留。只改顯示文字、不在前端計算。
- [x] 529.6 新增針對性回歸：本次 63,454−48,168＝15,286；賣股待收款與買賣同日淨額；同銀行同幣別同時有活存與證券戶仍各自入帳／不足不互抵；兩幣別、不同銀行、缺／重複目標、來源管理、重跑。既有 AssetService 與獨立 Testcontainers PostgreSQL 測試增加證券戶 full PUT 來源保護與一筆真實交易／rollback／冪等路徑與跨幣別證券戶 sibling 正常 PUT 保留（兩種來源／手動幣別方向皆驗證，含非法改身分不得放行），不將 fixture 寫入正式資料庫。
- [ ] 529.7 run-stack 驗收後 commit／no-ff merge／push，再從乾淨 main 重建 business-services 與 frontend。正式啟動補跑可處理本次已到期手動款，不以 SQL 手改帳或製造測試款；唯讀保存前後證據：富邦證券戶 63,454→15,286、48,168 元在途列移除、明日待收款／無日期款保留，totalDeposit／totalAssets 不因結清變動。若當下資料已改變，依新證據重算預期，不能沿用舊快照盲改。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='AssetServiceTest,Transit*Test,SnapshotDateRollSchedulerTest' test
node --test frontend/src/utils/snapshotFormUpdateMode.contract.test.js
git diff --check
```

使用 Java 25。run-stack 子代理依技能從權威 main 同步缺少的 feature .env，確認 bind secrets/TLS，僅報一致／不同不顯示秘密；僅重建受影響 business-services、frontend 並 force-recreate。BFF 保持運行且驗證 upstream 健康後 safe GET 復原。唯讀 GET /api/assets/latest 與 DB 查核正式結清，確認部署後 JAR、前端提示與 image SHA，提交後從乾淨 main 再部署。

## 完成報告

- 根因：正式 snapshot=15 富邦只有 TWD 證券戶、沒有活存，舊規則記錄 MISSING_TARGET 並保留到期買股款。改以股票在途既有 code 對應同銀行同幣別證券戶，其他款維持活存；Batch key 納入目標類型，不互抵或 fallback。
- 實作：TransitBankSettlement、AssetService 與三支既有測試檔；SnapshotFormView 只改說明文字。來源管理證券戶在結清與完整 PUT 受保護，合法原手動另一幣別 sibling 以已驗證原 id／原銀行／類型／幣別條件正常 round-trip。
- 規格審查兩輪：第一輪 0 critical／0 major／1 minor，措辭已修；第二輪跨幣別保護 0 findings。機械最終 BLOCK 0／CHECK 0，schema 與金融文件完全一致。獨立架構審查重做後 0 findings，來源碼 hash gate current。
- Java 25 focused suite 72 項全過，0 failures／errors／skipped：AssetService 33、純結清 21、日期 2、真 PostgreSQL 13、Scheduler 3。包含本次金額、兩幣別、批次隔離、來源／跨幣別 PUT 保護、真交易回滾、parent lock、重跑零 DML。前端既有契約 4 項全過；git diff --check 通過。日誌 /tmp/t529-backend-tests-sibling.log。
- feature run-stack：只重建 business-services／frontend 並 force-recreate；2026-11-02 11:46:10 台北啟動補跑 rolled=1、skipped=2、failed=0。正式 16594 號 48,168 元待付列已移除，16598 號富邦證券戶由 63,454 降為 15,286 元；剩餘 17 筆存款除該 amount 外每欄相同，11/03 待收與無日期款保留。totalDeposit 7,643,932 不變；盤中 totalAssets 的獨立行情差額等於股票／基金估值差額，並經 9090 canonical snapshot 回讀。
- runtime：business healthy、BFF 原容器持續 UP 且 safe GET 已恢復；HTTP 前端 200，新 SnapshotFormView-CVuEylhy.js 提示已發布；運行 JAR 含新 helper 與 sibling 保護。effective env／bind 掛載比權威 main 一致，8080／8082 無 host listener，兩次 image 身分穩定。證據 /tmp/t529-run-stack-feature/；正式只由既有啟動 Service 結清，沒有 SQL 手改帳、測試款或銀行／券商寫入。
- feature 驗收後自動接續 commit／no-ff merge／push，並由乾淨 main 再部署兩服務、確認重啟不重扣。此為提交時的發布收尾步驟，最終 SHA 與 readback 由本次對話收據保留。
