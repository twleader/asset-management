# [t599] SRPP WAIT 原因與六位數 ETF 投影契約

**對應 Requirements:** Requirement 191（封閉唯讀投影契約與呼叫前檢核）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

2026-11-16 Codex 午盤八個 order-plan token 僅 legId~WAIT，缺必填 reason 因而400；symbol-rule-facts 009826 被 Java 及 consumer 的4–5位regex拒絕。原因不能從市場訊息推測或用OTHER補值。

## 要做什麼

- [ ] 599.1 保持後端 WAIT/VETO 三欄契約，REDUCE四欄且必填正數amount/reason，ADOPT兩欄；合法原因只為 EVENT_RISK/RISK_MODE/MARKET_CONTEXT/PRIORITY_ELSEWHERE/EVIDENCE_CONFLICT/OTHER。新增 SRPP verifier 的 --prepare-decisions JSON 模式，與 order-plan/symbol-rule-facts/stock-codes response模式互斥，先通過 published Swagger與完整 report.verify 的 owner/consumer/date/slot/hash/run/base檢查，再序列化明確決策，輸出 decisions/asOf及base binding。陣列1..25，legId唯一、eligible且維持base順序，欄位閉合；缺原因、REDUCE缺amount或任何不合法值 exit2且無待傳參數，零HTTP/DB I/O。asOf沿用now但不得早於base capture。不能猜原因、改決策、填零或默認OTHER。
- [ ] 599.2 Java SrppProjectionRequest.symbols、BFF SrppProjectionQuery 與 PublicSrppProjectionService query/response 檢核、OpenAPI SrppSymbolRuleFactsSymbol.symbol、SRPP TW_SYMBOL 使用 (?:[0-9]{4,5}[A-Z]?|[0-9]{6})；保留0050/2330/00697B，接受009826，拒空白/重複/七位數/六位數加字母/非ASCII/小寫。合法格式不繞過凍結universe，未捕獲仍UNAVAILABLE，不改量價/風險/策略/交易權限。
- [ ] 599.3 Java parser與既有service測試、Python序列化與response測試涵蓋正反例、reason遺漏、REDUCE amount遺漏、錯順序、重複/未知leg、six-digit frozen evidence。文件要求兩軌先preflight再GET；明確原因由LLM提供，原response驗證保留。
- [ ] 599.4 在乾淨managed SRPP feature worktree先執行 renderer→sync→renderer --check→sync --check，再改consumer/preflight來源，重產runtime contract與manifest及backend authority/insert-only registry；保留金融27+觀測1路由、版本僅patch，記錄新bundle/swagger hashes與所有來源差異。不得繞過dirty-worktree檢查。
- [ ] 599.5 獨立spec/arch CLI審查與離線測試過後交發布：兩專案feature commit/no-ff merge/push/ref核對，再insert-only registry、乾淨main重建business-services與bff並驗證。台股合法交易日09:05<=now<13:30才做新identity report-calculations readback，其他時段NOT_YET_EXECUTED；不重跑producer/報告、寄信或交易。此實作階段不宣稱部署完成。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppOrderPlanServiceTest,SrppSymbolRuleFactsServiceTest test
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s /Users/steven/Project/SRPP-projection-fix/scripts -p test_verify_srpp_projection_api.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest=SrppProjectionQueryTest test
ruby scripts/render-9090-openapi-docs.rb --asset-only --check
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/Project/SRPP-projection-fix --check
```
parser 正反例擴充既有 SrppOrderPlanServiceTest 與 SrppSymbolRuleFactsServiceTest；BFF擴充 SrppProjectionQueryTest。發布者依序跑受控run-stack的image rebuild/container recreate與合時窗9090核對；無schema變更。

## 完成報告

待實作與驗收。
