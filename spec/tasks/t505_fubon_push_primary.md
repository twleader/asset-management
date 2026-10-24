# [t505] 個股推播寫入盤中canonical並停止重複價格輪詢

**對應 Requirements:** Requirement 196（成交推播主來源與受控補抓）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前push僅寫Redis，十秒dispatcher仍重複Fubon→MIS→Yahoo查成交價。HTTP已連線也不代表SDK完成訂閱。要求以真實SDK連線/ACK/heartbeat健康證據停止重複查價，保留五檔並先DB後Redis。

## 要做什麼

- [x] 505.1 Python既有token-protected SSE增加event stock-status，無id、data僅schemaVersion=1、connectionEpoch正int64、scopeVersion非負int64、sorted unique desiredSymbols、confirmedSymbols subset、ready boolean；每5秒送出。ready要求當前session/epoch、有效lease、connect完成、無reconnect、desired全ACK且無pending、有效SDK heartbeat/pong/validated ACK/data距monotonic≤75秒。heartbeat/pong data.time需正整數；逾75秒強制reconnect；停用/config/error/disconnect立即撤銷。無成交但heartbeat正常維持ready。保留300symbols限制與既有read-only連線/訂閱操作，不新增SDK write。
- [x] 505.2 Java嚴格解析status並以本地monotonic 15秒lease、generation/body fence管理；EOF/error/rejected/stop/malformed立即失效，body idle watchdog15秒close/reconnect。interface健康查詢有安全false預設。manager健康判斷要求known-open、45秒授權未過期、status desired與當前授權集合exact match且symbol confirmed；scope更動到新status前不抑制補抓。
- [x] 505.3 consumer保留實成交、calendar/radar/lease最終檢查；raw StockEvent轉PriceResult後先經StockSourceQuery.persistIntradayQuote transaction提交stock_intraday_quote，再只把canonical.asPriceResult寫Redis strict-newer。DB FAILED/無canonical零Redis，APPLIED及STALE_OR_EQUAL可投影canonical repair；Redis失敗保留DB；consumer至多保留300筆已提交canonical待repair，manager每30秒續租後重試，重驗calendar/radar/有效授權，不用raw、不外呼、不新增排程，移出scope或過日清除。FUBON_WS_AGGREGATES維持source，bid/ask/volume null，不寫stock_price_history、不推算size單位；源OHLC不寫local dayhl。
- [x] 505.4 dispatcher push-enabled下每個SSE generation/provider connection epoch的每檔首次受控補抓後，healthy symbols不查任何價格REST。未就緒/啟動/重連/故障symbol最多40-code fair cursor snapshot、每檔60秒monotonic冷卻，先記錄再外呼，失敗亦冷卻、不排隊；即使ACK早於首輪仍執行每epoch一次bounded初始化補抓，recoveryIdentity含SSE generation/provider epoch，未就緒時補抓不能代表之後epoch初始化完成。MIS/Yahoo價格只限同輪selected snapshot codes；scope移除清理cooldown。push disabled保留原模式。始終input∩radar、known-open fail closed，不碰指數/非台股。
- [x] 505.5 五檔每輪另一40-code fair cursor，healthy推播時走既有獨立Yahoo worker（8-code fair cursor／最多16 probes／最多4 concurrent probes／2秒probe／9秒round），無每檔10秒保證；snapshot完整Fubon book仍primary；若本輪normalized Fubon envelope DB失敗，兩book worker皆不啟動；排除同輪有效Fubon book後才送Yahoo。明確允許push模式無Fubon quote也可book fallback，取代舊trigger；不新增不存在books REST/WS books、不跨source拼欄位，沿用DB comparator與Redis canonical revision。五檔不進generic price/ticks/dayhl/SSE，不作估值警報下單。
- [x] 505.6 更新catalog文案而非排程頻率；補有意義的offline tests：DB/Redis順序與失敗repair、健康/超時/ACK/scope/EOF、healthy零price REST、snapshot公平輪替/60秒冷卻及book候選；不新增公開API、無SRPP改動。依SDD獨立spec與arch審查後重建recreate external-materials-service、fubon-broker-service、bff三受影響服務，唯讀驗收並commit/no-ff merge/push。

## 驗證

```bash
bash scripts/spec-check.sh
PYTHONPATH=fubon-broker-service/src /Users/steven/.venvs/asset-management-fubon-tests/bin/pytest -q fubon-broker-service/tests/test_stock_push_stream.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml test
```

Docker驗收透過run-stack流程從本次worktree同步正式.env後執行：
```bash
docker compose -p asset-management build fubon-broker-service external-materials-service bff
docker compose -p asset-management up -d --no-deps --force-recreate fubon-broker-service external-materials-service bff
docker compose -p asset-management ps fubon-broker-service external-materials-service bff
```

2026-10-24非交易日：只能唯讀health/log/jar部署身分與closed-market gates；不得注入盤中真DB/Redis、broker行情事件或手動開gate。盤中live ACK/heartbeat/quote讀回驗收明確記為未執行，由offline測試確認行為。

## 完成報告

完成於 2026-10-24（台北非交易日）。

- Python `stock_push_stream.py` 提供 typed stock-status、ACK／SDK heartbeat 存活與 75 秒斷線重連；Java client 嚴格解析、15 秒有期限健康與 idle watchdog，並以每條 body generation/provider epoch 識別初始化。
- consumer 經 `StockSourceQuery.persistIntradayQuote` 的 REQUIRES_NEW 提交盤中 canonical 再 Redis；DB 失敗不觸碰 Redis，Redis 失敗由最多 300 筆 background pending repair 在續租時重新讀取 DB canonical。dispatcher 健康且當前 epoch 已初始化後不查任何 generic 價格，初始化／異常受 40-code fair cursor 與每檔 60 秒冷卻約束。必要五檔沿用獨立 Yahoo worker，Fubon envelope DB 失敗保留兩 book worker 抑制。兩份 BFF catalog 文案已同步。
- 獨立 spec 審查完成三輪，最後 critical 0／major 0；唯一 CLAUDE 初始化措辭 minor 已修。獨立 diff-scoped arch 審查及兩份 catalog 補充確認均 0 findings；實作 gate hash `abdcd9874475`。最終 spec-check BLOCK 0，唯一 CHECK 是 origin/main commit age（已 fetch，remote SHA 未變）；117 表 schema 與實機逐位元同步，9090 契約未變。
- Python 選定 stream／event／HTTP 回歸共 174 passed；external-materials 10 個相關測試類共 47 tests，BFF 兩個 catalog 測試類共 38 tests，全部 failures 0／errors 0／skipped 0。PostgreSQL／Redis 故障及 strict-newer 驗證使用隔離 Testcontainers，未注入正式行情。
- 依 run-stack 從 feature 重建／recreate `fubon-broker-service`、`external-materials-service`、`bff`，三服務 healthy 且 health UP。effective env 與 main 展開全部鍵一致，正式 Fubon/shared secrets 為唯讀掛載。Python 檔案 SHA 與 source 一致、Java stock-status 及 BFF 初始化文案均存在；`GET :9090/api/quotes` 為 200／JSON array／58 筆；兩個 catalog 未登入為 401，沒有繞過驗證。8080／8082 無 host listener。
- `GET :9090/api/public/trading-calendar?year=2026` 確認 2026-10-24 `twTrading=false`，服務 `twMarketOpen=false`。實際盤中成交→DB→Redis、健康抑制輪詢、重連補抓的 live 驗收明確為 **NOT_YET_EXECUTED**；未偽造事件、開 market gate、寄信或交易。沒有 schema／公開 API／SRPP 變更。
- 部署驗收 image IDs：Fubon `257a76f51915f98c086656657fb8647122d8b29ab558328ff4416d5c54dc2378`；external `7ad4c1cb6f2b52cd14908cb3af2b05f03d290b2e09c52e98613cb8d579d85e4a`；BFF `b9425592def108931803efebf3d0336582ec1522ea1a194cd4bd62e62343605c`。

偏差：沒有新增不存在的 books-only REST 或跨 channel 拼接；健康時必要五檔採既有 bounded Yahoo worker，補抓的完整 Fubon book 仍為 primary。盤中 live 受非交易日 gate 保留未執行，不以健康檢查代替。

