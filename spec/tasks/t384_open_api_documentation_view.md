# [t384] 公開資訊「開放 API」文件檢視

**對應 Requirements:** Requirement 119（登入使用者可從公開資訊檢視唯一 port 9090 OpenAPI 契約的所有 operation 與完整 Swagger）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前 port 9090 的對外 API 定義集中在一份 OpenAPI YAML，使用者在系統側欄沒有可直接、可信且不會執行 API 的閱讀入口。這個交付要在「公開資訊」新增「開放 API」頁，讓登入使用者可展開閱讀每一項 operation 的 Swagger 詳細資料。畫面不可把 YAML 複製為另一份前端資料，也不可將文件頁誤作可直接呼叫公開 API 的 Swagger UI。

## 要做什麼

- [ ] **384.1 BFF 契約讀取端點。** 在 `bff/src/main/java/com/steven/assets/bff/openapi/` 建立專屬此頁的 `OpenApiContractBffController` 與單一 `OpenApiContractService`，並在對應的 `bff/src/test/java/com/steven/assets/bff/openapi/` 建立其 tests；該資料夾只承載本頁 controller/service。controller 只提供精確 GET `/api/bff/open-api/contract` 並委派 service；service 以 UTF-8 讀取 classpath 的 `docker-external-api.yaml` 並原樣回傳。成功 response 的 content type 必為 `application/yaml; charset=UTF-8`。除指定的唯一路徑 classpath resource 唯讀外，service 不得進行 filesystem I/O，尤其不得寫入檔案，且不得依賴或觸及 DB、Redis、WebClient、business service、vendor、排程、broker、交易、user、帳戶、tenant 或 token；不得有資料修改或任何外部呼叫。

- [ ] **384.2 保持登入保護與公開 API 邊界。** 此 BFF GET 使用現有預設 `anyExchange().authenticated()`，不得新增 `permitAll`、匿名例外或自建 token。不得新增、刪除或修改任何 port 9090 path/method、API gateway mapping、Tailscale Serve、frontend nginx 9090 allowlist、OpenAPI `paths`、Swagger Markdown 或既有 API 行為；也不得在畫面或驗收中呼叫 POST `/api/public/crawler-data/rescan`。

- [ ] **384.3 單一 YAML 的 Maven/Docker 打包。** `bff/pom.xml` 顯式保留 `src/main/resources`，並另外把 `../docs/openapi` 中唯一的 `docker-external-api.yaml` 收進 jar classpath resource。`docker-compose.yml` 的 bff build 使用 named context `openapi-contract: ./docs/openapi`；`bff/Dockerfile` 在 Maven package 前以該 named context 將 `docker-external-api.yaml` 複製到 `/docs/openapi/docker-external-api.yaml`，使相對資源目錄可被 Maven 讀取。不得建立任何 versioned duplicate YAML 在 `bff/`、`frontend/` 或 resources source directory；jar 裡的資源必與 `docs/openapi/docker-external-api.yaml` 逐位元相同。

- [ ] **384.4 前端路由與選單。** 新增受登入保護的 `/open-api` route（name `OpenApi`、title `開放 API`）與 `OpenApiView.vue`。在側欄「公開資訊」群組新增「開放 API」項目；既有項目的 path、相對順序、行為與存取控制皆不得改動。`frontend/src/api/index.js` 以既有 axios/BFF abstraction 加入 `bffApi.openApi.contract()`，從 `/bff/open-api/contract` 取得 text；view、utility 與測試不得 raw `fetch`、讀取靜態 `/docs`、呼叫 port 9090、對每個 endpoint 逐一 request、提供 Try it、產生 curl/request body、輸入 token 或執行任何 API。

- [ ] **384.5 動態 Swagger 解析與展開 UI。** 新增無副作用的 YAML parser utility，從載入文字驗證 `openapi`、`info`、`servers`、`paths`、`components`，只在頂層 `paths:` 區塊抽取 path 與 HTTP operation，並保存每個 operation 的原始 YAML fragment。title、version、server URL、operation 總數、method、path、summary 與每個 operation 的細節均由載入契約取得；不得在 runtime 寫死目前 13 筆、路徑、method、摘要或版本。缺必要區塊、沒有可辨識 operation、或結構錯誤時必 fail-closed：清空舊列表並顯示可理解錯誤，不得備援成手寫或舊清單。畫面以 method badge、path、summary 的可展開列表顯示每個 operation；展開內容顯示其 exact YAML fragment（description、parameters、requestBody、responses、schema reference），另提供完整原始 YAML 的唯讀檢視，讓 `components` 也可閱讀。提供 loading/error state、窄螢幕可讀版面，以及「僅文件瀏覽、實際 API 受 loopback/Tailscale 契約受眾限制」提示；特別以非互動文字提醒既有 crawler rescan POST 有外部抓取副作用。

- [ ] **384.6 測試。** 新增 frontend parser tests：最小合法 fixture 驗證 title/version/server/path/method/summary/response fragment、malformed input fail-closed，並直接讀取現行 `docs/openapi/docker-external-api.yaml` 驗證其目前 13 個 path/method（12 GET、1 POST）全數被辨識。新增 BFF service/controller tests，驗證 classpath resource 內容、exact GET URI、`application/yaml; charset=UTF-8` 與 controller delegate-only 行為。將新 frontend test 納入既有 `npm test` script；不得新增 YAML/Swagger runtime dependency。

## 驗證

~~~bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
npm --prefix frontend test
npm --prefix frontend run build
docker compose -p asset-management build bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
docker compose -p asset-management ps bff frontend
OPENAPI_VERIFY_DIR="$(mktemp -d)"
trap 'rm -rf "$OPENAPI_VERIFY_DIR"' EXIT
docker compose -p asset-management cp bff:/app/app.jar "$OPENAPI_VERIFY_DIR/"
unzip -p "$OPENAPI_VERIFY_DIR/app.jar" BOOT-INF/classes/docker-external-api.yaml | cmp -s - docs/openapi/docker-external-api.yaml
~~~

以實際 Compose stack 驗證未登入請求 `/api/bff/open-api/contract` 仍被登入流程保護；完成登入後在 `/open-api` 確認側欄項目存在、operation 總數與 YAML 動態相符、至少一個 GET 與既有 POST operation 可展開看到原始 Swagger fragment、完整 YAML drawer 可開啟。瀏覽驗收不得送出任何 port 9090 POST，尤其不得送出 crawler rescan。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
