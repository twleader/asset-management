# [t378] 9090 指定年份完整交易日曆 API

**對應 Requirements:** Requirement 113（一次指定一個年份，回完整台／美／英日曆、假日、交易旗標與市場狀態）
**前置任務:** t377（共用 OpenAPI-to-Markdown generator 與 11-route manifest）
**Liquibase changeset:** 無

## 背景

交易日曆畫面目前透過登入 BFF 將 `holidays` map 和當下 `marketStatus` 合併，月格的每一天由前端自行以週末和各市場假日 map 重算。9090 沒有這項 API，因此外部 consumer 無法一次取得一個完整年度，也無法分辨台股假日 authority 讀取失敗與真的休市。

本任務新增 global、無 tenant 的 market-data API，回全年 typed day rows。它只能查一個受現有頁面來源支援的年份，並保留 authority unavailable = unknown 的語意；不是交易、匯出或排程入口。全 branch 最終 gateway manifest 為 12 條 exact 9090 route（11 GET、1 POST）；本任務新增其中 `trading-calendar` 一條。

## 要做什麼

- [ ] 378.1 **新增精確 9090 route。** 新增 `GET /api/public/trading-calendar?year=YYYY`，operationId `getPublicTradingCalendar`；更新 gateway exact Nginx location、BFF SecurityConfig exact GET permitAll、Tailscale Serve config/preflight/mock、frontend exact deny、OpenAPI parity test，以及 `CLAUDE.md`、`spec/steering/structure.md`、`spec/steering/tech.md`、`scripts/README.md`、`.agents/skills/run-stack/SKILL.md`、`INSTALLATION.md` 的 current count/path list，使最終 manifest 精確為 11 GET + 1 POST。不得有 wildcard、dynamic path、month/range query、export/schedule/filesystem route、root proxy、Swagger UI、Funnel 或 host port；non-GET 405 `Allow: GET`，unknown/descendant/trailing/matrix 404。

- [ ] 378.2 **year validation 與 fail-closed boundary。** `year` required，僅一個 value，四位 integer。BFF `TradingCalendarYearWindow`（Asia/Taipei Clock）接受 currentYear-1/currentYear/currentYear+1；格式、空白、多值或範圍外回 sanitized 400，無 outbound request。200 必帶 `availableYears`, `minYear`, `maxYear`。不得默認當年、代 caller 填值、改查多個年份或讓 `year` 進外部 URL 前未驗證。

- [ ] 378.3 **business typed calendar read service。** 新增 container-only exact business calendar read endpoint `GET /internal/public-market-data/trading-calendar?year=`；它不得被 `MarketDataBffRoutes`（`/api/market-data/**`）、frontend proxy、public gateway 直接接住，也不得用 `/internal/public-market-data/**` wildcard 放行。controller 只 validation/delegate，service 只讀既有 `MarketDataService.getTwHolidays/getUsHolidays/getUkHolidays` 和既有 `StockPriceService.getMarketStatus`。建立所選年份 `days`／`holidays` 時，三個 holiday authority 各讀一次並 defensive copy，從該三份年度 snapshot 生成全年 day rows；`marketStatus` 是獨立的既有當下 session read，不承諾與年度 authority 合計只呼叫一次。不得逐日 authority／外呼。台股沿用既有 TWSE 優先／DGPA 暫行 source-aware reader，美／英沿用既有 NYSE／LSE rules；不得改成只判周末。台股 authority map 空／source failure 為 `UNAVAILABLE`，day trading flag/count 固定 null；美／英正常 rules 仍可 AVAILABLE，不能因一市失敗丟棄另兩市。不得觸發 price refresh、broker、trade、calendar export/schedule/notification、DB/Redis write 或 external sync job。

- [ ] 378.4 **凍結 typed response shape。** `PublicTradingCalendarResponse` required fields 是 `year`, `generatedAt`, `timezone`, `availableYears`, `minYear`, `maxYear`, `markets`, `availability`, `tradingDayCount`, `holidays`, `days`, `marketStatus`。`generatedAt` 是 Asia/Taipei ISO offset timestamp、timezone exactly `Asia/Taipei`; days are 365/366 dates ascending, each `TradingCalendarDay(date, weekday 一～日, isWeekend, nullable twTrading, nullable usTrading, nullable ukTrading, nullable twHoliday, nullable usHoliday, nullable ukHoliday)`。`CalendarHolidaySet` has typed sorted `tw/us/uk CalendarHoliday(date,name)[]`; `CalendarTradingDayCount` has nullable `tw/us/uk` counts; `CalendarAvailability` has three typed authority statuses/sources/sanitized nullable messages; `CalendarMarketDefinition[]` exactly TW/US/UK in this fixed order and exactly these definitions: TW=`Asia/Taipei`, `daylightSavingSupported=false`, `09:00–13:30`; US=`America/New_York`, `daylightSavingSupported=true`, `09:30–16:00`; UK=`Europe/London`, `daylightSavingSupported=true`, `08:00–16:30`; each still has `code`, `displayName`, `exchange`, `timezone`, `regularTradingHours`, `daylightSavingSupported`。`CalendarMarketStatus` has three typed `MarketSessionStatus(marketOpen, localTime, displayTradingDate, timezone)`. No date-key map/free-form object or owner data. Under AVAILABLE, each flag means weekday and not holiday; under UNAVAILABLE it is null, never false.

- [ ] 378.5 **no-tenant BFF boundary and error handling。** Public BFF controller only delegates a service using an explicit no-tenant WebClient to the container-only business endpoint and deletes Reactor caller identity; it does not use configured-admin bootstrap, direct DB, business generic browser routes, or caller headers. Calendar is global market data: response must not include user/account/broker/asset/transaction/snapshot/export/Drive data. sanitize business non-2xx/decode to 502, transport 503, timeout 504; no HTML/upstream URI/exception content. A per-market authority unavailable is valid 200 typed partial data, not a generic BFF failure.

- [ ] 378.6 **OpenAPI and generated standards document。** YAML must document `year` required/single/three-year window, all classes/properties/array items/enums, holiday source/provenance, nullable unknown flags/counts, exact yearly ordering, public no-tenant and no-side-effect boundary, and 200/400/502/503/504. Run the standard YAML generator to update `docs/openapi/9090-api-swagger.md` and overwrite `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`, byte-identical; `--check` must validate both and all OpenAPI descriptions.

- [ ] 378.7 **tests, Docker and runtime validation。** Test BFF no outbound on invalid years, correct URI/header/context; business leap/non-leap day count/order, weekday/holiday flags, known three-market data, partial authority unavailable null semantics, no writes; gateway/Tailscale route/404/405 and final 12 route manifest; OpenAPI/generator checks. Run target Maven tests then use run-stack from main to build/recreate business-services, bff, api-gateway. Runtime GET an allowed year through 127.0.0.1:9090; assert all 365/366 rows, availability, holiday arrays, status fields and 400 invalid year. Record DB/Redis before/after readback showing no mutation. Do not POST crawler rescan.

## 驗證

```bash
bash scripts/spec-check.sh
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
docker compose -p asset-management build --no-cache business-services bff api-gateway
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff api-gateway
curl -fsS 'http://127.0.0.1:9090/api/public/trading-calendar?year=2026'
```

## 完成報告

（實作者做完後回填：實際改動、calendar authority／unknown tests、OpenAPI／Markdown checks、Docker runtime payload 與 zero-mutation evidence。）
