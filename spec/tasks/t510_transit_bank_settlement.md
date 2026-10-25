# [t510] 在途款項到期同步調整銀行活存

**對應 Requirements:** Requirement 150（在途處理日轉入對應銀行活存）
**前置任務:** t448
**Liquibase changeset:** 無

## 背景

現有 AssetService.removeDueTransit 只移除到期在途款，使待付款消失後活存偏高、待收款消失後活存偏低。本任務取代只刪列行為，改成本機原子記帳；不呼叫銀行／券商寫入、不追補已刪歷史款項。

## 要做什麼

- [x] 510.1 純 Service helper 共用 TransitProcessingDatePolicy 的台北 due 判定；AssetService 的 create／完整 PUT／roll 入口沿用既有 latest 非未來與 owner lock／transaction，改呼叫同 helper。不得在 GET、前端或 scheduler 自行計算餘額，不新增排程或更改節拍。
- [x] 510.2 以 bankId、對應幣別、processingDate 分組，日期由舊到新，同日淨額處理。TRANSIT_TWD 只對應同快照同非 null bankId、TWD／活存；TRANSIT_USD 只對應 USD／美元活存。這兩個是既有產品 code，不能新增 enum／分類；目標必須唯一且 source=MANUAL 或 legacy null。完整 PUT 在 locked snapshot collection replacement 前，把來源非 MANUAL/null 的 TWD／活存、USD／美元活存原列納入保護，沿用 AUTO transit pattern：原列即使 payload 省略仍原樣保留，忽略 exact id 及同 bankId＋depositType 的 payload 碰撞，不得重建成 MANUAL。缺少／重複／來源管理目標、缺銀行／金額時整批保留、日誌具名原因；不得建列、改定存／證券戶或跨銀行。
- [x] 510.3 TWD 以 persisted signed amount 入帳。USD 用 abs(originalAmount) 與 persisted amount.signum() 得到原幣淨額；所有原幣必須齊全、目標原幣非 null、snapshot usdExchangeRate >0，否則整批保留。更新 originalAmount 後以快照匯率 HALF_UP 到整數重新計算 amount。任何結果活存 <0 整批保留，不套 abs。驗證整批後再變更 entity，保留利率、備註、source 等非餘額欄位。
- [x] 510.4 更新目標、移除在途列與重算 aggregates 同交易原子完成，失敗全部 rollback。重跑無重複調整；保留既有 supplied id 歸屬／唯一／stale 全交易拒絕與 AUTO 保護。普通手動刪除為取消不入帳；null／future processingDate、歷史／未來快照不處理。
- [x] 510.5 修 SnapshotFormView.vue 在途提示：處理日待付款扣除／待收款增加同銀行同幣別活存，無唯一目標／不足保留；UI 不計算。沒有 DTO／DB／API／Swagger 變更。
- [x] 510.6 更新 AssetServiceTest 與 TransitProcessingLifecyclePostgresTest 中舊「只刪列」斷言，增加純規則測試與真 PostgreSQL 餘額／aggregates／rollback／第二輪零 DML／並行鎖驗證。兩幣別、正負、同日抵銷、多銀行、缺／重複／來源管理目標（含真正完整 PUT 不降級來源）、餘額不足、缺匯率／原幣、日期邊界／多 owner／stale id 皆覆蓋。使用獨立 Testcontainers 資料庫，不將測試資料寫入實帳。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='AssetServiceTest,Transit*Test' test
node --test frontend/src/utils/snapshotFormUpdateMode.contract.test.js
```

使用可編譯專案 Java 25 的 JDK 與 pom 既有 Mockito 支援，不沿用歷史 Java 21 註記。run-stack 子代理從權威 main 同步新驗收目錄 .env 並確保相對 secrets 仍指向 main 正式來源，只報相同／不同，不輸出值。重建 business-services 與 frontend、force-recreate，維持 BFF 並按既有復原窗口驗證安全 GET 與新產物。功能變更在獨立 PostgreSQL 驗證，正式帳戶僅做唯讀 readback，不製造測試款項。驗收後子代理 commit/no-ff merge/push，從乾淨 main 重建相同服務，驗 main／origin SHA。

## 完成報告

- 實作：新增純規則 TransitBankSettlement；AssetService 三個既有到期入口統一委派，並在 PUT 保護來源管理活存。前端只修改在途提示，API／DTO／DB／Swagger／排程節拍不變。
- 規格：機械 BLOCK 0／CHECK 0；首輪 source 降級 finding 已修，第二輪獨立審查 critical 0／major 0／minor 0。實作後架構審查 critical 0／major 0／minor 0。
- 測試：Java 25 指定 focused suite 共 58 項，0 failures／errors／skipped（AssetService 31、結清純規則 15、日期 2、PostgreSQL 10）。正式 schema source fixture 改為 FUBON_SYNC 後，PostgreSQL 10 項重新全通過。既有前端契約 4／4 通過；git diff --check 通過。
- PostgreSQL 證據：入帳與刪除失敗全部回滾；同 owner 兩交易實際等待 parent lock、只結清一次；第二輪零 DML；兩幣別來源管理完整 PUT 保護與舊 id 拒絕。全部使用獨立 Testcontainers，正式資料未寫測試款項。
- runtime 驗收：從 feature 重建 business-services／frontend 並 force-recreate；business healthy、BFF UP 且未重啟；實際 latest assets GET 200 且 deposits 含 processingDate，jar 含新 helper，HTTP 的 SnapshotFormView-HDrWBJhe.js 含新提示。effective env／bind mounts 與權威 main 一致（未輸出秘密），正式僅做唯讀驗收。
- 驗收日誌：/tmp/t510-backend-tests.log、/tmp/t510-postgres-source-recheck.log、/tmp/task510-run-stack-20261025-224624/。服務 image：business 2ccbc45a7b43593a4b437c85ca710b5c8b9de6c45592bf2a2956a6a4a29cce99，frontend 22b4482a5e9274ffc0a8592f2808108dc33e68557fb5adfcb082d2175acbe2bf。
- 收尾依專案 commit/no-ff merge/push 後，從乾淨 main 再部署並驗遠端 SHA；不追補舊版已刪列。
