# [t584] D-216核心與短線獨立容量及日頻牛市核心保留

**對應 Requirements:** Requirement 207（SRPP核心／短線獨立額度、低接不重複否決及日頻核心保留）
**前置任務:** t542、t582、t583
**Liquibase changeset:** 無

## 背景

Steven要求修正SRPP交易邏輯並完成兩專案測試與發布。D-214已將0050核心目標提高到10%，但D-173仍用同代號核心加短線5%上限，核心達標時無短線容量。D-204把Nasdaq單日急跌直接硬擋全部新增股票風險，與具名深回撤機會衝突；D-156日頻窗口仍以股票50%下限計算可釋出預算，可能把長期核心減到戰略目標以下。本任務只改已核准的日頻規則，不預測頂底、不宣稱績效改善。

## 要做什麼

- [ ] 584.1 股票D-173總1%、單檔0.5%、最多2檔與計畫損失0.1%不變。`limits.combined_symbol_cap_mode=CORE_TARGET_PLUS_SLEEVE`、`ledger.strategy=SHORT_TERM_D173`時，合併上限為`max(limits.combined_symbol_cap,detailed_target_allocation_2027[code]+limits.per_position)`，以同輪扣成本分母計量；screen binary sizing與最終五檔限價重算必須使用同一helper。0050核心10%時cap10.5%；00881目標4.53%時cap5.03%；小目標保留原5%。沒有模式的歷史policy保留原值，未知模式／缺target／非法數字不補0。債券D-185仍原3%，不得套股票例外。
- [ ] 584.2 維持實際fill／pending／最新持股驗真reconcile，已確認短線才從核心份額扣除，合併曝險與資金不扣；缺帳本／拆分不得默認空帳。現有群組、全股票、funding、protectedCash、去重、雷達、成本、流動性與短線獨立退出不變。新增測試必須證明已達0050核心目標可有小額短線容量，但袖套、同代號新上限及原daily執行硬閘仍會封鎖。
- [ ] 584.3 D-204 Nasdaq最近完成日跌幅<=-5%改為風險揭露，非新增股票一刀切硬閘；官方一手直接台海戰爭／封鎖、六項防禦及所有真正來源／基本面／雷達／資金／配置硬閘保留。此評分與D-215獨立路徑採用在SRPP實作，資產端不新增重複否決、不繞過原UsNamedDrawdownD215正式來源驗真。D-215仍不得用本機解開API有效BLOCKED／零量；D-212普通換股暫停不再重複擋已驗證D-215獨立路徑。
- [ ] 584.4 增加共用pure日頻D-216牛市可釋出容量：只在`dynamic_allocation_policy_d141.daily_core_retention_d216={decision:D-216,enabled:true,aggregate_target_source:normal_equity_target,per_symbol_target_source:detailed_target_allocation_2027}`精確設定時，使用`max(0,equity-total*normal_equity_target)`。normal target須在原normal floor/cap內；缺設定沿歷史規則，設定存在但非法要fail closed。首次arm不超過當輪excess；既有active窗口只clamp當輪候選量、不重設高水位／起日／鎖定預算／已成交批次，不擴大舊windowBudget；clamp後仍須大於上一批實際成交，不足則等待。`regimeCapacity`簡化pure入口、full-state `ladderAdvance`及module最終cap都保持一致；不改月頻monthly_bull_first_equity_target或模型代理。
- [ ] 584.5 逐檔正目標核心一般減碼／D-156最終賣出不得賣穿該檔目標。資產端現有D-156只有aggregate facts、無executable SELL leg，故不得以aggregate容量receipt宣稱逐檔已驗證；SRPP final helper／guard負責同輪實際core/short split、目標、股數與具名必要風險／生活提款等原授權例外。SHORT_TERM與美股戰術的獨立退出不被核心保留誤擋；歸屬未知只影響該筆。D-174／D-211的1.5%回落與D>=80不改。
- [ ] 584.6 延伸既有SrppReportShortUsCalculatorTest與SrppReportStateTopupCalculatorTest，覆蓋新上限上下界、歷史股票policy／債券policy不變、最終重新限價容量、袖套／每日硬閘仍阻擋；新／既有bull窗口、56.53%等於／上下界、正常整理/創高無額外減碼、縮量後growing判斷、BEAR不受影響及非法設定拒絕。重產SRPP同輸入golden，比較Java與Python的日頻輸出；月頻代理無修改。不得為fixture捏造正式採用。
- [ ] 584.7 同步最終D-216／2.64.0 SRPP model／manifest原bytes，generator產policy document、golden與insert-only registration SQL，通過authority與限定測試。保持現有公開schema、Swagger bytes、端點、排程、通知與純讀架構，無DB schema、vendor I/O或broker write。獨立spec與arch審查後再正式發布。
- [ ] 584.8 由根agent統籌雙repo feature commit→main no-ff merge／push→精確remote refs；之後才正式insert-only registry、回讀exact row、乾淨main原.env只重建/recreate必要business-services，從SRPP以新hash業務API回讀。前置測試／離線build不替換正式image；時窗外只診斷且正式採用NOT_YET_EXECUTED，不手動producer／重跑報告／寄信／下單。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='SrppReportShortUsCalculatorTest,SrppReportStateTopupCalculatorTest,SrppReportModuleCalculatorTest,UsTacticalCalculatorTest' test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/d216-core-tactical/SRPP --check
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
```

根agent完成兩專案落地與registry後，從正式main與原正式設定執行`docker compose -p asset-management build business-services`和`docker compose -p asset-management up -d --no-deps --force-recreate business-services`，保存新image/container、healthy、JAR authority全文一致及exact hash業務回讀。無關容器與設定維持。

## 完成報告

待實作及測試完成後填寫實際證據；本任務不預先宣稱測試、發布或正式日報採用成功。
