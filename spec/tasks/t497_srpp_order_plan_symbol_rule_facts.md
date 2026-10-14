# [t497] SRPP fail-closed 決策投影與逐檔規則事實 9090 唯讀 API

**對應 Requirements:** Requirement 191（以已發布 immutable report package 提供逐腿 fail-closed 決策投影及逐檔規則事實，但永不交易或重算容量）
**前置任務:** t489（immutable `report-calculations` package）
**Liquibase changeset:** 無

## 背景

現有 `report-calculations` 保存背景 producer 的報告建議。本任務只增加兩個 read-only 9090 GET，讓 LLM **只交付有序 decisions**，程式對既有immutable v1 package做fail-closed投影；不接收風險模式、不改package或SRPP runtime。

這不是交易、券商同步、資料接入、策略擴充或SRPP adoption。缺少 P1/P2、M2/M3/M5/M7/D-202 或 package evidence 時，結果必明列 `UNAVAILABLE`、`NOT_EVALUATED`、`BLOCKED`，不得以 0、false、空集合或替代候選猜測。v1沒有capacity evidence，故零容量回流；未交割賣款不能資助 BUY。

## 要做什麼

- [ ] **497.1 v1 immutable projection。** 僅讀既有schemaVersion 1 FINAL package；不得改package output、consumer schema、manifest、registry、producer、migration、SRPP validator/prompt/schedule/runtime。eligible leg僅module `data.legs`的BUY/SELL，market精確台股→TW、美股→US；NONE排除且不計coverage。legId是ASCII `moduleId + "." + data.legs 零起算decimal index`，module order/index保留；action/market/moduleId/index未知或缺失為upstream-invalid→502，不能猜。A2 universe是eligible leg的sorted unique `(TW,symbol)`投影，非package field；package content hash保護它，input snapshot hash只identity-bind且不得聲稱含或保護universe。
- [ ] **497.2 order-plan route／CSV wire。** 新增唯一 `GET /api/public/srpp/order-plan`。封閉query只有optional `email`；required consumer/date/slot/policy SHA/Swagger SHA/run UUID/RFC3339 asOf/單一decisions；不收risk、quote、fresh/revision欄位。UTF-8 percent-decode恰一次、每key恰一次、無body；decisions是1–25 ASCII comma CSV（raw comma與`%2C`同義、不可double-decode），token順序即module/index投影決策順序。legId必精確為497.1的`moduleId.index`；token只可ADOPT/REDUCE~positive amount~reason/WAIT~reason/VETO~reason，reason是既有六值；空token/空白/invalid encoding/grammar或duplicate key/legId均400。server clock驗`capturedAt<=asOf<=now`，D175用validated asOf；identity exact bind FINAL package，not-found/non-owner404、context mismatch409、暫缺503。
- [ ] **497.3 decision/replay／derived risk。** 漏列eligible leg為NOT_DECIDED/zero-or-null/LLM_DECISION_MISSING。只讀frozen package；P2/fresh/revision不在本輪。無client risk assertion且package無safely-bound risk evidence，derivedRisk固定`DEFENSIVE/UNAVAILABLE/RISK_EVIDENCE_UNAVAILABLE`。每BUY均risk-gated，final quantity只能verified zero/null；SELL只投影frozen base quantity且executable=false，缺evidence則unavailable/null；REDUCE只對一個non-null base unit，否則NOT_EVALUATED。無M1 capacity evidence，零capacity reflow與candidate substitution，未交割SELL不資助BUY。

  固定順序為identity/decision→fixed DEFENSIVE BUY gate→SELL frozen projection→D175；沒有capacity calculation、重排或D-144量變更。支撐資料缺失即逐leg UNAVAILABLE/NOT_EVALUATED/BLOCKED及null/reason。
- [ ] **497.4 sealed responses/problems/BFF。** A1 root固定`{schemaVersion:"1",planId,identity,base,asOf,normalizedDecisions,derivedRisk,legs,coverage,executionScope:"REPORT_RECOMMENDATION_ONLY",tradeAuthorization:false,placesOrders:false,receiptRefs,contentSha256}`，沒有root quoteCapture/capacityTrace/candidate欄位，root receiptRefs只aggregate。每leg除v1 projection欄位另有`quoteCapture:null|{sourceId:"market:台股:<symbol>"|"market:美股:<symbol>",sourceRevision:string(1..128),sourceSha256:64hex,capturedAt:RFC3339,receiptRef:{kind:"QUOTE",sha256:64hex,capturedAt:RFC3339}}`；唯當`inputSnapshot.inputBundleJcs.sources[sourceId].quote`精確匹配leg market/symbol、`sourceVector[sourceId]`精確匹配sourceId/hash/capturedAt且validation=VERIFIED、quoteCapture.sourceRevision精確等於vector revision，且leg limitPrice/amount皆使用此同一package source才non-null，否則quoteCapture/limitPrice/amount皆null且fail closed。receipt kind enum含SOURCE。BFF驗identity/hash/coverage/quote binding/zero-null/false flags，任何unknown或package投影不一致502且零寫入。
- [ ] **497.5 symbol-rule-facts route／sealed response。** A2 query仍為email optional+六identity fields+單一CSV stockCodes；拒絕asOf/decisions/risk/quote/fresh/revision。universe來自497.1 eligible TW legs，not-in-universe是逐列UNAVAILABLE/NOT_IN_PACKAGE_CAPTURE。facts必恰12筆無重名且固定順序`WEEK52_POSITION,K,D,RSI5,BIAS10,WILLIAMS_R9,PREMIUM_PERCENT,OVERHEAT_HIT,LOW_HIT,ENTRY_WATCH,BUY_OBSERVATION,SELL_OBSERVATION`。唯一sourceId=`market:台股:<symbol>`，須exact matching inputSnapshot.inputBundleJcs.sources[sourceId]與sourceVector sha/identity/validation=VERIFIED：`radar.stock.week52Position→WEEK52_POSITION/RATIO 0..1`，`radar.stock.kValue→K/PERCENT 0..100`，`radar.stock.dValue→D/PERCENT 0..100`，`radar.stock.extendedIndicators.rsi5→RSI5/PERCENT 0..100`，`radar.stock.extendedIndicators.bias10→BIAS10/PERCENT finite`，`radar.stock.extendedIndicators.wr9→WILLIAMS_R9/PERCENT 0..100`，`quote.premiumDiscountPct→PREMIUM_PERCENT/PERCENT finite`；皆canonical decimal。只有exact source/vector、一筆matching SOURCE receipt才AVAILABLE且asOf=package capturedAt/sourceRevision=vector sha；matching意為kind=SOURCE、sha256=vector sha、capturedAt=vector capturedAt，否則null NOT_EVALUATED/FROZEN_INPUT_UNAVAILABLE且無source receipt。OVERHEAT_HIT唯由五個AVAILABLE（故VERIFIED）mapped values依純規則`(K>90&&D>80)||RSI5>=80||BIAS10>=8||WILLIAMS_R9<=20`得BOOLEAN/NONE/AVAILABLE，asOf=package capturedAt、sourceRevision=vector sha、receiptRefs為同一SOURCE receipt；任一input缺失或未VERIFIED固定null/NONE/NOT_EVALUATED/null/null/[]/[FROZEN_INPUT_UNAVAILABLE]。LOW_HIT/ENTRY_WATCH/BUY_OBSERVATION/SELL_OBSERVATION固定null/NONE/NOT_EVALUATED/null/null/[]/[FROZEN_INPUT_UNAVAILABLE]；沒有alternate/transform/policy inference或untyped scalar。
- [ ] **497.6 9090 contract。** 金融OpenAPI升1.26.0，financial=26（22GET/4POST）；AI觀測OpenAPI保持1.1.0／一POST；gateway/catalog/統一Swagger union=27（22GET/5POST）。同步Nginx exact/405、BFF security+route catalog、frontend exact404/matrix deny、Tailscale Serve、兩YAML local-ref/operationId/component safety、renderer、repo/backend/SRPP mirrors、PublishedSwaggerIdentity、OpenAPI/route-count contracts。mail GET/POST仍共用prefix，Tailscale=26handlers。拒root、`/api/`、wildcard、matrix、descendant，不修改觀測路由或引入交易能力。
- [ ] **497.7 tests/deploy/consumer boundary。** 覆蓋v1 module/index leg projection、NONE排除、unknown action/market 502、derived TW universe、sealed success/problem/fact schema、coverage/JCS/CSV/owner isolation、A1 asOf/D175、逐leg quoteCapture的source/sourceVector `validation=VERIFIED`/revision exact equality/QUOTE receipt／price-amount同源與全null fallback、fixed DEFENSIVE/BUY zero-null/SELL frozen-only/REDUCE base invariant、P1/P2/M2/M3/M5/M7/D202 unavailable、zero capacity reflow/no substitute、A2 12筆no-duplicate fixed-order facts、七個source mapping/range/`validation=VERIFIED`/SOURCE receipt、overheat boundary、四筆fixed unavailable、outside universe、BFF hostile upstream。只發布API和Task495必要Swagger metadata；不得改v1 package output、consumer schema、manifest、registry或任何SRPP prompts/schedules/validators/runtime/adoption，另立任務才可採納API。執行：

  帶有`@Transactional`的projection service必須持續可由Spring CGLIB proxy；名稱含`OrderPlan`的測試必須以真實Spring context證明該service是CGLIB proxy，避免僅編譯通過卻在container startup失敗。此項僅是runtime wiring驗收，不改變任何A1/A2行為、wire契約或商業規則。

  ```bash
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*' test
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*' test
  ruby scripts/tests/docker-external-api-openapi-test.rb
  bash scripts/tests/configure-tailscale-api-gateway-test.sh
  ruby scripts/render-9090-openapi-docs.rb --check
  bash scripts/spec-check.sh
  git diff --check
  ```

  合併後才從乾淨、已追平origin/main的main worktree及權威`.env` build/recreate business-services、bff、api-gateway；healthy後用127.0.0.1:9090讀合法GET與deny matrix。不得為驗收呼叫券商、vendor refresh、POST、email或任何交易；沒有FINAL package只能回報runtime boundary。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='*SrppReport*,*OrderPlan*,*SymbolRuleFacts*' test
ruby scripts/tests/docker-external-api-openapi-test.rb
bash scripts/tests/configure-tailscale-api-gateway-test.sh
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/spec-check.sh
git diff --check
```

部署讀回必確認兩GET package identity、row-level unavailable、POST/child/matrix denial、false execution flags與零API-error-ingestion；fixture和拒絕探測不可替代真交易日完整package。

## 完成報告

（實作者完成後回填：實際檔案、financial/observer/union/Tailscale route counts、OpenAPI/Swagger hash、測試、main image/container provenance、9090 readback及尚缺正式package/source。不得將mock、fixture、休市或拒絕探測描述為真交易日成功。）
