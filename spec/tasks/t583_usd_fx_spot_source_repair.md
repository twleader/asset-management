# [t583] USD即期匯率原始來源修復、兩年回補與共用API

**對應 Requirements:** Requirement 206（使用者新增要求實際補齊匯率資料）
**前置任務:** t582
**Liquibase changeset:** v1.165.0-usd-fx-source-capture.sql（若撞號採下一可用版本）

## 背景

使用者追加「把匯率資料補齊」，明確核准實際資料來源修復／兩年回補；取代t582暫不修管線的限制。現有FinMind parser優先cash_buy/cash_sell，DB沒有basis/source，public匯出只把欄名寫即期，無法驗USD兩年spot範圍。官方FinMind文件分別定義cash與spot欄。已實讀TaiwanExchangeRate USD 2024-11-08..2026-11-13：495唯一合格spot列，HTTP200，原始SHA2b271509afc67b01784af5880a4ec474fb8e00d4584303dcc59c48840a19dce7。不能以舊JSON標題或UNKNOWN永久停工。

## 要做什麼

- [x] 583.1 新normalized immutable exchange_rate_source_capture(id,provider,rate_basis,source_url,captured_at,source_sha256,raw_body TEXT)，僅同provider/basis/rawSHA/sourceURL/capturedAt的同一實際fetch snapshot可重用；history只增加source_capture_id nullableFK及source_member_sha256稽核收據，舊NULL保持UNKNOWN。capture原文UTF8 SHA需重算一致，來源URL不含token，禁止UPDATE/DELETE，不新增清理。migration幂等／列約束，版本先檢查避撞；原migration不改。
- [x] 583.2 writer/backfill建立收據前從raw再核對currency/date/native spot買賣值；FinMind data[].currency/date/spot_buy/spot_sell；BOT CSV指定幣別欄3/13且date為同次觀察日；MEGA rates[].currKey/spot.bid/ask/update日期。不是只讀basis字串。同batch原文parse一次，backend metadata entity不映射raw_body，history relation EntityGraph僅預載輕量metadata，reader重算member收據，提供getRateBasis/getSource/getSourceSha256/getFetchedAt/isSpotBasisVerified；無原文或錯hash/value/source不可verified。publicDTO不洩rawbody/內部id/secret。
- [x] 583.3 USD FinMind只取spot_buy/spot_sell正值且sell>buy，缺值／負值／cash-only／buy=sell不補成spot；非USD原價格行為保留且未驗spot不標verified。保存實際rawbody與sourceURL/capturedAt。BOT／MEGA歷史writer同樣帶原文，保留FxSpotQuote2/3參數constructor，歷史持久化才storecapture；2秒livepoll／GET不新寫capture。Yahoo中間價及legacy無證據upsert不可降級既有verifiedSPOT。USD17:00改force兩曆年+一曆月邊界窗口（仍原排程一次GET、明確end_date=台北今日）以補正當日已有intraday列，其他幣別增量流程不變。
- [x] 583.4 原GET /api/public/exchange-rate/usd-twd改兩曆年，原fields保留；historypoint新增rateBasis、source nullable、sourceSha256 nullable、sourceMemberSha256 nullable、fetchedAt nullable、spotBasisVerified。UNKNOWN原樣披露。requestedrange內history日期仍升冪去重，新增nullable historyBoundary提供起點前最近來源已驗證的native列，若start已有已驗證列不重複boundary；較近UNKNOWN假日列不得遮蔽較早qualified列；所有GET只讀，不refresh/vendor/寫DB。OpenAPI完整schema同步兩mirror及SRPPmetadata，無新endpoint；與t582V28一次identity發布。
- [x] 583.5 稽核腳本預設dry-run，從原始FinMind原文+收據驗SHA/去密URL/USD唯一日期/日期範圍/spot值，--apply才單交易存capture+授權USD窗口upsert。正式寫入等根agent協調review/Git落地／migration部署後才執行。邊界至少覆蓋windowEnd.minusYears(2)以前最近可信日，最多至當輪台北17:00時鐘截點內最大qualified native日期D，TW完成日僅為獨立最低新鮮度而非銀行日期上限，不猜缺銀行日；只宣稱原始回傳495列及跨度，不冒稱獨立證明全年逐營業日齊備。回讀原文spot每列＋captureFK，核對窗口外fullfingerprint不變；同raw重跑冪等，不改capture。
- [x] 583.6 新匯出包含basis/provenance，同一canonical資料供雷達/API/報告；十年legacy部分保持UNKNOWN、實際修復兩年才verified。保留舊SRPP data/input/台幣兌美元_1_20261114.json原bytes，另產時間戳修復後JSON或API收據，不改名冒充原run。metadata來源與pricebasis不同拒絕共同FX工具採用；當前quoteLIVE仍獨立驗證，週末HISTORY不冒LIVE。
- [x] 583.7 測cash/spot不同時USD選spot、缺spot拒絕／非USD不漂移、rawSHA/value/ticker/date篡改拒絕、legacyUNKNOWN、Yahoo不能降級spot、批次capture重用／immutable／FK／失敗rollback／回補冪等／窗口外不變、兩年與boundary API純讀／嚴格schema／原欄相容。原US D21530項tests保留。先獨立spec審查，再實作及獨立架構／功能審查。
- [x] 583.8 正式順序同已核准雙repo發布：前置離線tests/build→兩repo Git落地→registry→backend migration先部署→external-materials重建/recreate→單交易實際回補→精確API/radar/readback；BFF若改也重建/recreate。actual DB schema變更後重產db/schema.sql並跑driftcheck，必要文件followupcommit不另重建相同runtime。不得寄信／下單／改排程。

- [x] 583.9 Sourcecapture metadata-only entity禁止映射raw_body，EntityGraph不得把完整batchbodyJOIN到每歷史列，也不得readerN+1；原文只producer/backfill parse一次與特定audit查詢使用。history追加source_member_sha256，與FK須同NULL/同非NULL。具名允許密碼學receipt denormalization，不新增金融衍生metric。memberSHA=SHA256UTF8(`FX_SPOT_MEMBER_V1\n`+rawSHA+`\n`+currency+`\n`+ISOdate+`\n`+buyCanonical+`\n`+sellCanonical)，最後無換行；Decimal去尾0plain表示且DBscale4可精確表示。writer/backfill先核原文/native值才存receipt；reader依immutablemetadata與實際row重算receipt。不同語言測同goldentuple，API附nullable sourceMemberSha256。正式回補重算DBrawbody字節SHA，不能只看regex/flag。

- [x] 583.10 實際536canonical列對495原生source列之41UNKNOWN保持原值不刪改，不因legacy週末／合成列存在就永久UNKNOWN。僅在可信FinMind capture exact官方https host/path/api/v4/data、datasetTaiwanExchangeRate、data_idUSD、單一canonicalISO start_date/end_date之閉區間聯集覆蓋[start,D]、每採用native成員rawSHA/日期/價格receipt與capture一致、qualified boundary/latestD成立，才排除UNKNOWN價格並計excludedLegacyRows。沒有完整querycoverage就UNKNOWN；claimedverified但hash錯不可靜默忽略。這不是全銀行交易日證明。NATIVE_QUERY僅FinMind可提供coverage；銀行OBSERVATION query起迄NULL、不可橋接coverage，但具完整FinMind coverage時真銀行SPOTmember可作價格與latestD。
- [x] 583.11 PublicResponse新增historySourceCaptures陣列exact metadata{source,sourceKind,sourceSha256,sourceUrl,requestedStartDate,requestedEndDate,fetchedAt,rateBasis}，從採用verified rows及boundary之immutablemetadata去重（相同rawSHA/source同capture）；保留原history全列與品質，不新增DBid/rawBody或另份價格。sourceURL精確scope檢查、token不得存在；sourceMemberSha256及rawcaptureidentity在共同工具再次驗。native495極值成立且41legacy排除值不參與、gap query interval／錯data_id／重複query參數／不合格source／已驗成員被篡改的反例要測。

## 驗證

```bash
bash scripts/spec-check.sh main
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f external-materials-service/pom.xml -Dtest='*ExchangeRate*,*FxProvider*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*ExchangeRate*,*UsdTwd*,UsTacticalCalculatorTest' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest='*UsdTwd*' test
ruby scripts/tests/docker-external-api-openapi-test.rb
```

部署由根agent協調從乾淨main執行必要business-services、external-materials-service、bff之build/recreate；先migration後producer。回補只在已合併受審工具--apply執行，保存before/after/audit/rawhash與實際APIJSON。schema更新後：

```bash
bash scripts/tests/schema-sql-drift-test.sh
```

## 完成報告

2026-11-14已完成實作、審查及正式495筆原生SPOT回補；491筆兩年窗口公開API與原文逐筆／member SHA獨立核對PASS，41筆舊UNKNOWN與窗口外資料保留。三服務健康、schema120表逐位元同步；正式交易日時窗採用另記NOT_YET_EXECUTED。完整證據：[發布驗收](../../docs/reviews/t582_t583_d215_fx/release-20261114/README.md)。


### 根agent功能QA日期修正（ARCH第二輪範圍）

實際原文與當輪日曆證明2026-02-12／13、07-10、10-23為TW股票休市但存在原生銀行FX且US開市。2年FX須在台北17:00時鐘截點內取最新qualified native日期D；完整query coverage到D，D落後獨立TW最低完成日則UNKNOWN，currentLIVE／future-capture與舊5年score截點均不變。以02-13晚盤包含12／13新極值、17:00前最多12、lag落後TWminimum反例驗證。根agent已核准此具體bug修正；ARCH1 PASS後ARCH2審delta，無第四輪spec cycle。
