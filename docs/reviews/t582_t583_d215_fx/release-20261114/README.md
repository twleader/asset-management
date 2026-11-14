# D-215／V28 與兩年美元即期匯率發布驗收

2026-11-14 已將兩專案落地、精確登錄並部署必要服務。最終規則包 `1ae220819d750d7016175ea0e5e8f616ae2e7551cbdbe1c1ac15238a3c4bbbb0`；Swagger `85c7e3d7f84bf9fceec94fb4efee876f910bfe936537a37c145518bfd1f75001`。來源與遠端提交見 `source-git-delivery.json`；本次收尾只新增 schema、完成狀態及驗收證據，不變更已部署程式與 authority bytes。

具名52週回撤免止穩規則為0050／00881達20%、七個指定美股代碼達30%；非長期實際首批買進固定當時前高，回復該前高僅提供退出觀察。美元資產買進匯率偏好採銀行即期賣出成本>32.8或兩年區間位置>=90%；經證明ETF自身回撤嚴格>10%、個股>=30%可解除FX偏好，其他資金、額度、來源與交易條件仍保留。台幣交易債券ETF納入美元資產範圍。沒有下單、寄信、人工producer或改排程。

兩輪獨立架構審查皆無發現；SRPP186項測試、最終日期修正80項、authority Java76項、外部來源29項、BFF10項及registry工具回歸通過。111+15原計算器golden、正式policy原generator與authority gate通過。實際運行JAR的26筆來源分類／15筆美元資產範圍已載入驗證。

原始FinMind495筆銀行即期資料完成正式單交易回補，495筆價格及成員雜湊逐筆相同；4289筆總列數不變、3794筆非原生日期外資料及41筆未核實歷史列保留。完整原文、來源收據、實際公開HTTP本文與typed envelope附在本目錄。兩年窗口2024-11-14至2026-11-14，完成日2026-11-13，共491筆合格原生窗口資料，低點28.86（2025-07-03）、高點33.255（2025-03-31）。起日已有合格原生列，因此nullable historyBoundary為null，仍具完整跨度；41筆舊未核實列與1筆尚未完成的當日列不參與極值。根agent另外逐筆獨立核對來源原文、所有491筆價格與member SHA。

registry新增第49筆，既有48筆全文指紋不變；容器僅重建business-services、external-materials-service、BFF，另外6個容器與有效設定保持。actual DB schema已重產120張表並逐位元drift查核通過。執行中JAR的5份authority資源與已合併來源逐位元相同，雷達HTTP200回TW_RULES_V28。從實際SRPP環境三個連線GET皆HTTP200且有資料。

精確新bundle及Swagger的美股業務API實測回409 `OUTSIDE_REPORT_WINDOW`，證明身分驗證後依時窗拒絕；不宣稱正式排程計算採用。合法時段正式日報採用仍為 `NOT_YET_EXECUTED`。本目錄中任何行情與LIVE驗證僅代表該GET收據當時狀態，不構成後續交易許可；後來回補的來源也不作歷史策略績效證明。
