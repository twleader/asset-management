# [t600] 修復分鐘 K 表示相容並揭露交易雷達確切資料缺口

**對應 Requirements:** Requirement117（風險診斷語意）及Requirement185（盤中分鐘K來源）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-17 009826具有71根完成日K（2026-08-03至11-16），MA20/60/KD可得但MA240與年線兩日確認不足；市場資料完整。現行三軌只稱必要資料不足，無法分辨真正歷史長度與下載失敗。全24檔分鐘capture同日INVALID_RESPONSE；SDK當日分鐘root timeframe實際為字串"1"，adapter只比對整數1而拒絕，其他列有效。配息resolution為STALE、knownAt 2026-11-04，缺少decision日至+45日scope證據；當前官方HTTP成功也不能證明未來45日公告完整性，保持PARTIAL與揭露而非action veto。

## 要做什麼

- [x] 600.1 當日Python分鐘adapter的可選root timeframe僅允type(value) is int且value==1、或type(value) is str且value=="1"。未提供沿既有行為；拒bool、float、null、其他int/string、空白及容器。canonical wire固定int1，歷史分鐘、其他identity/價格/量/最多271列/完成截止/排程配額/retention/readonly權限不變。不得改券商狀態或下單。
- [x] 600.2 引擎complete邏輯不變，純helper依同一StockInput列出缺少MA20/MA60/MA240/K/D、三種兩日確認、價格/漲跌/商品類型/大盤條件。full detail資料不完整時在三軌原通用risks後追加本次acceptedPrice.indicatorSeriesRows.size()完成輸入根數N/241（不含live），及真缺項；原通用句與支持順序保留，不推測上市或下載故障。technical basis不可用與原始完成輸入根數分開，不宣稱列數足夠即已可算。無新增IO/DTO/StockInput形狀。
- [x] 600.3 配息共同披露保留並追加同一Evidence.dividendEvent的status/knownAt和非空missingReason，三軌共同/個別路由不變；不改resolver狀態或complete，不把未知當無配息。分鐘UNAVAILABLE依既有reason給繁中分類並保留代碼，未知reason用保守通用文案；WAIT/CONFIRMED訊息及時間來源維持。只在risks/audit追加，不動supports。
- [x] 600.4 不改RULE_VERSION、分數、候選/實際動作、閾值、三軌完整性要求、通知邏輯、Swagger、API shape、route、DB、Redis、正式SRPP或政策hash。修分鐘source後真capture可恢復，並不代表009826通過年線條件。
- [x] 600.5 Python測試精確int1/string1同值與bool/float/null/其他等拒絕，及root/row身份與未完成棒回歸；backend測試71/241與240/241、缺欄/大盤不足、live不算完成、basis失敗不誤稱可算、三軌風險/支持/分數/action不變、配息STALE與空reason、分鐘既有reason分類及未知碼。獨立spec/arch審查通過後重建recreate fubon-broker-service及business-services，必要時external重連；只讀比對DB背景capture與9090 response，不主動重跑報告/寄信/交易。

## 驗證

```bash
python3 -m pytest fubon-broker-service/tests/test_task408_market_data.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=TradingRadarIndicatorSeriesProvenanceTest,RadarIntradayCandlePolicyTest,TradingRadarEvidenceConfidenceResolverTest test
bash scripts/spec-check.sh
```

實際驗收另依run-stack從正確.env來源image rebuild/container recreate後，等待既有背景capture；以既有9090 trading-radar/stock?stockCode=009826&market=台股純讀檢查精確缺項/根數與minute status。若時窗外不得偽造capture成功，保留未完成驗收原因。無schema變更。

## 完成報告

2026-11-17完成實作與feature實機驗收。Python精確接受int1/string1且其餘型別拒絕，57項聚焦測試通過；Java缺項/根數、配息與分鐘原因診斷45項通過，原action/score/supports契約保留。獨立spec與arch均PASS、critical/major/minor皆0。

feature重建recreate兩服務後HTTP200 canonical分鐘資料為AVAILABLE，既有背景輪替將22檔capture全部恢復AVAILABLE。009826雷達明示71/241與MA240/年線兩日確認缺項、配息STALE原因；分鐘來源已可讀但12:44棒確實缺少，維持MISSING_CONSECUTIVE_COMPLETED_BARS，不補造資料。三軌NO_TRADE仍保留。部署環境與main一致，未重啟BFF或手動啟動producer，未修改SRPP、寄信或交易。

本段及checkbox為驗收後完成紀錄，不變更已審規格語義，未另作獨立規格審查；逐項安全證據與映像身分留於docs/reviews/t600_radar_data_gap。正式main落地及部署紀錄同目錄續記。
