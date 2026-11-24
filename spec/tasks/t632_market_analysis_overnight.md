# [t632] 今日股市分析反映隔夜行情

**對應 Requirements:** Requirement 78（今日股市分析本機規則引擎）
**前置任務:** t337
**Liquibase changeset:** 無

## 背景

11/24 近月 TX 202612 夜盤 +8.65%，報告中的費半 +14.03%、那指 +6.51%，舊引擎將美股計分封頂在 2%，也沒有期貨資料，因而只顯示偏空。

## 要做什麼

- [ ] 外部資料服務依確定的台灣交易日曆，在台北時間 05:05–08:59 每五分鐘讀既有期交所 TX 盤後日報（https://www.taifex.com.tw/cht/3/futDailyMarketExcel?marketCode=1）。驗證日期、夜盤時段，先選 TX 最早列出的六位數月合約再驗有效成交及官方漲跌百分比，不為缺值往後挑。首份有效證據以專用日期鍵 SET NX 存 Redis 14 天，保存交易日、合約、官方漲跌、實際觀察時間。空資料重試，外呼設逾時，缺資料則揭露。
- [ ] business 盤前每五分鐘讀 DB 並以交易日曆驗證 SOX、IXIC 各最近兩個已完成美股交易日，僅四筆皆有效時以另一專用日期鍵 SET NX 封存 14 天，保存交易日、前後值及觀察時間。開盤後不得回填，讀取須驗證資料日期及觀察時間小於目標日 09:00 且不晚於分析當下。台股趨勢只採分析日前的完成日。
- [ ] 本機 v2 沿用原 bias、confidence 與基礎分數；開盤方向獨立使用台指期絕對漲跌達 2%、費半達 3%、那指達 2%，三者同向才確認，反向對稱。一致性未校準。摘要第一段顯示「開盤偏多／偏空／方向未確認」，揭露來源、合約、時段、漲跌、觀察時間與缺值；既有 Vue 對 v2 主標改為「台股基礎趨勢」、信心改為「趨勢訊號一致性」。原 API 欄位、付費引擎、舊報告及寄信排程設定沿用。
- [ ] 券商整合維持唯讀，無任何送單能力。正式部署後需在 09:00 前驗證來源確實提供當日證據，沒有證據時只顯示未確認。

## 驗證

- [ ] 以 11/24 官方夜盤 +8.65% 與畫面美股數值建立重現案例，確認開盤偏多。相反符號確認開盤偏空。驗證月合約換月、缺值、反向走勢、休市、及開盤截止後才取得的資料。
- [ ] 執行 `mvn -f backend/pom.xml -Dtest=LocalMarketAnalysisEngineTest,MarketAnalysisServiceLocalEngineTest test` 及外部資料服務新增來源測試。完成 Docker 重建及實機盤前讀回後再提交發布。

## 部署與回滾

依實際變更重建 external-materials-service、business-services 及 frontend。回滾時由乾淨 main 的回滾版本重建。無資料庫 migration。

## 完成紀錄

2026-11-25 發布驗收紀錄：

- 使用者確認已在 Desktop 以獨立 subagent 完成規格及架構審查並通過；本輪沿用該結果，沒有重跑審查。
- 本輪以最新 `origin/main`（462a49604826ef0a8c9910e15d7a710aeefcb7cb）建立乾淨 feature worktree，只搬移本任務 13 個檔案；原工作目錄及其他未追蹤檔案均保留。
- 本輪重新執行 backend 43 項與 external-materials 4 項針對性測試，共 47 項，全部通過；Docker frontend production build 通過。
- feature 驗收已完成 external-materials-service、business-services、frontend 的 image rebuild 與 container recreate。兩個 Java 服務 healthy，frontend running 且 HTTP 200；運行中 JAR 含新增證據類別，前端產物含 v2 標題及一致性標籤，三服務 effective environment 與 main 部署設定一致。
- BFF 維持運行，quotes JSON array 與 market-index JSON 恢復；今日分析唯讀回應與部署前相同（2026-11-24、local-rule-engine:v1、OK），沒有覆寫舊報告、手動重跑分析、寄信或建立假的 Redis 證據。
- 本輪依使用者明確要求，在部署驗收通過後發布，不將自然盤前證據當作發布前置。期交所報表是否可在台北 08:45 前取得，以及 09:00 前自然封存／v2 分析與畫面端到端結果，均為 `NOT_YET_EXECUTED`；須取得當日實際盤前觀察時間及來源證據後才可標記已驗證。
