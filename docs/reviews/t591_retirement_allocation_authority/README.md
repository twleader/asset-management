# Task 591 離線權威同步準備

D220，policy 2.68.0／model 3.24.0；最終凍結bundle `b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11`，已由主代理明確凍結。

VOO 20%、0050核心12%、00881核心6.53%、高股息13%/硬上限15%；股票56.53%、債券26.47%、現金17%。保留D219熊市15%首梯、Task589債券稅務與D218止穩。

獨立本機Codex spec三輪：第1輪PASS；第2輪指出合成fixture漏兩個開關，已採納；第3輪PASS。獨立本機Codex架構審查第1輪PASS（0/0/0）。六類Java離線回歸61 tests無失敗，三組golden111/15/22與authority、runtime23檔、匯出worker import均通過。

Same-input與long-short golden只bundle hash差異；state-topup只有原始transition_ledger來源hash及三筆groupCap從12%至15%，金融expected輸出原樣。正式Java演算法無修改；D216兩個精確target+.5pp斷言更新，離線state fixture補明示無bridge四欄。

VOO bridge仍為saved-evidence budget diagnostics，正式API既有BLOCKED/UNAVAILABLE/零量保持；未新增bridge正數量wire、route或執行能力。新配置可載入不代表bridge金融採用。

本階段沒有commit/merge/push、正式registry、部署、報告、交易或寄信；註冊SQL僅產物。完整發布待最終source凍結與主代理協調。

最新候選包含月頻runtime修正：USD DCA專款、alias隔離、滾動FX與D215直接VOO優先、跌深例外代理、共享cadence/資金及期限。已逐位元重新匯出並重跑authority/golden/runtime/import/Java；獨立架構第2輪審查中，原第1輪報告僅保留原版本證據。

歷史5c2c候選已作廢；最新b711最終凍結來源已包含共用cap_direct_voo_purchase及三個入口修正、嚴格bridge ledger、專款釋放、FX-only與持有cap分離、執行/估值FX分離。已重新生成所有artifacts；第3輪獨立架構審查PASS（0/0/0），已重讀最終b711來源與三個直接VOO入口；舊PASS僅歷史證據。

最終隔離state fixture另明示execution_fx_ratio=1.0與bridge_cash_released=0；沒有修改正式缺證據判斷。等待主代理最終蒙地卡羅數字驗收後才協調發布。
