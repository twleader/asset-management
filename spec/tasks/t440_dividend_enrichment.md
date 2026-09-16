# [t440] 完成且完整價格證據才回填現金股利

**對應 Requirements:** Requirement 158（完成且完整價格證據才回填現金股利）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

新main保存四日期並有投影，但enrichment缺漏不會由本機price history補齊。舊單日期分支價格無asOf、會跨缺口，因此重設計而不覆蓋目前投影事件模型。純讀雷達/public不得加寫入。

## 要做什麼

- 保留main四日期（exDividendDate/exRightsDate/cashPaymentDate/stockPaymentDate）與min非null兩除權息日anchor的事件身份／cancel語意；只對ACTIVE、正現金股利且有exDividendDate之事件回填現金enrichment，不把純配股anchor誤作現金除息日。partial yield-only null亦候選；僅填原欄null，不改既有非null/provider facts。
- 獨立proxy bean的REQUIRES_NEW回填與既有projection各自fail-soft。只接既有有投影副作用findFromDb流程；findFromDbReadOnly、public/9090、radar、batch pure-read不得接回填或外呼。價格讀取有顯式asOf上界，asOf由市場時區／本地cache-only交易日與完成收盤證據判定，不能把當日盤中／future列當完成K。
- 現金昨收是exDividendDate前一個權威交易session的原始正收盤，除息日已完成且bar存在；本地交易日曆可證該session且價格完整才填。從exDate到第一個已完成close>=previousClose的區間不得缺任何權威交易session，fillDays為0起算完成交易session位移；無hit或曆／資料缺口為null。資料日期嚴格升序/唯一、null/非正拒絕；不得跳過無效價格或以週末推斷future。跨另一除權息／分割等公司行動且無同basis證據時fillDays仍null，不以raw跨基礎比大小。
- yieldPct=cashDividend/previousClose*100，scale4 HALF_UP；previousClose遵守DB numeric(15,4)範圍、yield與fill範圍先驗證。只有本次可證值且current仍ACTIVE、owner-independent exact stock/market/event id/兩除權息日及金額身份相同時原子COALESCE缺值更新；若併發projection變更／取消／刪除則零寫，不復活、不覆寫剛填值。無價格／證據直接無寫入，零新增券商功能、零schema變更。

- [x] 440.1 保留現版DividendDates與Event/ProjectedEvent/Active detail、anchor=min非null日期、cancel identity與payment metadata。新增獨立Dividend enrichment service proxy，不把整舊類套入。findFromDb目前side-effect projection與cold-cache行為不擴張；在其projectFailSoft兩段獨立catch下呼叫，回填失敗不連坐projection。findFromDbReadOnly、pure-read quote child/list/public/radar不呼叫它。
- [x] 440.2 repository candidates攜完整兩除權息日與cash/stock金額、id/code/market原身份；ACTIVE且cash>0/exDividendDate有效，任一enrichment欄null皆候選，包含只有yield null。pure rights/null cash/非正cash不補cash yield。候選無資料zero price queries。
- [x] 440.3 重用現有MarketDataService.isTradingDayCachedOnly(market,date)（TW現有本地權威日曆快照、cache-only且不得外呼、US/UK現有local曆），UNKNOWN fail closed；不能用isTradingDayKnown或建立另一Redis格式。市場本地cache-only交易曆與完成收盤authority給completedAsOf，缺authority不回填；只讀existing DB/session calendar，不為證明而外呼。價格SQL有上下界、ASC unique日期、全部positive；exDate必完成且正bar存在，exact preceding-session raw close作basis。不能用首>=exDate近似。保留20日曆日最大回看前價；更久停牌/缺資料未知。
- [x] 440.4 previousClose可證時僅其原null可填，yield以本次可證basis或仍相同身份的既有正previousClose且其basis可證計算cash/basis*100 scale4。fillDays只在price/session coverage完整且無未證basis公司行動、first hit已完成時按交易session0起算。exRightsDate不同且落在比對區間、其他新除權息/分割無同basis校正證明皆不填fill。日曆未知/日期缺口/未完成/null/負/0/亂序/重複全部保守null；不得跳過不良row續算。
- [x] 440.5 write guarded current ACTIVE完整身份且原欄IS NULL，atomic COALESCE不覆蓋新值、不改updatedAt除非真的fill。若event amend/cancel/delete競態zero write，不復活。不改source/event facts/snapshot/candidate action/scoring或任何schema。量值先驗證schema numeric precision/scale與integer overflow，失敗不寫。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
```

新增單元證據：cash/rights不同日且anchor仍正確、pure rights不補cash、yield-only null候選、19日/20日floor、known周末與平日休市僅靠權威曆、future/盤中不得算、除息日缺bar不能跨過、prior session缺bar、fill中間gap不少算、當日hit=0、no hit null、再次公司行動unknown、null/非正/duplicate/亂序、各欄既有非null保存、overflow fail closed。proxy真交易及bounded latch PostgreSQL fixture證event取消/金額變更/刪除與另一writer剛fill不被覆蓋。pure-read service和public/radar路徑spy證零enrichment、零sync/外呼。mock/fake externalClient，全部不觸真人資料/實機cold-cache。

Docker只讀既有price/dividend/current-state查看部屬source provenance，不為驗收呼叫side-effect findFromDb/cold sync；runtime缺合格證據標未知。


驗收不呼叫券商、不下單、不觸發外部通知，不讀出secret。新增單元／整合測試為待實作，不以不存在的具名測試類作已完成證據。實作後依project規定作獨立diff-scoped架構審查、Docker image rebuild/container recreate（從main同步環境或先比對.env，不輸出值）與只讀功能驗收；不以container healthy取代資料語意。最後自動focused commit/no-ff merge/push，main部署與版本一致。

## 完成報告

已完成獨立 REQUIRES_NEW 現金 enrichment、既有副作用路徑獨立 fail-soft、本機 cache-only 完成收盤日／20 日曆回看、精確前 session 與原始價格、numeric 範圍、ACTIVE／兩除權息日／金額身份原子 COALESCE。已驗取消／修改／刪除／另一 writer 競爭與 pure-read 零寫。缺乏可信分割／跨日價格基礎證據時，多日 fillDays 維持未知；同日 hit 也排除本事件及其他 ACTIVE 事件在比較區間的已知公司行動。無 schema 變更，不以 runtime 副作用查詢驗收。

本批後端最新256組測試報告共2214項、失敗0／錯誤0；BFF全套67組316項、失敗0／錯誤0。獨立架構最終覆核0 critical／0 major／0 minor；機械 spec／OpenAPI／兩鏡像／102表 schema drift 已通過。feature business／frontend／BFF 均已重建並重新部署驗收；分組提交、no-ff main 合併推送與 main 最終重建於本批收尾執行，不由測試替代部署。
