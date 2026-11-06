# [t559] 富邦當日買賣改投影官方淨交割款

**對應 Requirements:** Requirement 129（富邦未來交割款投影）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-06真SDK列11/5→11/9 buySettlement=-43181；11/6→11/10 buyValue131220、buyFee186、buyTax0、buySettlement=-131406、sellValue40800、sellFee58、sellTax40、sellSettlement40702、totalTax40、totalSettlementAmount=-90704。現系統分成131406待付與40702待收，且今日ledger全MANUAL因此generic note永久保留。使用者要求修正當沖在途款，正確是依SDK total投影11/10待付90704，11/9待付43181保留；不從本地成交或截圖重算官方款。DB SELL40.80與截圖40.88不同，不擅自改成交。

## 要做什麼

- [x] 559.1 既有完整SDK envelope／owner／freshness／日期／TWD／required金額／符號／buy+sell=total／重複驗證不放寬。future AVAILABLE以SDK totalSettlementAmount直接分類負數待付、正數待收。summary按各日期net分類合計，futureRowCount語意不變。不改Python/SDK來源、API、schema、flags、排程、Swagger、SRPP。
- [x] 559.2 writer在原lock／transaction內先純求既有gross-buy驗證下精確可銜接的 matchedManual 集合（sourceQueryDate=queryDate、原owner/bank/settlementDate/唯一金額與notes stockCode條件），不先刪除；雙向guard只排除該集合。先驗其餘同bank兩方向所有候選：wrong currency、null date、同日duplicate為AMBIGUOUS_TARGET，MANUAL碰撞為UNMANAGED_TARGET；保留既有整類候選guard。全部guard成功後才原子刪matchedManual並投影。官方row任一gross方向非零時，移除同settlementDate另一方向FUBON_SYNC列並更新/建淨額列；兩方向非零且淨零刪該日兩方向AUTO。all-zero／missing row／placeholder保留，不碰其他日期/銀行/owner/存款/ledger。rollback、freshness與aggregate guard保留。
- [x] 559.3 pure ledger note reconciler僅讀owner+sourceQueryDate+富邦證券+台股+TWD+股票+買賣+MANUAL/FUBON_SYNC已存ledger。驗合法positive shares/price、amount=shares×price、nonnegative integer fee/tax及來源gross買賣amount/fee/tax/settlement完全一致（optional來源tax null時仍須totalTax、gross方向settlement一致）；有證據才產生sorted代號買／賣及淨交割note覆蓋generic，200字內，過長明示摘要；不聲稱當沖配對。無證據保留既有非空note，新列generic標sourceQueryDate及settlementDate，不用queryDate假裝成交日。既有手動待付消重只用原gross-buy evidence及原bank/date/唯一金額條件，不能用net消重。
- [x] 559.4 測試fake來源：真案例 -90704、反向淨收、mixed淨零、單邊買賣、all-zero/omitted保留、不同日期不互抵、老gross AUTO轉net、單邊BUY及mixed買賣完整gross證據可銜接原manual待付且重跑一次、可銜接manual加另一方向manual/wrong-currency/null-date/duplicate時仍整批拒絕且matchedManual未刪、兩方向manual/wrong currency/null-date/duplicate拒絕、rollback、summary、重跑、source-date與MANUAL備註及不完整/不符證據不覆寫。測試不使用實帳戶。實機只重建business-services、核對main設定、既有settlement-sync先dry-run再兩次false，唯讀回查11/9及11/10與所有其他存款/ledger不變。不執行銀行存款同步或券商寫入。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*FubonAccounting*,*FubonSettlement*,*FubonPayable*,*FubonTransit*' test
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

部署由run-stack執行並以安全GET驗服務；token只讀掛載檔不輸出。從API及DB讀11/9仍43181、11/10唯一待付90704且待收40702已移除，其他存款/ledger逐欄保持一致，再由獨立arch review及commit-merge-push收尾。

## 完成報告

- 完成 Java 官方逐交割日淨額投影、同日反方向 AUTO 原子移除、淨零處理及 summary；all-zero／missing／placeholder不清款。matchedManual先純求集合、雙向guard通過後才移除，原gross-buy手動銜接條件不變。新增完整MANUAL/FUBON_SYNC ledger備註核對，使用SDK sourceQueryDate，證據不足保留原備註。無schema／Python／排程／flags／公開API／Swagger／SRPP變更。
- 聚焦10 suites共230項測試全部通過，0 failure／error／skipped，其中真PostgreSQL writer34項。新增gross轉net、反向淨收、mixed淨零、不同日期保留、手動銜接與另一方向manual／currency／null-date／duplicate防線、刪除rollback、單邊SELL及真90704證據。既有備註精確字串因「已記錄成交／淨付」新契約更新；新測試fixture明確persist後取得生成ID，未放寬業務保護。紀錄：`/tmp/00881-tests-r3.log`。
- 獨立spec R2審查critical0／major0／minor0，機械BLOCK0／CHECK0；已記錄spec gate。實作經獨立arch審查並記錄gate，`--status`確認「已通過架構查證且實作碼未再變動」（hash前綴3d9571688bf0）。`git diff --check`通過。
- run-stack已重建並recreate business-services及完成實機驗收。既有settlement-sync的dry-run為DRY_RUN，兩次正式同步均SUCCESS，summary payable=-133885、receivable=0、rowCount=2。11/9 id16807 -43181保留（僅備註更新）；11/10 id16808修為唯一待付-90704，備註含買0050、00881及賣00881；舊待收40702 id16809已移除。其餘157筆存款逐欄不變，ledger完整bytes不變。僅更新本系統記錄與衍生摘要，無券商寫入或銀行餘額更新。
- 驗收後依commit-merge-push兩段式流程收尾；feature與main的最終SHA、remote ref及乾淨狀態由發布回報提供。本段僅回填完成事實，不更改功能契約或實作。
