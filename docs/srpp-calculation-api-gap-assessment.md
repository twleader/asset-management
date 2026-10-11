# Claude SRPP 計算 API 提案評估（2026-10-11）

來源：使用者 /Users/steven/Downloads/SRPP_CALCULATION_API_GAPS_SPEC_1.md，候選提案而非 Accepted policy。只讀對照 Task489 實作與當前 SRPP 已接受政策；未查詢金融 API／DB／broker／Gmail。

A1 order-plan 與 A2 symbol-rule-facts 確有用途，列為下一階段候選；本次尚未新增這兩支計算 API。使用者已指定今晚不修改 SRPP，M1 schemaVersion 2 與對應 validator、規則／Swagger 採用必須一起另日處理。Task492 只新增非同步觀測 API，不把觀測紀錄當成計算或成交證據。

- P1：classpath model_inputs／policy_manifest 原bytes已與 SRPP 相同，不能稱尚未同步；DB registry當下未查證。逐模組formula coverage只可在全域bundle/原始bytes/targets/L0/owner/Swagger均已驗證後做依賴閉包；资金模組未支援時依賴買腿也不可正數。現行authority用classpath，不能宣稱只註冊hash即可零部署參數更新。
- P2：Task489已有富邦官方唯讀完整pending/inventory、120秒時效與帳號綁定；國泰、exact leg/order/fill及歷史quote revision仍缺。不能從空/未知來源推成零或從AI流程文字推成交。
- M1：A1前提。穩定legId、共享分配前策略上限、capacityOnlyBlocked明確PASS來源、risk適用性與pair身份；新增schemaVersion必須同步BFF及SRPP validator，不silent容錯。
- A1：必要真gap。既有sharedFunds僅固定模組順序分配，否決後沒有純重算工具。只釋放本候選計畫的未執行分配，不能釋放frozen官方掛單與歷史reservations。Seller有共享容量限制時固定D144 week52Position↓、excessAmount↓、symbol↑，LLM只刪除/等待/減量；真policy/source/selection BLOCKED不能因capacityOnlyBlocked恢復。初版不遞補落選D195/D198標的、不將未交割賣款加入買腿資金。
- A1最新quote：需要背景producer已保存的可重播immutable revision向量，GET不得寫來源。最新價必重驗受影響的tier/high gate/個別群組容量/fees/depth，不只改limit；asOf與riskEvidenceSha不是caller自報就已驗証。可先以另一FINAL frozen package作已保存quote capture，但不可混新quote與舊未一致的相關gate。無可重播來源時不發布假LATEST_CACHE成功。
- A2：必要，補通用低檔/過熱分項、方向、membership，引用已算D189/D198/D195/CORE receipts，純投影frozen market:<市場>:<代號>。雷達原值與D188/D189完成日序列口徑分開；各field/unit/date/revision/receipt綁定。沿用現有PERCENT unit而非另造PCT，null分項保留。每輪精確radar detail一次仍不可被A2取代。
- M2：核心賣出數量是真gap，但 consumer-state port現固定UNAVAILABLE。必須先有正式雙軌statefeed（baseline/已確認批次/成交冷卻），不猜可執行量。
- M3：已有cadence、locked ladder、reserve FIFO/age與Python golden，不重寫既有算式。需補正式逐檔live ledger/windows/actual legs。pair預算上限、已交割資金、獨立現金分開；附件min(netSell,reserve,...) reserve0會違反D184。廣泛股票基準未明定，不自行用TAIEX/0000代理。既有00882例外、SGOV D185豁免與已對帳部分恢復50%下限不可遺漏。
- M5：現已使用DCA scheduled-unfilled占用與stop-state，補公開next debit/stop target/confirmed state。未知券商假日规则維持null；沒有完整歷史/短線歸屬不猜sticky停扣。
- M7：補戰略預算/待美股session驗證的計畫有用；units/limit=null、國泰實際FX缺失/費用LOWER_BOUND時不能ACTIONABLE或executable。D133戰術仍22:00任務。
- M4：ADHOC為另有目的的完成收盤資料契約，週報/季檢可讀最近完成日；不能用作日報calendarfirst/當天/09:05&12:00繞道，原負例保留。
- M6：最後文件治理，替代能力真的發布/採用後才標superseded。COMPLETED_TECHNICALS替代為既有完成日API而非同名report module。

主要程式：backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java:25/53/54/56/77/83/90/108；SrppReportStateCalculator.java:17；SrppReportReadService.java:35（完整authority）；SrppReportReservationCapture.java:36（歷史占用）；SrppReportAuthority.java:15。
SRPP：docs/API_CALCULATION_RULES.md:7/11/15；docs/DAILY_TRADING_AUTOMATION.md:272（D144）；assumptions/model_inputs.json:314（D184）/639（D144）；scripts/verify_srpp_report_calculations.py:416。
Schema：db/schema.sql:3029 五檔latest-only；OpenAPI:4150 已有units。固定proposal賣方排序／pair公式錯誤不能採用。

後續順序建議：Task491升級→Task492 AI流程觀測/雙軌/90天→另立自足SDD審查M1/A1/A2並與SRPP validator一同採用→補可重播final quote及已明確來源模組。此評估不保留Task493編號，也不宣稱後續功能已實作。90天只套AI流程，不改金融reservations/成交資料/報價history。已有真證據來源才作正常runtime驗收；合成fixture只證公式/邊界，不證投資準確度或報告已完成。
