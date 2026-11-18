VERDICT: CHANGES_REQUESTED

## 判定
critical: 1   major: 0   minor: 1

## Findings

### [Critical] 新增段落將未知值契約誤寫為 `quality=null`，與 producer、BFF 及 OpenAPI 均衝突

位置：`spec/requirements.md:7184`、`spec/design.md:14199`、`spec/tasks/t604_srpp_d222_report_wire.md:15`。

證據：三處均寫「未知值仍按原quality=null契約」，但目前實際契約是：

- `backend/src/main/java/com/steven/assets/service/srpp/report/SrppReportModuleCalculator.java:317`：`quality` 寫入 `value==null?"UNAVAILABLE":"EXACT"`；未知值另寫入 `reason="FIELD_SOURCE_UNVERIFIED"`。
- `bff/src/main/java/com/steven/assets/bff/publicsrpp/SrppReportResponseValidator.java:259`：`quality` 必須是非空字串，且屬於 `QUALITIES`。
- 同檔第 260–261 行：`quality="UNAVAILABLE"` 必須與 `value=null` 對應，且 `reason` 不得為 null。
- `docs/openapi/docker-external-api.yaml:5085`：`quality` 為 `type: string`，enum 僅有 `EXACT`、`UNAVAILABLE`、`LOWER_BOUND`；第 5087 行明寫 `UNAVAILABLE` 對應 `value=null`。

被違反的出處：上述既有 wire 契約，以及本輪 `spec/requirements.md:7182`、任務檔第 13 行要求保留現行 quality 規則的限制。按審查判準，「原契約為 quality=null」屬對現況的錯誤斷言；若依此放寬 validator 或 OpenAPI，會改變此次要求保留的契約。

修法：三處統一改為：「未知值維持 `value=null`、`quality="UNAVAILABLE"`，且 `reason` 為非空字串；不允許 `quality=null`。」在 604.3 明列未知值合法案例與 `quality=null` 拒絕案例。

### [Minor] 任務檔仍將 D-222 錯掛到官方成交費用的 Requirement 214

位置：`spec/tasks/t604_srpp_d222_report_wire.md:3`。

證據：

- 任務檔寫：`Requirement 214（實質準備金及市場調節）`。
- `spec/requirements.md:7141` 的實際標題是「Requirement 214：官方交割證據推導成交費用」。
- `spec/requirements.md:7161` 明列 Task 603 為「Requirement 210 修訂」。
- `spec/tasks/t603_srpp_reserve_allocation.md:3` 同樣對應 Requirement 210。

被違反的出處：`spec/tasks/README.md:53` 要求「對應 Requirements」填入實際 Requirement 編號及其摘要；目前編號與摘要指向不同需求。

修法：改為「Requirement 210（退休配置權威同步；Task 603 的 D-222 修訂）之報告事實傳輸相容性」。保留現有 Task 604 子標題，不新增 Requirement。

## 已查無發現

- **機械檢查**：採用本輪提供的完整結果，`BLOCK: 0 / CHECK: 0`；未重跑可能產生檔案或其他副作用的腳本。
- **前輪編號問題**：`spec/requirements.md:7180` 已改為 `#### Task 604`，沒有新增 Requirement 214；前輪的重複 Requirement 標題問題已解除。任務檔殘留的錯誤引用另列 Minor。
- **前輪五個條件式 facts**：已核對三份規格與 `SrppReportModuleCalculator.java:54–59`。五個名稱確實存在，subject、unit、range 及單標的成功仍保留 facts 的描述均有補入；604.3 也已列 PARTIAL 案例。除上述 quality 錯誤外，無發現。
- **可查證性**：已查八個 fact identifiers、兩個測試類、renderer 與公開 GET controller/service，均存在。BFF 與 OpenAPI 尚未加入八個名稱是本任務待修範圍，未誤報為缺陷。
- **內部一致性及資料來源**：已對照 requirements、design、任務檔與 producer；本輪沒有新增行情來源、DB schema 斷言或固定排程時點。除 Findings 外，無發現。

架構鐵則逐條核對如下：

1. Controller 直讀 Repository、外部 HTTP、upsert 或領域判斷：否；規格維持既有 service 委派及傳輸驗證。
2. 新增／修改 DTO 使用可變物件：不適用；本輪沒有 DTO 變更。
3. 新頁面是否同時有專屬 BFF：不適用；沒有新增頁面。
4. 前端跨頁 BFF、直打 resource 或裸 fetch／EventSource：否；沒有前端呼叫變更。
5. 自創跨頁共用例外：否。
6. 同義值是否改走不同 business API：否；沿用同一報告計算包。
7. frontend／business／external-materials 越層：否；規格未新增越層依賴。
8. FK 與字串冗餘或衍生值持久化：否；沒有新增持久化。
9. 新業務分類是否硬編 enum：否；八個名稱是既有 wire facts 的閉合識別碼。
10. 新 endpoint 命名：不適用；沒有新增 endpoint。

## 自我挑戰

- **保留 quality finding**：考慮過它可能只是把 `value=null` 寫錯。然而錯誤在三份文件重複出現，且直接涉及此次要實作的 validator／OpenAPI 邊界；producer、validator、OpenAPI 三者均證明 `quality` 不可為 null，因此不能視為無影響措辭。
- **Requirement 引用僅列 Minor**：重複 Requirement 標題確實已移除；剩下的是任務 metadata 錯引。任務正文與前置 t603 已足以辨識功能方向，因此不沿用前輪 Major。
- **撤下「必須三檔區間都成功才可傳 facts」的疑慮**：producer 第 56 行在各標的迴圈內直接加入成功 facts，第 57 行才因候選不足阻擋 selector。新增的單標的 PARTIAL 描述符合現況。
- **未將新 hash 與舊不可變包列為衝突**：任務第 17、28 行已將正式部署交由 release 協調，並禁止修改舊包；未承諾舊 identity 的包可當作新 identity 驗收。
- 本輪未取得具體的原始失敗包與公開 GET 收據，因此不認定背景所述兩個早報的 502／內部 200 已獲獨立驗證；此限制不影響上述由原始碼及契約直接證實的 findings。