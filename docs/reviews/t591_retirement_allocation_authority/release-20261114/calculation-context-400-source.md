# calculation-context 400 的唯讀來源查證

2026-11-14週六，SRPP host使用Swagger原契約GET tradingDate=2026-11-14、slot=11:40與精確b711 hash，回400 INVALID_REQUEST。未重試或更動參數、日期、日曆或來源。

原因是此既有路由的非交易日判定，並非slot契約矛盾：

- backend/src/main/java/com/steven/assets/srpp/SrppOrchestratedReadService.java:95先驗日期/slot/hash格式；同檔:589接受09:05與11:40。
- bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppOrchestratedQuery.java:60–67接受相同三個query與11:40。
- SrppOrchestratedReadService.java:99–101核當日/slot時間；:103讀cached-only台股日曆，:105在false時直接回INVALID_REQUEST。
- backend/src/main/java/com/steven/assets/controller/SrppOrchestratedProblemCatalog.java:13將INVALID_REQUEST映射HTTP400。
- docs/openapi/docker-external-api.yaml:1307要求合法當日且cached-only台股交易日；:1314列出11:40合法值。這條舊路由使用400；不應把report-calculations的409 NON_TRADING_DAY狀態套用到它。

同次report-calculations以reportSlot=12:00與精確b711 hash回409 NON_TRADING_DAY。三GET連線PASS只證明傳輸/非空來源；兩條業務API均未接受金融package。正式金融採用維持NOT_ESTABLISHED，bridge正數量API支援未實作，月頻情境不宣稱逐日策略等價。
