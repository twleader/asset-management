# [t493] API logs 每頁 100 筆、翻頁與條件交集

**對應 Requirements:** Requirement 188（API名稱全部顯示所有符合來源的日誌，指定方向排序，每頁100筆並可翻頁）
**前置任務:** t490、t491
**Liquibase changeset:** 無

## 背景

2026-10-11 Chrome管理者查ALL／NEWEST／API全部顯示暫無資料。business內網純讀回3981筆、1128340bytes；BFF回HTTP200 body0bytes，log證實whole-array解碼超過當時1048576bytes，而BusinessErrorAdvice把解碼失敗relay成upstream200空內容。使用者明確要求log分頁傳回、每頁100筆並增加翻頁功能。本任務同步改DB查詢、既有兩層list wire與前端，取代全量array，維持filter交集與排序。

## 要做什麼

- [x] 493.1 ApiErrorLogController.list增加optional int page（零起算、預設0），委派ApiErrorLogService。service驗ADMIN／來源／API／sort，拒絕負page，固定PageRequest.of(page,100)。ApiErrorLogRepository.newest/oldest回Page並接Pageable，維持 (source=ALL OR row.source=source) AND (operationKey omitted OR row.operationKey=operationKey)，occurredAt/id同向排序。DB先篩選排序再limit/offset及同條件count，不取全表再切片，不新增size輸入。
- [x] 493.2 business list新增immutable PageResult record：List<ListItem> items、long totalElements、int page、int pageSize、int totalPages。pageSize固定100，totalPages為總數/100無條件進位，空集合0；越界items空但總數/page保留。items僅Long id、String source/operationKey/apiName/messageHeader、nullable Integer httpStatus、Instant occurredAt，不含stackTrace/dedupeKey，不截斷。malformed/負page回400。移除ApiErrorLogController局部exception handler，由GlobalExceptionHandler統一處理MethodArgumentTypeMismatchException，framework query/path型別轉換錯誤回400與固定detail「Invalid request parameter」，不帶raw value／型別診斷，既有業務例外映射不變。detail/catalog/retention不變，無DBmigration或寫入。
- [x] 493.3 BFF ApiErrorLogsBffController.list同步optional page=0與typed page record，用既有tenant business client轉送source/sort/page/optional operationKey並relayitems與metadata，不自行count/filter/sort/page；records在BFF模組，不依賴backend類。BusinessErrorAdvice對帶upstream2xx的WebClientResponseException回502 application/problem+json，固定detail「無法讀取上游回應，請稍後重試」，不回原始body／內部診斷／stacktrace；保留capture及sanitized logging，非2xx status/bodyrelay不變，不調高全域buffer。
- [x] 493.4 frontend/src/api/index.js的bffApi.apiErrorLogs.list增加page=0並傳query。ApiErrorLogsView.vue依pageobject呈現items及totalElements，使用el-pagination layout="total, prev, pager, next"、固定100筆，畫面current page從1起算、傳page-1；換頁重新讀list，提供上一頁、頁碼、下一頁。source/sort/API選取或清除回第1頁；API全部保留local ALL sentinel並傳null省略operationKey，來源切換仍更新catalog。保留來源160px、排序/API寬1:2、API label中文加method/path、ADMIN、lazy detail與純文字呈現。list以遞增請求識別保護，只讓最新請求更新rows/total/loading/error，避免舊回應覆蓋新條件。不client端分頁/filter/sort，不呼叫catalog所列API。
- [x] 493.5 擴充既有ApiErrorLogServiceTest驗固定100、metadata、負page、ADMIN、malformed／overflow page的400固定detail與不洩漏值、共用framework型別轉換handler；加跑GlobalExceptionHandlerBackupRemoteTest回歸既有advice。ApiErrorLogDedupeKeyPostgresTest用隔離真PostgreSQL至少205列驗100/100/5三頁、總數、不同source/operation交集、occurredAt/id雙向排序、空集合與越界。現有呼叫與測試同步新list/page簽名，不保留舊無分頁production路徑。正式DB只讀，僅Testcontainers可寫。
- [x] 493.6 擴充既有ApiErrorLogsBffControllerTest驗page與source/API/sort轉送、typedpage全部metadata／nullable status／Instant、empty/out-of-range頁，且list無stackTrace/dedupeKey；以成功HTTP但body解碼失敗驗BFF+BusinessErrorAdvice回502固定ProblemDetail，403/503原status/bodyrelay保留。只用隔離mock交換／loopback測試，不連正式broker。更新既有frontend/src/utils/apiErrorLogsView.contract.test.js配合分頁handlers，保留t490所有sentinel/lazy/text斷言，補pagination/reset/request保護契約。
- [x] 493.7 Java25跑相關BFF/backend tests、frontend tests/build、spec-check/diff-check。獨立arch-auditor查完整diff。run-stack先核對feature/main ignored .env且不輸出秘密，只rebuild/recreate business-services、BFF、frontend，保留BFF resolver30秒恢復規則及既有TLS。Chrome已登入頁驗ALL/API全部第1/2/末頁、OPEN_API/FUBON_API、指定API與兩種排序，核對總數、每頁最多100、filter reset，未展開無stackTrace。驗收後兩段式commit/no-ffmerge/push；合併後從乾淨同步main再部署變動服務。

## 驗證

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
export PATH="$JAVA_HOME/bin:/Users/steven/.nvm/versions/node/v22.21.0/bin:$PATH"
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test -Dtest=ApiErrorLogsBffControllerTest
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest=ApiErrorLogServiceTest,ApiErrorLogDedupeKeyPostgresTest,GlobalExceptionHandlerBackupRemoteTest
npm --prefix frontend test
npm --prefix frontend run build
bash scripts/spec-check.sh
git diff --check
docker compose -p asset-management build business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
docker exec asset-bff wget -qO- http://127.0.0.1:8080/actuator/health
```

Compose只可在完成main-to-feature部署設定相同比對的驗收worktree，或合併後乾淨同步main執行；實際依run-stack服務順序與健康恢復流程，不以HTTP200當分頁行為證明。Chrome僅操作日誌選單與翻頁，不呼叫catalogAPI，不寄信、不刷新、不下單。

## 完成報告

實作、feature 驗收、兩段式 main 合併推送與正式 main 再部署均已完成。功能 feature commit 為 8ed024807131d022ff212b180241e6a5c613d2d3，no-ff merge 為 cf6532b87d3041fed18e7bfa41f5b14cabc5af21，main 與 origin 同步且乾淨。

- Java 25：ApiErrorLogServiceTest 10、隔離真 PostgreSQL ApiErrorLogDedupeKeyPostgresTest 5、GlobalExceptionHandlerBackupRemoteTest 4 全通過；真 PG 205 筆兩種排序皆為 100／100／5、跨頁無重複，AND／總數／空集合／越界／負頁碼及格式錯誤均符合契約。
- BFF 完整 suite 858 項，0 failures/errors/skipped；typed page、ISO Instant、nullable status、成功 HTTP 解碼失敗 502 固定 ProblemDetail、403／503／400 原回應 relay 均通過。
- Node 22：frontend 105 項全通過；production build 成功。root 驗收補正來源切換先失效舊 list、等待 catalog 再核對 request/source，才載入 list。全域 framework 400 handler 已依 arch finding 移入 GlobalExceptionHandler，query/path rejected value 不出現在回應，instance 固定 about:blank。
- spec-check BLOCK 0／CHECK 0；獨立 spec 與 architecture reviews findings 均 0；diff-check 通過。沒有 migration、金融 API／Swagger／schema 變更。
- run-stack 由繼承主代理模型／effort 的 subagent 執行（派工工具不能選 Terra）。三服務 business-services、BFF、frontend 已 build/recreate；business/BFF healthy、frontend running；其他六容器未動。feature ignored .env 由 main 同步，僅加既有 Fubon secrets 絕對掛載；runtime business 46、BFF 9、frontend 4 個環境鍵與 main 一致，TLS／OAuth／內部 tokens 與正式來源一致。business healthy 後保留舊 BFF 先驗 9090 GET 恢復，再正常 recreate 本次改動 BFF，沒有使用 restart fallback。
- Chrome 管理者實測：ALL／API全部 3982 筆、40 頁，第 1／2 頁各 100 且 IDs 不重疊，第 40 頁 82；OPEN_API／API全部 256 筆、3 頁，末頁 56；OPEN_API／OPEN_MARKET_INDEX 154 筆、2 頁，100／54，NEWEST 全 154 IDs 與 OLDEST 精確相反；FUBON_API／API全部 3726 筆、38 頁，100／末頁26；指定 FUBON_PORTFOLIO_READ 為44筆。來源／排序／API 選取及清除均回第1頁；選第三個全部只送 list、省略 operationKey，保留 source/sort，未重抓 catalog。頁面無 error、未展開無 detail／stackTrace；items 僅七欄且 Instant 為 ISO 字串。切回 ALL／NEWEST／API全部第1頁並保存畫面。
- 正式 DB 只讀核對，沒有超過30天的 api_error_log row。背景 Nginx 日誌可在服務 recreate 時產生真實 502 記錄，因此3981歷史根因筆數與實測3982筆不同，並非測試寫入。
- feature 驗收 image pins：business sha256:9d9ee6d0ec4404c374cc7ce5026b665f93165b0c4afcb48971a37223aabe69d5；BFF sha256:c409fa5539c6bb7bc43c669f026d6aa82592276f458209a184ce287ad7bc143f；frontend sha256:4a358be25442b0d8a5611fdef4956869de9900bd3915b8891688e177eb1eb5dc。驗收末尾再次確認 images 未被覆蓋。

- 正式部署來源 /Users/steven/Project/asset-management-main，commit cf6532b87d3041fed18e7bfa41f5b14cabc5af21。從 main build/recreate 同三服務，其他六容器未動；權威 .env 未改，46／9／4 個 environment keys 與正式 mounts 一致。business healthy 後原 BFF 經 9090 GET 正常恢復，再 recreate BFF／frontend，未用 restart fallback。business/BFF healthy、frontend running，正式 HTTPS／9090／成品 chunk 正常。
- main 部署後 Chrome 再驗 ALL／NEWEST／API全部第1與第2頁各100，BFF 第2頁實際回200、page=1、pageSize=100、totalElements=3982、totalPages=40；畫面條件保留且無error。返回第1頁，未展開無stackTrace。正式DB再只讀確認3982筆、expired=0。此輪與 feature 驗收的前端 JS／CSS成品一致，Maven layers cached，image index因provenance更新。
- 最終main images（Chrome驗收後再比對未被覆蓋）：business sha256:42e7450c04f27ec49d22469bff2c350be3ccb894447fa11e8d643858be1ba086；BFF sha256:cebf829676131bf73c867a53d13fa418c29d46fc1a87300ab5708ed2e5f5fec4；frontend sha256:a6b1dafa0ede99c7c8a60f92d28cc65bc667477dc007f788bd95ebaa83ebff2a。
