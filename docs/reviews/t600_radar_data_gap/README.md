# Task600：009826 資料不足調查與修復

調查日：2026-11-17（Asia/Taipei）。

- 完成日K：2026-08-03至2026-11-16，權威交易日曆expected71、DB actual71、missing=[]；不是漏抓。MA20/60/KD可得，MA240與年線兩日確認（需241根完成日K）不足，三軌NO_TRADE維持。
- 分鐘K：同日24檔capture出現INVALID_RESPONSE。真SDK的root timeframe為字串"1"，現行adapter與整數1比較而拒絕；009826同次211根列皆能通過現行逐列parser。修復限exact int1/string1同義，拒bool/float/其他；其他資料閘門不動。
- 配息：既存resolution為STALE、knownAt 2026-11-04。現行官方HTTP成功無法證明未來45日公告完整性，必維持PARTIAL/缺完整性證據，風險披露不改成無事件。
- 今日SRPP兩軌：Codex 12:00輸出VETO，exact_radar=UNVERIFIED/EXACT_RADAR_REQUIRED_TECHNICALS_INCOMPLETE、radar_direction=BLOCKED/RADAR_NO_TRADE，另quote_freshness=UNVERIFIED。Claude同日12:00把NO_TRADE-neutral視為PASS並ADOPT。這是既有報告證據差異，本次不修改歷史報告、SRPP規則或硬閘，不承諾分鐘修復即允許買進。

精簡實證：sdk-public-summary.json、calendar-comparison.json、srpp-consumer-comparison.json。原始完整SDK或私人帳務未複製。

## 驗收

獨立規格審查PASS（critical0/major0/minor0）；spec-check BLOCK0/CHECK0。Java四類聚焦測試共45項全過；Python分鐘來源與既有市場資料共57項全過。Java涵蓋71根/live排除、240根僅缺年線確認、技術basis缺項、股票/債券大盤適用邊界、配息STALE/空reason及分鐘UNAVAILABLE原因。獨立架構審查PASS（critical0/major0/minor0），已記錄spec/arch gates。重建與實機讀回由主agent補入。


### Feature 實機驗收（2026-11-17 12:54）

從本次feature重建並recreate `fubon-broker-service`、`business-services`。兩服務healthy、effective環境變數與main一致，憑證掛載實體與main一致，沒有重啟BFF；9090 market-index讀回JSON正常。

- 唯讀adapter HTTP200：009826 canonical timeframe為integer 1、status=AVAILABLE、219根分鐘棒。
- 未手動啟動producer。既有每分鐘5檔背景輪替將今日capture由22檔全部`UNAVAILABLE/INVALID_RESPONSE`恢復為22檔全部`AVAILABLE`；009826的capture為12:54:00，latest_completed_at為12:53:00。
- 9090明確列出「71／241根（不含盤中K）；必要資料缺少：MA240、MA240兩日確認」，並披露配息STALE、2026-11-04 knownAt及scope未覆蓋至+45日原因。三軌仍NO_TRADE、score=null。
- 雷達分鐘確認現為`MISSING_CONSECUTIVE_COMPLETED_BARS`，與來源抓取故障分開：DB所需12:40–12:49區間確實缺12:44棒。沒有補造未成交分鐘或放寬連續棒條件；本次成功是修復來源解析，並非宣稱買進條件已成立。

精簡證據：`runtime-feature-summary.json`、`radar-feature-summary.json`及`capture-feature-{before,after}.txt`；完整build log留於本機/tmp/t600-release，未保存憑證或私人帳務。
