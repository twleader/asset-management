# API 實作驗收清單

本表是交接給資產管理系統的驗收要求。文件範例驗證已執行；以下生產驗收尚未執行。

## A. 路由、權限與唯讀

1. 本機 9090 與 Tailscale 同名 exact GET 都成功；BFF、gateway、frontend 與 OpenAPI 路由集合一致。
2. 非 GET 回 405＋Allow: GET，尾斜線、子路徑、matrix 變體 404。
3. 未知／重複 query、GET body、錯誤 date/slot/hash/email、evidence 缺 packageId/sourceId 都回 400。
4. 不帶 email 選 configured-admin；帶 email 選該 ACTIVE owner。cookie、X-User-* 不得暗改 owner。
5. owner 不存在／停用／lookup timeout 用相同 503；跨 owner package 查詢 404，不外洩存在性。
6. GET 不呼叫 broker/vendor/refresh/export/LLM、不寫 DB/Redis／lastRead，不更新權威持倉或資金。
7. 冷啟動無結果回 503，不等待同步重算；並發 Claude/Codex GET 不重做計算。

## B. 來源一致與狀態

8. snapshot/live ID、日期、holding 集合、市場、代號或股數不一致，拒絕發布。
9. 八項對帳全部存在且不重複；保留負在途款與 nullable 銀行列；未知產品不得靜默刪除。
10. 發布時 DB/Redis revision 改變，取消並重算；不得發布半套或回較舊快取 revision。
11. 同一 packageId 的 context/evidence 永不變；修正資料另建 package。
12. 政策未知不能 echo request hash 回成功；registry 更新是離線維護，不是 GET side effect。
13. latest 的日期／時段／policy／來源仍有效才回 CURRENT；過期 409、無法核對 503。
14. pinned 舊套件可回 200 STALE／UNKNOWN 供稽核；adapter 不採作本輪計算。
15. 未來 dataAsOf、capturedAt > generatedAt、generatedAt > checkedAt 一律拒絕；日期時區明確。
16. AVAILABLE source 必須可回放，body hash 與 context 內相同；MISSING 為 null＋原因。
17. schema 拒絕未知欄位、錯誤 enum、boolean 金額、NaN、Infinity、科學記號、負零與 null 假成功。
18. 有 unavailable Metric／缺列／未映射不能 COMPLETE；部分失敗可交付其他模組。

## C. 公式與差分

19. 使用原 SRPP reference calculator 產生凍結輸入的預期結果，再獨立驗證新實作；不可新舊共用同一份待驗程式作 oracle。
20. 資產金額對帳沿用原 tolerance；股數與身分精確比較。null metadata、負待付款、美元等值不得遺漏或重乘。
21. 配置分母為同一 live 資產；涵蓋持有與政策目標聯集。跨市場同代號不合併，未映射不猜零。總曝險差距不得當核心可買容量。
22. 來源收益加總／永久定存續存利息／稅後值與 reference 比對；負數退稅、跨券商健保合併、存款上界、缺給付日、缺 ETF 組成皆有案例。
23. funding 分清 TWD 與外幣等值、負／正在途款；低於定存底線但總存款仍有 headroom，不得因此授權交易。
24. 對 calculationDate≠tradingDate 明列 PARTIAL；不得更換日期卻沿用舊 inflation 值。
25. 技術資料需足夠完成交易日窗口；週末／假日／除權息／分割／資料回補／未來或重複 bar 有案例。
26. D-170 的 20 日還原收盤高點不得當 D-174 watch high-water；不回 CORE_SELL_READY，不共用 Claude/Codex state。
27. Decimal 精度與 rounding 以 reference 規則為準；逐欄界限、0/null、正負號、原因碼都比，禁止廣泛 approximate tolerance 掩蓋差異。

## D. 未來 SRPP 接入驗收（本次不執行）

28. 先 shadow；任一 API 不存在／409／503／504／非 JSON／hash 不符，原流程仍可完成原本能完成的報告。
29. 不增加新 L0；原 calendar、資產、行情、五檔、雷達、資金及狀態停止條件完整保留。
30. 原 assets API 在 package 發布後更新時，不能沿用舊依賴計算；保留當輪原算法。
31. API context hash 與原 INPUT_SNAPSHOT_SHA256 明確區分；同 policy hash 不算同輸入。
32. 兩個執行端×兩時段×核心／附錄的完整格式與 Gmail MIME 讀回仍通過。
33. 稅務或技術資料缺漏，不得造成其他已驗證模組或整份核心等待至逾時。

## E. 效能與部署證據

34. 30／100 持股與目標的固定資料量，量測 cold/ready/stale、producer、模組、GET p50/p95；標記硬體與版本。
35. 驗證多次／並發 GET 無計算或外部抓取；目標 summary p95≤2 秒、evidence p95≤5 秒為待驗目標。
36. DTO、BFF strict validator、OpenAPI、catalog、gateway、Tailscale、fixtures 一起變更；確認公開 API 不是只在 backend 成功。
37. 需要結果表時，驗 DB migration/schema；快取表不改權威持倉、交易或銀行資料。
38. 生產 Docker 與端點 smoke test 完成後，另報「已部署」。文件／單元測試不能冒充實際上線。
39. **依使用者指定，本階段所有 SRPP 檔案、Swagger 鏡像與排程均不修改。** 待 API 完成再另做接入。

## 文件驗證器的界線

`validate_contract.py` 驗證 OpenAPI、YAML/JSON 相同、14 份合成範例、來源/context 雜湊與惡意／矛盾資料拒絕。

範例的 source body 刻意使用精簡合成格式，政策為 SYNTHETIC_TEST_ONLY，技術日曆是合成 weekdays。生產 evidence 必須保存實際來源契約的完整 payload；實作測試須另外使用真實 schema、合法交易日曆及已去識別化固定 snapshot。

驗證器不是生產判斷引擎，不查目前日曆、不連 API，也不證明 Java／Python 全部金融公式等價。
