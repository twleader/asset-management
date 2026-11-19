VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：以 `HEAD 2a5dad86f58b774ed61caf2e901bd458e35e2893` 比對目前工作樹，重點審查本輪最終變更：

- 完整讀取 `SrppNamedCashBuyBudget.java`、`SrppReportMediumDipCalculator.java`、`SrppNamedCashBuyBudgetTest.java`。
- 核對 `docs/openapi/docker-external-api.yaml` 與兩份本專案 Swagger Markdown。
- 沿呼叫關係查閱 `SrppReportModuleCalculator`、`SrppReportExtraCaptureAdapter`、`SrppNamedCashRotation`、`SrppNamedRadarSellCalculator` 與 BFF `SrppReportResponseValidator`，對七支相關 production Java 搜尋架構禁用依賴及副作用。
- 使用 `git status --short` 納入 untracked 計算器，沒有把它們漏在 tracked diff 之外。

判準僅採本次完整讀取的 `CLAUDE.md` 與 `spec/steering/structure.md`。全程只讀，未修改檔案、執行測試、呼叫 API 或操作部署。

## Findings

無。未找到同時符合「本輪新增或擴大、具可驗證程式證據、違反兩份判準明文」的架構違規。

前輪為零 findings，沒有待修項目可逐項結案。本輪另行查證了作者列出的最終調整，未直接沿用前輪 PASS 作為證據。

## 已查無發現

**最終變更的實際查證**

| 項目 | 證據與結果 |
|---|---|
| linked pending／reservation 不忽略較大承諾 | `SrppNamedCashBuyBudget.java:50` 保存 reservation 的 `amount + fees`；`:53` 使用 pending 含費扣款 `.max(linkedDebits.getOrDefault(...))`，再單次 `committed.add(debit)`。linked reservation 不進入 `:51` 的未連結累加分支，因此這條路徑取較大值一次。 |
| 目標承諾明確要求文字 TWD | `SrppNamedCashBuyBudget.java:26` 對目標 BUY 要求 `currency.isTextual()` 且等於 `"TWD"`；`:50` 再核對 linked 兩端均為文字 TWD。缺漏或 USD 不會轉成較便宜的 TWD 扣款。 |
| 預算值只投影至 receipt | `SrppReportMediumDipCalculator.java:146` 將 `usedTwd`、`committedTwd`、`remainingTwd` 與 `planEntryDebitTwd` 寫入 `D223_PLAN_BUDGET` 的 `receipt.values`。搜尋該檔未發現將這些值寫入 `module.fact()` 的呼叫。 |
| 保留封閉的 module facts 契約 | `SrppReportResponseValidator.java:28` 的 `STRATEGIC_BUY_GATES` facts 名稱集合仍為既有七項；`:130` 繼續檢查名稱集合。`:306` 則讓 receipt values 沿既有 `fact()` 驗證，沒有為預算值放寬 module facts。 |
| 獨立買方的 15 gates 對齊 | calculator 的 `REQUIRED` 有 13 項；plan 路徑以 `D226_BUY_PATH` 取代 `D215_NAMED_PATH`、加入 `D223_PLAN_BUDGET`，加上 shared currency gate 為 15 項。BFF `:256–258` 使用相同集合並要求 `gates.equals(required)`。 |
| OpenAPI 已記錄策略路徑 | YAML `:4843` 明載獨立 D223 買方、15 gates、receipt values 與 linked 較大扣款去重。兩份本專案 Markdown 在 `:10829`、`:10854` 可找到相同描述；`cmp` 回傳成功，兩份 Markdown 位元組一致。這是既有報告 API 內的策略路徑，沒有新增 HTTP mapping。 |

使用的重點搜尋包括：

```text
rg -n 'linkedDebits|currency|committed=committed.add|usedTwd|removeAll' <budget>
rg -n 'usedTwd|committedTwd|remainingTwd|planEntryDebitTwd|module\.fact|maximumDebitTwd' <medium calculator>
rg -n 'required.remove|D223_PLAN_BUDGET|NAMED_CASH_BUY_D226|values|FACT' <BFF validator>
```

測試原碼亦包含：

```text
SrppNamedCashBuyBudgetTest.java:35: linkedDebitUsesMaximumOnceInBothDirections
SrppNamedCashBuyBudgetTest.java:36: linkedDifferentCurrencyCannotBecomeCheaperTwdDebit
SrppNamedCashBuyBudgetTest.java:38: linkedMissingCurrencyOnEitherOrBothSidesRemainsUnknown
```

以上只確認案例與斷言存在，未宣稱測試已執行通過。

**逐維度架構檢查**

對前述七支 production Java 執行下列搜尋，結果為零命中，並人工核對新增呼叫：

```text
rg -n -i 'https?://|WebClient|RestTemplate|HttpClient|ResponseEntity|HttpServletResponse|JdbcTemplate|EntityManager|\.save(All)?\(|\.delete[A-Z]|redis|place_order|modify_order|cancel_order|ProcessBuilder|Files\.|@.*Mapping|@Scheduled|\b(double|float)\b|^\s*@Data\b|\benum\b' <七支相關 production Java>
```

| 判準／檢查項目 | 查證結果 |
|---|---|
| 模組界線：外部行情直連、反向呼叫、跨服務讀表、券商交易 | 未見本輪新增這些依賴。三支計算器只處理凍結輸入與記憶體計算；本輪無 external-materials、frontend 或券商服務 diff。符合 `structure.md` §8。 |
| Controller／Service 職責 | Controller 無本輪變更；新增金融計算位於 service。七支檔案未命中 HTTP response 型別或 mapping，沒有新增 Controller→Repository 技術債。依 `CLAUDE.md:311`、`structure.md` §2.2 核對。 |
| Repository／Entity／DTO | 相關目錄無本輪 diff，沒有新增 Repository 業務方法、Entity 業務邏輯、持久化 enum 或可變 transport DTO。內部計算結果採 record，未新增 Entity 直接回傳 API 的路徑。 |
| 新頁面專屬 BFF、跨頁呼叫、裸 fetch／SSE | frontend 與路由無本輪變更，未新增頁面或繞過 `api/index.js` 的呼叫；一頁一 BFF 與多 panel 並行載入沒有本輪適用變更。依 `structure.md` §3.2、§5.2 核對。 |
| 同義來源與計算責任 | adapter 重用既有價格還原、雷達及 FX helper；medium calculator 重用回撤與容量 helper。預算、配置與 sizing 留在 business；BFF 增加的是契約驗證，未另建預算計算器。 |
| 正規化與衍生值 | 沒有新增表、Entity 或持久化欄位；本輪移除 module facts 的重複預算投影，保留既有 receipt values 形狀。未新增 FK／名稱冗餘儲存。 |
| 硬編業務分類 | 未新增銀行、券商、存款或市場分類 enum。策略、gate 與來源狀態字串屬報告協定識別，沒有將新增可管理分類寫死成 enum。 |
| endpoint、命名、金額與時間 | 本輪 OpenAPI 僅改版本與既有 schema 描述；沒有新增 endpoint 或軟停用操作。計算使用 `BigDecimal`、`LocalDate`／`Instant`，輸出沿用 canonical decimal 字串，沒有新增 double／float 金額欄位。 |
| Liquibase 與 schema | changelog、master、`db/schema.sql` 無本輪 diff，沒有新增 changeset 的版號碰撞、冪等或註冊問題可查。未使用 changelog 推測 DB 現況。 |
| 即時價、收盤價與缺價處理 | adapter 續用 `PriceQueryService` 與 `StockPriceHistoryRepository`；本輪預算調整未新增價格 I/O 或快取寫入，未見以預設值、`o`、`y` 回寫補價。 |
| 未知來源與交易權限 | budget `:55` 保留 `UNAVAILABLE`、將 committed／remaining 設 null 並清空 entries；caller 要求預算 PASS。沒有把來源未知當成零承諾，也沒有新增送單、通知或交易執行路徑。 |

## 無法查證

- 沒有第二輪提交當時的獨立工作樹快照，因此無法機械重建「第二輪材料 → 第三輪材料」的精確 diff；本輪以相同 HEAD 對目前工作樹及實際檔案查證最終狀態。
- 依唯讀限制，未執行 Maven、generator、`spec-check.sh`、SQL 或任何 POST，未驗證完整 envelope 的執行結果。
- 只確認本專案兩份 Swagger Markdown 相同及指定描述存在，未完成 YAML renderer、跨 repo consumer／bundle、registry／JAR 的完整發布一致性驗收。
- 未連線運行中 DB、官方來源或正式容器。本報告不構成測試、部署、自然時窗產量或 consumer 同輪採納的完成證明。

## 自我挑戰

1. **是否把既有問題算入本輪？** 已以 `git status`、指定目錄 `git diff --name-only HEAD` 與相關檔案 diff 確認範圍，並另外讀取 untracked 計算器。未回報既有 Controller→Repository 或 SSE 基準線。
2. **是否踩到三類誤報？** 未將外層 DTO 容器 class、註解中的 `@Data` 或區域數值運算當成違規；本輪沒有新增相應持久化／傳輸金額欄位。
3. **前輪撤下的 BFF 重算疑慮是否成立？** 本次 `git show HEAD:…/SrppReportResponseValidator.java` 仍可驗證原本 `:265–266` 已有 quantity 與 `limitPrice × quantity == amount` 檢查。這不是本輪新增的金融重算，維持撤下。
4. **純 helper 是否必須另建介面？** 已回查 `structure.md:106` 明文「不替純業務流程或counter增空介面」。本輪 final delta 沒有新增外部 I/O，因此沒有自行要求額外介面。
5. **是否錯把策略路徑當成新 HTTP route？** OpenAPI diff 僅為版本與既有 receipts 描述，未新增 paths；沒有以「新路由缺 gateway／Tailscale」產生誤報。
6. **規範是否確有明文？** 已重新搜尋兩份判準的分層、BFF、正規化、金額型別及模組界線條文。歷史記憶僅用於提醒重新查證目前規範，判定均依本次檔案證據；沒有保留僅憑風格、未執行測試或作者說法推導的 finding。
