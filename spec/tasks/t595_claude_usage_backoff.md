# [t595] Claude 費用查詢去重與限流退避

**對應 Requirements:** Requirement 208（情境模擬訂閱AI工具、模型與effort選擇）
**前置任務:** t592、t593、t594
**Liquibase changeset:** 無

## 背景

Claude正常訂閱登入已通過，但usage固定端點回HTTP429，原bridge把它洗成費用未知。每次execute先全工具fresh探測再worker選定工具探測，加上20秒目錄重載，造成不必要費用查詢。Codex和Claude不是互斥工具，需移除重複查詢、保留即時費用硬閘並清楚顯示限流。

## 要做什麼

## Task 595：Claude 費用查詢去重、限流與退避

1. Claude與Codex工具彼此獨立可用；不能因選用Codex而查詢Claude費用。Java SrppSettingsLocalAi只在明確capabilities()清單載入時讀host GET，保存最後一次成功的immutable catalog snapshot；清單讀取失敗清snapshot。validateSelection()與interpret()不再readCapabilities()/GET，僅以snapshot驗selection；同步validateSelection無snapshot或revision不符回409 AI_CATALOG_STALE要求重載，背景interpret則保留AI_CATALOG_STALE及已算數值，不改已送出的非同步HTTP回應狀態，不能以snapshot授權費用。取消interpret parent對全部工具capabilities(fresh=True)的網路探測；parent只以已顯示的catalog快照驗selection與當前registry身分，無目錄或registry不符則AI_CATALOG_STALE要求重載。真正worker仍重新驗registry及所選工具的模型/effort，Claude僅一次即時費用proof通過後才infer；快取UI可用狀態永不授權推論。LOCAL不探測AI，Codex原同snapshot proof/exec及安全閘不變。
2. Claude metadata費用正證據可於host記憶體短快取最多60秒，僅用工具清單；依本次正常訂閱登入/credential身分隔離，登入改變/未知/失效不能重用。多執行緒、多Claude工具別名與多worker以host私有state中的全域Claude usage協調鎖單飛，flock等待有界、檔案no-follow/owner/0600、目錄0700。worker fresh檢查不讀成功快取，不保存可供後續推論重用的正向授權；不得儲存原token/account/email/原始usage到狀態檔或收據。
3. HTTP429對應新閉合安全碼AI_RATE_LIMITED，與AI_AUTH_REQUIRED/EXPIRED、AI_BILLING_UNVERIFIED及EXTRA_USAGE_ENABLED分開。只在Claude usage固定端點識別可信HTTP status/Retry-After，不以任意錯誤文字猜測；對外不傳原body/header/token。重用原普通HTTP helper行為時不得影響Ollama/Codex等其它adapter。429後的私有負向狀態跨process及host重啟保存重試次數與nextAllowedAt，不保存成功授權；等待期間所有重載/worker直接回限流且零外呼、零推論，不因refresh重設冷卻，不讓不同工具id或憑證輪替繞過同host冷卻。
4. 退避採60秒起的指數延長，上限900秒並加入非負有界jitter；有效Retry-After支援整數秒及HTTP-date，以較晚期限為準，不以本機上限截短供應者期限；未知/負數/非有限/損壞header使用保守預設，時鐘回退/損壞state不得提早放行。只在下一次允許的查詢到來時重試，不能背景無限輪詢；每個probe最多一次HTTP請求，重試在到期後的下一次probe觸發；等待期間立即回限流，不在同一request迴圈重打或睡過7.5秒metadata總deadline／BFF12秒期限。成功且資料完整才清除連續限流計數/負狀態；401/403、網路失敗或unknown schema不可當成功，不能使用舊正證據回退。
5. 保留Claude個人訂閱登入、extra_usage.is_enabled=false、spend.enabled非true及額度未耗盡、無API key/provider/付費fallback、動態模型/effort/特殊付費context拒絕及工具隔離。fresh proof拒絕/429/unknown均零推論，模型請求本身不得自動重試以免重複使用額度。metadata即使cached unavailable，下一次允許probe成功後可恢復；interpret收到限流須使host目錄快取及Claude記憶體正證據失效，使後續重讀呈現限流。
6. backend安全碼白名單及前端安全文案/測試同步，明示『費用狀態查詢暫時被限流，等待退避後再重新載入；等待期間不重複查詢，費用確認前不呼叫模型』，不誤稱已付費/登入過期或保證背景自動重試。Codex/Ollama可保持可選，Claude有有效新proof時也能同時可選；不為讓選單變亮跳過費用證據。沿既有wire不加DB/9090/BFF DTO/金融計算或政策變更。
7. 測試需用合成HTTP/時鐘而非打真usage刷429：併行單飛、跨process/重啟cooldown、Retry-After秒/date/無效/極長與時鐘回退、指數jitter邊界、deadline/max attempts、state安全、metadata TTL與auth輪替、fresh worker不讀成功cache、只查所選工具一次、429後不得stale positive授權、200後恢復與完整fee否決零推論，並覆蓋所有Task594回歸。跨Java→host測試須先明確載入清單再推進時鐘超過目錄/metadata TTL，validateSelection/interpret不觸發全工具GET；選Codex時Claude usage零次，選Claude僅worker一次fresh usage，明確重載才可探測所有工具；無snapshot/revision不符安全拒絕。部署重啟host、重建recreate business/frontend；瀏覽器驗Codex/Claude同可選及只有Claude限流時的安全文案，真host/business驗同時可用並在有效費用proof後最多一次Claude合成解讀。真服務仍429須尊重冷卻等待、不刷查詢或偽造可用；測試與實際未恢復狀態分開報告。完成獨立spec/arch審查、測試及feature部署驗收後feature commit/push、main no-ff merge/push、乾淨main重建與host指回main並驗TLS/既有數值hash/remote refs。

- [ ] 595.1 實作host Claude usage協調、負向state/退避與只查所選工具的執行流程。
- [ ] 595.2 同步Java安全碼及前端文案，加入HTTP/合成時鐘/併行安全與瀏覽器測試。
- [ ] 595.3 完成獨立審查、feature部署、Git發布與main部署回讀。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B -m unittest discover -s scripts/tests -p 'test_srpp_local_ai_bridge.py'
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='SrppSettings*,ShortTerm*' test
cd frontend && npm test && npm run build
```

以main權威.env於feature重建recreate business-services/frontend、installer重啟host，安全GET能力/業務center與最多一次Claude合成解讀，尊重真429冷卻不繞過。瀏覽器載部署assets以合成登入/BFF驗兩CLI同可選與Claude獨立限流。正式TLS/9090唯讀/舊run hash與receipt回歸，無寄信交易。獨立Codex spec/arch PASS後兩段式Git，main重建同服務、host來源main與remote精確SHA/clean驗證；不輸出secret/env diff。

## 完成報告

完整測試、審查與feature部署收據保存docs/reviews/t595_claude_usage_backoff；最終main收據存私有/tmp/t595-acceptance/main-runtime.json。此任務檔凍結實作契約，實際結果以收據記錄，不存憑證或完整模型文字。
