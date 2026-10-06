# [t479] 富邦來源列手動存檔吸收股數、持股成本、交易日期

**對應 Requirements:** Requirement 179（延伸 Requirement 90／138）
**前置任務:** t371
**Liquibase changeset:** 無

## 背景

今日最新快照的富邦台股列為 SOURCE_OWNED，完整 PUT 只吸收成本，股數與交易日期被靜默丟棄，且同步會清掉交易日期，造成「存檔成功但數字沒變」。

## 要做什麼

- [ ] 479.1 `AssetService.applySourceOwnedManualCosts`：唯一 TWD 候選時吸收 `investmentCost`、`shares`、`transactionDate`；不合法值該列零 mutation（沿用既有語意）。
- [ ] 479.2 `FubonInventoryWriter`：重寫列保留前一列 `transactionDate`。
- [ ] 479.3 測試：`AssetServiceTest` 三欄位吸收與不合法零 mutation；writer 測試保留交易日期。
- [ ] 479.4 spec／design 同步，`/run-stack` 重建 `business-services` 驗收。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='AssetServiceTest,FubonInventoryWriter*Test' test
```

## 完成報告

（實作者回填）
