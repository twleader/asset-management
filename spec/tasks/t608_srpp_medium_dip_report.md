# [t608] 完成0050／00881中期低接候選的報告評估

**對應 Requirements:** Requirement 215（台股中期候選逐路徑完整評估）
**前置任務:** t603、t605、t607
**Liquibase changeset:** 無

## 背景

今日雷達0050、00881已為ADD_CANDIDATE，日報卻未完成配置與資金評估；D223 final gate又對全部00881 BUY施38元門檻，阻擋獨立路徑。

## 要做什麼

- [ ] 608.1 在既有STRATEGIC_BUY_GATES為0050、00881輸出NAMED_DEEP_DRAWDOWN_D215 leg。依全部同輪凍結原始資料分別驗證具名52曆週回撤、價格、雷達、D188、D222配置、保護現金/資金、官方pending、所有策略reservation、防重、五檔、正式執行帳戶、thesis與可交易性。每項收據皆有PASS/BLOCK/UNAVAILABLE，缺失不跳過其他項目，不填猜測零值。
- [ ] 608.2 D222完整三檔還原52週區間與市場方向/FX決定選檔，不以先有交易授權作選檔前提；缺證、平手、部分容量不重流。僅完整目標可供交易容量。
- [ ] 608.3 既有leg欄位不變；正量lots為整數、units=null，broker=國泰證券、subaccount=CORE_D215；price/amount/fee為canonical decimal。只給報告容量，consumer仍須同輪風險採納。保留單檔/群組/依同輪政策旗標有效的全股票上限、現金底線、成本與既有BLOCKED；不新增批次比例。
- [ ] 608.4 D226取消D223成交價與限價<38條件，改同輪精確action=ADD_CANDIDATE與consumer的LLM採納；保留40萬跨日累計含費、掛單與預留，2881賣出嚴格>152及原閘、2885保留。完整獨立D215腿不被D223預算缺證覆蓋；不依任意purpose或client布林解封。加入D223本身取消價格條件（非僅D215豁免）的回歸。
- [ ] 608.5 不增加API、vendor I/O、券商寫入、寄信或下單；正式缺官方狀態時維持具名缺證。加入正量、高於38、缺源、硬阻擋及共享資金回歸測試。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppNamedCashRotationTest,SrppReportModuleCalculatorTest test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppReportResponseValidatorTest test
docker compose -p asset-management build business-services bff
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff
```

正式部署沿用授權跨repo發布，核對policy authority/registry/JAR精確bytes，合法時窗外不啟動producer或重跑報告。


- [ ] 608.6 BFF strict validator只允指定STRATEGIC_BUY_GATES/NAMED_DEEP_DRAWDOWN_D215/0050或00881組合，完整正量gate set與refs嚴格核對；加入正量、缺證及非法策略測試並部署bff。全股票旗標明確false不套用，歷史缺旗標維持上限，非法型別拒絕；保留有效個別/群組/現金/債券限制。

## 完成報告

待實作與驗收。
