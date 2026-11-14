# 後續發布準備（尚未執行）

最終來源bundle b71151fefc58dfbf2f1c129037ad1ac3ea3c1d547ce66b5cb0be9b5ff9d97c11，待主代理最終數值合法完成與通知。所有下述操作本階段皆未執行。

1. 核對SRPP source與本feature embedded bytes、runtime export、golden及manifest精確一致；確認沒有新source hash。主代理先確認最終Monte Carlo／規格與獨立審查收尾。
2. asset feature與main比較本輪檔案衝突；main worktree目前定位為/Users/steven/Project/asset-management-main，落地時重新發現並核乾淨/main/remote。兩repo feature commit、main no-ff merge/push及remote精確回讀，由主代理協調順序。本階段不搶先提交。
3. 雙repo落地後，僅使用受追蹤d201-registration.sql作insert-only registry；相同hash先核全部欄位、矛盾停止，不UPDATE/DELETE歷史。保存精確row及原rows未改證據。
4. 從乾淨asset main及該main原.env重建並recreate business-services與srpp-simulator。前者authority resources變更，後者runtime變更；BFF/formula V3及Swagger未變，沒有理由重建BFF/frontend/gateway。發布時仍需按實際最終diff判斷。任何設定只記一致布林/hash，禁止輸出憑證。
5. 核新image/container、health、JAR內model/manifest/SQL/Swagger全文與main相等；核simulator內runtime-manifest與23檔原bytes及catalog身分。保留無關服務。
6. 從SRPP實機按Swagger先讀路由規格後跑固定三GET連線驗收；另外用最終完整hash精確查相關9090業務API。health/registry/HTTP200不替代金融採用；非合法交易日/時窗或CONTEXT_NOT_READY保留原狀，不觸發producer刷新或補造報告。
7. 記錄兩repo main/remote、registry、部署、精確hash API及能力限制；bridge仍無正式API正數執行量，正式報告採用若未合法實跑仍NOT_YET_EXECUTED。
