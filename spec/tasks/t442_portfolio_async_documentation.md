# [t442] 投組建議三模式與現有90秒逾時注釋校正

**對應 Requirements:** Requirement 32（投組建議既有產生流程；僅校正注釋不改行為）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

main PortfolioAdviceService開頭仍稱同步Messages/request thread tenant，但當前LOCAL/HYBRID/LLM三模式已不同，舊docs分支含早已存在90秒timeout實作，不應重套。controller產生注釋目前只稱全部PROCESSING也應精確區分。

## 要做什麼

- [x] 442.1 只改PortfolioAdviceService及PortfolioAdviceController過期Javadoc與必要MarketAnalysisService描述，映現存三模式：LOCAL同步終態、零client/keycheck/外呼；HYBRID立即PROCESSING交背景（數字本機、LLM僅summary/riskAssessment、web_search禁/reference空）；LLM完整背景PROCESSING與既有references處理。request依owner建立prompt、背景只捕捉adviceId寫回與原stale self-heal描述按當前實作，不稱request tenant自動延伸到thread。
- [x] 442.2 MarketAnalysis現有client timeout Duration.ofSeconds(90)只補準確注釋，保留Batch poll/catch/retry/stale規則，不新增constant/client/timeout設定、不改HTTP/status/model/prompt/API/SQL/cron。同步design的R32說明即可，不新增Requirement。不套舊t222/舊requirements計數、不改CLAUDE/索引過期資料。

## 驗證

```bash
bash scripts/spec-check.sh
git diff --check
```

review完整diff確認Java變更僅comments，所有可執行token不變；可用Java lexical去註釋前後hash證明，不跑真人LLM/key/API或通知。純注釋不新增鏡像/容器重啟或行為測試；若同批另有可執行變更由該任務驗收。驗收後focused commit/no-ff merge/push保持SDD同步。

## 完成報告

已同步三模式現行注釋與既有90秒transport timeout說明，沒有重做引擎或改可執行行為。PortfolioAdvice service/controller、MarketAnalysis service 與其 BFF 的 Java lexical tokens 與基線相同；未呼叫真人 LLM、通知或外部服務。

本批後端最新256組測試報告共2214項、失敗0／錯誤0；BFF全套67組316項、失敗0／錯誤0。獨立架構最終覆核0 critical／0 major／0 minor；機械 spec／OpenAPI／兩鏡像／102表 schema drift 已通過。feature business／frontend／BFF 均已重建並重新部署驗收；分組提交、no-ff main 合併推送與 main 最終重建於本批收尾執行，不由測試替代部署。
