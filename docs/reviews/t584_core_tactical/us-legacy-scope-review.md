# 舊usFull低接阻擋finding的調用查證

本輪review指出`SrppReportShortUsCalculator.usFull`仍套普通MA200／RISK_OFF／止穩門檻。此finding不適用目前正式US API路徑，判定為scope誤判，未為清除finding擴張舊schema或解除fail-closed。

- 全`backend/src/main`對單字`usFull`的搜尋只有`service/srpp/report/SrppReportShortUsCalculator.java:155`的宣告；沒有production caller。其餘三個呼叫位置只在`SrppReportShortUsCalculatorTest.java:102/103/112`的隔離舊普通路徑測試。
- 台股十二模組的`SrppReportShortUsCalculator.calculate`於`:171`明列`US_TACTICAL_TW_REPORT_HORIZON`／`NOT_EVALUATED`／`HORIZON_NOT_APPLICABLE`，不呼叫`usFull`。
- 正式US發布者`service/srpp/us/UsReportPublisher.java:42`呼叫`UsTacticalCalculator.calculate`；其`:23/29`進入新版evaluate，`:78`經已驗canonical radar與candidate binding後呼叫`UsNamedDrawdownD215.eligible`，`:91`與`:99`把同一namedDeepDrawdown結果送到independentGate及fullChecks。只有合格具名路徑才豁免普通RISK_OFF／MA200／止穩／後批方向，其他硬閘原樣保留。
- 新版US計算器只重用舊`usCheck`的純限價／成本／額度算術，沒有呼叫`usFull`。
- 最終已同步D216 policy的`UsTacticalCalculatorTest`17項全過，包含具名RISK_OFF、MA200下行、無止穩正例、來源竄改反例及原funding／fundamental等否決。這是離線行為證據，不是實際交易時窗API採用。

