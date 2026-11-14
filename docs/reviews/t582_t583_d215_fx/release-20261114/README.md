# D-215／V28 與兩年美元即期匯率發布驗收

2026-11-14 已將兩專案落地、精確登錄並部署必要服務。首輪規則包（由下述1.28.1文件勘誤身分接替） `1ae220819d750d7016175ea0e5e8f616ae2e7551cbdbe1c1ac15238a3c4bbbb0`；Swagger `85c7e3d7f84bf9fceec94fb4efee876f910bfe936537a37c145518bfd1f75001`。來源與遠端提交見 `source-git-delivery.json`；本次收尾只新增 schema、完成狀態及驗收證據，不變更已部署程式與 authority bytes。

具名52週回撤免止穩規則為0050／00881達20%、七個指定美股代碼達30%；非長期實際首批買進固定當時前高，回復該前高僅提供退出觀察。美元資產買進匯率偏好採銀行即期賣出成本>32.8或兩年區間位置>=90%；經證明ETF自身回撤嚴格>10%、個股>=30%可解除FX偏好，其他資金、額度、來源與交易條件仍保留。台幣交易債券ETF納入美元資產範圍。沒有下單、寄信、人工producer或改排程。

兩輪獨立架構審查皆無發現；SRPP186項測試、最終日期修正80項、authority Java76項、外部來源29項、BFF10項及registry工具回歸通過。111+15原計算器golden、正式policy原generator與authority gate通過。實際運行JAR的26筆來源分類／15筆美元資產範圍已載入驗證。

原始FinMind495筆銀行即期資料完成正式單交易回補，495筆價格及成員雜湊逐筆相同；4289筆總列數不變、3794筆非原生日期外資料及41筆未核實歷史列保留。完整原文、來源收據、實際公開HTTP本文與typed envelope附在本目錄。兩年窗口2024-11-14至2026-11-14，完成日2026-11-13，共491筆合格原生窗口資料，低點28.86（2025-07-03）、高點33.255（2025-03-31）。起日已有合格原生列，因此nullable historyBoundary為null，仍具完整跨度；41筆舊未核實列與1筆尚未完成的當日列不參與極值。根agent另外逐筆獨立核對來源原文、所有491筆價格與member SHA。

registry新增第49筆，既有48筆全文指紋不變；容器僅重建business-services、external-materials-service、BFF，另外6個容器與有效設定保持。actual DB schema已重產120張表並逐位元drift查核通過。執行中JAR的5份authority資源與已合併來源逐位元相同，雷達HTTP200回TW_RULES_V28。從實際SRPP環境三個連線GET皆HTTP200且有資料。

精確新bundle及Swagger的美股業務API實測回409 `OUTSIDE_REPORT_WINDOW`，證明身分驗證後依時窗拒絕；不宣稱正式排程計算採用。合法時段正式日報採用仍為 `NOT_YET_EXECUTED`。本目錄中任何行情與LIVE驗證僅代表該GET收據當時狀態，不構成後續交易許可；後來回補的來源也不作歷史策略績效證明。

## 1.28.1 兩年匯率文件勘誤

路由5原摘要、說明及合成範例仍顯示一年，現已與既有兩年API行為對齊。沒有改金融數值、資料欄位、來源價格、排程或交易閘門；BFF例外類別只改同一行Javadoc，編譯前後class bytes相同。最終文件契約1.28.1，規則包 `8c5efa165b270731451d1412af8cbe8439a4855a1b96a53c1a435a5d59cf2c1f`，Swagger `5704b5599bf948e0c9d3398aa88929c26fcdad885117845bbdfbf9156c4d6683`；model bytes未變。111+15golden金融輸出與calculator hashes逐值不變。

首輪registry／495筆來源回補／491筆原文獨立驗收收據完整保留。文件勘誤的新身分採兩專案落地、insert-only登錄及僅business-services更新的同一發布流程，不重複回補，也不重建已部署BFF／external。新身分與後續驗收見 `metadata-1.28.1/`。

1.28.1最終驗收完成：兩專案新main／remote精確refs見metadata目錄git-delivery.json；registry新增第50筆且既有49筆完整指紋不變。只重建／recreate business-services，另外8個容器與image保持不變；1762個business class與首輪已驗收版本逐位元相同，新JAR的5份authority資源與合併來源完全相同。實際新JAR分類載入26／15通過。新FX GET仍為533公開列／491已核實完成原生列，與根agent逐筆稽核的序列完全相同；維護3個GET均PASS，精確新8c5＋5704身分的美股業務GET仍回409 OUTSIDE_REPORT_WINDOW。沒有重複回補；正式合法時窗採用保持NOT_YET_EXECUTED。
