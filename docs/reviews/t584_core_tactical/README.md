# Task 584 D-216核心與短線分離

## 交付狀態

資產端實作、authority同步及離線驗收完成，可交根agent進行兩專案Git落地、registry與正式部署。本agent未修改main、正式DB或容器，也未執行正式報告、寄信或交易。

- 規則包：`a9d05b6ab58685ca5cee088b05996fa9db868e3e4e4a6d3bf6a272b52ecb6175`，D-216／政策2.64.0／模型3.22.0。
- model、manifest與SRPP凍結來源逐位元相同；正式target document沿原Ruby projection產生，配置未改所以bytes不變。registration SQL由原generator完整輸出，僅insert-only，尚未執行。
- Swagger仍為`5704b5599bf948e0c9d3398aa88929c26fcdad885117845bbdfbf9156c4d6683`；本輪無契約變更，兩份asset mirror檢查通過。

## 已驗證行為

股票D173的合併上限共用`max(舊上限,權威核心目標+短線單檔額度)`，涵蓋screen與最終五檔重定價；債券袖套及沒有新模式的歷史policy不變。核心／短線仍依實際fill與pending驗真，未知不當空帳。既有pending SELL先逐position累計核對不超賣，再從可退出short shares扣除，待退出期間凍結新增；新cap不改此路徑。

日頻D156保留股票戰略目標56.53%，只對超額部分提議落袋；舊窗口不重設高水位、預算或成交批次，當輪量被excess限縮後重算growingFill。沒有D216設定的歷史40%過渡容量保留，月頻`monthly_bull_first_equity_target`與模型代理未改。資產module僅提供aggregate facts，逐檔核心保留及短線退出股數由SRPP final helper／guard驗證。

## 驗收

- Java四類共65項，0 failures／errors／skipped：ShortUs 17、StateTopup 12、Module 19、UsTactical 17。
- Python state與long-short generator回歸各5項，authority回歸4項；Ruby registry 5項／261 assertions，全部通過。
- 四組golden以凍結正本產生：report same-input 111、state-topup 22、short-US 13、long-short exit 15例；未手改數字。
- exact bundle authority檢查通過，spec-check最終0 BLOCK／0 CHECK，`git diff --check`乾淨。
- Maven離線package成功；JAR的model、manifest、formal policy、registration SQL及Swagger五份資源逐byte符合本worktree。這是離線產物驗證，不能替代正式image/container讀回。
- CLI spec兩輪均PASS、0 findings；第2輪只重綁清除檔尾空白後的bytes。CLI架構兩輪均PASS、0 findings；第2輪已覆蓋小修後的legacy條件與最終authority／golden，因此沒有未複審的實作小修。閘門已記錄。

完整數量與hash見[validation.json](validation.json)，離線JAR證據見[offline-package-authority.json](offline-package-authority.json)。

## 舊US helper finding

`SrppReportShortUsCalculator.usFull`沒有production caller；台股十二模組明列US horizon不適用。正式US publisher走新版`UsTacticalCalculator`，保留D215具名豁免。調用證據見[scope查證](us-legacy-scope-review.md)，另以未覆寫的官方D216 model加隔離NVDA合成輸入直接執行新計算器，RISK_OFF／MA200不合格／無止穩仍得到PASS與正數股數，見[實際計算器離線結果](official-d216-us-named-path.json)。這不代表自然交易時窗API已採用。

正式發布與runtime採用依根agent另存的兩專案refs、registry、image/container/JAR及exact-hash business API收據判定。
