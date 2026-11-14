# Task 591 退休配置權威同步與發布

D220、policy 2.68.0／model 3.24.0，最終bundle `b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11`。

VOO20%、0050核心12%、00881核心6.53%、高股息13%/硬上限15%；股票56.53%、債券26.47%、現金17%。保留D219熊市15%首梯、Task589稅務與D218止穩。

獨立本機Codex spec與架構各最多三輪，最終均PASS（0/0/0）；六類Java61測例、authority、golden111/15/22、runtime23檔與worker import通過。原無bridge合成fixture明示零bridge狀態及execution_fx_ratio=1.0，未改正式未知證據判定。正式Java演算法未變。

程式feature620a8da0、main no-ff bac1bb67已推送並remote精確回讀。SRPP程式featurea5aa206b、main no-ff 5de629cd另由主代理驗證。insert-only registry新增b711，55→56筆，原row全文保持、新policy/formula全文吻合。

從乾淨main與原.env重建/recreate business-services及srpp-simulator；兩新image/container健康，JAR五份authority與worker23檔逐位元相等，正式env/mounts及其他8容器保持。完整runtime/registry與sourceGit收據在release-20261114/。本次後續收尾只有文件與收據，不變更source/runtime bytes、不再重建服務。

SRPP host三GET連線PASS（365日曆、21TWSE日線、39報價）；精確hash report-calculations回409 NON_TRADING_DAY。calculation-context合法11:40 query回400是原service:105非交易日分支，並非slot矛盾，詳release-20261114/calculation-context-400-source.md。未改date/參數重試或解封。

**正式金融採用：NOT_ESTABLISHED。** 當日2026-11-14為週六，沒有正式報告、POST、寄信或交易。bridge仍為saved-evidence預算診斷及明示月頻代理，未新增正式API正數量能力。退休財務目標是否達成由SRPP最終Monte Carlo報告判定；測試通過不等於達標。

歷史失效候選及複審原文保留供追溯；最終狀態以b711與本release收據為準。
