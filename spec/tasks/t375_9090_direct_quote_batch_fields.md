# [t375] 9090 報價直接提供批次可讀的最佳五檔、完整行情與股利歷史

**對應 Requirements:** Requirement 110（既有公開報價回應以純讀方式追加 direct market-data projection）
**前置任務:** t372、t373
**Liquibase changeset:** 無

> **Current-state override — Requirement 114／Task 379：**本檔原本「僅 FUBON_BOOKS 可以投影 direct bidLevels／askLevels」與「source 非 Fubon 必回空」的 source-only 規則已被取代。現行 direct fields 可從同一 immutable、available、complete canonical snapshot 的 FUBON_BOOKS 或 YAHOO_TW 投影；兩邊均必五筆正價／正量、排序正確且不重複，否則皆回空。OpenAPI 的 class／attribute description 義務也擴及**全部** 9090 operations、response classes、nested classes 與 properties，不能僅補 quote 欄位。raw 19 欄、property order、純讀、零 request-time vendor I/O、無個人資料及禁止估值／下單／警示的規則不變。

## 背景

目前 `GET /api/quotes` 與 `GET /api/quotes/one` 已能透過 BFF 回傳 19 個 raw 報價欄位，並在 `marketData.quoteDetail` 讀到富邦十秒快照的最佳五檔；DB canonical 與 dedicated Redis `price:quote-detail:{market}:{code}` 均已有資料。然而批次 consumer 的固定契約是在每一報價 row 直接讀 `bidLevels`／`askLevels`，因此看不到巢狀的 paired `levels`，把可用的富邦資料誤判成最佳五檔缺失。

同一 public response 已有行情畫面使用的完整 `QuoteDetail` 與 DB-only 十年 `DividendHistory`，但也只藏在 `marketData`，且股利畫面的年度小計／由除息日昨收價推導的現金殖利率要由前端再算。此任務要在不改 producer、不新增外呼、不開新 API path、不暴露個人資料的前提下，將同一 immutable child snapshot 直接投影給 batch reader。

**交付順序與自足驗收：**t375 先建立所有 9090 變更共用的 YAML-to-Markdown foundation，並在既有 **9** 條 manifest（8 GET、1 POST）上完成 direct quote contract；它不新增路徑。之後 t376 將 9→10、t377 將 10→11、t378 將 11→12。四項可在同一 feature branch 一次交付，但每個 task 的 OpenAPI、generator、路由計數與 runtime 驗證都必以自己的里程碑為準，禁止前置或預期後續 task 尚未產生的 route／script。

## 要做什麼

- [ ] 375.1 在 BFF 的 `DetailedLatestQuote` 固定保持原始 19 欄及其順序、既有 `marketData` 和其四個 child 不變；只在它們之後依序追加 required、non-null 的 `quoteDetail`、`bidLevels`、`askLevels`、`dividendHistory`。不得改 path、query parameter、controller、gateway、Tailscale、Nginx、raw cache miss 204、raw list order、timeout、8 列併發上限或任何 raw field 值。

- [ ] 375.2 讓 direct `quoteDetail` 與 `marketData.quoteDetail` 使用同一個 normalized immutable `QuoteDetail` 值，保留其現有完整欄位：support／availability／source／message／sourceTime／fetchedAt／market status、成交／昨收／開高低／均價／漲跌／漲跌幅／總量／昨量／成交額（億）／振幅／內外盤與百分比／五檔小計／paired `levels`。不能再查一次 business endpoint，也不能由 generic raw price、Yahoo、display price 或個人資料拼出值。

- [ ] 375.3 新增 typed BookSideLevel(BigDecimal price, Long size)，top-level bidLevels、askLevels 永遠是 non-null array、每側最多五筆。Requirement 114／Task 379 取代原 source-only 限制：只有 direct quoteDetail.available=true 且 source 精確為 FUBON_BOOKS 或 YAHOO_TW，並且同一 immutable levels 是完整五檔（五個 level、所有 bid/ask 正 price／正 lots、bid 遞減、ask 遞增、無重複）時才可投影。任一 source 不完整／零量／缺值／亂序、unknown source、unsupported、unavailable、0000 或非台股時兩側皆 []。絕不可用 buyPrice／sellPrice、估算價、另一側或跨來源偽裝／補齊五檔。

- [ ] 375.4 將 BFF 的 `DividendHistory` 正規化為 immutable direct／nested 同一值，新增 required non-null `annualSummaries: List<AnnualDividendSummary>`，並在每個既有 `DividendRow` 追加 nullable `cashYieldPct`。row 的 `cashYieldPct` 只有 `previousClose > 0` 時是 `(cashDividend null 視為 0) / previousClose * 100`，以 `BigDecimal` scale 6、`RoundingMode.HALF_UP`；既有 `yieldPct` 原值不改。`AnnualDividendSummary` 只有 `Integer year`、`BigDecimal cashDividend`、`BigDecimal stockDividend`、nullable `BigDecimal cashYieldPct`；每年由 event year 分組且新年在前，cash／stock null 視為 0 累加，年度殖利率以同年 anchor date（除息日優先、無除息日則除權日）由新到舊第一筆 `previousClose != null` event 作候選分母，只有該候選值 `> 0` 才計算，否則 `cashYieldPct=null`，不得改用較舊正值。event row 必保留 `year`、`cashDividend`、`stockDividend`、`exDividendDate`、`yieldPct`、`cashPaymentDate`、`stockPaymentDate`、`fillDays`、`previousClose`、`exRightsDate`。child unavailable 或 DB 沒資料時 `rows=[]`、`annualSummaries=[]`，source/message 仍為既有 typed fallback。

- [ ] 375.5 direct fields 只能使用既有四 child 的單次並行 fetch 結果；不得增加 BFF→business／external request、retry、WebClient、timeout、DB／Redis direct access、cold sync、projection、HTTP outbound、broker request、Yahoo request、tenant／configured-admin context 或 write side effect。每個 row 的 direct object 與 nested counterpart 必須在成功與 fail-soft 時同時一致，任何 child timeout／schema failure 只降級其 direct／nested pair，不得隱藏有效 raw quote 或其他 list row。

- [ ] 375.6 **建立 OpenAPI-to-Markdown foundation 並更新 direct quote contract。** 更新 `docs/openapi/docker-external-api.yaml`、Ruby gateway schema assertion，並建立 deterministic `scripts/render-9090-openapi-docs.rb`（Ruby stdlib、支援 `--check`）：本 task 的 path/method baseline 精確為既有 9 條（8 GET、1 POST），但所有可達 operation／input／response／schema／property／array item／enum 都必有具體 description。`DetailedLatestQuote` property order 是 raw 19、`marketData`、`quoteDetail`、`bidLevels`、`askLevels`、`dividendHistory`；所有新增 property required，兩個 side arrays 引用 typed `{price,size}` item；`DividendHistory` 包含 required `annualSummaries`，event row 包含 nullable `cashYieldPct`，summary schema 的 fields／nullable／array items 全部寫明。由同一 YAML 重產 `docs/openapi/9090-api-swagger.md` 並覆寫 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`，兩份 bytes 必相同、`--check` 必驗證文件與所有 attribute description。後續每一 9090 task 必重用並更新這個 generator，不得使用 loose object、map 或補增個人資料欄位。

- [ ] 375.7 在 PublicQuoteMarketDataServiceTest 或等價 BFF tests 驗證：完整 raw 19 個欄位與原 marketData prefix；direct quoteDetail／dividendHistory 對 nested counterpart 的值一致；完整 FUBON_BOOKS 與完整 YAHOO_TW levels 都變成正確 ordered {price,size}；非台股、unknown source、unavailable、partial／zero／亂序 side 均回空，且沒有多一個行情 request；多筆同年 dividend event 的 row yield、年度 sum／第一筆非 null 分母的畫面同口徑、empty／無分母；list order、one cache miss 204、不含 configured-admin／持股／帳戶／成本／交易／快照欄位。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
docker compose -p asset-management build --no-cache bff
docker compose -p asset-management up -d --no-deps --force-recreate bff
curl --fail --silent --show-error --max-time 10 'http://127.0.0.1:9090/api/quotes/one?code=00697B&market=台股'
curl --fail --silent --show-error --max-time 10 'http://127.0.0.1:9090/api/quotes?market=台股'
```

實機 readback 必須對一個非 `0000` 台股證明：原 19 個 raw field、`marketData` 四個 child、top-level `quoteDetail`、non-null `bidLevels`／`askLevels`、top-level `dividendHistory` 和 `annualSummaries` 都存在；available book 的 source 是 approved `FUBON_BOOKS` 或 `YAHOO_TW`，兩側 `{price,size}` 可直接累計；回應沒有個人資產資料。Docker rebuild 與 recreate 必須從已合併的 main worktree 執行，只重建受影響的 `bff` service。

## 完成報告

（實作者做完後回填：實際改了哪些檔、驗證輸出、與原計畫的偏差及原因。）
