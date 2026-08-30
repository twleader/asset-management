# [t403] 富邦目錄由本頁BFF篩選並守住報價身分

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** Requirement 121（登入後唯讀API目錄）、109／114（完整同源五檔）、115（單檔富邦資料不重複投影）
**前置任務:** 現有富邦目錄52筆／15已串接、既有public quote與bridge已落地；external bridge日期的Java型別搬移保持同一ISO wire。
**Liquibase changeset:** 無

## 背景

基線main `6aa219719c4d20d3ef405ba4b5cad75e1316df15` 的API目錄清單在controller，FubonApiView自行computed/filter，違反BFF負責篩選、前端只render的原則。HTTP欄位以row.connected決定，會藏起已存在但尚未完成入帳的安全預檢入口。取消axios請求目前也會經全域interceptor彈錯誤。

PublicQuoteMarketDataService的五檔validator只確認代碼合法，沒有確認book與目前raw quote是同一stockCode/market；有效但錯股票的完整五檔可能被投影。FubonBridgeQuote.tradingDate仍為String，需與external typed bridge對齊而不改raw19欄。

## 要做什麼

- [ ] **403.1 精確範圍。** BFF只改 `fubonapi/FubonApiInfoBffController`、其DTO／新增本頁service/query純型別及 `publicquote/PublicQuoteMarketDataService` 的book identity／bridge日期；加必要tests。前端只改FubonApiView.vue、本頁api/index.js方法，必要的本頁request-state純helper及node:test、將該test列入現有npm test；不新增依賴。Vue實作依專案規則由gpt-5.6-terra/high agent執行。保留其他worktree與.env/secrets/flags，不改router/App.vue/permissions、全域axios interceptor、其他頁或任何public route/schema。不提供券商試打、sync或下單操作。

- [ ] **403.2 常數目錄與篩選移service。** FubonApiInfoService持有人工維護且不可變的52筆常數，保留每筆九欄connected/category/name/sdkReference/httpEndpoint/description/consumer/requestSummary/responseSummary、順序與SDK方法。controller只做HTTP query到中立DTO的機械轉換並委派，不能持有目錄／篩選規則；移除測試內「controller不能有Service」的舊斷言。service完全不依HTTPclient、Repository、DB、Redis、SDK、反射掃描或其他服務；只有純記憶體篩選，不加新capability。原15/37狀態不因本task改動；394/395將來另有完整來源報表功能驗收時，才由該獨立工作單元更新對應標示，不把來源報表稱為已完成舊資產入帳。

| 可選query | 精確規則 |
|---|---|
| connected | 只接受小寫true/false；空值、TRUE、1皆400 |
| category | 既有七分類精確字串；未知或空字串回空陣列 |
| keyword | 依Java String.strip去頭尾空白後至多200 Unicode code points；Locale.ROOT小寫後substring比對name/description/category/sdkReference；空白等同省略 |

- [ ] **403.3 輸入及相容。** 多條件AND，省略條件不篩，結果維持常數相對順序。重複query名稱、未知名稱、非法boolean或超長keyword統一400；service純query parser即可驗，不對runtime作alias／方法矩陣／安全探測。不帶參數仍52筆原JSON array，不加wrapper/pagination/計數欄位。既有authenticated GET `/api/bff/fubon-api`與七分類名稱、路由、icon、存取控制保持；沒有9090/Tailscale/OpenAPI新路徑。

- [ ] **403.4 前端只render與處理自身請求。** 移除computed/filter，初始及篩選條件變更均呼 `bffApi.fubonApi.get(params, {signal})`。只對本頁加既有skipErrorToast:true，view呈現最新有效error；不能修改其他頁interceptor、401登入或403待核准行為。新請求取消前請求，並以遞增generation守住rows/loading/error和finally；卸載也失效舊generation，取消不顯示錯誤。新條件載入中不把前條件列表冒稱最新；loading、empty、error可辨識。row key用穩定sdkReference＋必要的實際endpoint，不含當次array index。分類選项固定原七類順序；keyword原文送BFF、不在Vue再做比對或截斷改結果。

- [ ] **403.5 真實入口與未串接語意。** HTTP表格欄位與展開明細，只要httpEndpoint非空白就顯示，無值才「－」，不以connected隱藏。兩財務項仍false時要清楚寫來源採富邦官方、僅限已設定的專用同步帳戶、目前安全預檢不寫資產；只有完整能力驗收才能改true。requestSummary／responseSummary全文可展開、不得截斷；唯讀警語與無交易方法清單保留。目錄不展示私人email、帳戶、金額、個資報表或實際持倉；未來來源報表使用獨立頁面／BFF。本頁不得加試打API、curl產生器、同步／匯出／下單按鈕。

- [ ] **403.6 五檔必為同股票。** 在採納完整QuoteDetail並產生direct/nested projection前，驗Objects.equals(detail.stockCode,raw.stockCode)和market相等，保留原台股非0000、完整五檔、source只FUBON_BOOKS/YAHOO_TW、正值、排序、OPEN與source-time驗證。wrong code或market即以該raw row產生safe unavailable（source=null、兩側空）；direct quoteDetail和marketData.quoteDetail必须同一不可變結果，bidLevels/askLevels同時空。不改book代碼來配raw，不從不同source逐欄合併，不外呼或寫cache。

- [ ] **403.7 僅typed bridge日期。** BFF FubonBridgeQuote.tradingDate改LocalDate，反序列化只接受合法四位年YYYY-MM-DD字串；拒array、數值／boolean及非canonical ISO，錯誤只令此child unavailable。raw RawLatestQuote.tradingDate仍String，嚴格parse後與typed日期比較；source/stockCode/market/normalized timestamp gates全保持，任一mismatch不可覆蓋raw。投影回raw日期仍ISO String；premiumDiscountPct保持raw NAV、不加第二份同義quote。不得順便改raw19欄、opaque response JSON、歷史股利／ETF日期、public response或list額外讀取。

- [ ] **403.8 驗證（規劃新增）。** BFF純service驗無參數52與穩定順序、每分類／connected／keyword/AND、空列表、空白與200/201個Unicode code point、Locale.ROOT、未知/重複/非法參數400、零下游依賴。book測合法same identity、合法shape但wrong code/market時nested/direct/both sides都不可用，source與其他public欄位不退化。bridge測ISO正常與wrong date/type、raw較新時不覆蓋。node:test用deferred promises真競態驗A取消後晚成功／晚失敗／finally不改B、卸載後零更新、取消不toast、新error只屬最新；api呼叫攜signal/params/skipErrorToast且不改其他頁。既有所有BFF/frontend tests與production build必過。

## 驗證

先獨立spec審查。從feature repo root：

```bash
bash scripts/spec-check.sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml clean test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/lib/node_modules/npm/bin/npm-cli.js --prefix frontend test
/Users/steven/.nvm/versions/node/v22.21.0/bin/node /Users/steven/.nvm/versions/node/v22.21.0/lib/node_modules/npm/bin/npm-cli.js --prefix frontend run build
git diff --check
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env build bff frontend
FUBON_ENABLED=false DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock FUBON_SECRETS_DIR_HOST=/Users/steven/Project/asset-management-main/secrets/fubon docker compose -p asset-management --env-file /Users/steven/Project/asset-management-main/.env up -d --no-deps --force-recreate bff frontend
```

協調者依run-stack操作共享stack，先核對current main忽略版.env／canonical secret mount及全部flags不變，不down/刪volume。authenticated `https://asset-management.asuscomm.com/fubon-api` 真頁在403單獨階段驗52/15/37，與406整批且兩報表已完整驗收時最終驗52/17/35；其餘驗七分類、連續快速篩選、loading/empty/error、完整展開與未串接preflight端點；只本頁BFF GET，不觸發broker。未登入GET仍401/登入保護，原排程64筆保持。只用既定GET白名單，禁止live/internal alias／method矩陣／安全探測與任意POST。截圖／DOM及image/compiled asset hash一起驗，不只本機build。

完整diff獨立架構review與feature runtime通過才commit→no-ff merge→push，從main重建再驗。來源報表/原帳務目標的完成另算，不提前刪原Claude refs。

## 完成報告

尚未實作；測試為規劃，待實作者回填實際diff、測試、瀏覽器與部署證據，checkbox不先勾。
