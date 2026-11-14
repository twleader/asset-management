# 第2輪處理紀錄
- Major: Vue rowsFor.filter 分組已移至新增 BFF SrppShortTermPresentation.list；BFF 產生 observationBuckets 含key/label/emptyText/rows，Vue直接render，無signal重算、無價格改寫、source序不變。null/unknown分類保留row原值進WAIT顯示。BFF actual HTTP＋immutable source/no mutation等測試已通過。
- 前輪兩項minor：價格String與日期String型別仍保留。協定確保decimal string、null與ISO dates，未浮點轉換；維持既有wire和source offset，未謊稱已修。
- 額外實際瀏覽器驗收發現term deposit原始0.164乘100顯示16.400000000000002。主agent按CLAUDE較低模型驗收失敗收尾例外接手，weight input僅格式化顯示為16.4，不回寫state，另加合計區CSS。完整原始配置儲存deepEqual catalog精度PASS。
- 完整UI複驗採真Python結果＋真編譯BFF presentation、明確synthetic雷達，PASS。非正式部署。
