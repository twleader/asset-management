# [t609] 完成2881 D226雷達賣方報告路徑

**對應 Requirements:** Requirement 216（2881雷達賣方獨立評估）
**前置任務:** t608
**Liquibase changeset:** 無

## 背景

使用者追加授權：2881精確雷達EXIT_CANDIDATE或REDUCE_CANDIDATE且LLM時機合宜即可評估賣出，取消原152與D174高檔/KD擇時。本任務取代Task608及舊D223文件對2881的該價格/擇時條件，保留D152數量、5日成交冷卻、正式可賣量、費稅、核心保留及去重。

## 要做什麼

- [ ] 609.1 新增純SrppNamedRadarSellCalculator，由STRATEGIC_BUY_GATES產NAMED_RADAR_ROTATION_D226/2881/台股/SELL leg；lots=null，units正整數字串，broker以官方executionAccounts.CORE_D226對照snapshot brokerId，subaccount=CORE_D226。API只給容量上限，risk及LLM時機由consumer同輪採納，不偽造已採納。
- [ ] 609.2 全部14個gate獨立輸出PASS/BLOCK/UNAVAILABLE：D226_SELL_PATH/QUOTE/RADAR/POSITION/PENDING/RESERVATIONS/DEDUPLICATION/CORE_RETENTION/D152_BATCH/COOLDOWN/DEPTH/EXECUTION_ACCOUNT/COSTS/TRADABILITY。QUOTE/RADAR/DEPTH refs=policy/market:台股:2881；TRADABILITY refs=policy/market:台股:2881/topup-state；其餘refs=assets/policy/topup-state/report-reservations/classifications/market:台股:2881。缺源不猜空/零、不中斷其他gate檢核。
- [ ] 609.3 正式topup-state同owner/date/bundle、verified且coverage COMPLETE。executionAccounts.CORE_D226必含verified、ownerUserId、brokerId、broker、subaccount、accountFingerprint、costs={verified,brokerageRate,minimumFeeTwd,sellTaxRate,source_ref}；sellablePositions={verified,coverage COMPLETE,rows:[{holdingId,symbol,market,brokerId,accountFingerprint,settledShares,sellableShares,source_ref}]}；逐holding與資產對帳。官方量min(sellableShares,settledShares-pending-未連結預留)，linkedOrderId去重。
- [ ] 609.4 rotationExit.2881必含verified、planId、baselineSnapshot={id,snapshotDate,stocks:[原正式stock rows]}、fills={verified,coverage COMPLETE,rows:[{fillId,batchId,symbol,market,brokerId,accountFingerprint,side,shares,tradeDate,confirmed,planId}]}及source_ref。重播baseline股數-已驗SELL fills=現有股數，batchId只計已成交，completedSessions驗5完成日冷卻；基準<=5000股且已驗原市值<100萬才清零，否則固定基準前兩批各1/3、第三批殘額。缺起始市值不授小額例外。零股另需market:台股:2881.oddLotBook，bookType=ODD_LOT、volumeUnit=SHARES、五檔bidVolumeShares，其他sourceTime/fetchedAt/stockCode/market/available/marketStatus/source沿既有同輪book規範；整張book不冒充零股。
- [ ] 609.4a 第3輪修正冷卻來源：D152重播/批次仍只用基準後、本plan已確認fills；COOLDOWN改用獨立topup-state.rotationCooldown.2881={verified:true,coverage:COMPLETE,scope:OWNER_ALL_BROKERS_SAME_SYMBOL_DIRECTION,symbol:2881,market:台股,side:SELL,coverageStart,coverageEnd,source_ref,rows:[{fillId,symbol,market,side,shares,tradeDate,confirmed:true,brokerId}]}。coverageStart不晚於最近5個已完成台股交易日第一日、coverageEnd恰為本輪交易日；rows涵蓋全owner所有券商該代號同方向已確認成交，不能用planId或基準日截斷。若同窗口內plan fills有記錄，global rows必精確包含同fillId/日期/股數/brokerId，否則UNKNOWN/UNAVAILABLE。缺此完整來源不得從plan fills空陣列推無冷卻。基準日前一交易日已有SELL的案例須阻擋，真正完成5個交易日後才解除。

- [ ] 609.5 2881只以權威zero-target退出，不把正核心標成短線，不創正target，也不賣其他核心。D144缺官方可交易性只用買一限價，原批次量與總五檔深度仍維持，唯一可UNAVAILABLE reason=D144_OFFICIAL_SOURCE_UNAVAILABLE_BEST_LEVEL_LIMIT_ONLY；真禁賣仍BLOCK。費稅只用已驗costs來源，account.costs.sellTaxRate必須精確等於同版model.execution_policy.tactical_profit_policy.stock_sell_tax且stock_sell_tax_source非空；本輪財政部一般股票賣稅為0.003，不能用ETF的0.001。實際brokerageRate/minimumFeeTwd仍依已驗同帳來源，不自猜折扣或最低費用；淨款交割前不給買方。
- [ ] 609.6 BFF只接受上述精確module/strategy/symbol/SELL/units/帳戶/14gate及refs，OpenAPI說明與formula source hash同版同步。不增路由、vendor抓取、券商寫入、寄信或下單。新增正常低於152且D174不通過仍正量、缺可賣來源、雷達非賣方、批次/5日冷卻、掛單去重、D144、費稅與零股市場回歸。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppNamedCashRotationTest,SrppReportMediumDipCalculatorTest,SrppNamedRadarSellCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
docker compose -p asset-management build business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff
```

新seller測試完成後納入同輪Maven，完整synthetic package經consumer驗證；正式部署由授權release operator完成，時窗外不補跑日報、不冒稱自然時窗採納。

## 完成報告

待實作驗收。
